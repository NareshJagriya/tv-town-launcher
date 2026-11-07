package com.nareshjagriya.tvtown;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Award winners, from Wikidata: films that won (in any category) an Academy Award, Filmfare Award, National Film
 * Award, BAFTA or Golden Globe, or the Palme d'Or, Golden Bear or Golden Lion; and series that won an Emmy or Golden
 * Globe; by TMDB id. Fetched once a month (a few hundred kilobytes) and kept; older copies are used when Wikidata is
 * slow or offline.
 */
final class Awards {

    private Awards() {
    }

    private static final long MONTH = 30L * 24 * 60 * 60 * 1000, YEAR = 12 * MONTH;

    static final String FILM_QUERY = "SELECT DISTINCT ?tmdb WHERE { { ?film wdt:P166 ?award . ?award wdt:P31|wdt:P361 ?kind . "
            + "VALUES ?kind { wd:Q19020 wd:Q37049 wd:Q1350697 wd:Q732997 wd:Q1011547 } } UNION { ?film wdt:P166 ?award . "
            + "VALUES ?award { wd:Q179808 wd:Q154590 wd:Q209459 } } ?film wdt:P4947 ?tmdb . }";
    static final String SERIES_QUERY = "SELECT DISTINCT ?tmdb WHERE { ?show wdt:P166 ?award . ?award wdt:P31|wdt:P361 ?kind . "
            + "VALUES ?kind { wd:Q1044427 wd:Q1011547 } ?show wdt:P4983 ?tmdb . }";

    private static volatile Set<String> keys;

    /** "movie:123" and "tv:456" keys of award winners; empty until loaded. Runs network requests the first time. */
    static Set<String> keys(Context ctx) {
        Set<String> k = keys;
        if (k != null) return k;
        Set<String> out = new HashSet<>();
        load(ctx, FILM_QUERY, "movie", out);
        load(ctx, SERIES_QUERY, "tv", out);
        keys = out;
        return out;
    }

    /** Cached so far (no network); null when not loaded yet. */
    static Set<String> loaded() {
        return keys;
    }

    private static void load(Context ctx, String query, String type, Set<String> out) {
        try {
            String url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/sparql-results+json");
            h.put("User-Agent", "TVTownLauncher/" + BuildConfig.VERSION_NAME + " (Android TV launcher)");
            parse(Net.cached(ctx, url, h, MONTH, YEAR, 60_000), type, out);
        } catch (Exception ignored) {
            // offline and nothing cached: no badges this time
        }
    }

    static void parse(String json, String type, Set<String> out) throws Exception {
        JSONArray rows = new JSONObject(json).getJSONObject("results").getJSONArray("bindings");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject v = rows.getJSONObject(i).optJSONObject("tmdb");
            String id = v != null ? v.optString("value") : "";
            if (id.matches("[1-9]\\d{0,8}")) out.add(type + ":" + id);
        }
    }
}
