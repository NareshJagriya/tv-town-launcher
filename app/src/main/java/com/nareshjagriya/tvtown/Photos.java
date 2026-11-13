package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Free pictures for the screensaver, no account needed: Wikimedia Commons' Picture of the Day (the last three
 * weeks, freely licensed, credited on screen) and NASA's Image Library (space and Earth, public domain). Fetched at
 * most once a day, in the background, and kept, so the screensaver starts at once.
 */
final class Photos {

    static final int WORLD = 3, SPACE = 4;   // TvTownDream kinds
    private static final long DAY = 24 * 3600_000L;

    private Photos() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("photos", Context.MODE_PRIVATE);
    }

    /** The kept pictures for a kind: each its address and the line shown with it. */
    static List<String[]> kept(Context c, int kind) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("k" + kind, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String u = a.getJSONObject(i).getString("u");
                if (u.contains("?")) continue;   // kept by 1.0's first build, before the tracking tail was cut: fetched again
                out.add(new String[]{u, a.getJSONObject(i).getString("n")});
            }
        } catch (Exception ignored) {
            // none yet
        }
        java.util.Collections.shuffle(out);
        return out;
    }

    /** Fetches the chosen kind's pictures when a day has passed (or never fetched). Off the main thread. */
    static void refresh(Context ctx, int kind) {
        if (kind != WORLD && kind != SPACE) return;
        Context c = ctx.getApplicationContext();
        if (System.currentTimeMillis() - prefs(c).getLong("at" + kind, 0) < DAY && !kept(c, kind).isEmpty()) return;
        new Thread(() -> {
            JSONArray out = new JSONArray();
            try {
                if (kind == SPACE) space(c, out);
                else world(c, out);
            } catch (Exception e) {
                android.util.Log.w("TVTown", "photos " + kind + ": " + e);
            }
            if (out.length() > 0) prefs(c).edit().putString("k" + kind, out.toString()).putLong("at" + kind, System.currentTimeMillis()).apply();
        }, "photos").start();
    }

    /** Space: NASA's Image Library (public domain, no key), a few searches; each picture at the largest size kept. */
    private static void space(Context c, JSONArray out) throws Exception {
        String[] topics = {"hubble nebula", "webb galaxy", "earth from space", "aurora from space", "saturn cassini", "milky way"};
        java.util.List<String> seen = new ArrayList<>();
        for (String t : topics) {
            if (out.length() >= 24) break;
            JSONArray items;
            try {
                items = new JSONObject(Net.cached(c, "https://images-api.nasa.gov/search?media_type=image&page_size=12&q=" + android.net.Uri.encode(t),
                        null, 7 * DAY, 30 * DAY, 15_000)).getJSONObject("collection").getJSONArray("items");
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; i < items.length() && out.length() < 24; i++) {
                JSONObject d = items.getJSONObject(i).getJSONArray("data").getJSONObject(0);
                String id = d.optString("nasa_id"), title = d.optString("title");
                if (id.isEmpty() || seen.contains(id)) continue;
                seen.add(id);
                try {   // its files: the large copy, else the medium one (originals can be huge)
                    JSONArray files = new JSONObject(Net.cached(c, "https://images-api.nasa.gov/asset/" + android.net.Uri.encode(id),
                            null, 30 * DAY, 30 * DAY, 15_000)).getJSONObject("collection").getJSONArray("items");
                    String large = null, medium = null;
                    for (int f = 0; f < files.length(); f++) {
                        String h = files.getJSONObject(f).optString("href").replace("http://", "https://");
                        if (h.endsWith("~large.jpg")) large = h;
                        else if (h.endsWith("~medium.jpg")) medium = h;
                    }
                    String url = large != null ? large : medium;
                    if (url == null || !url.startsWith("https://images-assets.nasa.gov/")) continue;
                    out.put(new JSONObject().put("u", url).put("n", title + "  \u00B7  NASA"));
                } catch (Exception ignored) {
                    // that one: left out
                }
            }
        }
    }

    private static void world(Context c, JSONArray out) throws Exception {
        Calendar d = Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
        for (int i = 0; i < 21 && out.length() < 21; i++) {
            String day = f.format(d.getTime());
            d.add(Calendar.DAY_OF_YEAR, -1);
            try {   // each day's answer never changes: kept for a month
                JSONObject img = new JSONObject(Net.cached(c, "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/" + day,
                        null, 30 * DAY, 30 * DAY, 15_000)).optJSONObject("image");
                if (img == null) continue;
                JSONObject orig = img.optJSONObject("image");
                String src = orig == null ? "" : orig.optString("source");
                if (src.contains("?")) src = src.substring(0, src.indexOf('?'));   // Wikimedia adds "?utm_source=…" now
                int w = orig == null ? 0 : orig.optInt("width");
                if (!src.startsWith("https://upload.wikimedia.org/") || w < 1600 || orig.optInt("height") > w) continue;   // wide, sharp ones
                String url = src.contains("/commons/") && w > 1920
                        ? src.replace("/commons/", "/commons/thumb/") + "/1920px-" + src.substring(src.lastIndexOf('/') + 1) : src;
                JSONObject desc = img.optJSONObject("description");
                String text = desc == null ? "" : desc.optString("text").replaceAll("<[^>]*>", "").trim();
                if (text.length() > 90) text = text.substring(0, text.lastIndexOf(' ', 90) > 40 ? text.lastIndexOf(' ', 90) : 90) + "…";
                String artist = img.optJSONObject("artist") == null ? "" : img.getJSONObject("artist").optString("text").replaceAll("<[^>]*>", "").trim();
                out.put(new JSONObject().put("u", url).put("n", text + "  ·  " + (artist.isEmpty() ? "" : artist + ", ") + "Wikimedia Commons"));
            } catch (Exception ignored) {
                // that day: left out
            }
        }
    }
}
