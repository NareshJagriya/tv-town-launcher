package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Opens a title directly in a streaming app. The app's own title id comes from Wikidata, looked up by the
 * TMDB id (P4947 movies, P4983 series): Netflix P1874, JioHotstar P11049, Prime Video P8055, Apple TV P9586
 * (movies) and P9751 (shows). SPARQL is tried first; when it is rate-limited or slow, Wikidata's search API
 * is used instead. The whole lookup has a 3 s budget, and results (misses too) are cached on the TV.
 * Fallback: the exact title, else the app, else its Play Store page.
 */
final class DeepLinks {

    static final int BUDGET_MS = 3000;
    private static final long HIT_TTL = 30L * 24 * 3600_000L, MISS_TTL = 3L * 24 * 3600_000L;

    // Formats from Wikidata's own constraints for each property.
    static final Pattern TMDB_ID = Pattern.compile("[1-9]\\d{0,8}");
    static final Pattern NETFLIX_ID = Pattern.compile("\\d{6,9}");
    static final Pattern HOTSTAR_ID = Pattern.compile("\\d{4,12}");
    static final Pattern PRIME_ID = Pattern.compile("B[0-9A-Z]{9}");
    static final Pattern APPLE_ID = Pattern.compile("umc\\.cmc\\.[a-z0-9]{20,30}");

    /** A title's ids on the services; null where Wikidata has none. */
    static final class Ids {
        String netflix, hotstar, prime, appleMovie, appleShow;

        boolean any() {
            return netflix != null || hotstar != null || prime != null || appleMovie != null || appleShow != null;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("nf", netflix).put("hs", hotstar).put("pv", prime).put("am", appleMovie).put("as", appleShow);
            } catch (org.json.JSONException ignored) {
                // only string values: cannot happen
            }
            return o;
        }

