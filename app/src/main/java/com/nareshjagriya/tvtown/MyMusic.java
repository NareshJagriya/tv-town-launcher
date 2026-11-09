package com.nareshjagriya.tvtown;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * My Music: the songs on a plugged-in drive, from the TV's media index. Names are cleaned for the screen (track
 * numbers, website names and "Full Video Song" go; "033 - Lady Gaga - Alejandro" becomes Alejandro by Lady Gaga) and
 * the files are never changed: your own edits, favourites and lyrics timing are kept on the TV. Songs are grouped by
 * their top folder under Music/ ("Music/Gazals/Best Gazals/x.mp3" is in Gazals) and by their main artist.
 */
final class MyMusic {

    private MyMusic() {
    }

    /** One song file on the drive, with what is shown for it (cleaned, or as you edited it). */
    static final class Song {
        long id, size, durationMs;
        String name, rel, folder;   // folder: the top folder under Music/ ("Gazals")
        String path;                // the file on the drive (for a lyrics file beside it), when the TV gives it
        String title = "", artist = "", album = "", genre = "";   // album: the film, for film songs
        String fileTitle = "";      // the name as cleaned from the file, before tidying (lyrics are searched by both)
        boolean matched;            // tidied from the iTunes catalogue (SongMatch)
        String cover;               // a cover you chose (https), else the one inside the file
        boolean siteArt;            // tagged by a download site: its picture is the site's advert, not a cover
        Uri uri;

        /** Stays the same while the file does (name and size), across drives and re-plugs. */
        String key() {
            return name + ":" + size;
        }

        /** Under the title: "Jab We Met  ·  Pritam & Mohit Chauhan" (film or album, then who sings). */
        String detail() {
            if (album.isEmpty() || squash(album).equals(squash(title)) || squash(album).equals(squash(artist))) return artist;
            return artist.isEmpty() ? album : album + "  ·  " + artist;
        }
    }

    /** A folder or an artist: a card in the grid, with its songs in order. */
    static final class Group {
        final String name;
        final List<Song> songs = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }

