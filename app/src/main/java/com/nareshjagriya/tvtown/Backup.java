package com.nareshjagriya.tvtown;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Back up and restore everything you set up, in one file (tv-town-backup.json): Customize, the content style,
 * country and languages, the app row, the Random cards you changed, your watched list and ratings, My Movies'
 * matches, tags, favourites and resume points, My Music's song details, and the keys you added. Caches are left out.
 *
 * The file goes on a plugged-in drive when there is one (so it moves to another TV with the drive, and survives
 * reinstalling TV Town), else in Downloads/TV Town on the TV. Restore looks in the same places, and still reads an old
 * watched-list export (tv-town-watched.json).
 */
final class Backup {

    static final String FILE_NAME = "tv-town-backup.json", FOLDER = "TV Town";
    private static final String APP = "TV Town";

    /** The settings files kept; the rest are caches TV Town fills again by itself. */
    static final String[] FILES = {"home", "keys", "library", "my_movies", "my_music", "my_music_match", "pick_custom", "save", "save_pictures", "screensaver", "picks_seen", "tvtown_search"};

    /** Not carried to another TV: what belongs to this TV or this copy of TV Town. */
    private static final Set<String> SKIP = new HashSet<>(java.util.Arrays.asList("update_checked", "update_announced"));

    private Backup() {
    }

    /** Every kept setting as JSON: {"app":"TV Town","version":1,"files":{"home":{"key":{"t":"s","v":"…"}}}}. */
    static JSONObject toJson(Context ctx) throws Exception {
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            JSONObject f = new JSONObject();
            for (Map.Entry<String, ?> e : ctx.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (SKIP.contains(e.getKey())) continue;
                JSONObject v = value(e.getValue());
                if (v != null) f.put(e.getKey(), v);
            }
            files.put(name, f);
        }
        return new JSONObject().put("app", APP).put("version", 1).put("build", BuildConfig.VERSION_NAME)
                .put("time", System.currentTimeMillis()).put("files", files);
    }

    @Nullable
    static JSONObject value(Object v) throws Exception {
        if (v instanceof String) return new JSONObject().put("t", "s").put("v", v);
        if (v instanceof Boolean) return new JSONObject().put("t", "b").put("v", v);
        if (v instanceof Integer) return new JSONObject().put("t", "i").put("v", v);
        if (v instanceof Long) return new JSONObject().put("t", "l").put("v", v);
        if (v instanceof Float) return new JSONObject().put("t", "f").put("v", (double) (Float) v);
        if (v instanceof Set) {
            JSONArray a = new JSONArray();
            for (Object x : (Set<?>) v) a.put(String.valueOf(x));
            return new JSONObject().put("t", "set").put("v", a);
        }
        return null;
    }

    /** Puts every setting in the backup back (settings not in it are left as they are). Returns how many, or -1. */
    static int fromJson(Context ctx, JSONObject root) {
        if (!APP.equals(root.optString("app")) || root.optInt("version") != 1) return -1;
        JSONObject files = root.optJSONObject("files");
        if (files == null) return -1;
        int n = 0;
        for (String name : FILES) {
            JSONObject f = files.optJSONObject(name);
            if (f == null) continue;
            SharedPreferences.Editor ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
            for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject v = f.optJSONObject(key);
                if (v == null || SKIP.contains(key)) continue;
                switch (v.optString("t")) {
                    case "s": ed.putString(key, v.optString("v")); break;
                    case "b": ed.putBoolean(key, v.optBoolean("v")); break;
                    case "i": ed.putInt(key, v.optInt("v")); break;
                    case "l": ed.putLong(key, v.optLong("v")); break;
                    case "f": ed.putFloat(key, (float) v.optDouble("v")); break;
                    case "set": {
                        Set<String> s = new HashSet<>();
                        JSONArray a = v.optJSONArray("v");
                        for (int i = 0; a != null && i < a.length(); i++) s.add(a.optString(i));
                        ed.putStringSet(key, s);
                        break;
                    }
                    default: continue;
                }
                n++;
            }
            ed.commit();   // written before TV Town restarts
        }
        return n;
    }

    /** Where a backup can go or be found, best first: the drive (its TV Town folder, then TV Town's own folder on it), then the TV. */
    static List<File> places(Context ctx) {
        List<File> out = new ArrayList<>();
        File drive = DriveStore.anyDrive(ctx);   // needs All files access
        if (drive != null) out.add(new File(new File(drive, FOLDER), FILE_NAME));
        File[] dirs = ctx.getExternalFilesDirs(null);
        for (int i = 1; dirs != null && i < dirs.length; i++) if (dirs[i] != null) out.add(new File(dirs[i], FILE_NAME));
        out.add(new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER), FILE_NAME));
        File own = ctx.getExternalFilesDir(null);
        out.add(new File(own != null ? own : ctx.getFilesDir(), FILE_NAME));
        return out;
    }

    /** Writes the backup to the best place that takes it; returns where, or null. */
    @Nullable
    static String save(Context ctx) {
        byte[] data;
        try {
            data = toJson(ctx).toString(1).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        for (File f : places(ctx)) {
            File dir = f.getParentFile();
            try {
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException(L10n.t("no folder"));
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
                return f.getAbsolutePath();
            } catch (Exception e) {
                if (f.getAbsolutePath().contains(Environment.DIRECTORY_DOWNLOADS)) {   // Downloads without All files access
                    String at = saveToDownloads(ctx, data);
                    if (at != null) return at;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String saveToDownloads(Context ctx, byte[] data) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                if (o == null) return null;
                o.write(data);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + FILE_NAME;
        } catch (Exception e) {
            return null;
        }
    }

    /** The newest backup found, or null. */
    @Nullable
    static File find(Context ctx) {
        File best = null;
        for (File f : places(ctx)) {
            if (f.isFile() && f.canRead() && f.length() < 20_000_000 && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    /** Restores the newest backup; returns how many settings, or -1 when none was found or it can't be read. */
    static int restore(Context ctx) {
        File f = find(ctx);
        if (f == null) return -1;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            return fromJson(ctx, new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return -1;
        }
    }
}
