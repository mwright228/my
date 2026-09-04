#!/bin/bash
# Reality multi-front configuration builders.
#
# Reality (XTLS) can camouflage against ANY real HTTPS site, not just Apple.
# Each configured "front" (an SNI host your clients' carriers permit) gets
# its own Xray Reality inbound on 127.0.0.1:10443+N plus a matching HAProxy
# SNI route on public port 443, so different users on different SIM
# networks can each use the front their carrier allows.
#
# The active front list lives in REALITY_FRONTS inside
# /etc/telecom-engine.env (space separated, quoted). It is managed with
# `reality-fronts` (menu option 10) and seeded during install.sh.
#
# Sourced by install.sh and bin/reality-fronts. Requires jq, which
# install.sh always provisions.

REALITY_MARKER_RULES='#MUBX_REALITY_RULES#'
REALITY_MARKER_BACKENDS='#MUBX_REALITY_BACKENDS#'
REALITY_BASE_PORT=10443
REALITY_MAX_FRONTS=12
SS_MARKER='#MUBX_SS_LOCATIONS#'
SS_BASE_PORT=10006

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
        (.streamSettings.realitySettings? != null) as $reality |
        .settings.clients = (
          if .protocol == "trojan" then
            [ $users[] | {password: .uuid, email: (.name + "@" + $dom)} ]
          elif .protocol == "vmess" then
            [ $users[] | {id: .uuid, alterId: 0, email: (.name + "@" + $dom)} ]
          else
            [ $users[] |
              {id: .uuid, email: (.name + "@" + $dom)} +
              (if $reality then {flow: "xtls-rprx-vision"} else {} end)
            ]
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

# Parse a space separated list into the global FRONTS array.
reality_fronts() { # $1 optional raw list (default: $REALITY_FRONTS or apple)
  local raw="${1:-${REALITY_FRONTS:-www.apple.com}}"
  FRONTS=()
  # shellcheck disable=SC2206
  read -r -a FRONTS <<< "$raw"
}

front_syntax_ok() {
  [[ "$1" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]]
}

# A front must resolve and answer on TCP 443 from this host: Xray relays
# probe handshakes and fetches the real certificate chain from dest.
front_reachable() {
  local host="$1"
  getent ahostsv4 "$host" >/dev/null 2>&1 || getent hosts "$host" >/dev/null 2>&1 || return 1
  timeout 6 bash -c ":</dev/tcp/$host/443" 2>/dev/null
}

# Validate a whole candidate list; echoes nothing, nonzero on any bad front.
front_list_validate() {
  local f
  for f in "$@"; do
    front_syntax_ok "$f" || { echo "[!] Invalid front domain: $f" >&2; return 1; }
    [ "$f" != "${DOMAIN:-}" ] || {
      echo "[!] A front cannot be your own domain ($f); pick a third-party HTTPS site." >&2
      return 1
    }
    front_reachable "$f" || {
      echo "[!] $f does not resolve or TCP 443 is unreachable from this host; Reality must relay to it." >&2
      return 1
    }
  done
}

# Rewrite one key inside the env file, preserving every other key. Keys are
# whitelisted, so unknown keys can never sneak back in.
mubx_env_set() { # $1 key  $2 value  [$3 env file]
  local key="$1" value="$2" env_file="${3:-/etc/telecom-engine.env}"
  local tmp k v
  declare -A kv=()
  [ -f "$env_file" ] || { echo "[!] Missing $env_file" >&2; return 1; }
  while IFS='=' read -r k v; do
    case "$k" in
      DOMAIN|UUID|REALITY_PRIVKEY|REALITY_PUBKEY|SHORT_ID|HY2_PASS|ZIVPN_PASS|SSH_WS_PATH|REALITY_FRONTS)
        v="${v#\"}"; v="${v%\"}"
        kv["$k"]="$v"
        ;;
    esac
  done < "$env_file"
  kv["$key"]="$value"
  tmp="$(mktemp "${env_file}.XXXXXX")"
  umask 077
  for k in DOMAIN UUID REALITY_PRIVKEY REALITY_PUBKEY SHORT_ID \
           HY2_PASS ZIVPN_PASS SSH_WS_PATH REALITY_FRONTS; do
    [ -n "${kv[$k]:-}" ] || continue
    printf '%s="%s"\n' "$k" "${kv[$k]}" >> "$tmp"
  done
  chmod 0600 "$tmp"
  mv -f "$tmp" "$env_file"
}