        long durationMs() {
            long d = 0;
            for (Song s : songs) d += s.durationMs;
            return d;
        }
    }

    private static final String[] AUDIO = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma"};

    // The songs read last time, kept for as long as TV Town runs: My Music shows them at once while it reads again.
    private static volatile List<Song> last;

    @Nullable
    static List<Song> lastRead() {
        return last;
    }

    private static long lastAt;

    /** Reads the drive (scan) and keeps the result; one read at a time, and one just made is shared, not repeated. */
    static synchronized List<Song> read(Context ctx) {
        if (last != null && android.os.SystemClock.uptimeMillis() - lastAt < 10_000) return last;
        List<Song> s = scan(ctx);
        last = s;
        lastAt = android.os.SystemClock.uptimeMillis();
        return s;
    }

    /** Reads the songs in the background ahead of time (the TV started, or a drive came in), so My Music opens at once. */
    static void warmUp(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Perms.media(app)) return;
        new Thread(() -> {
            if (MyMovies.driveMounted(app)) read(app);
            else last = null;
        }, "music-warm").start();
    }

    /** Every song on removable drives (not ringtones, nor clips under 40 seconds). Needs the storage permission. */
    static List<Song> scan(Context ctx) {
        List<Song> out = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 30) return out;
        ContentResolver cr = ctx.getContentResolver();
        for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
            if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;   // the TV's own storage
            Uri base = MediaStore.Audio.Media.getContentUri(vol);
            String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.DATA};
            try (Cursor c = cr.query(base, cols, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Song s = new Song();
                    s.id = c.getLong(0);
                    s.name = c.getString(1);
                    s.size = c.getLong(2);
                    s.durationMs = c.getLong(3);
                    s.rel = c.getString(4);
                    if (s.name == null || s.name.startsWith("._") || !isAudio(s.name)) continue;
                    if (s.durationMs > 0 && s.durationMs < 40_000) continue;
                    if (s.rel != null && s.rel.toLowerCase(Locale.ROOT).contains("tv town scenes")) continue;
                    s.folder = topFolder(s.rel);
                    s.path = c.getString(9);
                    s.uri = android.content.ContentUris.withAppendedId(base, s.id);
                    clean(s, c.getString(5), c.getString(6), c.getString(7), c.getString(8));
                    SongMatch.apply(ctx, s);
                    applyEdits(ctx, s);
                    out.add(s);
                }
            } catch (Exception ignored) {
                // the drive went away mid-scan, or no permission
            }
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return withoutCopies(out);
    }

    /**
     * The same song twice in one folder (same title and singer, lengths within two seconds: a file copied in again)
     * shows once. Only the list changes; nothing on the drive is touched.
     */
    static List<Song> withoutCopies(List<Song> songs) {
        List<Song> out = new ArrayList<>();
        Map<String, List<Song>> seen = new java.util.HashMap<>();
        for (Song s : songs) {
            String id = s.folder + "|" + squash(s.title) + "|" + squash(s.artist);
            List<Song> same = seen.get(id);
            boolean copy = false;
            if (same != null && !squash(s.title).isEmpty()) {
                for (Song o : same) if (Math.abs(o.durationMs - s.durationMs) <= 2000) copy = true;
            }
            if (copy) continue;
            if (same == null) seen.put(id, same = new ArrayList<>());
            same.add(s);
            out.add(s);
        }
        return out;
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : AUDIO) if (n.endsWith(e)) return true;
        return false;
    }

    /** "Music/Gazals/Best Gazals/" gives "Gazals"; a song at the top of the drive is in "Songs". */
    static String topFolder(@Nullable String rel) {
        if (rel == null) return "Songs";
        List<String> parts = new ArrayList<>();
        for (String p : rel.split("/")) if (!p.isEmpty()) parts.add(p);
        if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase("music")) parts.remove(0);
        // An Apple Music library copied over: Music/Media.localized/Music/Unplugged/Unknown Album/
        while (!parts.isEmpty() && parts.get(0).matches("(?i)media(\\.localized)?|music|itunes( media)?|itunes music")) parts.remove(0);
        return parts.isEmpty() ? "Songs" : parts.get(0);
    }

    /** Folders, then artists with at least three songs (the rest under "Others"); each list in title order. */
    static List<Group> folders(List<Song> songs) {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : songs) {
            Group g = m.get(s.folder);
            if (g == null) m.put(s.folder, g = new Group(s.folder));
            g.songs.add(s);
        }
        List<Group> out = new ArrayList<>(m.values());
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static final String OTHERS = "Others";

    static List<Group> artists(List<Song> songs) {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : songs) {
            String a = mainArtist(s.artist);
            if (a.isEmpty()) continue;
            String key = a.toLowerCase(Locale.ROOT);
            Group g = m.get(key);
            if (g == null) m.put(key, g = new Group(a));
            g.songs.add(s);
        }
        List<Group> out = new ArrayList<>();
        Group others = new Group(OTHERS);
        for (Group g : m.values()) {
            if (g.songs.size() >= 3) out.add(g);
            else others.songs.addAll(g.songs);
        }
        for (Song s : songs) if (mainArtist(s.artist).isEmpty()) others.songs.add(s);
        Collections.sort(out, (a, b) -> Integer.compare(b.songs.size(), a.songs.size()));
        if (!others.songs.isEmpty()) out.add(others);
        return out;
    }

    /** "Shankar-Ehsaan-Loy; Adnan Sami" gives "Shankar-Ehsaan-Loy"; "Atif Aslam ft. Shreya" gives "Atif Aslam". */
    static String mainArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split("(?i)[,;/&]| feat\\.? | ft\\.? | x | and | with ")[0].trim();
        return a.length() < 2 ? "" : a;
    }

    // ---- cleaning names ------------------------------------------------------------------------------

    private static final Pattern EXT = Pattern.compile("\\.[A-Za-z0-9]{2,4}$");
    // Website names, rips and video words left in downloaded file names and tags.
    private static final Pattern SITE = Pattern.compile("(?i)[\\[(\\-\\s]*\\(?\\s*(www\\.)?[a-z0-9-]{3,}\\.(com|net|in|pk|org|info|fm|name|me|co|cc|mobi|ws|tv|xyz|link|live|site|club|io|la|cx|to|ch|pw|sx|cm|biz|us|vip|top|cloud)\\b"
            + "(\\.[a-z]{2})?\\s*\\)?[\\])]?");
    // A web address in brackets, any ending: "(wapking.fm)", "[DJMaza.Info]".
    private static final Pattern SITE_IN_BRACKETS = Pattern.compile("(?i)\\s*[\\[(]\\s*(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,4}\\s*[\\])]");
    private static final Pattern WORDS = Pattern.compile("(?i)\\b(full (video|audio|song)s?( song)?|official (video|audio|music video|lyric(al)? video)"
            + "|lyric(al)? video|video song|audio song|hd|hq|320 ?kbps|128 ?kbps|kbps|mp3|t-series|tseries|zee music( company)?"
            + "|sony music( india)?|saregama|wid lyrics|with lyrics|lyrics|songspk|mp3 ?paglu|pagalworld|mr-?jatt|djmaza|wapking"
            + "|songs ?lover|mp3 ?glu|mp3mad|downloadming|320|128)\\b");
    // "{FILM : JAB WE MET}" or "(From \"Jab We Met\")": the film the song is from.
    private static final Pattern FILM = Pattern.compile("(?i)[{(\\[]\\s*(?:film|movie|from)\\s*[:\\-]?\\s*\"?([^}\\])\"]+)\"?\\s*[})\\]]");
    private static final Pattern TRACK = Pattern.compile("^\\s*(\\[[^\\]]{1,6}\\]\\s*)?(\\d{1,3})\\s*([-.)_]\\s*|\\s+)");
    private static final Pattern COVER = Pattern.compile("(?i)\\s+cover$");

    /**
     * Title and artist for the screen, from the file's tags when they look right, else from its name. With no artist
     * tag, "Artist - Title" names give the artist when a track number came first (as download sites do), else
     * "Title - Artist" (as most Indian uploads do).
     */
    static void clean(Song s, @Nullable String tagTitle, @Nullable String tagArtist, @Nullable String tagAlbum, @Nullable String tagGenre) {
        String base = EXT.matcher(s.name).replaceFirst("");
        String film = "";
        for (String t : new String[]{tagTitle, s.name}) {
            java.util.regex.Matcher f = t != null ? FILM.matcher(t) : null;
            if (f != null && f.find()) {
                film = titleCase(f.group(1).trim());
                break;
            }
        }
        if (tagTitle != null) tagTitle = FILM.matcher(tagTitle).replaceAll(" ");
        base = FILM.matcher(base).replaceAll(" ");
        s.siteArt = site(tagTitle) || site(tagArtist) || site(tagAlbum) || site(tagGenre) || site(s.name);
        boolean numbered = TRACK.matcher(base).find();
        String fileName = tidy(TRACK.matcher(base).replaceFirst(""));
        String artist = usable(tagArtist) ? tidy(tagArtist) : "";
        String title = usable(tagTitle) && !squash(tagTitle).equals(squash(base)) ? tidy(TRACK.matcher(tagTitle).replaceFirst("")) : "";
        if (title.isEmpty()) {
            title = fileName;
            String[] parts = fileName.split("\\s+-\\s+|\\s+–\\s+");
            if (parts.length >= 2) {
                String a = parts[0].trim(), b = parts[parts.length - 1].trim();
                if (!artist.isEmpty() && squash(a).contains(squash(artist))) title = b;
                else if (!artist.isEmpty() && squash(b).contains(squash(artist))) title = a;
                else if (artist.isEmpty() && numbered) {
                    artist = a;
                    title = b;
                } else if (artist.isEmpty()) {
                    title = a;
                    artist = b;
                } else title = a;
            }
        }
        title = title.replaceAll("\\s*\\(\\s*\\d{4}\\s*\\)\\s*-?\\s*", " ").replaceAll("\\s+", " ").trim();
        artist = COVER.matcher(artist).replaceFirst("").trim();
        title = title.replaceAll("[\\s\\-–—.,:;]+$", "");   // "Karo-" ends as "Karo"
        s.title = title.isEmpty() ? fileName : title;
        s.fileTitle = s.title;
        s.artist = artist;
        s.album = !film.isEmpty() ? film : usable(tagAlbum) ? tidy(tagAlbum) : "";
        s.genre = usable(tagGenre) && !tagGenre.matches("(?i).*\\b(top|20\\d\\d|19\\d\\d|other|unknown|www)\\b.*") ? tidy(tagGenre) : "";
    }

    private static final Pattern SITE_NAME = Pattern.compile("(?i)(www\\.|\\.(com|net|in|pk|info)\\b|songs ?lover|mp3 ?glu|mr-?jatt|djmaza|songs\\.pk|pagalworld|downloadming|mp3mad|wapking)");

    private static boolean site(@Nullable String t) {
        return t != null && SITE_NAME.matcher(t).find();
    }

    /** A tag worth showing: not empty, "<unknown>", a bare number or a website. */
    private static boolean usable(@Nullable String t) {
        if (t == null) return false;
        String s = t.trim();
        return !s.isEmpty() && !s.equalsIgnoreCase("<unknown>") && !s.matches("\\d+") && !s.matches("(?i).*(www\\.|\\.com\\b|\\.pk\\b|\\.in\\b).*")
                && !s.matches("(?i)(unknown|various|various artists|track\\s*\\d+)");
    }

    /** Website names, rip words and stray brackets out; underscores to spaces; Title Case when all one case. */
    static String tidy(String s) {
        String t = SITE_IN_BRACKETS.matcher(s.replace('_', ' ').replaceAll("(?i)[-–\\s]*mp3g\\.lu\\b", " ")).replaceAll(" ");
        t = SITE.matcher(t).replaceAll(" ");
        t = WORDS.matcher(t).replaceAll(" ");
        t = t.replace('_', ' ').replaceAll("\\(\\s*\\)|\\[\\s*\\]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^[-–.,\\s]+|[-–.,\\s]+$", "");
        if (t.equals(t.toLowerCase(Locale.ROOT)) || t.equals(t.toUpperCase(Locale.ROOT))) t = titleCase(t);
        return t;
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(start ? Character.toUpperCase(ch) : ch);
            start = ch == ' ' || ch == '(' || ch == '-';
        }
        return b.toString();
    }

    static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ---- your edits, favourites, lyrics timing (kept on the TV) ----------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("my_music", Context.MODE_PRIVATE);
    }

    /** What you changed for a song: title, artist, genre, cover (any of them). */
    static void edit(Context ctx, Song s, @Nullable String title, @Nullable String artist, @Nullable String genre, @Nullable String cover) {
        try {
            String old = prefs(ctx).getString("edit:" + s.key(), null);
            JSONObject o = old != null ? new JSONObject(old) : new JSONObject();
            if (title != null) o.put("title", title.trim());
            if (artist != null) o.put("artist", artist.trim());
            if (genre != null) o.put("genre", genre.trim());
            if (cover != null) o.put("cover", cover);
            prefs(ctx).edit().putString("edit:" + s.key(), o.toString()).apply();
        } catch (Exception ignored) {
            // kept as it was
        }
        applyEdits(ctx, s);
    }

    static boolean edited(Context ctx, Song s) {
        return prefs(ctx).contains("edit:" + s.key());
    }

    static void clearEdits(Context ctx, Song s) {
        prefs(ctx).edit().remove("edit:" + s.key()).apply();
    }

    private static void applyEdits(Context ctx, Song s) {
        String e = prefs(ctx).getString("edit:" + s.key(), null);
        if (e == null) return;
        try {
            JSONObject o = new JSONObject(e);
            if (!o.optString("title").isEmpty()) s.title = o.optString("title");
            if (o.has("artist")) s.artist = o.optString("artist");
            if (o.has("genre")) s.genre = o.optString("genre");
            String cover = o.optString("cover");
            if (cover.startsWith("https://")) s.cover = cover;
        } catch (Exception ignored) {
            // as cleaned
        }
    }

    static boolean favourite(Context ctx, Song s) {
        return prefs(ctx).getBoolean("fav:" + s.key(), false);
    }

    static void setFavourite(Context ctx, Song s, boolean on) {
        prefs(ctx).edit().putBoolean("fav:" + s.key(), on).apply();
    }

    static boolean favouriteFolder(Context ctx, String folder) {
        return prefs(ctx).getBoolean("favf:" + folder, false);
    }

    static void setFavouriteFolder(Context ctx, String folder, boolean on) {
        prefs(ctx).edit().putBoolean("favf:" + folder, on).apply();
    }

    /** Lyrics earlier (minus) or later (plus) for one song, in ms. */
    static long lyricsOffset(Context ctx, Song s) {
        return prefs(ctx).getLong("lrc:" + s.key(), 0);
    }

    static void setLyricsOffset(Context ctx, Song s, long ms) {
        prefs(ctx).edit().putLong("lrc:" + s.key(), ms).apply();
    }

    static SharedPreferences settings(Context ctx) {
        return prefs(ctx);
    }

    // ---- covers --------------------------------------------------------------------------------------

    private static final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final Set<String> noCover = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, List<Runnable>> waiting = new java.util.HashMap<>();
    private static final ExecutorService coverPool = Work.pool(1);
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The song's cover: one you chose online, else the picture inside the file (small, read once in the
     * background); null for now, and `ready` runs when it arrives. False from hasCover() once a file is known to
     * have none.
     */
    @Nullable
    static Bitmap cover(Context ctx, Song s, @Nullable Runnable ready) {
        if (s.cover != null) return ImageCache.get(s.cover, ready, coverStore(s), COVER_PX);
        if (s.siteArt) return null;
        String key = s.key();
        Bitmap b = covers.get(key);
        if (b != null || noCover.contains(key)) return b;
        synchronized (waiting) {
            List<Runnable> w = waiting.get(key);
            if (w != null) {
                if (ready != null) w.add(ready);
                return null;
            }
            w = new ArrayList<>();
            if (ready != null) w.add(ready);
            waiting.put(key, w);
        }
        Context app = ctx.getApplicationContext();
        coverPool.execute(() -> {
            Bitmap got = null;
            try {
                got = app.getContentResolver().loadThumbnail(s.uri, new Size(256, 256), null);
            } catch (Exception ignored) {
                // no picture in the file
            }
            if (got != null && advert(app, got, s)) got = null;
            Bitmap fin = got;
            main.post(() -> {
                if (fin != null) covers.put(key, fin);
                else noCover.add(key);
                List<Runnable> w;
                synchronized (waiting) {
                    w = waiting.remove(key);
                }
                if (w != null) for (Runnable r : w) r.run();
            });
        });
        return null;
    }

    // Where chosen and catalogue covers are kept: ".tvtown/covers" on the song's drive (looked up once per drive).
    private static final Map<String, java.io.File> stores = new java.util.HashMap<>();

    @Nullable
    private static java.io.File coverStore(Song s) {
        java.io.File vol = DriveStore.volumeOf(s.path);
        if (vol == null) return null;
        String k = vol.getPath();
        if (!stores.containsKey(k)) stores.put(k, DriveStore.dir(vol, "covers"));
        return stores.get(k);
    }

    /** Covers are held at this width: enough for the player's card, small enough for hundreds in memory. */
    static final int COVER_PX = 300;

    /** False once the file is known to have no picture (and no cover was chosen). */
    static boolean hasCover(Song s) {
        return s.cover != null || !s.siteArt && !noCover.contains(s.key());
    }

    // The same picture in songs of three or more different albums and artists is a download site's advert.
    private static final Map<Long, Set<String>> seenWith = new java.util.HashMap<>();

    /** True when this picture is a known advert (remembered on the TV), or now turns out to be one. */
    private static synchronized boolean advert(Context ctx, Bitmap b, Song s) {
        long fp = fingerprint(b);
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean("ad:" + fp, false)) return true;
        Set<String> with = seenWith.get(fp);
        if (with == null) seenWith.put(fp, with = new HashSet<>());
        with.add(squash(s.album) + "|" + squash(mainArtist(s.artist)));
        if (with.size() < 3) return false;
        p.edit().putBoolean("ad:" + fp, true).apply();
        covers.evictAll();   // pictures already shown with it go too
        return true;
    }

    /** A rough fingerprint of a picture: light or dark in an 8 x 8 grid. */
    private static long fingerprint(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 8, 8, true);
        int[] px = new int[64];
        small.getPixels(px, 0, 8, 0, 0, 8, 8);
        if (small != b) small.recycle();
        long sum = 0;
        int[] lum = new int[64];
        for (int i = 0; i < 64; i++) {
            lum[i] = (Color.red(px[i]) * 3 + Color.green(px[i]) * 6 + Color.blue(px[i])) / 10;
            sum += lum[i];
        }
        long avg = sum / 64, fp = 0;
        for (int i = 0; i < 64; i++) if (lum[i] > avg) fp |= 1L << i;
        return fp;
    }

    /** The cover's main colour, brightened, for the bars and glow; a soft violet without one. */
    static int colour(@Nullable Bitmap b) {
        if (b == null) return Theme.VIOLET_A;
        Bitmap small = Bitmap.createScaledBitmap(b, 8, 8, true);
        float best = -1;
        float[] hsv = new float[3], pick = {265, 0.45f, 1};
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            Color.colorToHSV(small.getPixel(x, y), hsv);
            float score = hsv[1] * (0.3f + hsv[2]);   // colourful and not too dark
            if (score > best) {
                best = score;
                pick = hsv.clone();
            }
        }
        if (small != b) small.recycle();
        pick[1] = Math.max(0.35f, Math.min(0.75f, pick[1]));
        pick[2] = 1;
        return Color.HSVToColor(pick);
    }

    /** "3 h 10 min" or "42 min". */
    static String length(long ms) {
        long min = Math.round(ms / 60_000.0);
        return min >= 60 ? min / 60 + " h " + min % 60 + " min" : min + " min";
    }
}
