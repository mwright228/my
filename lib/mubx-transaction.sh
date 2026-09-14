#!/usr/bin/env bash
# MUB-X common transaction primitives.
# Consumers must hold FD 9 for the duration of a mutating operation.
set -Eeuo pipefail

MUBX_TX_ROOT="${MUBX_TX_ROOT:-/var/lib/mubx/transactions}"
MUBX_TX_LOCK="${MUBX_TX_LOCK:-/run/lock/mubx-operation.lock}"
MUBX_TX_DIR=""
MUBX_TX_MANIFEST=""

mubx_tx_begin() {
    local label="${1:-operation}"
    install -d -m 0700 "$MUBX_TX_ROOT"
    install -d -m 0755 "$(dirname "$MUBX_TX_LOCK")"
    exec 9>"$MUBX_TX_LOCK"
    flock -n 9 || {
        echo "[!] Another MUB-X operation is already running." >&2
        return 75
    }
    MUBX_TX_DIR="$(mktemp -d "$MUBX_TX_ROOT/${label}.XXXXXX")"
    MUBX_TX_MANIFEST="$MUBX_TX_DIR/manifest"
    : > "$MUBX_TX_MANIFEST"
    chmod 0600 "$MUBX_TX_MANIFEST"
}

mubx_tx_snapshot() {
    local path="$1" backup kind
    [ -n "$MUBX_TX_DIR" ] || return 2
    # Snapshot each path only once: the transaction must restore its state at begin,
    # not whichever intermediate state happened to be recorded later.
    if grep -Fqx -- "$path" < <(cut -f2 "$MUBX_TX_MANIFEST" 2>/dev/null); then
        return 0
    fi
    if [ -L "$path" ]; then
        kind=symlink
        printf 'symlink\t%s\t%s\n' "$path" "$(readlink -- "$path")" >> "$MUBX_TX_MANIFEST"
    elif [ -f "$path" ]; then
        backup="$MUBX_TX_DIR/files$(dirname "$path")"
        install -d -m 0700 "$backup"
        cp -aP -- "$path" "$backup/"
        printf 'file\t%s\n' "$path" >> "$MUBX_TX_MANIFEST"
    elif [ -d "$path" ]; then
        printf 'dir\t%s\n' "$path" >> "$MUBX_TX_MANIFEST"
    else
        printf 'absent\t%s\n' "$path" >> "$MUBX_TX_MANIFEST"
    fi
}

mubx_tx_atomic_install() {
    local src="$1" dst="$2" mode="${3:-0644}" dir tmp
    dir="$(dirname "$dst")"
    install -d -m 0755 "$dir"
    tmp="$(mktemp "$dir/.mubx-atomic.XXXXXX")"
    chmod "$mode" "$tmp"
    if ! cp -- "$src" "$tmp"; then
        rm -f -- "$tmp"
        return 1
    fi
    sync -f "$tmp"
    mv -f -- "$tmp" "$dst"
    sync -d "$dir"
}

mubx_tx_rollback_files() {
    local kind path target
    [ -f "$MUBX_TX_MANIFEST" ] || return 0
    while IFS=$'\t' read -r kind path target; do
        case "$kind" in
            absent)
                rm -rf -- "$path"
                ;;
            file)
                target="$MUBX_TX_DIR/files$path"
                install -d -m 0755 "$(dirname "$path")"
                mubx_tx_atomic_install "$target" "$path" "$(stat -c '%a' "$target")"
                ;;
            symlink)
                rm -rf -- "$path"
                install -d -m 0755 "$(dirname "$path")"
                ln -s -- "$target" "$path"
                ;;
            dir)
                install -d -m 0755 "$path"
                ;;
            *)
                echo "[!] Invalid transaction manifest entry; refusing rollback." >&2
                return 1
                ;;
        esac
    done < "$MUBX_TX_MANIFEST"
}

mubx_tx_release() {
    if [ -n "$MUBX_TX_DIR" ]; then
        rm -rf -- "$MUBX_TX_DIR"
    fi
    MUBX_TX_DIR=""
    MUBX_TX_MANIFEST=""
    flock -u 9 2>/dev/null || true
    exec 9>&- 2>/dev/null || true
}

mubx_tx_abort() {
    mubx_tx_rollback_files
    mubx_tx_release
}

mubx_tx_commit() {
    sync
    mubx_tx_release
}
