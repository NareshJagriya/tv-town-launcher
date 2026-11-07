package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Find details for a song: matching songs from the iTunes catalogue (free, no key), each with its proper name,
 * artist, genre and cover, to pick from when fixing a song's details. Indian store first, so Hindi film songs are
 * found under their film.
 */
final class SongInfo {

    private SongInfo() {
    }

    static final class Found {
        String title, artist, album, genre, cover;
        long durationMs;
    }

    /** Up to eight matches for words like "Tere Naina Shafqat"; empty when offline or nothing matches. Network. */
    static List<Found> search(Context ctx, String words) {
        List<Found> out = new ArrayList<>();
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=8&country=IN&term=" + Uri.encode(words);
        try {
            JSONArray r = new JSONObject(Net.cached(ctx, url, null, 7L * 24 * 3600_000, 30L * 24 * 3600_000, 12_000))
                    .optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                Found f = new Found();
                f.title = o.optString("trackName");
                f.artist = o.optString("artistName");
                f.album = o.optString("collectionName");
                f.genre = o.optString("primaryGenreName");
                f.durationMs = o.optLong("trackTimeMillis");
                String art = o.optString("artworkUrl100", "");
                f.cover = art.startsWith("https://") ? art.replace("100x100bb", "600x600bb") : null;
                if (!f.title.isEmpty()) out.add(f);
            }
        } catch (Exception ignored) {
            // offline or nothing found
        }
        return out;
    }
}
