package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where Home and Discover get their titles: TMDB first, and JustWatch when TMDB has no key, rejects the key, or does
 * not answer. Home's picks in India start at StreamRadar instead when there is no TMDB key or Customize says so. All
 * give the same Tmdb.Title objects, so the screens do not care which one answered.
 * JustWatch titles also carry their direct page in each app, which DeepLinks uses before asking Wikidata.
 */
final class Catalog {

    private Catalog() {
    }

    /** Set when TMDB refuses the key (401): no point asking again until the app restarts. */
    private static volatile boolean tmdbRefused;

    static boolean tmdb() {
        return Tmdb.available() && !tmdbRefused;
    }

    private static void tmdbFailed(IOException e) {
        if (e instanceof Net.Failure && ((Net.Failure) e).status == 401) tmdbRefused = true;
    }

    // ---- the calls the screens make --------------------------------------------------------------

    /** Trending this week across the apps (Home's Discover card). */
    @Nullable
    static Tmdb.Title trending(Context ctx) throws IOException {
        if (StreamRadar.chosen(ctx)) {
            try {
                Tmdb.Title t = StreamRadar.trending(ctx);
                if (t != null) return t;
            } catch (IOException ignored) {
                // TMDB or JustWatch next
            }
        }
        if (tmdb()) {
            try {
                Tmdb.Title t = Tmdb.trending(ctx);
                if (t != null) return t;
            } catch (IOException e) {
                tmdbFailed(e);
            }
        }
        return first(jw(() -> JustWatch.browse(sorted("TRENDING"), codes(Services.streaming()), null, 0, 10).titles), null);
    }

    /** The most popular title on one app (Home's app cards). */
    @Nullable
    static Tmdb.Title topOn(Context ctx, Services.Service s) throws IOException {
        if (StreamRadar.chosen(ctx)) {
            try {
                Tmdb.Title t = StreamRadar.topOn(ctx, s);
                if (t != null) return t;
            } catch (IOException ignored) {
                // TMDB or JustWatch next
            }
        }
        if (tmdb()) {
            try {
                Tmdb.Title t = Tmdb.topOn(ctx, s);
                if (t != null) return t;
            } catch (IOException e) {
                tmdbFailed(e);
            }
        }
        return first(jw(() -> JustWatch.browse(sorted("POPULAR"), codes(Collections.singletonList(s)), null, 0, 10).titles), s);
    }

