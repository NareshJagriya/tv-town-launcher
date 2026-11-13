package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The service keys: one added on the TV (Settings, typed on a phone) wins; else the one built into this copy (a
 * personal build); else none, and the features that need it say how to add it.
 */
final class Keys {

    static final String TMDB = "tmdb";

    private Keys() {
    }

    private static SharedPreferences prefs() {
        return TvTownApp.context().getSharedPreferences("keys", Context.MODE_PRIVATE);
    }

    static String tmdb() {
        String k = prefs().getString(TMDB, "").trim();
        return k.isEmpty() ? BuildConfig.TMDB_API_KEY.trim() : k;
    }

    static void set(String which, String key) {
        prefs().edit().putString(which, key == null ? "" : key.trim()).apply();
    }
}
