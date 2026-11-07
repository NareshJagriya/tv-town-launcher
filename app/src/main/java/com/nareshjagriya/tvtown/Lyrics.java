package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timed lyrics from LRCLIB (free, no key): each line with the moment it is sung, so the player can light the line
 * being sung like karaoke. The version whose length matches the file is chosen (another recording would drift), in
 * Hindi script or English letters as set, else whichever there is. Answers are kept for months, so a song looked up
 * once has its lyrics offline.
 */
final class Lyrics {

    private Lyrics() {
    }

    /** One sung line: when it starts (ms) and its words ("" for a pause in the singing). */
    static final class Line {
        final long at;
        final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    /** Lyrics with only rough timing: plain words spread over the song (no timed version was found). */
    static final class Rough extends ArrayList<Line> {
    }

    static final String SCRIPT_HINDI = "hi", SCRIPT_LATIN = "en";
    private static final String API = "https://lrclib.net/api/search";
    private static final Map<String, String> HEADERS = Collections.singletonMap("User-Agent",
            "TV Town Launcher " + BuildConfig.VERSION_NAME + " (Android TV home screen)");

    /**
     * The song's timed lyrics; an empty list when there are none, null when LRCLIB could not be reached (try again
     * later). Network: not on the main thread.
     */
    @Nullable
    static List<Line> find(Context ctx, MyMusic.Song s, String script) {
        List<Line> own = beside(s, script);   // a .lrc file next to the song (yours, or saved by TV Town) comes first
        if (own != null) return own;
        java.io.File drive = DriveStore.volumeOf(s.path);
        JSONObject known = DriveStore.map(drive, "lyrics");
        JSONObject was = known.optJSONObject(s.key());
        String asked = s.title + "|" + s.artist;   // a "not found" stands only while the song's name is the same
        if (was != null && asked.equals(was.optString("asked")) && (was.optInt("v") >= SEARCH_VERSION || was.has("picked"))) {
            if (was.has("stretched")) return stretch(was.optString("stretched"), was.optDouble("ratio", 1));
            if (was.has("rough")) return spread(was.optString("rough"), s.durationMs);
            if (System.currentTimeMillis() - was.optLong("none") < 14L * 24 * 3600_000) return new ArrayList<>();
        }
        long seconds = s.durationMs / 1000;
        List<String> titles = new ArrayList<>();
        titles.add(s.title);
        String bare = s.title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").trim();   // "Iktara (Male Version)" also as "Iktara"
        if (!bare.isEmpty() && !bare.equalsIgnoreCase(s.title)) titles.add(bare);
        // The name from the file too: the catalogue may spell it differently from the lyrics ("Aayee" / "Ayee").
        if (s.fileTitle != null && !s.fileTitle.isEmpty() && !MyMusic.squash(s.fileTitle).equals(MyMusic.squash(s.title))) titles.add(s.fileTitle);
        List<String> singers = singers(s.artist);
        identify(ctx, s, titles, singers);   // the song's real name first: files often carry a messy one
        List<JSONArray> seen = new ArrayList<>();   // every answer, for the plain lyrics at the end
        boolean reached = false;
        String got = null;
        // 1. The exact lookup (name, singer, length): LRCLIB also asks other lyrics sources when it has no copy itself.
        for (int i = 0; got == null && i < singers.size() && i < 3; i++) {
            JSONObject o = exact(s.title, singers.get(i), seconds);
            if (o == NOT_REACHED) continue;
            reached = true;
            if (o != null) {
                String lrc = o.optString("syncedLyrics", "");
                if (!lrc.isEmpty() && !"null".equals(lrc) && parse(lrc).size() >= 3) got = lrc;
                JSONArray one = new JSONArray().put(o);
                seen.add(one);
            }
        }
        // 2. Name and each singer; 3. name with the singer as words; 4. the name alone (closer length needed: covers
        // and other recordings share it); 5. the name with the film.
        for (String t : titles) {
            for (int i = 0; got == null && i < singers.size() && i < 2; i++) {
                JSONArray r = search(Uri.encode(t), Uri.encode(singers.get(i)), null);
                if (r != null) {
                    reached = true;
                    seen.add(r);
                    got = best(r, titles, seconds, 12, script);
                }
            }
            if (got == null && !singers.isEmpty()) {
                JSONArray r = search(null, null, Uri.encode(t + " " + singers.get(0)));
                if (r != null) {
                    reached = true;
                    seen.add(r);
                    got = best(r, titles, seconds, 12, script);
                }
            }
            if (got == null) {
                JSONArray r = search(null, null, Uri.encode(t));
                if (r != null) {
                    reached = true;
                    seen.add(r);
                    got = best(r, titles, seconds, 5, script);
                }
            }
            if (got == null && !s.album.isEmpty() && !MyMusic.squash(s.album).equals(MyMusic.squash(t))) {
                JSONArray r = search(null, null, Uri.encode(t + " " + s.album));
                if (r != null) {
                    reached = true;
                    seen.add(r);
                    got = best(r, titles, seconds, 8, script);
                }
            }
            if (got != null) break;
        }
        // 6. Shorter names (the singer or a spelling stuck at the end: "Aaye Kuch Abr Atif Aslam"), and the singer
        // field as the name when the two were swapped ("K.K" / "01 Tu Hi Meri Shab Hai").
        List<String> more = new ArrayList<>();
        for (String t : titles) {
            String[] w = t.trim().split("\\s+");
            for (int n = w.length - 1; n >= 2 && n >= w.length - 3; n--) more.add(String.join(" ", java.util.Arrays.copyOf(w, n)));
        }
        String swapped = s.artist == null ? "" : s.artist.replaceAll("^\\s*\\d{1,3}\\s*[-.)_]?\\s*", "").trim();
        if (swapped.split("\\s+").length >= 2) more.add(0, swapped);
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (String t : titles) tried.add(MyMusic.squash(t));
        int asks = 0;
        for (String t : more) {
            if (got != null || asks >= 4 || !tried.add(MyMusic.squash(t))) continue;
            asks++;
            JSONArray r = search(null, null, Uri.encode(t));
            if (r == null) continue;
            reached = true;
            seen.add(r);
            if (r.length() > 0) {
                titles.add(t);
                got = best(r, titles, seconds, 8, script);
            }
        }
        if (!reached) return null;   // LRCLIB not reached: tried again later
        if (got != null) {   // kept as "Song.lrc" beside the song: on the drive, readable by other players too
            if (s.path != null) {
                int dot = s.path.lastIndexOf('.');
                DriveStore.write(new java.io.File((dot > 0 ? s.path.substring(0, dot) : s.path) + ".lrc"),
                        "[by:TV Town]\n[la:" + script + "]\n" + got);
            }
            return parse(got);
        }
        // 7. Another recording of the same song (a cover, a longer version): its timed lyrics stretched to this
        // file's length, as rough timing; 8. else plain words spread over the song.
        JSONObject other = sameSong(seen, titles, seconds, script);
        String plain = null;
        if (other == null) for (JSONArray r : seen) if (plain == null) plain = plainFor(r, seconds, script);
        double ratio = other != null && other.optDouble("duration", 0) > 0 ? seconds / other.optDouble("duration") : 1;
        try {
            JSONObject e = new JSONObject().put("script", script).put("asked", asked).put("v", SEARCH_VERSION);
            if (other != null) e.put("stretched", other.optString("syncedLyrics")).put("ratio", ratio);
            else if (plain != null) e.put("rough", plain);
            else e.put("none", System.currentTimeMillis());
            known.put(s.key(), e);
            DriveStore.save(drive, "lyrics");
        } catch (Exception ignored) {
        }
        if (other != null) return stretch(other.optString("syncedLyrics"), ratio);
        return plain != null ? spread(plain, s.durationMs) : new ArrayList<>();
    }

