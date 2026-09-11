#!/bin/bash
# Shared helpers for MUB-X scripts.
#
# Colors only light up when stdout is a terminal, so piped output and logs
# stay clean. Scripts source this file for cohesive visual styling across
# all CLI tools, status monitors, and link generators.

case ":$PATH:" in
  *":/usr/local/bin:"*) ;;
  *) export PATH="/usr/local/bin:/usr/local/sbin:$PATH" ;;
esac

if [ -t 1 ]; then
  C_RESET=$'\033[0m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_ITALIC=$'\033[3m'
  C_UNDER=$'\033[4m'

  # Core 16-color palette
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'
  C_MAGENTA=$'\033[35m'
  C_CYAN=$'\033[36m'
  C_WHITE=$'\033[37m'

  # Modern 256-color accents
  C_ELECTRIC=$'\033[38;5;51m'    # Vibrant Electric Cyan
  C_PURPLE=$'\033[38;5;141m'     # Neon Violet / Lavender
  C_MINT=$'\033[38;5;48m'        # Cyber Mint Green
  C_AMBER=$'\033[38;5;214m'      # Warm Amber Gold
  C_CORAL=$'\033[38;5;203m'      # Neon Coral Red
  C_SLATE=$'\033[38;5;244m'      # Medium Slate Gray
  C_BORDER=$'\033[38;5;240m'     # Dark Slate Border
  C_BRIGHT=$'\033[1;37m'         # Crisp Pure White
  C_BG_CARD=$'\033[48;5;236m'    # Dark Card Background
  C_BG_ACCENT=$'\033[48;5;24m'   # Deep Blue-Cyan Accent BG
else
  C_RESET="" C_BOLD="" C_DIM="" C_ITALIC="" C_UNDER=""
  C_RED="" C_GREEN="" C_YELLOW="" C_BLUE="" C_MAGENTA="" C_CYAN="" C_WHITE=""
  C_ELECTRIC="" C_PURPLE="" C_MINT="" C_AMBER="" C_CORAL="" C_SLATE="" C_BORDER="" C_BRIGHT="" C_BG_CARD="" C_BG_ACCENT=""
fi

log()  { echo -e "${C_MINT}[✓]${C_RESET} $*"; }
warn() { echo -e "${C_AMBER}[!]${C_RESET} $*" >&2; }
die()  { echo -e "${C_CORAL}[✕]${C_RESET} $*" >&2; exit 1; }

# Portable base64 encoders: works uniformly on GNU coreutils (Linux), busybox, and BSD/macOS.
mubx_base64() { # stdin -> stdout (single line without newlines)
  (base64 -w 0 2>/dev/null || base64 2>/dev/null) | tr -d '\r\n'
}

mubx_b64url() { # stdin -> stdout (URL-safe base64 without padding)
  mubx_base64 | tr '+/' '-_' | tr -d '='
}

# Portable in-place sed: works with both GNU sed (-i) and BSD/macOS sed (-i '')
mubx_sed_i() {
  if sed --version 2>&1 | grep -q GNU; then
    sed -i "$@"
  else
    sed -i '' "$@"
  fi
}

load_mubx_env() {
  local env_file="${1:-/etc/telecom-engine.env}" key value
  if [ ! -r "$env_file" ]; then
    [ -n "${DOMAIN:-}" ] && return 0
    return 1
  fi
  while IFS='=' read -r key value; do
    case "$key" in
      DOMAIN|UUID|HY2_PASS|ZIVPN_PASS|SSH_WS_PATH|SHADOWTLS_PASS|SHADOWTLS_SNI)
        value="${value#\"}"
        value="${value%\"}"
        [[ "$value" != *$'\n'* ]] || die "Invalid newline in $key"
        printf -v "$key" '%s' "$value"
        ;;
      REALITY_PRIVKEY|REALITY_PUBKEY|SHORT_ID|REALITY_FRONTS|STLS_PASS|STLS_SERVER_NAME|SQUID_*)
        # Retired Reality keys from legacy installs and legacy keys: ignore silently
        ;;
      '') ;;
      *) die "Unexpected key in $env_file: $key" ;;
    esac
  done < "$env_file"
  if [ -z "${DOMAIN:-}" ]; then
    if [ -r /etc/mubx/domain ]; then
      DOMAIN="$(tr -d ' \t\r\n' < /etc/mubx/domain 2>/dev/null || true)"
    elif [ -r /usr/local/etc/xray/domain ]; then
      DOMAIN="$(tr -d ' \t\r\n' < /usr/local/etc/xray/domain 2>/dev/null || true)"
    fi
  fi
}

