#!/bin/bash
set -euo pipefail
read -p "Type DELETE to remove MUB-X: " c
[ "$c" = "DELETE" ] || exit 0
INSTALL_ROOT="$(cat /etc/mubx/install-root 2>/dev/null || printf '%s\n' /root/mub-x)"
case "$INSTALL_ROOT" in
  /*) ;;
  *) echo "[!] Invalid MUB-X install root." >&2; exit 1 ;;
esac
[ "$INSTALL_ROOT" != "/" ] && [ "$INSTALL_ROOT" != "/root" ] ||
  { echo "[!] Refusing to remove a protected install root." >&2; exit 1; }
[ -d "$INSTALL_ROOT/.git" ] ||
  { echo "[!] Refusing to remove a directory without a MUB-X checkout." >&2; exit 1; }
[ -f /etc/mubx/ownership ] && [ "$(cat /etc/mubx/ownership)" = "MUB-X" ] ||
  { echo "[!] Refusing to remove an unverified MUB-X installation." >&2; exit 1; }
[ "$(git -C "$INSTALL_ROOT" remote get-url origin 2>/dev/null || true)" = \
  "${MUBX_REPO_URL:-https://github.com/mwright228/my.git}" ] ||
  { echo "[!] Refusing to remove an unexpected repository." >&2; exit 1; }
for svc in nginx haproxy xray dropbear squid \
  openvpn-server@tcp openvpn-server@udp wg-quick@wg0 \
  badvpn@7100 badvpn@7200 badvpn@7300 badvpn@7400 badvpn@7500 badvpn@7600 badvpn@7700 \
  dnstt hysteria zivpn mubx-cron.timer; do
  systemctl disable --now "$svc" 2>/dev/null || true
done
restore_path() {
  local path="$1" backup="/etc/mubx/backup$1"
  if [ -e "$backup" ] || [ -L "$backup" ]; then
    install -d -m 0700 "$(dirname "$path")"
    rm -rf -- "$path"
    cp -aP "$backup" "$path"
  else
    rm -rf -- "$path"
  fi
}
for path in /etc/systemd/system/badvpn@.service /etc/systemd/system/dnstt.service \
  /etc/systemd/system/hysteria.service /etc/systemd/system/zivpn.service \
  /etc/systemd/system/mubx-cron.service /etc/systemd/system/mubx-cron.timer \
  /etc/systemd/system/xray.service; do
  restore_path "$path"
done
systemctl daemon-reload
for path in /usr/local/bin/xray /usr/local/bin/hysteria /usr/local/bin/zivpn \
  /usr/local/bin/dnstt-server /usr/local/bin/dnstt-client /usr/local/bin/badvpn-udpgw \
  /usr/local/go; do
  restore_path "$path"
done
for backup in /etc/mubx/backup/usr/local/go-*; do
  [ -e "$backup" ] || continue
  restore_path "${backup#/etc/mubx/backup}"
done
for path in /usr/local/bin/menu /usr/local/bin/link-gen /usr/local/bin/add-user \
  /usr/local/bin/delete-user \
  /usr/local/bin/mubx-probe /usr/local/bin/set-domain /usr/local/bin/mubx-cron \
  /usr/local/bin/generate-secrets /usr/local/bin/uninstall.sh; do
  restore_path "$path"
done
restore_path /usr/local/lib/mubx/common.sh
WAN_IF="$(cat /etc/mubx/wan-interface 2>/dev/null || true)"
if [ -f /etc/mubx/iptables.previous ]; then
  if ! iptables-restore < /etc/mubx/iptables.previous; then
    echo "[!] Failed to restore the pre-install firewall rules." >&2
    exit 1
  fi
else
  if [ -n "$WAN_IF" ]; then
    iptables -t nat -D POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null || true
    iptables -t nat -D POSTROUTING -s 10.9.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null || true
    iptables -t nat -D PREROUTING -i "$WAN_IF" -p udp --dport 6000:19999 \
      -j DNAT --to-destination :5667 2>/dev/null || true
  fi
  iptables-save > /etc/iptables/rules.v4 2>/dev/null || true
fi
restore_path /etc/sysctl.d/99-mubx-forwarding.conf
for path in /etc/telecom-engine.env /usr/local/etc/xray/domain \
  /etc/hysteria/config.yaml /etc/dnstt/dnstt.priv /etc/dnstt/dnstt.pub \
  /etc/zivpn/config.json /etc/zivpn/zivpn.crt /etc/zivpn/zivpn.key; do
  restore_path "$path"
done
for path in /usr/local/etc/xray/config.json /usr/local/share/xray/geoip.dat \
  /usr/local/share/xray/geosite.dat; do
  restore_path "$path"
done
for path in /etc/openvpn/client/mubx-client.key /etc/openvpn/client/mubx-client.crt \
  /etc/openvpn/client/mubx-client.csr /etc/openvpn/client/mubx-client-tcp.ovpn \
  /etc/openvpn/client/mubx-client-udp.ovpn /etc/openvpn/client/client.ext \
  /etc/openvpn/server/tcp.conf /etc/openvpn/server/udp.conf \
  /etc/openvpn/certs/ca.crt /etc/openvpn/certs/ca.key /etc/openvpn/certs/ca.srl \
  /etc/openvpn/certs/server.ext \
  /etc/openvpn/certs/dh.pem /etc/openvpn/certs/server.crt /etc/openvpn/certs/server.csr \
  /etc/openvpn/certs/server.key /etc/openvpn/certs/server.ext \
  /etc/wireguard/server.key /etc/wireguard/server.pub /etc/wireguard/client.key \
  /etc/wireguard/client.pub /etc/wireguard/wg0.conf /etc/wireguard/mubx-client.conf; do
  backup="/etc/mubx/backup$path"
  if [ -e "$backup" ] || [ -L "$backup" ]; then
    install -d -m 0700 "$(dirname "$path")"
    rm -f "$path"
    cp -aP "$backup" "$path"
  else
    rm -f "$path"
  fi
done
if [ -f /etc/mubx/ip_forward.previous ]; then
  sysctl -w net.ipv4.ip_forward="$(cat /etc/mubx/ip_forward.previous)" >/dev/null
fi
for path in /etc/default/dropbear /etc/squid/squid.conf /etc/haproxy/haproxy.cfg /etc/nginx/nginx.conf; do
  backup="/etc/mubx/backup$path"
  if [ -e "$backup" ] || [ -L "$backup" ]; then
    install -d -m 0755 "$(dirname "$path")"
    rm -f "$path"
    cp -a "$backup" "$path"
  else
    rm -f "$path"
  fi
done
if [ -f /etc/mubx/service-state ]; then
  systemctl daemon-reload
  while read -r service was_active was_enabled was_present; do
    [ -n "$service" ] || continue
    if [ "$was_present" -eq 1 ] && [ "$was_active" -eq 1 ]; then
      systemctl start "$service" || echo "[!] Failed to restore active state for $service" >&2
    else
      systemctl stop "$service" 2>/dev/null || true
    fi
    if [ "$was_present" -eq 1 ] && [ "$was_enabled" -eq 1 ]; then
      systemctl enable "$service" || echo "[!] Failed to restore enabled state for $service" >&2
    else
      systemctl disable "$service" 2>/dev/null || true
    fi
  done < /etc/mubx/service-state
fi
if [ -e /etc/mubx/resolv.conf.previous ]; then
  rm -f /etc/resolv.conf
  cp -a /etc/mubx/resolv.conf.previous /etc/resolv.conf
fi
if [ -f /etc/mubx/systemd-resolved.active ]; then
  systemctl enable --now systemd-resolved
fi
rm -rf -- "$INSTALL_ROOT" /etc/mubx
echo "[*] Removed"
