package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Today's cricket for Home's top bar and the Cricket page. The scores come from a public feed that needs no key; it
 * isn't a documented service, so when it changes the score simply stops showing. Asked only while Home is showing.
 */
final class Cricket {

    private static final String FEED = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates=";
    private static final long WHILE_ON = 60_000L, WHILE_QUIET = 1_800_000L, AFTER_FAILING = 300_000L;
    private static final String LOCAL_DAY = "yyyy-MM-dd";

    /** The words the feed uses for a match that's on, with nobody playing at the moment. */
    private static final Pattern NOT_PLAYING = Pattern.compile(
            L10n.t("\\b(?:delayed|delay|suspended|stumps|lunch|tea|dinner|rain|no play|play stopped|wet outfield|bad light)\\b"),
            Pattern.CASE_INSENSITIVE);
    /** Overs in a score, as sent: "(21.2/50 ov, target 130)". Shown as "(21.2)". */
    private static final Pattern OVERS_PART = Pattern.compile("\\(([\\d.]+)(?:/\\d+)? ov[^)]*\\)");

    /** A country that follows cricket: its team, and its own competitions. */
    private static final class Fans {
        final String team;
        final List<String> leagues;

        Fans(String team, String... leagues) {
            this.team = team;
            this.leagues = Arrays.asList(leagues);
        }
    }

    private static final Map<String, Fans> FANS = new LinkedHashMap<>();

    static {
        FANS.put("IN", new Fans("india", "indian premier league", "ipl", "women's premier league", "ranji trophy", "syed mushtaq ali",
                L10n.t("vijay hazare"), L10n.t("duleep trophy"), L10n.t("irani cup"), L10n.t("irani trophy"), L10n.t("deodhar trophy")));
        FANS.put("AU", new Fans("australia", "big bash", "sheffield shield", "marsh cup"));
        FANS.put("GB", new Fans("england", "county championship", "the hundred", "t20 blast"));
        FANS.put("NZ", new Fans("new zealand", "super smash", "plunket shield", "ford trophy"));
        FANS.put("ZA", new Fans("south africa", "sa20", "csa t20"));
        FANS.put("PK", new Fans("pakistan", "pakistan super league", "psl", "quaid-e-azam"));
        FANS.put("LK", new Fans("sri lanka", "lanka premier league"));
        FANS.put("BD", new Fans("bangladesh", "bangladesh premier league", "bpl"));
        FANS.put("AF", new Fans("afghanistan"));
        FANS.put("IE", new Fans("ireland"));
        FANS.put("AE", new Fans("united arab emirates", "ilt20", "international league t20"));
        FANS.put("US", new Fans("united states of america", "major league cricket"));
        FANS.put("CA", new Fans("canada"));
    }

    /** One match, ready to show. */
    static final class Match {
        String t1, t2, s1 = "", s2 = "", series = "", kind = "", status = "", start;
        String sport = Scores.CRICKET, app;   // app: where to watch it, when known (other sports)
        String logo1, logo2, venue = "";       // the teams' badges, and where it is played
        int col1, col2;                        // the teams' colours (0: not known)
        final List<String> plays = new ArrayList<>();   // goals, cards, quarters: newest last
        String event = "", feed = "";          // for the scorecard: the match's id, and its feed (a league's id or path)

        String key() {
            return sport + event + t1 + t2;
        }
        boolean live, paused, ended, home, intl;
        int rank;

        /** "IND 245/4 (42.3) v AUS 310". */
        String score() {
            return (t1 + " " + s1).trim() + " v " + (t2 + " " + s2).trim();
        }

        /** The short word in front of the score. */
        String state() {
            if (live) return L10n.t("LIVE");
            if (paused) return L10n.t("Paused");
            if (ended) return L10n.t("Result");
            return start == null ? "" : start;
        }

        /** Worth a place on Home by itself: being played, your team's day, or an international that has paused. */
        boolean forHome() {
            return live || home || (intl && paused);
        }

        /** The app with the rights in India (2026). */
        @Nullable
        String pkg() {
            if (!Scores.CRICKET.equals(sport)) return app;
            String name = series.toLowerCase(Locale.ROOT);
            if (name.contains("lanka premier league")) return "com.fancode.tv";
            return name.contains("asia cup") ? Services.SONYLIV.pkg : Services.HOTSTAR.pkg;
        }
    }

    private static volatile List<Match> matches = Collections.emptyList();
    private static volatile long gotAt, askedAt;
    private static volatile boolean somethingOn, busy;
    private static volatile String forDay = "";
    private static volatile java.util.Set<String> sports = new java.util.HashSet<>(Arrays.asList(Scores.CRICKET, Scores.FOOTBALL));

    /** The sports chosen on the Sports page; the next refresh fetches them. */
    static void useSports(java.util.Set<String> chosen) {
        sports = chosen;
    }

