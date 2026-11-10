package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Films and series opened straight on their page in Stremio or Nuvio, instead of a streaming app.
 * Nuvio (com.nuvio.tv) takes nuvio://tmdb/movie/123 with TMDB's own number, which TV Town already has.
 * Stremio (com.stremio.one) takes stremio:///detail/movie/tt0111161 with the IMDb number, looked up once on TMDB.
 * The links name their app: Nuvio also answers stremio:// links, and a "which app?" box is not wanted here.
 */
final class OpenIn {

    static final String APPS = "apps", STREMIO = "stremio", NUVIO = "nuvio";
    static final String STREMIO_PKG = "com.stremio.one", NUVIO_PKG = "com.nuvio.tv";

    private OpenIn() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** What Play, Watch now and Surprise Me open: "apps" (the streaming apps), or Stremio or Nuvio when installed. */
    static String chosen(Context c) {
        String v = prefs(c).getString("open_in", APPS);
        if (STREMIO.equals(v) && installed(c, STREMIO_PKG) || NUVIO.equals(v) && installed(c, NUVIO_PKG)) return v;
        return APPS;
    }

    static void choose(Context c, String v) {
        prefs(c).edit().putString("open_in", v).apply();
    }

    /** The choices on this TV: the streaming apps, then each of Stremio and Nuvio that is installed. */
    static List<String> choices(Context c) {
        List<String> out = new ArrayList<>();
        out.add(APPS);
        if (installed(c, STREMIO_PKG)) out.add(STREMIO);
        if (installed(c, NUVIO_PKG)) out.add(NUVIO);
        return out;
    }

    static String name(String v) {
        return STREMIO.equals(v) ? "Stremio" : NUVIO.equals(v) ? "Nuvio" : "Streaming apps";
    }

    /** Stremio or Nuvio when either is installed (the chosen one first): for titles no streaming app has. */
    @Nullable
    static String fallback(Context c) {
        String v = chosen(c);
        if (!APPS.equals(v)) return v;
        if (installed(c, NUVIO_PKG)) return NUVIO;
        if (installed(c, STREMIO_PKG)) return STREMIO;
        return null;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The intent that opens this title's page in `which`, or null when the title can't be found there. Network: off the main thread. */
    @Nullable
    static Intent intent(Context ctx, Tmdb.Title t, String which) {
        String type = t.isSeries() ? "series" : "movie";
        Uri uri;
        String pkg;
        if (NUVIO.equals(which)) {
            if (t.id <= 0) return null;
            uri = Uri.parse("nuvio://tmdb/" + type + "/" + t.id);
            pkg = NUVIO_PKG;
        } else if (STREMIO.equals(which)) {
            String imdb = null;
            try {
                if (t.id > 0) imdb = Tmdb.imdbId(ctx, t.isSeries() ? "tv" : "movie", t.id);
            } catch (Exception ignored) {
                // offline, or TMDB has none
            }
            if (imdb == null) return null;
            uri = Uri.parse("stremio:///detail/" + type + "/" + imdb);
            pkg = STREMIO_PKG;
        } else return null;
        // A fresh start each time: Nuvio ignores a new link while it is already open on a title (tested), so its old
        // screens are cleared and it opens on this one.
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }
}
