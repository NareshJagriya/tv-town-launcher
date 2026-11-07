package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.TimeZone;

/**
 * The content style and streaming country, both chosen in Home's Customize.
 *
 * The style decides taste: the Random cards (music, Extras, kinds of film), the languages on their lists and the
 * Discover categories. Indian is the original TV Town; Global is made for the US, UK, Canada and Australia; Mixed is
 * its own blend of both. Each style keeps its own customising, so switching back loses nothing.
 *
 * The country decides what streams where (TMDB and JustWatch). Automatic follows the TV: India when its time zone
 * is India's, else the country of its language setting, else the US.
 */
final class Style {

    static final String INDIAN = "indian", GLOBAL = "global", MIXED = "mixed";
    static final String[] STYLES = {INDIAN, GLOBAL, MIXED};

    /** Countries that can be chosen, after Automatic: code and name. */
    static final String[][] COUNTRIES = {{"IN", "India"}, {"US", "United States"}, {"GB", "United Kingdom"},
            {"CA", "Canada"}, {"AU", "Australia"}, {"NZ", "New Zealand"}, {"IE", "Ireland"}, {"AE", "UAE"},
            {"SG", "Singapore"}, {"DE", "Germany"}, {"FR", "France"}, {"ES", "Spain"}, {"IT", "Italy"},
            {"NL", "Netherlands"}};

    private static final String PREF_STYLE = "content_style", PREF_COUNTRY = "stream_country";

    // Read once at start-up (TvTownApp) and on every change, so code without a Context can ask.
    private static volatile String style = INDIAN, country = "IN";

    private Style() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static void init(Context ctx) {
        String c = prefs(ctx).getString(PREF_COUNTRY, "");
        country = c.isEmpty() ? autoCountry() : c;
        style = prefs(ctx).getString(PREF_STYLE, "IN".equals(country) ? INDIAN : GLOBAL);
    }

    /** Uses a style and country without saving them (tests). */
    static void use(String s, String c) {
        style = s;
        country = c;
    }

    /** INDIAN, GLOBAL or MIXED. */
    static String get() {
        return style;
    }

    static boolean indian() {
        return INDIAN.equals(style);
    }

    static boolean global() {
        return GLOBAL.equals(style);
    }

    static void set(Context ctx, String s) {
        prefs(ctx).edit().putString(PREF_STYLE, s).apply();
        style = s;
    }

    static String name(String s) {
        switch (s) {
            case GLOBAL: return "🌎  Global";
            case MIXED: return "🔀  Mixed";
            default: return "🇮🇳  Indian";
        }
    }

    /**
     * A preference key for the current style: Indian keeps the original keys (so an existing TV keeps its
     * customising), the others add their name.
     */
    static String key(String base) {
        return INDIAN.equals(style) ? base : base + "@" + style;
    }

    /** The streaming country, as ISO 3166-1: "IN", "US"… */
    static String country() {
        return country;
    }

    /** English as written in the country, for TMDB and YouTube: "en-IN", "en-GB"… (en-US elsewhere). */
    static String englishLocale() {
        switch (country) {
            case "IN": case "GB": case "CA": case "AU": case "NZ": case "IE": case "SG": return "en-" + country;
            default: return "en-US";
        }
    }

    /**
     * The country YouTube searches as: the streaming country, but the US for Global on a TV in India (else YouTube
     * answers "chill songs" with Indian ones).
     */
    static String youtubeRegion() {
        return GLOBAL.equals(style) && "IN".equals(country) ? "US" : country;
    }

    /** The date as the country writes it: "Monday, October 26" in the US and Canada, else "Monday 26 October". */
    static String datePattern() {
        return "US".equals(country) || "CA".equals(country) ? "EEEE, MMMM d" : "EEEE d MMMM";
    }

    /** True when the country is chosen by the TV (Automatic). */
    static boolean countryAuto(Context ctx) {
        return prefs(ctx).getString(PREF_COUNTRY, "").isEmpty();
    }

    /** "" for Automatic, else a code from COUNTRIES. */
    static void setCountry(Context ctx, String code) {
        prefs(ctx).edit().putString(PREF_COUNTRY, code).apply();
        country = code.isEmpty() ? autoCountry() : code;
    }

    static String countryName(String code) {
        for (String[] c : COUNTRIES) if (c[0].equals(code)) return c[1];
        return code;
    }

    /** India by its time zone (TVs sold in India often run in English (US)), else the language's country, else the US. */
    static String autoCountry() {
        String tz = TimeZone.getDefault().getID();
        if ("Asia/Kolkata".equals(tz) || "Asia/Calcutta".equals(tz)) return "IN";
        String c = Locale.getDefault().getCountry();
        return c != null && c.matches("[A-Z]{2}") ? c : "US";
    }

    // ---- the languages the viewer watches in -------------------------------------------------------
    // Asked on the Welcome screen and in Customize. They decide which films need subtitles (My Movies), "In a
    // language I follow", and the languages ticked first on the Random lists.

    /** The choices, as TMDB language codes; the first one in each is the viewer's main language. */
    static final String[][] MY_LANGUAGES = {{"en"}, {"hi", "en"}, {"hi", "en", "mr"}, {"hi", "en", "gu"}, {"hi", "en", "pa"},
            {"hi", "en", "bn"}, {"ta", "en"}, {"te", "en"}, {"ml", "en"}, {"kn", "en"}, {"es", "en"}, {"fr", "en"},
            {"de", "en"}, {"pt", "en"}, {"it", "en"}, {"ko", "en"}, {"ja", "en"}};
    private static final String PREF_MY_LANGS = "my_languages";

    /** The viewer's languages: chosen, else Hindi and English for the Indian and Mixed styles, else English. */
    static java.util.List<String> myLanguages(Context ctx) {
        String v = prefs(ctx).getString(PREF_MY_LANGS, "");
        if (!v.isEmpty()) return java.util.Arrays.asList(v.split(","));
        return GLOBAL.equals(style) ? java.util.Arrays.asList("en") : java.util.Arrays.asList("hi", "en");
    }

    static void setMyLanguages(Context ctx, java.util.List<String> codes) {
        prefs(ctx).edit().putString(PREF_MY_LANGS, String.join(",", codes)).apply();
    }

    /** "Hindi + English". */
    static String languagesName(java.util.List<String> codes) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String c : codes) names.add(languageName(c));
        return String.join(" + ", names);
    }

    static String languageName(String code) {
        for (String[] l : Picks.TITLE_LANGUAGES) if (l[1].equals(code)) return l[0];
        return code;
    }
}
