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
        String s = free(new StatFs(c.getFilesDir().getAbsolutePath())) + " free";
        java.io.File drive = DriveStore.anyDrive(c);
        if (drive != null) {
            try {
                s += "  ·  drive " + free(new StatFs(drive.getAbsolutePath())) + " free";
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
        String newestFirst = TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC";
        try (Cursor row = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, newestFirst)) {
            while (row != null && list.size() < max && row.moveToNext()) {
                Watching w = fromRow(c, row, apps, saved);
                if (w != null) list.add(w);
            }
        } catch (RuntimeException noList) {
            // not allowed, or this TV has no Watch Next
        }
        return list;
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
        if (!season.isEmpty()) parts.add("Season " + season);
        if (!episode.isEmpty()) parts.add("Episode " + episode);
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
