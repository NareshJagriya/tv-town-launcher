#!/bin/bash
# Builds the copy to share: signed with your key (so it updates later), but with none of your keys or accounts inside.
# Saves it as dist/TV-Town.apk and opens the folder, ready to send to a phone (WhatsApp, Drive, email) or a USB stick.
#   scripts/build-for-friends.sh
source "$(dirname "$0")/lib.sh"

[ -d "$HOME/Library/Android/sdk" ] || die "Android Studio is needed to build (it brings the Android SDK)."
[ -f "$STATE_DIR/keystore.properties" ] || die "No signing key in $STATE_DIR (see README, Build)."
export ANDROID_HOME="$HOME/Library/Android/sdk"
[ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ] && \
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
[ -f "$REPO/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$REPO/local.properties"

say "Building TV Town for friends (no keys inside)"
(cd "$REPO" && ./gradlew -q assembleRelease -Ppublic --console=plain) || die "Build failed."
APK="$(ls -t "$REPO"/app/build/outputs/apk/release/tv-town-launcher-*-release.apk | head -1)"
mkdir -p "$REPO/dist"
cp "$APK" "$REPO/dist/TV-Town.apk"
ok "dist/TV-Town.apk ($(du -h "$REPO/dist/TV-Town.apk" | cut -f1))"
echo "  Send it with the steps in SHARE.md. Your own TV keeps using 'Install or Update TV Town'."
command -v open >/dev/null && open "$REPO/dist"
