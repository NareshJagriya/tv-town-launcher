package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * What the Random pop-up offers: up to 12 cards per kind (Comedy, Thriller & Crime… for movies and series; Romantic,
 * Party & Dance, Top Singers… for music; trailers, stand-up, classic TV, news… for Extras), and languages. A card
 * covers several parts (Thriller & Crime: thrillers, heists, courtroom dramas…; Top Singers: each singer) and each
 * pick uses one of them at random, so one card gives variety without more choices on screen.
 *
 * Everything is customisable from the pop-up and remembered: any part can be switched off, cards made of names
 * (singers, artists, comedians, shows, channels) and phrases take new ones typed in, and the language list shows
 * only the languages you choose.
 */
final class Picks {

    private Picks() {
    }

    static final int MOVIE = 0, SERIES = 1, SONG = 2, EXTRAS = 3, SEARCH = 4;   // SEARCH: the Search pop-up

    /** A card, or one of the parts a card covers. */
    static final class Entry {
        final String emoji, label;
        final int color;
        final List<Entry> parts = new ArrayList<>();
        Tmdb.Category category;   // a part for movies and series
        Tune tune;                // a part for music and videos
        boolean myMix;            // the viewer's own YouTube mix
        boolean off;              // a part switched off in Customize
        boolean added;            // a part typed in by the viewer
        boolean own;              // a card made by the viewer (➕ New card)
        boolean hidden;           // a card hidden in Customize (kept there, not on the pop-up)
        java.util.function.Function<String, Tune> adder;   // a card: how a typed name becomes a part (null: none)
        String addHint;           // a card: what to type ("A singer's name")
        boolean asks;             // an Extras tile that asks first (Topics): Recipes, Podcasts…
        String topicSig;          // a card built from what was ticked (Topics.card): kept apart from other choices

        Entry(String emoji, String label, int color) {
            this.emoji = emoji;
            this.label = label;
            this.color = color;
        }

        /** Stable name for remembering what it has given: "Thriller & Crime". */
        String key() {
            return topicSig == null ? label : label + "#" + topicSig;
        }

        /** The parts in use (all of them when every one is switched off). */
        List<Entry> on() {
            List<Entry> out = new ArrayList<>();
            for (Entry p : parts) if (!p.off) out.add(p);
            return out.isEmpty() ? parts : out;
        }

        /** True when some of its parts follow the language list (so the list matters for it). */
        boolean followsLanguage() {
            for (Entry p : on()) {
                if (p.tune != null ? p.tune.language == null && p.tune.artist == null
                        : p.category != null && p.category.language == null) return true;
            }
            return false;
        }
    }

    /** A kind of music or video: YouTube searches ("|" between wordings; {lang} and {year} filled in) and what to accept. */
    static final class Tune {
        final String[] queries;
        String language;             // null = the chosen languages; else used only when this language is chosen
        String artist;               // prefer results naming the artist (or channel, or show)
        String[] allow = {};         // words normally blocked (lofi, mashup…) that this kind is about
        int minSeconds = 90, maxSeconds = 600;
        boolean tail = true;         // add "video song" / "official music video" to song searches
        boolean live;                // live streams (news)
        String filter = "EgIQAQ%253D%253D";   // YouTube search filter: videos only
        java.util.regex.Pattern must, never;  // the title must match / must not match
        boolean verified;            // prefer verified channels

        Tune(String queries) {
            this.queries = queries.split("\\|");
        }

