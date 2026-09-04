#!/bin/bash
set -Eeuo pipefail

REPO_URL="${MUBX_REPO_URL:-https://github.com/mwright228/my.git}"
INSTALL_ROOT="${MUBX_INSTALL_ROOT:-/root/mub-x}"
XRAY_VERSION="v26.3.27"
HYSTERIA_VERSION="v2.12.2"
WSTUNNEL_VERSION="v10.7.1"

die() { printf '[!] %s\n' "$*" >&2; exit 1; }
require_root() { [ "$(id -u)" -eq 0 ] || die "Run this installer as root."; }
run() { printf '[*] %s\n' "$*"; "$@"; }
download_verified() {
  local url="$1" expected="$2" output="$3"
  curl --fail --location --proto '=https' --tlsv1.2 "$url" --output "$output"
  [ "$(sha256sum "$output" | awk '{print $1}')" = "$expected" ] ||
    die "Checksum verification failed for $url."
}
load_secrets() {
  local key value
  while IFS='=' read -r key value; do
    case "$key" in
      DOMAIN|UUID|REALITY_PRIVKEY|REALITY_PUBKEY|SHORT_ID|HY2_PASS|ZIVPN_PASS|SSH_WS_PATH|REALITY_FRONTS)
        value="${value#\"}"
        value="${value%\"}"
        [[ "$value" != *$'\n'* ]] || die "Invalid secret value for $key."
        printf -v "$key" '%s' "$value"
        ;;
      '') ;;
      *) die "Unexpected key in /etc/telecom-engine.env: $key" ;;
    esac
  done < /etc/telecom-engine.env
  for key in DOMAIN UUID REALITY_PRIVKEY REALITY_PUBKEY SHORT_ID HY2_PASS ZIVPN_PASS SSH_WS_PATH REALITY_FRONTS; do
    [ -n "${!key:-}" ] || die "Missing $key in /etc/telecom-engine.env."
  done
}

require_root
resolver_changed=0
resolved_was_active=0
install_success=0
firewall_captured=0
firewall_snapshot_tmp=
download_root=
declare -A install_backups=()
declare -A install_absent=()
declare -A services_was_active=()
declare -A services_was_enabled=()
declare -A services_were_present=()
services_checked=0
remove_path() {
  local path="$1"
  if [ -d "$path" ] && [ ! -L "$path" ]; then
    rm -rf -- "$path"
  else
    rm -f -- "$path"
  fi
}
restore_install_state() {
  local status=$?
  if [ "$install_success" -eq 0 ]; then
    local path backup
    for path in "${!install_backups[@]}"; do
      backup="${install_backups[$path]}"
      if ! { remove_path "$path" && cp -aP "$backup" "$path"; }; then
        printf '[!] Failed to restore %s\n' "$path" >&2
        status=1
      fi
    done
    for service in "${!services_was_active[@]}"; do
      if [ "${services_were_present[$service]}" -eq 0 ] &&
        systemctl is-active --quiet "$service"; then
        systemctl stop "$service" || status=1
      fi
    done
    for path in "${!install_absent[@]}"; do
      if ! remove_path "$path"; then
        printf '[!] Failed to remove %s\n' "$path" >&2
        status=1
      fi
    done
    if [ "$services_checked" -eq 1 ]; then
      systemctl daemon-reload || status=1
      for path in "${!services_was_active[@]}"; do
        if [ "${services_was_active[$path]}" -eq 1 ]; then
          if ! systemctl start "$path"; then
            printf '[!] Failed to restore service %s\n' "$path" >&2
            status=1
          fi
        elif systemctl cat "$path" >/dev/null 2>&1 &&
          ! systemctl stop "$path"; then
          printf '[!] Failed to stop partially installed service %s\n' "$path" >&2
          status=1
        fi
        if [ "${services_was_enabled[$path]}" -eq 1 ]; then
          systemctl enable "$path" || status=1
        else
          systemctl disable "$path" >/dev/null 2>&1 || true
        fi
      done
    fi
    if [ "$resolver_changed" -eq 1 ] &&
      { [ -e /etc/mubx/resolv.conf.previous ] || [ -L /etc/mubx/resolv.conf.previous ]; }; then
      rm -f /etc/resolv.conf
      cp -a /etc/mubx/resolv.conf.previous /etc/resolv.conf
    fi
    if [ "$resolved_was_active" -eq 1 ]; then
      systemctl enable --now systemd-resolved || true
    fi
    if [ "$firewall_captured" -eq 1 ]; then
      if ! iptables-restore < /etc/mubx/iptables.previous; then
        printf '[!] Failed to restore firewall rules\n' >&2
        status=1
      fi
      if ! sysctl -w net.ipv4.ip_forward="$(cat /etc/mubx/ip_forward.previous)"; then
        printf '[!] Failed to restore IPv4 forwarding\n' >&2
        status=1
      fi
    fi
  fi
  if [ -n "${BUILD_ROOT:-}" ]; then
    rm -rf -- "$BUILD_ROOT"
  fi
  if [ -n "$download_root" ]; then
    rm -rf -- "$download_root"
  fi
  if [ -n "$firewall_snapshot_tmp" ]; then
    rm -f -- "$firewall_snapshot_tmp"
  fi
  return "$status"
}
trap restore_install_state EXIT
[ -r /etc/os-release ] || die "Cannot detect the operating system."
. /etc/os-release
case "$ID" in
  ubuntu)
    case "$VERSION_ID" in 20.04|22.04|24.04) ;;
      *) die "Supported Ubuntu releases are 20.04, 22.04, and 24.04." ;;
    esac
    ;;
  debian)
    case "$VERSION_ID" in 11|12|13) ;;
      *) die "Supported Debian releases are 11, 12, and 13." ;;
    esac
    ;;
  *) die "This installer supports Ubuntu or Debian only." ;;
