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
  # reality-inbound.json, ss-inbound.json and ss-plain-inbound.json are
  # per-item fragments with a numeric placeholder ("port": __PORT__), so
  # standalone JSON parsing does not apply to them; the render smoke tests
  # below validate the substituted results instead.
  case "$(basename "$f")" in reality-inbound.json|ss-inbound.json|ss-plain-inbound.json) continue ;; esac
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" 2>/dev/null; then
    echo "JSON FAIL: $f"
    fail=1
  fi
done

render_xray() { # $1 tmpdir  $2 users store (may be empty)  -> writes "$1/xray.json" "$1/haproxy.cfg"
  local tmp="$1" users="$2"
  (
    if [ -n "$users" ]; then
      export MUBX_USERS_FILE="$users" DOMAIN="example.com"
    fi
    export UUID="11111111-2222-3333-4444-555555555555" \
      REALITY_PRIVKEY="dummy-private-key-material" \
      SHORT_ID="1a2b3c4d" \
      REALITY_FRONTS="www.apple.com dl.google.com"
    bash -c 'source lib/reality-build.sh &&
             mubx_xray_render configs/xray.json configs/reality-inbound.json "$1" &&
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
    export UUID="11111111-2222-3333-4444-555555555555" \
      REALITY_FRONTS="www.apple.com dl.google.com"
    bash -c 'source lib/reality-build.sh && mubx_nginx_render configs/nginx.conf "$1"' \
      x "$tmp/nginx.conf"
  )
}

# The one Shadowsocks inbound every valid render must carry for a user.
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

say "Reality render smoke test (multi-front)"
if command -v jq >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  if render_xray "$tmp" ""; then
    python3 - "$tmp/xray.json" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
reality = [i for i in cfg["inbounds"] if i["tag"].startswith("reality")]
assert len(reality) == 2, "expected 2 reality inbounds, got %d" % len(reality)
for i in reality:
    assert i["port"] in (10443, 10444), i["port"]
    rs = i["streamSettings"]["realitySettings"]
    assert rs["dest"] == "%s:443" % rs["serverNames"][0], rs
print("  xray render OK:", sorted((i["port"], i["streamSettings"]["realitySettings"]["dest"]) for i in reality))
PY
    rc=$?
    if [ $rc -ne 0 ]; then fail=1; fi
    if grep -q 'MUBX_REALITY_RULES#\|MUBX_REALITY_BACKENDS#' "$tmp/haproxy.cfg"; then
      echo "HAProxy markers were not expanded"
      fail=1
    fi
    grep -q 'acl is_reality_1 req_ssl_sni -i dl.google.com' "$tmp/haproxy.cfg" || {
      echo "second front ACL missing from HAProxy render"
      fail=1
    }
    check_ss_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 10006 || fail=1
    check_ss_plain_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 8388 || fail=1
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
reality = [i for i in cfg["inbounds"] if i["tag"].startswith("reality")]
for i in reality:
    clients = i["settings"]["clients"]
    assert len(clients) == len(users)
    assert all(c.get("flow") == "xtls-rprx-vision" for c in clients), i["tag"]
print("  multi-user render OK: %d users across %d inbounds" % (len(users), len(tags)))
PY
    rc=$?
    if [ $rc -ne 0 ]; then fail=1; fi
    check_ss_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 10006 || fail=1
    check_ss_user "$tmp/xray.json" alice "22222222-3333-4444-5555-666666666666" 10007 || fail=1
    check_ss_plain_user "$tmp/xray.json" admin "11111111-2222-3333-4444-555555555555" 8388 || fail=1
    check_ss_plain_user "$tmp/xray.json" alice "22222222-3333-4444-5555-666666666666" 8389 || fail=1
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

if [ "$fail" -eq 0 ]; then
  say "all checks passed"
else
  say "checks FAILED"
  exit 1
fi
