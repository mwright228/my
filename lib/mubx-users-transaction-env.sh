#!/usr/bin/env bash
# BASH_ENV hook used only by mubx-users-legacy.
# Bash reads BASH_ENV for non-interactive scripts before executing the script.
# See GNU Bash Startup Files documentation.

mubx_users_atomic_install() {
  local src="$1" dst="$2" mode="0600" dir tmp
  shift 2 || true
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -m) mode="$2"; shift 2 ;;
      --mode=*) mode="${1#--mode=}"; shift ;;
      *) shift ;;
    esac
  done

  [ -f "$src" ] || { echo "[!] User-store source missing: $src" >&2; return 1; }
  dir="$(dirname -- "$dst")"
  command install -d -m 0700 -- "$dir"
  tmp="$(mktemp "$dir/.users.json.XXXXXX")"
  cleanup() { rm -f -- "$tmp"; }
  trap cleanup RETURN

  command install -m "$mode" -- "$src" "$tmp"
  command jq empty "$tmp" >/dev/null 2>&1 || {
    echo "[!] Refusing to activate invalid users JSON." >&2
    return 1
  }
  command sync -f "$tmp"
  command mv -f -- "$tmp" "$dst"
  command sync -d "$dir"
  trap - RETURN
}

install() {
  local argc="$#" dst=""
  [ "$argc" -gt 0 ] && dst="${!argc}"
  if [ -n "$dst" ] && [ "$dst" = "${MUBX_USERS_FILE:-/etc/mubx/users.json}" ]; then
    mubx_users_atomic_install "$@"
    return $?
  fi
  command install "$@"
}
