#!/usr/bin/env bash
# Static checks for the MUB-X repository. Runs in CI (see ci.yml) and can be
# run locally from anywhere:  bash .github/ci-check.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0

say() { printf '== %s\n' "$*"; }

say "shell syntax"
for f in install.sh bin/* lib/*.sh; do
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
  # render smoke test below validates the substituted result instead.
  [ "$(basename "$f")" = "reality-inbound.json" ] && continue
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" 2>/dev/null; then
    echo "JSON FAIL: $f"
    fail=1
  fi
done

say "Reality render smoke test (multi-front)"
if command -v jq >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  if UUID="11111111-2222-3333-4444-555555555555" \
     REALITY_PRIVKEY="dummy-private-key-material" \
     SHORT_ID="1a2b3c4d" \
     REALITY_FRONTS="www.apple.com dl.google.com" \
     bash -c 'source lib/reality-build.sh &&
              mubx_xray_render configs/xray.json configs/reality-inbound.json "$1" &&
              mubx_haproxy_render configs/haproxy.cfg "$2"' \
        x "$tmp/xray.json" "$tmp/haproxy.cfg"; then
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
  rm -rf "$tmp"
else
  echo "  (jq not installed; skipping the render smoke test)"
fi

if [ "$fail" -eq 0 ]; then
  say "all checks passed"
else
  say "checks FAILED"
  exit 1
fi
