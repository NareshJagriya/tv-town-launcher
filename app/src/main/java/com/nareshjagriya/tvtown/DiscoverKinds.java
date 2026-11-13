package com.nareshjagriya.tvtown;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Discover offers down its left side, as plain data: the four "for you" kinds, then the genres. Each kind has a
 * short id (for links: the phone remote and Home Assistant can open Discover at "anime" or "top-rated") and the TMDB
 * category its rows are filled from. Adding a genre is one line in GENRES; DiscoverKindsTest checks every one resolves.
 */
final class DiscoverKinds {

    private DiscoverKinds() {
    }

    /** One kind on the left. */
    static final class Kind {
        final String id, name;
        final boolean genre;   // false: one of the four at the top

        Kind(String id, String name, boolean genre) {
            this.id = id;
            this.name = name;
            this.genre = genre;
        }
    }

    static final String NEW = "new", TRENDING = "trending", TOP_RATED = "top-rated", HIDDEN_GEMS = "hidden-gems", COMING_UP = "coming-up";

    /** The genres, in the order shown: the most asked-for first, then the classic genres, then the mixes. */
    static final String[] GENRES = {L10n.t("Anime"), L10n.t("K-Drama"), L10n.t("Action"), L10n.t("Comedy"), L10n.t("Thriller"), L10n.t("Romance"), L10n.t("Horror"), L10n.t("Sci-Fi"),
            L10n.t("Crime"), L10n.t("Drama"), L10n.t("Mystery"), L10n.t("Fantasy"), L10n.t("Adventure"), L10n.t("Family"), L10n.t("Kids"), L10n.t("Animation"), L10n.t("Documentary"),
            L10n.t("Action comedy"), L10n.t("Romantic comedy"), L10n.t("Crime thriller"), L10n.t("Sci-fi thriller"), L10n.t("Horror thriller"), L10n.t("Crime drama"),
            L10n.t("War"), L10n.t("Reality")};

    static final List<Kind> ALL;

    static {
        List<Kind> l = new ArrayList<>();
        l.add(new Kind(NEW, L10n.t("New"), false));
        l.add(new Kind(TRENDING, L10n.t("Trending"), false));
        l.add(new Kind(TOP_RATED, L10n.t("Top rated"), false));
        l.add(new Kind(HIDDEN_GEMS, L10n.t("Hidden gems"), false));
        l.add(new Kind(COMING_UP, L10n.t("Coming up"), false));
        for (String g : GENRES) l.add(new Kind(idOf(g), g, true));
        ALL = Collections.unmodifiableList(l);
    }

    /** "Action comedy" → "action-comedy", "K-Drama" → "k-drama". */
    static String idOf(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** The kind for words like "anime", "K Drama", "kdrama" or "top rated"; null when none matches. */
    @Nullable
    static Kind find(@Nullable String words) {
        if (words == null) return null;
        String w = idOf(words).replace("-", "");
        if (w.isEmpty()) return null;
        for (Kind k : ALL) if (k.id.replace("-", "").equals(w)) return k;
        if (w.equals("popular")) return ALL.get(1);
        return null;
    }

    /**
     * The TMDB category a kind's rows come from; null for New (it has its own list of what arrived lately). Anime
     * and K-Drama carry their own language, which Discover's language choice doesn't override.
     */
    @Nullable
    static Tmdb.Category category(Kind k) {
        switch (k.id) {
            case NEW:
            case COMING_UP: return null;   // its own lists (ComingUp)
            case TRENDING: return named(L10n.t("Popular"));
            case TOP_RATED: return named(L10n.t("Top rated"));
            case HIDDEN_GEMS: return named(L10n.t("Hidden gems"));
            case "anime": return new Tmdb.Category("Genres", k.name).genres("16", "16").lang("ja");
            case "k-drama": return new Tmdb.Category("Genres", k.name).genres("18", "18").lang("ko");
            default: return named(k.name);
        }
    }

    @Nullable
    private static Tmdb.Category named(String n) {
        for (Tmdb.Category c : Tmdb.categories()) if (c.name.equalsIgnoreCase(n) || L10n.t(c.name).equalsIgnoreCase(n)) return c;   // names are English; n may be shown in Spanish
        return null;
    }
}
