#!/bin/bash
# Re-applies the TV tune-up: features nobody uses turned off, snappier animations.
# Nothing is uninstalled; restore-default.sh undoes all of it.
#   scripts/tuneup.sh [tv-ip]
source "$(dirname "$0")/lib.sh"
connect_tv "$1"

say "Turning off unused background apps"
for p in com.google.android.tvrecommendations fusion.android.tv.demo; do
  if enabled "$p"; then
    tv pm disable-user --user 0 "$p" >/dev/null
    log_change "turned off $p" "$ADB -s $ANDROID_SERIAL shell pm enable $p"
    ok "Turned off $p"
  elif installed "$p"; then
    ok "$p already off"
  fi
done

say "Animations at 0.5x (snappier menus)"
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  cur="$(tv settings get global $s)"
  if [ "$cur" != "0.5" ]; then
    tv settings put global $s 0.5
    log_change "$s $cur -> 0.5" "$ADB -s $ANDROID_SERIAL shell settings put global $s ${cur/null/1.0}"
    ok "$s: $cur -> 0.5"
  else
    ok "$s already 0.5"
  fi
done

say "Done."
echo "  Changes and undo commands: $LOG"
