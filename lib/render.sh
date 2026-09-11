#!/bin/bash
# MUB-X configuration renderers (Xray / HAProxy / Nginx).
#
# Xray: renders the base template (VLESS/VMess/Trojan WS, HTTPUpgrade,
# xHTTP) and then appends the per-user
# Shadowsocks inbounds (WS loopback + plain public TCP) plus the shared
# SS-on-443 inbound that HAProxy fronts from public port 443.
#
# HAProxy: owns public 443 and multiplexes by content - TLS ClientHello
# (any SNI, incl. carrier bug-hosts) goes to the nginx loopback TLS
# terminator; non-TLS connections (raw Shadowsocks TCP) go to the shared
# SS-443 Xray inbound. Reality SNI fronts were removed.
#
# The per-user overlay (mubx_users_apply) rewrites .settings.clients on
# every inbound that has one and enables the stats API. The user store
# lives in MUBX_USERS_FILE (/etc/mubx/users.json by default).
#
# Sourced by install.sh, mubx-update, mubx-users and link-gen. Requires jq,
# which install.sh always provisions.

SS_MARKER='#MUBX_SS_LOCATIONS#'
SS_BASE_PORT=10006
SS_PLAIN_BASE_PORT=8388
# Shared SS-on-443 inbound: HAProxy sends every non-TLS connection on
# public 443 here, so it is a single (primary) identity by design - raw
# Shadowsocks has no SNI/path to demultiplex per user on one TCP port.
SS_443_LOOPBACK_PORT=17000

# Multi-user store. Overridable (MUBX_USERS_FILE) so tests can render
# against a scratch copy. Each entry: {name, uuid, added, expiry, quota_gb, status}.
MUBX_USERS_FILE="${MUBX_USERS_FILE:-/etc/mubx/users.json}"
MUBX_WARP_FILE="${MUBX_WARP_FILE:-/etc/mubx/warp.json}"

mubx_users_count() { # $1 store file -> number of users (0 on any error)
  jq -r 'length' "$1" 2>/dev/null || printf '0\n'
}

# Idempotent: seed the store with the legacy single identity ("admin") so
# pre-existing client links keep working after an upgrade.
mubx_users_seed() {
  [ -n "${UUID:-}" ] || return 0
  if [ ! -f "$MUBX_USERS_FILE" ]; then
    install -d -m 0700 "$(dirname "$MUBX_USERS_FILE")"
    printf '[{"name":"admin","uuid":"%s","added":"%s","expiry":"","quota_gb":0,"status":"active"}]\n' \
      "$UUID" "$(date -u '+%Y-%m-%d')" > "$MUBX_USERS_FILE"
    chmod 0600 "$MUBX_USERS_FILE"
  fi
}

# Primary (shared) identity: the seeded "admin" UUID when a store exists,
# else the legacy UUID. Used by the shared SS-443 inbound and for the
# client links that carry the shared identity.
mubx_primary_uuid() {
  if [ -f "$MUBX_USERS_FILE" ]; then
    local u
    u="$(jq -r '.[] | select(.name == "admin" and (.status // "active") != "frozen") | .uuid' "$MUBX_USERS_FILE" 2>/dev/null | head -n 1)"
    [ -n "$u" ] && printf '%s\n' "$u" && return 0
  fi
  printf '%s\n' "${UUID:-}"
}

# --- Protocol Selection & Filtering Engine ---------------------------------

# Universal jq helper definition for protocol filtering, loaded from proto-def.jq
_proto_jq_file="${_MUBX_LIB_DIR:-/usr/local/lib/mubx}/proto-def.jq"
if [ ! -f "$_proto_jq_file" ]; then
  _proto_jq_file="$(dirname "${BASH_SOURCE[0]}")/proto-def.jq"
fi
if [ -f "$_proto_jq_file" ]; then
  JQ_PROTO_DEF="$(< "$_proto_jq_file")"
else
  JQ_PROTO_DEF='
