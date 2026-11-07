package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Photos of singers for My Music's cards, from Wikipedia (the page's own picture; only pages about a singer, musician
 * or band are used). A folder's card shows the great voices of its kind of music (Gazals: Jagjit Singh, Ghulam Ali,
 * Nusrat Fateh Ali Khan, Mehdi Hassan), else the singers it has most songs by. Answers are kept for half a year.
 */
final class ArtistPhotos {

    private ArtistPhotos() {
    }

    private static final Map<String, String> HEADERS = Collections.singletonMap("User-Agent",
            "TV Town Launcher " + BuildConfig.VERSION_NAME + " (Android TV home screen)");

    /** The voices that stand for a kind of music, by words in a folder's name, best known first; empty when none fits. */
    static List<String> voicesFor(String folder) {
        String n = folder == null ? "" : folder.toLowerCase(Locale.ROOT);
        if (n.matches(".*(gazal|ghazal).*")) return Arrays.asList("Jagjit Singh", "Ghulam Ali (singer)", "Mehdi Hassan", "Nusrat Fateh Ali Khan",
                "Pankaj Udhas", "Chitra Singh", "Talat Aziz", "Farida Khanum");
        if (n.matches(".*(sufi|qawwal).*")) return Arrays.asList("Nusrat Fateh Ali Khan", "Abida Parveen", "Rahat Fateh Ali Khan", "Kailash Kher",
                "Wadali Brothers", "Sabri Brothers");
        if (n.matches(".*(old|retro|classic|purane).*")) return Arrays.asList("Kishore Kumar", "Lata Mangeshkar", "Mohammed Rafi", "Asha Bhosle",
                "Mukesh (singer)", "Manna Dey", "Hemant Kumar", "Geeta Dutt");
        if (n.matches(".*(silent|calm|soft|sleep|peace|sad|romantic).*")) return Arrays.asList("Arijit Singh", "Shreya Ghoshal", "Rahat Fateh Ali Khan",
                "Atif Aslam", "Shafqat Amanat Ali", "Sonu Nigam", "KK (singer)", "Jubin Nautiyal");
        if (n.matches(".*(unplug|acoustic).*")) return Arrays.asList("Mohit Chauhan", "Papon (singer)", "Lucky Ali", "Prateek Kuhad", "Shankar Tucker",
                "Jonita Gandhi", "Harshdeep Kaur", "Amit Trivedi", "Vishal Mishra", "Armaan Malik");
        if (n.matches(".*(english|western).*")) return Arrays.asList("Ed Sheeran", "Adele", "Eminem", "Lady Gaga", "Taylor Swift", "Bruno Mars",
                "Coldplay", "Rihanna");
        if (n.matches(".*marathi.*")) return Arrays.asList("Ajay–Atul", "Shankar Mahadevan", "Asha Bhosle", "Sonu Nigam", "Bela Shende",
                "Swapnil Bandodkar", "Avadhoot Gupte");
        if (n.matches(".*punjabi.*")) return Arrays.asList("Diljit Dosanjh", "Gurdas Maan", "Sidhu Moose Wala", "Jasmine Sandlas", "Guru Randhawa");
        if (n.matches(".*(new|latest|hits|party|dance).*")) return Arrays.asList("Neha Kakkar", "Badshah (rapper)", "Pritam", "Sunidhi Chauhan",
                "Vishal Dadlani", "Shaan (singer)", "Darshan Raval", "Guru Randhawa");
        if (n.matches(".*(bhajan|devotion|medit|mantra).*")) return Arrays.asList("Anup Jalota", "Hariharan (singer)", "Anuradha Paudwal", "Jagjit Singh");
        return Collections.emptyList();
    }

    // Well-known voices to fill a card with when its own kind has none left.
    private static final List<String> MORE = Arrays.asList("Udit Narayan", "Alka Yagnik", "Kumar Sanu", "Sunidhi Chauhan", "A. R. Rahman",
            "Hariharan (singer)", "Javed Ali", "Shaan (singer)", "Kavita Krishnamurthy", "Sukhwinder Singh");

    /**
     * Singers for each folder's card, never the same singer on two cards: its own kind's voices first, then the
     * singers it has most songs by, then other well-known voices; up to six names each (spares for missing photos).
     */
    static Map<String, List<String>> assign(List<MyMusic.Group> folders) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (MyMusic.Group g : folders) {
            List<String> pick = new ArrayList<>();
            List<String> candidates = new ArrayList<>(voicesFor(g.name));
            for (String a : topSingers(g)) if (candidates.size() < 12) candidates.add(a);
            candidates.addAll(MORE);
            for (String n : candidates) {
                String key = n.toLowerCase(Locale.ROOT).replaceAll("\\s*\\(.*\\)", "");
                if (pick.size() >= 6 || used.contains(key)) continue;
                used.add(key);
                pick.add(n);
            }
            out.put(g.name, pick);
        }
        return out;
    }

    private static List<String> topSingers(MyMusic.Group g) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (MyMusic.Song s : g.songs) {
            String a = MyMusic.mainArtist(s.artist);
            if (!a.isEmpty()) count.merge(a, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> e = new ArrayList<>(count.entrySet());
        Collections.sort(e, (x, y) -> y.getValue() - x.getValue());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> x : e) if (x.getValue() >= 2) out.add(x.getKey());
        return out;
    }

    /**
     * A singer's photo (https, about 500 px wide), or null. Looked up once per drive (".tvtown/singers.json").
     * Network: not on the main thread.
     */
    @Nullable
    static String photo(Context ctx, String name) {
        if (name == null || name.equals(MyMusic.OTHERS) || name.length() < 3) return null;
        java.io.File drive = DriveStore.anyDrive(ctx);
        JSONObject known = DriveStore.map(drive, "singers");
        if (known.has(name)) {   // looked up before (kept on the drive): "" means no photo
            String u = known.optString(name);
            return u.isEmpty() ? null : u;
        }
        String url = page(ctx, name);
        if ("".equals(url) && !name.contains("(")) url = page(ctx, name + " (singer)");
        if (url == null) return null;   // Wikipedia not reached: asked again another time
        try {
            known.put(name, url);
            DriveStore.save(drive, "singers");
        } catch (Exception ignored) {
        }
        return url.isEmpty() ? null : url;
    }

    @Nullable
    private static String page(Context ctx, String title) {
        String u = "https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(title.replace(' ', '_'));
        String body = null;
        for (int attempt = 0; attempt < 4 && body == null; attempt++) {   // Wikipedia asks to slow down now and then
            try {
                body = Net.text(Net.get(u, HEADERS, 12_000, 1));   // the answer is kept on the drive, not the TV
            } catch (Net.Failure e) {
                if (e.status == 404) return "";   // no such page
                try {
                    Thread.sleep(1500L << attempt);
                } catch (InterruptedException ie) {
                    return null;
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(1500L << attempt);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            if (!"standard".equals(o.optString("type"))) return "";
            String about = (o.optString("description") + " " + o.optString("extract")).toLowerCase(Locale.ROOT);
            if (!about.matches("(?s).*\\b(singer|musician|composer|rapper|band|vocalist|qawwal|ghazal|songwriter|duo|music director|playback)\\b.*")) return "";
            JSONObject t = o.optJSONObject("thumbnail");
            String src = t != null ? t.optString("source", "") : "";
            if (!src.startsWith("https://")) return "";
            return src.replaceAll("/\\d+px-", "/500px-");   // Wikimedia serves only its standard widths (330, 500, 960…)
        } catch (Exception e) {
            return "";
        }
    }
}
