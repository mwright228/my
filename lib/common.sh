#!/bin/bash
C_RESET='\033[0m'; C_GREEN='\033[38;5;48m'; C_RED='\033[38;5;196m'
log()  { echo -e "${C_GREEN}[*]${C_RESET} $*"; }
warn() { echo -e "${C_RED}[!]${C_RESET} $*" >&2; }
die()  { warn "$*"; exit 1; }
load_mubx_env() {
  local env_file="${1:-/etc/telecom-engine.env}" key value
  [ -r "$env_file" ] || die "Missing MUB-X environment file: $env_file"
  while IFS='=' read -r key value; do
    case "$key" in
      DOMAIN|UUID|REALITY_PRIVKEY|REALITY_PUBKEY|SHORT_ID|HY2_PASS|ZIVPN_PASS|SSH_WS_PATH)
        value="${value#\"}"
        value="${value%\"}"
        [[ "$value" != *$'\n'* ]] || die "Invalid newline in $key"
        printf -v "$key" '%s' "$value"
        ;;
      '') ;;
      *) die "Unexpected key in $env_file: $key" ;;
    esac
  done < "$env_file"
}
