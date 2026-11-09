package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What you were watching in other apps, for Continue watching. Most apps on this kind of TV don't fill in Android's
 * shared Watch Next list, but they all say what is playing (for the remote and the notification), so TV Town keeps
 * its own note of it: the title, the picture, where you stopped and when. Music and short clips are left out, and a
 * title watched to the end drops off.
 */
final class Watched {

    static final class Item {
        String pkg, title, sub = "", image;   // image: a web address, or "watched:<name>" for a picture kept here
        long pos, dur, at;
    }

    private static final int KEEP = 30;
    private static final String[] MUSIC = {"music", "spotify", "gaana", "saavn", "wynk", "hungama", "audible", "podcast", "radio", "soundcloud"};
    private static long lastWrite;
    private static String lastKey = "";

    private Watched() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("watched", Context.MODE_PRIVATE);
    }

    /** Notes what this app is playing now. Called when its title or its play state changes. */
    static synchronized void record(Context c, MediaController m) {
        if (!Features.on(c, Features.APPS)) return;   // switched off in Settings → Features
        String pkg = m.getPackageName();
        if (pkg.equals(c.getPackageName()) || pkg.equals(MyMoviesActivity.VLC)) return;   // the drive's films are noted by My Movies
        if (notWatching(c, pkg)) return;
        MediaMetadata md = m.getMetadata();
        PlaybackState ps = m.getPlaybackState();
        if (md == null || ps == null) return;
        String title = TvTools.title(m);
        if (title.isEmpty()) return;
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean yt = HomeKeyService.youTube(pkg);
        if (dur > 0 && dur < (yt ? 4 : 10) * 60_000L) return;   // a clip or a song, not something to come back to
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0)
            pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
        String key = pkg + "|" + title;
        long now = System.currentTimeMillis();
        boolean playing = ps.getState() == PlaybackState.STATE_PLAYING;
        if (playing && key.equals(lastKey) && now - lastWrite < 15_000) return;   // a note every 15 seconds is plenty
        lastKey = key;
        lastWrite = now;
        List<Item> all = list(c);
        Item it = null;
        for (Item x : all) if ((x.pkg + "|" + x.title).equals(key)) it = x;
        if (it == null) {
            it = new Item();
            it.pkg = pkg;
            it.title = title;
            all.add(0, it);
        }
        CharSequence sub = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (sub == null) sub = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (sub != null) it.sub = sub.toString();
        if (pos > 0) it.pos = pos;
        if (dur > 0) it.dur = dur;
        it.at = now;
        if (it.image == null) it.image = picture(c, md, key);
        all.remove(it);
        if (it.dur > 0 && it.pos > it.dur * 0.93) {   // watched to the end: done, and off the watchlist
            save(c, all);
            Library.finished(c, it.title);
            return;
        }
        all.add(0, it);
        while (all.size() > KEEP) all.remove(all.size() - 1);
        save(c, all);
    }

    /**
     * Music apps, and the TV's own parts (Live TV and its tuner run as the system and report test titles): never
     * something to continue.
     */
    private static boolean notWatching(Context c, String pkg) {
        String low = pkg.toLowerCase(Locale.ROOT);
        for (String w : MUSIC) if (low.contains(w)) return true;
        for (String w : new String[]{"tvcenter", "tvinput", "livetv", "tv.tuner", "mediatek.wwtv"}) if (low.contains(w)) return true;
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).uid == android.os.Process.SYSTEM_UID;
        } catch (Exception e) {
            return false;
        }
    }

    /** The app's picture for it: its web address, or the picture itself kept in a small file. */
    @androidx.annotation.Nullable
    private static String picture(Context c, MediaMetadata md, String key) {
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = md.getString(k);
            if (u != null && u.startsWith("https://")) return u;
        }
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) return null;
        String name = Integer.toHexString(key.hashCode()) + ".jpg";
        File dir = new File(c.getFilesDir(), "watched");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            Bitmap small = b.getWidth() > 640 ? Bitmap.createScaledBitmap(b, 640, Math.max(1, b.getHeight() * 640 / b.getWidth()), true) : b;
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return "watched:" + name;
        } catch (Exception e) {
            return null;
        }
    }

    /** Readies a kept picture for drawing (off the main thread), so ImageCache has it under its name. */
    static void loadPicture(Context c, @androidx.annotation.Nullable String image) {
        if (image == null || !image.startsWith("watched:") || ImageCache.peek(image) != null) return;
        Bitmap b = BitmapFactory.decodeFile(new File(new File(c.getFilesDir(), "watched"), image.substring(8)).getPath());
        if (b != null) ImageCache.put(image, b);
    }

    static synchronized List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.pkg = o.optString("pkg");
                it.title = o.optString("title");
                it.sub = o.optString("sub");
                it.image = o.optString("image").isEmpty() ? null : o.optString("image");
                it.pos = o.optLong("pos");
                it.dur = o.optLong("dur");
                it.at = o.optLong("at");
                if (!notWatching(c, it.pkg)) out.add(it);   // kept from before the TV's own parts were left out
            }
        } catch (Exception ignored) {
            // nothing kept yet
        }
        return out;
    }

    private static void save(Context c, List<Item> all) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : all)
                a.put(new JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.sub).put("image", it.image == null ? "" : it.image)
                        .put("pos", it.pos).put("dur", it.dur).put("at", it.at));
        } catch (Exception e) {
            return;
        }
        prefs(c).edit().putString("items", a.toString()).apply();
    }
}
