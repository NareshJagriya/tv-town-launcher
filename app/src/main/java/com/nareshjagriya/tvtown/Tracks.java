package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A film file's languages: the audio tracks and subtitle tracks inside it (read once and kept), subtitle files
 * next to it (needs All files access), subtitles downloaded for it, and what its name says ("Dual Audio",
 * "Hindi + English", "ESub"). Summed up in one line: "Dual audio: Hindi + English · English subs",
 * "Hindi (dubbed)", "Korean · no subtitles".
 */
final class Tracks {

    private Tracks() {
    }

    /** What a file has: audio and subtitle languages (two-letter codes), and whether any subtitles exist. */
    static final class Info {
        final Set<String> audio = new LinkedHashSet<>(), subs = new LinkedHashSet<>();
        boolean hasSubs;          // inside, next to it, downloaded, or the name says so
        boolean subsOutside;      // a subtitle file next to the film or downloaded

        JSONObject json() throws Exception {
            return new JSONObject().put("a", new JSONArray(audio)).put("s", new JSONArray(subs)).put("h", hasSubs)
                    .put("o", subsOutside);
        }

        static Info from(JSONObject o) {
            Info i = new Info();
            JSONArray a = o.optJSONArray("a"), s = o.optJSONArray("s");
            for (int k = 0; a != null && k < a.length(); k++) i.audio.add(a.optString(k));
            for (int k = 0; s != null && k < s.length(); k++) i.subs.add(s.optString(k));
            i.hasSubs = o.optBoolean("h");
            i.subsOutside = o.optBoolean("o");
            return i;
        }
    }

    private static final Map<String, String> CODES = new HashMap<>();

    static {
        String[][] c = {{"hin", "hi"}, {"eng", "en"}, {"mar", "mr"}, {"tam", "ta"}, {"tel", "te"}, {"mal", "ml"},
                {"kan", "kn"}, {"ben", "bn"}, {"guj", "gu"}, {"pan", "pa"}, {"urd", "ur"}, {"kor", "ko"}, {"jpn", "ja"},
                {"spa", "es"}, {"fra", "fr"}, {"fre", "fr"}, {"deu", "de"}, {"ger", "de"}, {"ita", "it"}, {"chi", "zh"},
                {"zho", "zh"}, {"rus", "ru"}, {"por", "pt"}, {"ara", "ar"}, {"tur", "tr"}, {"tha", "th"}};
        for (String[] x : c) CODES.put(x[0], x[1]);
    }

