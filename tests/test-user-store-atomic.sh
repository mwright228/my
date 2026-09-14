#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf -- "$tmpdir"' EXIT

export MUBX_USERS_FILE="$tmpdir/users.json"
export BASH_ENV="$ROOT/lib/mubx-users-transaction-env.sh"

printf '[{"name":"old"}]\n' > "$MUBX_USERS_FILE"
printf '[{"name":"new"}]\n' > "$tmpdir/new.json"

bash -c 'install -m 0600 "$1" "$MUBX_USERS_FILE"' _ "$tmpdir/new.json"

jq -e '.[0].name == "new"' "$MUBX_USERS_FILE" >/dev/null
[ "$(stat -c '%a' "$MUBX_USERS_FILE")" = 600 ]
! find "$tmpdir" -maxdepth 1 -name '.users.json.*' -print -quit | grep -q .

printf 'user-store atomic transaction: PASS\n'
