package com.nareshjagriya.tvtown;

import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.StatFs;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Settings' tools for the TV itself: free memory, free space, Bluetooth speakers and headphones, what's playing, and
 * what your apps say you're in the middle of (Android TV's Watch Next list).
 */
final class TvTools {

    private TvTools() {
    }

    // ---- memory and storage ----------------------------------------------------------------------

    static long freeMemoryMb(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    /** Stops apps waiting in the background (never system apps or TV Town); returns how many MB came back. Off the main thread. */
    static long freeUpMemory(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        long before = freeMemoryMb(c);
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(c.getPackageName())) continue;
            am.killBackgroundProcesses(ai.packageName);
        }
        try {
            Thread.sleep(800);   // the memory shows up free a moment later
        } catch (InterruptedException ignored) {
            // report what is free now
        }
        return Math.max(0, freeMemoryMb(c) - before);
    }

    /** "3.2 GB free" for the TV, then "· drive 1.1 TB free" when a drive is in. */
    static String storage(Context c) {
        String s = free(new StatFs(c.getFilesDir().getAbsolutePath())) + L10n.t(" free");
        java.io.File drive = DriveStore.anyDrive(c);
        if (drive != null) {
            try {
                s += L10n.t("  ·  drive ") + free(new StatFs(drive.getAbsolutePath())) + L10n.t(" free");
            } catch (IllegalArgumentException ignored) {
                // the drive went
            }
        }
        return s;
    }

    private static String free(StatFs f) {
        double gb = f.getAvailableBytes() / 1e9;
        return gb >= 1000 ? String.format(Locale.US, "%.1f TB", gb / 1000) : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- Bluetooth -------------------------------------------------------------------------------

    private static BluetoothA2dp speakerLink;   // Android hands this over once, a moment after asking

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager service = c.getSystemService(BluetoothManager.class);
        if (service == null) return null;
        return service.getAdapter();
    }

    /** Gives `done` the link to Bluetooth speakers, on the main thread; null when Bluetooth is off or not allowed. */
    static void audio(Context c, Consumer<BluetoothA2dp> done) {
        if (speakerLink != null) {
            done.accept(speakerLink);
            return;
        }
        BluetoothAdapter radio = adapter(c);
        if (radio == null || !radio.isEnabled()) {
            done.accept(null);
            return;
        }
        BluetoothProfile.ServiceListener handedOver = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int which, BluetoothProfile link) {
                speakerLink = (BluetoothA2dp) link;
                done.accept(speakerLink);
            }

            @Override
            public void onServiceDisconnected(int which) {
                speakerLink = null;
            }
        };
        boolean waiting = false;
        try {
            waiting = radio.getProfileProxy(c.getApplicationContext(), handedOver, BluetoothProfile.A2DP);
        } catch (SecurityException notAllowed) {
            // answered below
        }
        if (!waiting) done.accept(null);
    }

    /** Paired devices that play sound; the remote and game controllers aren't speakers. */
    static List<BluetoothDevice> speakers(Context c) {
        List<BluetoothDevice> sound = new ArrayList<>();
        BluetoothAdapter radio = adapter(c);
        if (radio == null) return sound;
        try {
            for (BluetoothDevice device : radio.getBondedDevices()) {
                BluetoothClass kind = device.getBluetoothClass();
                boolean unknown = kind == null;
                if (unknown || kind.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO || kind.hasService(BluetoothClass.Service.AUDIO)) {
                    sound.add(device);
                }
            }
        } catch (SecurityException notAllowed) {
            // none to show
        }
        return sound;
    }

    static String name(BluetoothDevice d) {
        String shown = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) shown = d.getAlias();
            if (shown == null || shown.isEmpty()) shown = d.getName();
        } catch (SecurityException notAllowed) {
            // its address will do
        }
        return shown == null || shown.isEmpty() ? d.getAddress() : shown;
    }

    static boolean connected(BluetoothA2dp link, BluetoothDevice d) {
        if (link == null) return false;
        try {
            return link.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException notAllowed) {
            return false;
        }
    }

    /** Connecting a speaker isn't in Android's public list of calls, though the link itself can do it. */
    static boolean connect(BluetoothA2dp link, BluetoothDevice d, boolean on) {
        if (link == null) return false;
        String call = on ? "connect" : "disconnect";
        try {
            Object answer = BluetoothA2dp.class.getMethod(call, BluetoothDevice.class).invoke(link, d);
            return Boolean.TRUE.equals(answer);
        } catch (ReflectiveOperationException | RuntimeException refused) {
            return false;
        }
    }

    // ---- Now playing -----------------------------------------------------------------------------

    /** What's playing on the TV, else the latest paused thing with a title; null when nothing, or without notification access. */
    static MediaController nowPlaying(Context c) {
        MediaSessionManager sessions = c.getSystemService(MediaSessionManager.class);
        if (sessions == null) return null;
        List<MediaController> active;
        try {
            active = sessions.getActiveSessions(new ComponentName(c, MediaWatch.class));
        } catch (SecurityException noAccess) {
            return null;
        }
        if (active == null) return null;
        MediaController paused = null;
        for (MediaController one : active) {
            if (title(one).isEmpty()) continue;
            if (playing(one)) return one;
            if (paused == null) paused = one;
        }
        return paused;
    }

    /** True when TV Town may see what other apps are playing (notification access is on). */
    static boolean seesPlaying(Context c) {
        android.app.NotificationManager n = c.getSystemService(android.app.NotificationManager.class);
        return n != null && n.isNotificationListenerAccessGranted(new ComponentName(c, MediaWatch.class));
    }

    static boolean playing(MediaController session) {
        PlaybackState now = session.getPlaybackState();
        if (now == null) return false;
        int state = now.getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    static String title(MediaController session) {
        return firstText(session.getMetadata(), MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
    }

    /** The picture the app gives for what's playing (album art), or null. */
    @androidx.annotation.Nullable
    static android.graphics.Bitmap art(MediaController session) {
        MediaMetadata about = session.getMetadata();
        if (about == null) return null;
        android.graphics.Bitmap picture = about.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (picture == null) picture = about.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (picture == null) picture = about.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return picture;
    }

    // Music apps, in the order they're offered when nothing is playing.
    static final String[][] MUSIC_APPS = {{"com.google.android.youtube.tvmusic", "YouTube Music"}, {"com.spotify.tv.android", "Spotify"},
            {"com.amazon.mp3", "Amazon Music"}, {"com.jio.media.jiobeats", "JioSaavn"}, {"com.gaana", "Gaana"}, {"com.apple.android.music", "Apple Music"}};

    /** The first music app installed ({package, name}), or null. */
    @androidx.annotation.Nullable
    static String[] musicApp(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String[] a : MUSIC_APPS) if (pm.getLeanbackLaunchIntentForPackage(a[0]) != null || pm.getLaunchIntentForPackage(a[0]) != null) return a;
        return null;
    }

    static String artist(MediaController session) {
        return firstText(session.getMetadata(), MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
    }

    private static String firstText(MediaMetadata about, String... wanted) {
        if (about == null) return "";
        for (String key : wanted) {
            CharSequence found = about.getText(key);
            String clean = found == null ? "" : found.toString().trim();
            if (!clean.isEmpty()) return clean;
        }
        return "";
    }

    // ---- Watch Next: what your apps say you're in the middle of ----------------------------------

    static final class Watching {
        String pkg, app, title, line, intentUri, image;
        long at, pos, dur;   // where you stopped and how long it is, in ms (0 when the app doesn't say)
        float frac = -1;
    }

    /**
     * What your apps list as "continue" or "next episode" in the TV's Watch Next list, latest first; "new season" and
     * watchlist entries are promotions. A picture that isn't a plain web address is read here and put in ImageCache
     * under its address. Blocking: off the main thread.
     */
    static List<Watching> watchNext(Context c, int max) {
        return watchNextOf(c, max, false);
    }

    /** What your apps keep in the TV's watchlist (saved for later), latest first. Blocking. */
    static List<Watching> appWatchlist(Context c, int max) {
        return watchNextOf(c, max, true);
    }

    private static List<Watching> watchNextOf(Context c, int max, boolean saved) {
        List<Watching> list = new ArrayList<>();
        PackageManager apps = c.getPackageManager();
        String newestFirst = TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + L10n.t(" DESC");
        try (Cursor row = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, newestFirst)) {
            while (row != null && list.size() < max && row.moveToNext()) {
                Watching w = fromRow(c, row, apps, saved);
                if (w != null) list.add(w);
            }
        } catch (RuntimeException noList) {
            // not allowed, or this TV has no Watch Next
        }
        if (!saved && list.size() < max) {   // then the apps' own L10n.t("Continue watching") rows on the TV's home
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Watching w : list) have.add(w.pkg + "|" + w.title.toLowerCase(Locale.ROOT));
            for (Watching w : appRows(c, apps, max)) {
                if (list.size() >= max) break;
                if (have.add(w.pkg + "|" + w.title.toLowerCase(Locale.ROOT))) list.add(w);
            }
        }
        return list;
    }

    /** An item an app labels as one to carry on with: its words start "Continue watching", "Resume" or "Keep watching". */
    static boolean markedToContinue(String words) {
        String w = words.trim().toLowerCase(Locale.ROOT);
        return w.startsWith("continue watching") || w.startsWith("resume") || w.startsWith("keep watching");
    }

    /** A row an app puts on the TV's home that is about carrying on: "Continue watching", "Resume", "Keep watching"... */
    static boolean continueRow(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("continue") || n.contains("resume") || n.contains("keep watching") || n.contains("carry on")
                || n.contains("pick up") || n.contains("jump back") || n.contains("recently watched") || n.contains("watch history")
                || n.contains("देखना जारी") || n.contains("जारी रखें");
    }

    /**
     * What apps that don't use Watch Next show on the TV's home instead: titles in a row named like "Continue watching",
     * and anything in any of their rows that has a place you stopped at. As Flow and other launchers do.
     */
    private static List<Watching> appRows(Context c, PackageManager apps, int max) {
        List<Watching> out = new ArrayList<>();
        java.util.Map<Long, String[]> rows = new java.util.HashMap<>();   // channel id: package, its name
        try (Cursor ch = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (ch != null && ch.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(field(ch, TvContract.Channels.COLUMN_TYPE))) continue;
                if (number(ch, TvContract.Channels.COLUMN_BROWSABLE, 1) == 0) continue;
                String pkg = field(ch, TvContract.Channels.COLUMN_PACKAGE_NAME);
                if (pkg.isEmpty() || apps.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
                rows.put(number(ch, TvContract.Channels._ID, -1), new String[]{pkg, field(ch, TvContract.Channels.COLUMN_DISPLAY_NAME)});
            }
        } catch (RuntimeException notAllowed) {
            return out;
        }
        if (rows.isEmpty()) return out;
        try (Cursor row = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (row != null && out.size() < max && row.moveToNext()) {
                String[] in = rows.get(number(row, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, -1));
                if (in == null || number(row, TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                long pos = number(row, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                if (android.util.Log.isLoggable("TVTown", android.util.Log.DEBUG))
                    android.util.Log.d("TVTown", "row item: " + in[0] + " | " + field(row, TvContract.PreviewPrograms.COLUMN_TITLE)
                            + L10n.t(" | pos=") + pos + L10n.t(" dur=") + number(row, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0)
                            + L10n.t(" type=") + number(row, TvContract.PreviewPrograms.COLUMN_TYPE, -1)
                            + " watchNextType=" + number(row, TvContract.PreviewPrograms.COLUMN_INTERACTION_TYPE, -1)
                            + L10n.t(" weight=") + number(row, TvContract.PreviewPrograms.COLUMN_WEIGHT, -1)
                            + L10n.t(" internal=") + field(row, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                            + L10n.t(" desc=") + field(row, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION));
                String says = field(row, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
                boolean marked = markedToContinue(says);   // Netflix: L10n.t("Continue Watching Episode 1")
                if (!continueRow(in[1]) && pos <= 0 && !marked) continue;   // a row of picks, not of what you're watching
                Watching w = new Watching();
                w.pkg = in[0];
                w.app = appName(apps, w.pkg);
                w.title = field(row, TvContract.PreviewPrograms.COLUMN_TITLE);
                if (w.title.isEmpty()) continue;
                if (w.pkg.contains("videolan")) {   // VLC gives the file's name ("Anaconda 2025 AAC5 1"): tidied as My Movies does
                    MyMovies.Parsed tidy = MyMovies.parse(w.title, "", "");
                    if (!tidy.title.isEmpty()) w.title = tidy.title + (tidy.year > 0 ? " (" + tidy.year + ")" : "");
                }
                w.line = placeInShow(row, w.title) + w.app;
                w.pos = pos;
                w.dur = number(row, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0);
                if (w.pos > 0 && w.dur > 0) w.frac = Math.min(1f, w.pos / (float) w.dur);
                String open = field(row, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
                w.intentUri = open.isEmpty() ? null : open;
                String picture = field(row, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
                if (picture.isEmpty()) picture = field(row, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
                if (!picture.isEmpty()) w.image = keepPicture(c, picture);
                out.add(w);
            }
        } catch (RuntimeException notAllowed) {
            // the TV keeps these to itself
        }
        if (android.util.Log.isLoggable("TVTown", android.util.Log.DEBUG)) for (String[] r : rows.values()) android.util.Log.d("TVTown", "app row: " + r[0] + " / " + r[1]);
        android.util.Log.d("TVTown", "app rows: " + rows.size() + " rows, " + out.size() + " to continue");
        return out;
    }

    private static Watching fromRow(Context c, Cursor row, PackageManager apps, boolean saved) {
        long kind = number(row, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
        if (saved ? kind != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST
                : kind != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && kind != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) return null;
        String pkg = field(row, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
        boolean hiddenByApp = number(row, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0;
        if (hiddenByApp || pkg.isEmpty() || apps.getLeanbackLaunchIntentForPackage(pkg) == null) return null;
        Watching w = new Watching();
        w.pkg = pkg;
        w.app = appName(apps, pkg);
        w.title = field(row, TvContract.WatchNextPrograms.COLUMN_TITLE);
        w.line = placeInShow(row, w.title) + w.app;
        w.pos = number(row, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
        w.dur = number(row, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, 0);
        if (w.pos > 0 && w.dur > 0) w.frac = Math.min(1f, w.pos / (float) w.dur);
        w.at = number(row, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0);
        if (android.util.Log.isLoggable("TVTown", android.util.Log.DEBUG))
            android.util.Log.d("TVTown", "watch next: " + w.pkg + " / " + w.title + " pos=" + w.pos + " dur=" + w.dur + " type=" + kind);
        String open = field(row, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
        w.intentUri = open.isEmpty() ? null : open;
        String picture = field(row, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
        if (picture.isEmpty()) picture = field(row, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
        if (!picture.isEmpty()) w.image = keepPicture(c, picture);
        return w;
    }

    private static String appName(PackageManager apps, String pkg) {
        try {
            return apps.getApplicationLabel(apps.getApplicationInfo(pkg, 0)).toString().trim();
        } catch (PackageManager.NameNotFoundException gone) {
            return pkg;
        }
    }

    /** "Season 2 · Episode 1 · ", or the episode's own name; empty for a film. */
    private static String placeInShow(Cursor row, String title) {
        String season = field(row, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = field(row, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        String named = field(row, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
        List<String> parts = new ArrayList<>();
        if (!season.isEmpty()) parts.add(L10n.t("Season ") + season);
        if (!episode.isEmpty()) parts.add(L10n.t("Episode ") + episode);
        if (parts.isEmpty() && !named.isEmpty() && !named.equals(title)) parts.add(named);
        return parts.isEmpty() ? "" : String.join(" · ", parts) + " · ";
    }

    /** Its address once ImageCache can show it; null when it can't be read. */
    private static String keepPicture(Context c, String address) {
        if (ImageCache.allowed(address) || ImageCache.peek(address) != null) return address;
        Bitmap read = read(c, address);
        if (read == null) return null;
        ImageCache.put(address, read);
        return address;
    }

    private static String field(Cursor row, String column) {
        int at = row.getColumnIndex(column);
        String value = at < 0 || row.isNull(at) ? null : row.getString(at);
        return value == null ? "" : value.trim();
    }

    private static long number(Cursor row, String column, long missing) {
        int at = row.getColumnIndex(column);
        return at < 0 || row.isNull(at) ? missing : row.getLong(at);
    }

    /** A picture from an app's own address (content://, or a web host ImageCache doesn't fetch), at most 960 px wide. */
    private static Bitmap read(Context c, String address) {
        try (InputStream source = address.startsWith("http") ? web(address) : c.getContentResolver().openInputStream(android.net.Uri.parse(address))) {
            Bitmap picture = source == null ? null : BitmapFactory.decodeStream(source);
            if (picture == null || picture.getWidth() <= 960) return picture;
            return Bitmap.createScaledBitmap(picture, 960, Math.max(1, picture.getHeight() * 960 / picture.getWidth()), true);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static InputStream web(String address) throws java.io.IOException {
        HttpURLConnection link = (HttpURLConnection) new URL(address).openConnection();
        link.setConnectTimeout(15_000);
        link.setReadTimeout(20_000);
        return link.getInputStream();
    }
}