    private Cricket() {
    }

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("cricket_on", true);
    }

    static void setOn(Context ctx, boolean on) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("cricket_on", on).apply();
        if (!on) matches = Collections.emptyList();
        gotAt = 0;
        askedAt = 0;
    }

    /** Today's matches, most interesting first. */
    static List<Match> today() {
        return matches;
    }

    /** The match for Home's score, or null when none deserves the space. */
    @Nullable
    static Match forHome() {
        for (Match m : matches) if (m.forHome()) return m;
        return null;
    }

    private static String dayNow() {
        return new SimpleDateFormat(LOCAL_DAY, Locale.US).format(new Date());
    }

    /** Asks when it's time, off the main thread; `changed` runs on that thread when the list arrives. Cheap to call often. */
    static void refresh(Context ctx, Runnable changed) {
        if (busy || !on(ctx)) return;
        sports = Scores.chosen(ctx);
        String day = dayNow();
        if (!day.equals(forDay)) {
            forDay = day;
            gotAt = 0;
        }
        long now = System.currentTimeMillis(), gap = somethingOn ? WHILE_ON : WHILE_QUIET;
        boolean fresh = now - gotAt < gap, triedLately = now - askedAt < Math.min(gap, AFTER_FAILING);
        if (fresh || triedLately) return;
        askedAt = now;
        ask(changed);
    }

    /** For the Cricket page: the list now, even with the score on Home switched off. */
    static void refreshNow(Runnable changed) {
        if (!busy) ask(changed);
    }

    private static void ask(Runnable changed) {
        busy = true;
        new Thread(() -> {
            try {
                load();
                changed.run();
            } catch (Exception problem) {
                android.util.Log.w("TVTown", "cricket: " + problem);   // the last scores stay
            } finally {
                busy = false;
            }
        }, "cricket").start();
    }

    /** Replaces the list. Network: off the main thread. */
    static void load() throws Exception {
        Fans fans = FANS.get(Style.country());
        List<Match> found = new ArrayList<>();
        String day = dayNow();
        java.util.Set<String> want = sports;
        if (want.contains(Scores.CRICKET) && fans != null) for (JSONObject event : eventsToday()) {
            try {
                Match m = read(event, fans, day);
                boolean already = false;
                if (m != null) for (Match other : found) already |= m.t1.equals(other.t1) && m.t2.equals(other.t2) && m.series.equals(other.series);
                if (m != null && !already) found.add(m);
            } catch (JSONException oddEntry) {
                // the others still show
            }
        }
        found.addAll(Scores.load(want));
        Collections.sort(found, Comparator.comparingInt(m -> m.rank));
        boolean on = false;
        for (Match m : found) if (m.live || m.paused) on = true;
        matches = found;
        somethingOn = on;
        gotAt = System.currentTimeMillis();
    }

    /** Today here can fall on two days in UTC, which is how the feed is arranged; a later day's copy of a match is newer. */
    private static List<JSONObject> eventsToday() throws Exception {
        GregorianCalendar midnight = new GregorianCalendar();
        midnight.set(midnight.get(GregorianCalendar.YEAR), midnight.get(GregorianCalendar.MONTH), midnight.get(GregorianCalendar.DAY_OF_MONTH), 0, 0, 0);
        SimpleDateFormat feedDay = new SimpleDateFormat("yyyyMMdd", Locale.US);
        feedDay.setTimeZone(TimeZone.getTimeZone("UTC"));
        String first = feedDay.format(midnight.getTime());
        String last = feedDay.format(new Date(midnight.getTimeInMillis() + 86_340_000L));
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        addEvents(first, byId);
        if (!last.equals(first)) addEvents(last, byId);
        return new ArrayList<>(byId.values());
    }

    private static void addEvents(String feedDay, Map<String, JSONObject> byId) throws Exception {
        JSONArray groups = new JSONObject(Net.text(Net.get(FEED + feedDay, null, 15_000, 1))).optJSONArray("scores");
        if (groups == null) return;
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.getJSONObject(g);
            JSONArray events = group.optJSONArray("events");
            if (events == null) continue;
            JSONArray leagues = group.optJSONArray("leagues");
            String name = leagues == null || leagues.length() == 0 ? "" : leagues.getJSONObject(0).optString("name");
            for (int n = 0; n < events.length(); n++) {
                JSONObject event = events.getJSONObject(n);
                event.put("ownSeries", name);
                event.put("ownLeague", leagues == null || leagues.length() == 0 ? "" : leagues.getJSONObject(0).optString("id"));
                byId.put(event.optString("id"), event);
            }
        }
    }

    /** One event as a match, or null when it isn't one to show today. */
    @Nullable
    private static Match read(JSONObject event, @Nullable Fans fans, String day) throws JSONException {
        JSONObject game = event.getJSONArray("competitions").getJSONObject(0);
        JSONArray sides = game.getJSONArray("competitors");
        if (sides.length() != 2) return null;
        JSONObject first = sides.getJSONObject(0), second = sides.getJSONObject(1);
        JSONObject team1 = first.getJSONObject("team"), team2 = second.getJSONObject("team");
        String name1 = plainName(team1), name2 = plainName(team2);
        if (name1.isEmpty() || name2.isEmpty()) return null;

        Match m = new Match();
        m.series = event.optString("ownSeries");
        m.event = event.optString("id");
        m.feed = event.optString("ownLeague");
        JSONObject grade = game.optJSONObject("class");
        int level = grade == null ? 0 : grade.optInt("internationalClassId");
        m.intl = isInternational(level);
        if (!m.intl && !isOwnLeague(m.series, fans)) return null;

        JSONObject status = event.getJSONObject("status"), kindOf = status.getJSONObject("type");
        String phase = kindOf.optString("state"), label = kindOf.optString("description");
        String note = status.optString("summary").replace("&amp;", "&");
        if (note.startsWith("Starts at")) note = "";   // the ground's local time: the card shows ours
        m.ended = phase.equals("post");
        m.live = phase.equals("in");
        if (m.live && NOT_PLAYING.matcher(label + " " + note).find()) {   // a Test is "in" for all five days
            m.live = false;
            m.paused = true;
        }
        if (m.paused) {
            String session = status.optString("session");
            m.status = (session.isEmpty() ? "" : session + ": ") + label + (note.isEmpty() ? "" : " - " + note);
        } else {
            m.status = note.isEmpty() && !phase.equals("pre") ? label : note;
        }
        if (fans != null) m.home = isTeam(name1, fans.team) || isTeam(name2, fans.team);

        Date startsAt = whenStarts(event.optString("date"));
        boolean playingNow = m.live || m.paused;
        boolean dayOf = startsAt != null && day.equals(new SimpleDateFormat(LOCAL_DAY, Locale.US).format(startsAt));
        if (!playingNow && !dayOf) return null;   // a Test that began days ago counts only while it's on
        if (!playingNow && !m.ended && startsAt != null) {
            m.start = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(startsAt).toLowerCase(Locale.ROOT);
        }
        m.t1 = team1.optString("abbreviation", name1);
        m.t2 = team2.optString("abbreviation", name2);
        m.logo1 = Scores.logo(team1);
        m.logo2 = Scores.logo(team2);
        m.col1 = Scores.colour(team1);
        m.col2 = Scores.colour(team2);
        JSONObject ground = game.optJSONObject("venue");
        if (ground != null) m.venue = ground.optString("fullName");
        m.s1 = shortScore(first.optString("score"));
        m.s2 = shortScore(second.optString("score"));
        m.kind = kindName(level, m.intl, grade == null ? "" : grade.optString("generalClassCard"));
        int order = m.live ? 0 : m.paused ? 5 : m.ended ? 20 : 10;
        m.rank = order + (m.home ? 0 : 4) + (m.intl ? 0 : 2);
        return m;
    }

    /** The feed's classes for Test, ODI and T20I (1 to 3), and the women's (8 to 10); not A sides or under-19s. */
    private static boolean isInternational(int level) {
        return (level >= 1 && level <= 3) || (level >= 8 && level <= 10);
    }

    private static boolean isOwnLeague(String series, @Nullable Fans fans) {
        if (fans == null) return false;
        String name = series.toLowerCase(Locale.ROOT);
        for (String league : fans.leagues) if (name.contains(league)) return true;
        return false;
    }

    private static boolean isTeam(String name, String team) {
        return name.toLowerCase(Locale.ROOT).startsWith(team);
    }

    private static String shortScore(String score) {
        return OVERS_PART.matcher(score).replaceAll("($1)").trim();
    }

    private static String kindName(int level, boolean intl, String gradeCard) {
        String card = gradeCard.toLowerCase(Locale.ROOT);
        boolean longForm = level == 1 || level == 8 || card.equals("first-class");
        if (intl) {
            if (longForm) return L10n.t("Test");
            return level == 2 || level == 9 ? "ODI" : "T20I";
        }
        if (longForm) return L10n.t("First-class");
        if (card.contains("t20") || card.contains("twenty20")) return "T20";
        return card.contains("list a") || card.contains("od") ? "One-day" : "";
    }

    private static String plainName(JSONObject team) {
        return team.optString("displayName").replaceAll("\\s*\\(.*\\)$", "").trim();
    }

    @Nullable
    private static Date whenStarts(String text) {
        SimpleDateFormat minutes = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US), seconds = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        minutes.setTimeZone(TimeZone.getTimeZone("UTC"));
        seconds.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return text.length() > 17 && text.charAt(16) == ':' ? seconds.parse(text) : minutes.parse(text);
        } catch (ParseException notADate) {
            return null;
        }
    }
}
