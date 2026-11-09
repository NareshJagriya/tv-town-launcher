package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tidies each song from the iTunes catalogue, once: its proper name, the film or album it is from, who sings it, its
 * genre and a good cover. A match is only taken when it is the same recording (the same length, give or take four
 * seconds), or the same song from the film the file names; otherwise the cleaned file name stays. Runs in the
 * background a song every three seconds (the catalogue's limit) and is kept on the song's drive (".tvtown/songs.json",
 * see DriveStore; the TV only when the drive cannot be written); your own edits still win.
 */
final class SongMatch {

    private SongMatch() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("my_music_match", Context.MODE_PRIVATE);
    }

    /** The song's entry in its drive's list: {"m": match} or {"miss": time}; null when not looked up. */
    private static JSONObject entry(Context ctx, MyMusic.Song s) {
        JSONObject list = DriveStore.map(DriveStore.volumeOf(s.path), "songs");
        JSONObject e = list.optJSONObject(s.key());
        if (e != null) return e;
        // Kept on the TV by an earlier version: moved to the drive.
        SharedPreferences p = prefs(ctx);
        String old = p.getString("m:" + s.key(), null);
        long miss = p.getLong("miss:" + s.key(), 0);
        if (old == null && miss == 0) return null;
        try {
            e = old != null ? new JSONObject().put("m", new JSONObject(old)) : new JSONObject().put("miss", miss);
            java.io.File vol = DriveStore.volumeOf(s.path);
            if (vol != null) {
                DriveStore.map(vol, "songs").put(s.key(), e);
                synchronized (moved) {
                    moved.add(s.key());   // taken off the TV once the drive's list is written (flush)
                }
            }
        } catch (Exception ignored) {
        }
        return e;
    }

    /** Saves an entry on the song's drive; false (and kept on the TV instead) when the drive cannot be written. */
    private static boolean store(MyMusic.Song s, JSONObject e) {
        java.io.File vol = DriveStore.volumeOf(s.path);
        try {
            DriveStore.map(vol, "songs").put(s.key(), e);
        } catch (Exception ignored) {
            return false;
        }
        return pendingSave(vol);
    }

    // The list is written at most every few seconds (not after every song).
    private static long lastSave;

    private static synchronized boolean pendingSave(java.io.File vol) {
        if (System.currentTimeMillis() - lastSave < 5000) return vol != null;
        lastSave = System.currentTimeMillis();
        return DriveStore.save(vol, "songs");
    }

    private static final java.util.Set<String> moved = new java.util.HashSet<>();

    /** Writes what is waiting to the drive, then lets go of the TV's copies of what was moved there. */
    static void flush(Context ctx) {
        synchronized (moved) {
            if (moved.isEmpty()) return;   // nothing new: the drive isn't touched
        }
        java.io.File vol = DriveStore.anyDrive(ctx);
        if (vol == null || !DriveStore.save(vol, "songs")) return;
        synchronized (moved) {
            if (moved.isEmpty()) return;
            SharedPreferences.Editor ed = prefs(ctx).edit();
            for (String key : moved) ed.remove("m:" + key).remove("miss:" + key);
            ed.apply();
            moved.clear();
        }
    }

    /** Puts a saved match on a freshly scanned song. */
    static void apply(Context ctx, MyMusic.Song s) {
        JSONObject e = entry(ctx, s);
        JSONObject o = e != null ? e.optJSONObject("m") : null;
        if (o == null) return;
        try {
            s.title = balanced(o.optString("title", s.title));
            s.album = balanced(o.optString("album", s.album));
            s.artist = o.optString("artist", s.artist);
            if (!o.optString("genre").isEmpty()) s.genre = o.optString("genre");
            String cover = o.optString("cover");
            if (cover.startsWith("https://")) s.cover = cover;
            s.matched = true;
        } catch (Exception ignored) {
            // as cleaned
        }
    }

    /** Not looked up yet (or not found more than a month ago). */
    static boolean pending(Context ctx, MyMusic.Song s) {
        JSONObject e = entry(ctx, s);
        if (e == null) return true;
        if (e.has("m")) return false;
        if (!(s.title + "|" + s.artist).equals(e.optString("asked"))) return true;   // its name is cleaner now: again
        return System.currentTimeMillis() - e.optLong("miss", 0) > 30L * 24 * 3600_000;
    }

    /** Looks one song up (network; call off the main thread). True when it was matched and saved. */
    static boolean match(Context ctx, MyMusic.Song s) {
        List<JSONObject> found = new ArrayList<>();
        String artist = MyMusic.mainArtist(s.artist);
        if (!artist.isEmpty()) found = search(ctx, s.title + " " + artist);
        if (found == null) return false;   // offline: tried again next time
        JSONObject pick = best(found, s);
        if (pick == null && !s.album.isEmpty()) {
            List<JSONObject> more = search(ctx, s.title + " " + s.album);
            if (more != null) pick = best(more, s);
        }
        if (pick == null) {
            List<JSONObject> more = search(ctx, s.title);
            if (more == null) return false;
            pick = best(more, s);
        }
        if (pick == null) {
            try {
                JSONObject e = new JSONObject().put("miss", System.currentTimeMillis()).put("asked", s.title + "|" + s.artist);
                if (!store(s, e) && DriveStore.volumeOf(s.path) == null)
                    prefs(ctx).edit().putLong("miss:" + s.key(), System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
            }
            return false;
        }
        try {
            String[] t = titleAndFilm(pick.optString("trackName"));
            String album = t[1].isEmpty() ? albumName(pick.optString("collectionName")) : t[1];
            String art = pick.optString("artworkUrl100", "");
            JSONObject o = new JSONObject().put("title", t[0]).put("album", album)
                    .put("artist", pick.optString("artistName")).put("genre", genre(pick.optString("primaryGenreName")))
                    .put("cover", art.startsWith("https://") ? art.replace("100x100bb", "600x600bb") : "");
            if (!store(s, new JSONObject().put("m", o)) && DriveStore.volumeOf(s.path) == null)
                prefs(ctx).edit().putString("m:" + s.key(), o.toString()).apply();
            apply(ctx, s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Null when the catalogue could not be reached. */
    private static List<JSONObject> search(Context ctx, String words) {
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=15&country=IN&term=" + Uri.encode(words.trim());
        try {
            // Not kept on the TV: the match itself is what is saved (on the drive).
            JSONArray r = new JSONObject(Net.text(Net.get(url, null, 12_000, 1)))
                    .optJSONArray("results");
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; r != null && i < r.length(); i++) out.add(r.getJSONObject(i));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The same recording: a title that agrees and the same length (within four seconds), the original film
     * soundtrack first; or, when the length differs, the same title from the film the file itself names.
     */
    private static JSONObject best(List<JSONObject> found, MyMusic.Song s) {
        String want = MyMusic.squash(s.title);
        if (want.length() < 3) return null;
        JSONObject pick = null;
        int pickScore = Integer.MAX_VALUE;
        for (JSONObject o : found) {
            String name = MyMusic.squash(titleAndFilm(o.optString("trackName"))[0]);
            if (name.isEmpty() || !(name.startsWith(want) || want.startsWith(name) || name.contains(want))) continue;
            if (o.optString("trackName").matches("(?i).*\\b(lofi|lo-fi|remix|mashup|reprise|karaoke|instrumental|cover)\\b.*")
                    && !s.title.matches("(?i).*\\b(lofi|lo-fi|remix|mashup|reprise|karaoke|instrumental|cover)\\b.*")) continue;
            long diff = Math.abs(o.optLong("trackTimeMillis") - s.durationMs) / 1000;
            boolean sameFilm = !s.album.isEmpty() && MyMusic.squash(o.optString("collectionName")).contains(MyMusic.squash(s.album))
                    || !s.artist.isEmpty() && MyMusic.squash(o.optString("collectionName")).contains(MyMusic.squash(s.artist));
            if (s.durationMs > 0 && diff > 4 && !sameFilm) continue;
            int score = (int) Math.min(diff, 60) * 10;
            String coll = o.optString("collectionName");
            if (!coll.matches("(?i).*(motion picture|soundtrack).*")) score += 15;   // the film's own album first
            if (coll.matches("(?i).*(hits|best of|collection|greatest|super hit|retro|unplugged|top \\d+).*")) score += 25;
            if (score < pickScore) {
                pickScore = score;
                pick = o;
            }
        }
        return pick;
    }

    private static final Pattern FROM = Pattern.compile("\\s*[(\\[](?:From|from)\\s+\"([^\"]+)\"[)\\]]");

    /** "Tere Naina (From "My Name Is Khan")" or "… [From "Life in a Metro"]" gives {"Tere Naina", "My Name Is Khan"}. */
    static String[] titleAndFilm(String track) {
        Matcher m = FROM.matcher(track);
        if (m.find()) return new String[]{balanced(track.substring(0, m.start()) + track.substring(m.end())), balanced(m.group(1))};
        return new String[]{balanced(track), ""};
    }

    /** Without brackets left open or closed on their own ("Life in a Metro)" gives "Life in a Metro"). */
    static String balanced(String s) {
        StringBuilder b = new StringBuilder();
        int round = 0, square = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '(') round++;
            else if (ch == '[') square++;
            else if (ch == ')' && round == 0 || ch == ']' && square == 0) continue;
            else if (ch == ')') round--;
            else if (ch == ']') square--;
            b.append(ch);
        }
        String out = b.toString();
        if (round > 0 || square > 0) out = out.replaceAll("\\s*[(\\[][^)\\]]*$", "");
        return out.replaceAll("\\s+", " ").trim();
    }

    /** "Jab We Met (Original Motion Picture Soundtrack) [Deluxe Edition]" gives "Jab We Met". */
    static String albumName(String c) {
        return c.replaceAll("(?i)\\s*[(\\[][^)\\]]*(soundtrack|edition|version|remaster|ep|single)[^)\\]]*[)\\]]", "")
                .replaceAll("(?i)\\s+-\\s+(ep|single)$", "").trim();
    }

    /** iTunes' "Bollywood", "Ghazal" and the like, as is; nothing for the catalogue's catch-alls. */
    private static String genre(String g) {
        return g == null || g.toLowerCase(Locale.ROOT).matches("music|world|soundtrack|other") ? "" : g;
    }
}
