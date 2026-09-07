#!/usr/bin/env bash
# Static checks for the MUB-X repository. Runs in CI (see ci.yml) and can be
# run locally from anywhere:  bash .github/ci-check.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0

say() { printf '== %s\n' "$*"; }

say "shell syntax"
for f in install.sh bin/* lib/*.sh .github/ci-check.sh; do
  [ -f "$f" ] || continue
  if ! bash -n "$f"; then
    echo "SYNTAX FAIL: $f"
    fail=1
  fi
done

say "JSON validity (templates and examples)"
for f in configs/*.json examples/*.json; do
  [ -f "$f" ] || continue
  # ss-inbound.json, ss-plain-inbound.json, ss-443-inbound.json and
  # ss-2022-inbound.json are per-item fragments with a numeric placeholder
  # ("port": __SS_PORT__), so standalone JSON parsing does not apply to
  # them; the render smoke tests below validate the substituted results.
  case "$(basename "$f")" in ss-inbound.json|ss-plain-inbound.json|ss-443-inbound.json|ss-2022-inbound.json) continue ;; esac
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" 2>/dev/null; then
    echo "JSON FAIL: $f"
    fail=1
  fi
done

render_xray() { # $1 tmpdir  $2 users store (may be empty)  -> writes "$1/xray.json" "$1/haproxy.cfg"
  local tmp="$1" users="$2"
  (
    export DOMAIN="example.com"
    if [ -n "$users" ]; then
      export MUBX_USERS_FILE="$users"
    fi
    export UUID="11111111-2222-3333-4444-555555555555"
    bash -c 'source lib/render.sh &&
             mubx_xray_render configs/xray.json "$1" &&
             mubx_haproxy_render configs/haproxy.cfg "$2"' \
      x "$tmp/xray.json" "$tmp/haproxy.cfg"
  )
}

render_nginx() { # $1 tmpdir  $2 users store (may be empty)  -> writes "$1/nginx.conf"
  local tmp="$1" users="$2"
  (
    export DOMAIN="example.com" \
      SSH_WS_PATH="/ssh-ci-9f2a"
    if [ -n "$users" ]; then
      export MUBX_USERS_FILE="$users"
    fi
    export UUID="11111111-2222-3333-4444-555555555555"
    bash -c 'source lib/render.sh && mubx_nginx_render configs/nginx.conf "$1"' \
      x "$tmp/nginx.conf"
  )
}

# The every-TLS-on-443 invariant every valid render must uphold: no Xray
# TLS inbound on any port other than 443 (WS transports terminate at nginx
# on loopback 20443; sing-box's ShadowTLS rides 443 via SNI on loopback
# 8448). Also verifies the vless-tls-tcp inbound is gone.
check_tls_ports() { # $1 xray config
  python3 - "$1" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
bad = [i["tag"] for i in cfg["inbounds"]
       if (i.get("streamSettings") or {}).get("security") == "tls"
       and i.get("port") != 443]
assert not bad, "TLS inbounds on non-443 ports: %s" % bad
assert not any(i["tag"] == "vless-tls-tcp" for i in cfg["inbounds"]), \
    "vless-tls-tcp inbound should be removed"
print("  all Xray TLS inbounds on 443 only")
PY
}

# The one Shadowsocks WS inbound every valid render must carry for a user.
check_ss_user() { # $1 xray config  $2 name  $3 uuid  $4 port
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
name, uuid, port = sys.argv[2], sys.argv[3], int(sys.argv[4])
matches = [i for i in cfg["inbounds"] if i["tag"] == "shadowsocks-" + name]
assert len(matches) == 1, "expected 1 shadowsocks-%s inbound" % name
ss = matches[0]
assert ss["port"] == port, (name, ss["port"], port)
assert ss["settings"]["method"] == "aes-256-gcm"
assert ss["settings"]["password"] == uuid
assert ss["streamSettings"]["network"] == "ws"
assert ss["streamSettings"]["wsSettings"]["path"] == "/ss-" + name
print("  ss inbound OK: %s -> %d%s" % (name, port, "/ss-" + name))
PY
}

# The plain (non-WS) Shadowsocks inbound every valid render must carry for a
# user: public bind, raw SS TCP, no TLS.
check_ss_plain_user() { # $1 xray config  $2 name  $3 uuid  $4 port
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
name, uuid, port = sys.argv[2], sys.argv[3], int(sys.argv[4])
matches = [i for i in cfg["inbounds"] if i["tag"] == "ssplain-" + name]
assert len(matches) == 1, "expected 1 ssplain-%s inbound" % name
ss = matches[0]
assert ss["listen"] == "0.0.0.0", ss["listen"]
assert ss["port"] == port, (name, ss["port"], port)
assert ss["settings"]["method"] == "aes-256-gcm"
assert ss["settings"]["password"] == uuid
assert ss["streamSettings"]["network"] == "tcp"
print("  ss plain TCP OK: %s -> :%d" % (name, port))
PY
}

# The shared SS-443 inbound every render must carry: loopback listener that
# HAProxy fronts from public 443, carrying the primary (admin) password.
check_ss443() { # $1 xray config  $2 expected password
  python3 - "$1" "$2" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
passwd = sys.argv[2]
matches = [i for i in cfg["inbounds"] if i["tag"] == "ss-443"]
assert len(matches) == 1, "expected 1 ss-443 inbound"
ss = matches[0]
assert ss["listen"] == "127.0.0.1", ss["listen"]
assert ss["port"] == 17000, ss["port"]
assert ss["settings"]["method"] == "aes-256-gcm"
assert ss["settings"]["password"] == passwd, (ss["settings"]["password"], passwd)
assert ss["streamSettings"]["network"] == "tcp"
print("  ss-443 shared inbound OK: 127.0.0.1:17000 (primary password)")
PY
}

# No Reality may survive anywhere in the rendered artifacts.
check_no_reality() { # $1 xray config  $2 haproxy config
  python3 - "$1" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
assert not any("reality" in (i.get("tag") or "").lower() or "reality" in str(i.get("streamSettings", {})).lower() for i in cfg["inbounds"]), "reality inbound found"
print("  xray render is Reality-free")
PY
  if grep -qi 'reality\|is_reality\|10443' "$2"; then
    echo "HAProxy render still mentions Reality"
    fail=1
  else
    echo "  haproxy render is Reality-free"
  fi
}

# The static 443 split every HAProxy render must produce: a TLS check routed
# to the nginx loopback terminator, and a raw/SS fallback to the 17000
# inbound. The template is installed verbatim by the renderer.
check_haproxy_split() { # $1 haproxy config
  grep -q 'use_backend srv_nginx if { req_ssl_hello_type 1 }' "$1" || {
    echo "HAProxy TLS -> nginx rule missing"
    fail=1
  }
  grep -q 'use_backend srv_singbox if { req.ssl_sni -i ' "$1" || {
    echo "HAProxy ShadowTLS decoy-SNI rule missing"
    fail=1
  }
  grep -q 'server srv_singbox 127.0.0.1:8448' "$1" || {
    echo "HAProxy singbox backend target missing"
    fail=1
  }
  grep -q 'default_backend srv_ss443' "$1" || {
    echo "HAProxy default backend (SS-443) missing"
    fail=1
  }
  grep -q 'server srv_ss443 127.0.0.1:17000' "$1" || {
    echo "HAProxy SS-443 backend target missing"
    fail=1
  }
  grep -q 'server srv_nginx 127.0.0.1:20443' "$1" || {
    echo "HAProxy nginx backend target missing"
    fail=1
  }
  echo "  haproxy 443 split OK (stls-SNI -> singbox, TLS -> nginx, non-TLS -> ss443)"
}

say "Render smoke test (legacy single identity)"
if command -v jq >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  if render_xray "$tmp" ""; then
    check_tls_ports "$tmp/xray.json" || fail=1
    check_ss_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 10006 || fail=1
    check_ss_plain_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 8388 || fail=1
    check_ss443 "$tmp/xray.json" "11111111-2222-3333-4444-555555555555" || fail=1
    check_haproxy_split "$tmp/haproxy.cfg" || fail=1
    check_no_reality "$tmp/xray.json" "$tmp/haproxy.cfg" || fail=1
    if render_nginx "$tmp" ""; then
      grep -q '#MUBX_SS_LOCATIONS#' "$tmp/nginx.conf" && {
        echo "Nginx SS marker was not expanded (legacy admin)"
        fail=1
      }
      grep -q 'location /ss-admin' "$tmp/nginx.conf" || {
        echo "legacy SS route /ss-admin missing from nginx render"
        fail=1
      }
      grep -q '__SSH_WS_PATH__\|__DOMAIN__' "$tmp/nginx.conf" && {
        echo "nginx placeholders were not substituted"
        fail=1
      }
      grep -q 'reality' "$tmp/nginx.conf" && {
        echo "nginx render still mentions Reality"
        fail=1
      }
    else
      echo "legacy nginx render step failed"
      fail=1
    fi
  else
    echo "render step failed"
    fail=1
  fi

  say "Multi-user render smoke test (per-user identities + stats API)"
  cat > "$tmp/users.json" <<'JSON'
[
  {"name": "admin", "uuid": "11111111-2222-3333-4444-555555555555", "added": "2026-01-01", "expiry": ""},
  {"name": "alice", "uuid": "22222222-3333-4444-5555-666666666666", "added": "2026-01-01", "expiry": "2026-12-31"}
]
JSON
  if render_xray "$tmp" "$tmp/users.json"; then
    python3 - "$tmp/xray.json" "$tmp/users.json" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
users = json.load(open(sys.argv[2]))
assert cfg.get("api", {}).get("services") == ["HandlerService", "StatsService"], "stats api missing"
assert cfg["policy"]["levels"]["0"]["statsUserUplink"] is True, "policy missing"
tags = {i["tag"] for i in cfg["inbounds"]}
assert "api" in tags, "api inbound missing"
assert cfg["routing"]["rules"][0]["inboundTag"] == ["api"], "api routing missing"
for tag in ("vless-ws", "vmess-ws", "trojan-ws", "vless-httpupgrade", "vless-xhttp"):
    inbound = next(i for i in cfg["inbounds"] if i["tag"] == tag)
    clients = inbound["settings"]["clients"]
    assert len(clients) == len(users), (tag, len(clients))
    assert all(c.get("email") for c in clients), tag
print("  multi-user render OK: %d users across %d inbounds" % (len(users), len(tags)))
PY
    rc=$?
    if [ $rc -ne 0 ]; then fail=1; fi
    check_ss_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 10006 || fail=1
    check_ss_user "$tmp/xray.json" alice "22222222-3333-4444-5555-666666666666" 10007 || fail=1
    check_ss_plain_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 8388 || fail=1
    check_ss_plain_user "$tmp/xray.json" alice "22222222-3333-4444-5555-666666666666" 8389 || fail=1
    check_tls_ports "$tmp/xray.json" || fail=1
    check_ss443 "$tmp/xray.json" "11111111-2222-3333-4444-555555555555" || fail=1
    check_haproxy_split "$tmp/haproxy.cfg" || fail=1
    check_no_reality "$tmp/xray.json" "$tmp/haproxy.cfg" || fail=1
    if render_nginx "$tmp" "$tmp/users.json"; then
      for route in '/ss-admin' '/ss-alice'; do
        [ "$(grep -c "location $route" "$tmp/nginx.conf")" -eq 2 ] || {
          echo "$route must appear in both nginx servers (80 + 20443)"
          fail=1
        }
      done
      for port in 10006 10007; do
        [ "$(grep -c "proxy_pass http://127.0.0.1:$port;" "$tmp/nginx.conf")" -eq 2 ] || {
          echo "SS proxy_pass to $port must appear in both nginx servers"
          fail=1
        }
      done
      for loc in '/vless-httpupgrade' '/vless-xhttp'; do
        [ "$(grep -c "location $loc" "$tmp/nginx.conf")" -eq 2 ] || {
          echo "$loc must appear in both nginx servers (443 TLS + 80 plain)"
          fail=1
        }
      done
      grep -q 'location /ssh-ci-9f2a' "$tmp/nginx.conf" || {
        echo "SSH-over-WS path placeholder was not substituted"
        fail=1
      }
      grep -q '#MUBX_SS_LOCATIONS#\|__SSH_WS_PATH__\|__DOMAIN__' "$tmp/nginx.conf" && {
        echo "nginx markers or placeholders left in multi-user render"
        fail=1
      }
    else
      echo "multi-user nginx render step failed"
      fail=1
    fi
  else
    echo "multi-user render step failed"
    fail=1
  fi
  rm -rf "$tmp"
else
  echo "  (jq not installed; skipping the render smoke tests)"
fi

say "Subscription generator smoke test"
tmp2="$(mktemp -d)"
cat > "$tmp2/users.json" <<'JSON'
[
  {"name": "admin", "uuid": "11111111-2222-3333-4444-555555555555", "added": "2026-01-01", "expiry": ""},
  {"name": "alice", "uuid": "22222222-3333-4444-5555-666666666666", "added": "2026-01-01", "expiry": ""}
]
JSON
if command -v jq >/dev/null 2>&1; then
  (
    export DOMAIN="example.com" \
      SSH_WS_PATH="/ssh-ci-9f2a" \
      HY2_PASS="hy2secret" \
      MUBX_USERS_FILE="$tmp2/users.json" \
      MUBX_SUB_DIR="$tmp2/sub" \
      MUBX_SUB_TOKEN_DIR="$tmp2/tokens" \
      MUBX_SS2022_DIR="$tmp2/ss2022" \
      UUID="11111111-2222-3333-4444-555555555555"
    source lib/render.sh
    source lib/subscribe.sh
    mubx_sub_generate alice
  ) > "$tmp2/url" || { echo "mubx_sub_generate failed"; fail=1; }
  if [ -s "$tmp2/url" ]; then
    tok="$(sed 's|https://example.com/sub/||; s|/index.txt||' "$tmp2/url")"
    for f in links.txt index.txt clash.yaml singbox.json; do
      [ -s "$tmp2/sub/$tok/$f" ] || { echo "subscription file missing: $f"; fail=1; }
    done
    (base64 -d < "$tmp2/sub/$tok/index.txt" 2>/dev/null || base64 -D < "$tmp2/sub/$tok/index.txt" 2>/dev/null) | grep -q 'vless://' || {
      echo "index.txt is not a base64 link list"; fail=1
    }
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp2/sub/$tok/singbox.json" || {
      echo "singbox.json invalid"; fail=1
    }
    grep -q 'MUBX-SS22-alice' "$tmp2/sub/$tok/links.txt" || {
      echo "SS22 link missing from subscription"; fail=1
    }
    grep -q 'mport' "$tmp2/sub/$tok/links.txt" && {
      echo "unexpected mport (port hopping is not configured)"; fail=1
    }
    grep -q 'MUBX-SS-443' "$tmp2/sub/$tok/links.txt" && {
      echo "shared SS-443 link must not appear for non-admin users"; fail=1
    }
    echo "  subscription files OK for alice (token path verified)"
  fi
  rm -rf "$tmp2"
else
  rm -rf "$tmp2"
  echo "  (jq not installed; skipping)"
fi

say "SS-2022 inbound render smoke test"
tmp3="$(mktemp -d)"
if command -v jq >/dev/null 2>&1; then
  (
    export DOMAIN="example.com" \
      SSH_WS_PATH="/ssh-ci-9f2a" \
      MUBX_USERS_FILE="$tmp3/users.json" \
      MUBX_SS2022_DIR="$tmp3/ss2022" \
      UUID="11111111-2222-3333-4444-555555555555"
    printf '[{"name":"admin","uuid":"11111111-2222-3333-4444-555555555555","added":"2026-01-01","expiry":""},{"name":"bob","uuid":"33333333-4444-5555-6666-777777777777","added":"2026-01-01","expiry":""}]\n' > "$tmp3/users.json"
    source lib/render.sh
    source lib/subscribe.sh
    mubx_ensure_ss2022_keys
    mubx_xray_render configs/xray.json "$tmp3/xray.json"
  ) || { echo "ss2022 render failed"; fail=1; }
  if [ -f "$tmp3/xray.json" ]; then
    python3 - "$tmp3/xray.json" <<'PY' || fail=1
import json, sys, base64
cfg = json.load(open(sys.argv[1]))
inb = [i for i in cfg["inbounds"] if i["tag"].startswith("ss2022-")]
assert len(inb) == 2, "expected 2 ss2022 inbounds, got %d" % len(inb)
for i in inb:
    assert i["listen"] == "127.0.0.1", i["listen"]
    assert 11000 <= i["port"] < 11100, i["port"]
    assert i["settings"]["method"] == "2022-blake3-aes-256-gcm"
    assert len(base64.b64decode(i["settings"]["password"])) == 32, "key must be 32 bytes"
    assert i["streamSettings"]["network"] == "ws"
print("  ss2022 inbounds OK: 2 users, ws on 11006+, 32-byte keys")
PY
  fi
fi
rm -rf "$tmp3"

say "sing-box (ShadowTLS) render smoke test"
tmp4="$(mktemp -d)"
if command -v jq >/dev/null 2>&1; then
  (
    export DOMAIN="example.com" \
      SSH_WS_PATH="/ssh-ci-9f2a" \
      HY2_PASS="hy2secret" \
      MUBX_USERS_FILE="$tmp4/users.json" \
      MUBX_SS2022_DIR="$tmp4/ss2022" \
      MUBX_SUB_DIR="$tmp4/sub" \
      MUBX_SUB_TOKEN_DIR="$tmp4/tokens" \
      SHADOWTLS_PASS="stlssecret" \
      SHADOWTLS_SNI="www.microsoft.com" \
      UUID="11111111-2222-3333-4444-555555555555"
    printf '[{"name":"admin","uuid":"11111111-2222-3333-4444-555555555555","added":"2026-01-01","expiry":""}]\n' > "$tmp4/users.json"
    source lib/render.sh
    source lib/subscribe.sh
    mubx_ensure_ss2022_keys
    mubx_singbox_render configs/singbox.json "$tmp4/singbox.json"
    mubx_sub_generate admin > "$tmp4/url"
  ) || { echo "singbox render/sub generate failed"; fail=1; }
  if [ -f "$tmp4/singbox.json" ]; then
    python3 - "$tmp4/singbox.json" <<'PY' || fail=1
import json, sys, base64
cfg = json.load(open(sys.argv[1]))
stls = [i for i in cfg["inbounds"] if i["type"] == "shadowtls"]
assert len(stls) == 1, "expected 1 shadowtls inbound"
st = stls[0]
assert st["listen"] == "127.0.0.1", st["listen"]
assert st["listen_port"] == 8448, st["listen_port"]
assert st["version"] == 3, st["version"]
# sing-box v1.10+ carries the ShadowTLS password in users[]; older
# releases used a top-level "password" field. Accept both layouts.
stls_pass = st["users"][0]["password"] if "users" in st else st["password"]
assert stls_pass == "stlssecret", "stls password not substituted"
assert st["handshake"]["server"] == "www.microsoft.com", st["handshake"]
assert st["handshake"]["server_port"] == 443
assert st["detour"] == "ss22-in"
ss = [i for i in cfg["inbounds"] if i["type"] == "shadowsocks"][0]
assert ss["listen"] == "127.0.0.1" and ss["listen_port"] == 18500
assert ss["method"] == "2022-blake3-aes-256-gcm"
assert len(base64.b64decode(ss["password"])) == 32, "admin key must be 32 bytes"
print("  sing-box render OK: shadowtls v3 loopback :8448 (SNI-routed from 443) -> ss2022 127.0.0.1:18500 (32B admin key)")
PY
    # The subscription for admin must contain the ShadowTLS node.
    tok="$(sed 's|https://example.com/sub/||; s|/index.txt||' "$tmp4/url")"
    if grep -q 'MUBX-ShadowTLS' "$tmp4/sub/$tok/clash.yaml" 2>/dev/null && \
       grep -q '\"port\": 443' "$tmp4/sub/$tok/clash.yaml" && \
       python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); tags=[o["tag"] for o in d["outbounds"]]; assert "MUBX-ShadowTLS" in tags and "MUBX-ShadowTLS-wrap" in tags, tags' "$tmp4/sub/$tok/singbox.json" 2>/dev/null; then
      echo "  ShadowTLS subscription entries OK (clash + sing-box)"
    else
      echo "ShadowTLS subscription entries missing or invalid"; fail=1
    fi
  fi
fi
rm -rf "$tmp4"

say "Concurrency & file locking test"
tmp5="$(mktemp -d)"
lockfile="$tmp5/test.lock"
(
  source lib/common.sh
  for i in $(seq 1 10); do
    (
      mubx_with_lock "$lockfile" bash -c "val=\$(cat $tmp5/count 2>/dev/null || echo 0); echo \$((val + 1)) > $tmp5/count"
    ) &
  done
  wait
  total="$(cat "$tmp5/count" 2>/dev/null || echo 0)"
  [ "$total" -eq 10 ] || { echo "Concurrency lock test failed: expected 10, got $total"; exit 1; }
) || fail=1
rm -rf "$tmp5"
echo "  concurrency lock test OK (10 parallel jobs cleanly serialized)"

if [ "$fail" -eq 0 ]; then
  say "all checks passed"
else
  say "checks FAILED"
  exit 1
fi