esac
[ "$(ps -p 1 -o comm=)" = systemd ] || die "A systemd-based VPS is required."
case "$(dpkg --print-architecture)" in
  amd64|arm64|armhf) ;;
  *) die "Supported architectures are amd64, arm64, and armhf." ;;
esac

read -r -p "Domain: " DOMAIN </dev/tty
DOMAIN="${DOMAIN,,}"
[[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] ||
  die "Enter a valid DNS hostname."

# Reality fronts: which real HTTPS site(s) should the Reality tunnel
# camouflage against? Different SIM carriers whitelist different SNIs, so
# any number of fronts can be configured (apple.com stays the default).
# Each front is validated for DNS + TCP/443 reachability from this host.
reality_default="$(awk -F= '/^REALITY_FRONTS=/{gsub(/["\r]/, "", $2); print $2}' \
  /etc/telecom-engine.env 2>/dev/null || true)"
reality_default="${reality_default:-www.apple.com}"
REALITY_FRONTS_IN=
read -r -p "Reality SNI front host(s), space separated [$reality_default]: " REALITY_FRONTS_IN </dev/tty
REALITY_FRONTS_IN="${REALITY_FRONTS_IN,,}"
REALITY_FRONTS_IN="$(printf '%s' "$REALITY_FRONTS_IN" | tr -s ' ' | sed 's/^ //; s/ $//')"
if [ -n "$REALITY_FRONTS_IN" ]; then
  for front in $REALITY_FRONTS_IN; do
    [[ "$front" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] ||
      die "Invalid Reality front domain: $front"
    [ "$front" != "$DOMAIN" ] ||
      die "A Reality front cannot be your own domain ($front)."
    timeout 6 bash -c ":</dev/tcp/$front/443" 2>/dev/null ||
      die "Reality front $front is not reachable on TCP 443 from this host."
  done
fi

export DEBIAN_FRONTEND=noninteractive
run apt-get update
run apt-get install -y ca-certificates curl certbot dnsutils lsof psmisc git jq \
  uuid-runtime openssl nginx dropbear squid haproxy openvpn wireguard-tools \
  iptables iptables-persistent qrencode netcat-openbsd fail2ban \
  build-essential cmake libnspr4-dev libnss3-dev unzip iproute2
install -d -m 0700 /etc/mubx
firewall_snapshot_tmp="$(mktemp /etc/mubx/firewall.XXXXXX)"
if iptables-save > "$firewall_snapshot_tmp" &&
  cat /proc/sys/net/ipv4/ip_forward > "$firewall_snapshot_tmp.ip_forward"; then
  mv -f "$firewall_snapshot_tmp" /etc/mubx/iptables.previous
  mv -f "$firewall_snapshot_tmp.ip_forward" /etc/mubx/ip_forward.previous
  firewall_snapshot_tmp=
  firewall_captured=1
else
  rm -f -- "$firewall_snapshot_tmp" "$firewall_snapshot_tmp.ip_forward"
  firewall_snapshot_tmp=
fi
backup_file() {
  local path="$1" backup="/etc/mubx/backup$1"
  if [ -e "$path" ] || [ -L "$path" ]; then
    install -d -m 0700 "$(dirname "$backup")"
    if [ ! -e "$backup" ] && [ ! -L "$backup" ]; then
      cp -aP "$path" "$backup"
    fi
    install_backups["$path"]="$backup"
  else
    install_absent["$path"]=1
  fi
}
backup_file /etc/mubx/install-root
backup_file /etc/mubx/ownership
printf '%s\n' "$INSTALL_ROOT" > /etc/mubx/install-root
printf 'MUB-X\n' > /etc/mubx/ownership
if [ -L /etc/mubx/resolv.conf.previous ]; then
  rm -f /etc/mubx/resolv.conf.previous
fi
if [ ! -e /etc/mubx/resolv.conf.previous ] && [ -e /etc/resolv.conf ]; then
  cp -a /etc/resolv.conf /etc/mubx/resolv.conf.previous
fi
if systemctl is-active --quiet systemd-resolved; then
  resolved_was_active=1
  printf '1\n' > /etc/mubx/systemd-resolved.active
  run systemctl disable --now systemd-resolved
fi
if [ -L /etc/resolv.conf ] || grep -q '127\.0\.0\.53' /etc/resolv.conf 2>/dev/null; then
  rm -f /etc/resolv.conf
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf
  chmod 0644 /etc/resolv.conf
  resolver_changed=1
fi
command -v ss >/dev/null 2>&1 || die "The ss command is required for port validation."
if ss -H -lun 'sport = :53' | grep -q .; then
  die "UDP port 53 is already in use; stop the existing DNS listener before installing DNSTT."
fi

if [ ! -d "$INSTALL_ROOT/.git" ]; then
  if [ -e "$INSTALL_ROOT" ] && [ -n "$(find "$INSTALL_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    die "$INSTALL_ROOT exists and is not an empty MUB-X checkout."
  fi
  run git clone --depth 1 "$REPO_URL" "$INSTALL_ROOT"
else
  [ -z "$(git -C "$INSTALL_ROOT" status --porcelain)" ] ||
    die "$INSTALL_ROOT has local changes; commit or remove them before upgrading."
  [ "$(git -C "$INSTALL_ROOT" remote get-url origin)" = "$REPO_URL" ] ||
    die "$INSTALL_ROOT origin does not match $REPO_URL."
  run git -C "$INSTALL_ROOT" pull --ff-only origin main
fi
cd "$INSTALL_ROOT"

for service in apache2 nginx haproxy xray \
  openvpn-server@tcp openvpn-server@udp wg-quick@wg0 \
  badvpn@7100 badvpn@7200 badvpn@7300 badvpn@7400 badvpn@7500 badvpn@7600 badvpn@7700 \
  dnstt zivpn hysteria wstunnel mubx-cron.timer mubx-adaptive.timer; do
  systemctl is-active --quiet "$service" && services_was_active["$service"]=1 ||
    services_was_active["$service"]=0
  systemctl is-enabled --quiet "$service" && services_was_enabled["$service"]=1 ||
    services_was_enabled["$service"]=0
  systemctl cat "$service" >/dev/null 2>&1 &&
    services_were_present["$service"]=1 || services_were_present["$service"]=0
done
backup_file /etc/mubx/service-state
{
  for service in "${!services_was_active[@]}"; do
    printf '%s %s %s %s\n' "$service" "${services_was_active[$service]}" \
      "${services_was_enabled[$service]}" "${services_were_present[$service]}"
  done
} > /etc/mubx/service-state
services_checked=1
systemctl stop apache2 nginx haproxy xray 2>/dev/null || true
if ss -H -ltn 'sport = :80' | grep -q .; then
  die "TCP port 80 is still in use by an unmanaged listener."
fi
install -d -m 0755 /usr/local/etc/xray
backup_file /etc/default/dropbear
backup_file /etc/squid/squid.conf
backup_file /etc/haproxy/haproxy.cfg
backup_file /etc/nginx/nginx.conf
backup_file /etc/telecom-engine.env
backup_file /etc/sysctl.d/99-mubx-forwarding.conf
backup_file /etc/sysctl.d/99-mubx-network.conf
for path in /etc/hysteria/config.yaml /etc/dnstt/dnstt.priv /etc/dnstt/dnstt.pub \
  /etc/zivpn/config.json /etc/openvpn/server/tcp.conf \
  /etc/openvpn/server/udp.conf /etc/openvpn/certs/server.ext \
  /etc/openvpn/client/client.ext /etc/openvpn/certs/ca.crt \
  /etc/openvpn/certs/ca.key /etc/openvpn/certs/ca.srl \
  /etc/openvpn/certs/dh.pem /etc/openvpn/certs/server.crt \
  /etc/openvpn/certs/server.key /etc/openvpn/client/mubx-client.key \
  /etc/openvpn/client/mubx-client.crt /etc/openvpn/client/mubx-client.csr \
  /etc/openvpn/client/mubx-client-tcp.ovpn /etc/openvpn/client/mubx-client-udp.ovpn \
  /etc/wireguard/server.key /etc/wireguard/server.pub /etc/wireguard/client.key \
  /etc/wireguard/client.pub /etc/wireguard/wg0.conf /etc/wireguard/mubx-client.conf; do
  backup_file "$path"
done
for file in bin/*; do
  backup_file "/usr/local/bin/$(basename "$file")"
done
backup_file /usr/local/lib/mubx/common.sh
backup_file /usr/local/lib/mubx/reality-build.sh
for file in systemd/*.service systemd/*.timer; do
  backup_file "/etc/systemd/system/$(basename "$file")"
done
backup_file /usr/local/etc/xray/domain
backup_file /usr/local/etc/xray/config.json
backup_file /usr/local/share/xray/geoip.dat
backup_file /usr/local/share/xray/geosite.dat
backup_file /usr/local/go
for file in xray hysteria wstunnel zivpn dnstt-server dnstt-client badvpn-udpgw; do
  backup_file "/usr/local/bin/$file"
done
install -m 0755 bin/* /usr/local/bin/
install -D -m 0644 lib/common.sh /usr/local/lib/mubx/common.sh
install -D -m 0644 lib/reality-build.sh /usr/local/lib/mubx/reality-build.sh
install -m 0644 configs/dropbear /etc/default/dropbear
install -m 0644 configs/squid.conf /etc/squid/squid.conf
install -m 0644 configs/nginx.conf /etc/nginx/nginx.conf
install -m 0644 systemd/*.service /etc/systemd/system/
install -m 0644 systemd/*.timer /etc/systemd/system/
printf '%s\n' "$DOMAIN" > /usr/local/etc/xray/domain

case "$(dpkg --print-architecture)" in
  amd64)
    XRAY_ASSET="Xray-linux-64.zip"
    XRAY_SHA256="23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae"
    HYSTERIA_ASSET="hysteria-linux-amd64"
    HYSTERIA_SHA256="6493dfffd55b5883f64c76c63880ecc32988f0c568c9ca9014907877b4d55f94"
    ;;
  arm64)
    XRAY_ASSET="Xray-linux-arm64-v8a.zip"
    XRAY_SHA256="4d30283ae614e3057f730f67cd088a42be6fdf91f8639d82cb69e48cde80413c"
    HYSTERIA_ASSET="hysteria-linux-arm64"
    HYSTERIA_SHA256="ebfacc1ec3a0edfd742cd68ce17f292a6092e606b9d11f99b035c1d888f3d709"
    ;;
  armhf)
    XRAY_ASSET="Xray-linux-arm32-v7a.zip"
    XRAY_SHA256="c7265ae13c63ca0241a037df4ef960ad37938c8a67d984cc08834b2cfdf5654b"
    HYSTERIA_ASSET="hysteria-linux-arm"
    HYSTERIA_SHA256="274a0de7e2d145aa03fac017a7c7e9a995620f4eaf8db41656d37eddf2764f03"
    ;;
esac
case "$(dpkg --print-architecture)" in
  amd64) WSTUNNEL_ASSET="wstunnel_10.7.1_linux_amd64.tar.gz"; WSTUNNEL_SHA256="fa842ed53fbb14b1c69cd98829f9895d7f8a6b0d562c57c1175851a52cea9ea2" ;;
  arm64) WSTUNNEL_ASSET="wstunnel_10.7.1_linux_arm64.tar.gz"; WSTUNNEL_SHA256="99f9506d01d1b4073254609600ec5056dab8dc58aec75c32f6eb0508335a8fd2" ;;
  armhf) WSTUNNEL_ASSET="wstunnel_10.7.1_linux_armv6.tar.gz"; WSTUNNEL_SHA256="f72ec22fc060dfbd54a4591d35ff3929035f26f1401473e7faf8ce254800b9af2" ;;
esac
download_root="$(mktemp -d /tmp/mubx-download.XXXXXX)"
download_verified \
  "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$XRAY_ASSET" \
  "$XRAY_SHA256" "$download_root/xray.zip"
unzip -p "$download_root/xray.zip" xray > /usr/local/bin/xray
chmod 0755 /usr/local/bin/xray
# Ship geoip/geosite databases now instead of waiting for the first weekly
# maintenance run. Newer Xray releases bundle them in the zip; skip quietly
# if this build does not.
install -d -m 0755 /usr/local/share/xray
for geo in geoip.dat geosite.dat; do
  # Locate the member wherever the zip puts it (some builds nest files in a
  # per-architecture directory) and extract it if present.
  member="$(unzip -Z1 "$download_root/xray.zip" | grep -E "(^|/)$geo$" | head -n 1 || true)"
  if [ -n "$member" ]; then
    unzip -p "$download_root/xray.zip" "$member" > "/usr/local/share/xray/$geo"
    chmod 0644 "/usr/local/share/xray/$geo"
  fi
done
rm -f "$download_root/xray.zip"
download_verified \
  "https://github.com/HyNetworks/hysteria/releases/download/app/$HYSTERIA_VERSION/$HYSTERIA_ASSET" \
  "$HYSTERIA_SHA256" /usr/local/bin/hysteria
chmod 0755 /usr/local/bin/hysteria
download_verified \
  "https://github.com/erebe/wstunnel/releases/download/$WSTUNNEL_VERSION/$WSTUNNEL_ASSET" \
  "$WSTUNNEL_SHA256" "$download_root/wstunnel.tar.gz"
tar -xzf "$download_root/wstunnel.tar.gz" -C "$download_root"
install -m 0755 "$download_root/wstunnel" /usr/local/bin/wstunnel

run certbot certonly --standalone --keep-until-expiring --non-interactive --agree-tos \
  --register-unsafely-without-email -d "$DOMAIN"
command -v hysteria >/dev/null 2>&1 || die "Hysteria installation did not provide /usr/local/bin/hysteria."

BUILD_ROOT="$(mktemp -d)"

GO_VERSION="1.26.8"
case "$(dpkg --print-architecture)" in
  amd64) GO_ARCH="amd64"; GO_SHA256="d0f743b33e8d8945e6b1f432edd15785c70507121d6e2a723b21285eddf8b57b" ;;
  arm64) GO_ARCH="arm64"; GO_SHA256="211ffced9dcb9633a55eac6364816ec0ddd951389a740e88fa8b3337971bdda0" ;;
  armhf) GO_ARCH="armv6l"; GO_SHA256="eab440beabf395870752021fa74cedf04f97b61e43ebbe005ecbb98b94e55697" ;;
esac
download_verified \
  "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" \
  "$GO_SHA256" \
  "$BUILD_ROOT/go.tar.gz"
GO_STAGE="$BUILD_ROOT/go-stage"
install -d -m 0755 "$GO_STAGE"
tar -C "$GO_STAGE" -xzf "$BUILD_ROOT/go.tar.gz"
export PATH="$GO_STAGE/go/bin:$PATH"

run git clone --quiet https://github.com/tladesignz/dnstt.git "$BUILD_ROOT/dnstt"
git -C "$BUILD_ROOT/dnstt" checkout --quiet f1b9b97a269f83bad41d2ceef291b4d2c161cd11
(cd "$BUILD_ROOT/dnstt" && run go build -trimpath -o /usr/local/bin/dnstt-server ./dnstt-server)
(cd "$BUILD_ROOT/dnstt" && run go build -trimpath -o /usr/local/bin/dnstt-client ./dnstt-client)
install -d -m 0700 /etc/dnstt
if [ ! -s /etc/dnstt/dnstt.priv ]; then
  run /usr/local/bin/dnstt-server -gen-key \
    -privkey-file /etc/dnstt/dnstt.priv -pubkey-file /etc/dnstt/dnstt.pub
  chmod 0600 /etc/dnstt/dnstt.priv
fi

run git clone --quiet https://github.com/ambrop72/badvpn.git "$BUILD_ROOT/badvpn"
git -C "$BUILD_ROOT/badvpn" checkout --quiet 07268f02706e78e282e19641b5d1d41e8e89bf31
cmake -S "$BUILD_ROOT/badvpn" -B "$BUILD_ROOT/badvpn-build" \
  -DBUILD_NOTHING_BY_DEFAULT=1 -DBUILD_UDPGW=1
run cmake --build "$BUILD_ROOT/badvpn-build" --target badvpn-udpgw --parallel "$(nproc)"
[ -x "$BUILD_ROOT/badvpn-build/udpgw/badvpn-udpgw" ] ||
  die "BadVPN build completed without badvpn-udpgw."
install -m 0755 "$BUILD_ROOT/badvpn-build/udpgw/badvpn-udpgw" /usr/local/bin/badvpn-udpgw
GO_INSTALL_DIR="/usr/local/go-${GO_VERSION}"
backup_file "$GO_INSTALL_DIR"
rm -rf "$GO_INSTALL_DIR"
mv "$GO_STAGE/go" "$GO_INSTALL_DIR"
backup_file /usr/local/go
rm -f /usr/local/go
ln -sfn "$GO_INSTALL_DIR" /usr/local/go

install -d -m 0700 /etc/openvpn/certs /etc/openvpn/server
if [ ! -s /etc/openvpn/certs/ca.crt ]; then
  run openssl req -x509 -nodes -newkey rsa:4096 -days 3650 \
    -subj "/CN=MUB-X CA" -keyout /etc/openvpn/certs/ca.key \
    -out /etc/openvpn/certs/ca.crt
  run openssl req -nodes -newkey rsa:2048 -subj "/CN=MUB-X server" \
    -keyout /etc/openvpn/certs/server.key -out /etc/openvpn/certs/server.csr
  cat > /etc/openvpn/certs/server.ext <<'EOF'
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:server
EOF
  run openssl x509 -req -days 825 -CA /etc/openvpn/certs/ca.crt \
    -CAkey /etc/openvpn/certs/ca.key -CAcreateserial \
    -in /etc/openvpn/certs/server.csr -out /etc/openvpn/certs/server.crt \
    -extfile /etc/openvpn/certs/server.ext
  run openssl dhparam -out /etc/openvpn/certs/dh.pem 2048
fi
chmod 0600 /etc/openvpn/certs/*
if ! openssl x509 -in /etc/openvpn/certs/server.crt -noout -text 2>/dev/null |
  grep -Eq '1\.3\.6\.1\.5\.5\.7\.3\.1|serverAuth'; then
  rm -f /etc/openvpn/certs/server.crt /etc/openvpn/certs/server.csr
  run openssl req -nodes -newkey rsa:2048 -subj "/CN=MUB-X server" \
    -keyout /etc/openvpn/certs/server.key -out /etc/openvpn/certs/server.csr
  cat > /etc/openvpn/certs/server.ext <<'EOF'
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:server
EOF
  run openssl x509 -req -days 825 -CA /etc/openvpn/certs/ca.crt \
    -CAkey /etc/openvpn/certs/ca.key -CAcreateserial \
    -in /etc/openvpn/certs/server.csr -out /etc/openvpn/certs/server.crt \
    -extfile /etc/openvpn/certs/server.ext
fi
install -m 0644 configs/openvpn-tcp.conf /etc/openvpn/server/tcp.conf
install -m 0644 configs/openvpn-udp.conf /etc/openvpn/server/udp.conf
install -d -m 0700 /etc/openvpn/client
if [ ! -s /etc/openvpn/client/mubx-client.key ]; then
  run openssl genrsa -out /etc/openvpn/client/mubx-client.key 2048
  run openssl req -new -key /etc/openvpn/client/mubx-client.key \
    -subj "/CN=mubx-client" -out /etc/openvpn/client/mubx-client.csr
  cat > /etc/openvpn/client/client.ext <<'EOF'
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = clientAuth
EOF
  run openssl x509 -req -days 825 -CA /etc/openvpn/certs/ca.crt \
    -CAkey /etc/openvpn/certs/ca.key -CAcreateserial \
    -in /etc/openvpn/client/mubx-client.csr \
    -out /etc/openvpn/client/mubx-client.crt \
    -extfile /etc/openvpn/client/client.ext
fi
if ! openssl x509 -in /etc/openvpn/client/mubx-client.crt -noout -text 2>/dev/null |
  grep -Eq '1\.3\.6\.1\.5\.5\.7\.3\.2|clientAuth'; then
  rm -f /etc/openvpn/client/mubx-client.crt /etc/openvpn/client/mubx-client.csr
  run openssl req -new -key /etc/openvpn/client/mubx-client.key \
    -subj "/CN=mubx-client" -out /etc/openvpn/client/mubx-client.csr
  cat > /etc/openvpn/client/client.ext <<'EOF'
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = clientAuth
EOF
  run openssl x509 -req -days 825 -CA /etc/openvpn/certs/ca.crt \
    -CAkey /etc/openvpn/certs/ca.key -CAcreateserial \
    -in /etc/openvpn/client/mubx-client.csr \
    -out /etc/openvpn/client/mubx-client.crt \
    -extfile /etc/openvpn/client/client.ext
fi
rm -f /etc/openvpn/certs/server.ext /etc/openvpn/client/client.ext
cat > /etc/openvpn/client/mubx-client-tcp.ovpn <<EOF
client
dev tun
proto tcp-client
remote $DOMAIN 1194
resolv-retry infinite
nobind
persist-key
persist-tun
remote-cert-tls server
<ca>
$(cat /etc/openvpn/certs/ca.crt)
</ca>
<cert>
$(cat /etc/openvpn/client/mubx-client.crt)
</cert>
<key>
$(cat /etc/openvpn/client/mubx-client.key)
</key>
EOF
cp /etc/openvpn/client/mubx-client-tcp.ovpn /etc/openvpn/client/mubx-client-udp.ovpn
sed -i "s/proto tcp-client/proto udp/; s/remote $DOMAIN 1194/remote $DOMAIN 2200/" \
  /etc/openvpn/client/mubx-client-udp.ovpn
install -d -m 0755 /etc/hysteria
install -m 0600 configs/hysteria.yaml /etc/hysteria/config.yaml
WAN_IF="$(ip -o route show to default | awk 'NR == 1 {print $5}')"
[ -n "$WAN_IF" ] || die "Unable to determine the public network interface."
install -d -m 0700 /etc/wireguard
if [ ! -s /etc/wireguard/server.key ]; then
  wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
  wg genkey | tee /etc/wireguard/client.key | wg pubkey > /etc/wireguard/client.pub
fi
WG_SERVER_PRIV="$(cat /etc/wireguard/server.key)"
WG_SERVER_PUB="$(cat /etc/wireguard/server.pub)"
WG_CLIENT_PRIV="$(cat /etc/wireguard/client.key)"
cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.66.66.1/24
ListenPort = 51820
PrivateKey = $WG_SERVER_PRIV
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o $WAN_IF -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o $WAN_IF -j MASQUERADE

[Peer]
PublicKey = $(cat /etc/wireguard/client.pub)
AllowedIPs = 10.66.66.2/32
EOF
cat > /etc/wireguard/mubx-client.conf <<EOF
[Interface]
PrivateKey = $WG_CLIENT_PRIV
Address = 10.66.66.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = $WG_SERVER_PUB
Endpoint = $DOMAIN:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
chmod 0600 /etc/wireguard/*.key /etc/wireguard/*.conf
printf 'net.ipv4.ip_forward=1\n' > /etc/sysctl.d/99-mubx-forwarding.conf
if [ ! -e /etc/mubx/ip_forward.previous ]; then
  cat /proc/sys/net/ipv4/ip_forward > /etc/mubx/ip_forward.previous
fi
run sysctl --system
iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null ||
  iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE
iptables -t nat -C POSTROUTING -s 10.9.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null ||
  iptables -t nat -A POSTROUTING -s 10.9.0.0/24 -o "$WAN_IF" -j MASQUERADE
iptables-save > /etc/iptables/rules.v4
printf '%s\n' "$WAN_IF" > /etc/mubx/wan-interface

run /usr/local/bin/generate-secrets
if [ -n "${REALITY_FRONTS_IN:-}" ]; then
  sed -i "s|^REALITY_FRONTS=.*|REALITY_FRONTS=\"$REALITY_FRONTS_IN\"|" /etc/telecom-engine.env
fi
sed -i "s/^DOMAIN=.*/DOMAIN=\"$DOMAIN\"/; s|__DOMAIN__|$DOMAIN|g" /etc/telecom-engine.env
load_secrets
sed -i "s|__SSH_WS_PATH__|$SSH_WS_PATH|g" /etc/nginx/nginx.conf /etc/systemd/system/wstunnel.service
sed -i "s|__DOMAIN__|$DOMAIN|g" /etc/nginx/nginx.conf /etc/systemd/system/dnstt.service
sed -i "s|__DOMAIN__|$DOMAIN|g; s|__HY2_PASS__|$HY2_PASS|g" /etc/hysteria/config.yaml
# Generate the Xray config (one Reality inbound per configured SNI front,
# plus a per-user client identity for every entry in the users store) and
# the HAProxy SNI routing from the shared renderers.
source ./lib/reality-build.sh
mubx_users_seed
mubx_xray_render configs/xray.json configs/reality-inbound.json \
  /usr/local/etc/xray/config.json
mubx_haproxy_render configs/haproxy.cfg /etc/haproxy/haproxy.cfg
chmod 0600 /usr/local/etc/xray/config.json
ZIVPN_ARCH="$(dpkg --print-architecture)"
ZIVPN_ENABLED=1
case "$ZIVPN_ARCH" in
  amd64)
    ZIVPN_ASSET="udp-zivpn-linux-amd64"
    ZIVPN_SHA256="df6658c195882ff2f6cefb44050e8cb2c238ceb2b6e3fbefb931698f4f0519cb"
    ;;
  arm64)
    ZIVPN_ASSET="udp-zivpn-linux-arm64"
    ZIVPN_SHA256="1bc3f0a46db2b4a4771dd08e68e2134c55d7c48874334ed7bba512d983bfa83a"
    ;;
  armhf)
    ZIVPN_ENABLED=0
    printf '[!] ZivPN is unavailable on armhf; continuing without it.\n' >&2
    ;;
  *) die "No ZivPN binary mapping for $ZIVPN_ARCH." ;;
