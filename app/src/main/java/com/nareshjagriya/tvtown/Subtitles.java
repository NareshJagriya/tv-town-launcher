package com.nareshjagriya.tvtown;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Where a downloaded subtitle goes: next to the film as "Film.en.srt" (with All files access, so VLC and other
 * players find it by themselves), else in Downloads/TV Town on the TV, which VLC can read when it is passed along.
 */
final class Subtitles {

    private Subtitles() {
    }

    private static final String FOLDER = "TV Town";

    /** Saves the subtitle and returns its path. */
    static String save(Context ctx, MyMovies.Video v, String language, byte[] data) throws IOException {
        String name = fileName(v.name, language);
        File dir = Tracks.folder(ctx, v);
        if (dir != null && dir.canWrite()) {
            File out = new File(dir, name);
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(data);
                return out.getAbsolutePath();
            } catch (IOException ignored) {
                // read-only drive: Downloads instead
            }
        }
        if (Build.VERSION.SDK_INT < 29) throw new IOException(L10n.t("Can't save on this TV"));
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException(L10n.t("Can't save the subtitle"));
        try (OutputStream o = cr.openOutputStream(uri)) {
            if (o == null) throw new IOException(L10n.t("Can't save the subtitle"));
            o.write(data);
        }
        try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER + "/" + name)
                .getAbsolutePath();
    }

    /** "The Pianist (2002).mkv" in English: "The Pianist (2002).en.srt". */
    static String fileName(String video, String language) {
        String base = video.replaceAll("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + "." + (language == null || language.isEmpty() ? "und" : language) + ".srt";
    }

    /** Deletes a downloaded subtitle (directly, or through the media index for one in Downloads). */
    static void remove(Context ctx, @Nullable String path) {
        if (path == null) return;
        File f = new File(path);
        if (f.delete() || !f.exists() || Build.VERSION.SDK_INT < 29) return;
        try {
            ctx.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DATA + "=?", new String[]{path});
        } catch (Exception ignored) {
            // left in place; it is no longer used
        }
    }
}
