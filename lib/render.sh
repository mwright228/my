#!/bin/bash
# MUB-X configuration renderers (Xray / HAProxy / Nginx).
#
# Xray: renders the base template (VLESS/VMess/Trojan WS, HTTPUpgrade,
# xHTTP, plain VLESS TCP+TLS on 8443) and then appends the per-user
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
# against a scratch copy. Each entry: {name, uuid, added, expiry}.
MUBX_USERS_FILE="${MUBX_USERS_FILE:-/etc/mubx/users.json}"

mubx_users_count() { # $1 store file -> number of users (0 on any error)
  jq -r 'length' "$1" 2>/dev/null || printf '0\n'
}

# Idempotent: seed the store with the legacy single identity ("admin") so
# pre-existing client links keep working after an upgrade.
mubx_users_seed() {
  [ -n "${UUID:-}" ] || return 0
  if [ ! -f "$MUBX_USERS_FILE" ]; then
    install -d -m 0700 "$(dirname "$MUBX_USERS_FILE")"
    printf '[{"name":"admin","uuid":"%s","added":"%s","expiry":""}]\n' \
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
    u="$(jq -r '.[] | select(.name == "admin") | .uuid' "$MUBX_USERS_FILE" 2>/dev/null | head -n 1)"
    [ -n "$u" ] && printf '%s\n' "$u" && return 0
  fi
  printf '%s\n' "${UUID:-}"
}

# Overlay the per-user client identities onto a rendered Xray config and
# enable the stats API (policy + dokodemo api inbound + routing) so traffic
# can be queried per user email with `xray api statsquery`. When the store
# is absent or empty the config is left in the legacy single-identity shape.
mubx_users_apply() { # $1 rendered config file
  local users_json n dom
  [ -f "$MUBX_USERS_FILE" ] || return 0
  users_json="$(jq -c '[.[] | {name: (.name // ""), uuid: (.uuid // "")} | select(.uuid != "")]' "$MUBX_USERS_FILE" 2>/dev/null || true)"
  n="$(mubx_users_count "$MUBX_USERS_FILE")"
  [ "$n" -ge 1 ] || return 0
  dom="${DOMAIN:-mubx}"
  jq --argjson users "$users_json" --arg dom "$dom" '
    .inbounds |= map(
      if (.settings.clients? == null) then .
      else
        .settings.clients = (
          if .protocol == "trojan" then
            [ $users[] | {password: .uuid, email: (.name + "@" + $dom)} ]
          elif .protocol == "vmess" then
            [ $users[] | {id: .uuid, alterId: 0, email: (.name + "@" + $dom)} ]
          else
            [ $users[] | {id: .uuid, email: (.name + "@" + $dom)} ]
          end
        )
      end
    )
    | .api = {tag: "api", services: ["HandlerService", "StatsService"]}
    | .policy = {levels: {"0": {statsUserUplink: true, statsUserDownlink: true}}}
    | .inbounds += [{tag: "api", listen: "127.0.0.1", port: 10085, protocol: "dokodemo-door", settings: {address: "127.0.0.1", network: "tcp"}}]
    | .routing = {rules: [{type: "field", inboundTag: ["api"], outboundTag: "direct"}]}
  ' "$1" > "$1.mubx" && mv -f "$1.mubx" "$1"
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
  # Per-user Shadowsocks WS inbounds (one loopback port + ws path per user).
  mubx_ss_apply "$out" "$(dirname "$base_tpl")/ss-inbound.json" || return 1
  # Per-user plain Shadowsocks TCP inbounds (one public port per user).
  mubx_ss_plain_apply "$out" "$(dirname "$base_tpl")/ss-plain-inbound.json" || return 1
  # Shared Shadowsocks on 443 (HAProxy non-TLS frontend -> this inbound).
  mubx_ss_443_apply "$out" "$(dirname "$base_tpl")/ss-443-inbound.json" || return 1
  # Per-user Shadowsocks 2022 WS inbounds (opt-in: needs /etc/mubx/ss2022-keys).
  # shellcheck disable=SC2154  # subscribe.sh may not be sourced in CI renders
  if declare -F mubx_ss2022_psk >/dev/null 2>&1; then
    mubx_ss2022_apply "$out" "$(dirname "$base_tpl")/ss-2022-inbound.json" || return 1
  fi
}

