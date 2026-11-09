package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Random Music: YouTube songs or playlists for a card of the picker (a mood, an era, a singer…) as cards to choose
 * from (picture, title, channel, length or number of songs). Songs come from any channel that is verified or well
 * watched (often a fan upload is the better copy), the right length, and without jukeboxes, mashups or lofi edits
 * unless the card is about them; a singer's card prefers songs naming the singer. Playlists are real song collections
 * (15 to 500 videos). Nothing already shown for the same card, or hidden with Not for me, comes back until the pool
 * is used up.
 */
final class Songs {

    /** Words in a title or channel that mark what Random Song should never play. */
    private static final String[] BLOCKED = {"bhojpuri", "haryanvi", "jukebox", "mashup", "nonstop", "non-stop",
            "non stop", "back to back", "full album", "all songs", "8d", "slowed", "reverb", "status", "shorts",
            "ringtone", "karaoke", "instrumental", "cover by", "dj remix", "dance video", "hot", "sexy",
            // Low-effort uploads that copy film stars' names: "New Song 2026 | New Hindi Song | ..."
            "new hindi song", "new song 20", "new songs 20", "viral song", "purane songs", "hits songs", "lofi", "lo-fi"};

    /** Unverified channels need at least this many views (keeps out spam and re-uploads nobody watches). */
    private static final int MIN_VIEWS = 50_000;

    /** India's main film and music labels: never picked for English and international music. */
    private static final String[] INDIAN_LABELS = {"t-series", "sony music india", "zee music", "saregama", "tips official",
            "tips music", "yrf", "yash raj", "times music", "eros now", "shemaroo", "venus", "universal music india",
            "warner music india", "speed records", "desi music factory", "ultra bollywood", "goldmines", "pen movies",
            "superhit gaane", "90sfilmygaane", "sonymusicindiavevo", "t-series bollywood classics", "rajshri",
            "bollywood classics", "white hill", "lahari music", "aditya music", "think music", "sony music south",
            "everest marathi", "zee music marathi", "video palace", "soor mandir", "studio saraswati", "raghav digital",
            "jhankar music", "muzik247", "manorama music", "millennium audios", "satyam audios", "goodwill entertainments"};

    private Songs() {
    }

    /** One result card: a song or a playlist. */
    static final class Pick {
        final String id, title, by, thumb;
        final boolean playlist;
        final int seconds, videos;
        volatile String includes;   // a playlist's first few songs, fetched when its card is in view

        Pick(String id, boolean playlist, String title, String by, String thumb, int seconds, int videos) {
            this.id = id;
            this.playlist = playlist;
            this.title = title;
            this.by = by;
            this.thumb = thumb;
            this.seconds = seconds;
            this.videos = videos;
        }

        /** What to open in the YouTube app: the song, or the playlist from a random song near its start. */
        String url(Random r) {
            return playlist ? Picks.playlistUrl(id, videos, r) : "https://www.youtube.com/watch?v=" + id;
        }
    }

