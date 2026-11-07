package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Your watched list: what you have watched, what you want to watch, and your ratings (1 to 10), kept on the TV. Each
 * title keeps enough to show its card offline (name, year, poster, scores). Shared by Must-Watch, My Movies and
 * Random (which can skip what you have watched). Export and import go through a file on a pen drive (or the TV's own
 * storage when none is plugged in).
 */
final class Library {

    private Library() {
    }

    static final int NONE = 0, WANT = 1, WATCHED = 2;
    static final String FILE_NAME = "tv-town-watched.json";

    /** One title in the list. */
    static final class Entry {
        String key, type, title, poster, language = "";
        int year, status, rating, tomato = -1, tmdbId;
        double imdb;
        long time;                 // when last changed
        List<String> genres = new ArrayList<>();

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("type", type).put("title", title).put("poster", poster == null ? "" : poster)
                    .put("language", language).put("year", year).put("status", status).put("rating", rating)
                    .put("tomato", tomato).put("tmdb", tmdbId).put("imdb", imdb).put("time", time)
                    .put("genres", new JSONArray(genres));
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key");
            e.type = "tv".equals(o.optString("type")) ? "tv" : "movie";
            e.title = o.optString("title");
            String p = o.optString("poster", "");
            e.poster = p.startsWith("https://") ? p : null;
            e.language = o.optString("language", "");
            e.year = o.optInt("year");
            e.status = Math.max(NONE, Math.min(WATCHED, o.optInt("status")));
            e.rating = Math.max(0, Math.min(10, o.optInt("rating")));
            e.tomato = o.optInt("tomato", -1);
            e.tmdbId = o.optInt("tmdb");
            e.imdb = o.optDouble("imdb", 0);
            e.time = o.optLong("time");
            JSONArray g = o.optJSONArray("genres");
            for (int i = 0; g != null && i < g.length(); i++) e.genres.add(g.optString(i));
            return e;
        }

        boolean valid() {
            return key != null && key.matches("(movie|tv):\\d{1,9}|jw:[a-z]{2}\\d{1,12}") && title != null && !title.isEmpty();
        }
    }

    /** The same title from TMDB or JustWatch gets the same key when its TMDB id is known. */
    static String keyOf(Tmdb.Title t) {
        return t.id > 0 ? t.type + ":" + t.id : "jw:" + t.jwId;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    static Entry get(Context ctx, String key) {
        String s = prefs(ctx).getString(key, null);
        if (s == null) return null;
        try {
            return Entry.from(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    static Entry get(Context ctx, Tmdb.Title t) {
        return get(ctx, keyOf(t));
    }

    static List<Entry> all(Context ctx) {
        List<Entry> out = new ArrayList<>();
        for (Object v : prefs(ctx).getAll().values()) {
            if (!(v instanceof String)) continue;
            try {
                Entry e = Entry.from(new JSONObject((String) v));
                if (e.valid()) out.add(e);
            } catch (Exception ignored) {
                // a damaged entry is skipped
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** Sets want / watched (NONE clears it) for a title, keeping its rating. */
    static void setStatus(Context ctx, Tmdb.Title t, int status) {
        Entry e = entryFor(ctx, t);
        e.status = status;
        save(ctx, e);
    }

    /** Your rating, 1 to 10 (0 clears it). Rating a title also marks it watched. */
    static void rate(Context ctx, Tmdb.Title t, int rating) {
        Entry e = entryFor(ctx, t);
        e.rating = Math.max(0, Math.min(10, rating));
        if (e.rating > 0) e.status = WATCHED;
        save(ctx, e);
    }

    private static Entry entryFor(Context ctx, Tmdb.Title t) {
        Entry e = get(ctx, t);
        if (e == null) e = new Entry();
        e.key = keyOf(t);
        e.type = t.type;
        e.title = t.title;
        e.year = t.year;
        e.poster = t.poster();
        e.language = t.language == null ? "" : t.language;
        e.imdb = t.rating;
        e.tomato = t.tomato;
        e.tmdbId = t.id;
        if (t.genreCodes != null && !t.genreCodes.isEmpty()) e.genres = new ArrayList<>(t.genreCodes);
        return e;
    }

    private static void save(Context ctx, Entry e) {
        e.time = System.currentTimeMillis();
        try {
            if (e.status == NONE && e.rating == 0) prefs(ctx).edit().remove(e.key).apply();
            else prefs(ctx).edit().putString(e.key, e.json().toString()).apply();
        } catch (Exception ignored) {
            // not saved
        }
    }

    /** Titles marked watched (or rated), by key. */
    static Set<String> watched(Context ctx) {
        Set<String> out = new HashSet<>();
        for (Entry e : all(ctx)) if (e.status == WATCHED) out.add(e.key);
        return out;
    }

    // ---- Random skips watched titles -------------------------------------------------------------

    static boolean skipWatched(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("random_skip_watched", true);
    }

    static void setSkipWatched(Context ctx, boolean on) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("random_skip_watched", on).apply();
    }

    // ---- export and import -----------------------------------------------------------------------

    /**
     * Where an old watched-list export (before full backups) was kept: TV Town's folder on a plugged-in pen drive (Android/data/com.nareshjagriya.tvtown/files),
     * else on the TV's own storage. No storage permission is needed for either.
     */
    static File exportFile(Context ctx) {
        File[] dirs = ctx.getExternalFilesDirs(null);
        File best = null;
        for (int i = 0; dirs != null && i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            if (best == null || i > 0) best = dirs[i];   // index 0 is the TV's own storage; later ones are drives
        }
        if (best == null) best = ctx.getFilesDir();
        return new File(best, FILE_NAME);
    }

    /** Reads the export file (pen drive first); newer changes win. Returns how many titles were taken, or -1. */
    static int importFrom(Context ctx) {
        File f = exportFile(ctx);
        if (!f.exists()) f = new File(ctx.getExternalFilesDir(null), FILE_NAME);
        if (!f.exists() || f.length() > 5_000_000) return -1;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            JSONArray a = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8)).optJSONArray("titles");
            int taken = 0;
            SharedPreferences.Editor ed = prefs(ctx).edit();
            for (int i = 0; a != null && i < a.length(); i++) {
                Entry e = Entry.from(a.optJSONObject(i));
                if (!e.valid() || (e.status == NONE && e.rating == 0)) continue;
                Entry mine = get(ctx, e.key);
                if (mine != null && mine.time >= e.time) continue;
                ed.putString(e.key, e.json().toString());
                taken++;
            }
            ed.apply();
            return taken;
        } catch (Exception e) {
            return -1;
        }
    }
}
