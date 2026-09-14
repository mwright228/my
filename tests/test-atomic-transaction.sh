#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/lib/mubx-atomic.sh"

tmpdir="$(mktemp -d)"
trap 'rm -rf -- "$tmpdir"' EXIT

printf '%s\n' old > "$tmpdir/live"
printf '%s\n' new > "$tmpdir/candidate"

mubx_atomic_replace "$tmpdir/candidate" "$tmpdir/live" 0600

[ "$(cat "$tmpdir/live")" = new ]
[ "$(stat -c '%a' "$tmpdir/live")" = 600 ]
! find "$tmpdir" -maxdepth 1 -name '.mubx-atomic.*' -print -quit | grep -q .

printf 'atomic transaction helper: PASS\n'