    /**
     * New on your apps in the last `days` days. TMDB knows release dates; JustWatch knows the day a title arrived on
     * each app, so its fallback is checked a day at a time (up to a week back).
     */
    static List<Tmdb.Title> newOn(Context ctx, List<Services.Service> services, int days) throws IOException {
        if (StreamRadar.chosen(ctx) && !services.isEmpty()) {
            try {
                // Always a month: its lists are often quiet for a week, and a short answer here would hand over to TMDB.
                List<Tmdb.Title> found = StreamRadar.newOn(ctx, services, 30);
                if (!found.isEmpty()) return found;
            } catch (IOException ignored) {
                // TMDB or JustWatch next
            }
        }
        if (tmdb()) {
            try {
                return Tmdb.newOn(ctx, services, days);
            } catch (IOException e) {
                tmdbFailed(e);
            }
        }
        if (services.isEmpty()) return new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar day = Calendar.getInstance();
        Map<String, Tmdb.Title> out = new LinkedHashMap<>();
        IOException last = null;
        for (int d = 0; d < Math.min(days, 7) && out.size() < 20; d++) {
            String date = f.format(day.getTime());
            day.add(Calendar.DAY_OF_YEAR, -1);
            try {
                for (JustWatch.Title j : JustWatch.newTitles(date, codes(services), 40)) {
                    Tmdb.Title t = fromJw(j, null);
                    if (t == null || t.jwBackdrop == null || out.containsKey(t.key()) || t.streams.isEmpty()) continue;
                    t.service = firstInstalled(t.streams, services);
                    if (t.service == null) continue;
                    t.added = date;
                    out.put(t.key(), t);
                    if (out.size() >= 20) break;
                }
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return new ArrayList<>(out.values());
    }

    /** One page (1-based) of a Discover category on one app; type "movie" or "tv"; language a code or "". */
    @Nullable
    static Tmdb.Page discover(Context ctx, Tmdb.Category c, Services.Service s, String type, int page, String language)
            throws IOException {
        if (tmdb()) {
            try {
                Tmdb.Page p = Tmdb.discover(ctx, c, s, type, page, language);
                if (p != null) return p;   // null: TMDB doesn't list this app in India
            } catch (IOException e) {
                tmdbFailed(e);
            }
        }
        String genres = "movie".equals(type) ? c.movieGenres : c.tvGenres;
        if (genres == null) return new Tmdb.Page(new ArrayList<>(), 0, false);
        String lang = language != null && !language.isEmpty() ? language
                : c.language != null ? c.language.split("\\|")[0] : language;
        JustWatch.Category jc = category(c, genres, lang);
        int size = 20;
        JustWatch.Chunk chunk = jwChunk(() -> JustWatch.browse(jc, codes(Collections.singletonList(s)),
                "movie".equals(type) ? "MOVIE" : "SHOW", (page - 1 + (c.random ? 0 : c.pageOffset)) * size, size));
        List<Tmdb.Title> titles = new ArrayList<>();
        for (JustWatch.Title j : chunk.titles) {
            Tmdb.Title t = fromJw(j, language);
            if (t != null && t.streams.contains(s)) {
                t.service = s;
                titles.add(t);
            }
        }
        return new Tmdb.Page(titles, 0, chunk.fetched >= size);
    }

    /** Typed search, one page (1-based). JustWatch results already say where they stream. */
    static Tmdb.Page search(Context ctx, String query, int page) throws IOException {
        if (tmdb()) {
            try {
                return Tmdb.search(ctx, query, page);
            } catch (IOException e) {
                tmdbFailed(e);
            }
        }
        String clean = query.trim();
        if (clean.isEmpty() || clean.length() > 100) return new Tmdb.Page(new ArrayList<>(), 0, false);
        List<JustWatch.Title> found = jw(() -> JustWatch.search(clean, null, (page - 1) * 20, 20));
        List<Tmdb.Title> titles = new ArrayList<>();
        for (JustWatch.Title j : found) {
            Tmdb.Title t = fromJw(j, null);
            if (t != null) titles.add(t);
        }
        return new Tmdb.Page(titles, 0, found.size() >= 20);
    }

    /** The apps that stream a title in India, in row order. */
    static List<Services.Service> whereToWatch(Context ctx, Tmdb.Title t) throws IOException {
        if (t.streams != null) return t.streams;
        return Tmdb.whereToWatch(ctx, t);
    }

    /** Runtime, genres and trailer. JustWatch titles arrive with them already. */
    static void details(Context ctx, Tmdb.Title t) {
        if (t.detailed || t.jwId != null || !tmdb()) return;
        try {
            Tmdb.details(ctx, t);
        } catch (IOException e) {
            tmdbFailed(e);
        }
    }

    /** The title's own logo, or null: the name is then set in type. */
    @Nullable
    static String logo(Context ctx, Tmdb.Title t) {
        if (t.logoUrl != null) return t.logoUrl;
        if (t.id <= 0 || !tmdb()) return null;
        try {
            return Tmdb.logo(ctx, t);
        } catch (IOException e) {
            tmdbFailed(e);
            return null;
        }
    }

    /** Up to five leading actors, or "". */
    static String cast(Context ctx, Tmdb.Title t) {
        if (t.id <= 0 || !tmdb()) return "";
        try {
            return Tmdb.cast(ctx, t);
        } catch (IOException e) {
            tmdbFailed(e);
            return "";
        }
    }

    // ---- JustWatch → the shared title model ------------------------------------------------------

    /** JustWatch package codes for each app. */
    private static final Map<String, Services.Service> BY_CODE = new HashMap<>();

    static {
        BY_CODE.put("nfx", Services.NETFLIX);
        BY_CODE.put("nfk", Services.NETFLIX);
        BY_CODE.put("prv", Services.PRIME);
        BY_CODE.put("jhs", Services.HOTSTAR);
        BY_CODE.put("atp", Services.APPLE);
        BY_CODE.put("zee", Services.ZEE5);
        BY_CODE.put("snl", Services.SONYLIV);
        BY_CODE.put("nfa", Services.NETFLIX);
        BY_CODE.put("amp", Services.PRIME);
        BY_CODE.put("dnp", Services.DISNEY);
        BY_CODE.put("mxx", Services.MAX);
        BY_CODE.put("hlu", Services.HULU);
        BY_CODE.put("pct", Services.PEACOCK);
        BY_CODE.put("pcp", Services.PEACOCK);
        BY_CODE.put("ppp", Services.PARAMOUNT);
        BY_CODE.put("ppe", Services.PARAMOUNT);
    }

    static List<String> codes(List<Services.Service> services) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Services.Service> e : BY_CODE.entrySet()) if (services.contains(e.getValue())) out.add(e.getKey());
        Collections.sort(out);
        return out;
    }