# Render /usr/local/etc/xray/config.json from the base template (which keeps
# the non-Reality inbounds) plus one generated Reality inbound per front.
mubx_xray_render() { # $1 base_template  $2 inbound_template  $3 out
  local base_tpl="$1" inbound_tpl="$2" out="$3"
  local base tmpbase i=0 extra='[]' obj f
  tmpbase="$(mktemp /tmp/mubx-xray-base.XXXXXX)"
  sed -e "s|__UUID__|${UUID:?UUID is not loaded; run the installer first}|g" \
    "$base_tpl" > "$tmpbase" || { rm -f "$tmpbase"; return 1; }
  reality_fronts
  for f in "${FRONTS[@]}"; do
    [ -n "$f" ] || continue
    if [ "$i" -ge "$REALITY_MAX_FRONTS" ]; then
      echo "[!] Too many Reality fronts (max $REALITY_MAX_FRONTS)." >&2
      rm -f "$tmpbase"
      return 1
    fi
    obj="$(sed -e "s|__PORT__|$((REALITY_BASE_PORT + i))|g" \
               -e "s|__DEST__|$f|g" \
               -e "s|__SNI__|$f|g" \
               -e "s|__UUID__|${UUID}|g" \
               -e "s|__REALITY_PRIVKEY__|${REALITY_PRIVKEY:?Reality private key missing}|g" \
               -e "s|__SHORT_ID__|${SHORT_ID:?Reality short id missing}|g" \
               "$inbound_tpl")" || { rm -f "$tmpbase"; return 1; }
    extra="$(jq -cn --argjson arr "$extra" --argjson o "$obj" '$arr + [$o]')" \
      || { rm -f "$tmpbase"; return 1; }
    i=$((i + 1))
  done
  jq --argjson add "$extra" '.inbounds += $add' "$tmpbase" > "$out" || {
    rm -f "$tmpbase"
    return 1
  }
  rm -f "$tmpbase"
  # Multi-user overlay (per-user identities + stats API) when a store exists.
  mubx_users_apply "$out" || return 1
  # Per-user Shadowsocks WS inbounds (one loopback port + ws path per user).
  mubx_ss_apply "$out" "$(dirname "$base_tpl")/ss-inbound.json" || return 1
}

# Emit one tab-separated row per Shadowsocks user: name, uuid (the SS
# password), loopback port (10006 + store index) and the ws path
# (/ss-<name>). Without a store (pre-upgrade host) the legacy admin
# identity is used, matching how xray configs were rendered before.
mubx_ss_plan() {
  if [ -f "$MUBX_USERS_FILE" ]; then
    jq -r --argjson base "$SS_BASE_PORT" \
      'range(0; length) as $i | .[$i] |
       select((.uuid // "") != "") |
       [.name, .uuid, ($base + $i), ("/ss-" + .name)] | @tsv' \
      "$MUBX_USERS_FILE" 2>/dev/null || true
  else
    printf '%s\t%s\t%s\t%s\n' "admin" "${UUID:-}" "$SS_BASE_PORT" "/ss-admin"
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

# Render /etc/haproxy/haproxy.cfg: expand the two markers in the template
# with one exact-SNI acl + backend per configured front.
mubx_haproxy_render() { # $1 template  $2 out
  local tmpl="$1" out="$2"
  local content rules='' backends='' i=0 f
  reality_fronts
  for f in "${FRONTS[@]}"; do
    [ -n "$f" ] || continue
    printf -v rules '%s    # front %s\n    acl is_reality_%d req_ssl_sni -i %s\n    use_backend srv_reality_%d if is_reality_%d\n' \
      "$rules" "$f" "$i" "$f" "$i" "$i"
    printf -v backends '%sbackend srv_reality_%d\n    mode tcp\n    server srv_reality_%d 127.0.0.1:%d\n\n' \
      "$backends" "$i" "$i" "$((REALITY_BASE_PORT + i))"
    i=$((i + 1))
  done
  content="$(< "$tmpl")"
  content="${content//$REALITY_MARKER_RULES/$rules}"
  content="${content//$REALITY_MARKER_BACKENDS/$backends}"
  printf '%s' "$content" > "$out"
}
