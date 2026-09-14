#!/usr/bin/env bash
# BASH_ENV hook used only by mubx-users-legacy.
# Bash reads BASH_ENV for non-interactive scripts before executing the script.

if [ "${MUBX_USERS_TX_ACTIVE:-0}" != "1" ]; then
  _MUBX_TX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if [ -r "$_MUBX_TX_ROOT/mubx-transaction.sh" ]; then
    source "$_MUBX_TX_ROOT/mubx-transaction.sh"
  elif [ -r /usr/local/lib/mubx/mubx-transaction.sh ]; then
    source /usr/local/lib/mubx/mubx-transaction.sh
  fi
  if declare -F mubx_tx_begin >/dev/null 2>&1; then
    export MUBX_USERS_TX_ACTIVE=1
    mubx_tx_begin users || exit $?
  fi
fi

mubx_users_atomic_install() {
  local mode="0600" dir tmp src="" dst="" arg
  while [ "$#" -gt 0 ]; do
    arg="$1"
    case "$arg" in
      -m)
        [ "$#" -ge 2 ] || { echo "[!] install -m requires a mode." >&2; return 1; }
        mode="$2"; shift 2 ;;
      --mode=*)
        mode="${arg#--mode=}"; shift ;;
      -D|-v|-b|-C|-p|-s)
        shift ;;
      -o|-g|-t)
        [ "$#" -ge 2 ] || { echo "[!] install option $arg requires an argument." >&2; return 1; }
        shift 2 ;;
      --)
        shift
        while [ "$#" -gt 0 ]; do src="$dst"; dst="$1"; shift; done
        ;;
      -*)
        shift ;;
      *)
        src="$dst"; dst="$arg"; shift ;;
    esac
  done
  [ -n "$src" ] && [ -n "$dst" ] || { echo "[!] Invalid install invocation for user-store transaction." >&2; return 1; }
  [ -f "$src" ] || { echo "[!] User-store source missing: $src" >&2; return 1; }
  dir="$(dirname -- "$dst")"
  command install -d -m 0700 -- "$dir"
  tmp="$(mktemp "$dir/.users.json.XXXXXX")"
  cleanup() { rm -f -- "$tmp"; }
  trap cleanup RETURN
  command install -m "$mode" -- "$src" "$tmp"
  command jq empty "$tmp" >/dev/null 2>&1 || { echo "[!] Refusing to activate invalid users JSON." >&2; return 1; }
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

cp() {
  local argc="$#" dst="" src="" mode="0600"
  local -a args=("$@")
  [ "$argc" -ge 2 ] || { command cp "$@"; return $?; }
  dst="${args[$((argc - 1))]}"
  src="${args[$((argc - 2))]}"
  case "$dst" in
    /usr/local/etc/xray/config.json|/etc/nginx/nginx.conf|/etc/sing-box/config.json)
      [ -f "$src" ] || { echo "[!] Atomic config source missing: $src" >&2; return 1; }
      [ -e "$dst" ] && mode="$(stat -c '%a' -- "$dst" 2>/dev/null || printf '0600')"
      if declare -F mubx_tx_atomic_install >/dev/null 2>&1; then
        mubx_tx_atomic_install "$src" "$dst" "$mode"
      else
        command cp "$@"
      fi
      ;;
    *)
      command cp "$@"
      ;;
  esac
}
