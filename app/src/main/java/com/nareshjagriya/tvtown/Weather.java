package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The weather beside Home's clock, from Open-Meteo (free, no key, no account): the temperature and a symbol for the
 * sky, for the town you set in Settings → Weather (typed on a phone). Asked at most every 30 minutes, only while
 * Home is shown; off until a town is set. °F in the US, else °C.
 */
final class Weather {

    /** What's shown: "☀️", "24°". */
    static final class Now {
        final String symbol, temperature;

        Now(String symbol, String temperature) {
            this.symbol = symbol;
            this.temperature = temperature;
        }
    }

    private static final long FRESH = 30 * 60_000L, STALE = 6 * 3600_000L;
    @Nullable
    private static volatile Now now;
    private static volatile long askedAt;

    private Weather() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** The town set, or "" (weather off). */
    static String place(Context ctx) {
        return prefs(ctx).getString("weather_place", "");
    }

    static void off(Context ctx) {
        prefs(ctx).edit().remove("weather_place").remove("weather_lat").remove("weather_lon").apply();
        now = null;
    }

    /** The latest weather, or null (off, or not fetched yet). */
    @Nullable
    static Now current() {
        return now;
    }

    /** Finds a town by name (Open-Meteo's place search) and keeps it; returns its full name, or null. Network: call off the main thread. */
    @Nullable
    static String setPlace(Context ctx, String name) {
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + Uri.encode(name.trim());
            JSONArray r = new JSONObject(Net.text(Net.get(url, null, 10_000, 1))).optJSONArray("results");
            JSONObject p = r == null ? null : r.optJSONObject(0);
            if (p == null) return null;
            String full = p.optString("name") + (p.optString("country").isEmpty() ? "" : ", " + p.optString("country"));
            prefs(ctx).edit().putString("weather_place", full).putFloat("weather_lat", (float) p.optDouble("latitude"))
                    .putFloat("weather_lon", (float) p.optDouble("longitude")).apply();
            askedAt = 0;
            return full;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches the weather when due (in the background); `changed` runs on that thread when it arrives. */
    static void refresh(Context ctx, Runnable changed) {
        if (place(ctx).isEmpty() || System.currentTimeMillis() - askedAt < FRESH) return;
        askedAt = System.currentTimeMillis();
        Context app = ctx.getApplicationContext();
        float lat = prefs(ctx).getFloat("weather_lat", 0), lon = prefs(ctx).getFloat("weather_lon", 0);
        boolean f = "US".equals(Style.country());
        new Thread(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day%s", lat, lon, f ? "&temperature_unit=fahrenheit" : "");
                JSONObject c = new JSONObject(Net.cached(app, url, null, FRESH, STALE, 10_000)).getJSONObject("current");
                now = new Now(symbol(c.optInt("weather_code"), c.optInt("is_day", 1) == 1),
                        Math.round(c.optDouble("temperature_2m")) + "°");
                changed.run();
            } catch (Exception ignored) {
                // offline: the last weather stays, or none
            }
        }, "weather").start();
    }

    /** WMO weather code to a symbol. */
    static String symbol(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "🌙";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "🌧️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }
}
