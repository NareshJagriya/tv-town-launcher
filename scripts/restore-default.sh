#!/bin/bash
# Puts the TV back: the stock launcher on and Home again, Home button mode off, and optionally the tune-up
# undone and TV Town uninstalled.
#   scripts/restore-default.sh [tv-ip]
source "$(dirname "$0")/lib.sh"
connect_tv "$1"

say "Turning the stock launcher back on"
detect_stock_launcher
[ -n "$STOCK_LAUNCHERS" ] || die "Could not find the stock launcher. List turned-off apps with: adb shell pm list packages -d"
for L in $STOCK_LAUNCHERS; do
  tv pm enable "$L" >/dev/null && ok "$L on"
done
FIRST="$(echo $STOCK_LAUNCHERS | cut -d' ' -f1)"
ACT="$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "$FIRST" 2>/dev/null | tail -1)"
case "$ACT" in */*) tv cmd package set-home-activity --user 0 "$ACT" >/dev/null 2>&1 || true ;; esac
log_change "stock launcher back on ($STOCK_LAUNCHERS)" "run scripts/install-as-home.sh"

# Home button mode: take TV Town's accessibility service out of the enabled list.
SERVICES="$(tv settings get secure enabled_accessibility_services)"
case "$SERVICES" in
  *"$PACKAGE"*)
    LEFT="$(echo "$SERVICES" | tr ':' '\n' | grep -v "^$PACKAGE/" | paste -sd: -)"
    if [ -z "$LEFT" ]; then tv settings delete secure enabled_accessibility_services >/dev/null
    else tv settings put secure enabled_accessibility_services "$LEFT"; fi
    ok "Home button mode off"
    log_change "Home button mode off" "TV Settings > Accessibility > TV Town Home button"
    ;;
esac

printf '\nAlso undo the tune-up (background apps back on, animations 1x)? [y/N] '
read -r a
if [ "$a" = y ] || [ "$a" = Y ]; then
  for p in com.google.android.tvrecommendations fusion.android.tv.demo; do
    installed "$p" && tv pm enable "$p" >/dev/null && ok "Turned on $p"
  done
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    tv settings put global $s 1.0
  done
  ok "Animations back to 1x"
  log_change "tune-up undone" "run scripts/tuneup.sh"
fi

printf '\nUninstall TV Town Launcher from the TV? Your Customize settings will be lost. [y/N] '
read -r a
if [ "$a" = y ] || [ "$a" = Y ]; then
  "$ADB" uninstall "$PACKAGE" >/dev/null && ok "TV Town Launcher uninstalled"
  log_change "uninstalled $PACKAGE" "run scripts/install-as-home.sh"
fi

tv input keyevent KEYCODE_HOME
say "Done."
