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
  jq --argjson add "$extra" '.inbounds += $add' "$tmpbase" > "$out"
  rm -f "$tmpbase"
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
