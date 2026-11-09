package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The parts of TV Town that keep running while you're in other apps, each with an off switch in Settings → Features.
 * Everything else (My Movies, My Music, Sports, Discover…) only loads when you open it, so it needs no switch.
 */
final class Features {

    static final String PHONE = "phone", APPS = "watchapps", YT_SAVE = "ytsave";

    private Features() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("features", Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String what) {
        return prefs(c).getBoolean(what, true);
    }

    static void set(Context c, String what, boolean on) {
        prefs(c).edit().putBoolean(what, on).apply();
        if (what.equals(PHONE)) {
            if (on) PhoneLink.start(c.getApplicationContext());
            else PhoneLink.stop();
        }
    }
}