esac
if [ "$ZIVPN_ENABLED" -eq 1 ]; then
  download_verified \
    "https://github.com/zahidbd2/udp-zivpn/releases/download/udp-zivpn_1.4.9/$ZIVPN_ASSET" \
    "$ZIVPN_SHA256" \
    /usr/local/bin/zivpn
  chmod 0755 /usr/local/bin/zivpn
  install -d -m 0700 /etc/zivpn
  install -m 0600 configs/zivpn.json /etc/zivpn/config.json
  ln -sfn "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" /etc/zivpn/zivpn.crt
  ln -sfn "/etc/letsencrypt/live/$DOMAIN/privkey.pem" /etc/zivpn/zivpn.key
  sed -i "s|__ZIVPN_PASS__|$ZIVPN_PASS|g" /etc/zivpn/config.json
  # Route a wide UDP range to ZivPN so carrier port filters are less
  # effective, but keep the BadVPN UDPGW bridge ports (7100-7700) out of
  # the DNAT set: without the exclusion every packet to the public IP on
  # 7100-7700 would be swallowed by ZivPN and the gaming bridge would die.
  # Also drop any legacy 6000:19999 rule left by older installs.
  iptables -t nat -D PREROUTING -i "$WAN_IF" -p udp --dport 6000:19999 \
    -j DNAT --to-destination :5667 2>/dev/null || true
  if ! iptables -t nat -C PREROUTING -i "$WAN_IF" -p udp \
    -m multiport --dports 6000:7099,7701:19999 \
    -j DNAT --to-destination :5667 2>/dev/null; then
    iptables -t nat -A PREROUTING -i "$WAN_IF" -p udp \
      -m multiport --dports 6000:7099,7701:19999 \
      -j DNAT --to-destination :5667
  fi
  iptables-save > /etc/iptables/rules.v4
