package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Scores beyond cricket, from the same free public feed: football, basketball, tennis and Formula 1. Which sports show
 * is chosen on the Sports page (cricket and football to begin with). Each comes back as a Cricket.Match, so Home's top
 * bar and the Sports page show them the same way.
 */
final class Scores {

    static final String CRICKET = "cricket", FOOTBALL = "football", BASKETBALL = "basketball", TENNIS = "tennis", F1 = "f1";
    static final String[] SPORTS = {CRICKET, FOOTBALL, BASKETBALL, TENNIS};   // F1 left out: a race is 20 drivers, not a score
    static final String[] NAMES = {L10n.t("Cricket"), L10n.t("Football"), L10n.t("Basketball"), L10n.t("Tennis")};

    private static final String FEED = "https://site.api.espn.com/apis/site/v2/sports/";

    /** sport, the feed's path, and the competition's name as shown. */
    private static final String[][] LEAGUES = {
            {FOOTBALL, "soccer/eng.1", L10n.t("Premier League")},
            {FOOTBALL, "soccer/uefa.champions", L10n.t("Champions League")},
            {FOOTBALL, "soccer/ind.1", L10n.t("Indian Super League")},
            {FOOTBALL, "soccer/esp.1", L10n.t("La Liga")},
            {FOOTBALL, "soccer/fifa.world", L10n.t("FIFA World Cup")},
            {BASKETBALL, "basketball/nba", "NBA"},
            {TENNIS, "tennis/atp", "ATP"},
            {TENNIS, "tennis/wta", "WTA"},
            {F1, "racing/f1", L10n.t("Formula 1")}};

    private Scores() {
    }

    static Set<String> chosen(Context c) {
        Set<String> s = c.getSharedPreferences("home", Context.MODE_PRIVATE).getStringSet("sports", null);
        if (s == null) {
            s = new HashSet<>();
            s.add(CRICKET);
            s.add(FOOTBALL);
        }
        Set<String> out = new HashSet<>(s);
        out.retainAll(java.util.Arrays.asList(SPORTS));   // a sport since left out (F1) is dropped
        return out;
    }

    static void toggle(Context c, String sport) {
        Set<String> s = chosen(c);
        if (!s.remove(sport)) s.add(sport);
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putStringSet("sports", s).apply();
    }

