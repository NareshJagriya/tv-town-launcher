#!/bin/bash
# Installs or updates TV Town Launcher on the TV and makes it the home screen, without root and without turning
# anything off: it switches on the TV Town home helper (an accessibility service), which opens TV Town whenever the
# TV's own home would appear. On the TV, Settings > Switch to Google TV home hands Home back to the TV's own
# launcher, and opening TV Town from the app list makes it the home again. restore-default.sh undoes everything.
# Safe to run again at any time, for example after an Android system update.
#   scripts/install-as-home.sh [tv-ip]
source "$(dirname "$0")/lib.sh"

# 1. The app: build the signed release when the build tools and key are here, else use the copy in dist/.
APK=""
if [ -d "$HOME/Library/Android/sdk" ] && [ -f "$STATE_DIR/keystore.properties" ]; then
  say "Building TV Town Launcher"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ] && \
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  [ -f "$REPO/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$REPO/local.properties"
  if (cd "$REPO" && ./gradlew -q assembleRelease --console=plain); then
    APK="$(ls -t "$REPO"/app/build/outputs/apk/release/tv-town-launcher-*-release.apk | head -1)"
    mkdir -p "$REPO/dist" && cp "$APK" "$REPO/dist/"
    ok "Built $(basename "$APK")"
  else
    warn "Build failed; using the copy in dist/"
  fi
fi
[ -z "$APK" ] && APK="$(ls -t "$REPO"/dist/tv-town-launcher-*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || die "No APK found in dist/ and no way to build one."

connect_tv "$1"

# 2. Install or update (keeps your Customize settings).
say "Installing $(basename "$APK")"
was_installed=no; installed "$PACKAGE" && was_installed=yes
"$ADB" install -r "$APK" >/dev/null || die "Install failed."
ok "Installed"
[ "$was_installed" = no ] && log_change "installed $PACKAGE" "$ADB -s $ANDROID_SERIAL uninstall $PACKAGE"

# 3. Home screen, through the home helper. The TV's own launcher stays on (turning it off sends Google TV to a
#    recovery screen, and would stop the switch back to it from working).
say "Making TV Town the home screen"
detect_stock_launcher
for L in $STOCK_LAUNCHERS $KNOWN_LAUNCHERS; do
  if installed "$L" && ! enabled "$L"; then   # turned off by an earlier version of this script
    tv pm enable "$L" >/dev/null && ok "Turned $L back on (the helper keeps TV Town as home)"
    log_change "turned the stock launcher $L back on" "$ADB -s $ANDROID_SERIAL shell pm disable-user --user 0 $L"
  fi
done
SVC="$PACKAGE/$PACKAGE.HomeKeyService"
CUR="$(tv settings get secure enabled_accessibility_services)"
# Lets TV Town put the helper back by itself whenever Android stops it (an update, a force stop, low memory).
tv pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 && ok "TV Town can repair its home helper by itself"
case "$CUR" in
  *"$SVC"*)
    # Installing restarted TV Town: Android may keep the helper listed but stopped. Off and on binds it afresh.
    WITHOUT="$(echo "$CUR" | tr ':' '\n' | grep -v -x "$SVC" | paste -sd: -)"
    if [ -n "$WITHOUT" ]; then tv settings put secure enabled_accessibility_services "$WITHOUT"
    else tv settings delete secure enabled_accessibility_services >/dev/null; fi
    sleep 1
    tv settings put secure enabled_accessibility_services "$CUR"
    tv settings put secure accessibility_enabled 1
    ok "Home helper restarted" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = null ]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
    tv settings put secure enabled_accessibility_services "$NEW"
    tv settings put secure accessibility_enabled 1
    log_change "turned on the TV Town home helper" "$ADB -s $ANDROID_SERIAL shell settings put secure enabled_accessibility_services \"$CUR\""
    ok "Home helper on"
    ;;
esac

tv input keyevent KEYCODE_HOME
say "Done. TV Town Launcher is your home screen."
echo "  To use the TV's own home: TV Town Settings > Switch to Google TV home."
echo "  To come back: open TV Town from the TV's app list."
echo "  Changes and undo commands: $LOG"