fi

nginx -t
haproxy -c -f /etc/haproxy/haproxy.cfg
xray run -test -config /usr/local/etc/xray/config.json
run systemctl daemon-reload
for svc in nginx haproxy xray dropbear squid wstunnel; do
  run systemctl enable "$svc"
  run systemctl restart "$svc"
  systemctl is-active --quiet "$svc" || die "$svc failed to start; inspect journalctl -u $svc."
done
for svc in openvpn-server@tcp openvpn-server@udp; do
  run systemctl enable "$svc"
  run systemctl restart "$svc"
  systemctl is-active --quiet "$svc" || die "$svc failed to start; inspect journalctl -u $svc."
done
run systemctl enable wg-quick@wg0
run systemctl restart wg-quick@wg0
systemctl is-active --quiet wg-quick@wg0 ||
  die "wg-quick@wg0 failed to start; inspect its journal."
for port in 7100 7200 7300 7400 7500 7600 7700; do
  run systemctl enable --now "badvpn@$port.service"
  systemctl is-active --quiet "badvpn@$port.service" ||
    die "badvpn@$port.service failed to start; inspect its journal."
done
run systemctl enable dnstt
run systemctl restart dnstt
systemctl is-active --quiet dnstt || die "dnstt failed to start; inspect journalctl -u dnstt."
run systemctl enable wstunnel
run systemctl restart wstunnel
systemctl is-active --quiet wstunnel || die "wstunnel failed to start; inspect journalctl -u wstunnel."
if [ "$ZIVPN_ENABLED" -eq 1 ]; then
  run systemctl enable zivpn
  run systemctl restart zivpn
  systemctl is-active --quiet zivpn || die "zivpn failed to start; inspect journalctl -u zivpn."
fi
run systemctl enable hysteria
run systemctl restart hysteria
systemctl is-active --quiet hysteria || die "hysteria failed to start; inspect journalctl -u hysteria."
run systemctl enable --now mubx-cron.timer
run /usr/local/bin/mubx-tune
run systemctl daemon-reload
run systemctl enable --now mubx-adaptive.timer
if command -v fail2ban-client >/dev/null 2>&1; then
  install -D -m 0644 configs/fail2ban-mubx.conf /etc/fail2ban/jail.d/mubx.conf
  systemctl enable --now fail2ban || true
  fail2ban-client status dropbear >/dev/null 2>&1 ||
    echo "[!] fail2ban 'dropbear' jail did not activate; inspect journalctl -u fail2ban." >&2
fi

install_success=1
echo "[+] Core online. Type 'menu'."
