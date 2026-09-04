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
  # reality-inbound.json is a per-front fragment with a numeric placeholder
  # ("port": __PORT__), so standalone JSON parsing does not apply to it; the
  # render smoke tests below validate the substituted result instead.
  [ "$(basename "$f")" = "reality-inbound.json" ] && continue
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