        Tune lang(String l) { language = l; return this; }
        Tune allow(String words) { allow = words.split(","); return this; }
        Tune length(int min, int max) { minSeconds = min; maxSeconds = max; return this; }
        Tune noTail() { tail = false; return this; }
        Tune thisWeek() { filter = "EgQIAxAB"; return this; }    // videos uploaded this week
        Tune thisMonth() { filter = "EgQIBBAB"; return this; }   // videos uploaded this month
        Tune live() { live = true; filter = "EgJAAQ%253D%253D"; tail = false; return this; }   // live now
        Tune must(String regex) { must = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE); return this; }
        Tune never(String regex) { never = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE); return this; }
        Tune verified() { verified = true; return this; }
        Tune artist(String a) { artist = a; return this; }
    }

    // ---- languages -------------------------------------------------------------------------------

    /** Every language that can be put on the list: name and TMDB original-language code. */
    static final String[][] TITLE_LANGUAGES = {{"Hindi", "hi"}, {"English", "en"}, {"Marathi", "mr"}, {"Gujarati", "gu"},
            {"Malayalam", "ml"}, {"Korean", "ko"}, {"Tamil", "ta"}, {"Telugu", "te"}, {"Kannada", "kn"}, {"Bengali", "bn"},
            {"Punjabi", "pa"}, {"Japanese", "ja"}, {"Spanish", "es"}, {"French", "fr"}, {"German", "de"},
            {"Italian", "it"}, {"Portuguese", "pt"}, {"Chinese", "zh"}};
    static final String[] SONG_LANGUAGES = {"Hindi", "English", "Marathi", "Punjabi", "Gujarati", "Tamil", "Telugu",
            "Malayalam", "Bengali", "Korean"};
    private static final List<String> TITLE_DEFAULT = Arrays.asList("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Korean");
    private static final List<String> SONG_DEFAULT = Arrays.asList("Hindi", "English", "Punjabi");

    // Global: a short list, world languages first; Mixed: both sides' main ones.
    private static final String[] GLOBAL_TITLES = {"English", "Hindi", "Korean", "Spanish", "French", "Japanese", "German",
            "Italian", "Portuguese", "Chinese", "Tamil", "Telugu"};
    private static final String[] GLOBAL_SONGS = {"English", "Hindi", "Korean", "Spanish", "French", "Portuguese",
            "Japanese", "Punjabi", "Tamil", "Telugu"};
    private static final String[] MIXED_TITLES = {"Hindi", "English", "Korean", "Marathi", "Spanish", "Japanese", "Tamil",
            "Telugu", "Malayalam", "French"};
    private static final String[] MIXED_SONGS = {"Hindi", "English", "Punjabi", "Korean", "Marathi", "Spanish", "Tamil",
            "Telugu"};

    /** Every language a kind can list, in order. */
    static List<String> allLanguages(int kind) {
        if (Style.global()) return Arrays.asList(kind == SONG ? GLOBAL_SONGS : GLOBAL_TITLES);
        if (Style.MIXED.equals(Style.get())) return Arrays.asList(kind == SONG ? MIXED_SONGS : MIXED_TITLES);
        if (kind == SONG) return Arrays.asList(SONG_LANGUAGES);
        List<String> out = new ArrayList<>();
        for (String[] l : TITLE_LANGUAGES) if (out.size() < 13) out.add(l[0]);
        return out;
    }

    /** The languages on a kind's list until the viewer changes it. */
    private static List<String> defaultShown(int kind) {
        List<String> base = Style.indian() ? kind == SONG ? SONG_DEFAULT : TITLE_DEFAULT
                : Style.global() ? Arrays.asList("English", "Hindi", "Korean")
                : kind == SONG ? Arrays.asList("Hindi", "English", "Punjabi", "Korean") : Arrays.asList("Hindi", "English", "Korean");
        List<String> out = new ArrayList<>();   // the viewer's own languages (Welcome, Customize) first
        for (String l : mine()) if (allLanguages(kind).contains(l)) out.add(l);
        for (String l : base) if (!out.contains(l)) out.add(l);
        return out;
    }

    /** The languages ticked until the viewer changes them: the viewer's main language. */
    private static List<String> defaultTicked() {
        List<String> m = mine();
        if (!m.isEmpty()) return Arrays.asList(m.get(0));
        return Style.global() ? Arrays.asList("English") : Arrays.asList("Hindi");
    }

    /** The viewer's languages by name ("Hindi", "English"). */
    private static List<String> mine() {
        List<String> out = new ArrayList<>();
        Context ctx = TvTownApp.context();
        if (ctx == null) return out;
        for (String code : Style.myLanguages(ctx)) out.add(Style.languageName(code));
        return out;
    }

    /** Music languages searched with Indian wordings ("video song"); the rest are searched as international. */
    static boolean indian(String language) {
        return Arrays.asList("Hindi", "Marathi", "Punjabi", "Gujarati", "Tamil", "Telugu", "Malayalam", "Bengali", "Kannada")
                .contains(language);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private static String group(int kind) {
        return kind == SONG ? "songs" : kind == EXTRAS ? "extras" : "titles";
    }

    private static List<String> readList(Context ctx, String key, List<String> fallback) {
        String s = prefs(ctx).getString(key, null);
        if (s == null) return new ArrayList<>(fallback);
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    /** The languages shown on the pop-up's list (chosen in Customize), in the usual order. */
    static List<String> shownLanguages(Context ctx, int kind) {
        List<String> chosen = readList(ctx, Style.key("pick_lang_list_" + group(kind)), defaultShown(kind));
        List<String> out = new ArrayList<>();
        for (String l : allLanguages(kind)) if (chosen.contains(l)) out.add(l);
        if (out.isEmpty()) out.add(allLanguages(kind).get(0));
        return out;
    }

    static void setShown(Context ctx, int kind, String language, boolean show) {
        List<String> l = shownLanguages(ctx, kind);
        l.remove(language);
        if (show) l.add(language);
        prefs(ctx).edit().putString(Style.key("pick_lang_list_" + group(kind)), String.join("\n", l)).apply();
        if (!show) setTicked(ctx, kind, language, false);
    }

    /** The languages ticked (empty = Any), among those shown. Hindi at first (English for Global). */
    static List<String> ticked(Context ctx, int kind) {
        List<String> on = readList(ctx, Style.key("pick_lang_on_" + group(kind)), kind == EXTRAS ? new ArrayList<>() : defaultTicked());
        List<String> out = new ArrayList<>();
        for (String l : shownLanguages(ctx, kind)) if (on.contains(l)) out.add(l);
        return out;
    }

    static void setTicked(Context ctx, int kind, String language, boolean on) {
        List<String> l = readList(ctx, Style.key("pick_lang_on_" + group(kind)), kind == EXTRAS ? new ArrayList<>() : defaultTicked());
        l.remove(language);
        if (on) l.add(language);
        prefs(ctx).edit().putString(Style.key("pick_lang_on_" + group(kind)), String.join("\n", l)).apply();
    }

    /** Whether the viewer has chosen languages for a kind (else the defaults apply). */
    static boolean languagesSet(Context ctx, int kind) {
        return prefs(ctx).contains(Style.key("pick_lang_on_" + group(kind)));
    }

    static void clearTicked(Context ctx, int kind) {
        prefs(ctx).edit().putString(Style.key("pick_lang_on_" + group(kind)), "").apply();
    }

    /** "Hindi + English", or "Any". */
    static String languageSummary(List<String> ticked) {
        return ticked.isEmpty() ? "Any" : String.join(" + ", ticked);
    }

    /** TMDB language codes for ticked languages; [""] for Any. */
    static List<String> titleLanguages(List<String> ticked) {
        List<String> out = new ArrayList<>();
        for (String[] l : TITLE_LANGUAGES) if (ticked.contains(l[0])) out.add(l[1]);
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** The music languages to search in: the ticked ones, or for Any every language on the list. */
    static List<String> songLanguages(List<String> ticked, List<String> shown) {
        return ticked.isEmpty() ? new ArrayList<>(shown) : new ArrayList<>(ticked);
    }

    // ---- customising cards -----------------------------------------------------------------------

    private static SharedPreferences custom(Context ctx) {
        return ctx.getSharedPreferences("pick_custom", Context.MODE_PRIVATE);
    }

    private static JSONObject customOf(Context ctx, int kind, Entry card) {
        try {
            return new JSONObject(custom(ctx).getString(Style.key(kind + "/" + card.label), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static List<String> strings(JSONObject o, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = o.optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static void saveCustom(Context ctx, int kind, Entry card, List<String> off, List<String> added) {
        try {
            JSONObject o = new JSONObject().put("off", new JSONArray(off)).put("add", new JSONArray(added));
            custom(ctx).edit().putString(Style.key(kind + "/" + card.label), o.toString()).apply();
        } catch (Exception ignored) {
            // not saved
        }
    }

    /** Switches a part off (or back on). */
    static void setOff(Context ctx, int kind, Entry card, Entry part, boolean off) {
        JSONObject o = customOf(ctx, kind, card);
        List<String> offList = strings(o, "off"), added = strings(o, "add");
        offList.remove(part.label);
        if (off) offList.add(part.label);
        saveCustom(ctx, kind, card, offList, added);
    }

    /** Adds a typed name (a singer, a show, a channel, a phrase) to a card. */
    static void addName(Context ctx, int kind, Entry card, String name) {
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 60 || card.adder == null) return;
        JSONObject o = customOf(ctx, kind, card);
        List<String> off = strings(o, "off"), added = strings(o, "add");
        for (Entry p : card.parts) if (p.label.equalsIgnoreCase(n)) {   // already there: just switch it on
            off.remove(p.label);
            saveCustom(ctx, kind, card, off, added);
            return;
        }
        added.add(n);
        saveCustom(ctx, kind, card, off, added);
    }

    /** Removes a name the viewer added. */
    static void removeName(Context ctx, int kind, Entry card, String name) {
        JSONObject o = customOf(ctx, kind, card);
        List<String> off = strings(o, "off"), added = strings(o, "add");
        added.remove(name);
        off.remove(name);
        saveCustom(ctx, kind, card, off, added);
    }

    /** The most cards the pop-up shows (three rows of four). */
    static final int MAX_CARDS = 12;
    private static final int[] OWN_COLOURS = {0xFF5B8CFF, 0xFFFF6FA5, 0xFF3DDC97, 0xFFFFC93C, 0xFF9D6BFF, 0xFF36C8F4};

    /** The cards on the pop-up: every card not hidden, at most MAX_CARDS. */
    static List<Entry> cards(Context ctx, int kind) {
        List<Entry> out = new ArrayList<>();
        for (Entry c : allCards(ctx, kind)) if (!c.hidden && out.size() < MAX_CARDS) out.add(c);
        return out;
    }

    /** Only Music and Extras take cards of your own (they are YouTube searches). */
    static boolean takesOwnCards(int kind) {
        return kind == SONG || kind == EXTRAS;
    }

    /** What a typed search becomes: a song search for Music, any video (up to four hours) for Extras. */
    private static Tune ownTune(int kind, String phrase) {
        return kind == SONG ? new Tune(phrase) : new Tune(phrase).length(60, 4 * 3600).noTail();
    }

    private static List<String> ownNames(Context ctx, int kind) {
        return readList(ctx, Style.key("pick_own_" + kind), new ArrayList<>());
    }

    private static List<String> hiddenNames(Context ctx, int kind) {
        return readList(ctx, Style.key("pick_hidden_" + kind), new ArrayList<>());
    }

    /** A new card of your own, named as typed; it starts by searching for its name. False when not possible. */
    static boolean addCard(Context ctx, int kind, String name) {
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 40 || !takesOwnCards(kind) || cards(ctx, kind).size() >= MAX_CARDS) return false;
        for (Entry c : allCards(ctx, kind)) if (c.label.equalsIgnoreCase(n)) return false;
        List<String> own = ownNames(ctx, kind);
        own.add(n);
        prefs(ctx).edit().putString(Style.key("pick_own_" + kind), String.join("\n", own)).apply();
        return true;
    }

    /** Deletes a card of your own, with what was added to it. */
    static void deleteCard(Context ctx, int kind, Entry card) {
        List<String> own = ownNames(ctx, kind);
        own.remove(card.label);
        prefs(ctx).edit().putString(Style.key("pick_own_" + kind), String.join("\n", own)).apply();
        custom(ctx).edit().remove(Style.key(kind + "/" + card.label)).apply();
    }

    /** Hides a card from the pop-up, or shows it again. False when that is not possible (the last card, or no room). */
    static boolean setHidden(Context ctx, int kind, Entry card, boolean hide) {
        int shown = cards(ctx, kind).size();
        if (hide ? shown <= 1 : shown >= MAX_CARDS) return false;
        List<String> l = hiddenNames(ctx, kind);
        l.remove(card.label);
        if (hide) l.add(card.label);
        prefs(ctx).edit().putString(Style.key("pick_hidden_" + kind), String.join("\n", l)).apply();
        return true;
    }

    /**
     * Every card of a kind with the viewer's changes, hidden ones included (marked): parts switched off are marked,
     * typed-in names are added, and the viewer's own cards come after the built-in ones.
     */
    static List<Entry> allCards(Context ctx, int kind) {
        List<Entry> cards = cards(kind);
        if (takesOwnCards(kind)) {
            List<String> own = ownNames(ctx, kind);
            for (int i = 0; i < own.size(); i++) {
                Entry c = new Entry(kind == SONG ? "🎵" : "⭐", own.get(i), OWN_COLOURS[i % OWN_COLOURS.length]);
                c.own = true;
                c.adder = phrase -> ownTune(kind, phrase);
                c.addHint = "Something to search for on YouTube";
                Entry p = new Entry(c.emoji, own.get(i), c.color);
                p.tune = ownTune(kind, own.get(i));
                c.parts.add(p);
                cards.add(c);
            }
        }
        List<String> hidden = hiddenNames(ctx, kind);
        for (Entry c : cards) {
            c.hidden = hidden.contains(c.label);
            JSONObject o = customOf(ctx, kind, c);
            List<String> off = strings(o, "off");
            if (c.adder != null) {
                for (String name : strings(o, "add")) {
                    Entry p = new Entry(c.emoji, name, c.color);
                    p.tune = c.adder.apply(name);
                    p.added = true;
                    c.parts.add(p);
                }
            }
            for (Entry p : c.parts) p.off = off.contains(p.label);
        }
        return cards;
    }

    // ---- choosing a part -------------------------------------------------------------------------

    /**
     * One of a movie or series card's parts at random, among those in the chosen languages (a part with a language of
     * its own, such as 90s Bollywood, only when that language is chosen or Any).
     */
    static Entry titlePart(Entry card, List<String> codes, Random r) {
        boolean any = codes.contains("");
        List<Entry> ok = new ArrayList<>();
        for (Entry p : card.on()) {
            String l = p.category.language;
            if (l == null || any || codes.contains(l)) ok.add(p);
        }
        if (ok.isEmpty()) ok = card.on();
        return ok.get(r.nextInt(ok.size()));
    }

    /**
     * One of a music card's parts at random, in one of the chosen languages: parts that follow the language list
     * work for all of them; a part with its own language (a Marathi singer) only when it is chosen. Extras
     * (anyLanguage) use every part.
     */
    static Entry tunePart(Entry card, List<String> languages, boolean anyLanguage, Random r) {
        List<Entry> ok = new ArrayList<>();
        for (Entry p : card.on()) if (anyLanguage || p.tune.language == null || languages.contains(p.tune.language)) ok.add(p);
        if (ok.isEmpty()) ok = card.on();
        return ok.get(r.nextInt(ok.size()));
    }

    /** The language a part is searched in: its own, or one of the chosen ones. */
    static String songLanguage(Tune t, List<String> chosen, Random r) {
        return t.language != null ? t.language : chosen.get(r.nextInt(chosen.size()));
    }

    // ---- no repeats ------------------------------------------------------------------------------

    /**
     * A "shuffle bag" per choice: everything already given for this exact choice (kind, card, languages), up to 400
     * items. Picks skip these until the choice's whole pool has been shown; then the bag starts a new round.
     */
    static String bag(int kind, Entry e, List<String> ticked) {
        return kind + ":" + e.key() + ":" + (e.followsLanguage() ? String.join("+", ticked) : "");
    }

    private static SharedPreferences seenPrefs(Context ctx) {
        return ctx.getSharedPreferences("picks_seen", Context.MODE_PRIVATE);
    }

    static java.util.Set<String> seen(Context ctx, String bag) {
        java.util.Set<String> out = new java.util.HashSet<>(Arrays.asList(seenPrefs(ctx).getString(bag, "").split(",")));
        out.remove("");
        return out;
    }

    static synchronized void markSeen(Context ctx, String bag, String id) {
        List<String> list = new ArrayList<>(Arrays.asList(seenPrefs(ctx).getString(bag, "").split(",")));
        list.remove("");
        list.remove(id);
        list.add(0, id);
        while (list.size() > 400) list.remove(list.size() - 1);
        seenPrefs(ctx).edit().putString(bag, String.join(",", list)).apply();
    }

    /** Titles hidden with "Not for me": never suggested again (up to 2000 remembered). */
    static java.util.Set<String> hidden(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>(Arrays.asList(seenPrefs(ctx).getString("hidden", "").split(",")));
        out.remove("");
        return out;
    }

    /** Everything in the pool has been shown: start a new round, keeping only the last pick (no instant repeat). */
    static synchronized void newRound(Context ctx, String bag) {
        String all = seenPrefs(ctx).getString(bag, "");
        String last = all.contains(",") ? all.substring(0, all.indexOf(',')) : all;
        seenPrefs(ctx).edit().putString(bag, last).apply();
    }

    /** The cards for a kind in the current style, as built in (no customising). */
    static List<Entry> cards(int kind) {
        String s = Style.get();
        switch (kind) {
            case SONG: return Style.GLOBAL.equals(s) ? globalMusicCards() : Style.MIXED.equals(s) ? mixedMusicCards() : musicCards();
            case EXTRAS: return Style.GLOBAL.equals(s) ? globalExtrasCards() : Style.MIXED.equals(s) ? mixedExtrasCards() : extrasCards();
            default: return titleCards(kind);
        }
    }

    // ---- movies and series -----------------------------------------------------------------------

    // TMDB keyword ids.
    private static final String ROMANCE = "9840", HEIST = "10051", SPY = "470|5265", TIME_TRAVEL = "4379",
            SURVIVAL = "10349", SUPERHERO = "9715", BIOPIC = "5565|360939", COURTROOM = "33519|214780|222517",
            TRUE_STORY = "9672", PSYCHOLOGICAL = "12565", DARK_COMEDY = "10123|383021", SPORTS = "6075|294708",
            MIND_BENDING = "12565|4379|326438|362567|157171|243230|346773", ISEKAI = "237451", SLICE_OF_LIFE = "9914",
            MINISERIES = "11162";

    private static Entry card(List<Entry> out, String emoji, String label, int color) {
        Entry e = new Entry(emoji, label, color);
        out.add(e);
        return e;
    }

    /** A part of a movie or series card: a Discover category (named after the part). */
    private static Tmdb.Category part(Entry card, String label) {
        Entry p = new Entry(card.emoji, label, card.color);
        p.category = new Tmdb.Category("Random", label);
        p.category.rated(0, 30);   // real films and series, with some votes
        card.parts.add(p);
        return p.category;
    }

    /** Every movie and series card with all its parts (titleCards(kind) keeps what a type has). */
    static List<Entry> titleCards() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        List<Entry> l = new ArrayList<>();

        Entry c = card(l, "😂", "Comedy", 0xFFFFC93C);
        part(c, "Comedy").genres("35", "35");
        part(c, "Romantic Comedy").genres("10749,35", "35").tvKeywords = ROMANCE;
        part(c, "Action-Comedy").genres("28,35", "10759,35");
        part(c, "Dark Comedy").keywords(DARK_COMEDY).genres("35", "35");
        part(c, "Feel-Good").genres("35|10751", "35|10751").rated(7.0, 100).without("27,53,80");

        c = card(l, "💕", "Romance", 0xFFFF6FA5);
        part(c, "Romance").genres("10749", "18").tvKeywords = ROMANCE;
        part(c, "Romantic Drama").genres("10749,18", "18").tvKeywords = ROMANCE;

        c = card(l, "💥", "Action & Adventure", 0xFFFF6A3D);
        part(c, "Action").genres("28", "10759");
        part(c, "Adventure").genres("12", "10759");
        part(c, "Superhero").keywords(SUPERHERO);
        part(c, "Spy").keywords(SPY);
        part(c, "Survival").keywords(SURVIVAL);
        part(c, "War").genres("10752", "10768");

        c = card(l, "🔪", "Thriller & Crime", 0xFF9D6BFF);
        part(c, "Thriller").genres("53", "80|9648");
        part(c, "Crime Thriller").genres("80,53", "80,9648");
        part(c, "Psychological Thriller").keywords(PSYCHOLOGICAL);
        part(c, "Heist").keywords(HEIST);
        part(c, "Legal / Courtroom").keywords(COURTROOM);
        part(c, "Crime").genres("80", "80");

        c = card(l, "👻", "Horror", 0xFF6A5BFF);
        part(c, "Horror").genres("27", "9648,10765");
        part(c, "Horror-Comedy").genres("27,35", "35,10765");

        c = card(l, "🚀", "Sci-Fi & Mind-Benders", 0xFF36C8F4);
        part(c, "Sci-Fi").genres("878", "10765");
        part(c, "Mind-Benders").keywords(MIND_BENDING).rated(6.5, 100);
        part(c, "Time Travel").keywords(TIME_TRAVEL);
        part(c, "Mystery").genres("9648", "9648");
        part(c, "Fantasy").genres("14", "10765");

        c = card(l, "🎭", "Drama & True Stories", 0xFFFF9F43);
        part(c, "Drama").genres("18", "18");
        part(c, "Based on True Events").keywords(TRUE_STORY);
        part(c, "Biopic").keywords(BIOPIC);
        part(c, "Sports Drama").keywords(SPORTS).genres("18", "18");
        part(c, "History").genres("36", "10768");
        part(c, "Documentary").genres("99", "99");

        c = card(l, "👪", "Family & Animation", 0xFF3DDC97);
        part(c, "Family").genres("10751", "10751");
        part(c, "Animation").genres("16", "16");

        c = card(l, "💎", "Hidden Gems", 0xFF5B8CFF);
        part(c, "Hidden Gems").sort("vote_average.desc").rated(7.0, 50).votes(1500);
        part(c, "Underrated").rated(7.0, 300).votes(3000);
        part(c, "Cult Classics").years(1970, 2008).rated(7.2, 300).votes(6000);
        part(c, "Slow Burn").genres("18,9648", "18,9648").rated(7.0, 100);

        c = card(l, "🏆", "Award Winners", 0xFFFFC93C);
        part(c, "Award Winners").sort("vote_average.desc").rated(7.8, 2500).genres("", null);
        part(c, "Award-Winning Series").genres(null, "").sort("vote_average.desc").rated(8.2, 1000);
        part(c, "Top Rated").rated(7.5, 800);

        // Indian: 90s Bollywood and Indian web series; Global: Hollywood classics, British TV and prestige drama;
        // Mixed: some of each.
        boolean desi = !Style.global(), western = !Style.indian();
        c = card(l, "📼", "Classics", 0xFFB06BFF);   // movies only
        if (desi) part(c, "90s Bollywood").genres("", null).lang("hi").years(1990, 1999).rated(6.0, 30);
        if (western) part(c, "80s & 90s Hollywood").genres("", null).lang("en").years(1980, 1999).rated(7.0, 500);
        part(c, "2000s Classics").genres("", null).years(2000, 2009).rated(7.0, 200);
        part(c, "Timeless Classics").genres("", null).years(1940, 1989).rated(7.3, 300);

        c = card(l, "🍿", "Binge-Worthy", 0xFFB06BFF);   // series only
        part(c, "Weekend Binge").genres(null, "").keywords(MINISERIES).rated(7.2, 50);
        part(c, "Finished Series").genres(null, "").status("3").rated(7.4, 200);
        part(c, "Quick Episodes").genres(null, "").runtime(15, 30).rated(7.0, 100).without("10767,10763");
        if (desi) part(c, "Indian Web Series").genres(null, "").country("IN").years(2015, year).rated(6.5, 20)
                .without("10766,10767,10764,10763");
        if (western) part(c, "British TV").genres(null, "").country("GB").rated(7.3, 200).without("10766,10767,10764,10763");
        if (Style.global()) part(c, "Prestige Drama").genres(null, "18").sort("vote_average.desc").rated(8.0, 1500);

        c = card(l, "🍥", "Anime", 0xFFFF6FA5);
        part(c, "Top Anime").lang("ja").genres("16", "16").sort("vote_average.desc").rated(7.8, 300);
        part(c, "Anime Movies").lang("ja").genres("16", null).rated(7.0, 100);
        part(c, "Action Anime").lang("ja").genres("16,28", "16,10759").rated(7.0, 100);
        part(c, "Isekai").lang("ja").genres("16", "16").keywords(ISEKAI);
        part(c, "Slice of Life").lang("ja").genres("16", "16").keywords(SLICE_OF_LIFE);
        return l;
    }

    /** The cards for Random Movie or Random Series: only the parts (and cards) that type has. */
    static List<Entry> titleCards(int kind) {
        List<Entry> out = new ArrayList<>();
        for (Entry c : titleCards()) {
            Entry copy = new Entry(c.emoji, c.label, c.color);
            for (Entry p : c.parts) {
                if ((kind == MOVIE ? p.category.movieGenres : p.category.tvGenres) != null) copy.parts.add(p);
            }
            if (!copy.parts.isEmpty()) out.add(copy);
        }
        return out;
    }

    // ---- music -----------------------------------------------------------------------------------

    /** A named part made of YouTube searches. */
    private static Tune phrase(Entry card, String name, String queries) {
        Entry p = new Entry(card.emoji, name, card.color);
        p.tune = new Tune(queries);
        card.parts.add(p);
        return p.tune;
    }

    /** A singer or band: their best-known songs, any channel. */
    static Tune artistTune(String name) {
        return new Tune(name + " songs|" + name + " hit songs|best of " + name + "|" + name + " top songs").artist(name);
    }

    private static void artists(Entry card, String language, String... names) {
        for (String name : names) {
            Entry p = new Entry(card.emoji, name, card.color);
            p.tune = artistTune(name).lang(language);
            card.parts.add(p);
        }
    }

    /** A card made of names (singers, shows, channels): each name a part, and typed names added the same way. */
    private static void names(Entry card, String hint, java.util.function.Function<String, Tune> tune, String... names) {
        card.adder = tune;
        card.addHint = hint;
        for (String name : names) {
            Entry p = new Entry(card.emoji, name, card.color);
            p.tune = tune.apply(name);
            card.parts.add(p);
        }
    }

    /** Named parts for one language (comedians who perform in Hindi…): shown when that language is ticked, or with Any. */
    private static void langNamed(Entry card, String lang, java.util.function.Function<String, Tune> tune, String... names) {
        for (String name : names) {
            Entry p = new Entry(card.emoji, name, card.color);
            p.tune = tune.apply(name).lang(lang);
            card.parts.add(p);
        }
    }

    /** Series trailers from each streaming app's own channel, as parts of the Trailers card. */
    private static void appTrailers(Entry card, String... apps) {
        for (String app : apps) {
            Entry p = new Entry(card.emoji, app + " trailers", card.color);
            p.tune = trailerTune(app).artist(app);
            card.parts.add(p);
        }
    }

    /** A card made of phrases: what you type is searched as it is. */
    private static void phrases(Entry card, java.util.function.Function<String, Tune> tune) {
        card.adder = tune;
        card.addHint = Style.global() ? "Something to search for, like \"90s summer hits\""
                : "Something to search for, like \"bollywood wedding songs\"";
    }

    private static final String DJ = "dj,remix,nonstop,non-stop,non stop,mashup,jukebox,dance video";
    private static final String LOFI = "lofi,lo-fi,slowed,reverb";

    static List<Entry> musicCards() {
        List<Entry> l = new ArrayList<>();
        Entry mix = card(l, "💜", "My Mix", 0xFFB06BFF);
        mix.myMix = true;

        Entry c = card(l, "😄", "Happy & Feel-Good", 0xFFFFC93C);
        phrase(c, "Happy songs", "happy {lang} songs|feel good {lang} songs|{lang} upbeat songs");
        phrase(c, "Road trip songs", "{lang} road trip songs|travel {lang} songs");
        phrases(c, Tune::new);

        c = card(l, "💕", "Romantic", 0xFFFF6FA5);
        phrase(c, "Romantic songs", "romantic {lang} songs|best romantic {lang} songs");
        phrase(c, "Love songs", "{lang} love songs|{lang} love duets");
        phrases(c, Tune::new);

        c = card(l, "💔", "Sad & Heartbreak", 0xFF5B8CFF);
        phrase(c, "Sad songs", "sad {lang} songs|heart touching {lang} songs");
        phrase(c, "Breakup songs", "{lang} breakup songs|heartbreak {lang} songs");
        phrase(c, "Sad lofi", "{lang} sad lofi songs|sad {lang} lofi").allow(LOFI).noTail();
        phrases(c, Tune::new);

        c = card(l, "🎉", "Party & Dance", 0xFFFF6A3D);
        phrase(c, "Party songs", "{lang} party songs|{lang} dance songs|party hits {lang}");
        phrase(c, "Wedding & sangeet", "hindi wedding songs|sangeet songs bollywood").lang("Hindi");
        phrase(c, "Bollywood DJ mix", "bollywood dj mix|bollywood remix nonstop|dj mix hindi songs").lang("Hindi")
                .allow(DJ).length(120, 3600).noTail();
        phrase(c, "Bhangra", "bhangra songs|punjabi dj mix|punjabi party songs").lang("Punjabi").allow(DJ).length(120, 3600);
        phrase(c, "Marathi DJ & Lavani", "marathi dj songs|marathi dj mix|lavani").lang("Marathi").allow(DJ).length(120, 3600);
        phrase(c, "EDM & dance pop", "edm hits|dance pop hits|best edm songs").lang("English");
        phrase(c, "Festival songs", "holi songs|garba songs|ganpati songs").lang("Hindi");
        phrases(c, Tune::new);

        c = card(l, "😌", "Chill & Unplugged", 0xFF36C8F4);
        phrase(c, "Chill songs", "chill {lang} songs|soft {lang} songs|relaxing {lang} songs");
        phrase(c, "Unplugged & acoustic", "{lang} unplugged songs|{lang} acoustic songs").allow("cover");
        phrase(c, "Lofi", "{lang} lofi songs|{lang} slowed reverb").allow(LOFI).noTail();
        phrase(c, "Late night drive", "late night drive {lang} songs");
        phrase(c, "Coke Studio", "coke studio|coke studio bharat").lang("Hindi").noTail();
        phrase(c, "Flute & instrumental covers", "{lang} songs flute cover|instrumental {lang} songs")
                .allow("instrumental,cover").noTail();
        phrases(c, Tune::new);

        c = card(l, "💪", "Workout & Motivation", 0xFFFF9F43);
        phrase(c, "Workout songs", "{lang} workout songs|gym {lang} songs");
        phrase(c, "Motivational songs", "motivational {lang} songs|{lang} inspirational songs");
        phrase(c, "Patriotic songs", "patriotic songs hindi|desh bhakti songs").lang("Hindi");
        phrases(c, Tune::new);

        c = card(l, "📻", "Old is Gold", 0xFFB06BFF);
        phrase(c, "Golden era", "old {lang} songs 60s 70s|golden era {lang} songs|evergreen {lang} songs");
        phrase(c, "90s & 2000s hits", "90s {lang} songs|2000s hit {lang} songs");
        phrase(c, "Antakshari & dhaba classics", "90s bollywood songs|antakshari songs|old hindi dhaba songs").lang("Hindi");
        phrase(c, "80s & 90s hits", "80s hits|90s hits|classic rock songs").lang("English");
        phrase(c, "Bhavgeet & old Marathi", "marathi bhavgeet|old marathi songs").lang("Marathi");
        phrase(c, "Old Punjabi", "old punjabi songs|gurdas maan songs").lang("Punjabi");
        phrases(c, Tune::new);

        c = card(l, "🆕", "New Releases", 0xFFFF6FA5);
        phrase(c, "New songs this year", "new {lang} songs {year}|latest {lang} songs {year}|new {lang} song {year}");
        phrases(c, Tune::new);

        c = card(l, "🎙️", "Indie & Hip-hop", 0xFF3DDC97);
        phrase(c, "Hindi indie", "hindi indie songs|indian indie music|hindi indie pop").lang("Hindi");
        phrase(c, "Desi hip-hop", "desi hip hop|indian rap songs").lang("Hindi");
        phrase(c, "Marathi rap", "marathi rap|marathi hip hop").lang("Marathi");
        phrase(c, "Punjabi rap", "punjabi rap songs|punjabi hip hop").lang("Punjabi");
        phrase(c, "Indie & alternative", "indie songs|alternative rock songs").lang("English");
        phrase(c, "Hip-hop hits", "hip hop hits|best rap songs").lang("English");
        phrases(c, Tune::new);

        c = card(l, "🎸", "Alternate Artists", 0xFF9D6BFF);
        names(c, "An artist or band, like \"Prateek Kuhad\"", Picks::artistTune, "Ritviz", "Anuv Jain", "The Local Train",
                "Prateek Kuhad", "Aditya Rikhari", "Nikhil D'Souza", "When Chai Met Toast", "Lifafa", "Peter Cat Recording Co.",
                "The Yellow Diary", "Indian Ocean", "Raghu Dixit Project", "Nucleya", "Zaeden", "Taba Chake", "Hanita Bhambri",
                "Kamakshi Khanna", "Jonita Gandhi");

        c = card(l, "🎤", "Top Singers", 0xFF5B8CFF);
        artists(c, "Hindi", "Arijit Singh", "Kishore Kumar", "Lata Mangeshkar", "Mohammed Rafi", "Asha Bhosle",
                "R. D. Burman", "A. R. Rahman", "Sonu Nigam", "Shreya Ghoshal", "Atif Aslam", "Papon", "Lucky Ali", "KK",
                "Mohit Chauhan", "Udit Narayan", "Kumar Sanu", "Alka Yagnik", "Jagjit Singh", "Shaan", "Sunidhi Chauhan",
                "Neha Kakkar", "Badshah", "Pritam", "Vishal-Shekhar", "Shankar-Ehsaan-Loy", "Amit Trivedi");
        artists(c, "Marathi", "Ajay-Atul", "Avadhoot Gupte", "Shankar Mahadevan");
        artists(c, "Punjabi", "Diljit Dosanjh", "AP Dhillon", "Karan Aujla", "Sidhu Moose Wala", "Gurdas Maan");
        artists(c, "English", "Michael Jackson", "Queen", "The Beatles", "Madonna", "Whitney Houston", "Bob Marley",
                "Celine Dion", "Backstreet Boys", "Bon Jovi", "Linkin Park", "Eminem", "Lady Gaga", "Shakira", "Rihanna",
                "Beyoncé", "Coldplay", "Bruno Mars", "Ed Sheeran", "Taylor Swift", "Adele", "The Weeknd", "Dua Lipa",
                "Billie Eilish", "Imagine Dragons", "Maroon 5", "Justin Bieber", "BTS", "BLACKPINK");
        c.adder = Picks::artistTune;   // a typed singer plays whatever the languages
        c.addHint = "A singer's name, like \"Arijit Singh\"";
        return l;
    }

    // ---- extras ----------------------------------------------------------------------------------

    private static final String TRAILER = "trailer|teaser",
            NOT_TRAILER = "review|reaction|explain|breakdown|spoof|fan.?made|concept|edit|scene|song|interview";
    private static final String NOT_SHORT = "shorts|#shorts|reaction|review|status";

    static Tune trailerTune(String name) {
        return new Tune(name + " official trailer|" + name + " trailer|" + name + " official teaser").must(TRAILER)
                .never(NOT_TRAILER).length(30, 360).noTail().thisMonth();
    }

    static Tune comicTune(String name) {
        return new Tune(name + " stand up comedy|" + name + " standup|" + name + " comedy|best of " + name)
                .length(120, 3600).noTail().verified().never("reaction|review|podcast|interview|shorts|#shorts|trailer").artist(name);
    }

    static Tune showTune(String name) {
        return new Tune(name + " full episode|" + name + " episode|" + name + " best episodes")
                .length(300, 5400).noTail().never(NOT_SHORT + "|promo|trailer").artist(name);
    }

    static Tune cartoonTune(String name) {
        return new Tune(name + " hindi episode|" + name + " full episode|" + name + " cartoon in hindi")
                .length(180, 3600).noTail().never(NOT_SHORT).artist(name);
    }

    static Tune newsTune(String name) {
        return new Tune(name + " live|" + name + " live news").live().verified().artist(name);
    }

    static Tune movieChannelTune(String name) {
        return new Tune(name + " full movie|" + name + " hindi full movie|" + name + " superhit full movie")
                .length(3600, 14400).noTail().never("trailer|scene|song|review|part 1|part 2").artist(name);
    }

    static Tune longTune(String phrase) {
        return new Tune(phrase).length(600, 43200).noTail().allow("instrumental,lofi,lo-fi");
    }

    static List<Entry> extrasCards() {
        List<Entry> l = new ArrayList<>();
        Entry c = card(l, "🎬", "Trailers", 0xFFFF6A3D);
        phrase(c, "Hindi trailers", "official trailer hindi|hindi movie trailer|hindi trailer {year}|official teaser hindi")
                .lang("Hindi").must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisWeek();
        phrase(c, "English trailers", "official trailer|new movie trailer {year}|official teaser trailer")
                .lang("English").must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisWeek();
        phrase(c, "Marathi trailers", "marathi movie official trailer|marathi movie trailer")
                .lang("Marathi").must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisMonth();
        appTrailers(c, "Netflix India", "Prime Video India", "JioHotstar", "SonyLIV", "ZEE5");
        phrases(c, Picks::trailerTune);
        c.addHint = "A language, film or app, like \"Gujarati\"";

        c = card(l, "🎤", "Stand-up Comedy", 0xFFFFC93C);
        langNamed(c, "Hindi", Picks::comicTune, "Zakir Khan", "Abhishek Upmanyu",
                "Anubhav Singh Bassi", "Samay Raina", "Munawar Faruqui", "Harsh Gujral", "Ravi Gupta", "Aakash Gupta",
                "Gaurav Kapoor", "Amit Tandon", "Kunal Kamra", "Vipul Goyal", "Appurv Gupta", "Pratyush Chaubey",
                "Ashish Solanki", "Shashwat Maheshwari", "Vikas Kush Sharma", "Gaurav Gupta", "Nishant Tanwar",
                "Inder Sahani", "Jaspreet Singh", "Swati Sachdeva", "Rajat Chauhan", "Sundeep Sharma", "Manik Mahna",
                "Raju Srivastava", "Kapil Sharma", "Rahul Dua", "Prashasti Singh");
        langNamed(c, "English", Picks::comicTune,
                // Indian, in English
                "Kenny Sebastian", "Biswa Kalyan Rath", "Kanan Gill", "Vir Das", "Rahul Subramanian", "Varun Thakur",
                "Sapan Verma", "Rohan Joshi", "Tanmay Bhat", "Atul Khatri", "Sorabh Pant", "Neeti Palta", "Aditi Mittal",
                "Kaneez Surka", "Sumukhi Suresh", "Abish Mathew", "Karunesh Talwar", "Daniel Fernandes", "Papa CJ",
                "Anirban Dasgupta", "Urooj Ashfaq", "Azeem Banatwalla", "Kautuk Srivastava", "Sahil Shah", "Rohan Gujral",
                // International
                "Trevor Noah", "Kevin Hart", "Russell Peters", "Dave Chappelle", "Jimmy Carr", "Hasan Minhaj",
                "Gabriel Iglesias", "Ali Wong", "John Mulaney", "Bill Burr", "Jim Gaffigan", "Ricky Gervais", "Chris Rock",
                "Jerry Seinfeld", "George Carlin", "Robin Williams", "Eddie Murphy", "Richard Pryor", "Sebastian Maniscalco",
                "Nate Bargatze", "Taylor Tomlinson", "Tom Segura", "Matt Rife", "Iliza Shlesinger", "Michael McIntyre",
                "James Acaster", "Eddie Izzard", "Jim Jefferies", "Bert Kreischer", "Ronny Chieng", "Jo Koy", "Daniel Sloss",
                "Mark Normand", "Andrew Schulz", "Bo Burnham", "Amy Schumer", "Mike Birbiglia", "Ali Siddiq");
        c.adder = Picks::comicTune;
        c.addHint = "A comedian, like \"Zakir Khan\"";

        c = card(l, "📺", "Classic TV Shows", 0xFFFF9F43);
        names(c, "A show, like \"Sarabhai vs Sarabhai\"", Picks::showTune, "Maharashtrachi Hasyajatra", "Chala Hawa Yeu Dya",
                "Taarak Mehta Ka Ooltah Chashmah", "CID", "Crime Patrol");

        c = card(l, "🧸", "Cartoons", 0xFF3DDC97);
        names(c, "A cartoon, like \"Pokemon\"", Picks::cartoonTune, "Doraemon", "Shinchan", "Chhota Bheem", "Motu Patlu",
                "Tom and Jerry", "Oggy and the Cockroaches", "Peppa Pig");

        c = card(l, "📡", "Live News", 0xFFFF6A3D);
        names(c, "A news channel, like \"NDTV India\"", Picks::newsTune, "Aaj Tak", "ABP News", "India TV", "Zee News",
                "NDTV India", "ABP Majha", "TV9 Marathi", "Zee 24 Taas", "News18 Lokmat", "Saam TV");


        c = card(l, "🎞️", "Free Full Movies", 0xFF5B8CFF);
        names(c, "A YouTube movie channel, like \"Shemaroo\"", Picks::movieChannelTune, "Goldmines", "Shemaroo",
                "Rajshri", "Ultra Movie Parlour", "Pen Movies", "Venus Movies");


        c = card(l, "🌧️", "Ambient & Relax", 0xFF36C8F4);
        phrase(c, "Rain sounds", "rain sounds for sleep|rain and thunder sounds").length(600, 43200).noTail();
        phrase(c, "Relaxing piano", "relaxing piano music|calm piano").length(600, 43200).noTail().allow("instrumental");
        phrase(c, "Nature sounds", "nature sounds birds forest|ocean waves sounds").length(600, 43200).noTail();
        phrase(c, "Fireplace", "fireplace ambience|cozy fireplace sounds").length(600, 43200).noTail();
        phrase(c, "Meditation music", "meditation music|calm flute meditation music").length(600, 43200).noTail()
                .allow("instrumental");
        phrases(c, Picks::longTune);
        c.addHint = "Something to search for, like \"thunderstorm sounds\"";
        extrasNew(l, true);
        return ordered(l);
    }

    /** Short Films, and the tiles that ask first (Topics): Recipes, Podcasts & Talks, Spirituality, Documentaries. */
    private static void extrasNew(List<Entry> l, boolean india) {
        Entry c = card(l, "🎬", "Short Films", 0xFFB06BFF);
        if (india) phrase(c, "Hindi short films", "hindi short film|award winning hindi short film|large short films")
                .lang("Hindi").length(300, 2700).noTail().never("#shorts|trailer|reel");
        phrase(c, "Short films", "award winning short film|short film|best short film")
                .length(300, 2700).noTail().never("#shorts|trailer|reel");
        phrase(c, "Animated shorts", "animated short film|pixar style short film").length(180, 1800).noTail().never("#shorts|trailer");
        phrases(c, n -> new Tune(n + " short film").length(300, 2700).noTail().never("#shorts|trailer"));
        c.addHint = "A kind of short film, like \"thriller short film\"";
        topicTile(l, "🍳", Topics.RECIPES, 0xFFFF9F43, "easy recipe");
        topicTile(l, "🎙️", Topics.PODCASTS, 0xFF5B8CFF, "podcast full episode");
        topicTile(l, "🪷", Topics.SPIRITUALITY, 0xFFFFC93C, "guided meditation");
        topicTile(l, "🌍", Topics.DOCS, 0xFF3DDC97, "full documentary");
    }

    private static void topicTile(List<Entry> l, String emoji, String label, int color, String pictureWords) {
        Entry c = card(l, emoji, label, color);
        c.asks = true;
        phrase(c, label, pictureWords).length(180, 7200).noTail();   // for its picture, and if nothing is ticked
    }

    /** Extras in a useful order: twelve, four to a row. */
    private static List<Entry> ordered(List<Entry> l) {
        String[] want = {"Trailers", "Short Films", "Stand-up Comedy", Topics.RECIPES, Topics.PODCASTS, Topics.DOCS,
                Topics.SPIRITUALITY, "Classic TV Shows", "Classic TV", "TV Comedy", "Cartoons", "Free Full Movies", "Free Movies",
                "Live News", "Ambient & Relax"};
        List<Entry> out = new ArrayList<>();
        for (String w : want) for (Entry e : l) if (e.label.equals(w) && !out.contains(e)) out.add(e);
        for (Entry e : l) if (!out.contains(e)) out.add(e);
        return out;
    }

    static Tune sportsTune(String name) {
        return new Tune(name + " highlights|" + name + " extended highlights|" + name + " best moments")
                .length(120, 1800).noTail().thisWeek().verified().never(NOT_SHORT + "|preview|press conference").artist(name);
    }

    /** Late-night and sketch shows: monologues and sketches from the show's own channel. */
    static Tune lateNightTune(String name) {
        return new Tune(name + " monologue|" + name + " sketch|" + name + " best of").length(180, 1800).noTail()
                .verified().never(NOT_SHORT + "|reaction").artist(name);
    }

    /** Sitcoms: their official clip channels (full episodes are rarely on YouTube). */
    static Tune sitcomTune(String name) {
        return new Tune(name + " best moments|" + name + " funniest scenes|" + name + " full episode")
                .length(180, 3600).noTail().never(NOT_SHORT + "|promo|trailer|cast|interview").artist(name);
    }

    static Tune kidsTune(String name) {
        return new Tune(name + " full episode|" + name + " full episodes|" + name + " cartoon")
                .length(180, 3600).noTail().never(NOT_SHORT).artist(name);
    }

    static Tune documentaryTune(String name) {
        return new Tune(name + " full documentary|" + name + " documentary").length(1200, 10800).noTail()
                .never(NOT_SHORT + "|trailer|preview").artist(name);
    }

    static Tune freeMovieTune(String name) {
        return new Tune(name + " full movie|" + name + " free full movie|" + name + " full movie english")
                .length(3600, 14400).noTail().never("trailer|scene|song|review|part 1|part 2").artist(name);
    }

    /** Adds named parts made with a tune, without making it the card's adder (cards that mix kinds of show). */
    private static void named(Entry card, java.util.function.Function<String, Tune> tune, String... names) {
        for (String name : names) {
            Entry p = new Entry(card.emoji, name, card.color);
            p.tune = tune.apply(name);
            card.parts.add(p);
        }
    }

    // ---- Global: made for the US, UK, Canada and Australia ----------------------------------------

    static List<Entry> globalMusicCards() {
        List<Entry> l = new ArrayList<>();
        Entry mix = card(l, "💜", "My Mix", 0xFFB06BFF);
        mix.myMix = true;

        Entry c = card(l, "😄", "Feel-Good", 0xFFFFC93C);
        phrase(c, "Feel-good songs", "feel good {lang} songs|happy {lang} songs|upbeat {lang} pop songs");
        phrase(c, "Road trip songs", "{lang} road trip songs|road trip anthems");
        phrase(c, "Sing-alongs", "sing along songs|songs everyone knows").lang("English");
        phrases(c, Tune::new);

        c = card(l, "💕", "Love Songs", 0xFFFF6FA5);
        phrase(c, "Love songs", "{lang} love songs|romantic {lang} songs");
        phrase(c, "Slow dance", "slow dance songs|first dance wedding songs").lang("English");
        phrase(c, "Love ballads", "love ballads|80s 90s love songs").lang("English");
        phrases(c, Tune::new);

        c = card(l, "💔", "Heartbreak", 0xFF5B8CFF);
        phrase(c, "Sad songs", "sad {lang} songs|songs to cry to {lang}");
        phrase(c, "Breakup songs", "{lang} breakup songs|heartbreak {lang} songs");
        phrases(c, Tune::new);

        c = card(l, "🎉", "Party & Dance", 0xFFFF6A3D);
        phrase(c, "Party hits", "{lang} party songs|party hits {lang}|{lang} dance songs");
        phrase(c, "EDM & dance pop", "edm hits|dance pop hits|best edm songs").lang("English");
        phrase(c, "Disco & funk", "disco hits|funk classics|70s disco songs").lang("English");
        phrase(c, "Reggaeton & Latin", "reggaeton hits|latin party songs").lang("Spanish");
        phrase(c, "K-pop dance", "kpop dance songs|kpop party songs").lang("Korean");
        phrase(c, "Bollywood party", "bollywood party songs|bollywood dance hits").lang("Hindi");
        phrases(c, Tune::new);

        c = card(l, "😌", "Chill & Acoustic", 0xFF36C8F4);
        phrase(c, "Chill songs", "chill {lang} songs|soft {lang} songs|relaxing {lang} songs");
        phrase(c, "Acoustic", "{lang} acoustic songs|acoustic covers").allow("cover");
        phrase(c, "Coffee shop", "coffee shop music|coffee house acoustic songs").lang("English");
        phrase(c, "Lofi", "lofi hip hop|chill lofi beats").allow(LOFI).noTail().lang("English");
        phrases(c, Tune::new);

        c = card(l, "💪", "Workout", 0xFFFF9F43);
        phrase(c, "Workout songs", "{lang} workout songs|gym {lang} songs");
        phrase(c, "Running", "running songs|running playlist songs").lang("English");
        phrase(c, "Rock workout", "rock workout songs|hard rock gym songs").lang("English");
        phrase(c, "Hip-hop workout", "hip hop workout songs|rap gym songs").lang("English");
        phrases(c, Tune::new);

        c = card(l, "📻", "Throwbacks", 0xFFB06BFF);
        phrase(c, "60s & 70s", "60s hits|70s hits|classic 70s songs").lang("English");
        phrase(c, "80s", "80s hits|best 80s songs|80s pop songs").lang("English");
        phrase(c, "90s", "90s hits|best 90s songs|90s pop songs").lang("English");
        phrase(c, "2000s", "2000s hits|best 2000s songs|2000s pop songs").lang("English");
        phrase(c, "2010s", "2010s hits|best 2010s songs").lang("English");
        phrase(c, "Old favourites", "old {lang} songs|classic {lang} hits");
        phrases(c, Tune::new);

        c = card(l, "🆕", "New Releases", 0xFFFF6FA5);
        phrase(c, "New songs this year", "new {lang} songs {year}|latest {lang} songs {year}|{lang} new music {year}");
        phrase(c, "New pop", "new pop songs {year}|top pop hits {year}").lang("English");
        phrases(c, Tune::new);

        c = card(l, "🎙️", "Hip-hop & R&B", 0xFF3DDC97);
        phrase(c, "Hip-hop hits", "hip hop hits|best rap songs|rap hits {year}").lang("English");
        phrase(c, "R&B", "r&b hits|best r&b songs|rnb love songs").lang("English");
        phrase(c, "Old-school hip-hop", "old school hip hop|90s hip hop hits").lang("English");
        phrase(c, "Hip-hop in your language", "{lang} hip hop|{lang} rap songs");
        phrases(c, Tune::new);

        c = card(l, "🎸", "Rock & Country", 0xFF9D6BFF);
        phrase(c, "Country hits", "country hits|best country songs|new country songs {year}").lang("English");
        phrase(c, "Classic rock", "classic rock songs|rock hits 70s 80s").lang("English");
        phrase(c, "Indie & alternative", "indie songs|alternative rock songs|indie pop hits").lang("English");
        phrase(c, "Pop punk & 2000s rock", "pop punk songs|2000s rock hits").lang("English");
        phrases(c, Tune::new);

        c = card(l, "🎤", "Top Artists", 0xFF5B8CFF);
        artists(c, "English", "Taylor Swift", "Beyoncé", "Drake", "Ed Sheeran", "The Weeknd", "Billie Eilish", "Adele",
                "Bruno Mars", "Kendrick Lamar", "Morgan Wallen", "Sabrina Carpenter", "Olivia Rodrigo", "SZA",
                "Post Malone", "Harry Styles", "Dua Lipa", "Rihanna", "Ariana Grande", "Coldplay", "Imagine Dragons",
                "Zach Bryan", "Luke Combs", "Lady Gaga", "Eminem", "Michael Jackson", "Queen", "The Beatles",
                "Fleetwood Mac", "Whitney Houston", "Elton John");
        artists(c, "Spanish", "Bad Bunny", "Karol G", "Shakira");
        artists(c, "Korean", "BTS", "BLACKPINK", "Stray Kids");
        artists(c, "Hindi", "Arijit Singh", "A. R. Rahman", "Shreya Ghoshal");
        c.adder = Picks::artistTune;   // a typed singer plays whatever the languages
        c.addHint = "A singer's name, like \"Taylor Swift\"";
        return l;
    }

    static List<Entry> globalExtrasCards() {
        List<Entry> l = new ArrayList<>();
        Entry c = card(l, "🎬", "Trailers", 0xFFFF6A3D);
        phrase(c, "Movie trailers", "official trailer|new movie trailer {year}|official teaser trailer")
                .must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisWeek();
        phrase(c, "Coming soon", "coming soon trailers {year}|new trailers this week")
                .must(TRAILER).never(NOT_TRAILER).length(30, 600).noTail().thisMonth();
        appTrailers(c, "Netflix", "HBO Max", "Prime Video", "Disney Plus", "Apple TV", "Hulu", "Peacock", "Paramount Plus");
        phrases(c, Picks::trailerTune);
        c.addHint = "A film, studio or app, like \"Pixar\"";

        c = card(l, "🎤", "Stand-up Comedy", 0xFFFFC93C);
        names(c, "A comedian, like \"John Mulaney\"", Picks::comicTune, "Nate Bargatze", "John Mulaney", "Taylor Tomlinson",
                "Jim Gaffigan", "Ali Wong", "Trevor Noah", "Kevin Hart", "Dave Chappelle", "Sebastian Maniscalco",
                "Gabriel Iglesias", "Bill Burr", "Jerry Seinfeld", "Chris Rock", "Hasan Minhaj", "Matt Rife", "Tom Segura",
                "Iliza Shlesinger", "Ronny Chieng", "Jo Koy", "Mark Normand", "Bert Kreischer", "Mike Birbiglia",
                "Ricky Gervais", "Michael McIntyre", "Jimmy Carr", "Russell Peters", "Robin Williams", "George Carlin",
                "Dry Bar Comedy", "Netflix Is A Joke");

        c = card(l, "📺", "TV Comedy", 0xFFFF9F43);
        named(c, Picks::sitcomTune, "Friends", "The Office", "Seinfeld", "Parks and Recreation", "Brooklyn Nine-Nine",
                "Frasier", "Modern Family", "How I Met Your Mother", "The Big Bang Theory", "Cheers", "Mr Bean", "Fawlty Towers");
        named(c, Picks::lateNightTune, "Saturday Night Live", "Jimmy Fallon", "Jimmy Kimmel Live", "Late Night with Seth Meyers",
                "Stephen Colbert", "Last Week Tonight", "The Daily Show", "Hot Ones");
        c.adder = Picks::sitcomTune;
        c.addHint = "A show, like \"Frasier\"";

        c = card(l, "🧸", "Cartoons", 0xFF3DDC97);
        names(c, "A cartoon, like \"Pokemon\"", Picks::kidsTune, "Bluey", "Peppa Pig", "Paw Patrol", "SpongeBob",
                "Looney Tunes", "Tom and Jerry", "Mickey Mouse", "Scooby-Doo", "Pokemon", "Sesame Street");

        c = card(l, "📡", "Live News", 0xFFFF6A3D);
        names(c, "A news channel, like \"CNN\"", Picks::newsTune, "ABC News", "NBC News", "CBS News", "LiveNOW from FOX",
                "Sky News", "BBC News", "Bloomberg Television", "Al Jazeera English", "DW News", "CNA");



        c = card(l, "🎞️", "Free Movies", 0xFFB06BFF);
        names(c, "A YouTube movie channel, like \"Popcornflix\"", Picks::freeMovieTune, "FilmRise Movies", "Popcornflix",
                "Paramount Vault", "Movie Central", "Timeless Classic Movies");

        c = card(l, "🌧️", "Ambient & Relax", 0xFF36C8F4);
        phrase(c, "Rain sounds", "rain sounds for sleep|rain and thunder sounds").length(600, 43200).noTail();
        phrase(c, "Relaxing piano", "relaxing piano music|calm piano").length(600, 43200).noTail().allow("instrumental");
        phrase(c, "Nature sounds", "nature sounds birds forest|ocean waves sounds").length(600, 43200).noTail();
        phrase(c, "Fireplace", "fireplace ambience|cozy fireplace sounds").length(600, 43200).noTail();
        phrase(c, "Aquarium", "aquarium 4k|coral reef fish relaxing").length(600, 43200).noTail();
        phrase(c, "Jazz café", "jazz cafe music|coffee shop jazz").length(600, 43200).noTail().allow("instrumental");
        phrases(c, Picks::longTune);
        c.addHint = "Something to search for, like \"thunderstorm sounds\"";
        extrasNew(l, false);
        return ordered(l);
    }

    // ---- Mixed: some of the Indian cards, some of the Global ones ----------------------------------

    static List<Entry> mixedMusicCards() {
        List<Entry> l = new ArrayList<>();
        Entry mix = card(l, "💜", "My Mix", 0xFFB06BFF);
        mix.myMix = true;

        Entry c = card(l, "😄", "Happy & Feel-Good", 0xFFFFC93C);
        phrase(c, "Happy songs", "happy {lang} songs|feel good {lang} songs|{lang} upbeat songs");
        phrase(c, "Road trip songs", "{lang} road trip songs|travel {lang} songs");
        phrases(c, Tune::new);

        c = card(l, "💕", "Romantic", 0xFFFF6FA5);
        phrase(c, "Romantic songs", "romantic {lang} songs|best romantic {lang} songs");
        phrase(c, "Love songs", "{lang} love songs|{lang} love duets");
        phrases(c, Tune::new);

        c = card(l, "💔", "Sad & Heartbreak", 0xFF5B8CFF);
        phrase(c, "Sad songs", "sad {lang} songs|heart touching {lang} songs");
        phrase(c, "Breakup songs", "{lang} breakup songs|heartbreak {lang} songs");
        phrases(c, Tune::new);

        c = card(l, "🎉", "Party & Dance", 0xFFFF6A3D);
        phrase(c, "Party songs", "{lang} party songs|{lang} dance songs|party hits {lang}");
        phrase(c, "Bollywood DJ mix", "bollywood dj mix|bollywood remix nonstop|dj mix hindi songs").lang("Hindi")
                .allow(DJ).length(120, 3600).noTail();
        phrase(c, "Bhangra", "bhangra songs|punjabi dj mix|punjabi party songs").lang("Punjabi").allow(DJ).length(120, 3600);
        phrase(c, "EDM & dance pop", "edm hits|dance pop hits|best edm songs").lang("English");
        phrase(c, "K-pop dance", "kpop dance songs|kpop party songs").lang("Korean");
        phrases(c, Tune::new);

        c = card(l, "😌", "Chill & Unplugged", 0xFF36C8F4);
        phrase(c, "Chill songs", "chill {lang} songs|soft {lang} songs|relaxing {lang} songs");
        phrase(c, "Unplugged & acoustic", "{lang} unplugged songs|{lang} acoustic songs").allow("cover");
        phrase(c, "Coke Studio", "coke studio|coke studio bharat").lang("Hindi").noTail();
        phrase(c, "Coffee shop", "coffee shop music|coffee house acoustic songs").lang("English");
        phrases(c, Tune::new);

        c = card(l, "💪", "Workout & Motivation", 0xFFFF9F43);
        phrase(c, "Workout songs", "{lang} workout songs|gym {lang} songs");
        phrase(c, "Motivational songs", "motivational {lang} songs|{lang} inspirational songs");
        phrases(c, Tune::new);

        c = card(l, "📻", "Throwbacks", 0xFFB06BFF);
        phrase(c, "Golden era", "old {lang} songs 60s 70s|golden era {lang} songs|evergreen {lang} songs");
        phrase(c, "90s & 2000s hits", "90s {lang} songs|2000s hit {lang} songs");
        phrase(c, "Antakshari classics", "90s bollywood songs|antakshari songs").lang("Hindi");
        phrase(c, "80s & 90s hits", "80s hits|90s hits|classic rock songs").lang("English");
        phrases(c, Tune::new);

        c = card(l, "🆕", "New Releases", 0xFFFF6FA5);
        phrase(c, "New songs this year", "new {lang} songs {year}|latest {lang} songs {year}|new {lang} song {year}");
        phrases(c, Tune::new);

        c = card(l, "🎙️", "Indie & Hip-hop", 0xFF3DDC97);
        phrase(c, "Hindi indie", "hindi indie songs|indian indie music|hindi indie pop").lang("Hindi");
        phrase(c, "Desi hip-hop", "desi hip hop|indian rap songs").lang("Hindi");
        phrase(c, "Punjabi rap", "punjabi rap songs|punjabi hip hop").lang("Punjabi");
        phrase(c, "Indie & alternative", "indie songs|alternative rock songs").lang("English");
        phrase(c, "Hip-hop & R&B", "hip hop hits|best rap songs|r&b hits").lang("English");
        phrases(c, Tune::new);

        c = card(l, "🎸", "Alternate Artists", 0xFF9D6BFF);
        names(c, "An artist or band, like \"Prateek Kuhad\"", Picks::artistTune, "Prateek Kuhad", "Anuv Jain", "Ritviz",
                "The Local Train", "When Chai Met Toast", "Nucleya", "Arctic Monkeys", "Tame Impala", "Hozier",
                "Lana Del Rey", "Phoebe Bridgers", "Mitski", "Bon Iver", "The 1975");

        c = card(l, "🎤", "Top Singers", 0xFF5B8CFF);
        artists(c, "Hindi", "Arijit Singh", "Kishore Kumar", "Lata Mangeshkar", "Mohammed Rafi", "A. R. Rahman",
                "Shreya Ghoshal", "Sonu Nigam", "Atif Aslam", "KK", "Pritam", "Badshah");
        artists(c, "Punjabi", "Diljit Dosanjh", "AP Dhillon", "Karan Aujla");
        artists(c, "English", "Taylor Swift", "Ed Sheeran", "The Weeknd", "Billie Eilish", "Bruno Mars", "Coldplay",
                "Dua Lipa", "Adele", "Michael Jackson", "Queen", "Eminem", "Rihanna");
        artists(c, "Korean", "BTS", "BLACKPINK");
        c.adder = Picks::artistTune;
        c.addHint = "A singer's name, like \"Arijit Singh\"";
        return l;
    }

    static List<Entry> mixedExtrasCards() {
        List<Entry> l = new ArrayList<>();
        Entry c = card(l, "🎬", "Trailers", 0xFFFF6A3D);
        phrase(c, "Hindi trailers", "official trailer hindi|hindi movie trailer|hindi trailer {year}|official teaser hindi")
                .lang("Hindi").must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisWeek();
        phrase(c, "English trailers", "official trailer|new movie trailer {year}|official teaser trailer")
                .lang("English").must(TRAILER).never(NOT_TRAILER).length(30, 360).noTail().thisWeek();
        appTrailers(c, "Netflix India", "Prime Video India", "JioHotstar", "Netflix", "HBO Max", "Disney Plus", "Apple TV");
        phrases(c, Picks::trailerTune);
        c.addHint = "A language, film or app, like \"Tamil\"";

        c = card(l, "🎤", "Stand-up Comedy", 0xFFFFC93C);
        langNamed(c, "Hindi", Picks::comicTune, "Zakir Khan", "Abhishek Upmanyu", "Anubhav Singh Bassi", "Samay Raina");
        langNamed(c, "English", Picks::comicTune, "Kenny Sebastian", "Biswa Kalyan Rath", "Vir Das", "Rahul Subramanian",
                "Trevor Noah", "Russell Peters", "Hasan Minhaj", "Nate Bargatze", "John Mulaney", "Kevin Hart", "Ali Wong",
                "Jim Gaffigan", "Ronny Chieng");
        c.adder = Picks::comicTune;
        c.addHint = "A comedian, like \"Zakir Khan\"";

        c = card(l, "📺", "Classic TV", 0xFFFF9F43);
        named(c, Picks::showTune, "Taarak Mehta Ka Ooltah Chashmah", "Sarabhai vs Sarabhai", "CID", "Khichdi");
        named(c, Picks::sitcomTune, "Friends", "The Office", "Seinfeld", "Brooklyn Nine-Nine", "Mr Bean");
        c.adder = Picks::sitcomTune;
        c.addHint = "A show, like \"Frasier\"";

        c = card(l, "🧸", "Cartoons", 0xFF3DDC97);
        named(c, Picks::cartoonTune, "Doraemon", "Shinchan", "Chhota Bheem");
        named(c, Picks::kidsTune, "Tom and Jerry", "Peppa Pig", "Bluey", "Pokemon", "Paw Patrol");
        c.adder = Picks::kidsTune;
        c.addHint = "A cartoon, like \"SpongeBob\"";

        c = card(l, "📡", "Live News", 0xFFFF6A3D);
        names(c, "A news channel, like \"WION\"", Picks::newsTune, "Aaj Tak", "NDTV India", "NDTV 24x7", "India Today",
                "BBC News", "Sky News", "Al Jazeera English", "ABC News", "DW News");



        c = card(l, "🎞️", "Free Full Movies", 0xFFB06BFF);
        named(c, Picks::movieChannelTune, "Goldmines", "Shemaroo", "Rajshri");
        named(c, Picks::freeMovieTune, "FilmRise Movies", "Popcornflix", "Paramount Vault");
        c.adder = Picks::freeMovieTune;
        c.addHint = "A YouTube movie channel, like \"Ultra Movie Parlour\"";

        c = card(l, "🌧️", "Ambient & Relax", 0xFF36C8F4);
        phrase(c, "Rain sounds", "rain sounds for sleep|rain and thunder sounds").length(600, 43200).noTail();
        phrase(c, "Relaxing piano", "relaxing piano music|calm piano").length(600, 43200).noTail().allow("instrumental");
        phrase(c, "Nature sounds", "nature sounds birds forest|ocean waves sounds").length(600, 43200).noTail();
        phrase(c, "Fireplace", "fireplace ambience|cozy fireplace sounds").length(600, 43200).noTail();
        phrase(c, "Meditation music", "meditation music|calm flute meditation music").length(600, 43200).noTail()
                .allow("instrumental");
        phrases(c, Picks::longTune);
        c.addHint = "Something to search for, like \"thunderstorm sounds\"";
        extrasNew(l, true);
        return ordered(l);
    }

    // ---- searches --------------------------------------------------------------------------------

    /** The words a music search uses for a language. */
    static String languageWords(String language, Random r) {
        switch (language) {
            case "Hindi": return pick(r, "hindi", "bollywood");
            case "English": return "english";   // YouTube answers by where the TV is: in India "chill songs" are Hindi
            case "Spanish": return pick(r, "spanish", "latin");
            case "Portuguese": return pick(r, "brazilian", "portuguese");
            case "Korean": return "kpop";
            case "Japanese": return pick(r, "japanese", "jpop");
            default: return language.toLowerCase(Locale.ROOT);
        }
    }

    /** One YouTube search for a part, in one language: a random wording, so searches differ. */
    static String songQuery(Tune t, String language, boolean playlists, Random r) {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String q = t.queries[r.nextInt(t.queries.length)].replace("{lang}", languageWords(language, r))
                .replace("{year}", String.valueOf(year));
        if (playlists) {
            q += " " + pick(r, "playlist", "songs playlist", "collection", "best playlist");
        } else if (t.tail && t.artist == null) {
            q += " " + (!indian(language) ? pick(r, "official music video", "music video", "")
                    : pick(r, "video song", "song", "full song", ""));
        }
        return q.replaceAll("\\s+", " ").trim();
    }

    /** A playlist from a random song near its start, so the same playlist does not always open the same way. */
    static String playlistUrl(String id, int videos, Random r) {
        int index = 1 + r.nextInt(Math.max(1, Math.min(videos, 15)));
        return "https://www.youtube.com/watch?list=" + id + "&index=" + index;
    }

    /** My Mix from a random point: YouTube plays the viewer's own mix starting at that song. */
    static String myMixUrl(Random r) {
        return "https://www.youtube.com/watch?list=RDMM&index=" + (1 + r.nextInt(30));
    }

    private static String pick(Random r, String... options) {
        return options[r.nextInt(options.length)];
    }
}