    /** Today's matches in the chosen sports other than cricket. */
    static List<Cricket.Match> load(Set<String> sports) {
        List<Cricket.Match> out = new ArrayList<>();
        for (String[] l : LEAGUES) {
            if (!sports.contains(l[0])) continue;
            try {
                JSONArray events = new JSONObject(Net.text(Net.get(FEED + l[1] + "/scoreboard", null, 15_000, 1))).optJSONArray("events");
                if (events == null) continue;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    if (l[0].equals(TENNIS)) tennis(e, l[2], out);
                    else {
                        Cricket.Match m = l[0].equals(F1) ? race(e, l[2]) : game(e, l[0], l[2]);
                        if (m != null) {
                            m.event = e.optString("id");
                            m.feed = l[1];
                            out.add(m);
                        }
                    }
                }
            } catch (Exception oneFeed) {
                android.util.Log.w("TVTown", "scores " + l[1] + ": " + oneFeed);   // the other sports still show
            }
        }
        android.util.Log.i("TVTown", "scores: " + out.size() + " for " + sports);
        return out;
    }

    /** A football or basketball game: home side first, as these sports are written. */
    @Nullable
    private static Cricket.Match game(JSONObject e, String sport, String league) throws JSONException {
        JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray sides = comp.getJSONArray("competitors");
        if (sides.length() < 2) return null;
        JSONObject a = sides.getJSONObject(0), b = sides.getJSONObject(1);
        if ("away".equals(a.optString("homeAway"))) {
            JSONObject t = a;
            a = b;
            b = t;
        }
        Cricket.Match m = base(e, sport, league);
        if (m == null) return null;
        m.t1 = code(a);
        m.t2 = code(b);
        JSONObject ta = a.optJSONObject("team"), tb = b.optJSONObject("team");
        if (ta != null) {
            m.logo1 = logo(ta);
            m.col1 = colour(ta);
        }
        if (tb != null) {
            m.logo2 = logo(tb);
            m.col2 = colour(tb);
        }
        JSONObject ground = comp.optJSONObject("venue");
        if (ground != null) m.venue = ground.optString("fullName");
        plays(comp, a, b, m);
        if (!m.start.isEmpty() && !m.live && !m.ended) return m;
        m.s1 = a.optString("score");
        m.s2 = b.optString("score");
        return m;
    }

    /** Tennis: only the matches being played, the singles first. */
    private static void tennis(JSONObject e, String tour, List<Cricket.Match> out) throws JSONException {
        JSONArray groups = e.optJSONArray("groupings");
        if (groups == null) return;
        int added = 0;
        for (int g = 0; g < groups.length() && added < 6; g++) {
            JSONArray comps = groups.getJSONObject(g).optJSONArray("competitions");
            for (int i = 0; comps != null && i < comps.length() && added < 6; i++) {
                JSONObject c = comps.getJSONObject(i);
                if (!"in".equals(c.getJSONObject("status").getJSONObject("type").optString("state"))) continue;
                JSONArray sides = c.getJSONArray("competitors");
                if (sides.length() < 2) continue;
                Cricket.Match m = new Cricket.Match();
                m.sport = TENNIS;
                m.series = tour + " · " + e.optString("shortName", e.optString("name"));
                m.live = true;
                m.rank = 20;
                m.t1 = player(sides.getJSONObject(0));
                m.t2 = player(sides.getJSONObject(1));
                m.s1 = sets(sides.getJSONObject(0));
                m.s2 = sets(sides.getJSONObject(1));
                m.status = c.getJSONObject("status").getJSONObject("type").optString("shortDetail");
                m.start = "";
                out.add(m);
                added++;
            }
        }
    }

    /** Formula 1: today's session, with who is leading or who won. */
    @Nullable
    private static Cricket.Match race(JSONObject e, String league) throws JSONException {
        Cricket.Match m = base(e, F1, league);
        if (m == null) return null;
        m.series = e.optString("shortName", e.optString("name"));
        JSONArray comps = e.optJSONArray("competitions");
        JSONArray sides = comps == null || comps.length() == 0 ? null : comps.getJSONObject(comps.length() - 1).optJSONArray("competitors");
        m.t1 = "F1";
        m.t2 = "";
        if (sides != null && sides.length() > 0 && (m.live || m.ended)) {
            String first = player(sides.getJSONObject(0));
            m.t1 = first;
            m.s1 = m.live ? "leads" : "wins";
        }
        return m;
    }

    /** What every sport has: its state, the time it starts, and whether it is on today. */
    @Nullable
    private static Cricket.Match base(JSONObject e, String sport, String league) throws JSONException {
        JSONObject type = e.getJSONObject("status").getJSONObject("type");
        String state = type.optString("state");
        Date when = when(e.optString("date"));
        if ("pre".equals(state) && (when == null || !today(when))) return null;
        if ("post".equals(state) && (when == null || System.currentTimeMillis() - when.getTime() > 20 * 3_600_000L)) return null;
        Cricket.Match m = new Cricket.Match();
        m.sport = sport;
        m.series = league;
        m.live = "in".equals(state);
        m.ended = "post".equals(state);
        m.status = type.optString("detail", type.optString("shortDetail"));
        m.start = "pre".equals(state) && when != null ? new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(when) : "";
        m.rank = m.live ? 10 : m.ended ? 40 : 30;
        if (F1.equals(sport)) m.app = "com.fancode.tv";
        return m;
    }

    /** Goals and cards (football), or the score of each quarter (basketball). */
    private static void plays(JSONObject comp, JSONObject a, JSONObject b, Cricket.Match m) {
        JSONArray d = comp.optJSONArray("details");
        String idA = a.optJSONObject("team") == null ? "" : a.optJSONObject("team").optString("id");
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject p = d.optJSONObject(i);
            if (p == null) continue;
            String mark = p.optBoolean("scoringPlay") ? "\u26BD" : p.optBoolean("redCard") ? "\uD83D\uDFE5" : p.optBoolean("yellowCard") ? "\uD83D\uDFE8" : "";
            if (mark.isEmpty()) continue;
            JSONArray who = p.optJSONArray("athletesInvolved");
            String name = who != null && who.length() > 0 ? who.optJSONObject(0).optString("shortName") : "";
            String side = p.optJSONObject("team") != null && p.optJSONObject("team").optString("id").equals(idA) ? m.t1 : m.t2;
            String kind = p.optBoolean("penaltyKick") ? " (pen)" : p.optBoolean("ownGoal") ? " (own goal)" : "";
            m.plays.add(p.optJSONObject("clock") == null ? "" : p.optJSONObject("clock").optString("displayValue") + "  " + mark + "  " + name + kind + "  \u00B7  " + side);
        }
        JSONArray q1 = a.optJSONArray("linescores"), q2 = b.optJSONArray("linescores");
        if (Scores.BASKETBALL.equals(m.sport) && q1 != null && q2 != null)
            for (int i = 0; i < Math.min(q1.length(), q2.length()); i++)
                m.plays.add((i < 4 ? "Q" + (i + 1) : "OT") + "   " + m.t1 + " " + Math.round(q1.optJSONObject(i).optDouble("value")) + "  \u2013  "
                        + Math.round(q2.optJSONObject(i).optDouble("value")) + " " + m.t2);
    }

    @Nullable
    static String logo(JSONObject team) {
        String l = team.optString("logo");
        if (l.isEmpty()) {
            JSONArray ls = team.optJSONArray("logos");
            if (ls != null && ls.length() > 0) l = ls.optJSONObject(0).optString("href");
        }
        return l.startsWith("https://") ? l : null;
    }

    /** The team's colour, made dark enough to sit behind white writing; 0 when not known. */
    static int colour(JSONObject team) {
        String c = team.optString("color").replace("#", "");
        if (c.length() != 6) return 0;
        try {
            int v = Integer.parseInt(c, 16);
            if (v == 0xFFFFFF || v == 0) {
                String alt = team.optString("alternateColor").replace("#", "");
                if (alt.length() == 6) v = Integer.parseInt(alt, 16);
            }
            return 0xFF000000 | v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A news story: its headline, its photo and the sport it is from. */
    static final class Story {
        String title, image, from;
    }

    /** A league table: the top of it, with badges. */
    static final class Table {
        String league;
        final List<String[]> rows = new ArrayList<>();   // rank, team, badge (may be ""), played, points
    }

    private static final String[][] NEWS = {
            {FOOTBALL, "soccer/eng.1", L10n.t("Premier League")}, {FOOTBALL, "soccer/uefa.champions", L10n.t("Champions League")},
            {BASKETBALL, "basketball/nba", "NBA"}, {TENNIS, "tennis/atp", L10n.t("Tennis")}, {F1, "racing/f1", L10n.t("Formula 1")}};
    private static final String[][] TABLES = {
            {FOOTBALL, "soccer/eng.1", L10n.t("Premier League")}, {FOOTBALL, "soccer/ind.1", L10n.t("Indian Super League")},
            {FOOTBALL, "soccer/esp.1", L10n.t("La Liga")}, {BASKETBALL, "basketball/nba", "NBA"}};

    /** Headlines with photos from the chosen sports, newest first, a few from each. Network. */
    static List<Story> news(Set<String> sports) {
        List<Story> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] n : NEWS) {
            if (!sports.contains(n[0])) continue;
            try {
                JSONArray a = new JSONObject(Net.text(Net.get(FEED + n[1] + "/news?limit=6", null, 15_000, 1))).optJSONArray("articles");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    JSONArray im = x.optJSONArray("images");
                    String pic = im == null || im.length() == 0 ? "" : im.getJSONObject(0).optString("url");
                    String t = x.optString("headline");
                    if (t.isEmpty() || !pic.startsWith("https://") || !seen.add(t)) continue;
                    Story s = new Story();
                    s.title = t;
                    s.image = pic;
                    s.from = n[2];
                    out.add(s);
                }
            } catch (Exception e) {
                // the others still show
            }
        }
        // mixed: one from each in turn
        List<Story> mixed = new ArrayList<>();
        for (int round = 0; mixed.size() < out.size() && round < 10; round++)
            for (String[] n : NEWS) {
                int c = 0;
                for (Story s : out) if (s.from.equals(n[2]) && c++ == round) mixed.add(s);
            }
        return mixed.subList(0, Math.min(12, mixed.size()));
    }

    /** The tables of the chosen sports' leagues, the top eight. Network. */
    static List<Table> tables(Set<String> sports) {
        List<Table> out = new ArrayList<>();
        for (String[] t : TABLES) {
            if (!sports.contains(t[0])) continue;
            try {
                JSONObject j = new JSONObject(Net.text(Net.get("https://site.api.espn.com/apis/v2/sports/" + t[1] + "/standings", null, 15_000, 1)));
                JSONArray kids = j.optJSONArray("children");
                JSONObject st = kids != null && kids.length() > 0 ? kids.getJSONObject(0).optJSONObject("standings") : j.optJSONObject("standings");
                JSONArray es = st == null ? null : st.optJSONArray("entries");
                if (es == null) continue;
                Table tb = new Table();
                tb.league = t[2] + (kids != null && kids.length() > 1 ? " \u00B7 " + kids.getJSONObject(0).optString("name") : "");
                for (int i = 0; i < es.length(); i++) {
                    JSONObject e = es.getJSONObject(i), team = e.getJSONObject("team");
                    String rank = "", played = "", points = "";
                    JSONArray ss = e.optJSONArray("stats");
                    for (int q = 0; ss != null && q < ss.length(); q++) {
                        JSONObject v = ss.getJSONObject(q);
                        switch (v.optString("name")) {
                            case "rank": case "playoffSeed": if (rank.isEmpty()) rank = v.optString("displayValue"); break;
                            case "gamesPlayed": played = v.optString("displayValue"); break;
                            case "points": points = v.optString("displayValue"); break;
                            case "winPercent": if (points.isEmpty()) points = v.optString("displayValue"); break;
                            default: break;
                        }
                    }
                    JSONArray logos = team.optJSONArray("logos");
                    String logo = logos == null || logos.length() == 0 ? "" : logos.getJSONObject(0).optString("href");
                    tb.rows.add(new String[]{rank.isEmpty() ? String.valueOf(i + 1) : rank, team.optString("abbreviation", team.optString("shortDisplayName")), logo, played, points});
                }
                java.util.Collections.sort(tb.rows, (x, y) -> {
                    try {
                        return Integer.parseInt(x[0]) - Integer.parseInt(y[0]);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                });
                while (tb.rows.size() > 8) tb.rows.remove(tb.rows.size() - 1);
                if (!tb.rows.isEmpty()) out.add(tb);
            } catch (Exception e) {
                // the others still show
            }
        }
        return out;
    }

    /** The scorecard: cricket's batting and bowling, or the other sports' match stats, side by side. */
    static final class Card {
        String batTitle = "", bowlTitle = "";
        final List<String[]> bat = new ArrayList<>(), bowl = new ArrayList<>();   // name, then figures
        final List<String[]> stats = new ArrayList<>();                            // label, side 1, side 2
        final List<Innings> innings = new ArrayList<>();                           // cricket: every innings, newest first
        long at = System.currentTimeMillis();
    }

    /** One innings in full: every batter (name, how out, R, B, 4s, 6s, SR), the extras and total, every bowler (O, M, R, W, Econ). */
    static final class Innings {
        String title = "", extras = "", bowlTitle = "", dnb = "";
        int number;
        final List<String[]> bat = new ArrayList<>(), bowl = new ArrayList<>();
    }

    /** One player's figures in one innings, by name ("runs" → "42"). */
    private static java.util.Map<String, String> figures(JSONObject ls) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        JSONObject st = ls == null ? null : ls.optJSONObject("statistics");
        JSONArray cats = st == null ? null : st.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            for (JSONObject x : list(cats.optJSONObject(i).optJSONArray("stats"))) out.put(x.optString("name"), x.optString("displayValue"));
        }
        return out;
    }

    private static String howOut(String card) {
        switch (card) {
            case "c": return L10n.t("caught");
            case "b": return L10n.t("bowled");
            case "lbw": return "lbw";
            case "st": return L10n.t("stumped");
            case "ro": case "run out": return L10n.t("run out");
            case "hw": return L10n.t("hit wicket");
            case "not out": return L10n.t("not out");
            default: return card.isEmpty() ? L10n.t("out") : card;
        }
    }

    /**
     * Every innings of the match, newest first: the totals from the header, each batter and bowler from the team
     * lists (they hold every innings), and the feed's latest card for how each batter got out in words.
     */
    static List<Innings> everyInnings(JSONObject j, JSONArray cards) throws org.json.JSONException {
        List<Innings> out = new ArrayList<>();
        JSONObject header = j.optJSONObject("header");
        JSONArray comps = header == null ? null : header.optJSONArray("competitions");
        JSONArray sides = comps == null || comps.length() == 0 ? null : comps.optJSONObject(0).optJSONArray("competitors");
        JSONArray rosters = j.optJSONArray("rosters");
        if (sides == null || rosters == null) return out;
        // the words for how out, and the extras, from the latest card(s)
        java.util.Map<String, String> words = new java.util.HashMap<>();
        java.util.Map<Integer, String> extrasText = new java.util.HashMap<>();
        for (JSONObject x : list(cards)) {
            if (!"Batting".equals(x.optString("headline"))) continue;
            int no = x.optInt("inningsNumber");
            String t = x.optString("total");
            if (t.matches("\\((b|lb|w|nb|pen) \\d.*")) extrasText.put(no, t);
            for (JSONObject pl : list(x.optJSONArray("playerDetails"))) words.put(no + "\u0001" + pl.optString("playerName"), pl.optString("dismissal"));
        }
        for (JSONObject side : list(sides)) {
            String team = side.optJSONObject("team") != null ? side.getJSONObject("team").optString("displayName") : "";
            String teamId = side.optJSONObject("team") != null ? side.getJSONObject("team").optString("id") : "";
            for (JSONObject ls : list(side.optJSONArray("linescores"))) {
                if (!ls.optBoolean("isBatting")) continue;
                int no = ls.optInt("period");
                java.util.Map<String, String> tot = figures(ls);
                Innings in = new Innings();
                in.number = no;
                String score = ls.optString("score", ls.optInt("runs") + "/" + ls.optInt("wickets"));
                String ov = tot.containsKey("overs") ? tot.get("overs") : String.valueOf(ls.optDouble("overs"));
                String rr = tot.get("runRate");
                String rate = rr == null || rr.isEmpty() ? "" : L10n.t("RR") + " " + rr;
                in.title = team + "  " + score + (score.contains("ov") ? (rate.isEmpty() ? "" : "  \u00b7 " + rate)
                        : "  (" + ov + " " + L10n.t("ov") + (rate.isEmpty() ? "" : ", " + rate) + ")");
                String ex = tot.get("extras");
                if (ex != null && !ex.isEmpty() && !"0".equals(ex)) {
                    String parts = extrasText.get(no);
                    if (parts == null) {
                        StringBuilder b = new StringBuilder();
                        String[][] kinds = {{"byes", "b"}, {"legbyes", "lb"}, {"wides", "w"}, {"noballs", "nb"}, {"penalties", "pen"}};
                        for (String[] kd : kinds) {
                            String v = tot.get(kd[0]);
                            if (v != null && !v.isEmpty() && !"0".equals(v)) b.append(b.length() == 0 ? "" : ", ").append(kd[1]).append(' ').append(v);
                        }
                        parts = b.length() == 0 ? "" : "(" + b + ")";
                    }
                    in.extras = L10n.t("Extras ") + ex + (parts.isEmpty() ? "" : "  " + parts);
                }
                List<Object[]> bats = new ArrayList<>(), bowls = new ArrayList<>();
                List<String> dnb = new ArrayList<>();
                for (JSONObject r : list(rosters)) {
                    boolean mine = r.optJSONObject("team") != null && teamId.equals(r.getJSONObject("team").optString("id"));
                    if (!mine) in.bowlTitle = (r.optJSONObject("team") != null ? r.getJSONObject("team").optString("displayName") : "") + L10n.t(" bowling");
                    for (JSONObject p : list(r.optJSONArray("roster"))) {
                        String who = p.optJSONObject("athlete") != null ? p.getJSONObject("athlete").optString("displayName") : "";
                        for (JSONObject per : list(p.optJSONArray("linescores"))) {
                            if (per.optInt("period") != no) continue;
                            for (JSONObject one : list(per.optJSONArray("linescores"))) {
                                java.util.Map<String, String> f = figures(one);
                                if (mine) {
                                    if (!"1".equals(f.get("batted"))) {
                                        if (f.containsKey("batted") || f.containsKey("ballsFaced")) dnb.add(who);
                                        continue;
                                    }
                                    String how = words.get(no + "\u0001" + who);
                                    if ("1".equals(f.get("notouts"))) how = L10n.t("not out");   // the team list is fresher than the card
                                    else if (how == null || how.isEmpty() || how.contains("not out")) how = howOut(String.valueOf(f.get("dismissalCard")));
                                    String sr = f.get("strikeRate");
                                    bats.add(new Object[]{num(f.get("battingPosition")), new String[]{who, how, f.get("runs"), f.get("ballsFaced"), f.get("fours"), f.get("sixes"), sr == null ? "" : sr}});
                                } else if ("1".equals(f.get("bowled"))) {
                                    bowls.add(new Object[]{num(f.get("bowlingPosition")), new String[]{who, f.get("overs"), f.get("maidens"), f.get("conceded"), f.get("wickets"), f.get("economyRate")}});
                                }
                            }
                        }
                    }
                }
                java.util.Collections.sort(bats, (a, b) -> (int) a[0] - (int) b[0]);
                java.util.Collections.sort(bowls, (a, b) -> (int) a[0] - (int) b[0]);
                for (Object[] b : bats) in.bat.add((String[]) b[1]);
                for (Object[] b : bowls) in.bowl.add((String[]) b[1]);
                if (!dnb.isEmpty() && !in.bat.isEmpty()) in.dnb = L10n.t("Did not bat: ") + String.join(", ", dnb);
                if (!in.bat.isEmpty() || !in.bowl.isEmpty()) out.add(in);
            }
        }
        java.util.Collections.sort(out, (a, b) -> b.number - a.number);
        return out;
    }

    private static int num(String s) {
        try {
            return s == null ? 99 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 99;
        }
    }

    /** Fetches the card for one match, or null. Network: off the main thread. */
    @Nullable
    static Card card(Cricket.Match m) {
        if (m.event.isEmpty() || m.feed.isEmpty()) return null;
        try {
            if (CRICKET.equals(m.sport)) {
                JSONObject j = new JSONObject(Net.text(Net.get("https://site.web.api.espn.com/apis/site/v2/sports/cricket/" + m.feed + "/summary?event=" + m.event, null, 15_000, 1)));
                JSONArray cards = j.optJSONArray("matchcards");
                if (cards == null) cards = new JSONArray();
                Card c = new Card();
                int bat = -1, bowl = -1;
                for (int i = 0; i < cards.length(); i++) {   // the latest innings
                    JSONObject x = cards.getJSONObject(i);
                    if ("Batting".equals(x.optString("headline")) && (bat < 0 || x.optInt("inningsNumber") >= cards.getJSONObject(bat).optInt("inningsNumber"))) bat = i;
                    if ("Bowling".equals(x.optString("headline")) && (bowl < 0 || x.optInt("inningsNumber") >= cards.getJSONObject(bowl).optInt("inningsNumber"))) bowl = i;
                }
                c.innings.addAll(everyInnings(j, cards));
                if (bat >= 0) {
                    JSONObject b = cards.getJSONObject(bat);
                    c.batTitle = b.optString("teamName") + "  " + b.optString("runs") + " " + b.optString("total");
                    List<JSONObject> ps = list(b.optJSONArray("playerDetails"));
                    java.util.Collections.sort(ps, (p, q) -> {   // the two at the crease first, then the top scores
                        boolean pn = p.optString("dismissal").contains("not out"), qn = q.optString("dismissal").contains("not out");
                        return pn != qn ? (pn ? -1 : 1) : q.optInt("runs") - p.optInt("runs");
                    });
                    for (int i = 0; i < Math.min(4, ps.size()); i++) {
                        JSONObject p = ps.get(i);
                        boolean in = p.optString("dismissal").contains("not out");
                        c.bat.add(new String[]{p.optString("playerName") + (in ? " *" : ""), p.optString("runs"), p.optString("ballsFaced"), p.optString("fours"), p.optString("sixes")});
                    }
                }
                if (bowl >= 0) {
                    JSONObject b = cards.getJSONObject(bowl);
                    c.bowlTitle = b.optString("teamName") + " bowling";
                    List<JSONObject> ps = list(b.optJSONArray("playerDetails"));
                    java.util.Collections.sort(ps, (p, q) -> q.optInt("wickets") != p.optInt("wickets") ? q.optInt("wickets") - p.optInt("wickets")
                            : Double.compare(q.optDouble("overs", 0), p.optDouble("overs", 0)));
                    for (int i = 0; i < Math.min(4, ps.size()); i++) {
                        JSONObject p = ps.get(i);
                        c.bowl.add(new String[]{p.optString("playerName"), p.optString("overs"), p.optString("conceded"), p.optString("wickets"), p.optString("economyRate")});
                    }
                }
                if (c.bat.isEmpty() && c.bowl.isEmpty() && c.innings.isEmpty()) return null;
                if (c.bat.isEmpty() && !c.innings.isEmpty()) {   // no latest card in the feed: the latest innings in short
                    Innings last = c.innings.get(0);
                    c.batTitle = last.title;
                    for (int i = 0; i < Math.min(4, last.bat.size()); i++) {
                        String[] b = last.bat.get(i);
                        c.bat.add(new String[]{b[0] + ("not out".equals(b[1]) ? " *" : ""), b[2], b[3], b[4], b[5]});
                    }
                    c.bowlTitle = last.bowlTitle;
                    for (int i = 0; i < Math.min(4, last.bowl.size()); i++) {
                        String[] b = last.bowl.get(i);
                        c.bowl.add(new String[]{b[0], b[1], b[3], b[4], b[5]});
                    }
                }
                return c;
            }
            JSONObject j = new JSONObject(Net.text(Net.get(FEED + m.feed + "/summary?event=" + m.event, null, 15_000, 1)));
            JSONArray teams = j.optJSONObject("boxscore") == null ? null : j.getJSONObject("boxscore").optJSONArray("teams");
            if (teams == null || teams.length() < 2) return null;
            JSONObject a = teams.getJSONObject(0), b = teams.getJSONObject(1);
            if (b.optJSONObject("team") != null && m.t1.equals(b.getJSONObject("team").optString("abbreviation"))) {
                JSONObject t = a;
                a = b;
                b = t;
            }
            Card c = new Card();
            JSONArray sa = a.optJSONArray("statistics"), sb = b.optJSONArray("statistics");
            String[] want = {L10n.t("Possession"), "SHOTS", L10n.t("ON GOAL"), L10n.t("Shots on Target"), L10n.t("Corner Kicks"), L10n.t("Fouls"), L10n.t("Saves"), "Field Goal %", "Three Point %", L10n.t("Rebounds"), L10n.t("Assists"), L10n.t("Turnovers")};
            for (String w : want) {
                for (int i = 0; sa != null && sb != null && i < Math.min(sa.length(), sb.length()); i++) {
                    JSONObject x = sa.getJSONObject(i), y = sb.getJSONObject(i);
                    String label = x.optString("label");
                    if (!label.equalsIgnoreCase(w)) continue;
                    String nice = label.equals("SHOTS") ? L10n.t("Shots") : label.equals("ON GOAL") ? L10n.t("Shots on target") : label;
                    c.stats.add(new String[]{nice, x.optString("displayValue"), y.optString("displayValue")});
                }
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<JSONObject> list(@Nullable JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }

    private static String code(JSONObject side) {
        JSONObject t = side.optJSONObject("team");
        if (t == null) return player(side);
        String a = t.optString("abbreviation");
        return a.isEmpty() ? t.optString("shortDisplayName") : a;
    }

    private static String player(JSONObject side) {
        JSONObject a = side.optJSONObject("athlete");
        if (a == null) return code(side);
        String s = a.optString("shortName");
        return s.isEmpty() ? a.optString("displayName") : s;
    }

    private static String sets(JSONObject side) {
        JSONArray l = side.optJSONArray("linescores");
        StringBuilder b = new StringBuilder();
        for (int i = 0; l != null && i < l.length(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(Math.round(l.optJSONObject(i).optDouble("value")));
        }
        return b.toString();
    }

    @Nullable
    private static Date when(String iso) {
        if (iso.isEmpty()) return null;
        for (String f : new String[]{"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
                p.setTimeZone(TimeZone.getTimeZone("UTC"));
                return p.parse(iso);
            } catch (java.text.ParseException ignored) {
                // the other way of writing it
            }
        }
        return null;
    }

    private static boolean today(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return f.format(d).equals(f.format(new Date()));
    }
}