# Emit one tab-separated row per Shadowsocks user: name, uuid (the SS
# password), loopback port (SS_BASE_PORT + store index) and the ws path
# (/ss-<name>). Without a store (pre-upgrade host) the legacy admin
# identity is used, matching how xray configs were rendered before.
mubx_ss_plan() { # $1 base port (default SS_BASE_PORT, i.e. the loopback WS ports)
  local base="${1:-$SS_BASE_PORT}"
  if [ -f "$MUBX_USERS_FILE" ]; then
    jq -r --argjson base "$base" \
      'range(0; length) as $i | .[$i] |
       select((.uuid // "") != "") |
       [.name, .uuid, ($base + $i), ("/ss-" + .name)] | @tsv' \
      "$MUBX_USERS_FILE" 2>/dev/null || true
  else
    printf '%s\t%s\t%s\t%s\n' "admin" "${UUID:-}" "$base" "/ss-admin"
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
# per store user, binding publicly on SS_PLAIN_BASE_PORT + index. This lets
# any Shadowsocks client - v2rayNG, sing-box, Shadowrocket - connect without
# a plugin or any certificate handling, and doubles as an odd-port channel
# past carrier filters that only police 80/443/8080. A missing template
# simply skips plain SS (older templates stay valid).
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
  done < <(mubx_ss_plan "$SS_PLAIN_BASE_PORT")
  if [ "$extra" != '[]' ]; then
    jq --argjson add "$extra" '.inbounds += $add' "$cfg" > "$cfg.mubx" || return 1
    mv -f "$cfg.mubx" "$cfg"
  fi
}

# Append one Shadowsocks 2022 WS inbound per store user (loopback port
# SS_BASE_PORT + 1000 + index) using the modern 2022-blake3-aes-256-gcm
# cipher. Keys come from mubx_ss2022_psk (lib/subscribe.sh), created once
# and reused so rendered configs stay stable across re-renders. A missing
# template or missing keys directory simply skips SS-2022 (older setups and
# CI renders stay valid).
mubx_ss2022_apply() { # $1 rendered config file  $2 ss-2022 inbound template
  local cfg="$1" ss_tpl="$2" extra='[]' obj name uuid port path psk
  [ -f "$ss_tpl" ] || return 0
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
  done < <(mubx_ss_plan $(( SS_BASE_PORT + 1000 )))
  if [ "$extra" != '[]' ]; then
    jq --argjson add "$extra" '.inbounds += $add' "$cfg" > "$cfg.mubx" || return 1
    mv -f "$cfg.mubx" "$cfg"
  fi
}

# Append the shared SS-on-443 inbound: a single Shadowsocks listener on the
# loopback SS_443_LOOPBACK_PORT carrying the primary identity's password.
# HAProxy sends every non-TLS public-443 connection here, so this is the
# "Shadowsocks TCP on 443" route that works in any plain SS client.
mubx_ss_443_apply() { # $1 rendered config file  $2 ss-443 inbound template
  local cfg="$1" ss_tpl="$2" obj pass
  [ -f "$ss_tpl" ] || return 0
  pass="$(mubx_primary_uuid)"
  [ -n "$pass" ] || return 0
  obj="$(sed -e "s|__SS_PORT__|$SS_443_LOOPBACK_PORT|g" \
             -e "s|__SS_PASS__|$pass|g" "$ss_tpl")" || return 1
  jq --argjson o "$obj" '.inbounds += [$o]' "$cfg" > "$cfg.mubx" || return 1
  mv -f "$cfg.mubx" "$cfg"
}

# Render /etc/nginx/nginx.conf from the template: expand the shared
# #MUBX_SS_LOCATIONS# marker (one /ss-<user> location block per user) in
# both the port-80 and the loopback-TLS server, and substitute the domain
# and SSH-over-WS secret path placeholders that install.sh used to handle
# with raw sed. Validated with `nginx -t` by every caller before use.
mubx_nginx_render() { # $1 template  $2 out
  local tmpl="$1" out="$2" content ss_blocks='' name uuid port path
  content="$(< "$tmpl")" || return 1
  while IFS=$'\t' read -r name uuid port path; do
    [ -n "$name" ] || continue
    printf -v ss_blocks '%s    location %s {\n        proxy_pass http://127.0.0.1:%s;\n        proxy_http_version 1.1;\n        proxy_set_header Upgrade $http_upgrade;\n        proxy_set_header Connection $connection_upgrade;\n        proxy_set_header Host $http_host;\n        proxy_read_timeout 86400s;\n        proxy_send_timeout 86400s;\n    }\n\n' \
      "$ss_blocks" "$path" "$port"
  done < <(mubx_ss_plan)
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
  install -m 0644 "$tmpl" "$out"
}
