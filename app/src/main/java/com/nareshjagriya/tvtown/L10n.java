package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The words TV Town draws itself, in the language chosen in Customize. English is written in the code and comes
 * back as it is (no lookup at all); another language is one file in assets (assets/lang/es.json: English → that
 * language), read once at start and only when that language is chosen. A text it doesn't have stays English.
 */
final class L10n {

    @Nullable
    private static Map<String, String> words;

    private L10n() {
    }

    static void init(Context c) {
        words = null;
        String code = Lang.code();
        if ("en".equals(code)) return;
        try (InputStream in = c.getAssets().open("lang/" + code + ".json")) {
            byte[] b = new byte[in.available()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONObject o = new JSONObject(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> m = new HashMap<>(o.length() * 2);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.optString(k, k));
            }
            words = m;
        } catch (Exception e) {
            android.util.Log.w("TVTown", "language " + code + ": " + e);
        }
    }

    /** The text in the language in use. */
    static String t(@Nullable String english) {
        Map<String, String> m = words;
        if (m == null || english == null) return english;
        String v = m.get(english);
        return v != null ? v : english;
    }

    /** A switch's value: On (in English or the language in use). */
    static boolean isOn(@Nullable String v) {
        return "On".equals(v) || t("On").equals(v);
    }

    static boolean isOff(@Nullable String v) {
        return "Off".equals(v) || t("Off").equals(v);
    }

    /** True while TV Town speaks a language other than English. */
    static boolean translated() {
        return words != null;
    }
}