    private static final java.util.Set<String> NOISE = new java.util.HashSet<>(java.util.Arrays.asList("feat", "ft", "from", "the", "version",
            "live", "remix", "mix", "male", "female", "original", "soundtrack", "official", "audio", "video", "lyrical", "duet"));

    /** A name's words, spelled loosely: without vowels and h, so "Aayee" and "Ayee" or "Zindagi" and "Zindagee" agree. */
    private static java.util.Set<String> looseWords(String name) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : name.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty() || NOISE.contains(w)) continue;
            String loose = w.replaceAll("[aeiouyh]", "");
            out.add(loose.isEmpty() ? w.replaceAll("(.)\\1+", "$1") : loose);   // all vowels: "Aayee" and "Ayee" alike
        }
        return out;
    }

    /** Most words of the shorter name are in the other, and they make up a fair part of the longer one too. */
    private static boolean similar(String a, String b) {
        java.util.Set<String> x = looseWords(a), y = looseWords(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        int common = 0;
        for (String w : x) if (y.contains(w)) common++;
        int small = Math.min(x.size(), y.size()), big = Math.max(x.size(), y.size());
        return common * 10 >= small * 6 && common * 3 >= big;
    }

    /** Whether a found track is one of these names. A name in Hindi script can't be compared: its length has to do. */
    private static boolean sameName(String track, List<String> names) {
        if (track.isEmpty() || devanagari(track)) return true;
        for (String n : names) if (similar(track, n)) return true;
        return false;
    }

    /**
     * Asks the iTunes catalogue what this song is really called, from the file's details, and puts that name and singer
     * first in the lists to search with. Only a result with a similar name (and, when known, a similar length) counts.
     */
    private static void identify(Context ctx, MyMusic.Song s, List<String> titles, List<String> singers) {
        String who = MyMusic.mainArtist(s.artist);
        SongInfo.Found pick = null;
        double pickDiff = Double.MAX_VALUE;
        for (String words : new String[]{(s.title + " " + who).trim(), s.fileTitle != null && !s.fileTitle.isEmpty() ? s.fileTitle : s.title}) {
            for (SongInfo.Found f : SongInfo.search(ctx, words)) {
                String[] named = SongMatch.titleAndFilm(f.title);
                if (!similar(named[0], s.title) && (s.fileTitle == null || !similar(named[0], s.fileTitle))) continue;
                double diff = s.durationMs > 0 && f.durationMs > 0 ? Math.abs(f.durationMs - s.durationMs) / 1000.0 : 20;
                if (diff > 45) continue;   // another recording altogether
                if (diff < pickDiff) {
                    pickDiff = diff;
                    pick = f;
                }
            }
            if (pick != null) break;
        }
        if (pick == null) return;
        String real = SongMatch.titleAndFilm(pick.title)[0].trim();
        if (!real.isEmpty() && !titles.contains(real)) titles.add(0, real);
        for (String who2 : singers(pick.artist)) if (!singers.contains(who2)) singers.add(0, who2);
        android.util.Log.d("TVTownLyrics", "\"" + s.title + "\" is \"" + real + "\" by " + pick.artist);
    }

    /** A version found by Find lyrics: what it is, and its words (timed, or plain). */
    static final class Choice {
        String title, artist, text;
        long seconds;
        boolean timed;
    }

    /**
     * Find lyrics (typed by you): every version LRCLIB has for the words, closest in length first, timed before
     * plain. Null when LRCLIB could not be reached. Network.
     */
    @Nullable
    static List<Choice> choices(String words, long seconds) {
        JSONArray r = search(null, null, Uri.encode(words));
        if (r == null) return null;
        List<Choice> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null || o.optBoolean("instrumental")) continue;
            String synced = o.optString("syncedLyrics", ""), plain = o.optString("plainLyrics", "");
            boolean timed = !synced.isEmpty() && !"null".equals(synced);
            if (!timed && (plain.isEmpty() || "null".equals(plain))) continue;
            Choice c = new Choice();
            c.title = o.optString("trackName");
            c.artist = o.optString("artistName");
            c.seconds = Math.round(o.optDouble("duration", 0));
            c.timed = timed;
            c.text = timed ? synced : plain;
            out.add(c);
        }
        java.util.Collections.sort(out, (a, b) -> {
            long da = seconds > 0 ? Math.abs(a.seconds - seconds) : 0, db = seconds > 0 ? Math.abs(b.seconds - seconds) : 0;
            if (Math.abs(da - db) > 3) return Long.compare(da, db);
            return a.timed != b.timed ? (a.timed ? -1 : 1) : Long.compare(da, db);
        });
        return out;
    }

    /** Keeps a chosen version for the song (timed: "Song.lrc" beside it; plain: on the drive) and gives its lines. */
    static List<Line> keep(MyMusic.Song s, Choice c, String script) {
        if (c.timed) {
            if (s.path != null) {
                int dot = s.path.lastIndexOf('.');
                DriveStore.write(new java.io.File((dot > 0 ? s.path.substring(0, dot) : s.path) + ".lrc"),
                        "[by:TV Town]\n[la:" + script + "]\n" + c.text);
            }
            return parse(c.text);
        }
        java.io.File drive = DriveStore.volumeOf(s.path);
        try {
            DriveStore.map(drive, "lyrics").put(s.key(), new JSONObject().put("script", script).put("asked", s.title + "|" + s.artist)
                    .put("rough", c.text).put("picked", true));
            DriveStore.save(drive, "lyrics");
        } catch (Exception ignored) {
        }
        return spread(c.text, s.durationMs);
    }

    /** Raised when the search gets better: songs not found before are looked up again. */
    private static final int SEARCH_VERSION = 3;

    /**
     * Timed lyrics of the same song in another recording (same name, any length within 60% to 160% of this one), the
     * chosen script first, then the closest in length; null when none.
     */
    @Nullable
    private static JSONObject sameSong(List<JSONArray> seen, List<String> titles, long seconds, String script) {
        if (seconds <= 0) return null;
        JSONObject pick = null;
        double pickScore = Double.MAX_VALUE;
        for (JSONArray r : seen) for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null) continue;
            String synced = o.optString("syncedLyrics", "");
            double d = o.optDouble("duration", 0);
            if (synced.isEmpty() || "null".equals(synced) || d <= 0 || seconds / d < 0.6 || seconds / d > 1.6) continue;
            String name = MyMusic.squash(o.optString("trackName"));
            boolean same = false;
            for (String t : titles) {
                String q = MyMusic.squash(t);
                if (q.length() >= 4 && (name.startsWith(q) || q.startsWith(name) && name.length() >= 4)) same = true;
            }
            if (!same || parse(synced).size() < 4) continue;
            double score = Math.abs(d - seconds) + (SCRIPT_HINDI.equals(script) == devanagari(synced) ? 0 : 60);
            if (score < pickScore) {
                pickScore = score;
                pick = o;
            }
        }
        return pick;
    }

    /** Timed lyrics with every moment scaled (another recording of another length): rough timing. */
    static List<Line> stretch(String lrc, double ratio) {
        Rough out = new Rough();
        for (Line l : parse(lrc)) out.add(new Line(Math.round(l.at * ratio), l.text));
        return out;
    }

    /** Each singer on its own: "Vishal Bhardwaj & Rahat Fateh Ali Khan" gives both; no film names or "Cover". */
    static List<String> singers(String artist) {
        List<String> out = new ArrayList<>();
        if (artist == null) return out;
        for (String a : artist.split("(?i)\\s*(,|;|/|&| and | feat\\.? | ft\\.? | x | with )\\s*")) {
            String t = a.replaceAll("(?i)\\b(cover|unplugged|version)\\b", "").replaceAll("\\s+", " ").trim();
            if (t.length() >= 3 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static final JSONObject NOT_REACHED = new JSONObject();

    /** LRCLIB's exact lookup; null when it has none, NOT_REACHED when it could not be asked. */
    @Nullable
    private static JSONObject exact(String title, String singer, long seconds) {
        StringBuilder url = new StringBuilder("https://lrclib.net/api/get?track_name=").append(Uri.encode(title))
                .append("&artist_name=").append(Uri.encode(singer));
        if (seconds > 0) url.append("&duration=").append(seconds);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new JSONObject(Net.text(Net.get(url.toString(), HEADERS, 15_000, 1)));
            } catch (Net.Failure e) {
                if (e.status == 404) return null;
            } catch (Exception ignored) {
                // busy: a moment, then again
            }
            try {
                Thread.sleep(1500L << attempt);
            } catch (InterruptedException ie) {
                return NOT_REACHED;
            }
        }
        return NOT_REACHED;
    }

    @Nullable
    private static JSONArray search(@Nullable String track, @Nullable String artist, @Nullable String q) {
        String url = API + (q != null ? "?q=" + q : "?track_name=" + track + "&artist_name=" + artist);
        // LRCLIB is often busy ("503"): a few tries, a little further apart each time.
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return new JSONArray(Net.text(Net.get(url, HEADERS, 12_000, 1)));   // what is found is kept on the drive
            } catch (Exception e) {
                try {
                    Thread.sleep(1500L << attempt);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * The synced version closest in length (within `slack` seconds), preferring the chosen script when two are as
     * close; null when none is.
     */
    @Nullable
    private static String best(@Nullable JSONArray found, List<String> names, long seconds, int slack, String script) {
        if (found == null) return null;
        JSONObject pick = null;
        double pickScore = Double.MAX_VALUE;
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.optJSONObject(i);
            if (o == null || o.optBoolean("instrumental")) continue;
            String synced = o.optString("syncedLyrics", "");
            if (synced.isEmpty() || "null".equals(synced)) continue;
            double diff = seconds > 0 ? Math.abs(o.optDouble("duration", 0) - seconds) : 0;
            if (seconds > 0 && diff > slack) continue;
            if (!sameName(o.optString("trackName"), names)) continue;   // right length isn't enough: it must be this song
            boolean hindi = devanagari(synced);
            boolean wanted = SCRIPT_HINDI.equals(script) == hindi;
            double score = diff + (wanted ? 0 : slack + 1);   // the chosen script wins over a second or two
            if (score < pickScore) {
                pickScore = score;
                pick = o;
            }
        }
        if (pick == null) return null;
        String lrc = pick.optString("syncedLyrics");
        return parse(lrc).size() >= 3 ? lrc : null;
    }

    /** A "Song.lrc" next to "Song.mp3" (made or downloaded by you), or null. */
    @Nullable
    /** Whether lyrics are already kept for a song (a .lrc beside it, or found words in the drive's list). Reads the disk. */
    static boolean known(MyMusic.Song s) {
        if (s.path == null) return false;
        int dot = s.path.lastIndexOf('.');
        if (new java.io.File((dot > 0 ? s.path.substring(0, dot) : s.path) + ".lrc").isFile()) return true;
        JSONObject was = DriveStore.map(DriveStore.volumeOf(s.path), "lyrics").optJSONObject(s.key());
        return was != null && (was.has("stretched") || was.has("rough"));
    }

    private static List<Line> beside(MyMusic.Song s, String script) {
        if (s.path == null) return null;
        int dot = s.path.lastIndexOf('.');
        java.io.File f = new java.io.File((dot > 0 ? s.path.substring(0, dot) : s.path) + ".lrc");
        if (!f.isFile() || f.length() > 200_000) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            // Whatever is saved beside the song stays its lyrics, in either script: the words on screen are converted,
            // never swapped for another search's guess.
            List<Line> l = parse(text);
            return l.size() >= 3 ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * No timed lyrics: the plain words of a version of about the same length, spread over the song from the end of
     * the opening music to near the end (longer lines get longer). Shown as rough timing.
     */
    /** The plain words of a version of about the same length (no timed one was found), or null. */
    @Nullable
    private static String plainFor(@Nullable JSONArray found, long seconds, String script) {
        if (found == null || seconds <= 0) return null;
        String plain = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.optJSONObject(i);
            if (o == null || o.optBoolean("instrumental")) continue;
            String p = o.optString("plainLyrics", "");
            if (p.isEmpty() || "null".equals(p)) continue;
            double diff = Math.abs(o.optDouble("duration", 0) - seconds);
            if (diff > 8) continue;
            double score = diff + (SCRIPT_HINDI.equals(script) == devanagari(p) ? 0 : 9);
            if (score < bestScore) {
                bestScore = score;
                plain = p;
            }
        }
        return plain;
    }

    /**
     * Plain words spread over the song, from the end of the opening music to near the end (longer lines get longer):
     * rough timing.
     */
    static List<Line> spread(String plain, long durationMs) {
        Rough out = new Rough();
        List<String> lines = new ArrayList<>();
        for (String l : plain.split("\\r?\\n")) if (!l.trim().isEmpty()) lines.add(l.trim());
        if (lines.size() < 4 || durationMs <= 0) return out;
        long start = Math.min(25_000, durationMs / 10), end = durationMs * 92 / 100;
        long chars = 0;
        for (String l : lines) chars += l.length() + 8;
        long at = start;
        for (String l : lines) {
            out.add(new Line(at, l));
            at += (end - start) * (l.length() + 8) / chars;
        }
        return out;
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** "[01:02.50] words" lines into timed lines, in order (a line with two stamps is sung twice). */
    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\r?\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    ms += Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    static boolean devanagari(String s) {
        int n = 0, letters = 0;
        for (int i = 0; i < s.length() && letters < 400; i++) {
            char ch = s.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            if (ch >= 0x0900 && ch <= 0x097F) n++;
        }
        return letters > 0 && n * 2 > letters;
    }

    /**
     * The lines in the chosen script: Hindi-script lyrics become English letters (Roman) when those are chosen and no
     * version in English letters was found. Rough timing stays rough.
     */
    static List<Line> inScript(List<Line> lines, String script) {
        if (!SCRIPT_LATIN.equals(script) || lines.isEmpty()) return lines;
        StringBuilder all = new StringBuilder();
        for (Line l : lines) all.append(l.text).append('\n');
        if (!devanagari(all.toString())) return lines;
        List<Line> out = lines instanceof Rough ? new Rough() : new ArrayList<>();
        for (Line l : lines) out.add(new Line(l.at, Roman.of(l.text)));
        return out;
    }

    private static final java.util.Set<String> ENGLISH = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "you", "and", "your", "that", "this", "with", "for", "are", "was", "what", "when", "they", "just", "don't",
            "can't", "love", "all", "have", "from", "like", "know", "will", "baby", "it's", "i'm", "in", "on", "of", "to", "my", "me", "it", "is"));

    /** Words of an English song rather than Hindi in English letters: Hindi songs are never spelled in Hindi script for it. */
    static boolean looksEnglish(List<Line> lines) {
        int words = 0, english = 0;
        for (Line l : lines) {
            for (String w : l.text.toLowerCase(java.util.Locale.ROOT).split("[^a-z']+")) {
                if (w.isEmpty()) continue;
                words++;
                if (ENGLISH.contains(w)) english++;
            }
        }
        return words == 0 || english * 100 >= words * 22;
    }

    /** True when these lines could be spelled in Hindi script: Hindi words written in English letters. */
    static boolean convertible(List<Line> lines) {
        StringBuilder all = new StringBuilder();
        for (Line l : lines) all.append(l.text).append('\n');
        return !lines.isEmpty() && !devanagari(all.toString()) && !looksEnglish(lines);
    }

    /**
     * Hindi words in English letters, in Hindi script, from Google's public typing service (no key; it isn't a
     * documented service and may change). Each line is kept once on the TV. Null when it can't be reached: the words
     * then stay as they are. Network: not on the main thread.
     */
    @Nullable
    static List<Line> toHindi(Context ctx, List<Line> lines) {
        Map<String, String> done = new java.util.HashMap<>();
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            String t = l.text.trim();
            if (t.isEmpty()) {
                out.add(l);
                continue;
            }
            String got = done.get(t);
            if (got == null) {
                try {
                    String url = "https://inputtools.google.com/request?itc=hi-t-i0-und&num=1&ie=utf-8&oe=utf-8&text=" + android.net.Uri.encode(t);
                    JSONArray a = new JSONArray(Net.cached(ctx, url, null, 90L * 24 * 3600_000, 365L * 24 * 3600_000, 8000));
                    if (!"SUCCESS".equals(a.optString(0))) return null;
                    got = a.getJSONArray(1).getJSONArray(0).getJSONArray(1).getString(0);
                    done.put(t, got);
                } catch (Exception e) {
                    return null;
                }
            }
            out.add(new Line(l.at, got));
        }
        return out;
    }

    /** The line being sung at a moment (index), -1 before the first. */
    static int lineAt(List<Line> lines, long ms) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).at <= ms) {
                ans = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return ans;
    }
}