def user_has_proto($p):
  if (.protocols == null or .protocols == [] or (.protocols | index("all") != null)) then true
  elif (.protocols | index($p) != null) then true
  elif ($p == "vless" and (.protocols | any(startswith("vless")))) then true
  elif ($p == "vmess" and (.protocols | any(startswith("vmess")))) then true
  elif ($p == "trojan" and (.protocols | any(startswith("trojan")))) then true
  elif ($p == "ss" and (.protocols | any(startswith("ss")))) then true
  elif (($p == "vless-ws" or $p == "vless_ws") and ((.protocols | index("vless") != null) or (.protocols | index("vless_ws") != null) or (.protocols | index("vless_ws_tls") != null) or (.protocols | index("vless_ws_ntls") != null))) then true
  elif (($p == "vless-httpupgrade" or $p == "vless_httpupgrade") and ((.protocols | index("vless") != null) or (.protocols | index("vless_httpupgrade") != null) or (.protocols | index("vless_httpupgrade_tls") != null) or (.protocols | index("vless_httpupgrade_ntls") != null))) then true
  elif (($p == "vless-xhttp" or $p == "vless_xhttp") and ((.protocols | index("vless") != null) or (.protocols | index("vless_xhttp") != null) or (.protocols | index("vless_xhttp_tls") != null))) then true
  elif (($p == "vless-grpc" or $p == "vless_grpc" or $p == "vless_grpc_tls") and ((.protocols | index("vless") != null) or (.protocols | index("vless_grpc") != null) or (.protocols | index("vless_grpc_tls") != null))) then true
  elif (($p == "vmess-ws" or $p == "vmess_ws") and ((.protocols | index("vmess") != null) or (.protocols | index("vmess_ws") != null) or (.protocols | index("vmess_ws_tls") != null) or (.protocols | index("vmess_ws_ntls") != null))) then true
  elif (($p == "trojan-ws" or $p == "trojan_ws") and ((.protocols | index("trojan") != null) or (.protocols | index("trojan_ws") != null) or (.protocols | index("trojan_ws_tls") != null) or (.protocols | index("trojan_ws_ntls") != null))) then true
  elif (($p == "ss-ws" or $p == "ss_ws") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_ws") != null) or (.protocols | index("ss_ws_tls") != null) or (.protocols | index("ss_ws_ntls") != null))) then true
  elif (($p == "ss-tcp" or $p == "ss_tcp") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_tcp") != null))) then true
  elif (($p == "ss-2022" or $p == "ss_2022") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_2022") != null) or (.protocols | index("shadowtls") != null))) then true
  elif ($p == "shadowtls" and ((.protocols | index("shadowtls") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "hysteria2" and ((.protocols | index("hysteria2") != null) or (.protocols | index("hy2") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "ssh" and ((.protocols | index("ssh") != null))) then true
  elif ($p == "openvpn" and ((.protocols | index("openvpn") != null) or (.protocols | index("vpn") != null))) then true
  elif (($p == "wireguard" or $p == "amneziawg" or $p == "awg") and ((.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("vpn") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "amneziawg" and ((.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("vpn") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "awg" and ((.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("vpn") != null))) then true
  elif (($p == "squid" or $p == "chameleon") and ((.protocols | index("squid") != null) or (.protocols | index("chameleon") != null))) then true
  elif ($p == "zivpn" and ((.protocols | index("zivpn") != null) or (.protocols | index("antidpi") != null))) then true
  elif ($p == "tuic" and ((.protocols | index("tuic") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "tbrutal" and ((.protocols | index("tbrutal") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  else false
  end;
'
fi


# Normalizes raw user protocol input (numbers, commas, names) into a JSON array string.
mubx_proto_normalize() { # $1 raw input string -> stdout JSON array
  local input="${1:-all}"
  input="${input//,/ }"
  input="${input//;/ }"
  input="$(printf '%s' "$input" | tr '[:upper:]' '[:lower:]')"
  
  local tags=()
  for item in $input; do
    case "$item" in
      0|8|all) tags+=("all") ;;
      1|ssh-bundle|ssh-only) tags+=("ssh" "chameleon") ;;
      2|vless-bundle|vless) tags+=("vless_ws_tls" "vless_ws_ntls" "vless_httpupgrade_tls" "vless_httpupgrade_ntls" "vless_xhttp_tls" "vless_grpc_tls" "vless_tcp_tls") ;;
      3|vmess-bundle|vmess) tags+=("vmess_ws_tls" "vmess_ws_ntls" "vmess_tcp") ;;
      4|trojan-bundle|trojan) tags+=("trojan_ws_tls" "trojan_ws_ntls" "trojan_tcp") ;;
      5|ss-bundle|shadowsocks|ss) tags+=("ss_ws_tls" "ss_ws_ntls" "ss_tcp" "ss_2022" "shadowtls") ;;
      6|vpn-bundle|vpn) tags+=("wireguard" "amneziawg" "openvpn") ;;
      7|antidpi-bundle|antidpi|stealth) tags+=("hysteria2" "shadowtls" "zivpn" "tuic" "amneziawg") ;;
      10|ssh) tags+=("ssh") ;;
      11|openvpn|ovpn) tags+=("openvpn") ;;
      12|wireguard|wg) tags+=("wireguard") ;;
      13|squid|chameleon) tags+=("chameleon") ;;
      14|amneziawg|awg|amnezia) tags+=("amneziawg") ;;
      20|vless-ws-tls|vless_ws_tls) tags+=("vless_ws_tls") ;;
      21|vless-ws-ntls|vless_ws_ntls|vless-ws-80) tags+=("vless_ws_ntls") ;;
      22|vless-upg-tls|vless_httpupgrade_tls|vless-httpupgrade) tags+=("vless_httpupgrade_tls") ;;
      23|vless-upg-ntls|vless_httpupgrade_ntls|vless-httpupgrade-80) tags+=("vless_httpupgrade_ntls") ;;
      24|vless-xhttp-tls|vless_xhttp_tls|vless-xhttp) tags+=("vless_xhttp_tls") ;;
      25|vless-tcp|vless_tcp_tls|vless-tcp-tls) tags+=("vless_tcp_tls") ;;
      26|vless-grpc|vless-grpc-tls|vless_grpc_tls) tags+=("vless_grpc_tls") ;;
      30|vmess-ws-tls|vmess_ws_tls) tags+=("vmess_ws_tls") ;;
      31|vmess-ws-ntls|vmess_ws_ntls|vmess-ws-80) tags+=("vmess_ws_ntls") ;;
      32|vmess-tcp|vmess_tcp) tags+=("vmess_tcp") ;;
      40|trojan-ws-tls|trojan_ws_tls) tags+=("trojan_ws_tls") ;;
      41|trojan-ws-ntls|trojan_ws_ntls|trojan-ws-80) tags+=("trojan_ws_ntls") ;;
      42|trojan-tcp|trojan_tcp) tags+=("trojan_tcp") ;;
      50|ss-ws-tls|ss_ws_tls) tags+=("ss_ws_tls") ;;
      51|ss-ws-ntls|ss_ws_ntls|ss-ws-80) tags+=("ss_ws_ntls") ;;
      52|ss-tcp|ss_tcp) tags+=("ss_tcp") ;;
      53|ss-2022|ss_2022) tags+=("ss_2022") ;;
      60|shadowtls|stls) tags+=("shadowtls") ;;
      61|hysteria2|hy2) tags+=("hysteria2") ;;
      62|zivpn) tags+=("zivpn") ;;
      63|tuic|tuic-v5) tags+=("tuic") ;;
      64|tbrutal|t-brutal) tags+=("tbrutal") ;;
      *) tags+=("$item") ;;
    esac
  done

  # Convert array to unique JSON list
  printf '%s\n' "${tags[@]}" | jq -R . | jq -s 'unique'
}

