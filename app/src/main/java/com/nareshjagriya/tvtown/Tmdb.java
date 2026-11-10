package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Movie Database (TMDB): titles, artwork, trailers and where each title streams in India. Responses are
 * cached on disk (fresh for a few hours, usable for a week when offline). The key comes from
 * local.properties at build time; without one, everything here reports "not available" and the screens fall
 * back to the illustrated scenes.
 */
final class Tmdb {

    
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long FRESH = 3 * 3600_000L, STALE = 7 * 24 * 3600_000L, DAY = 24 * 3600_000L;
    private static final int TIMEOUT = 8000;
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_\\-]+\\.(jpg|png)");
    private static final Pattern YT_KEY = Pattern.compile("[A-Za-z0-9_\\-]{11}");
    private static final Pattern LANG = Pattern.compile("[a-z]{2}");

    /** Discover's language list: a name and TMDB's original-language code ("" = all languages). */
    static final String[][] LANGUAGES = {{"All", ""}, {"Hindi", "hi"}, {"English", "en"}, {"Korean", "ko"}, {"Japanese", "ja"},
            {"Chinese", "zh"}, {"Spanish", "es"}, {"Tamil", "ta"}, {"Telugu", "te"}, {"Malayalam", "ml"}, {"Marathi", "mr"},
            {"Bengali", "bn"}};
    static final String[][] GLOBAL_LANGUAGES = {{"All", ""}, {"English", "en"}, {"Spanish", "es"}, {"French", "fr"},
            {"Korean", "ko"}, {"Japanese", "ja"}, {"German", "de"}, {"Italian", "it"}, {"Portuguese", "pt"},
            {"Chinese", "zh"}, {"Hindi", "hi"}};

    /** Discover's language list for the content style. */
    static String[][] languages() {
        return Style.global() ? GLOBAL_LANGUAGES : LANGUAGES;
    }

    /** "Hindi" for "hi"; empty when the code isn't in either list. */
    static String languageName(String code) {
        for (String[][] list : new String[][][]{LANGUAGES, GLOBAL_LANGUAGES}) {
            for (String[] l : list) if (!l[1].isEmpty() && l[1].equals(code)) return l[0];
        }
        return "";
    }

    /** A movie or series. Details (runtime, genres, trailer) are filled in later by details(). */
    static final class Title {
        final int id;
        final String type;          // "movie" or "tv"
        final String title, overview, date;
        String language = "";       // original language, ISO 639-1
        final int year;
        final double popularity, rating;
        final String backdropPath, posterPath;
        Services.Service service;   // the service it was found on, when known
        // Set only on titles that came from JustWatch (Catalog's fallback):
        String jwId;                // JustWatch's id
        String jwBackdrop, jwPoster;   // JustWatch image paths
        String backdropUrl;         // a full picture URL (StreamRadar titles)
        volatile String logoUrl;    // the film's own title art, when found
        volatile boolean logoAsked, logoDone;
        volatile long logoAskedAt;
        java.util.Map<String, String> links;   // service key → the title's page in that app
        List<Services.Service> streams;        // where it streams in India
        String added;               // yyyy-MM-dd it arrived on its service (New this week)
        int tomato = -1;            // Rotten Tomatoes %, or -1 (JustWatch titles)
        boolean fresh, rentOrBuy;   // Certified Fresh; only to rent or buy in India
        int votes;                  // IMDb votes (JustWatch titles)
        String posterUrl;           // a full poster URL kept from before (your watched list, My Movies)
        List<String> genreCodes = new ArrayList<>();   // JustWatch genre codes, for filters
        volatile boolean detailed;
        volatile int runtime;       // minutes
        volatile String genres = "", trailer;
        volatile List<String> trailers = new ArrayList<>();   // YouTube ids, best first

        Title(int id, String type, String title, String overview, String date, double popularity, double rating,
              String backdropPath, String posterPath) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.overview = overview;
            this.date = date;
            this.year = date.length() >= 4 && Character.isDigit(date.charAt(0)) ? Integer.parseInt(date.substring(0, 4)) : 0;
            this.popularity = popularity;
            this.rating = rating;
            this.backdropPath = backdropPath;
            this.posterPath = posterPath;
        }

        String key() {
            return jwId != null ? "jw:" + jwId : type + ":" + id;
        }

        /** Backdrop for full-screen use (w780), or null. */
        @Nullable
        String backdrop() {
            if (backdropUrl != null) return sized("w780");
            return jwBackdrop != null ? JustWatch.image(jwBackdrop, "s1440") : image(backdropPath, "w780");
        }

        /** The sharpest backdrop, for the picture behind the whole of Home (w1280), or null. */
        @Nullable
        String backdropLarge() {
            if (backdropUrl != null) return sized("w1280");
            return jwBackdrop != null ? JustWatch.image(jwBackdrop, "s1440") : image(backdropPath, "w1280");
        }

        /** Smaller backdrop for cards (w300). */
        @Nullable
        String backdropCard() {
            if (backdropUrl != null) return sized("w300");
            return jwBackdrop != null ? JustWatch.image(jwBackdrop, "s640") : image(backdropPath, "w300");
        }

        /** backdropUrl at a size; only TMDB's addresses carry one. */
        private String sized(String size) {
            return backdropUrl.replaceFirst("(image\\.tmdb\\.org/t/p/)w\\d+/", "$1" + size + "/");
        }

        @Nullable
        String poster() {
            if (posterUrl != null) return posterUrl;
            return jwPoster != null ? JustWatch.image(jwPoster, "s332") : image(posterPath, "w300");
        }

        boolean isSeries() {
            return "tv".equals(type);
        }

        /** "Series · Drama, Thriller · 2024 · 2 h 10 min", with what is known so far. */
        String meta() {
            List<String> parts = new ArrayList<>();
            parts.add(isSeries() ? "Series" : "Movie");
            String lang = languageName(language);
            if (!lang.isEmpty()) parts.add(lang);
            if (!genres.isEmpty()) parts.add(genres);
            if (year > 0) parts.add(String.valueOf(year));
            if (runtime > 0) parts.add(runtime >= 60 ? (runtime / 60) + " h " + (runtime % 60) + " min" : runtime + " min");
            return String.join("  ·  ", parts);
        }
    }

    private Tmdb() {
    }

    static boolean available() {
        return !Keys.tmdb().isEmpty();
    }

    @Nullable
    static String image(@Nullable String path, String size) {
        return path != null && PATH.matcher(path).matches() ? IMG + size + path : null;
    }

    // ---- requests --------------------------------------------------------------------------------

    /** A v3 API key goes in the URL; a longer v4 read token goes in the Authorization header. */
    private static JSONObject call(Context ctx, String path, Map<String, String> query, long fresh) throws IOException {
        return call(ctx, path, query, fresh, null);
    }

    /** saveAs: a name to keep the answer under when the address changes daily (see Net.cached), or null. */
    private static JSONObject call(Context ctx, String path, Map<String, String> query, long fresh, @Nullable String saveAs) throws IOException {
        String key = Keys.tmdb();
        if (key.isEmpty()) throw new Net.Failure("No TMDB key", 0);
        Uri.Builder b = Uri.parse(API + path).buildUpon();
        for (Map.Entry<String, String> q : query.entrySet()) b.appendQueryParameter(q.getKey(), q.getValue());
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        boolean bearer = key.length() > 40;
        if (bearer) headers.put("Authorization", "Bearer " + key);
        else b.appendQueryParameter("api_key", key);
        try {
            String url = b.build().toString();
            return new JSONObject(Net.cached(ctx, saveAs != null ? saveAs : url, url, headers, fresh, STALE, TIMEOUT));
        } catch (org.json.JSONException e) {
            throw new Net.Failure("Bad TMDB response", 0);
        }
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        m.put("language", Lang.tmdb());
        return m;
    }

    /** Parses a results array; items that are not well-formed movies or series are skipped. */
    static List<Title> parse(JSONArray results, @Nullable String forcedType) {
        List<Title> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String type = forcedType != null ? forcedType : o.optString("media_type");
            if (!"movie".equals(type) && !"tv".equals(type)) continue;
            int id = o.optInt("id");
            String name = o.optString("movie".equals(type) ? "title" : "name", "").trim();
            if (id <= 0 || name.isEmpty()) continue;
            String date = o.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) date = "";
            Title t = new Title(id, type, name, o.optString("overview", "").trim(), date,
                    o.optDouble("popularity", 0), o.optDouble("vote_average", 0),
                    nullIfBad(o.optString("backdrop_path", null)), nullIfBad(o.optString("poster_path", null)));
            String lang = o.optString("original_language", "");
            if (LANG.matcher(lang).matches()) t.language = lang;
            out.add(t);
        }
        return out;
    }

    @Nullable
    private static String nullIfBad(@Nullable String path) {
        return path != null && PATH.matcher(path).matches() ? path : null;
    }

    // ---- providers -------------------------------------------------------------------------------

    private static Map<String, String> providerIds;
    private static String providerCountry;   // the country providerIds are for

    /** TMDB provider ids for a service in India, joined with "|" (for with_watch_providers), or null. */
    @Nullable
    static synchronized String providerIds(Context ctx, Services.Service s) throws IOException {
        if (providerIds == null || !Style.country().equals(providerCountry)) {
            providerCountry = Style.country();
            Map<String, Set<Integer>> found = new HashMap<>();
            for (String type : new String[]{"movie", "tv"}) {
                JSONArray r = call(ctx, "/watch/providers/" + type, q("watch_region", Style.country()), 7 * DAY).optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject p = r.optJSONObject(i);
                    if (p == null) continue;
                    for (Services.Service x : Services.STREAMING) {
                        if (Services.matches(x, p.optString("provider_name"))) {
                            Set<Integer> set = found.get(x.key);
                            if (set == null) found.put(x.key, set = new HashSet<>());
                            set.add(p.optInt("provider_id"));
                        }
                    }
                }
            }
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, Set<Integer>> e : found.entrySet()) m.put(e.getKey(), joinIds(e.getValue()));
            providerIds = m;
        }
        return providerIds.get(s.key);
    }

    /** Services in India a title streams on (subscription, free or with ads), in TV Town's order. */
    private static String joinIds(Set<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (Integer i : ids) b.append(b.length() > 0 ? "|" : "").append(i);
        return b.toString();
    }

    static List<Services.Service> whereToWatch(Context ctx, Title t) throws IOException {
        JSONObject r = call(ctx, "/" + t.type + "/" + t.id + "/watch/providers", q(), DAY).optJSONObject("results");
        JSONObject in = r != null ? r.optJSONObject(Style.country()) : null;
        List<Services.Service> out = new ArrayList<>();
        if (in == null) return out;
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray a = in.optJSONArray(kind);
            for (int i = 0; a != null && i < a.length(); i++) {
                String name = a.optJSONObject(i) != null ? a.optJSONObject(i).optString("provider_name") : "";
                for (Services.Service s : Services.STREAMING) if (Services.matches(s, name) && !out.contains(s)) out.add(s);
            }
        }
        Collections.sort(out, (x, y) -> indexOf(x) - indexOf(y));
        return out;
    }

    private static int indexOf(Services.Service s) {
        for (int i = 0; i < Services.STREAMING.length; i++) if (Services.STREAMING[i] == s) return i;
        return 99;
    }

    // ---- home ------------------------------------------------------------------------------------

    /** The most popular title on a service in India right now (movie or series). */
    @Nullable
    static Title topOn(Context ctx, Services.Service s) throws IOException {
        String ids = providerIds(ctx, s);
        if (ids == null) return null;
        Title best = null;
        for (String type : new String[]{"movie", "tv"}) {
            List<Title> r = parse(call(ctx, "/discover/" + type, q("watch_region", Style.country(), "with_watch_providers", ids,
                    "with_watch_monetization_types", "flatrate|free|ads", "sort_by", "popularity.desc", "page", "1"), FRESH)
                    .optJSONArray("results"), type);
            for (Title t : r) {
                if (t.backdropPath == null) continue;
                if (best == null || t.popularity > best.popularity) best = t;
                break;
            }
        }
        if (best != null) best.service = s;
        return best;
    }

    /** Today's most popular movie or series overall, for the Discover card. */
    @Nullable
    static Title trending(Context ctx) throws IOException {
        for (Title t : parse(call(ctx, "/trending/all/day", q(), FRESH).optJSONArray("results"), null)) {
            if (t.backdropPath != null) return t;
        }
        return null;
    }

    /** Titles released in the last `days` days on the given services in India, most popular first, at most 20. */
    static List<Title> newOn(Context ctx, List<Services.Service> services, int days) throws IOException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar c = Calendar.getInstance();
        String today = f.format(c.getTime());
        c.add(Calendar.DAY_OF_YEAR, -days);
        String since = f.format(c.getTime());
        // Every app's films and series are asked for at once (four at a time), not one after another.
        final boolean savedOnly = Net.savedOnly();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<Services.Service> asked = new ArrayList<>();
        List<java.util.concurrent.Future<List<Title>>> jobs = new ArrayList<>();
        try {
            for (Services.Service s : services) {
                String ids = providerIds(ctx, s);
                if (ids == null) continue;
                for (String type : new String[]{"movie", "tv"}) {
                    String from = "movie".equals(type) ? "primary_release_date.gte" : "first_air_date.gte";
                    String to = "movie".equals(type) ? "primary_release_date.lte" : "first_air_date.lte";
                    asked.add(s);
                    jobs.add(pool.submit(() -> {
                        Net.savedOnly(savedOnly);
                        return parse(call(ctx, "/discover/" + type, q("watch_region", Style.country(), "with_watch_providers", ids,
                                "with_watch_monetization_types", "flatrate|free|ads", "sort_by", "popularity.desc",
                                from, since, to, today, "page", "1"), FRESH,
                                "tmdb-new/" + type + "/" + ids + "/" + Style.country() + "/" + Lang.tmdb() + "/" + days).optJSONArray("results"), type);
                    }));
                }
            }
        } finally {
            pool.shutdown();
        }
        Map<String, Title> merged = new HashMap<>();
        IOException failed = null;
        for (int i = 0; i < jobs.size(); i++) {
            try {
                for (Title t : jobs.get(i).get()) {
                    if (t.backdropPath == null || merged.containsKey(t.key())) continue;
                    t.service = asked.get(i);
                    merged.put(t.key(), t);
                }
            } catch (java.util.concurrent.ExecutionException e) {
                failed = e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        if (merged.isEmpty() && failed != null) throw failed;
        List<Title> out = new ArrayList<>(merged.values());
        Collections.sort(out, (a, b) -> Double.compare(b.popularity, a.popularity));
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    /** Titles TMDB suggests after this one ("because you watched"), best first. type: "movie" or "tv". */
    static List<Title> recommendations(Context ctx, String type, int id) throws IOException {
        if (!"movie".equals(type) && !"tv".equals(type) || id <= 0) return new ArrayList<>();
        return parse(call(ctx, "/" + type + "/" + id + "/recommendations", q("page", "1"), DAY).optJSONArray("results"), type);
    }

    /** The IMDb number ("tt0111161") of a film or series, or null. Kept a month: it never changes. */
    @Nullable
    static String imdbId(Context ctx, String type, int id) throws IOException {
        String v = call(ctx, "/" + type + "/" + id + "/external_ids", q(), 30 * DAY).optString("imdb_id", "");
        return v.matches("tt\\d{5,10}") ? v : null;
    }

    /** Up to five leading actors, "A, B, C". */
    static String cast(Context ctx, Title t) throws IOException {
        JSONArray a = call(ctx, "/" + t.type + "/" + t.id + "/credits", q(), DAY).optJSONArray("cast");
        List<String> names = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && names.size() < 5; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("name").isEmpty()) names.add(o.optString("name"));
        }
        return android.text.TextUtils.join(", ", names);
    }

    /** The title's own logo (English first, then Hindi, then any without words of a language), or null. */
    @Nullable
    static String logo(Context ctx, Title t) throws IOException {
        JSONArray logos = call(ctx, "/" + t.type + "/" + t.id + "/images", q("include_image_language", "en,hi,null"), FRESH).optJSONArray("logos");
        String best = null;
        int rank = 9;
        for (int i = 0; logos != null && i < logos.length(); i++) {
            JSONObject o = logos.optJSONObject(i);
            String path = o == null ? "" : o.optString("file_path");
            if (path.isEmpty() || path.endsWith(".svg")) continue;
            String lang = o.optString("iso_639_1");
            int r = "en".equals(lang) ? 0 : "hi".equals(lang) ? 1 : 2;
            if (r < rank) {
                rank = r;
                best = path;
            }
        }
        return image(best, "w500");
    }

    /** A title's wide picture and its story, for Home's shelf (the same request as details, so it's cached). */
    static String[] pictureAndStory(Context ctx, String type, int id) throws IOException {
        JSONObject o = call(ctx, "/" + type + "/" + id, q("append_to_response", "videos"), DAY);
        String path = o.optString("backdrop_path", "");
        return new String[]{path.isEmpty() || "null".equals(path) ? null : image(path, "w1280"), o.optString("overview", "")};
    }

    /** Runtime, genre names and the YouTube trailer, in one request. Safe to call more than once. */
    static void details(Context ctx, Title t) throws IOException {
        if (t.detailed) return;
        JSONObject o = call(ctx, "/" + t.type + "/" + t.id, q("append_to_response", "videos"), DAY);
        int runtime = o.optInt("runtime");
        JSONArray ert = o.optJSONArray("episode_run_time");
        if (runtime <= 0 && ert != null && ert.length() > 0) runtime = ert.optInt(0);
        t.runtime = Math.max(0, Math.min(1000, runtime));
        JSONArray g = o.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && names.size() < 2; i++) {
            String n = g.optJSONObject(i) != null ? g.optJSONObject(i).optString("name") : "";
            if (!n.isEmpty()) names.add(n.replace(" & ", " and "));
        }
        t.genres = String.join(", ", names);
        t.trailers = trailerKeys(o.optJSONObject("videos"));
        t.trailer = t.trailers.isEmpty() ? null : t.trailers.get(0);
        t.detailed = true;
    }

    /**
     * A title's original language (ISO 639-1), remembered for good once known; "" when unknown. JustWatch has no
     * language, so Must-Watch asks here for Indian films. Runs a network request the first time.
     */
    static String originalLanguage(Context ctx, String type, int id) {
        if (id <= 0 || !available()) return "";
        android.content.SharedPreferences p = ctx.getSharedPreferences("tmdb_lang", Context.MODE_PRIVATE);
        String key = type + ":" + id, known = p.getString(key, null);
        if (known != null) return known;
        try {
            String lang = call(ctx, "/" + type + "/" + id, q(), STALE).optString("original_language", "");
            if (!LANG.matcher(lang).matches()) lang = "";
            p.edit().putString(key, lang).apply();
            return lang;
        } catch (IOException e) {
            return "";
        }
    }

    /** The best YouTube trailer key: official trailers first, then any trailer, then a teaser. */
    @Nullable
    static String trailerKey(@Nullable JSONObject videos) {
        List<String> keys = trailerKeys(videos);
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** YouTube trailers and teasers, official trailers first: the first one that still plays is used. */
    static List<String> trailerKeys(@Nullable JSONObject videos) {
        JSONArray r = videos != null ? videos.optJSONArray("results") : null;
        List<String[]> scored = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject v = r.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site"))) continue;
            String key = v.optString("key");
            if (!YT_KEY.matcher(key).matches()) continue;
            String type = v.optString("type");
            int score = ("Trailer".equals(type) ? 4 : "Teaser".equals(type) ? 2 : 0) + (v.optBoolean("official") ? 1 : 0);
            if (score >= 2) scored.add(new String[]{String.valueOf(score), key});
        }
        // Stable sort: equal scores keep TMDB's order.
        Collections.sort(scored, (a, b) -> Integer.parseInt(b[0]) - Integer.parseInt(a[0]));
        List<String> out = new ArrayList<>();
        for (String[] x : scored) if (!out.contains(x[1]) && out.size() < 5) out.add(x[1]);
        return out;
    }

    // ---- Discover --------------------------------------------------------------------------------

    /** A Discover category as TMDB filters. Movie and series genre ids differ; null skips that type. */
    static final class Category {
        final String section, name;
        String movieGenres, tvGenres = "";   // "" = all genres; null = this type is not in the category
        String language;                     // ISO 639-1, e.g. "hi"
        String sort = "popularity.desc";
        int minVotes, sinceDays, untilYear, fromYear;
        String emoji;             // shown before the name in Discover's list
        String tvKeywords;        // TMDB keyword ids for series (TV has no romance genre)
        int maxVotes;             // at most this many votes (hidden gems)
        boolean random;           // Random picks: results are shuffled
        int pageOffset;           // Random picks start somewhere other than page 1
        double minRating;
        String keywords;          // TMDB keyword ids for both types ("|" = any of)
        int maxRuntime, minRuntime;   // minutes (an episode's, for series)
        String status;            // series: TMDB status ids, e.g. "3" = ended
        String country;           // origin country, e.g. "IN"
        String withoutGenres;     // TMDB genre ids to leave out

        Category(String section, String name) {
            this.section = section;
            this.name = name;
            this.movieGenres = "";
        }

        Category genres(String movie, String tv) { movieGenres = movie; tvGenres = tv; return this; }
        Category emoji(String e) { emoji = e; return this; }
        Category lang(String l) { language = l; return this; }
        Category sort(String s) { sort = s; return this; }
        Category rated(double min, int votes) { minRating = min; minVotes = votes; return this; }
        Category since(int days) { sinceDays = days; return this; }
        Category years(int from, int to) { fromYear = from; untilYear = to; return this; }
        Category keywords(String k) { keywords = k; return this; }
        Category runtime(int min, int max) { minRuntime = min; maxRuntime = max; return this; }
        Category votes(int max) { maxVotes = max; return this; }
        Category status(String s) { status = s; return this; }
        Category country(String c) { country = c; return this; }
        Category without(String g) { withoutGenres = g; return this; }
    }

    /** Categories with TMDB genre ids (movie ids first, series ids second; "," means all of, "|" any of). */
    static List<Category> categories() {
        List<Category> l = new ArrayList<>();
        String s = "Discover";
        l.add(new Category(s, "Popular").emoji(""));
        l.add(new Category(s, "New releases").emoji("").since(60));
        l.add(new Category(s, "Top rated").emoji("").sort("vote_average.desc").rated(7.5, 800));
        l.add(new Category(s, "Hidden gems").emoji("").sort("vote_average.desc").rated(7.2, 60));
        s = "Genres";
        l.add(new Category(s, "Action").emoji("").genres("28", "10759"));
        l.add(new Category(s, "Adventure").emoji("").genres("12", "10759"));
        l.add(new Category(s, "Animation").emoji("").genres("16", "16"));
        l.add(new Category(s, "Comedy").emoji("").genres("35", "35"));
        l.add(new Category(s, "Crime").emoji("").genres("80", "80"));
        l.add(new Category(s, "Documentary").emoji("").genres("99", "99"));
        l.add(new Category(s, "Drama").emoji("").genres("18", "18"));
        l.add(new Category(s, "Family").emoji("").genres("10751", "10751"));
        l.add(new Category(s, "Fantasy").emoji("").genres("14", "10765"));
        l.add(new Category(s, "History").emoji("").genres("36", null));
        l.add(new Category(s, "Horror").emoji("").genres("27", null));
        l.add(new Category(s, "Music").emoji("").genres("10402", null));
        l.add(new Category(s, "Mystery").emoji("").genres("9648", "9648"));
        l.add(new Category(s, "Romance").emoji("").genres("10749", null));
        l.add(new Category(s, "Sci-Fi").emoji("").genres("878", "10765"));
        l.add(new Category(s, "Thriller").emoji("").genres("53", null));
        l.add(new Category(s, "War").emoji("").genres("10752", "10768"));
        l.add(new Category(s, "Reality").emoji("").genres(null, "10764"));
        l.add(new Category(s, "Kids").emoji("").genres("10751,16", "10762"));
        s = "Mixes";
        l.add(new Category(s, "Crime thriller").emoji("").genres("80,53", "80,9648"));
        l.add(new Category(s, "Romantic comedy").emoji("").genres("10749,35", null));
        l.add(new Category(s, "Action comedy").emoji("").genres("28,35", "10759,35"));
        l.add(new Category(s, "Sci-fi thriller").emoji("").genres("878,53", "10765,9648"));
        l.add(new Category(s, "Horror thriller").emoji("").genres("27,53", null));
        l.add(new Category(s, "Crime drama").emoji("").genres("80,18", "80,18"));
        l.add(new Category(s, "Family animation").emoji("").genres("10751,16", "10751,16"));
        l.add(new Category(s, "War drama").emoji("").genres("10752,18", "10768,18"));
        s = "Decades";
        l.add(new Category(s, "2010s").emoji("").years(2010, 2019));
        l.add(new Category(s, "2000s").emoji("").years(2000, 2009));
        l.add(new Category(s, "1990s").emoji("").years(1990, 1999));
        l.add(new Category(s, "Classics").emoji("").years(1940, 1989).rated(7, 300));
        return l;
    }

    /** One TMDB page (20 titles) of a category on one service; also returns the total count for paging. */
    /**
     * Random picks: a random sort (popular, well rated, or recent) from a random point in the list, shuffled. Every
     * call gives a new mix; only titles with some votes, so the picks are real films and series.
     */
    static Category randomPicks(java.util.Random r) {
        Category c = new Category("Random", "Random picks");
        String[] sorts = {"popularity.desc", "vote_average.desc", "primary_release_date.desc"};
        c.sort = sorts[r.nextInt(sorts.length)];
        c.minVotes = c.sort.startsWith("vote") ? 300 : 40;
        c.random = true;
        c.pageOffset = r.nextInt(8);
        return c;
    }

    static final class Page {
        final List<Title> titles;
        final int totalResults;
        final boolean more;        // another page may follow

        Page(List<Title> titles, int totalResults) {
            this(titles, totalResults, titles.size() >= 20);
        }

        Page(List<Title> titles, int totalResults, boolean more) {
            this.titles = titles;
            this.totalResults = totalResults;
            this.more = more;
        }
    }

    @Nullable
    /** One page of a category on one service (null: on any); language is an original-language code, or "" for all. */
    static Page discover(Context ctx, Category c, Services.Service s, String type, int page, String language) throws IOException {
        String genres = "movie".equals(type) ? c.movieGenres : c.tvGenres;
        if (genres == null) return new Page(new ArrayList<>(), 0);
        String ids = s == null ? null : providerIds(ctx, s);   // no service: anywhere (the Random cards' pictures)
        if (s != null && ids == null) return null;
        String sort = "tv".equals(type) ? c.sort.replace("primary_release_date", "first_air_date") : c.sort;
        Map<String, String> m = q("sort_by", sort, "page", String.valueOf(Math.min(500, page + c.pageOffset)),
                "include_adult", "false");
        if (ids != null) {
            m.put("watch_region", Style.country());
            m.put("with_watch_providers", ids);
            m.put("with_watch_monetization_types", "flatrate|free|ads");
        }
        if (!genres.isEmpty()) m.put("with_genres", genres);
        if (language != null && LANG.matcher(language).matches()) m.put("with_original_language", language);
        else if (c.language != null && c.language.matches("[a-z]{2}(\\|[a-z]{2})*")) m.put("with_original_language", c.language);
        if (c.minVotes > 0) m.put("vote_count.gte", String.valueOf(c.minVotes));
        if (c.maxVotes > 0) m.put("vote_count.lte", String.valueOf(c.maxVotes));
        String keywords = "tv".equals(type) && c.tvKeywords != null ? c.tvKeywords : c.keywords;
        if (keywords != null && keywords.matches("[0-9|,]+")) m.put("with_keywords", keywords);
        if (c.maxRuntime > 0) m.put("with_runtime.lte", String.valueOf(c.maxRuntime));
        if (c.minRuntime > 0) m.put("with_runtime.gte", String.valueOf(c.minRuntime));
        if ("tv".equals(type) && c.status != null && c.status.matches("[0-9|]+")) m.put("with_status", c.status);
        if (c.country != null && c.country.matches("[A-Z]{2}")) m.put("with_origin_country", c.country);
        if (c.withoutGenres != null && c.withoutGenres.matches("[0-9,]+")) m.put("without_genres", c.withoutGenres);
        if (c.minRating > 0) m.put("vote_average.gte", String.valueOf(c.minRating));
        String dateKey = "movie".equals(type) ? "primary_release_date" : "first_air_date";
        if (c.sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -c.sinceDays);
            m.put(dateKey + ".gte", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime()));
        }
        if (c.fromYear > 0) {
            m.put(dateKey + ".gte", c.fromYear + "-01-01");
            m.put(dateKey + ".lte", c.untilYear + "-12-31");
        }
        if (c.sort.startsWith("primary_release_date")) {   // newest first, but nothing still to come
            m.put(dateKey + ".lte", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime()));
        }
        JSONObject o = call(ctx, "/discover/" + type, m, FRESH);
        List<Title> titles = parse(o.optJSONArray("results"), type);
        for (Title t : titles) t.service = s;
        return new Page(titles, Math.max(0, Math.min(10_000, o.optInt("total_results"))));
    }

    /**
     * What My Movies' tags and language need about a title: its keywords, genre ids, India's certificate and
     * original language. One request (kept for a week).
     */
    static Tags.Facts facts(Context ctx, String type, int id, String[] language) throws IOException {
        boolean tv = "tv".equals(type);
        JSONObject o = call(ctx, "/" + type + "/" + id, q("append_to_response", tv ? "keywords,content_ratings" : "keywords,release_dates"), STALE);
        Tags.Facts f = new Tags.Facts();
        JSONObject kw = o.optJSONObject("keywords");
        JSONArray list = kw == null ? null : kw.optJSONArray(tv ? "results" : "keywords");
        for (int i = 0; list != null && i < list.length(); i++) {
            String n = list.optJSONObject(i) != null ? list.optJSONObject(i).optString("name") : "";
            if (!n.isEmpty()) f.keywords.add(n.toLowerCase(Locale.ROOT));
        }
        JSONArray g = o.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) if (g.optJSONObject(i) != null) f.genres.add(g.optJSONObject(i).optInt("id"));
        JSONObject ratings = o.optJSONObject(tv ? "content_ratings" : "release_dates");
        JSONArray countries = ratings == null ? null : ratings.optJSONArray("results");
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c == null || !Style.country().equals(c.optString("iso_3166_1"))) continue;
            if (tv) f.certificate = c.optString("rating", "");
            else {
                JSONArray dates = c.optJSONArray("release_dates");
                for (int d = 0; dates != null && d < dates.length() && f.certificate.isEmpty(); d++) {
                    f.certificate = dates.optJSONObject(d) != null ? dates.optJSONObject(d).optString("certification", "") : "";
                }
            }
        }
        String lang = o.optString("original_language", "");
        if (language != null && language.length > 0) language[0] = LANG.matcher(lang).matches() ? lang : "";
        return f;
    }

    /** Search one type ("movie" or "tv"), optionally in a year (0 for any): My Movies' file matching. */
    static List<Title> searchType(Context ctx, String type, String query, int year) throws IOException {
        String clean = query.trim();
        if (clean.isEmpty() || clean.length() > 100 || !("movie".equals(type) || "tv".equals(type))) return new ArrayList<>();
        Map<String, String> m = q("query", clean, "include_adult", "false");
        if (year > 1800) m.put("movie".equals(type) ? "year" : "first_air_date_year", String.valueOf(year));
        return parse(call(ctx, "/search/" + type, m, STALE).optJSONArray("results"), type);
    }

    /** Typed search across movies and series (people and others dropped). */
    static Page search(Context ctx, String query, int page) throws IOException {
        String clean = query.trim();
        if (clean.isEmpty() || clean.length() > 100) return new Page(new ArrayList<>(), 0);
        JSONObject o = call(ctx, "/search/multi", q("query", clean, "page", String.valueOf(page), "include_adult", "false"), FRESH);
        return new Page(parse(o.optJSONArray("results"), null), Math.max(0, Math.min(10_000, o.optInt("total_results"))));
    }
}
