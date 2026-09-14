#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/lib/mubx-transaction.sh"
TMP="$(mktemp -d -t mubx-tx-test.XXXXXX)"
trap 'rm -rf -- "$TMP"' EXIT
export MUBX_TX_ROOT="$TMP/transactions"
export MUBX_TX_LOCK="$TMP/operation.lock"

mkdir -p "$TMP/etc"
printf 'old\n' > "$TMP/etc/existing"
mubx_tx_begin test
mubx_tx_snapshot "$TMP/etc/existing"
printf 'intermediate\n' > "$TMP/etc/existing"
mubx_tx_snapshot "$TMP/etc/existing"
printf 'new\n' > "$TMP/etc/existing"
mubx_tx_rollback_files
[[ "$(cat "$TMP/etc/existing")" == old ]]
mubx_tx_abort

mubx_tx_begin absent
mubx_tx_snapshot "$TMP/etc/new-file"
printf 'new\n' > "$TMP/etc/candidate"
mubx_tx_atomic_install "$TMP/etc/candidate" "$TMP/etc/new-file" 0600
[[ -f "$TMP/etc/new-file" ]]
mubx_tx_rollback_files
[[ ! -e "$TMP/etc/new-file" ]]
mubx_tx_abort

ln -s existing "$TMP/etc/link"
mubx_tx_begin symlink
mubx_tx_snapshot "$TMP/etc/link"
rm -f "$TMP/etc/link"
ln -s other "$TMP/etc/link"
mubx_tx_rollback_files
[[ -L "$TMP/etc/link" ]]
[[ "$(readlink "$TMP/etc/link")" == existing ]]
mubx_tx_abort

# Directory state, including generated files, must roll back as one snapshot.
mkdir -p "$TMP/tree"
printf 'before\n' > "$TMP/tree/a"
mubx_tx_begin tree
mubx_tx_snapshot_tree "$TMP/tree"
printf 'after\n' > "$TMP/tree/a"
printf 'new\n' > "$TMP/tree/b"
mubx_tx_rollback_files
[[ "$(cat "$TMP/tree/a")" == before ]]
[[ ! -e "$TMP/tree/b" ]]
mubx_tx_abort

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
mubx_tx_begin lock-c
mubx_tx_abort
mubx_tx_begin lock-d
mubx_tx_commit

echo 'mubx transaction tests: PASS'
