package com.nareshjagriya.tvtown;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What TV Town learns about your own songs and films is kept on the drive they are on, not in the TV's small memory:
 * a hidden ".tvtown" folder (covers, singers' photos, posters, tidied song details, lyrics with rough timing), and
 * timed lyrics as "Song.lrc" beside each song. The drive keeps it all when it moves to another TV or TV Town is
 * reinstalled. Needs All files access (given to TV Town); without it, or with no drive, null is returned and the
 * caller falls back to the TV.
 */
final class DriveStore {

    private DriveStore() {
    }

    static final String FOLDER = ".tvtown";

    /** "/storage/29E9-0801/Music/Gazals/x.mp3" gives the drive "/storage/29E9-0801"; null for the TV's own storage. */
    @Nullable
    static File volumeOf(@Nullable String path) {
        if (path == null || !path.startsWith("/storage/") || path.startsWith("/storage/emulated")) return null;
        int end = path.indexOf('/', "/storage/".length());
        return end < 0 ? null : new File(path.substring(0, end));
    }

    /** The first plugged-in drive TV Town can write to, or null. */
    @Nullable
    static File anyDrive(Context ctx) {
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null || android.os.Build.VERSION.SDK_INT < 30) return null;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File d = v.getDirectory();
            if (d != null && d.canWrite()) return d;
        }
        return null;
    }

    /** A folder inside the drive's ".tvtown" (made when missing), or null when the drive cannot be written. */
    @Nullable
    static File dir(@Nullable File volume, String sub) {
        if (volume == null) return null;
        File root = new File(volume, FOLDER);
        File d = new File(root, sub);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        File noMedia = new File(root, ".nomedia");   // the TV's photo and video apps leave it alone
        try {
            if (!noMedia.exists()) //noinspection ResultOfMethodCallIgnored
                noMedia.createNewFile();
        } catch (Exception ignored) {
        }
        return d;
    }

    // ---- small lists kept as JSON files in .tvtown (song details, lyrics not found, singers' photos) -------------

    private static final Map<String, JSONObject> maps = new HashMap<>();

    /** The named list on a drive (read once, then kept in memory); empty when there is none yet. */
    static synchronized JSONObject map(@Nullable File volume, String name) {
        String key = (volume == null ? "-" : volume.getPath()) + "|" + name;
        JSONObject m = maps.get(key);
        if (m != null) return m;
        m = new JSONObject();
        File d = dir(volume, "");
        File f = d != null ? new File(d, name + ".json") : null;
        if (f != null && f.isFile()) {
            try {
                m = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // unreadable: starts again
            }
        }
        maps.put(key, m);
        return m;
    }

    /** Writes the named list back to the drive (a temporary file first, so a pulled drive never leaves half a file). */
    static synchronized boolean save(@Nullable File volume, String name) {
        File d = dir(volume, "");
        if (d == null) return false;
        JSONObject m = map(volume, name);
        File tmp = new File(d, name + ".json.tmp"), f = new File(d, name + ".json");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(m.toString().getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (Exception e) {
            return false;
        }
        return tmp.renameTo(f) || (f.delete() && tmp.renameTo(f));
    }

    /** Writes a text file (a lyrics file beside a song); false when the drive cannot be written. */
    static boolean write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        return tmp.renameTo(f);
    }
}