    /**
     * Up to `max` songs or playlists for a card, none already shown for it (or hidden), marking them as shown. Several
     * searches (different wordings, each in one of the chosen languages, and a different part of the card each time)
     * give a bigger pool; Extras use every part with Any, else the parts for the ticked languages. When everything has been
     * shown, a new round starts. Runs network requests; call off the main thread.
     */
    static List<Pick> find(Context ctx, int kind, Picks.Entry entry, List<String> ticked, List<String> shown,
                           boolean playlists, int max, Random random) {
        // Extras: with Any, every part; with languages ticked, the parts made for them (Hindi trailers, Hindi stand-up…)
        // and the ones for every language.
        boolean anyLanguage = kind == Picks.EXTRAS && ticked.isEmpty();
        List<String> langs = anyLanguage ? java.util.Arrays.asList(Style.global() ? "English" : "Hindi")
                : kind == Picks.EXTRAS ? new ArrayList<>(ticked) : Picks.songLanguages(ticked, shown);
        String bag = Picks.bag(kind, entry, ticked) + (playlists ? ":pl" : ":song");
        java.util.Set<String> skip = Picks.seen(ctx, bag);
        skip.addAll(Picks.hidden(ctx));
        // Several searches side by side, each for a different part of the card where it has several (a different
        // comedian, singer, show or channel), so a round mixes them rather than filling up from the first one.
        int searches = entry.on().size() > 1 ? 4 : Math.max(3, Math.min(4, langs.size() + 1));
        List<Picks.Tune> tunes = new ArrayList<>();
        List<String> languages = new ArrayList<>();
        java.util.Set<String> usedParts = new java.util.HashSet<>();
        for (int i = 0; i < searches; i++) {
            Picks.Entry part = Picks.tunePart(entry, langs, anyLanguage, random);
            for (int retry = 0; retry < 6 && usedParts.contains(part.label); retry++) {
                part = Picks.tunePart(entry, langs, anyLanguage, random);
            }
            usedParts.add(part.label);
            tunes.add(part.tune);
            languages.add(Picks.songLanguage(part.tune, langs, random));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(searches);
        List<java.util.concurrent.Future<List<Pick>>> jobs = new ArrayList<>();
        for (int i = 0; i < searches; i++) {
            final Picks.Tune tune = tunes.get(i);
            final String language = languages.get(i), query = Picks.songQuery(tune, language, playlists, random);
            jobs.add(pool.submit(() -> {
                String html = fetch(playlists ? playlistSearchUrl(query) : searchUrl(query, tune.filter));
                return playlists ? playlists(html, tune, language) : videos(html, tune, language);
            }));
        }
        List<List<Pick>> lists = new ArrayList<>();
        for (java.util.concurrent.Future<List<Pick>> job : jobs) {
            try {
                lists.add(job.get(20, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                lists.add(new ArrayList<>());   // that search failed: the others still count
            }
        }
        pool.shutdownNow();
        // Take from each search in turn: one from the first, one from the second… then round again.
        List<Pick> found = new ArrayList<>(), fresh = new ArrayList<>();
        for (int i = 0, more = 1; more > 0; i++) {
            more = 0;
            for (List<Pick> l : lists) {
                if (i >= l.size()) continue;
                more++;
                Pick p = l.get(i);
                if (contains(found, p.id)) continue;
                found.add(p);
                if (!skip.contains(p.id) && fresh.size() < max) fresh.add(p);
            }
        }
        if (fresh.isEmpty() && !found.isEmpty()) {   // everything found has been shown: a new round
            Picks.newRound(ctx, bag);
            java.util.Set<String> hidden = Picks.hidden(ctx);
            for (Pick p : found) if (!hidden.contains(p.id) && fresh.size() < max) fresh.add(p);
        }
        java.util.Collections.shuffle(fresh, random);
        for (Pick p : fresh) Picks.markSeen(ctx, bag, p.id);
        return fresh;
    }

    private static boolean contains(List<Pick> l, String id) {
        for (Pick p : l) if (p.id.equals(id)) return true;
        return false;
    }

    /** A playlist's first three songs, shortened ("Tum Hi Ho, Bekhayali, Soch Na Sake"); "" when unknown. */
    static String includes(String playlistId) {
        if (!PLAYLIST_ID.matcher(playlistId).matches()) return "";
        try {
            Matcher m = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL)
                    .matcher(fetch("https://www.youtube.com/playlist?list=" + playlistId));
            if (!m.find()) return "";
            List<String> names = new ArrayList<>();
            JSONObject root = new JSONObject(m.group(1));
            collectTitles(root, names, 3);
            return String.join(", ", names);
        } catch (Exception e) {
            return "";
        }
    }

    /** Walks the page for video lockups and keeps their shortened titles. */
    private static void collectTitles(Object o, List<String> out, int max) {
        if (out.size() >= max) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject lock = j.optJSONObject("lockupViewModel");
            if (lock != null && "LOCKUP_CONTENT_TYPE_VIDEO".equals(lock.optString("contentType"))) {
                JSONObject meta = lock.optJSONObject("metadata");
                meta = meta != null ? meta.optJSONObject("lockupMetadataViewModel") : null;
                JSONObject t = meta != null ? meta.optJSONObject("title") : null;
                String shortName = shortTitle(t != null ? t.optString("content") : "");
                if (!shortName.isEmpty() && !out.contains(shortName)) out.add(shortName);
                return;
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext() && out.size() < max; ) collectTitles(j.opt(it.next()), out, max);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length() && out.size() < max; i++) collectTitles(a.opt(i), out, max);
        }
    }

    /**
     * A song or playlist name without the tags uploaders add: up to the first "|", without "Full Video Song",
     * "Official Video", "(Lyrical)" and the like, and without quotes.
     */
    static String shortTitle(String title) {
        String t = title.split("\\s*[|｜]\\s*")[0];
        t = t.replaceAll("(?i)\\b(full\\s+)?(hd\\s+)?(official\\s+)?(music\\s+)?(video|lyric(al)?|audio)(\\s+song)?\\b", "")
                .replaceAll("(?i)\\bfull song\\b|\\bvideo song\\b|\\b(4k|8k|hd)\\b", "")
                .replaceAll("[\"“”]", "").replaceAll("\\(\\s*\\)|\\[\\s*\\]", "").replaceAll("\\s{2,}", " ").trim();
        t = t.replaceAll("[\\s:\\-–]+$", "").trim();
        return t.length() > 48 ? t.substring(0, 46).trim() + "…" : t;
    }

