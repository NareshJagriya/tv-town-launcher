package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Random;

/**
 * Pictures for the Random pop-up's cards: for movies and series, the artwork of a popular title of the card's first
 * kind (TMDB); for music and Extras, the thumbnail of the first video its first search finds (a news channel's
 * picture for Live News, a cartoon's for Cartoons). Found once per card, and kept. No two cards
 * of a pop-up share a picture: each takes the first one no other card has taken, trying its other parts if needed.
 */
final class CardArt {

    private CardArt() {
    }

    private static final long KEEP = 180L * 24 * 3600_000;   // a card's picture is found once and kept (half a year)

    /** Cards have one picture each, whatever languages are ticked: found in the style's main language. */
    static final String LANGUAGE = "";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("card_art", Context.MODE_PRIVATE);
    }

    private static String key(int kind, Picks.Entry card, String language) {
        return Style.key("v5/" + kind + "/" + card.label + "/" + language);   // each style its own pictures
    }

    /** Pictures taken, each with the card that has it, per pop-up kind and language. */
    private static final java.util.Map<String, java.util.Map<String, String>> taken = new java.util.HashMap<>();

    /** Takes the first picture no other card has; when all are taken, the first one. null for none. */
    private static synchronized String claim(int kind, String language, List<String> candidates, boolean lastTry, String owner) {
        java.util.Map<String, String> set = taken.computeIfAbsent(Style.get() + "/" + kind + "/" + language, x -> new java.util.HashMap<>());
        for (String url : candidates) {
            String has = set.get(url);
            if (has == null || has.equals(owner)) {
                set.put(url, owner);
                return url;
            }
        }
        return lastTry && !candidates.isEmpty() ? candidates.get(0) : null;
    }

    /** The picture found before (up to a week ago), or null; also null when another card already shows it. */
    @Nullable
    static String cached(Context ctx, int kind, Picks.Entry card, String language) {
        SharedPreferences p = prefs(ctx);
        String k = key(kind, card, language);
        if (System.currentTimeMillis() - p.getLong(k + "@", 0) > KEEP) return null;
        String url = p.getString(k, null);
        if (url == null || !url.startsWith("https://")) return null;
        synchronized (CardArt.class) {
            java.util.Map<String, String> set = taken.computeIfAbsent(Style.get() + "/" + kind + "/" + language, x -> new java.util.HashMap<>());
            String has = set.get(url);
            if (has != null && !has.equals(card.label)) return null;   // two cards with one picture: this one finds another
            set.put(url, card.label);
        }
        return url;
    }

    /** Finds a picture for a card (network; off the main thread). null when nothing suits. */
    @Nullable
    static String find(Context ctx, int kind, Picks.Entry card, String language) {
        if (card.myMix || card.parts.isEmpty()) return null;
        String url = null;
        List<Picks.Entry> parts = card.on();
        int tries = Math.min(3, parts.size());
        for (int i = 0; i < tries && url == null; i++) {
            url = claim(kind, language, candidates(ctx, kind, parts.get(i), language, card.label), i == tries - 1, card.label);
        }
        if (url != null) {
            String k = key(kind, card, language);
            prefs(ctx).edit().putString(k, url).putLong(k + "@", System.currentTimeMillis()).apply();
        }
        return url;
    }

    /** Pictures that would suit a part, best first: backdrops of a TMDB page, or thumbnails of a YouTube search. */
    private static List<String> candidates(Context ctx, int kind, Picks.Entry part, String language, String label) {
        List<String> out = new java.util.ArrayList<>();
        if (language.isEmpty()) language = Style.global() ? "English" : "Hindi";   // one picture per card: the style's main language
        try {
            if (part.category != null) {
                String type = kind == Picks.SERIES ? "tv" : "movie";
                String code = part.category.language != null ? "" : codeOf(language);
                Tmdb.Page page = null;
                try {
                    page = Tmdb.discover(ctx, part.category, null, type, 1, code);
                } catch (Exception e) {
                    android.util.Log.d("TVTown", "card art tmdb: " + e);
                }
                if (page != null) for (Tmdb.Title t : page.titles) if (t.backdropCard() != null) out.add(t.backdropCard());
                if (out.isEmpty()) {   // no TMDB (or it failed): the same kind on the apps here, through Catalog
                    List<Services.Service> apps = Services.installedOrPopular(ctx);
                    for (int i = 0; i < Math.min(2, apps.size()) && out.isEmpty(); i++) {
                        Tmdb.Page p2 = Catalog.discover(ctx, part.category, apps.get(i), type, 1, code);
                        if (p2 != null) for (Tmdb.Title t : p2.titles) if (t.backdropCard() != null) out.add(t.backdropCard());
                    }
                }
            } else if (part.tune != null) {
                String lang = part.tune.language != null ? part.tune.language : language;
                String q = Picks.songQuery(part.tune, lang, false, new Random(label.hashCode()));
                String html = Songs.fetch("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q)
                        + "&sp=" + part.tune.filter + "&gl=" + Style.youtubeRegion());
                // Only real search results that pass the card's own checks (no ads or promoted videos).
                for (Songs.Pick p : Songs.videos(html, part.tune, lang)) {
                    String url = "https://i.ytimg.com/vi/" + p.id + "/mqdefault.jpg";
                    if (!out.contains(url) && out.size() < 12) out.add(url);
                }
            }
        } catch (Exception e) {
            android.util.Log.d("TVTown", "card art " + label + ": " + e);   // nothing from this part
        }
        return out;
    }

    private static String codeOf(String language) {
        for (String[] l : Picks.TITLE_LANGUAGES) if (l[0].equals(language)) return l[1];
        return "";
    }

    /** The language a card's picture is found in: the first ticked one, else the first on the list. */
    static String languageFor(List<String> ticked, List<String> shown) {
        return !ticked.isEmpty() ? ticked.get(0) : shown.isEmpty() ? "Hindi" : shown.get(0);
    }
}
