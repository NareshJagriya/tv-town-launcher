package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * StreamRadar India (streamradar.bricksgenius.com), a free list with no key: each Indian app's Top 10 and what has just
 * come out. Catalog asks it, in India, while there is no TMDB key, so a new TV has real picks straight away. Its
 * "streams" are links to the title's page in the app, never videos.
 * All of this is network: call off the main thread.
 */
final class StreamRadar {

    private static final String API = "https://streamradar.bricksgenius.com";
    private static final long LIST_FRESH = 3 * 3600_000L, TITLE_FRESH = 24 * 3600_000L, STALE = 7 * 24 * 3600_000L;
    private static final int TIMEOUT = 15_000, MAX = 20, LOOK_AT = 40;

    private StreamRadar() {
    }

    /** The lists only cover Indian apps. */
    static boolean usable() {
        return "IN".equals(Style.country());
    }

    /** Should Home's picks come from here? In India: always without a TMDB key; with one, when chosen in Customize. */
    static boolean chosen(Context ctx) {
        return usable() && (!Tmdb.available()
                || ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("picks_streamradar", false));
    }

    static void setChosen(Context ctx, boolean on) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("picks_streamradar", on).apply();
    }

    /** New on your apps in the last `days` days, newest first; only titles it can place on one of them. */
    static List<Tmdb.Title> newOn(Context ctx, List<Services.Service> mine, int days) throws IOException {
        if (mine.isEmpty()) return new ArrayList<>();
        JSONArray films = list(ctx, "movie", "streamradar-justin-movies"), shows = list(ctx, "series", "streamradar-justin-shows");
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
        List<JSONObject> wanted = new ArrayList<>();
        for (int i = 0; i < films.length() || i < shows.length(); i++) {
            for (JSONArray a : new JSONArray[]{films, shows}) {   // films and series in turn
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optString("releaseInfo").compareTo(since) >= 0) wanted.add(o);
            }
        }
        Collections.sort(wanted, (x, y) -> y.optString("releaseInfo").compareTo(x.optString("releaseInfo")));   // the lists aren't in date order
        if (wanted.size() > LOOK_AT) wanted = new ArrayList<>(wanted.subList(0, LOOK_AT));
        ExecutorService pool = Executors.newFixedThreadPool(4);   // a small TV: a few at a time
        try {
            List<Future<Tmdb.Title>> jobs = new ArrayList<>();
            for (JSONObject o : wanted) jobs.add(pool.submit(() -> title(ctx, o, mine, null)));
            List<Tmdb.Title> out = new ArrayList<>();
            for (Future<Tmdb.Title> f : jobs) {
                try {
                    Tmdb.Title t = f.get();
                    if (t != null && out.size() < MAX) {
                        t.added = t.date;
                        out.add(t);
                    }
                } catch (Exception ignored) {
                    // one title that won't load is left out
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The title at the top of an app's Top 10 films, or null when StreamRadar has no list for that app. */
    @Nullable
    static Tmdb.Title topOn(Context ctx, Services.Service s) throws IOException {
        String app = appId(s);
        if (app == null) return null;
        JSONArray films = list(ctx, "movie", "streamradar-" + app + "-top10-movies");
        List<Services.Service> only = Collections.singletonList(s);
        for (int i = 0; i < Math.min(5, films.length()); i++) {
            JSONObject o = films.optJSONObject(i);
            Tmdb.Title t = o == null ? null : title(ctx, o, only, s);
            if (t != null) return t;
        }
        return null;
    }

    /** The first Top 10 title on the apps on this TV. */
    @Nullable
    static Tmdb.Title trending(Context ctx) throws IOException {
        for (Services.Service s : Services.installed(ctx)) {
            Tmdb.Title t = topOn(ctx, s);
            if (t != null) return t;
        }
        return null;
    }

    /** StreamRadar's name for an app in its list ids, or null. */
    @Nullable
    private static String appId(Services.Service s) {
        if (s == Services.HOTSTAR) return "jiohotstar";
        if (s == Services.NETFLIX) return "netflix";
        if (s == Services.PRIME) return "prime";
        if (s == Services.ZEE5) return "zee5";
        if (s == Services.SONYLIV) return "sony-liv";
        if (s == Services.APPLE) return "apple-tv-plus";
        return null;
    }

    /** The app a StreamRadar link belongs to ("Hotstar", "Apple TV+"), or null. */
    @Nullable
    static Services.Service serviceNamed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("hotstar")) return Services.HOTSTAR;
        if (n.contains("netflix")) return Services.NETFLIX;
        if (n.contains("prime")) return Services.PRIME;
        if (n.contains("zee5")) return Services.ZEE5;
        if (n.contains("sony")) return Services.SONYLIV;
        if (n.contains("apple")) return Services.APPLE;
        return null;
    }

    private static JSONArray list(Context ctx, String type, String id) throws IOException {
        try {
            return new JSONObject(Net.cached(ctx, API + "/catalog/" + type + "/" + id + ".json", null, LIST_FRESH, STALE, TIMEOUT))
                    .getJSONArray("metas");
        } catch (org.json.JSONException e) {
            throw new IOException("StreamRadar: " + e.getMessage(), e);
        }
    }

    /**
     * A list entry as a full title: where it streams, its story, length and trailer, and a wide picture. Null when none
     * of `mine` has it, or there is no picture to show. `on` is the app whose Top 10 it came from, which counts even
     * when StreamRadar has no link to its page there (the app itself opens).
     */
    @Nullable
    private static Tmdb.Title title(Context ctx, JSONObject o, List<Services.Service> mine, @Nullable Services.Service on)
            throws IOException {
        String id = o.optString("id"), srType = o.optString("type");
        if (id.isEmpty() || !("movie".equals(srType) || "series".equals(srType))) return null;
        Map<String, String> links = new HashMap<>();
        List<Services.Service> streams = new ArrayList<>();
        try {
            JSONArray s = new JSONObject(Net.cached(ctx, API + "/stream/" + srType + "/" + id + ".json", null, TITLE_FRESH, STALE, TIMEOUT))
                    .optJSONArray("streams");
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject x = s.getJSONObject(i);
                Services.Service where = serviceNamed(x.optString("name"));
                String url = x.optString("externalUrl");
                if (where == null || url.isEmpty() || links.containsKey(where.key)) continue;
                links.put(where.key, url);
                streams.add(where);
            }
        } catch (org.json.JSONException ignored) {
            // no links: only a Top 10 entry can still be placed
        }
        Services.Service service = on;
        if (service == null) for (Services.Service s : streams) if (mine.contains(s)) { service = s; break; }
        if (service == null) return null;
        if (!streams.contains(service)) streams.add(0, service);

        JSONObject meta = new JSONObject();
        try {
            JSONObject m = new JSONObject(Net.cached(ctx, API + "/meta/" + srType + "/" + id + ".json", null, TITLE_FRESH, STALE, TIMEOUT))
                    .optJSONObject("meta");
            if (m != null) meta = m;
        } catch (IOException | org.json.JSONException ignored) {
            // the list's own name and pictures are enough
        }
        String backdrop = text(o, "background", text(meta, "background", null));
        if (backdrop == null) return null;
        String name = text(o, "name", text(meta, "name", null));
        if (name == null) return null;

        String date = text(o, "releaseInfo", "");
        if (date.length() < 4) {
            String released = text(meta, "released", text(meta, "year", ""));
            date = released.length() >= 10 ? released.substring(0, 10) : released;
        }
        Tmdb.Title t = new Tmdb.Title(meta.optInt("moviedb_id", 0), "series".equals(srType) ? "tv" : "movie", name.trim(),
                text(meta, "description", ""), date, 0, parse(text(o, "imdbRating", text(meta, "imdbRating", "0"))), null, null);
        t.jwId = "sr:" + id;   // not a JustWatch id, but the same job: a stable key without a TMDB id
        t.backdropUrl = backdrop;
        t.logoUrl = text(meta, "logo", null);
        t.posterUrl = text(o, "poster", text(meta, "poster", null));
        t.links = links;
        t.streams = streams;
        t.service = service;
        t.genres = genres(meta);
        String runtime = text(meta, "runtime", "").replaceAll("\\D", "");
        if (!runtime.isEmpty() && runtime.length() < 5) t.runtime = Integer.parseInt(runtime);
        JSONArray trailers = meta.optJSONArray("trailers");
        if (trailers != null && trailers.length() > 0) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < trailers.length(); i++) {
                JSONObject x = trailers.optJSONObject(i);
                String v = x == null ? "" : x.optString("source");
                if (!v.isEmpty()) ids.add(v);
            }
            if (!ids.isEmpty()) {
                t.trailer = ids.get(0);
                t.trailers = ids;
            }
        }
        t.detailed = true;
        return t;
    }

    /** Up to two genres, "Action, Drama" (the lists use either "genre" or "genres", some in capitals). */
    private static String genres(JSONObject meta) {
        JSONArray g = meta.optJSONArray("genre");
        if (g == null) g = meta.optJSONArray("genres");
        List<String> out = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && out.size() < 2; i++) {
            String s = g.optString(i).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1));
        }
        return String.join(", ", out);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A string field, or the fallback when it is missing or empty. */
    private static String text(JSONObject o, String name, @Nullable String fallback) {
        String s = o.optString(name, "");
        return s.isEmpty() || "null".equals(s) ? fallback : s;
    }
}
