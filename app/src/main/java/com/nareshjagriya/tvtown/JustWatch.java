package com.nareshjagriya.tvtown;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Title search and browsing on JustWatch's catalogue for India: which streaming app has a title, with a
 * link that opens it in that app. JustWatch's GraphQL API is not an official public API, so it may change: it
 * serves the Random buttons, and stands in for TMDB (see Catalog) when TMDB has no key or does not answer.
 */
final class JustWatch {

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMAGES = "https://images.justwatch.com";

    static final class Title {
        final String id;                 // JustWatch's own id, e.g. "tm1626286"
        final String title;
        final int year;
        final String releaseDate;        // yyyy-MM-dd, or ""
        final int runtime;               // minutes
        final String tmdbId;             // numeric, or null
        final String trailer;            // YouTube video id, or null
        final String poster, posterPath, backdropPath;   // paths still hold {profile} and {format}
        final JSONArray offers;
        final List<String> genres = new ArrayList<>();
        final String originalTitle, description, backdrop, objectType;
        final double imdb;
        final int votes;
        final int tomato;                // Rotten Tomatoes %, or -1
        final boolean fresh;             // Certified Fresh

        Title(JSONObject node) {
            JSONObject c = node.optJSONObject("content");
            if (c == null) c = new JSONObject();
            id = node.optString("id", "");
            title = c.optString("title");
            String rd = c.optString("originalReleaseDate", "");
            releaseDate = rd.matches("\\d{4}-\\d{2}-\\d{2}") ? rd : "";
            runtime = Math.max(0, Math.min(1000, c.optInt("runtime")));
            JSONObject ext = c.optJSONObject("externalIds");
            String tid = ext != null ? ext.optString("tmdbId", "") : "";
            tmdbId = tid.matches("[1-9]\\d{0,8}") ? tid : null;
            String yt = null;
            JSONArray clips = c.optJSONArray("clips");
            for (int i = 0; clips != null && i < clips.length() && yt == null; i++) {
                JSONObject cl = clips.optJSONObject(i);
                String key = cl != null ? cl.optString("externalId", "") : "";
                if (cl != null && "youtube".equalsIgnoreCase(cl.optString("provider")) && key.matches("[A-Za-z0-9_\\-]{11}")) yt = key;
            }
            trailer = yt;
            originalTitle = c.optString("originalTitle", "");
            year = c.optInt("originalReleaseYear");
            String p = c.optString("posterUrl", "");
            posterPath = imagePath(p);
            poster = posterPath == null ? null : IMAGES + posterPath.replace("{profile}", "s276").replace("{format}", "jpg");
            JSONArray g = c.optJSONArray("genres");
            for (int i = 0; g != null && i < g.length(); i++) genres.add(g.optJSONObject(i).optString("shortName"));
            JSONObject s = c.optJSONObject("scoring");
            imdb = s != null ? s.optDouble("imdbScore", 0) : 0;
            votes = s != null ? s.optInt("imdbVotes", 0) : 0;
            tomato = s != null && !s.isNull("tomatoMeter") ? Math.max(-1, Math.min(100, s.optInt("tomatoMeter", -1))) : -1;
            fresh = s != null && s.optBoolean("certifiedFresh", false);
            String d = c.optString("shortDescription", "");
            description = "null".equals(d) ? "" : d;
            JSONArray bd = c.optJSONArray("backdrops");
            String b = bd != null && bd.length() > 0 ? bd.optJSONObject(0).optString("backdropUrl", "") : "";
            backdropPath = imagePath(b);
            backdrop = backdropPath == null ? null : image(backdropPath, "s640");
            objectType = node.optString("objectType", "");
            JSONArray o = node.optJSONArray("offers");
            offers = o != null ? o : new JSONArray();
        }

        /** Package codes (nfx, prv…) that stream it (subscription, free or with ads), each with its web link. */
        java.util.Map<String, String> streamingLinks() {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < offers.length(); i++) {
                JSONObject o = offers.optJSONObject(i);
                JSONObject pk = o != null ? o.optJSONObject("package") : null;
                String type = o != null ? o.optString("monetizationType") : "";
                String url = o != null ? o.optString("standardWebURL", "") : "";
                boolean included = type.equals("FLATRATE") || type.equals("FREE") || type.equals("ADS");
                if (pk != null && included && url.startsWith("https://")) m.putIfAbsent(pk.optString("shortName"), url);
            }
            return m;
        }

