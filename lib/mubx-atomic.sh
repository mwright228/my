#!/usr/bin/env bash
# Durable same-filesystem replacement helpers.
# Source this file from mutating commands; never copy directly over a live file.
set -Eeuo pipefail

mubx_atomic_replace() {
  local src="$1" dst="$2" mode="${3:-}"
  [ -f "$src" ] || { echo "[!] Atomic replace source missing: $src" >&2; return 1; }

  local dir tmp
  dir="$(dirname -- "$dst")"
  install -d -m 0755 -- "$dir"
  tmp="$(mktemp "$dir/.mubx-atomic.XXXXXX")"

  cleanup() { rm -f -- "$tmp"; }
  trap cleanup RETURN

  if [ -n "$mode" ]; then
    install -m "$mode" -- "$src" "$tmp"
  else
    cp -a -- "$src" "$tmp"
  fi
  sync -f "$tmp"
  mv -f -- "$tmp" "$dst"
  sync -d "$dir"
  trap - RETURN
}

mubx_atomic_remove() {
  local dst="$1" dir
  dir="$(dirname -- "$dst")"
  rm -f -- "$dst"
  sync -d "$dir"
}

mubx_atomic_validate_json() {
  local path="$1"
  command -v jq >/dev/null 2>&1 || { echo "[!] jq is required for JSON validation." >&2; return 1; }
  jq empty "$path" >/dev/null 2>&1 || {
    echo "[!] Refusing to activate invalid JSON: $path" >&2
    return 1
  }
}
