#!/bin/bash
set -Eeuo pipefail

REPO_URL="${MUBX_REPO_URL:-https://github.com/mwright228/my.git}"
INSTALL_ROOT="${MUBX_INSTALL_ROOT:-/root/mub-x}"

die() { printf '[!] %s\n' "$*" >&2; exit 1; }
require_root() { [ "$(id -u)" -eq 0 ] || die "Run this installer as root."; }
run() { printf '[*] %s\n' "$*"; "$@"; }

require_root
[ -r /etc/os-release ] || die "Cannot detect the operating system."
. /etc/os-release
[ "$ID" = ubuntu ] && [ "$VERSION_ID" = 22.04 ] ||
  die "This installer targets Ubuntu 22.04 amd64."
[ "$(dpkg --print-architecture)" = amd64 ] || die "This installer targets amd64."

read -r -p "Domain: " DOMAIN </dev/tty
DOMAIN="${DOMAIN,,}"
[[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] ||
  die "Enter a valid DNS hostname."

export DEBIAN_FRONTEND=noninteractive
run apt-get update
run apt-get install -y ca-certificates curl certbot dnsutils lsof psmisc git jq \
  uuid-runtime openssl nginx dropbear squid haproxy openvpn wireguard-tools \
  iptables iptables-persistent qrencode netcat-openbsd

if [ ! -d "$INSTALL_ROOT/.git" ]; then
  run rm -rf "$INSTALL_ROOT"
  run git clone --depth 1 "$REPO_URL" "$INSTALL_ROOT"
fi
cd "$INSTALL_ROOT"

systemctl stop apache2 nginx haproxy xray 2>/dev/null || true
fuser -k 80/tcp 2>/dev/null || true
install -d -m 0755 /usr/local/etc/xray
install -m 0755 bin/* /usr/local/bin/
install -m 0644 configs/dropbear /etc/default/dropbear
install -m 0644 configs/squid.conf /etc/squid/squid.conf
install -m 0644 configs/haproxy.cfg /etc/haproxy/haproxy.cfg
install -m 0644 configs/nginx.conf /etc/nginx/nginx.conf
install -m 0644 systemd/*.service /etc/systemd/system/
printf '%s\n' "$DOMAIN" > /usr/local/etc/xray/domain

curl --fail --location --proto '=https' --tlsv1.2 \
  https://github.com/XTLS/Xray-install/raw/main/install-release.sh \
  --output /tmp/xray-install.sh
run bash /tmp/xray-install.sh install
rm -f /tmp/xray-install.sh

run certbot certonly --standalone --non-interactive --agree-tos \
  --register-unsafely-without-email -d "$DOMAIN"

install -d -m 0700 /etc/openvpn/certs /etc/openvpn/server
if [ ! -s /etc/openvpn/certs/ca.crt ]; then
  run openssl req -x509 -nodes -newkey rsa:4096 -days 3650 \
    -subj "/CN=MUB-X CA" -keyout /etc/openvpn/certs/ca.key \
    -out /etc/openvpn/certs/ca.crt
  run openssl req -nodes -newkey rsa:2048 -subj "/CN=MUB-X server" \
    -keyout /etc/openvpn/certs/server.key -out /etc/openvpn/certs/server.csr
  run openssl x509 -req -days 825 -CA /etc/openvpn/certs/ca.crt \
    -CAkey /etc/openvpn/certs/ca.key -CAcreateserial \
    -in /etc/openvpn/certs/server.csr -out /etc/openvpn/certs/server.crt
  run openssl dhparam -out /etc/openvpn/certs/dh.pem 2048
  chmod 0600 /etc/openvpn/certs/*
fi
install -m 0644 configs/openvpn-tcp.conf /etc/openvpn/server/tcp.conf
install -m 0644 configs/openvpn-udp.conf /etc/openvpn/server/udp.conf
WAN_IF="$(ip -o route show to default | awk 'NR == 1 {print $5}')"
[ -n "$WAN_IF" ] || die "Unable to determine the public network interface."
printf 'net.ipv4.ip_forward=1\n' > /etc/sysctl.d/99-mubx-forwarding.conf
run sysctl --system
iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null ||
  iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE
iptables -t nat -C POSTROUTING -s 10.9.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null ||
  iptables -t nat -A POSTROUTING -s 10.9.0.0/24 -o "$WAN_IF" -j MASQUERADE
iptables-save > /etc/iptables/rules.v4

run /usr/local/bin/generate-secrets
sed -i "s|__DOMAIN__|$DOMAIN|g" /etc/telecom-engine.env
install -m 0600 configs/xray.json /usr/local/etc/xray/config.json
source /etc/telecom-engine.env
sed -i "s|__DOMAIN__|$DOMAIN|g; s|__UUID__|$UUID|g; s|__SHORT_ID__|$SHORT_ID|g; s|__REALITY_PRIVKEY__|$REALITY_PRIVKEY|g" \
  /usr/local/etc/xray/config.json /etc/nginx/nginx.conf /etc/haproxy/haproxy.cfg \
  /etc/systemd/system/dnstt.service

nginx -t
haproxy -c -f /etc/haproxy/haproxy.cfg
xray run -test -config /usr/local/etc/xray/config.json
run systemctl daemon-reload
for svc in nginx haproxy xray dropbear squid; do
  run systemctl enable "$svc"
  run systemctl restart "$svc"
  systemctl is-active --quiet "$svc" || die "$svc failed to start; inspect journalctl -u $svc."
done
for svc in openvpn-server@tcp openvpn-server@udp; do
  run systemctl enable "$svc"
  run systemctl restart "$svc"
  systemctl is-active --quiet "$svc" || die "$svc failed to start; inspect journalctl -u $svc."
done

echo "[+] Core online. Type 'menu'."
