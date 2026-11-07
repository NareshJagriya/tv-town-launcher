package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tags for My Movies: up to three chosen automatically per film (from TMDB keywords and moods, Wikidata genres,
 * awards and India's certificate), and any number added by hand, including tags typed in by the viewer. Also the
 * viewer's favourites. A tag removed by hand stays removed.
 */
final class Tags {

    private Tags() {
    }

    /** At most this many automatic tags per film, so the cards stay tidy. */
    static final int MAX_AUTO = 3;

    /** A tag: its name, an emoji, and how it is found (a keyword pattern; null for tags only added by hand). */
    static final class Tag {
        final String name, emoji;
        final int group;          // 1 specific themes, 2 moods, 3 broad; picked in that order
        final Pattern keywords;   // matched against TMDB keywords and Wikidata genres

        Tag(String emoji, String name, int group, String keywords) {
            this.emoji = emoji;
            this.name = name;
            this.group = group;
            this.keywords = keywords == null ? null : Pattern.compile("(?i)\\b(" + keywords + ")\\b");
        }
    }

    static final String AWARD = "Award Winner", FAMILY = "Family-Friendly", FEEL_GOOD = "Feel-Good & Easy",
            PERIOD = "Period & Historical";

    static final Tag[] ALL = {
            new Tag("🌀", "Mind-Bending & Twists", 1, "mind-bending|twist ending|plot twist|surprise ending|dream world"
                    + "|alternate reality|parallel universe|nonlinear timeline|unreliable narrator|psychological thriller|amnesia"),
            new Tag("⏳", "Time Travel & Loops", 1, "time travel|time loop|time machine|time paradox|temporal paradox"),
            new Tag("🤖", "AI & Tech", 1, "artificial intelligence|robot|android|virtual reality|hacker|hacking|cyberpunk"
                    + "|computer virus|simulation"),
            new Tag("🏙️", "Dystopia & Society", 1, "dystopia|dystopian|totalitarian|post-apocalyptic|surveillance"
                    + "|authoritarian|police state"),
            new Tag("📰", "True Story", 1, "based on true story|based on a true story|biography|biographical|based on memoir"
                    + "|based on real person|biopic|true crime"),
            new Tag("🦸", "Superhero", 1, "superhero|super power|superpower|based on comic"),
            new Tag("🗡️", "Revenge", 1, "revenge|vengeance|vigilante"),
            new Tag("💰", "Heist & Con", 1, "heist|con artist|con man|robbery|bank robbery|swindle|scam"),
            new Tag("😢", "Tearjerker", 2, "tearjerker|heartbreaking|grief|melancholy|sad"),
            new Tag("🌑", "Dark & Intense", 2, "disturbing|serial killer|psychopath|brutal|grim|neo-noir|dark(?!\\s*comedy)"),
            new Tag("🃏", "Dark Comedy & Satire", 2, "dark comedy|black comedy|satire|satirical"),
            new Tag("🌟", "Inspirational", 2, "inspirational|underdog|overcoming adversity|motivational|sports"),
            new Tag("💭", "Thought-Provoking", 2, "thoughtful|philosophical|philosophy|existential|introspective"
                    + "|intellectual|meaning of life"),
            new Tag("😊", FEEL_GOOD, 2, "feel good|feel-good|lighthearted|light-hearted|cheerful|heartwarming|wholesome"),
            new Tag("🏰", PERIOD, 3, "period drama|period film|historical|19th century|18th century|ancient|medieval"
                    + "|world war|1920s|1930s|1940s|1950s"),
            new Tag("🏆", AWARD, 3, null),
            new Tag("👪", FAMILY, 3, null),
            new Tag("🕯️", "Slow Burn", 4, null),
            new Tag("🚪", "One Location", 4, null),
            new Tag("💎", "Hidden Gem", 4, null),
    };

    static Tag find(String name) {
        for (Tag t : ALL) if (t.name.equalsIgnoreCase(name)) return t;
        return null;
    }

    static String emoji(String name) {
        Tag t = find(name);
        return t != null ? t.emoji : "🏷";
    }

    /** What is known about a film, for choosing its tags. */
    static final class Facts {
        final List<String> keywords = new ArrayList<>();   // TMDB keywords and Wikidata genres, lower case
        final Set<Integer> genres = new java.util.HashSet<>();   // TMDB genre ids
        String certificate = "";   // the streaming country's: U, UA, U/A 13+, A… in India; G, PG, PG-13… in the US
        boolean award;
    }

    /**
     * Up to MAX_AUTO tags: those with clear evidence first (a keyword, an award, a family certificate), specific
     * themes before moods before broad ones. Only when nothing has clear evidence, a guess from the genres
     * (Feel-Good for a comedy or family film with nothing dark in it; Period for a history or war film).
     */
    static List<String> auto(Facts f) {
        List<String> strong = new ArrayList<>();
        for (int group = 1; group <= 3; group++) {
            for (Tag t : ALL) {
                if (t.group != group) continue;
                boolean hit = false;
                if (t.keywords != null) for (String k : f.keywords) if (t.keywords.matcher(k).find()) hit = true;
                if (t.name.equals(AWARD)) hit = f.award;
                if (t.name.equals(FAMILY)) hit = familyCertificate(f.certificate) && !f.genres.contains(27);
                if (hit) strong.add(t.name);
            }
        }
        if (!strong.isEmpty()) return new ArrayList<>(strong.subList(0, Math.min(MAX_AUTO, strong.size())));
        List<String> weak = new ArrayList<>();
        boolean dark = f.genres.contains(27) || f.genres.contains(53) || f.genres.contains(80) || f.genres.contains(10752);
        if (!dark && (f.genres.contains(35) || f.genres.contains(10751) || f.genres.contains(16))) weak.add(FEEL_GOOD);
        if (f.genres.contains(36) || f.genres.contains(10752) || f.genres.contains(10768)) weak.add(PERIOD);
        return weak;
    }

    static boolean familyCertificate(String c) {
        String s = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
        return s.equals("U") || s.equals("UA") || s.equals("U/A") || s.startsWith("UA 7") || s.startsWith("U/A 7")
                // The US, UK, Canada and Australia (the streaming country's certificate is used)
                || s.equals("G") || s.equals("PG") || s.equals("TV-Y") || s.equals("TV-Y7") || s.equals("TV-G")
                || s.equals("TV-PG");
    }

    /** Wikidata's genres for a title, lower case ("black comedy film", "biographical film"); empty when unknown. */
    static List<String> wikidataGenres(Context ctx, String type, int id) {
        List<String> out = new ArrayList<>();
        String prop = "tv".equals(type) ? "P4983" : "P4947";
        String q = "SELECT ?l WHERE { ?f wdt:" + prop + " \"" + id + "\" . ?f wdt:P136 ?g . ?g rdfs:label ?l . "
                + "FILTER(LANG(?l) = \"en\") }";
        try {
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Accept", "application/sparql-results+json");
            h.put("User-Agent", "TVTownLauncher/" + BuildConfig.VERSION_NAME + " (Android TV launcher)");
            String url = "https://query.wikidata.org/sparql?format=json&query=" + java.net.URLEncoder.encode(q, "UTF-8");
            org.json.JSONArray rows = new org.json.JSONObject(Net.cached(ctx, url, h, 30L * 24 * 3600_000, 365L * 24 * 3600_000, 15_000))
                    .getJSONObject("results").getJSONArray("bindings");
            for (int i = 0; i < rows.length(); i++) {
                String l = rows.getJSONObject(i).getJSONObject("l").optString("value");
                if (!l.isEmpty()) out.add(l.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
            // slow or offline: TMDB's keywords alone
        }
        return out;
    }

    // ---- the viewer's changes ----------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("my_movies", Context.MODE_PRIVATE);
    }

    private static List<String> list(Context ctx, String key) {
        String s = prefs(ctx).getString(key, "");
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    private static void save(Context ctx, String key, Collection<String> l) {
        prefs(ctx).edit().putString(key, String.join("\n", l)).apply();
    }

    /** A film's tags: its automatic ones (less those removed by hand), then those added by hand, without repeats. */
    static List<String> of(Context ctx, String film, List<String> auto) {
        Set<String> out = new LinkedHashSet<>(list(ctx, "tags+:" + film));
        List<String> removed = list(ctx, "tags-:" + film);
        for (String t : auto) if (!removed.contains(t)) out.add(t);
        return new ArrayList<>(out);
    }

    /** Tags added by hand to this film (shown first, highlighted). */
    static List<String> added(Context ctx, String film) {
        return list(ctx, "tags+:" + film);
    }

    /** Switches a tag on or off for a film. An automatic tag switched off stays off. */
    static void set(Context ctx, String film, String tag, boolean on, List<String> auto) {
        List<String> added = list(ctx, "tags+:" + film), removed = list(ctx, "tags-:" + film);
        added.remove(tag);
        removed.remove(tag);
        if (on && !auto.contains(tag)) added.add(tag);
        if (!on && auto.contains(tag)) removed.add(tag);
        save(ctx, "tags+:" + film, added);
        save(ctx, "tags-:" + film, removed);
    }

    /** Every tag that can be chosen: the built-in ones, then those the viewer has typed in. */
    static List<String> choices(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Tag t : ALL) out.add(t.name);
        for (String c : list(ctx, "tags_custom")) if (!out.contains(c)) out.add(c);
        return out;
    }

    /** A tag typed in by the viewer, kept for every film; returns it tidied, or null when empty or too long. */
    static String addCustom(Context ctx, String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 30) return null;
        Tag known = find(n);
        if (known != null) return known.name;
        List<String> custom = list(ctx, "tags_custom");
        for (String c : custom) if (c.equalsIgnoreCase(n)) return c;
        custom.add(n);
        save(ctx, "tags_custom", custom);
        return n;
    }

    // ---- favourites ----------------------------------------------------------------------------------

    static boolean favourite(Context ctx, String film) {
        return prefs(ctx).getBoolean("fav:" + film, false);
    }

    static void setFavourite(Context ctx, String film, boolean on) {
        prefs(ctx).edit().putBoolean("fav:" + film, on).apply();
    }
}
