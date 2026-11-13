package com.nareshjagriya.tvtown;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The TV calendar (Discover → Coming up): the next episode of every series you keep in My Watchlist or marked
 * Watched, soonest first, new episodes on your streaming apps and films about to stream. All from TMDB, nothing to
 * sign in to.
 * Rows with nothing in them leave (Discover does that), so with no series saved only the new series show.
 */
final class ComingUp {

    private static final long DAY = 24 * 3600_000L;
    private static final int MAX_SHOWS = 40, LATER_DAYS = 60;

    private ComingUp() {
    }

    private static List<Tmdb.Title> found;   // your shows, worked out once per Discover visit
    private static long foundAt;

    /** Your shows airing within a week (week = true), or after that within two months. */
    static synchronized List<Tmdb.Title> yours(Context ctx, boolean week) throws Exception {
        if (found == null || System.currentTimeMillis() - foundAt > 10 * 60_000L) {
            found = look(ctx);
            foundAt = System.currentTimeMillis();
        }
        long soon = today() + 7 * DAY;
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : found) if ((t.airAt < soon) == week) out.add(t);
        return out;
    }

    private static List<Tmdb.Title> look(Context ctx) {
        List<Library.Entry> shows = new ArrayList<>();
        for (Library.Entry e : Library.all(ctx)) {
            if ("tv".equals(e.type) && e.tmdbId > 0 && (e.status == Library.WANT || e.status == Library.WATCHED)) shows.add(e);
        }
        Collections.sort(shows, (a, b) -> Long.compare(b.time, a.time));
        List<Tmdb.Title> out = new ArrayList<>();
        long from = today(), until = from + LATER_DAYS * DAY;
        for (Library.Entry e : shows.subList(0, Math.min(MAX_SHOWS, shows.size()))) {
            try {
                JSONObject o = Tmdb.series(ctx, e.tmdbId);
                JSONObject next = o.optJSONObject("next_episode_to_air");
                if (next == null) continue;
                long at = day(next.optString("air_date"));
                if (at < from || at > until) continue;
                List<Tmdb.Title> one = Tmdb.parse(new JSONArray().put(o), "tv");
                if (one.isEmpty()) continue;
                Tmdb.Title t = one.get(0);
                t.airLine = when(at) + "  ·  S" + next.optInt("season_number") + " E" + next.optInt("episode_number");
                t.airAt = at;
                out.add(t);
            } catch (Exception ignored) {
                // that one: left out
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.airAt, b.airAt));
        return out;
    }

    /** New episodes on your streaming apps in the next two weeks, each with its day and episode. */
    static List<Tmdb.Title> onYourApps(Context ctx) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long from = today();
        String ids = Tmdb.idsOf(ctx, Services.installedOrPopular(ctx));
        if (ids.isEmpty()) return new ArrayList<>();
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : Tmdb.airingOn(ctx, ids, f.format(new Date(from)), f.format(new Date(from + 14 * DAY)))) {
            if (out.size() >= 15) break;
            if (t.poster() == null && t.backdropCard() == null) continue;
            try {
                JSONObject next = Tmdb.series(ctx, t.id).optJSONObject("next_episode_to_air");
                if (next == null) continue;
                long at = day(next.optString("air_date"));
                if (at < from || at > from + 14 * DAY) continue;
                t.airAt = at;
                t.airLine = when(at) + "  ·  S" + next.optInt("season_number") + " E" + next.optInt("episode_number");
                out.add(t);
            } catch (Exception ignored) {
                // that one: left out
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.airAt, b.airAt));
        return out;
    }

    /** Films starting to stream in your country in the next month, soonest first. */
    static List<Tmdb.Title> filmsSoon(Context ctx) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long from = today();
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : Tmdb.streamingSoon(ctx, f.format(new Date(from)), f.format(new Date(from + 30 * DAY)))) {
            if (out.size() >= 15) break;
            if (t.poster() == null && t.backdropCard() == null) continue;
            long at = day(Tmdb.streamingDate(ctx, t.id));
            if (at < from) continue;
            t.airAt = at;
            t.airLine = L10n.t("Streaming ") + when(at);
            out.add(t);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.airAt, b.airAt));
        return out;
    }

    private static long today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long day(String ymd) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "Today", "Tomorrow", else "Fri 14 Nov". */
    private static String when(long at) {
        long d = Math.round((at - today()) / (double) DAY);
        if (d == 0) return L10n.t("Today");
        if (d == 1) return L10n.t("Tomorrow");
        return new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date(at));
    }
}