    private static final Map<String, String> GENRE_NAMES = new HashMap<>();

    static {
        String[][] g = {{"act", "Action"}, {"ani", "Animation"}, {"cmy", "Comedy"}, {"crm", "Crime"}, {"doc", "Documentary"},
                {"drm", "Drama"}, {"fml", "Family"}, {"fnt", "Fantasy"}, {"hst", "History"}, {"hrr", "Horror"}, {"msc", "Music"},
                {"rma", "Romance"}, {"scf", "Sci-Fi"}, {"spt", "Sport"}, {"trl", "Thriller"}, {"war", "War"}, {"wsn", "Western"},
                {"rly", "Reality"}};
        for (String[] x : g) GENRE_NAMES.put(x[0], x[1]);
    }

    /** A JustWatch title as a Tmdb.Title: its TMDB id when known (for Wikidata), links into each app, and details. */
    @Nullable
    static Tmdb.Title fromJw(JustWatch.Title j, @Nullable String language) {
        String type = "SHOW".equals(j.objectType) ? "tv" : "MOVIE".equals(j.objectType) ? "movie" : null;
        if (type == null || j.title == null || j.title.trim().isEmpty() || j.id.isEmpty()) return null;
        String date = !j.releaseDate.isEmpty() ? j.releaseDate : j.year > 1800 ? j.year + "-01-01" : "";
        int tmdbId = j.tmdbId != null ? Integer.parseInt(j.tmdbId) : 0;
        Tmdb.Title t = new Tmdb.Title(tmdbId, type, j.title.trim(), j.description.trim(), date, 0, j.imdb, null, null);
        t.jwId = j.id;
        t.jwBackdrop = j.backdropPath;
        t.jwPoster = j.posterPath;
        t.runtime = j.runtime;
        t.trailer = j.trailer;
        if (j.trailer != null) t.trailers = Collections.singletonList(j.trailer);
        List<String> names = new ArrayList<>();
        for (String g : j.genres) if (GENRE_NAMES.containsKey(g) && names.size() < 2) names.add(GENRE_NAMES.get(g));
        t.genres = String.join(", ", names);
        t.genreCodes = new ArrayList<>(j.genres);
        t.tomato = j.tomato;
        t.fresh = j.fresh;
        t.votes = j.votes;
        t.rentOrBuy = j.rentOrBuyOnly();
        t.detailed = true;
        if (language != null && !language.isEmpty()) t.language = language;
        t.links = links(j);
        t.streams = new ArrayList<>();
        for (Services.Service s : Services.STREAMING) if (t.links.containsKey(s.key)) t.streams.add(s);
        Collections.sort(t.streams, (a, b) -> Arrays.asList(Services.STREAMING).indexOf(a) - Arrays.asList(Services.STREAMING).indexOf(b));
        return t;
    }