# --- System Telemetry Helpers ---------------------------------------------
mubx_sys_ram() {
  if [ -r /proc/meminfo ]; then
    local total avail used pct
    total=$(awk '/MemTotal:/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
    avail=$(awk '/MemAvailable:/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
    if [ "$total" -gt 0 ]; then
      used=$((total - avail))
      pct=$((used * 100 / total))
      printf '%d/%d MB (%d%%)' "$used" "$total" "$pct"
      return
    fi
  fi
  echo "n/a"
}

mubx_sys_cpu() {
  if [ -r /proc/loadavg ]; then
    awk '{print $1, $2, $3}' /proc/loadavg 2>/dev/null || echo "n/a"
  else
    echo "n/a"
  fi
}

mubx_sys_uptime() {
  if [ -r /proc/uptime ]; then
    local s d h m
    s=$(awk '{print int($1)}' /proc/uptime 2>/dev/null || echo 0)
    d=$((s / 86400))
    h=$(( (s % 86400) / 3600 ))
    m=$(( (s % 3600) / 60 ))
    if [ "$d" -gt 0 ]; then
      printf '%dd %dh %dm' "$d" "$h" "$m"
    elif [ "$h" -gt 0 ]; then
      printf '%dh %dm' "$h" "$m"
    else
      printf '%dm' "$m"
    fi
  else
    echo "n/a"
  fi
}

# --- Presentation & Box Drawing Engine -----------------------------------
# Inner width of bordered panels: 58 cols inner + 2 borders = 60 total
MUBX_IW="${MUBX_IW:-58}"

# Strip ANSI codes for exact character measurement
mubx_vis_len() {
  printf '%s' "$1" | LC_ALL=C.UTF-8 sed -e $'s/\x1b\\[[0-9;]*[a-zA-Z]//g' | LC_ALL=C.UTF-8 wc -m
}

# Rounded box top header: optional title ($1) and optional tag ($2)
mubx_box_top() {
  local title="${1:-}" tag="${2:-}"
  if [ -z "$title" ]; then
    printf '%s╭%s╮%s\n' "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$MUBX_IW"))" "$C_RESET"
    return
  fi
  local t_str=" ${C_BRIGHT}${title}${C_RESET} "
  local t_len; t_len=$(mubx_vis_len "$t_str")
  
  if [ -n "$tag" ]; then
    local tag_str=" ${C_SLATE}${tag}${C_RESET} "
    local tag_len; tag_len=$(mubx_vis_len "$tag_str")
    local rem=$((MUBX_IW - 2 - t_len - tag_len - 1))
    [ "$rem" -lt 1 ] && rem=1
    printf '%s╭─%s%s%s%s%s─╮%s\n' "$C_BORDER" "$t_str" "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$rem"))" "$tag_str" "$C_BORDER" "$C_RESET"
  else
    local rem=$((MUBX_IW - 2 - t_len))
    [ "$rem" -lt 1 ] && rem=1
    printf '%s╭─%s%s%s╮%s\n' "$C_BORDER" "$t_str" "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$rem"))" "$C_RESET"
  fi
}

# Rounded box divider line: optional section label ($1)
mubx_box_div() {
  local title="${1:-}"
  if [ -z "$title" ]; then
    printf '%s├%s┤%s\n' "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$MUBX_IW"))" "$C_RESET"
    return
  fi
  local t_str=" ${C_PURPLE}${title}${C_RESET} "
  local t_len; t_len=$(mubx_vis_len "$t_str")
  local rem=$((MUBX_IW - 2 - t_len))
  [ "$rem" -lt 1 ] && rem=1
  printf '%s├─%s%s%s┤%s\n' "$C_BORDER" "$t_str" "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$rem"))" "$C_RESET"
}

# Rounded box bottom
mubx_box_bot() {
  printf '%s╰%s╯%s\n' "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$MUBX_IW"))" "$C_RESET"
}

# Legacy compatibility wrapper
mubx_box_line() {
  case "$1" in
    '┌'|'╭') mubx_box_top ;;
    '├')     mubx_box_div ;;
    '└'|'╰') mubx_box_bot ;;
    *)       printf '%s%s%s%s%s\n' "$1" "$C_BORDER" "$(printf '─%.0s' $(seq 1 "$MUBX_IW"))" "$C_RESET" "$2" ;;
  esac
}

# One panel row: perfectly padded so right border is aligned
mubx_put() {
  local line="${1:-}" vis len pad
  vis="$(printf '%s' "$line" | LC_ALL=C.UTF-8 sed -e $'s/\x1b\\[[0-9;]*[a-zA-Z]//g')"
  len=$(printf '%s' "$vis" | LC_ALL=C.UTF-8 wc -m)
  pad=$((MUBX_IW - len))
  [ "$pad" -lt 0 ] && pad=0
  printf '%s│%s%s%*s%s│%s\n' "$C_BORDER" "$C_RESET" "$line" "$pad" "" "$C_BORDER" "$C_RESET"
}

# Left + Right dual aligned card row
mubx_card() {
  local left="$1" right="$2" lw rw pad
  lw=$(mubx_vis_len "$left")
  rw=$(mubx_vis_len "$right")
  pad=$((MUBX_IW - lw - rw))
  [ "$pad" -lt 0 ] && pad=0
  mubx_put "$left$(printf '%*s' "$pad" '')$right"
}

# Key-Value row with sleek colors
mubx_kvrow() {
  local key="$1" val="$2"
  mubx_put "$(printf '  %s%-13s%s %s%s%s' "$C_SLATE" "$key" "$C_RESET" "$C_ELECTRIC" "$val" "$C_RESET")"
}