    /** "hin", "Hindi" or "hi" as "hi"; null for unknown ("und"). */
    @Nullable
    static String code(@Nullable String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT).replaceAll("[-_].*", "");
        if (l.isEmpty() || l.equals("und") || l.equals("unknown") || l.equals("mul") || l.equals("zxx")) return null;
        if (CODES.containsKey(l)) return CODES.get(l);
        if (l.length() == 2) return java.util.Arrays.asList(Locale.getISOLanguages()).contains(l) ? l : null;
        for (String two : Locale.getISOLanguages()) {
            if (new Locale(two).getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(l)) return two;
        }
        return null;
    }

    /** "hi" as "Hindi". */
    static String name(String code) {
        String n = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
        return n.isEmpty() ? code : n;
    }

    // ---- what the file name says ----------------------------------------------------------------------

    private static final Pattern LANG_WORD = Pattern.compile("(?i)\\b(hindi|english|marathi|tamil|telugu|malayalam|"
            + "kannada|bengali|gujarati|punjabi|korean|japanese|spanish|french)\\b");
    private static final Pattern SUBS_WORD = Pattern.compile("(?i)\\b(e-?subs?|eng[ ._-]?subs?|subbed|subtitles?|msubs?)\\b");

    /** Audio languages a file name lists: "[Hindi AAC 2.0 + English AAC 2.0]" gives hi, en. */
    static List<String> nameLanguages(String fileName) {
        List<String> out = new ArrayList<>();
        Matcher m = LANG_WORD.matcher(fileName);
        while (m.find()) {
            String c = code(m.group(1));
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }

    static boolean nameSaysSubs(String fileName) {
        return SUBS_WORD.matcher(fileName).find();
    }

    // ---- reading the file ------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("my_movies", Context.MODE_PRIVATE);
    }

    /** What a file has, read once and kept (the subtitle files next to it are looked for each time). */
    static Info of(Context ctx, MyMovies.Video v) {
        Info info = null;
        String kept = prefs(ctx).getString("tracks:" + v.key(), null);
        if (kept != null) {
            try {
                info = Info.from(new JSONObject(kept));
            } catch (Exception ignored) {
                // read again
            }
        }
        if (info == null) {
            info = read(ctx, v);
            try {
                prefs(ctx).edit().putString("tracks:" + v.key(), info.json().toString()).apply();
            } catch (Exception ignored) {
                // read again next time
            }
        }
        // Subtitle files next to the film, or downloaded: these can change at any time.
        List<File> files = subtitleFiles(ctx, v);
        if (!files.isEmpty() || downloaded(ctx, v) != null) {
            info.hasSubs = true;
            info.subsOutside = true;
            for (File f : files) {
                String c = subtitleLanguage(f.getName());
                info.subs.add(c != null ? c : "en");
            }
            String d = downloaded(ctx, v);
            if (d != null) {
                String c = subtitleLanguage(new File(d).getName());
                info.subs.add(c != null ? c : "en");
            }
        }
        return info;
    }

    /** The tracks inside the file (Android's media extractor), plus what the name says. Slow: off the main thread. */
    static Info read(Context ctx, MyMovies.Video v) {
        Info info = new Info();
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(ctx, v.uri, null);
            for (int i = 0; i < x.getTrackCount(); i++) {
                MediaFormat f = x.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                String lang = f.containsKey(MediaFormat.KEY_LANGUAGE) ? code(f.getString(MediaFormat.KEY_LANGUAGE)) : null;
                if (mime == null) continue;
                if (mime.startsWith("audio/")) {
                    if (lang != null) info.audio.add(lang);
                } else if (mime.startsWith("text/") || mime.contains("subrip") || mime.contains("subtitle")
                        || mime.contains("x-ssa") || mime.contains("ttml") || mime.contains("vtt")) {
                    info.hasSubs = true;
                    info.subs.add(lang != null ? lang : "en");
                }
            }
        } catch (Exception ignored) {
            // an unusual file: the name alone
        } finally {
            x.release();
        }
        if (info.audio.isEmpty()) info.audio.addAll(nameLanguages(v.name));
        if (!info.hasSubs && nameSaysSubs(v.name)) {   // "ESub" usually means English subtitles inside
            info.hasSubs = true;
            info.subs.add("en");
        }
        return info;
    }

    /** The language in a subtitle file's name ("Film.en.srt", "Film.Hindi.srt"); null when it does not say. */
    @Nullable
    static String subtitleLanguage(String fileName) {
        String[] parts = fileName.toLowerCase(Locale.ROOT).split("\\.");
        for (int i = parts.length - 2; i >= Math.max(1, parts.length - 3); i--) {
            String c = code(parts[i]);
            if (c != null) return c;
        }
        return null;
    }

    // ---- files next to the film --------------------------------------------------------------------

    static final String[] SUB_EXT = {".srt", ".ass", ".ssa", ".vtt", ".sub"};

    /** True when TV Town may read and write any file (All files access, Android 11 and later). */
    static boolean allFiles() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    /** The folder a film is in, on the drive; null without All files access or when the drive is not found. */
    @Nullable
    static File folder(Context ctx, MyMovies.Video v) {
        if (!allFiles() || v.rel == null || Build.VERSION.SDK_INT < 30) return null;
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null) return null;
        for (StorageVolume vol : sm.getStorageVolumes()) {
            if (!v.volume.equalsIgnoreCase(vol.getMediaStoreVolumeName()) || vol.getDirectory() == null) continue;
            File dir = new File(vol.getDirectory(), v.rel);
            return dir.isDirectory() ? dir : null;
        }
        return null;
    }

    /** Subtitle files next to a film: those named after it, or any when it is the only film in its folder. */
    // A film folder's files, kept for ten minutes: listing all of them on a USB hard disk each time My Movies
    // opens took seconds. TV Town forgets a folder at once when it saves a subtitle there (forget).
    private static final java.util.Map<String, File[]> listed = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> listedAt = new java.util.concurrent.ConcurrentHashMap<>();

    @androidx.annotation.Nullable
    private static File[] list(File dir) {
        String k = dir.getPath();
        Long at = listedAt.get(k);
        if (at != null && System.currentTimeMillis() - at < 10 * 60_000) return listed.get(k);
        File[] files = dir.listFiles();
        if (files != null) {
            listed.put(k, files);
            listedAt.put(k, System.currentTimeMillis());
        }
        return files;
    }

    /** A subtitle was saved next to the film: its folder is looked at again. */
    static void forget(Context ctx, MyMovies.Video v) {
        File dir = folder(ctx, v);
        if (dir != null) listedAt.remove(dir.getPath());
    }

    static List<File> subtitleFiles(Context ctx, MyMovies.Video v) {
        List<File> out = new ArrayList<>();
        File dir = folder(ctx, v);
        File[] files = dir != null ? list(dir) : null;
        if (files == null) return out;
        String base = v.name.replaceAll("\\.[^.]+$", "").toLowerCase(Locale.ROOT);
        int videos = 0;
        for (File f : files) if (f.getName().matches("(?i).*\\.(mkv|mp4|avi|m4v|mov|webm|ts|wmv)$")) videos++;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            boolean sub = false;
            for (String e : SUB_EXT) if (n.endsWith(e)) sub = true;
            if (sub && !n.startsWith("._") && (n.startsWith(base) || videos <= 1)) out.add(f);
        }
        return out;
    }

    /** A subtitle downloaded for this film (its path), or null. */
    @Nullable
    static String downloaded(Context ctx, MyMovies.Video v) {
        String p = prefs(ctx).getString("subs:" + v.key(), null);
        return p != null && new File(p).exists() ? p : null;
    }

    static void setDownloaded(Context ctx, MyMovies.Video v, @Nullable String path) {
        if (path == null) prefs(ctx).edit().remove("subs:" + v.key()).apply();
        else prefs(ctx).edit().putString("subs:" + v.key(), path).apply();
    }

    // ---- the summary -------------------------------------------------------------------------------

    /** Where a film is from, by its original language: Hollywood, Bollywood, Korean… */
    static String origin(@Nullable String code) {
        if (code == null || code.isEmpty()) return "";
        switch (code) {
            case "en": return "Hollywood";
            case "hi": case "ur": return "Bollywood";
            case "mr": return "Marathi";
            case "ta": case "te": case "ml": case "kn": return "South Indian";
            case "bn": case "gu": case "pa": case "or": case "as": return "Other Indian";
            case "ko": return "Korean";
            case "ja": return "Japanese";
            case "zh": case "cn": return "Chinese";   // TMDB says "cn" for Cantonese
            case "fr": case "es": case "de": case "it": case "pt": case "nl": case "sv": case "da": case "no": case "fi":
            case "pl": case "ru": case "el": case "cs": case "hu": case "ro": case "is": case "tr":
                return "European";
            default: return "Other";
        }
    }

    /** A film's line: where it is from, its audio languages and its subtitles. */
    static final class Label {
        String text = "", origin = "";
        boolean foreignNoSubs;                            // nothing understood and no subtitles
        final List<String> audio = new ArrayList<>(), subs = new ArrayList<>();   // two-letter codes, main first
        boolean hasSubs;
    }

    /**
     * "Hollywood · Audio: English, Hindi · Subs: English". original is TMDB's original language (or "");
     * `understood` the languages that need no subtitles (Hindi, English, Marathi).
     */
    static Label label(String original, Info info, List<String> understood) {
        Label l = new Label();
        l.audio.addAll(info.audio);
        if (l.audio.isEmpty() && original != null && !original.isEmpty()) l.audio.add(original);
        l.subs.addAll(info.subs);
        l.hasSubs = info.hasSubs;
        l.origin = origin(original != null && !original.isEmpty() ? original : l.audio.isEmpty() ? null : l.audio.get(0));
        List<String> parts = new ArrayList<>();
        if (!l.origin.isEmpty()) parts.add(l.origin);
        if (!l.audio.isEmpty()) parts.add("Audio: " + names(l.audio));
        if (l.hasSubs) parts.add(l.subs.isEmpty() ? "Subs" : "Subs: " + names(l.subs.size() > 2 ? l.subs.subList(0, 2) : l.subs)
                + (l.subs.size() > 2 ? " +" + (l.subs.size() - 2) : ""));
        else if (!l.audio.isEmpty()) parts.add("No subs");
        boolean understoodAudio = false;
        for (String a : l.audio) if (understood.contains(a)) understoodAudio = true;
        l.foreignNoSubs = !l.audio.isEmpty() && !understoodAudio && !l.hasSubs;
        l.text = String.join("  ·  ", parts);
        return l;
    }

    private static String names(List<String> codes) {
        List<String> out = new ArrayList<>();
        for (String c : codes) out.add(name(c));
        return String.join(", ", out);
    }
}