# Checks if a user in the store has access to a specific protocol tag
mubx_user_has_proto() { # $1 user_name  $2 protocol_tag -> returns 0 (true) or 1 (false)
  local user="$1" proto="$2"
  [ -f "$MUBX_USERS_FILE" ] || return 0
  local has
  has="$(jq --arg u "$user" --arg p "$proto" "
    ${JQ_PROTO_DEF}
    [.[] | select(.name == \$u)] | if length == 0 then true else (first | user_has_proto(\$p)) end
  " "$MUBX_USERS_FILE" 2>/dev/null || echo true)"
  [ "$has" = "true" ]
}

# Generates a compact badge string for table display
mubx_proto_summary() { # $1 json_array_or_user_object -> formatted string
  local raw="$1"
  if [ -z "$raw" ] || [ "$raw" = "null" ] || [ "$raw" = "[]" ]; then
    printf 'ALL'
    return 0
  fi
  jq -r '
    def is_arr: type == "array";
    (if is_arr then . else (.protocols // ["all"]) end) as $p |
    if ($p | length == 0) or ($p | index("all") != null) then "ALL"
    else
      [
        (if ($p | index("ssh") != null) then "SSH" else empty end),
        (if ($p | any(startswith("vless"))) then "VLESS" else empty end),
        (if ($p | any(startswith("vmess"))) then "VMess" else empty end),
        (if ($p | any(startswith("trojan"))) then "Trojan" else empty end),
        (if (($p | index("ss") != null) or ($p | index("shadowsocks") != null) or ($p | any(startswith("ss_"))) or ($p | index("ss_2022") != null)) then "SS" else empty end),
        (if ($p | index("shadowtls") != null) then "STLS" else empty end),
        (if ($p | index("hysteria2") != null) then "HY2" else empty end),
        (if ($p | index("amneziawg") != null) then "AWG" elif ($p | index("wireguard") != null) then "WG" else empty end),
        (if ($p | index("openvpn") != null) then "OVPN" else empty end),
        (if (($p | index("squid") != null) or ($p | index("chameleon") != null)) then "Chameleon" else empty end),
        (if ($p | index("zivpn") != null) then "ZivPN" else empty end),
        (if ($p | index("tuic") != null) then "TUIC" else empty end),
        (if ($p | index("tbrutal") != null) then "T-Brutal" else empty end)
      ] as $cats |
      if ($cats | length) > 0 then ($cats | join("+"))
      else ($p | length | tostring + " protos")
      end
    end
  ' <<< "$raw" 2>/dev/null || printf 'ALL'
}

# Interactive protocol selector dialog
mubx_proto_picker() { # stdout: normalized JSON array
  if [ ! -t 0 ] && [ ! -t 1 ]; then
    printf '["all"]'
    return 0
  fi
  {
    printf '\n'
    mubx_box_top "PROTOCOL SELECTION" "SELECT PROTOCOL"
    mubx_put "$(printf '  %sSelect the protocol to create for this account:%s' "$C_SLATE" "$C_RESET")"
    mubx_box_div "DEDICATED SINGLE-PROTOCOL ACCOUNTS"
    mubx_put "$(printf '  %s[1]%s SSH & Chameleon Proxy  (SSH Direct/WS, Chameleon HTTP)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[2]%s VLESS Only               (VLESS TCP, WS TLS/80, HTTPUpg, gRPC)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[3]%s VMess Only               (VMess WS TLS/80, TCP)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[4]%s Trojan Only              (Trojan TCP, WS TLS/80)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[5]%s Shadowsocks Family Only  (SS TCP Raw, WS, SS-2022)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[6]%s Full-Tunnel VPNs Only    (WireGuard, AmneziaWG, OpenVPN)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[7]%s Anti-DPI Stealth Only    (Hysteria 2, ShadowTLS, TUIC v5, Awg)' "$C_PURPLE" "$C_RESET")"
    mubx_put "$(printf '  %s[8]%s ALL Protocols            (Full Suite - Everything)' "$C_ELECTRIC" "$C_RESET")"
    mubx_box_div "OR CUSTOM COMBINATIONS (E.G. 10,20,52)"
    mubx_put "$(printf '  %s[10]%s SSH Direct & WS         %s[11]%s OpenVPN TCP/UDP' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[12]%s WireGuard VPN           %s[14]%s AmneziaWG (Obfs WG)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[13]%s Chameleon HTTP Proxy' "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[20]%s VLESS WS (TLS 443)      %s[21]%s VLESS WS (Plain 80)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[22]%s VLESS HTTPUpgrade TLS   %s[23]%s VLESS HTTPUpgrade Plain' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[24]%s VLESS xHTTP (TLS 443)   %s[26]%s VLESS gRPC (TLS 443)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[25]%s VLESS TCP Direct/TLS' "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[30]%s VMess WS (TLS 443)      %s[31]%s VMess WS (Plain 80)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[32]%s VMess TCP' "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[40]%s Trojan WS (TLS 443)     %s[41]%s Trojan WS (Plain 80)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[42]%s Trojan TCP' "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[50]%s Shadowsocks WS (TLS)    %s[51]%s Shadowsocks WS (Plain)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[52]%s Shadowsocks TCP Raw     %s[53]%s Shadowsocks 2022 WS' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[60]%s ShadowTLS v3 Decoy      %s[61]%s Hysteria 2 (UDP 4433)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_put "$(printf '  %s[62]%s ZivPN UDP (5667)        %s[63]%s TUIC v5 QUIC (UDP 8444)' "$C_ELECTRIC" "$C_RESET" "$C_ELECTRIC" "$C_RESET")"
    mubx_box_bot
    printf '\n'
    printf '  %sSelect protocol [1-8, or numbers] (Default = 1 SSH): %s' "$C_PURPLE" "$C_RESET"
  } >&2
  local sel
  read -r sel || sel="1"
  sel="${sel:-1}"
  mubx_proto_normalize "$sel"
}

# Overlay the per-user client identities onto a rendered Xray config and
# enable the stats API (policy + dokodemo api inbound + routing) so traffic
# can be queried per user email with `xray api statsquery`. When the store
# is absent or empty the config is left in the legacy single-identity shape.
mubx_users_apply() { # $1 rendered config file
  local users_json n dom
  [ -f "$MUBX_USERS_FILE" ] || return 0
  users_json="$(jq -c '[.[] | select((.status // "active") != "frozen") | {name: (.name // ""), uuid: (.uuid // ""), protocols: (.protocols // ["all"])} | select(.uuid != "")]' "$MUBX_USERS_FILE" 2>/dev/null || true)"
  n="$(mubx_users_count "$MUBX_USERS_FILE")"
  [ "$n" -ge 1 ] || return 0
  dom="${DOMAIN:-mubx}"
  jq --argjson users "$users_json" --arg dom "$dom" "
    ${JQ_PROTO_DEF}
    .inbounds |= map(
      if (.settings.clients? == null) then .
      else
        (
          .tag as \$tag |
          .protocol as \$proto |
          (
            if \$tag == \"trojan-ws\" or \$proto == \"trojan\" then \"trojan-ws\"
            elif \$tag == \"vmess-ws\" or \$proto == \"vmess\" then \"vmess-ws\"
            elif \$tag == \"vless-httpupgrade\" then \"vless-httpupgrade\"
            elif \$tag == \"vless-xhttp\" then \"vless-xhttp\"
            elif \$tag == \"vless-grpc\" then \"vless_grpc_tls\"
            elif \$tag == \"vless-tcp\" then \"vless_tcp_tls\"
            else \"vless-ws\"
            end
          ) as \$req_proto |
          [ \$users[] | select(user_has_proto(\$req_proto)) ] as \$matching_users |
          \$matching_users as \$active_clients |
          if .protocol == \"trojan\" then
            .settings.clients = [ \$active_clients[] | {password: .uuid, email: (.name + \"@\" + \$dom)} ]
          elif .protocol == \"vmess\" then
            .settings.clients = [ \$active_clients[] | {id: .uuid, alterId: 0, email: (.name + \"@\" + \$dom)} ]
          else
            .settings.clients = [ \$active_clients[] | {id: .uuid, email: (.name + \"@\" + \$dom)} ]
          end
        )
      end
    )
    | .api = {tag: \"api\", services: [\"HandlerService\", \"StatsService\"]}
    | .policy = {levels: {\"0\": {statsUserUplink: true, statsUserDownlink: true}}}
    | .inbounds += [{tag: \"api\", listen: \"127.0.0.1\", port: 10085, protocol: \"dokodemo-door\", settings: {address: \"127.0.0.1\", network: \"tcp\"}}]
    | .routing = {rules: [{type: \"field\", inboundTag: [\"api\"], outboundTag: \"direct\"}]}
  " "$1" > "$1.mubx" && mv -f "$1.mubx" "$1"
}

# Overlay Cloudflare WARP WireGuard smart outbound and streaming routing rules
# when /etc/mubx/warp.json exists.
mubx_warp_apply() { # $1 rendered config file
  local cfg="$1" warp_obj
  [ -f "$MUBX_WARP_FILE" ] || return 0
  warp_obj="$(jq -c '.' "$MUBX_WARP_FILE" 2>/dev/null || true)"
  [ -n "$warp_obj" ] || return 0

  jq --argjson warp "$warp_obj" '
    .outbounds = (.outbounds // []) + [$warp]
    | .routing.rules = [
        {
          type: "field",
          outboundTag: "warp",
          domain: [
            "geosite:netflix",
            "geosite:openai",
            "geosite:disney",
            "geosite:spotify",
            "geosite:hulu",
            "geosite:primevideo"
          ]
        }
      ] + (.routing.rules // [])
  ' "$cfg" > "$cfg.mubx" || return 1
  mv -f "$cfg.mubx" "$cfg"
}

mubx_ensure_subscribe_loaded() {
  if ! declare -F mubx_ss2022_psk >/dev/null 2>&1; then
    if [ -r /usr/local/lib/mubx/subscribe.sh ]; then
      source /usr/local/lib/mubx/subscribe.sh
    elif [ -r "$(dirname "${BASH_SOURCE[0]}")/subscribe.sh" ]; then
      source "$(dirname "${BASH_SOURCE[0]}")/subscribe.sh"
    fi
  fi
}

# Render /usr/local/etc/xray/config.json from the base template plus the
# per-user Shadowsocks inbounds (WS, plain TCP) and the shared SS-443
# inbound that HAProxy fronts on public port 443.
mubx_xray_render() { # $1 base_template  $2 out
  local base_tpl="$1" out="$2"
  local tmpbase
  tmpbase="$(mktemp /tmp/mubx-xray-base.XXXXXX)"
  sed -e "s|__UUID__|${UUID:?UUID is not loaded; run the installer first}|g" \
      -e "s|__DOMAIN__|${DOMAIN:?DOMAIN is not loaded; run the installer first}|g" \
    "$base_tpl" > "$tmpbase" || { rm -f "$tmpbase"; return 1; }
  jq '.' "$tmpbase" > "$out" || {
    rm -f "$tmpbase"
    return 1
  }
  rm -f "$tmpbase"
  # Multi-user overlay (per-user identities + stats API) when a store exists.
  mubx_users_apply "$out" || return 1
  # Cloudflare WARP smart outbound overlay when configured.
  mubx_warp_apply "$out" || return 1
  # Per-user Shadowsocks WS inbounds (one loopback port + ws path per user).
  mubx_ss_apply "$out" "$(dirname "$base_tpl")/ss-inbound.json" || return 1
  # Per-user plain Shadowsocks TCP inbounds (one public port per user).
  mubx_ss_plain_apply "$out" "$(dirname "$base_tpl")/ss-plain-inbound.json" || return 1
  # Shared Shadowsocks on 443 (HAProxy non-TLS frontend -> this inbound).
  mubx_ss_443_apply "$out" "$(dirname "$base_tpl")/ss-443-inbound.json" || return 1
  # Per-user Shadowsocks 2022 WS inbounds (opt-in: needs /etc/mubx/ss2022-keys).
  mubx_ensure_subscribe_loaded
  if declare -F mubx_ss2022_psk >/dev/null 2>&1; then
    mubx_ss2022_apply "$out" "$(dirname "$base_tpl")/ss-2022-inbound.json" || return 1
  fi
  chmod 0600 "$out" 2>/dev/null || true
}

# Emit one tab-separated row per Shadowsocks user: name, uuid (the SS
# password), loopback port (SS_BASE_PORT + store index) and the ws path
# (/ss-<name>). Without a store (pre-upgrade host) the legacy admin
# identity is used, matching how xray configs were rendered before.
mubx_ss_plan() { # $1 base port (default SS_BASE_PORT, i.e. the loopback WS ports)
  local base="${1:-$SS_BASE_PORT}"
  if [ -f "$MUBX_USERS_FILE" ]; then
    jq -r --argjson base "$base" "
      ${JQ_PROTO_DEF}
      range(0; length) as \$i | .[\$i] |
      select((.status // \"active\") != \"frozen\") |
      select((.uuid // \"\") != \"\") |
      select(user_has_proto(\"ss-ws\")) |
      [.name, .uuid, (\$base + \$i), (\"/ss-\" + .name)] | @tsv
    " "$MUBX_USERS_FILE" 2>/dev/null || true
  else
    printf '%s\t%s\t%s\t%s\n' "admin" "${UUID:-}" "$base" "/ss-admin"
  fi
}

# Emit one tab-separated row per Shadowsocks 2022 user with /ss22-<name> path
# and 11006+ loopback port so Nginx and Xray route SS-2022 cleanly.
mubx_ss2022_plan() { # $1 base port (default SS_BASE_PORT + 1000)
  local base="${1:-$(( SS_BASE_PORT + 1000 ))}"
  if [ -f "$MUBX_USERS_FILE" ]; then
    jq -r --argjson base "$base" "
      ${JQ_PROTO_DEF}
      range(0; length) as \$i | .[\$i] |
      select((.status // \"active\") != \"frozen\") |
      select((.uuid // \"\") != \"\") |
      select(user_has_proto(\"ss-2022\")) |
      [.name, .uuid, (\$base + \$i), (\"/ss22-\" + .name)] | @tsv
    " "$MUBX_USERS_FILE" 2>/dev/null || true
  else
    printf '%s\t%s\t%s\t%s\n' "admin" "${UUID:-}" "$base" "/ss22-admin"
  fi
}

# Append one Shadowsocks WS inbound per store user to a rendered config.
# A missing template simply skips SS (older templates stay valid).
mubx_ss_apply() { # $1 rendered config file  $2 ss inbound template
  local cfg="$1" ss_tpl="$2" extra='[]' obj name uuid port path
  [ -f "$ss_tpl" ] || return 0
  while IFS=$'\t' read -r name uuid port path; do
    [ -n "$name" ] && [ -n "$uuid" ] || continue
    obj="$(sed -e "s|__SS_NAME__|$name|g" \
               -e "s|__SS_PORT__|$port|g" \
               -e "s|__SS_PASS__|$uuid|g" \
               -e "s|__SS_PATH__|$path|g" "$ss_tpl")" || return 1
    extra="$(jq -cn --argjson arr "$extra" --argjson o "$obj" '$arr + [$o]')" \
      || return 1
  done < <(mubx_ss_plan)
  if [ "$extra" != '[]' ]; then
    jq --argjson add "$extra" '.inbounds += $add' "$cfg" > "$cfg.mubx" || return 1
    mv -f "$cfg.mubx" "$cfg"
  fi
}

# Append one plain Shadowsocks TCP inbound (raw SS, no WebSocket / no TLS)
# per store user, binding publicly on SS_PLAIN_BASE_PORT + index.
mubx_ss_plain_apply() { # $1 rendered config file  $2 ss plain inbound template
  local cfg="$1" ss_tpl="$2" extra='[]' obj name uuid port path
  [ -f "$ss_tpl" ] || return 0
  while IFS=$'\t' read -r name uuid port path; do
    [ -n "$name" ] && [ -n "$uuid" ] || continue
    obj="$(sed -e "s|__SS_NAME__|$name|g" \
               -e "s|__SS_PORT__|$port|g" \
               -e "s|__SS_PASS__|$uuid|g" "$ss_tpl")" || return 1
    extra="$(jq -cn --argjson arr "$extra" --argjson o "$obj" '$arr + [$o]')" \
      || return 1
  done < <(
    if [ -f "$MUBX_USERS_FILE" ]; then
      jq -r --argjson base "${SS_PLAIN_BASE_PORT:-8388}" "
        ${JQ_PROTO_DEF}
        range(0; length) as \$i | .[\$i] |
        select((.status // \"active\") != \"frozen\") |
        select((.uuid // \"\") != \"\") |
        select(user_has_proto(\"ss-tcp\")) |
        [.name, .uuid, (\$base + \$i), (\"/ss-\" + .name)] | @tsv
      " "$MUBX_USERS_FILE" 2>/dev/null || true
    else
      printf '%s\t%s\t%s\t%s\n' "admin" "${UUID:-}" "${SS_PLAIN_BASE_PORT:-8388}" "/ss-admin"
    fi
  )
  if [ "$extra" != '[]' ]; then
    jq --argjson add "$extra" '.inbounds += $add' "$cfg" > "$cfg.mubx" || return 1
    mv -f "$cfg.mubx" "$cfg"
  fi
}

# Append one Shadowsocks 2022 WS inbound per store user (loopback port
# SS_BASE_PORT + 1000 + index) using the modern 2022-blake3-aes-256-gcm
# cipher. Keys come from mubx_ss2022_psk (lib/subscribe.sh), created once
# and reused so rendered configs stay stable across re-renders.
mubx_ss2022_apply() { # $1 rendered config file  $2 ss-2022 inbound template
  local cfg="$1" ss_tpl="$2" extra='[]' obj name uuid port path psk
  [ -f "$ss_tpl" ] || return 0
  mubx_ensure_subscribe_loaded
  [ -d "${MUBX_SS2022_DIR:-/etc/mubx/ss2022-keys}" ] || return 0
  while IFS=$'\t' read -r name uuid port path; do
    [ -n "$name" ] && [ -n "$uuid" ] || continue
    psk="$(mubx_ss2022_psk "$name")"
    [ -n "$psk" ] || continue
    obj="$(sed -e "s|__SS_NAME__|$name|g" \
               -e "s|__SS_PORT__|$port|g" \
               -e "s|__SS_PASS__|$psk|g" \
               -e "s|__SS_PATH__|$path|g" "$ss_tpl")" || return 1
    extra="$(jq -cn --argjson arr "$extra" --argjson o "$obj" '$arr + [$o]')" \
      || return 1
  done < <(mubx_ss2022_plan $(( SS_BASE_PORT + 1000 )))
  if [ "$extra" != '[]' ]; then
    jq --argjson add "$extra" '.inbounds += $add' "$cfg" > "$cfg.mubx" || return 1
    mv -f "$cfg.mubx" "$cfg"
  fi
}

# Append the shared SS-on-443 inbound: a Shadowsocks listener on the
# loopback SS_443_LOOPBACK_PORT. HAProxy sends every non-TLS public-443
# connection here. We populate .settings.clients with all active users who have
# ss-tcp permission so every user can connect directly on Port 443 without TLS.
mubx_ss_443_apply() { # $1 rendered config file  $2 ss-443 inbound template
  local cfg="$1" ss_tpl="$2" obj pass clients
  [ -f "$ss_tpl" ] || return 0
  pass="$(mubx_primary_uuid)"
  [ -n "$pass" ] || return 0
  obj="$(sed -e "s|__SS_PORT__|$SS_443_LOOPBACK_PORT|g" \
             -e "s|__SS_PASS__|$pass|g" "$ss_tpl")" || return 1
  if [ -f "$MUBX_USERS_FILE" ]; then
    clients="$(jq -c "
      ${JQ_PROTO_DEF}
      [
        .[] |
        select((.status // \"active\") != \"frozen\") |
        select((.uuid // \"\") != \"\") |
        select(user_has_proto(\"ss-tcp\")) |
        {password: .uuid, method: \"aes-256-gcm\", email: (.name + \"@\" + \"${DOMAIN:-mubx}\")}
      ]
    " "$MUBX_USERS_FILE" 2>/dev/null || echo '[]')"
    if [ "$clients" != '[]' ] && [ -n "$clients" ]; then
      obj="$(jq --argjson c "$clients" '.settings.clients = $c' <<< "$obj")"
    fi
  fi
  jq --argjson o "$obj" '.inbounds += [$o]' "$cfg" > "$cfg.mubx" || return 1
  mv -f "$cfg.mubx" "$cfg"
}

# Render /etc/sing-box/config.json: ShadowTLS v3 on loopback 8448 (HAProxy
# SNI-routes the decoy hello there from public 443) whose
# "detour" carries a bare SS-2022 inbound (the admin/primary identity's
# 32-byte key from lib/subscribe.sh). Clients speak TLS to a decoy SNI and
# only then negotiate SS-2022 - the strongest anti-DPI pairing MUB-X ships.
# Skipped when lib/subscribe.sh is unavailable (CI renders without keys).
mubx_singbox_render() { # $1 template  $2 out
  local tpl="$1" out="$2" ss22_pass tuic_users admin_uuid dom
  mubx_ensure_subscribe_loaded
  declare -F mubx_ss2022_psk >/dev/null 2>&1 || return 0
  ss22_pass="$(mubx_ss2022_psk admin)"
  [ -n "$ss22_pass" ] || return 0
  [ -n "${SHADOWTLS_PASS:-}" ] || {
    echo "[!] SHADOWTLS_PASS is not loaded; cannot render the sing-box config." >&2
    return 1
  }
  admin_uuid="$(mubx_primary_uuid)"
  [ -n "$admin_uuid" ] || admin_uuid="${UUID:-11111111-2222-3333-4444-555555555555}"
  dom="${DOMAIN:-}"
  [ -n "$dom" ] || dom="$(cat /etc/mubx/domain 2>/dev/null || cat /usr/local/etc/xray/domain 2>/dev/null || echo example.com)"
  sed -e "s|__SHADOWTLS_PASS__|$SHADOWTLS_PASS|g" \
      -e "s|__SNI_FRONT__|${SHADOWTLS_SNI:-www.microsoft.com}|g" \
      -e "s|__SS22_ADMIN_PASS__|$ss22_pass|g" \
      -e "s|__ADMIN_UUID__|$admin_uuid|g" \
      -e "s|__DOMAIN__|$dom|g" "$tpl" > "$out"
  if [ -f "$MUBX_USERS_FILE" ]; then
    tuic_users="$(jq -c "
      ${JQ_PROTO_DEF}
      [.[] | select((.status // \"active\") != \"frozen\") | select(user_has_proto(\"tuic\")) | {name: .name, uuid: .uuid, password: .uuid}]
    " "$MUBX_USERS_FILE" 2>/dev/null || true)"
    if [ -n "$tuic_users" ] && [ "$tuic_users" != "[]" ] && [ "$tuic_users" != "null" ]; then
      jq --argjson tu "$tuic_users" '.inbounds |= map(if .tag == "tuic-in" then .users = $tu else . end)' "$out" > "$out.tmp" && mv -f "$out.tmp" "$out"
    fi
  fi
  chmod 0600 "$out" 2>/dev/null || true
}

mubx_hysteria_render() { # $1 template  $2 out
  local tpl="$1" out="$2" dom="${DOMAIN:-}" pass="${HY2_PASS:-}"
  if [ -z "$dom" ] && [ -r /etc/mubx/domain ]; then
    dom="$(cat /etc/mubx/domain)"
  fi
  if [ -z "$pass" ] && [ -r /etc/telecom-engine.env ]; then
    pass="$(sed -n 's/^HY2_PASS=//p' /etc/telecom-engine.env | head -n1)"
  fi
  [ -n "$dom" ] || dom="example.com"
  [ -n "$pass" ] || pass="mubx-secret-hy2"
  sed -e "s|__DOMAIN__|$dom|g; s|__HY2_PASS__|$pass|g" "$tpl" > "$out"
  chmod 0600 "$out" 2>/dev/null || true
}

# Render /etc/nginx/nginx.conf from the template: expand the shared
# #MUBX_SS_LOCATIONS# marker (one /ss-<user> and /ss22-<user> location block
# per user) in both the port-80 and the loopback-TLS server, and substitute
# the domain and SSH-over-WS secret path placeholders that install.sh used to
# handle with raw sed. Validated with `nginx -t` by every caller before use.
mubx_nginx_render() { # $1 template  $2 out
  local tmpl="$1" out="$2" content ss_blocks='' name uuid port path
  content="$(< "$tmpl")" || return 1
  while IFS=$'\t' read -r name uuid port path; do
    [ -n "$name" ] || continue
    printf -v ss_blocks '%s    location %s {\n        proxy_pass http://127.0.0.1:%s;\n        proxy_http_version 1.1;\n        proxy_set_header Upgrade $http_upgrade;\n        proxy_set_header Connection $connection_upgrade;\n        proxy_set_header Host $http_host;\n        proxy_read_timeout 86400s;\n        proxy_send_timeout 86400s;\n    }\n\n' \
      "$ss_blocks" "$path" "$port"
  done < <(mubx_ss_plan)
  mubx_ensure_subscribe_loaded
  if [ -d "${MUBX_SS2022_DIR:-/etc/mubx/ss2022-keys}" ] || declare -F mubx_ss2022_psk >/dev/null 2>&1; then
    while IFS=$'\t' read -r name uuid port path; do
      [ -n "$name" ] || continue
      printf -v ss_blocks '%s    location %s {\n        proxy_pass http://127.0.0.1:%s;\n        proxy_http_version 1.1;\n        proxy_set_header Upgrade $http_upgrade;\n        proxy_set_header Connection $connection_upgrade;\n        proxy_set_header Host $http_host;\n        proxy_read_timeout 86400s;\n        proxy_send_timeout 86400s;\n    }\n\n' \
        "$ss_blocks" "$path" "$port"
    done < <(mubx_ss2022_plan $(( SS_BASE_PORT + 1000 )))
  fi
  content="${content//$SS_MARKER/$ss_blocks}"
  [ -n "${DOMAIN:-}" ] || {
    echo "[!] DOMAIN is not loaded; cannot render nginx.conf." >&2
    return 1
  }
  [ -n "${SSH_WS_PATH:-}" ] || {
    echo "[!] SSH_WS_PATH is not loaded; cannot render nginx.conf." >&2
    return 1
  }
  content="${content//__DOMAIN__/$DOMAIN}"
  content="${content//__SSH_WS_PATH__/$SSH_WS_PATH}"
  printf '%s' "$content" > "$out"
}

# Render /etc/haproxy/haproxy.cfg. The template is static (no Reality SNI
# fronts any more): TLS ClientHello -> nginx loopback terminator; every
# non-TLS connection on public 443 -> the shared SS-443 inbound.
mubx_haproxy_render() { # $1 template  $2 out
  local tmpl="$1" out="$2"
  sed -e "s|__DOMAIN__|${DOMAIN:-localhost}|g" \
      -e "s|__SHADOWTLS_SNI__|${SHADOWTLS_SNI:-www.microsoft.com}|g" \
    "$tmpl" > "$out"
}
