#!/usr/bin/env bash
# BASH_ENV hook used only by mubx-users-legacy.
# Bash reads BASH_ENV for non-interactive scripts before executing the script.

mubx_users_atomic_install() {
  local mode="0600" dir tmp src="" dst="" arg
  while [ "$#" -gt 0 ]; do
    arg="$1"
    case "$arg" in
      -m)
        [ "$#" -ge 2 ] || { echo "[!] install -m requires a mode." >&2; return 1; }
        mode="$2"
        shift 2
        ;;
      --mode=*)
        mode="${arg#--mode=}"
        shift
        ;;
      --)
        shift
        while [ "$#" -gt 0 ]; do
          src="$dst"
          dst="$1"
          shift
        done
        ;;
      -*)
        shift
        ;;
      *)
        src="$dst"
        dst="$arg"
        shift
        ;;
    esac
  done

  [ -n "$src" ] && [ -n "$dst" ] || {
    echo "[!] Invalid install invocation for user-store transaction." >&2
    return 1
  }
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
