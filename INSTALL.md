# Installing TV Town on your TV

Installing it for someone else? [SHARE.md](SHARE.md) has a ready-made message for them.

TV Town is a home screen for Android TV and Google TV. You don't need a computer or any cables, and developer mode is only
needed for one optional extra (the phone remote's arrow keys and game controller, see below).

## Which TVs work

| Your TV or box | Works? |
|---|---|
| **Android TV or Google TV, Android 9 or later**: Sony, Philips, TCL, Xiaomi / Mi, OnePlus, Hisense (Android models), Toshiba, Nvidia Shield, Chromecast with Google TV, Google TV Streamer, onn and others | ✅ Yes |
| Android 10 | ✅ Everything except My Music (needs Android 11) |
| Android 9 | ✅ Everything except My Movies (needs Android 10) and My Music (Android 11) |
| Android 13 and later | ✅ Yes; see *If a switch is greyed out* below |
| Android 8 or older | ❌ No |
| Amazon Fire TV | ⚠️ Installs and opens from the app list, but Fire OS doesn't let any other app be the home screen |
| Samsung (Tizen), LG (webOS), Roku, Vizio, Hisense VIDAA, Apple TV | ❌ No: they don't run Android apps |

**Screens:** HD (720p), Full HD and 4K. TV Town lays out every screen for 16:9 and scales it, so it looks the same on
each; a 4K TV draws the menus at Full HD, as Android TV does.
**Memory:** 1 GB is enough (it was made on a 1 GB TV); 2 GB or more is smoother.
**Not sure what you have?** Settings → Device Preferences → About shows the *Android TV OS version*.

## 1. Get the file onto the TV

Pick one:

**With the Downloader app (easiest: no computer, no USB stick)**
1. On the TV, open the **Play Store**, search **Downloader** and install **Downloader by AFTVnews**.
2. Open Downloader (choose **Allow** if it asks for access to files).
3. In the box on its Home tab, type the code **9356599** and press **Go**. TV Town downloads. The code always gives
   the latest version, so you can share it with anyone.

**With a USB stick**
1. Download `TV-Town.apk` from the [Releases page](https://github.com/NareshJagriya/tv-town-launcher/releases/latest) on a computer and copy it to a USB stick.
2. Plug the stick into the TV and open it in the TV's file manager (or install the free **File Commander** or **X-plore** from the Play Store).
3. Select `TV-Town.apk`.

## 2. Allow this one install

The TV asks whether the app you used (file manager or Downloader) may install apps. Choose **Settings**, switch it **on**,
press **Back**, then **Install**. This permission is only for that app; you can switch it off again afterwards in
*Settings → Apps → Special app access → Install unknown apps*.

### Google TV: if there's no switch to allow it

On Google TV (Chromecast with Google TV, Google TV Streamer and newer TVs) the switch is hidden until Developer mode is
on. Go to *Settings → System → About*, scroll to *Android TV OS build* and press OK on it **7 times** ("You are now a
developer"). Then open *Settings → Apps → Security & restrictions → Unknown sources*, switch on **Downloader** (or your
file manager), go back and press **Install** again.

### If Play Protect says "Blocked" or "Unsafe app"

Google Play Protect warns about every app that doesn't come from the Play Store, and about apps made for an older Android
version. TV Town is open source: you can read every line on this page. Choose **Install anyway** (on some TVs: *More
details* → *Install anyway*). If it was blocked outright, open the Play Store → your profile → *Play Protect* → ⚙️ and
switch off *Scan apps with Play Protect* for a moment, install, then switch it back on.

## 3. Open TV Town and follow the Welcome screen

TV Town opens with a short setup, every step with a button that takes you to the right place:

- **Content style and country**: what TV Town suggests (Indian, Global or a mix).
- **Languages you watch in**: which films need subtitles, and the languages ticked first on the Random lists.
- **Make TV Town your home screen**: switch on *TV Town home helper* in Accessibility, then press Back.
  The Home button then always brings you to TV Town.
- **Show what's playing on your apps**: switch on *TV Town* in Notification access. This lets TV Town show
  Continue Watching from your apps and the song playing in YouTube Music or Spotify.
- **Films and series info (optional)**: scan the QR code with your phone, get a free TMDB key in two minutes
  (the phone shows the steps), paste it, done. Without it TV Town still shows your apps, your drive and your music.

### If a switch is greyed out (Android 13 and later)

Android protects these switches for apps installed from a file. Open *Settings → Apps → TV Town*, choose
**Allow restricted settings** (on some TVs it's under ⋮ or *More*), then go back and switch it on. If your TV has no such
option, a computer can allow it once: `adb shell appops set com.nareshjagriya.tvtown ACCESS_RESTRICTED_SETTINGS allow`.

## Your own films and music (optional)

Plug in a pen drive or hard disk (FAT32 or NTFS) with films and songs; My Movies and My Music find them by themselves.

No drive? Nothing stands empty: the My Movies tile becomes **My List** (films and series you save with + My List on
their page, opened on your apps), and My Music opens your music app (YouTube Music, Spotify and others).

## Updates

TV Town checks for a new version once a day and mentions it once. **Settings → Updates** installs it: the first time,
Android asks you to allow TV Town to install apps (switch it on, press Back, choose Updates again). Your settings stay.

## Make it yours

- **Weather beside the clock:** Settings → *Weather beside the clock*, then your town (typed on your phone if you like).
- **Back up everything:** Settings → *Back up all settings* saves `tv-town-backup.json` on your drive (or in
  Downloads/TV Town). Plug that drive into another TV, or reinstall, and *Restore settings from a backup* puts it all back.

- **The app row:** Customize → tick up to 10 apps; Left and Right on a ticked app move it.
- **Random cards:** ✏️ Customize in any Random pop-up: switch kinds off, add singers, shows or searches, or a new card.
- **Subtitle downloads (optional):** Settings → *About and backup → Subtitle downloads*, with a free [OpenSubtitles key](https://www.opensubtitles.com/consumers) typed on your phone.
- **Your phone as the remote:** Settings → *Phone and downloads → Connect my phone*, then scan the QR code. It closes by itself once your phone is connected.
  - Search, apps, play and pause, volume, Back, Home, downloads and sharing work straight away.
  - **The arrow keys, OK, typing and the game controller** need one more switch, because Android only lets an app press another app's buttons through its developer tools:
    1. TV Settings → *Device Preferences* → *About*, and press OK on **Build** seven times until it says you're a developer.
    2. Back in *Device Preferences*, open **Developer options** and switch on **USB debugging** (on some TVs it's called *Network debugging* or *ADB debugging*).
    3. Press an arrow on the phone. The TV asks *Allow debugging?*: tick **Always allow** and choose **Allow**.

    Only TV Town on the TV itself uses this, for those buttons. To undo it, switch the debugging option off again.
- **Move files with your phone:** in the phone remote's *Files* tab, *Send to TV* puts songs, films and subtitles on the drive, and *Get from TV* saves them on your phone. It needs a drive plugged into the TV, Android 11 or later, and both on the same Wi-Fi. Keep the page open until a file finishes. The *?* on that card explains each step.

## Going back to the TV's own home

Customize TV Town (the sliders at the top of TV Town's home) → Control Center → *Home screen* (press OK twice). Or uninstall TV Town in *Settings → Apps*.