        static Ids fromJson(JSONObject o) {
            Ids i = new Ids();
            i.netflix = valid(o.optString("nf", null), NETFLIX_ID);
            i.hotstar = valid(o.optString("hs", null), HOTSTAR_ID);
            i.prime = valid(o.optString("pv", null), PRIME_ID);
            i.appleMovie = valid(o.optString("am", null), APPLE_ID);
            i.appleShow = valid(o.optString("as", null), APPLE_ID);
            return i;
        }
    }

    private DeepLinks() {
    }

    @Nullable
    static String valid(@Nullable String v, Pattern p) {
        return v != null && p.matcher(v).matches() ? v : null;
    }

    static boolean validTmdbId(String id) {
        return id != null && TMDB_ID.matcher(id).matches();
    }

    static String tmdbProperty(String type) {
        return "tv".equals(type) ? "P4983" : "P4947";
    }

    // ---- Wikidata --------------------------------------------------------------------------------

    /** The SPARQL query for one title. The TMDB id is validated first, so nothing else can reach the query. */
    static String sparql(String type, String tmdbId) {
        if (!validTmdbId(tmdbId)) throw new IllegalArgumentException("TMDB id must be numeric");
        return "SELECT ?nf ?hs ?pv ?am ?as WHERE { ?item wdt:" + tmdbProperty(type) + " \"" + tmdbId + "\" ."
                + " OPTIONAL { ?item wdt:P1874 ?nf } OPTIONAL { ?item wdt:P11049 ?hs } OPTIONAL { ?item wdt:P8055 ?pv }"
                + " OPTIONAL { ?item wdt:P9586 ?am } OPTIONAL { ?item wdt:P9751 ?as } } LIMIT 1";
    }

    /** Reads the first SPARQL result row; null when there is no row (Wikidata has no such item). */
    @Nullable
    static Ids parseSparql(JSONObject response) {
        JSONObject results = response.optJSONObject("results");
        JSONArray rows = results != null ? results.optJSONArray("bindings") : null;
        if (rows == null || rows.length() == 0) return null;
        JSONObject r = rows.optJSONObject(0);
        Ids i = new Ids();
        i.netflix = valid(binding(r, "nf"), NETFLIX_ID);
        i.hotstar = valid(binding(r, "hs"), HOTSTAR_ID);
        i.prime = valid(binding(r, "pv"), PRIME_ID);
        i.appleMovie = valid(binding(r, "am"), APPLE_ID);
        i.appleShow = valid(binding(r, "as"), APPLE_ID);
        return i;
    }

    @Nullable
    private static String binding(JSONObject row, String name) {
        JSONObject b = row != null ? row.optJSONObject(name) : null;
        return b != null ? b.optString("value", null) : null;
    }

    /** Reads ids from a wbgetentities "claims" object. */
    static Ids parseClaims(JSONObject claims) {
        Ids i = new Ids();
        i.netflix = valid(claim(claims, "P1874"), NETFLIX_ID);
        i.hotstar = valid(claim(claims, "P11049"), HOTSTAR_ID);
        i.prime = valid(claim(claims, "P8055"), PRIME_ID);
        i.appleMovie = valid(claim(claims, "P9586"), APPLE_ID);
        i.appleShow = valid(claim(claims, "P9751"), APPLE_ID);
        return i;
    }

    @Nullable
    private static String claim(JSONObject claims, String prop) {
        JSONArray a = claims != null ? claims.optJSONArray(prop) : null;
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject snak = a.optJSONObject(k) != null ? a.optJSONObject(k).optJSONObject("mainsnak") : null;
            JSONObject dv = snak != null ? snak.optJSONObject("datavalue") : null;
            Object v = dv != null ? dv.opt("value") : null;
            if (v instanceof String) return (String) v;
        }
        return null;
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/sparql-results+json, application/json");
        // Wikidata asks clients to identify themselves.
        h.put("User-Agent", "TVTownLauncher/" + BuildConfig.VERSION_NAME + " (Android TV launcher)");
        return h;
    }

    /** Ids for a title: from the cache, else Wikidata within the time budget. Never throws. */
    static Ids lookup(Context ctx, Tmdb.Title t) {
        String id = String.valueOf(t.id);
        Ids none = new Ids();
        if (!validTmdbId(id)) return none;
        SharedPreferences cache = ctx.getSharedPreferences("deeplinks", Context.MODE_PRIVATE);
        String key = t.type + ":" + id;
        try {
            String hit = cache.getString(key, null);
            if (hit != null) {
                JSONObject o = new JSONObject(hit);
                long age = System.currentTimeMillis() - o.optLong("t");
                boolean miss = o.optBoolean("miss");
                if (age < (miss ? MISS_TTL : HIT_TTL)) return miss ? none : Ids.fromJson(o.getJSONObject("ids"));
            }
        } catch (org.json.JSONException ignored) {
            // a damaged entry is simply looked up again
        }
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        Ids found = null;
        boolean answered = false;
        try {
            String url = "https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(sparql(t.type, id), StandardCharsets.UTF_8.name());
            found = parseSparql(new JSONObject(Net.text(Net.get(url, headers(), BUDGET_MS, 0))));
            answered = true;
        } catch (Exception e) {
            // rate-limited, slow or offline: try the search API with what is left of the budget
            int left = (int) (deadline - System.currentTimeMillis());
            if (left > 600) {
                try {
                    found = viaSearchApi(t.type, id, left);
                    answered = true;
                } catch (Exception ignored) {
                    // offline: nothing cached, try again next time
                }
            }
        }
        if (answered) {
            try {
                JSONObject entry = new JSONObject().put("t", System.currentTimeMillis());
                if (found != null && found.any()) entry.put("ids", found.toJson());
                else entry.put("miss", true);
                cache.edit().putString(key, entry.toString()).apply();
            } catch (org.json.JSONException ignored) {
                // not cached; harmless
            }
        }
        return found != null ? found : none;
    }

    /** haswbstatement search for the item, then its claims. Two small requests to www.wikidata.org. */
    @Nullable
    private static Ids viaSearchApi(String type, String tmdbId, int timeoutMs) throws Exception {
        String search = "https://www.wikidata.org/w/api.php?action=query&format=json&list=search&srlimit=1&srsearch="
                + URLEncoder.encode("haswbstatement:" + tmdbProperty(type) + "=" + tmdbId, StandardCharsets.UTF_8.name());
        JSONArray hits = new JSONObject(Net.text(Net.get(search, headers(), timeoutMs / 2, 0)))
                .optJSONObject("query").optJSONArray("search");
        if (hits == null || hits.length() == 0) return null;
        String q = hits.getJSONObject(0).optString("title");
        if (!q.matches("Q\\d{1,12}")) return null;
        String get = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims&ids=" + q;
        JSONObject entity = new JSONObject(Net.text(Net.get(get, headers(), timeoutMs / 2, 0)))
                .getJSONObject("entities").getJSONObject(q);
        return parseClaims(entity.optJSONObject("claims"));
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** The title's page in that service's Android TV app, or null when Wikidata has no id for it. */
    @Nullable
    static Uri titleUri(Services.Service s, Tmdb.Title t, Ids ids) {
        String url = titleUrl(s, t.isSeries(), ids);
        return url != null ? Uri.parse(url) : null;
    }

    /**
     * A TMDB title's pages in each app, from JustWatch: a search by name, keeping only the result with the same TMDB
     * id, so a different title with the same name is never opened. Cached like the Wikidata lookups (hits 30 days,
     * misses 3 days). Runs a network request; call off the main thread.
     */
    static Map<String, String> justWatchLinks(Context ctx, Tmdb.Title t) {
        Map<String, String> out = new HashMap<>();
        if (!validTmdbId(String.valueOf(t.id))) return out;
        SharedPreferences cache = ctx.getSharedPreferences("deeplinks", Context.MODE_PRIVATE);
        String key = "jw:" + t.type + ":" + t.id;
        try {
            String hit = cache.getString(key, null);
            if (hit != null) {
                JSONObject o = new JSONObject(hit);
                boolean found = o.optJSONObject("links") != null && o.optJSONObject("links").length() > 0;
                if (System.currentTimeMillis() - o.optLong("at") < (found ? HIT_TTL : MISS_TTL)) return linksFrom(o.optJSONObject("links"));
            }
        } catch (Exception ignored) {
            // a bad cache entry is simply replaced
        }
        try {
            for (JustWatch.Title j : JustWatch.search(t.title, t.isSeries() ? "SHOW" : "MOVIE", 0, 8)) {
                if (!String.valueOf(t.id).equals(j.tmdbId)) continue;
                out.putAll(Catalog.links(j));
                break;
            }
            JSONObject links = new JSONObject();
            for (Map.Entry<String, String> e : out.entrySet()) links.put(e.getKey(), e.getValue());
            cache.edit().putString(key, new JSONObject().put("at", System.currentTimeMillis()).put("links", links).toString()).apply();
        } catch (Exception ignored) {
            // JustWatch unreachable: the app opens instead
        }
        return out;
    }

    private static Map<String, String> linksFrom(@Nullable JSONObject o) {
        Map<String, String> out = new HashMap<>();
        if (o == null) return out;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, o.optString(k));
        }
        return out;
    }

    /** Hosts each app's own title links use (JustWatch's links are only followed to these). */
    static boolean directLink(Services.Service s, @Nullable String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String host = url.substring(8).split("[/?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        switch (s.key) {
            case "netflix": return host.equals("www.netflix.com");
            case "prime": return host.equals("app.primevideo.com") || host.equals("www.primevideo.com");
            case "hotstar": return host.equals("www.hotstar.com");
            case "apple": return host.equals("tv.apple.com");
            case "zee5": return host.equals("www.zee5.com");
            case "sonyliv": return host.equals("www.sonyliv.com");
            case "disney": return host.equals("www.disneyplus.com");
            case "max": return host.equals("play.hbomax.com") || host.equals("play.max.com");
            case "hulu": return host.equals("www.hulu.com");
            case "peacock": return host.equals("www.peacocktv.com");
            case "paramount": return host.equals("www.paramountplus.com");
            default: return false;
        }
    }

    /** The title's page on the service, from its Wikidata id; null when Wikidata has none for that service. */
    @Nullable
    static String titleUrl(Services.Service s, boolean series, Ids ids) {
        if (s == Services.NETFLIX && ids.netflix != null) return "https://www.netflix.com/title/" + ids.netflix;
        if (s == Services.HOTSTAR && ids.hotstar != null) return "https://www.hotstar.com/in/" + ids.hotstar;
        if (s == Services.PRIME && ids.prime != null) return "https://app.primevideo.com/detail?asin=" + ids.prime;
        if (s == Services.APPLE) {
            if (series && ids.appleShow != null) return "https://tv.apple.com/" + Style.country().toLowerCase(java.util.Locale.ROOT) + "/show/" + ids.appleShow;
            if (!series && ids.appleMovie != null) return "https://tv.apple.com/" + Style.country().toLowerCase(java.util.Locale.ROOT) + "/movie/" + ids.appleMovie;
        }
        return null;
    }


    /** What happened, for the message shown to the viewer. */
    enum Outcome { TITLE, APP, STORE, NOTHING }

    static final class Plan {
        final Intent intent;
        final Outcome outcome;

        Plan(@Nullable Intent intent, Outcome outcome) {
            this.intent = intent;
            this.outcome = outcome;
        }
    }

    /** Decides how to open a title on a service. Runs the Wikidata lookup: call off the main thread. */
    static Plan plan(Context ctx, Tmdb.Title t, Services.Service s) {
        PackageManager pm = ctx.getPackageManager();
        if (Services.installed(ctx, s.pkg)) {
            // JustWatch titles already carry their page in the app; others are looked up on Wikidata.
            String direct = t.links != null ? t.links.get(s.key) : null;
            Uri u = directLink(s, direct) ? Uri.parse(direct) : t.id > 0 ? titleUri(s, t, lookup(ctx, t)) : null;
            if (u == null && t.id > 0) {   // Wikidata has no id for it there (common for JioHotstar): ask JustWatch
                String jw = justWatchLinks(ctx, t).get(s.key);
                if (directLink(s, jw)) u = Uri.parse(jw);
            }
            if (u != null) {   // the app's own way in first (Netflix ignores plain links); else the link itself
                Intent own = AppLinks.own(ctx, s.pkg, u);
                if (own != null && own.resolveActivity(pm) != null) return new Plan(own, Outcome.TITLE);
            }
            if (u != null) {
                // A fresh start of the app's screen: Netflix and others ignore a title link when they are already
                // open in the background, and just come back where they were (their home page).
                Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(s.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                if (i.resolveActivity(pm) != null) return new Plan(i, Outcome.TITLE);
            }
            Intent app = pm.getLeanbackLaunchIntentForPackage(s.pkg);
            if (app == null) app = pm.getLaunchIntentForPackage(s.pkg);
            if (app != null) return new Plan(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Outcome.APP);
        }
        Intent store = playStore(ctx, s.pkg);
        return store != null ? new Plan(store, Outcome.STORE) : new Plan(null, Outcome.NOTHING);
    }

    /** The app's Play Store page, or null when there is no store on the TV. */
    @Nullable
    static Intent playStore(Context ctx, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i.resolveActivity(ctx.getPackageManager()) != null ? i : null;
    }

    /** A YouTube video in the YouTube app (trailers, Random Song), or in any app that can play it. */
    /**
     * The trailer to open for a title: the first of its trailers that YouTube still has (checked with YouTube's public
     * oEmbed page, 3 s each), else a YouTube search for "<title> <year> trailer", so it never opens on a blank page.
     * Runs network requests; call off the main thread.
     */
    static Intent trailer(Context ctx, Tmdb.Title t) {
        List<String> keys = new ArrayList<>(t.trailers);
        if (keys.isEmpty() && t.trailer != null) keys.add(t.trailer);
        for (String key : keys) {
            if (!key.matches("[A-Za-z0-9_\\-]{11}")) continue;
            try {
                Net.get("https://www.youtube.com/oembed?format=json&url=" + Uri.encode("https://www.youtube.com/watch?v=" + key),
                        null, 3000, 0);
                return youtube(ctx, key);
            } catch (IOException e) {
                // removed, private or not embeddable: try the next one
            }
        }
        String q = t.title + (t.year > 0 ? " " + t.year : "") + " trailer";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                .setPackage(Services.YOUTUBE_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(ctx.getPackageManager()) == null) i.setPackage(null);
        return i;
    }

    static Intent youtube(Context ctx, String videoId) {
        Uri u = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(Services.YOUTUBE_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(ctx.getPackageManager()) == null) i.setPackage(null);
        return i;
    }

}