        /** True when it can only be rented or bought in India (no subscription, free or ads offer). */
        boolean rentOrBuyOnly() {
            boolean paid = false;
            for (int i = 0; i < offers.length(); i++) {
                JSONObject o = offers.optJSONObject(i);
                String type = o != null ? o.optString("monetizationType") : "";
                if (type.equals("FLATRATE") || type.equals("FREE") || type.equals("ADS")) return false;
                if (type.equals("RENT") || type.equals("BUY")) paid = true;
            }
            return paid;
        }

    }

    /** Only JustWatch's own image paths, e.g. /backdrop/340777357/{profile}/name.{format}. */
    static String imagePath(String p) {
        return p != null && p.matches("/(backdrop|poster)/\\d+/\\{profile\\}/[A-Za-z0-9_\\-]+\\.\\{format\\}") ? p : null;
    }

    /** A full image URL at a size: s640, s1440 or s1920 for backdrops; s166, s276 or s332 for posters. */
    static String image(String path, String profile) {
        return path == null ? null : IMAGES + path.replace("{profile}", profile).replace("{format}", "jpg");
    }

    // ---- categories ------------------------------------------------------------------------------

    /** Writing systems used to tell Indian languages apart by a title's original name. */
    enum Script {
        NONE, DEVANAGARI, GURMUKHI, GUJARATI, TAMIL, TELUGU, KANNADA, MALAYALAM, BENGALI, SOUTH;

        boolean matches(String s) {
            if (this == NONE) return true;
            for (int i = 0; i < s.length(); i++) {
                Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
                if (b == null) continue;
                switch (this) {
                    case DEVANAGARI: if (b == Character.UnicodeBlock.DEVANAGARI) return true; break;
                    case GURMUKHI: if (b == Character.UnicodeBlock.GURMUKHI) return true; break;
                    case GUJARATI: if (b == Character.UnicodeBlock.GUJARATI) return true; break;
                    case TAMIL: if (b == Character.UnicodeBlock.TAMIL) return true; break;
                    case TELUGU: if (b == Character.UnicodeBlock.TELUGU) return true; break;
                    case KANNADA: if (b == Character.UnicodeBlock.KANNADA) return true; break;
                    case MALAYALAM: if (b == Character.UnicodeBlock.MALAYALAM) return true; break;
                    case BENGALI: if (b == Character.UnicodeBlock.BENGALI) return true; break;
                    case SOUTH:
                        if (b == Character.UnicodeBlock.TAMIL || b == Character.UnicodeBlock.TELUGU
                                || b == Character.UnicodeBlock.KANNADA || b == Character.UnicodeBlock.MALAYALAM) return true;
                        break;
                    default:
                }
            }
            return false;
        }
    }

    /** One browsable category, as JustWatch filters plus checks done here where JustWatch has no filter. */
    static final class Category {
        final String section, name;
        String[] genres, subgenres, countries, excludeGenres;
        String requireGenre;            // AND with genres (JustWatch's genre filter is OR)
        String type;                    // MOVIE, SHOW or null for both
        String sort = "POPULAR";
        int minYear, maxYear;
        double minImdb;
        int minVotes, maxVotes;
        Script script = Script.NONE;

        Category(String section, String name) {
            this.section = section;
            this.name = name;
        }

        Category genres(String... g) { genres = g; return this; }
        Category sub(String... s) { subgenres = s; return this; }
        Category countries(String... c) { countries = c; return this; }
        Category exclude(String... g) { excludeGenres = g; return this; }
        Category and(String g) { requireGenre = g; return this; }
        Category type(String t) { type = t; return this; }
        Category sort(String s) { sort = s; return this; }
        Category years(int min, int max) { minYear = min; maxYear = max; return this; }
        Category imdb(double min) { minImdb = min; return this; }
        Category votes(int min, int max) { minVotes = min; maxVotes = max; return this; }
        Category script(Script s) { script = s; return this; }

        boolean keep(Title t) {
            if (requireGenre != null && !t.genres.contains(requireGenre)) return false;
            if (minVotes > 0 && t.votes < minVotes) return false;
            if (maxVotes > 0 && t.votes > maxVotes) return false;
            return script.matches(t.originalTitle);
        }
    }

    static List<Category> categories() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        List<Category> l = new ArrayList<>();
        String s = "Discover";
        l.add(new Category(s, "Trending").sort("TRENDING"));
        l.add(new Category(s, "New Releases").years(year - 1, year));
        l.add(new Category(s, "Top Rated").imdb(8.0).votes(20000, 0));
        l.add(new Category(s, "Hidden Gems").imdb(7.5).votes(1000, 30000));
        l.add(new Category(s, "Award Winning").sub("fbpw", "fffd"));

        s = "Genres";
        l.add(new Category(s, "Action").genres("act"));
        l.add(new Category(s, "Adventure").sub("adva", "acte"));
        l.add(new Category(s, "Comedy").genres("cmy"));
        l.add(new Category(s, "Crime").genres("crm"));
        l.add(new Category(s, "Drama").genres("drm"));
        l.add(new Category(s, "Thriller").genres("trl"));
        l.add(new Category(s, "Horror").genres("hrr"));
        l.add(new Category(s, "Mystery").sub("myst", "detc"));
        l.add(new Category(s, "Romance").genres("rma"));
        l.add(new Category(s, "Sci-Fi").genres("scf"));
        l.add(new Category(s, "Fantasy").genres("fnt"));
        l.add(new Category(s, "Animation").genres("ani"));
        l.add(new Category(s, "Documentary").genres("doc"));
        l.add(new Category(s, "Family").genres("fml"));
        l.add(new Category(s, "War").genres("war"));
        l.add(new Category(s, "History").genres("hst"));
        l.add(new Category(s, "Biography").sub("drbi", "dobi", "mubc"));
        l.add(new Category(s, "Musical").genres("msc"));
        l.add(new Category(s, "Sports").genres("spt"));

        s = "Mixes";
        l.add(new Category(s, "Crime Thriller").genres("crm").and("trl"));
        l.add(new Category(s, "Psychological Thriller").sub("psch"));
        l.add(new Category(s, "Mystery Thriller").sub("myst"));
        l.add(new Category(s, "Action Thriller").sub("acat", "that"));
        l.add(new Category(s, "Sci-Fi Thriller").genres("scf").and("trl"));
        l.add(new Category(s, "Horror Thriller").sub("thht"));
        l.add(new Category(s, "Courtroom & Legal").sub("ccld"));
        l.add(new Category(s, "Crime Drama").genres("crm").and("drm"));
        l.add(new Category(s, "Romantic Comedy").sub("coro", "roco"));
        l.add(new Category(s, "Romantic Drama").sub("drrd"));
        l.add(new Category(s, "Action Comedy").sub("coac", "arac"));
        l.add(new Category(s, "Horror Comedy").sub("cohc", "horc"));
        l.add(new Category(s, "Teen Comedy").sub("tnrc", "trco"));
        l.add(new Category(s, "Space").sub("scsw"));
        l.add(new Category(s, "Dystopian").sub("syad", "spaw", "entw", "apcd"));
        l.add(new Category(s, "Heist").sub("thhm"));
        l.add(new Category(s, "Spy").sub("cssa"));
        l.add(new Category(s, "Detective").sub("detc"));
        l.add(new Category(s, "Gangster").sub("croc"));
        l.add(new Category(s, "Zombie").sub("sciz", "hosz"));
        l.add(new Category(s, "Supernatural Horror").sub("hopa"));
        l.add(new Category(s, "Superhero").sub("acsm"));
        l.add(new Category(s, "Martial Arts").sub("acma"));
        l.add(new Category(s, "Disaster").sub("acdm"));
        l.add(new Category(s, "Coming of Age").sub("dyad"));
        l.add(new Category(s, "Based on a True Story").sub("dbts"));
        l.add(new Category(s, "Biopic").sub("drbi", "mubc"));
        l.add(new Category(s, "True Crime").sub("docr"));

        s = "India";
        l.add(new Category(s, "Bollywood").sub("fobo"));
        l.add(new Category(s, "Hindi Thriller").countries("IN").genres("trl").script(Script.DEVANAGARI));
        l.add(new Category(s, "Hindi Comedy").countries("IN").genres("cmy").script(Script.DEVANAGARI));
        l.add(new Category(s, "Hindi Web Series").countries("IN").type("SHOW").script(Script.DEVANAGARI));
        l.add(new Category(s, "90s Bollywood").countries("IN").type("MOVIE").years(1990, 1999).script(Script.DEVANAGARI));
        l.add(new Category(s, "South Indian").countries("IN").script(Script.SOUTH));
        l.add(new Category(s, "Tamil").countries("IN").script(Script.TAMIL));
        l.add(new Category(s, "Telugu").countries("IN").script(Script.TELUGU));
        l.add(new Category(s, "Malayalam").countries("IN").script(Script.MALAYALAM));
        l.add(new Category(s, "Kannada").countries("IN").script(Script.KANNADA));
        l.add(new Category(s, "Punjabi").countries("IN").script(Script.GURMUKHI));

        s = "World";
        l.add(new Category(s, "Korean Drama").countries("KR").genres("drm"));
        l.add(new Category(s, "Korean Thriller").countries("KR").genres("trl"));
        l.add(new Category(s, "Japanese").countries("JP").exclude("ani"));
        l.add(new Category(s, "Spanish").countries("ES"));
        l.add(new Category(s, "British").countries("GB"));
        l.add(new Category(s, "Turkish Drama").countries("TR").genres("drm"));

        s = "TV";
        l.add(new Category(s, "Docuseries").genres("doc").type("SHOW"));
        l.add(new Category(s, "Reality").genres("rly"));
        l.add(new Category(s, "Sitcom").sub("sitc", "stcm"));
        l.add(new Category(s, "Stand-up Comedy").sub("cosu"));

        s = "Anime";
        l.add(new Category(s, "Anime").genres("ani").countries("JP"));
        l.add(new Category(s, "Anime Movies").genres("ani").countries("JP").type("MOVIE"));
        l.add(new Category(s, "Classic Anime").sub("jpan"));
        l.add(new Category(s, "Studio Ghibli").sub("ghbl"));

        s = "Family";
        l.add(new Category(s, "Family Movies").genres("fml").type("MOVIE"));
        l.add(new Category(s, "Kids").sub("fayc"));
        l.add(new Category(s, "Classic Movies").type("MOVIE").years(1930, 1985));
        return l;
    }

    // ---- requests --------------------------------------------------------------------------------

    private static final String CONTENT = "content(country: $c, language: $l) { title originalTitle originalReleaseYear "
            + "originalReleaseDate runtime posterUrl shortDescription backdrops { backdropUrl } genres { shortName } "
            + "externalIds { tmdbId } clips { externalId provider } scoring { imdbScore imdbVotes tomatoMeter certifiedFresh } }";
    private static final String OFFERS = "offers(country: $c, platform: WEB) { monetizationType standardWebURL package { shortName } }";
    private static final String QUERY = "query($c: Country!, $l: Language!, $f: TitleFilter, $n: Int!, $o: Int, "
            + "$s: PopularTitlesSorting!) { popularTitles(country: $c, filter: $f, first: $n, offset: $o, sortBy: $s) "
            + "{ edges { node { id objectType " + CONTENT + " " + OFFERS + " } } } }";
    /** Titles that arrived on a service on one day; a new season counts as its show. */
    private static final String NEW_QUERY = "query($c: Country!, $l: Language!, $d: Date!, $n: Int!, $f: TitleFilter) "
            + "{ newTitles(country: $c, date: $d, first: $n, filter: $f, pageType: NEW) { edges { "
            + "newOffer(platform: WEB) { monetizationType standardWebURL package { shortName } } node { "
            + "... on MovieOrShow { id objectType " + CONTENT + " " + OFFERS + " } "
            + "... on Season { show { id objectType " + CONTENT + " " + OFFERS + " } } } } } }";

    /** One fetch: the titles kept, and how many JustWatch returned before local filtering (to spot the end). */
    static final class Chunk {
        final List<Title> titles;
        final int fetched;

        Chunk(List<Title> titles, int fetched) {
            this.titles = titles;
            this.fetched = fetched;
        }
    }

    /** A page of a category on these package codes, starting `offset` titles in. */
    static Chunk browse(Category c, List<String> codes, String type, int offset, int count) throws Exception {
        JSONObject f = new JSONObject();
        put(f, "genres", c.genres);
        put(f, "subgenres", c.subgenres);
        put(f, "productionCountries", c.countries);
        put(f, "excludeGenres", c.excludeGenres);
        String t = c.type != null ? c.type : type;
        if (t != null) f.put("objectTypes", new JSONArray().put(t));
        if (c.minYear > 0) f.put("releaseYear", new JSONObject().put("min", c.minYear).put("max", c.maxYear));
        if (c.minImdb > 0) f.put("imdbScore", new JSONObject().put("min", c.minImdb));
        f.put("packages", new JSONArray(codes));
        f.put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
        List<Title> all = fetch(f, count, offset, c.sort);
        List<Title> out = new ArrayList<>();
        for (Title title : all) {
            if (c.keep(title)) out.add(title);
        }
        return new Chunk(out, all.size());
    }

    /**
     * Must-Watch: the best rated titles known in India, streaming or not. Category gives genres, countries, years,
     * minimum IMDb score and sort; minVotes and minTomato (Rotten Tomatoes %, 0 for any) narrow it further.
     */
    static Chunk top(Category c, String type, int minVotes, int minTomato, int offset, int count) throws Exception {
        JSONObject f = new JSONObject();
        put(f, "genres", c.genres);
        put(f, "productionCountries", c.countries);
        f.put("objectTypes", new JSONArray().put(type));
        if (c.minYear > 0) f.put("releaseYear", new JSONObject().put("min", c.minYear).put("max", c.maxYear));
        if (c.minImdb > 0) f.put("imdbScore", new JSONObject().put("min", c.minImdb));
        if (minVotes > 0) f.put("imdbVotes", new JSONObject().put("min", minVotes));
        if (minTomato > 0) f.put("tomatoMeter", new JSONObject().put("min", minTomato));
        List<Title> all = fetch(f, count, offset, c.sort);
        List<Title> out = new ArrayList<>();
        for (Title title : all) if (c.script.matches(title.originalTitle)) out.add(title);
        return new Chunk(out, all.size());
    }

    static List<Title> search(String text, String type, int offset, int count) throws Exception {
        JSONObject f = new JSONObject().put("searchQuery", text);
        if (type != null) f.put("objectTypes", new JSONArray().put(type));
        return fetch(f, count, offset, "POPULAR");
    }

    private static void put(JSONObject f, String key, String[] values) throws Exception {
        if (values != null && values.length > 0) f.put(key, new JSONArray(Arrays.asList(values)));
    }

    /** Titles added on one day (yyyy-MM-dd) to these package codes, with the code and link of the new offer. */
    static List<Title> newTitles(String date, List<String> codes, int count) throws Exception {
        JSONObject f = new JSONObject().put("packages", new JSONArray(codes))
                .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
        JSONObject vars = new JSONObject().put("c", Style.country()).put("l", "en").put("d", date).put("n", count).put("f", f);
        JSONArray edges = post(NEW_QUERY, vars).getJSONObject("data").getJSONObject("newTitles").getJSONArray("edges");
        List<Title> titles = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i), node = e != null ? e.optJSONObject("node") : null;
            if (node == null) continue;
            if (node.has("show")) node = node.optJSONObject("show");
            if (node == null || !node.has("content")) continue;
            titles.add(new Title(node));
        }
        return titles;
    }

    private static List<Title> fetch(JSONObject filter, int count, int offset, String sort) throws Exception {
        JSONObject vars = new JSONObject().put("c", Style.country()).put("l", "en").put("f", filter)
                .put("n", count).put("o", offset).put("s", sort);
        JSONArray edges = post(QUERY, vars).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        List<Title> titles = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) titles.add(new Title(edges.getJSONObject(i).getJSONObject("node")));
        return titles;
    }

    /** One GraphQL request, tried twice; at most 6 MB of response. */
    private static JSONObject post(String query, JSONObject vars) throws Exception {
        byte[] body = new JSONObject().put("query", query).put("variables", vars).toString().getBytes("UTF-8");
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return postOnce(body);
            } catch (java.io.IOException e) {
                last = e;
                Thread.sleep(600);
            }
        }
        throw last;
    }

    private static JSONObject postOnce(byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(15_000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16 * 1024];
                for (int n; (n = in.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                    if (buf.size() > 6 * 1024 * 1024) throw new java.io.IOException("Response too large");
                }
            }
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
