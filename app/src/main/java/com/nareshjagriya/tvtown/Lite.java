package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lite mode: TV Town drawn the quick way, for small TVs. Nothing is removed; it changes how things are drawn:
 * glass panels are a flat tint instead of the blurred picture behind (Frost.liquid), focus jumps instead of gliding
 * (Frost.glide), Home's picture sits in a card and changes only when you move (Frost.lighter, HomeView.heroTurn),
 * pictures are decoded smaller and fewer are kept in memory (ImageCache), and My Music's effects and turning record
 * stay still. Switching it off puts every one back as it was.
 */
final class Lite {

    private Lite() {
    }

    static volatile boolean on;

    static void init(Context c) {
        on = prefs(c).getBoolean("lite", false);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static void set(Context c, boolean v) {
        if (v == on) return;
        SharedPreferences p = prefs(c);
        if (v) {   // the picture in a card; what was chosen before comes back when Lite goes off
            p.edit().putBoolean("lite_was_lighter", Frost.lighter).apply();
            Frost.setLighter(c, true);
        } else Frost.setLighter(c, p.getBoolean("lite_was_lighter", false));
        on = v;
        p.edit().putBoolean("lite", v).commit();   // written at once: Lite and the picture's look must never disagree
        ImageCache.liteChanged(c);
    }
}