    /** YouTube search limited to playlists. */
    private static String playlistSearchUrl(String query) {
        return "https://www.youtube.com/results?search_query=" + Uri.encode(query) + "&sp=EgIQAw%253D%253D&gl=" + Style.youtubeRegion();
    }

    private static final Pattern PLAYLIST_ID = Pattern.compile("(PL|OLAK5uy_)[A-Za-z0-9_\\-]{10,60}");

    /**
     * Playlists from the search page: real song collections (15 to 500 videos), nothing with a blocked word in its
     * title (jukeboxes, mashups, lofi edits… unless the card is about them), and for international music no Indian
     * labels or Hindi titles.
     */
    static List<Pick> playlists(String html, Picks.Tune tune, String language) throws Exception {
        Matcher m = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL).matcher(html);
        List<Pick> out = new ArrayList<>();
        if (!m.find()) return out;
        boolean english = !Picks.indian(language);
        JSONArray sections = new JSONObject(m.group(1)).getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer").getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer").getJSONArray("contents");
        for (int s = 0; s < sections.length(); s++) {
            JSONObject isr = sections.getJSONObject(s).optJSONObject("itemSectionRenderer");
            JSONArray items = isr != null ? isr.optJSONArray("contents") : null;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject lock = items.getJSONObject(i).optJSONObject("lockupViewModel");
                if (lock == null || !"LOCKUP_CONTENT_TYPE_PLAYLIST".equals(lock.optString("contentType"))) continue;
                String id = lock.optString("contentId");
                if (!PLAYLIST_ID.matcher(id).matches()) continue;
                JSONObject meta = lock.optJSONObject("metadata");
                meta = meta != null ? meta.optJSONObject("lockupMetadataViewModel") : null;
                String title = meta != null && meta.optJSONObject("title") != null ? meta.getJSONObject("title").optString("content") : "";
                String owner = "";
                JSONObject cm = meta != null && meta.optJSONObject("metadata") != null
                        ? meta.getJSONObject("metadata").optJSONObject("contentMetadataViewModel") : null;
                JSONArray rows = cm != null ? cm.optJSONArray("metadataRows") : null;
                JSONArray parts = rows != null && rows.length() > 0 ? rows.getJSONObject(0).optJSONArray("metadataParts") : null;
                if (parts != null && parts.length() > 0 && parts.getJSONObject(0).optJSONObject("text") != null) {
                    owner = parts.getJSONObject(0).getJSONObject("text").optString("content");
                }
                int videos = videoCount(lock.toString());
                if (videos < 15 || videos > 500 || blocked(title, tune.allow) || blocked(owner, tune.allow)) continue;
                if (english && (indianLabel(owner) || title.matches(".*[\\u0900-\\u097F].*"))) continue;
                out.add(new Pick(id, true, shortTitle(title), owner, thumbnail(lock.toString()), 0, videos));
            }
        }
        return out;
    }

    /** The largest i.ytimg.com picture in a search result, or null. */
    static String thumbnail(String json) {
        json = json.replace("\\/", "/");   // JSONObject.toString() writes "/" as "\/"
        Matcher m = Pattern.compile("\"url\":\"(https://i\\.ytimg\\.com/[^\"]+?)\"").matcher(json);
        String best = null;
        while (m.find()) best = m.group(1).replace("\\u0026", "&");   // the last listed is the largest
        return best;
    }

    /** "24 videos" (or "1.2K videos") from the playlist's thumbnail badge; 0 when not shown. */
    static int videoCount(String lockupJson) {
        Matcher m = Pattern.compile("\"text\":\"([0-9][0-9,.]*)(K?) videos?\"").matcher(lockupJson);
        if (!m.find()) return 0;
        try {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            return (int) Math.round("K".equals(m.group(2)) ? n * 1000 : n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** YouTube search limited to videos (no channels, playlists or shorts shelves), and maybe to recent ones. */
    private static String searchUrl(String query, String filter) {
        return "https://www.youtube.com/results?search_query=" + Uri.encode(query) + "&sp=" + filter + "&gl=" + Style.youtubeRegion();
    }

    /**
     * Single songs from the search page, from any channel that is verified or has at least 50,000 views, the card's
     * length (1.5 to 10 minutes for most), and nothing with a blocked word in its title or channel unless the card is
     * about it. For international music, Indian labels and Hindi titles are left out. A singer's card keeps songs
     * naming the singer when there are enough of them.
     */
    static List<Pick> videos(String html, Picks.Tune tune, String language) throws Exception {
        Matcher m = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>", Pattern.DOTALL).matcher(html);
        List<Pick> out = new ArrayList<>(), named = new ArrayList<>(), trusted = new ArrayList<>();
        if (!m.find()) return out;
        boolean english = !Picks.indian(language);
        String artist = tune.artist != null ? squash(tune.artist) : null;
        JSONArray sections = new JSONObject(m.group(1)).getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer").getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer").getJSONArray("contents");
        for (int s = 0; s < sections.length() && out.size() < 25; s++) {
            JSONObject isr = sections.getJSONObject(s).optJSONObject("itemSectionRenderer");
            JSONArray items = isr != null ? isr.optJSONArray("contents") : null;
            for (int i = 0; items != null && i < items.length() && out.size() < 25; i++) {
                JSONObject v = items.getJSONObject(i).optJSONObject("videoRenderer");
                if (v == null) continue;
                String title = text(v.optJSONObject("title")), channel = text(v.optJSONObject("ownerText"));
                boolean verified = badge(v.optJSONArray("ownerBadges")) != null;
                JSONObject vc = v.optJSONObject("viewCountText");
                if (!verified && !tune.live && views(vc != null ? vc.optString("simpleText") : "") < MIN_VIEWS) continue;
                int secs = seconds(v.optJSONObject("lengthText") != null ? v.getJSONObject("lengthText").optString("simpleText") : "");
                if (tune.live ? secs != 0 : secs < tune.minSeconds || secs > tune.maxSeconds) continue;   // live: no length
                if (blocked(title, tune.allow) || blocked(channel, tune.allow)) continue;
                if (tune.must != null && !tune.must.matcher(title).find()) continue;
                if (tune.never != null && tune.never.matcher(title).find()) continue;
                if (english && tune.artist == null && (indianLabel(channel) || title.matches(".*[\\u0900-\\u097F].*"))) continue;
                String id = v.getString("videoId");
                if (!id.matches("[A-Za-z0-9_\\-]{11}")) continue;
                String thumb = thumbnail(String.valueOf(v.optJSONObject("thumbnail")));
                Pick p = new Pick(id, false, shortTitle(title), channel, thumb != null ? thumb : "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg", secs, 0);
                out.add(p);
                boolean isNamed = artist == null || squash(title + " " + channel).contains(artist);
                if (artist != null && isNamed) named.add(p);
                if (tune.verified && verified && isNamed) trusted.add(p);
            }
        }
        if (trusted.size() >= 3) return trusted;
        return named.size() >= 3 ? named : out;
    }

    /** Lower case letters and digits only, accents dropped: "A. R. Rahman" and "AR Rahman" both "arrahman". */
    static String squash(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    /** "1,234,567 views" in numbers; 0 when not shown. */
    static long views(String text) {
        Matcher m = Pattern.compile("([0-9][0-9,]*)\\s+views?").matcher(text == null ? "" : text);
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean indianLabel(String channel) {
        String c = channel.toLowerCase(Locale.ROOT);
        for (String l : INDIAN_LABELS) if (c.contains(l)) return true;
        return false;
    }

    private static String text(JSONObject o) {
        if (o == null) return "";
        JSONArray runs = o.optJSONArray("runs");
        StringBuilder b = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) b.append(runs.optJSONObject(i).optString("text"));
        return b.toString();
    }

    /** The channel's verification badge style, or null when it is not verified. */
    private static String badge(JSONArray badges) {
        for (int i = 0; badges != null && i < badges.length(); i++) {
            JSONObject r = badges.optJSONObject(i).optJSONObject("metadataBadgeRenderer");
            String style = r != null ? r.optString("style") : "";
            if (style.equals("BADGE_STYLE_TYPE_VERIFIED") || style.equals("BADGE_STYLE_TYPE_VERIFIED_ARTIST")) return style;
        }
        return null;
    }

    /** A blocked word in the text, other than ones the card is about (allow "remix" lets "dj remix" through). */
    static boolean blocked(String s, String[] allow) {
        String l = s.toLowerCase(Locale.ROOT);
        outer:
        for (String w : BLOCKED) {
            for (String a : allow) if (!a.isEmpty() && (w.contains(a) || a.contains(w))) continue outer;
            if (w.length() <= 3) {
                if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(l).find()) return true;
            } else if (l.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** "4:19" or "1:02:03" in seconds; 0 when unknown (a live stream). */
    private static int seconds(String t) {
        if (t == null || t.isEmpty()) return 0;
        int total = 0;
        for (String p : t.split(":")) {
            try {
                total = total * 60 + Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }

    /** Search: up to `max` YouTube videos for any words (any channel that is verified or well watched; no Shorts). */
    static List<Pick> searchVideos(String query, int max) {
        try {
            Picks.Tune t = new Picks.Tune(query).length(30, 4 * 3600).noTail().allow("jukebox,mashup,nonstop,full album,all songs,status,instrumental,karaoke,lofi,lo-fi,slowed,reverb,dj remix,dance video,cover by");
            List<Pick> out = videos(fetch(searchUrl(query, t.filter)), t, "Hindi");
            return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** A YouTube page, as a desktop browser would get it. */
    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Accept-Language", Style.englishLocale() + ",en;q=0.8");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
