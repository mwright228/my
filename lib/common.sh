#!/bin/bash
# Shared helpers for MUB-X scripts.
#
# Colors only light up when stdout is a terminal, so piped output and logs
# stay clean. Scripts should source this file instead of defining their own
# palettes.

if [ -t 1 ]; then
  C_RESET=$'\033[0m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'
  C_MAGENTA=$'\033[35m'
  C_CYAN=$'\033[36m'
  C_WHITE=$'\033[37m'
else
  C_RESET= C_BOLD= C_DIM= C_RED= C_GREEN= C_YELLOW= C_BLUE= C_MAGENTA= C_CYAN= C_WHITE=
fi

log()  { echo -e "${C_GREEN}[*]${C_RESET} $*"; }
warn() { echo -e "${C_YELLOW}[!]${C_RESET} $*" >&2; }
die()  { warn "$*"; exit 1; }

load_mubx_env() {
  local env_file="${1:-/etc/telecom-engine.env}" key value
  [ -r "$env_file" ] || die "Missing MUB-X environment file: $env_file"
  while IFS='=' read -r key value; do
    case "$key" in
      DOMAIN|UUID|REALITY_PRIVKEY|REALITY_PUBKEY|SHORT_ID|HY2_PASS|ZIVPN_PASS|SSH_WS_PATH|REALITY_FRONTS)
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

# --- presentation helpers -------------------------------------------------

# A subtle full-width divider.
mubx_rule() {
  printf '%s%s%s\n' "$C_DIM" '────────────────────────────────────────────' "$C_RESET"
}

# One section heading.
mubx_title() {
  printf '\n%s%s%s\n' "$C_BOLD" "$1" "$C_RESET"
}

# "label: value" row, label dim, value cyan.
mubx_kv() {
  printf '  %s%-13s%s %s%s%s\n' "$C_DIM" "$1:" "$C_RESET" "$C_CYAN" "$2" "$C_RESET"
}

# Colored ●/○ status dot for a systemd unit.
svc_dot() {
  if systemctl is-active --quiet "$1" 2>/dev/null; then
    printf '%s●%s' "$C_GREEN" "$C_RESET"
  else
    printf '%s○%s' "$C_RED" "$C_RESET"
  fi
}

# Clean-screen helper: no-op when output is not a terminal.
mubx_clear() {
  [ -t 1 ] && clear
}
