#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/lib/mubx-transaction.sh"

TMP="$(mktemp -d -t mubx-tx-test.XXXXXX)"
trap 'rm -rf -- "$TMP"' EXIT
export MUBX_TX_ROOT="$TMP/transactions"
export MUBX_TX_LOCK="$TMP/operation.lock"

# Existing file must be restored byte-for-byte after rollback.
mkdir -p "$TMP/etc"
printf 'old\n' > "$TMP/etc/existing"
mubx_tx_begin test
mubx_tx_snapshot "$TMP/etc/existing"
printf 'new\n' > "$TMP/etc/candidate"
mubx_tx_atomic_install "$TMP/etc/candidate" "$TMP/etc/existing" 0600
mubx_tx_rollback_files
[[ "$(cat "$TMP/etc/existing")" == old ]]
mubx_tx_abort

# A previously absent destination must be removed by rollback.
mubx_tx_begin absent
mubx_tx_snapshot "$TMP/etc/new-file"
printf 'new\n' > "$TMP/etc/candidate"
mubx_tx_atomic_install "$TMP/etc/candidate" "$TMP/etc/new-file" 0600
[[ -f "$TMP/etc/new-file" ]]
mubx_tx_rollback_files
[[ ! -e "$TMP/etc/new-file" ]]
mubx_tx_abort

# A symlink must be restored as a symlink with its original target.
ln -s existing "$TMP/etc/link"
mubx_tx_begin symlink
mubx_tx_snapshot "$TMP/etc/link"
rm -f "$TMP/etc/link"
ln -s other "$TMP/etc/link"
mubx_tx_rollback_files
[[ -L "$TMP/etc/link" ]]
[[ "$(readlink "$TMP/etc/link")" == existing ]]
mubx_tx_abort

# Concurrent transactions must serialize through the shared flock.
mubx_tx_begin lock-a
if (
    export MUBX_TX_ROOT="$TMP/transactions"
    export MUBX_TX_LOCK="$TMP/operation.lock"
    source "$ROOT/lib/mubx-transaction.sh"
    mubx_tx_begin lock-b
); then
    echo 'transaction lock was not exclusive' >&2
    exit 1
fi
mubx_tx_abort

echo 'mubx transaction tests: PASS'
