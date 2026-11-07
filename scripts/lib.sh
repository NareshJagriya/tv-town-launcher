#!/bin/bash
# Shared helpers for the TV Town Launcher scripts: finds adb, connects to the TV, logs changes.

PACKAGE="com.nareshjagriya.tvtown"
HOME_ACTIVITY="$PACKAGE/.MainActivity"
STATE_DIR="$HOME/.tvtown"
LOG="$STATE_DIR/tv-changes.log"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$STATE_DIR"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$*"; exit 1; }

# adb from the Android SDK, Homebrew, or PATH.
find_adb() {
  for a in "$HOME/Library/Android/sdk/platform-tools/adb" /opt/homebrew/bin/adb /usr/local/bin/adb "$(command -v adb 2>/dev/null)"; do
    [ -n "$a" ] && [ -x "$a" ] && { ADB="$a"; return; }
  done
  die "adb not found. Install it with:  brew install android-platform-tools"
}

# The TV address: first argument, else the one used last time, else ask.
connect_tv() {
  find_adb
  local addr="$1"
  [ -z "$addr" ] && [ -f "$STATE_DIR/tv-address" ] && addr="$(cat "$STATE_DIR/tv-address")"
  if [ -z "$addr" ]; then
    printf 'TV IP address (TV: Settings > Network > About): '
    read -r addr
  fi
  case "$addr" in *:*) ;; *) addr="$addr:5555" ;; esac
  say "Connecting to the TV at $addr"
  "$ADB" connect "$addr" >/dev/null 2>&1 || true
  local state
  state="$("$ADB" -s "$addr" get-state 2>&1 || true)"
  if echo "$state" | grep -q unauthorized; then
    die "The TV is asking to allow this Mac. Choose 'Always allow' on the TV, then run this again."
  fi
  [ "$state" = "device" ] || die "Could not reach the TV at $addr. Check that it is on, on the same Wi-Fi, and that
  network debugging is on (TV: Settings > Device Preferences > About > tap Build 7 times, then
  Developer options > USB debugging / ADB over network)."
  echo "$addr" > "$STATE_DIR/tv-address"
  export ANDROID_SERIAL="$addr"
  ok "Connected ($("$ADB" shell getprop ro.product.model | tr -d '\r'))"
}

tv() { "$ADB" shell "$@" | tr -d '\r'; }

# Records a change and how to undo it.
log_change() {
  printf '%s  %s\n    UNDO: %s\n' "$(date '+%Y-%m-%d %H:%M')" "$1" "$2" >> "$LOG"
}

enabled() { tv pm list packages -e "$1" | grep -qx "package:$1"; }
installed() { tv pm list packages "$1" | grep -qx "package:$1"; }

# The stock launcher: every other app that answers the Home intent, except the system's safety nets (boot-time
# FallbackHome screens, Settings, and Google TV's setup/recovery screen), which must never be turned off.
# Remembered once found, because a turned-off launcher no longer answers.
KNOWN_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.android.tv.launcher com.tpvision.launcher com.mediatek.wwtv.tvlauncher com.amazon.tv.launcher"
detect_stock_launcher() {
  local found
  found="$(tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
    | grep / | grep -v 'FallbackHome\|RecoveryActivity' | sed 's#/.*##; s/^ *//' | grep -vx "$PACKAGE" \
    | grep -v '^com.android.settings$\|^com.android.tv.settings$\|setupwraith\|setupwizard' | sort -u)"
  if [ -z "$found" ] && [ -f "$STATE_DIR/stock-launcher" ]; then
    found="$(cat "$STATE_DIR/stock-launcher")"
  fi
  if [ -z "$found" ]; then
    # Nothing answers Home (already turned off?): look for a known launcher that is installed but off.
    for p in $KNOWN_LAUNCHERS; do tv pm list packages -d "$p" | grep -qx "package:$p" && found="$found $p"; done
  fi
  STOCK_LAUNCHERS="$(echo $found)"
  [ -n "$STOCK_LAUNCHERS" ] && echo "$STOCK_LAUNCHERS" > "$STATE_DIR/stock-launcher"
}
