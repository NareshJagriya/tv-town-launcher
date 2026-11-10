package com.nareshjagriya.tvtown;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * My Movies: the videos on a plugged-in pen drive, from the TV's media index (it scans a drive when it is plugged
 * in). File names are cleaned ("The.Pianist.2002.1080p.BrRip.x264" becomes "The Pianist", 2002; "Show.S01E02" an
 * episode) and matched to TMDB, with the IMDb rating from JustWatch; matches, resume points and watched episodes are
 * kept on the TV so the drive works offline after the first time.
 */
final class MyMovies {

    private MyMovies() {
    }

    // ---- the drive -------------------------------------------------------------------------------

    /** True while a removable drive (pen drive, USB disk) is mounted. */
    static boolean driveMounted(Context ctx) {
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null) return false;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) return true;
        }
        return false;
    }

    /** One video file on the drive. */
    static final class Video {
        long id, size, added, durationMs;
        String volume, name, folder, rel;   // rel: the folder's path on the drive (L10n.t("Movies/The Pianist (2002)/"))
        Uri uri;

        /** Stays the same while the file does (name and size), across drives and re-plugs. */
        String key() {
            return name + ":" + size;
        }
    }

    private static final Pattern SKIP = Pattern.compile("(?i)^\\._|^sample[ ._-]|[ ._-]sample\\.|^sample\\.");

    /**
     * Every video on removable drives, from the media index. Leaves out macOS "._" files, samples and clips under
     * 30 MB. Needs the storage permission; empty without it.
     */
    static List<Video> scan(Context ctx) {
        return scan(ctx, false);
    }

    /** With saved: only the videos in the downloader's folder, however short (they're never films, so never in the rest). */
    static List<Video> scan(Context ctx, boolean saved) {
        java.io.File savedTo = YtSaver.folder(ctx, true);
        List<Video> out = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 29) return out;   // drives by name: Android 10 and later
        ContentResolver cr = ctx.getContentResolver();
        for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
            if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;   // the TV's own storage
            Uri base = MediaStore.Video.Media.getContentUri(vol);
            String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH};
            try (Cursor c = cr.query(base, cols, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Video v = new Video();
                    v.id = c.getLong(0);
                    v.name = c.getString(1);
                    v.size = c.getLong(2);
                    v.added = c.getLong(3);
                    v.durationMs = c.getLong(4);
                    String rel = c.getString(5);
                    if (v.name == null || SKIP.matcher(v.name).find()) continue;
                    if (saved != YtSaver.inside(savedTo, "/storage/" + vol + "/" + rel)) continue;
                    if (!saved && v.size > 0 && v.size < 30L * 1024 * 1024 && v.durationMs < 10 * 60_000) continue;
                    if (ownFolder(rel)) continue;   // My Music's backgrounds, the screensaver's videos, TV Town's own files
                    v.volume = vol;
                    v.folder = folderName(rel);
                    v.rel = rel;
                    v.uri = android.content.ContentUris.withAppendedId(base, v.id);
                    out.add(v);
                }
            } catch (Exception ignored) {
                // the drive went away mid-scan, or no permission
            }
        }
        return out;
    }

    /** The launcher's own folders on a drive ("TV Town Scenes", "TV Town Screensaver", ".tvtown"): never films. */
    static boolean ownFolder(@Nullable String rel) {
        if (rel == null) return false;
        String r = "/" + rel.toLowerCase(Locale.ROOT);
        return r.contains("/tv town scenes/") || r.contains("/tv town screensaver/") || r.contains("/.tvtown/");
    }

    /** The last folder of a relative path ("Movies/The Pianist (2002)/" gives "The Pianist (2002)"); "" at the top. */
    static String folderName(@Nullable String rel) {
        if (rel == null) return "";
        String r = rel.replaceAll("/+$", "");
        int i = r.lastIndexOf('/');
        return i >= 0 ? r.substring(i + 1) : r;
    }

    /** The folder above that, for "Show/Season 1/episode.mkv". */
    static String parentFolder(@Nullable String rel) {
        if (rel == null) return "";
        String r = rel.replaceAll("/+$", "");
        int i = r.lastIndexOf('/');
        return i >= 0 ? folderName(r.substring(0, i)) : "";
    }

    // ---- file names ------------------------------------------------------------------------------

    /** What a file name says: a movie (title, year) or an episode (show, season, episode). */
    static final class Parsed {
        String title = "";
        int year, season, episode;
        boolean series;
    }

    private static final Pattern EXT = Pattern.compile("\\.[A-Za-z0-9]{2,4}$");
    private static final Pattern EPISODE = Pattern.compile("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{2,3})\\b"
            + "|\\bSeason\\s*(\\d{1,2})\\s*Episode\\s*(\\d{1,3})\\b");
    private static final Pattern YEAR = Pattern.compile("[\\[(]?\\b((?:19[2-9]|20[0-4])\\d)\\b[\\])]?");
    private static final Pattern JUNK = Pattern.compile("(?i)\\b(480p|576p|720p|1080p|2160p|4k|uhd|hdr|hdr10|x26[45]|h ?26[45]"
            + L10n.t("|hevc|avc|blu ?ray|brrip|bdrip|web ?dl|webrip|web|hdrip|dvdrip|dvdscr|hdtv|hdcam|camrip|cam|aac\\S*|ac3|dd5\\S*")
            + "|ddp\\S*|dts|10bit|8bit|hindi|english|tamil|telugu|dual|multi|esub|esubs|subs|proper|repack|extended|unrated"
            + "|remastered|yify|yts\\S*|rarbg|internal|line)\\b");

    /** Cleans a file name (and its folder, when the name alone says too little) into a title and year, or an episode. */
    static Parsed parse(String fileName, String folder, String parent) {
        Parsed p = parseName(EXT.matcher(fileName).replaceFirst(""));
        if (p.series && p.title.length() < 2) {   // L10n.t("S01E02.mkv") inside L10n.t("Show/Season 1")
            Parsed f = parseName(folder.matches("(?i)season\\s*\\d+|s\\d+") ? parent : folder);
            p.title = f.title;
        }
        if (!p.series && p.title.length() < 2 && !folder.isEmpty()) {
            Parsed f = parseName(folder);
            if (f.title.length() >= 2) return f;
        }
        if (!p.series && p.year == 0 && !folder.isEmpty()) {   // L10n.t("The Pianist (2002)/movie.mp4")
            Parsed f = parseName(folder);
            if (f.year > 0 && Songs.squash(f.title).equals(Songs.squash(p.title))) p.year = f.year;
        }
        return p;
    }

    static Parsed parseName(String base) {
        Parsed p = new Parsed();
        String s = base.replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
        Matcher e = EPISODE.matcher(s);
        if (e.find()) {
            p.series = true;
            String season = e.group(1) != null ? e.group(1) : e.group(3) != null ? e.group(3) : e.group(5);
            String ep = e.group(2) != null ? e.group(2) : e.group(4) != null ? e.group(4) : e.group(6);
            p.season = Integer.parseInt(season);
            p.episode = Integer.parseInt(ep);
            s = s.substring(0, e.start());
        }
        Matcher y = YEAR.matcher(s);
        while (y.find()) {
            if (y.start() == 0) continue;   // "2012" or "1917" as the whole title
            p.year = Integer.parseInt(y.group(1));
            s = s.substring(0, y.start());
            break;
        }
        Matcher j = JUNK.matcher(s);
        if (j.find() && j.start() > 0) s = s.substring(0, j.start());
        s = s.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}", " ")
                .replaceAll("[\\[\\](){}]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^[-\\s]+|[-\\s]+$", "");
        p.title = s;
        return p;
    }

    // ---- matches, kept on the TV -----------------------------------------------------------------

    /** A file's TMDB match, saved so the drive shows its posters and details offline later. */
    static final class Match {
        String type, title, overview = "", genres = "", poster, backdrop, trailer;
        int id, year, tomato = -1;
        double imdb;
        boolean manual;   // chosen with Fix match: never replaced automatically
        List<String> genreCodes = new ArrayList<>();
        String language = "";                     // original language (two letters)
        List<String> tags = new ArrayList<>();    // automatic tags (at most Tags.MAX_AUTO)
        boolean tagged;                           // tags and language looked up

        JSONObject json() throws Exception {
            return new JSONObject().put("type", type).put("id", id).put("title", title).put("overview", overview)
                    .put("genres", genres).put("poster", poster == null ? "" : poster).put("backdrop", backdrop == null ? "" : backdrop)
                    .put("trailer", trailer == null ? "" : trailer).put("year", year).put("tomato", tomato).put("imdb", imdb)
                    .put("manual", manual).put("codes", new JSONArray(genreCodes)).put("lang", language)
                    .put("tags", new JSONArray(tags)).put("tagged", tagged);
        }

        static Match from(JSONObject o) {
            Match m = new Match();
            m.type = "tv".equals(o.optString("type")) ? "tv" : "movie";
            m.id = o.optInt("id");
            m.title = o.optString("title");
            m.overview = o.optString("overview", "");
            m.genres = o.optString("genres", "");
            m.poster = https(o.optString("poster", ""));
            m.backdrop = https(o.optString("backdrop", ""));
            String t = o.optString("trailer", "");
            m.trailer = t.matches("[A-Za-z0-9_\\-]{11}") ? t : null;
            m.year = o.optInt("year");
            m.tomato = o.optInt("tomato", -1);
            m.imdb = o.optDouble("imdb", 0);
            m.manual = o.optBoolean("manual");
            JSONArray c = o.optJSONArray("codes");
            for (int i = 0; c != null && i < c.length(); i++) m.genreCodes.add(c.optString(i));
            m.language = o.optString("lang", "");
            JSONArray tg = o.optJSONArray("tags");
            for (int i = 0; tg != null && i < tg.length(); i++) m.tags.add(tg.optString(i));
            m.tagged = o.optBoolean("tagged");
            return m;
        }

        /** As a title, for the watched list, trailers and the details. */
        Tmdb.Title title() {
            Tmdb.Title t = new Tmdb.Title(id, type, title, overview, year > 0 ? year + "-01-01" : "", 0, imdb, null, null);
            t.posterUrl = poster;
            t.genres = genres;
            t.genreCodes = new ArrayList<>(genreCodes);
            t.tomato = tomato;
            t.trailer = trailer;
            if (trailer != null) t.trailers = Collections.singletonList(trailer);
            t.detailed = true;
            return t;
        }
    }

    private static String https(String s) {
        return s != null && s.startsWith("https://") ? s : null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("my_movies", Context.MODE_PRIVATE);
    }

    /** The saved match for a file, or one for a show (series share their show's match). */
    @Nullable
    static Match saved(Context ctx, String key) {
        String s = prefs(ctx).getString("m:" + key, null);
        if (s == null) return null;
        try {
            return Match.from(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context ctx, String key, Match m) {
        try {
            prefs(ctx).edit().putString("m:" + key, m.json().toString()).apply();
        } catch (Exception ignored) {
            // matched again next time
        }
    }

    /** Remembered "no match" (so files with odd names are not searched every time), retried after a week. */
    static boolean missedRecently(Context ctx, String key) {
        return System.currentTimeMillis() - prefs(ctx).getLong("miss:" + key, 0) < 7L * 24 * 3600_000;
    }

    static void missed(Context ctx, String key) {
        prefs(ctx).edit().putLong("miss:" + key, System.currentTimeMillis()).apply();
    }

    /** The key a movie file, or a whole show, is matched under. */
    static String matchKey(Video v, Parsed p) {
        return p.series ? "show:" + Songs.squash(p.title) : v.key();
    }

    /**
     * Finds the best TMDB match for a cleaned name (same title and year first), with its details and IMDb rating.
     * Network; null when nothing is found or offline.
     */
    @Nullable
    static Match find(Context ctx, Parsed p) {
        if (p.title.length() < 2) return null;
        try {
            List<Tmdb.Title> found = Tmdb.searchType(ctx, p.series ? "tv" : "movie", p.title, p.year);
            if (found.isEmpty() && p.year > 0) found = Tmdb.searchType(ctx, p.series ? "tv" : "movie", p.title, 0);
            if (found.isEmpty()) return null;
            return fill(ctx, best(found, p));
        } catch (Exception e) {
            return null;
        }
    }

    /** Same title (ignoring case and punctuation) and year first, then same title, then within a year, then the first. */
    static Tmdb.Title best(List<Tmdb.Title> found, Parsed p) {
        String want = Songs.squash(p.title);
        for (Tmdb.Title t : found) if (Songs.squash(t.title).equals(want) && (p.year == 0 || t.year == p.year)) return t;
        for (Tmdb.Title t : found) if (Songs.squash(t.title).equals(want)) return t;
        if (p.year > 0) for (Tmdb.Title t : found) if (Math.abs(t.year - p.year) <= 1) return t;
        return found.get(0);
    }

    /** A match from a TMDB title: details (genres, trailer), its pictures, and IMDb / Rotten Tomatoes from JustWatch. */
    static Match fill(Context ctx, Tmdb.Title t) {
        try {
            Tmdb.details(ctx, t);
        } catch (Exception ignored) {
            // without genres and trailer
        }
        Match m = new Match();
        m.type = t.type;
        m.id = t.id;
        m.title = t.title;
        m.overview = t.overview;
        m.genres = t.genres;
        m.poster = t.poster();
        m.backdrop = t.backdrop();
        m.trailer = t.trailer;
        m.year = t.year;
        m.imdb = 0;
        try {
            for (JustWatch.Title j : JustWatch.search(t.title, "tv".equals(t.type) ? "SHOW" : "MOVIE", 0, 6)) {
                if (j.tmdbId != null && j.tmdbId.equals(String.valueOf(t.id))) {
                    m.imdb = j.imdb;
                    m.tomato = j.tomato;
                    m.genreCodes = new ArrayList<>(j.genres);
                    break;
                }
            }
        } catch (Exception ignored) {
            // TMDB's rating is shown instead
        }
        if (m.imdb <= 0) m.imdb = t.rating;
        tag(ctx, m);
        return m;
    }

    /**
     * A match's automatic tags and original language: TMDB keywords, moods, genres and India's certificate,
     * Wikidata's genres, and the awards list. Network; marks the match as tagged even when offline parts fail.
     */
    static void tag(Context ctx, Match m) {
        if (m.id <= 0) return;
        Tags.Facts f = new Tags.Facts();
        String[] lang = {""};
        try {
            f = Tmdb.facts(ctx, m.type, m.id, lang);
        } catch (Exception e) {
            return;   // offline: tried again next time
        }
        f.keywords.addAll(Tags.wikidataGenres(ctx, m.type, m.id));
        f.award = Awards.keys(ctx).contains(m.type + ":" + m.id);
        m.tags = Tags.auto(f);
        m.language = lang[0];
        m.tagged = true;
    }

    // ---- resume points and watched episodes ------------------------------------------------------

    /** Where you stopped, in ms (0 when not started or finished). */
    static long position(Context ctx, Video v) {
        return prefs(ctx).getLong("pos:" + v.key(), 0);
    }

    /**
     * Started: marked the moment it is played, so it is "In progress" even when the player never says where you
     * stopped (left with Home, or TV Town restarted meanwhile); VLC itself remembers the place and resumes there.
     */
    static void started(Context ctx, Video v) {
        if (!seen(ctx, v)) prefs(ctx).edit().putBoolean("began:" + v.key(), true).putLong("at:" + v.key(), System.currentTimeMillis()).apply();
    }

    static boolean began(Context ctx, Video v) {
        return prefs(ctx).getBoolean("began:" + v.key(), false) && !seen(ctx, v);
    }

    static long lastPlayed(Context ctx, Video v) {
        return prefs(ctx).getLong("at:" + v.key(), 0);
    }

    /**
     * Near enough to the end to count as watched: the end credits are often skipped. The last 10%; for a film (over an
     * hour) the last 12 minutes; for an episode the last 4 minutes, past the middle in each case.
     */
    static boolean nearEnd(long positionMs, long dur) {
        if (dur <= 0 || positionMs <= dur / 2) return false;
        long left = dur - positionMs;
        return positionMs >= dur * 0.90 || (dur > 60 * 60_000 ? left < 12 * 60_000 : left < 4 * 60_000);
    }

    /** After playing: the resume point, or watched when near the end (nearEnd). Returns true when it counts as watched. */
    static boolean played(Context ctx, Video v, long positionMs, long durationMs) {
        long dur = durationMs > 0 ? durationMs : v.durationMs;
        boolean finished = nearEnd(positionMs, dur);
        SharedPreferences.Editor e = prefs(ctx).edit().putLong("at:" + v.key(), System.currentTimeMillis());
        if (finished) e.remove("pos:" + v.key()).remove("began:" + v.key()).putBoolean("seen:" + v.key(), true);
        else if (positionMs > 60_000) e.putLong("pos:" + v.key(), positionMs);
        e.apply();
        return finished;
    }

    static boolean seen(Context ctx, Video v) {
        return prefs(ctx).getBoolean("seen:" + v.key(), false);
    }

    static void setSeen(Context ctx, Video v, boolean seen) {
        SharedPreferences.Editor e = prefs(ctx).edit().putBoolean("seen:" + v.key(), seen);
        if (seen) e.remove("pos:" + v.key()).remove("began:" + v.key());
        e.apply();
    }

    /** "1:02:03" or "45:10". */
    static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }
}