    /** A JustWatch title's page in each app it streams on (subscription, free or with ads), by service key. */
    static Map<String, String> links(JustWatch.Title j) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : j.streamingLinks().entrySet()) {
            Services.Service s = BY_CODE.get(e.getKey());
            if (s != null && !out.containsKey(s.key)) out.put(s.key, e.getValue());
        }
        return out;
    }

    /** A Discover category in JustWatch's terms: its genres, sort, years and rating, and the chosen language. */
    static JustWatch.Category category(Tmdb.Category c, String tmdbGenres, String language) {
        JustWatch.Category j = new JustWatch.Category("Catalog", c.name);
        List<String> genres = new ArrayList<>();
        boolean anyOf = tmdbGenres.contains("|");   // TMDB: "a|b" is either, "a,b" is both
        for (String id : tmdbGenres.split("[,|]")) {
            String g = JW_GENRE.get(id.trim());
            if (g != null && !genres.contains(g)) genres.add(g);
        }
        if (anyOf && !genres.isEmpty()) {
            j.genres = genres.toArray(new String[0]);   // JustWatch's genre filter is "any of"
        } else {
            if (!genres.isEmpty()) j.genres = new String[]{genres.get(0)};
            if (genres.size() > 1) j.requireGenre = genres.get(1);
        }
        j.sort = c.random ? "RANDOM" : c.sort.startsWith("vote_average") ? "IMDB_SCORE"
                : c.sort.contains("release_date") ? "RELEASE_YEAR" : "POPULAR";
        int year = Calendar.getInstance().get(Calendar.YEAR);
        if (c.sinceDays > 0) {
            Calendar since = Calendar.getInstance();
            since.add(Calendar.DAY_OF_YEAR, -c.sinceDays);
            j.minYear = since.get(Calendar.YEAR);
            j.maxYear = year;
        }
        if (c.fromYear > 0) {
            j.minYear = c.fromYear;
            j.maxYear = c.untilYear;
        }
        if (c.minRating > 0) j.minImdb = c.minRating;
        if (c.minVotes > 0) j.minVotes = c.minVotes * 10;   // IMDb has far more votes than TMDB
        if (c.maxVotes > 0) j.maxVotes = c.maxVotes * 10;
        applyLanguage(j, language);
        return j;
    }

    /** JustWatch has no language filter: production country, plus the script of the original title for Indian languages. */
    static void applyLanguage(JustWatch.Category j, String language) {
        if (language == null) return;
        switch (language) {
            case "hi": case "mr": j.countries = new String[]{"IN"}; j.script = JustWatch.Script.DEVANAGARI; break;
            case "ta": j.countries = new String[]{"IN"}; j.script = JustWatch.Script.TAMIL; break;
            case "te": j.countries = new String[]{"IN"}; j.script = JustWatch.Script.TELUGU; break;
            case "ml": j.countries = new String[]{"IN"}; j.script = JustWatch.Script.MALAYALAM; break;
            case "gu": j.countries = new String[]{"IN"}; j.script = JustWatch.Script.GUJARATI; break;
            case "bn": j.countries = new String[]{"IN", "BD"}; j.script = JustWatch.Script.BENGALI; break;
            case "en": j.countries = new String[]{"US", "GB", "CA", "AU"}; break;
            case "ko": j.countries = new String[]{"KR"}; break;
            case "ja": j.countries = new String[]{"JP"}; break;
            case "zh": j.countries = new String[]{"CN", "TW", "HK"}; break;
            case "es": j.countries = new String[]{"ES", "MX", "AR", "CO"}; break;
            case "fr": j.countries = new String[]{"FR", "BE"}; break;
            case "de": j.countries = new String[]{"DE", "AT"}; break;
            case "it": j.countries = new String[]{"IT"}; break;
            case "pt": j.countries = new String[]{"BR", "PT"}; break;
            default:
        }
    }

    /** TMDB genre ids (movie and TV) to JustWatch genre codes. */
    private static final Map<String, String> JW_GENRE = new HashMap<>();

    static {
        String[][] g = {{"28", "act"}, {"12", "act"}, {"16", "ani"}, {"35", "cmy"}, {"80", "crm"}, {"99", "doc"}, {"18", "drm"},
                {"10751", "fml"}, {"14", "fnt"}, {"36", "hst"}, {"27", "hrr"}, {"10402", "msc"}, {"9648", "trl"}, {"10749", "rma"},
                {"878", "scf"}, {"53", "trl"}, {"10752", "war"}, {"10759", "act"}, {"10765", "scf"}, {"10764", "rly"},
                {"10762", "fml"}, {"10768", "war"}};
        for (String[] x : g) JW_GENRE.put(x[0], x[1]);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static JustWatch.Category sorted(String sort) {
        JustWatch.Category c = new JustWatch.Category("Catalog", sort);
        c.sort = sort;
        return c;
    }

    @Nullable
    private static Tmdb.Title first(List<JustWatch.Title> list, @Nullable Services.Service s) {
        for (JustWatch.Title j : list) {
            Tmdb.Title t = fromJw(j, null);
            if (t == null || t.jwBackdrop == null || t.streams.isEmpty()) continue;
            t.service = s != null ? s : t.streams.get(0);
            return t;
        }
        return null;
    }

    @Nullable
    private static Services.Service firstInstalled(List<Services.Service> streams, List<Services.Service> mine) {
        for (Services.Service s : streams) if (mine.contains(s)) return s;
        return null;
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    /** Runs a JustWatch request, turning its failures into IOExceptions like TMDB's. */
    private static <T> T jw(Call<T> call) throws IOException {
        try {
            return call.run();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("JustWatch: " + e.getMessage(), e);
        }
    }

    private static JustWatch.Chunk jwChunk(Call<JustWatch.Chunk> call) throws IOException {
        return jw(call);
    }
}
