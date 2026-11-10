package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The language TV Town speaks: the TV's own (when TV Town has it), or one chosen in Customize. Only the chosen
 * language's words are loaded, so the choice costs nothing in speed. Films' titles and stories follow it too, where
 * TMDB has them translated (Tmdb asks in this language).
 */
final class Lang {

    /** Codes TV Town is translated into; "" is the TV's own language. */
    static final String[] CODES = {"", "en", "es"};   // more languages later: add the code here and its values-xx folder
    private static final String[] NAMES = {"Same as the TV", "English", "Español"};

    private static String chosen = "";
    /** True when TV Town is translated into the TV's own language (each translation sets lang_ready). */
    private static boolean tvHas;

    private Lang() {
    }

    static void init(Context c) {
        chosen = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("app_lang", "");
        tvHas = !c.getString(R.string.lang_ready).isEmpty();
    }

    static String chosen() {
        return chosen;
    }

    static void choose(Context c, String code) {
        chosen = code;
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("app_lang", code).apply();
    }

    static String name(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code.isEmpty() ? NAMES[0] : new Locale(code).getDisplayLanguage(new Locale(code));
    }

    /** The language in use: the chosen one, else the TV's when TV Town has it, else English. */
    static String code() {
        if (!chosen.isEmpty()) return chosen;
        String tv = Locale.getDefault().getLanguage();
        if (!tvHas) return "en";   // no translation for the TV's language yet: English throughout, not half and half
        for (String c : CODES) if (!c.isEmpty() && c.equals(tv)) return c;
        return "en";
    }

    /** A screen's context speaking the language in use (nothing changes when it is the TV's own). */
    static Context wrap(Context base) {
        if (chosen.isEmpty()) return base;
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        Locale l = new Locale(chosen, Style.country());
        Locale.setDefault(l);
        conf.setLocale(l);
        return base.createConfigurationContext(conf);
    }

    /**
     * What TMDB is asked in: English as written in the country ("en-IN"), or the language in use with the country
     * ("es-ES", "pt-BR"…). TMDB fills in titles and stories it has translated; others stay as it has them.
     */
    static String tmdb() {
        String code = code();
        if ("en".equals(code)) return Style.englishLocale();
        switch (code) {
            case "es": return "es-" + (in("MX", "AR", "CO", "CL", "PE", "US") ? Style.country() : "ES");
            case "pt": return "PT".equals(Style.country()) ? "pt-PT" : "pt-BR";
            case "fr": return "fr-" + (in("CA", "BE", "CH") ? Style.country() : "FR");
            case "de": return "de-" + (in("AT", "CH") ? Style.country() : "DE");
            case "it": return "it-IT";
            case "hi": return "hi-IN";
            default: return Style.englishLocale();
        }
    }

    /** The locale dates are written in: English, or the language in use ("martes, 10 de noviembre"). */
    static Locale dates() {
        String c = code();
        return "en".equals(c) ? Locale.ENGLISH : new Locale(c, Style.country());
    }

    /** The day's date in the language in use: the country's English pattern, or the language's own. */
    static String datePattern() {
        return "es".equals(code()) ? "EEEE, d 'de' MMMM" : Style.datePattern();
    }

    /** For voice search: the language in use, as Google's recogniser wants it ("es-ES", "hi-IN", "en-IN"). */
    static String speech() {
        return tmdb();
    }

    private static boolean in(String... countries) {
        for (String c : countries) if (c.equals(Style.country())) return true;
        return false;
    }
}