# Subtle horizontal rule
mubx_rule() {
  printf '%s%s%s\n' "$C_BORDER" "$(printf '─%.0s' $(seq 1 $((MUBX_IW + 2))))" "$C_RESET"
}

# Status Pill badges
mubx_pill() { # $1 type (UP|DOWN|WARN|ACTIVE|OFF) $2 custom label
  local type label="${2:-}"
  type="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$type" in
    UP|ACTIVE|OK|PASS)
      printf '%s● %s%s' "$C_MINT" "${label:-ONLINE}" "$C_RESET"
      ;;
    DOWN|FAIL|OFF)
      printf '%s○ %s%s' "$C_CORAL" "${label:-OFFLINE}" "$C_RESET"
      ;;
    WARN|PARTIAL)
      printf '%s◐ %s%s' "$C_AMBER" "${label:-PARTIAL}" "$C_RESET"
      ;;
    *)
      printf '%s· %s%s' "$C_SLATE" "${label:-$type}" "$C_RESET"
      ;;
  esac
}

# Single service status dot
svc_dot() {
  if systemctl is-active --quiet "$1" 2>/dev/null; then
    printf '%s●%s' "$C_MINT" "$C_RESET"
  else
    printf '%s○%s' "$C_CORAL" "$C_RESET"
  fi
}

# Group card helpers with hint accumulation
declare -a _mubx_hints=()

mubx_group_open() { # $1 section title
  _mubx_hints=()
  mubx_box_top "$1"
}

mubx_hint() { _mubx_hints+=("$1"); }

mubx_group_close() {
  mubx_box_bot
  local h
  if [ "${#_mubx_hints[@]}" -gt 0 ]; then
    for h in "${_mubx_hints[@]}"; do
      printf '  %s↳ %s%s\n' "$C_SLATE" "${h:0:96}" "$C_RESET"
    done
    printf '\n'
  fi
}

mubx_dot() { # $1 color  $2 glyph  $3 word
  printf '%s%s%s  %s%-4s%s' "$1" "$2" "$C_RESET" "$1" "$3" "$C_RESET"
}

# Progress bar helper: $1 percentage (0-100), $2 width in characters (default 10)
mubx_bar() {
  local pct="${1:-0}" width="${2:-10}" filled empty fill_col
  pct="${pct%.*}"
  [ "$pct" -lt 0 ] && pct=0
  [ "$pct" -gt 100 ] && pct=100
  filled=$(( (pct * width) / 100 ))
  empty=$(( width - filled ))
  if [ "$pct" -ge 90 ]; then
    fill_col="$C_CORAL"
  elif [ "$pct" -ge 75 ]; then
    fill_col="$C_AMBER"
  else
    fill_col="$C_MINT"
  fi
  printf '%s[' "$C_BORDER"
  [ "$filled" -gt 0 ] && printf '%s%s' "$fill_col" "$(printf '█%.0s' $(seq 1 "$filled"))"
  [ "$empty" -gt 0 ] && printf '%s%s' "$C_SLATE" "$(printf '░%.0s' $(seq 1 "$empty"))"
  printf '%s]%s' "$C_BORDER" "$C_RESET"
}

mubx_clear() {
  if [ -t 1 ]; then
    clear || true
  fi
  return 0
}

# Robust file locking helper using flock (with fallback to mkdir lock)
mubx_with_lock() { # $1 lock_file  $@ command
  local lockfile="$1" lockdir=""
  shift
  local parent_dir
  parent_dir="$(dirname "$lockfile")"
  if [ ! -d "$parent_dir" ]; then
    mkdir -p "$parent_dir" 2>/dev/null || true
  fi
  if [ ! -w "$parent_dir" ] 2>/dev/null; then
    # Parent directory is unwritable (e.g. testing sandbox or unprivileged user)
    "$@"
    return $?
  fi
  if command -v flock >/dev/null 2>&1; then
    (
      exec 200>"$lockfile"
      flock -w 15 200 || { echo "[!] Failed to acquire lock on $lockfile" >&2; return 1; }
      "$@"
    )
  else
    lockdir="${lockfile}.lock"
    local retry=0
    while ! mkdir "$lockdir" 2>/dev/null; do
      sleep 0.05
      retry=$((retry + 1))
      if [ "$retry" -ge 40 ]; then
        echo "[!] Timeout waiting for lock on $lockfile" >&2
        return 1
      fi
    done
    "$@"
    local rc=$?
    rmdir "$lockdir" 2>/dev/null || true
    return $rc
  fi
}

mubx_with_users_lock() {
  local lockfile="${MUBX_USERS_LOCK:-}"
  if [ -z "$lockfile" ]; then
    if [ -n "${MUBX_USERS_FILE:-}" ]; then
      lockfile="$(dirname "$MUBX_USERS_FILE")/.users.lock"
    else
      lockfile="/etc/mubx/.users.lock"
    fi
  fi
  mubx_with_lock "$lockfile" "$@"
}
