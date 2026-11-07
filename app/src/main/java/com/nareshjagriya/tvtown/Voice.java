package com.nareshjagriya.tvtown;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a spoken sentence asks for: open an app ("open Netflix"), a Random pop-up ("random movie", "surprise me"),
 * songs on YouTube ("play Arijit Singh", "Kesariya song"), or else a search in Discover.
 */
final class Voice {

    private Voice() {
    }

    static final int APP = 0, RANDOM = 1, YOUTUBE = 2, SEARCH = 3;

    static final class Command {
        final int kind;
        final String text;   // the app's name, the search, or the Random kind as a number
        Command(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static final Pattern OPEN = Pattern.compile("^(?:please )?(?:open|launch|start|go to|show)\\s+(.+)$");
    private static final Pattern PLAY = Pattern.compile("^(?:please )?(?:play|put on|listen to)\\s+(.+)$");

    /** labels: the installed apps' names, to match "open …" against. */
    static Command parse(String said, List<String> labels) {
        String s = said.toLowerCase(Locale.ROOT).replaceAll("[.!?]", "").trim();
        if (s.matches(".*\\b(surprise me|random movie|random film)\\b.*")) return new Command(RANDOM, "0");
        if (s.matches(".*\\b(random series|random show)\\b.*")) return new Command(RANDOM, "1");
        if (s.matches(".*\\b(random music|random song|random songs)\\b.*")) return new Command(RANDOM, "2");
        if (s.matches(".*\\b(extras|stand ?up|comedy videos|live news|news)\\b.*") && s.split("\\s+").length <= 3) {
            return new Command(RANDOM, "3");
        }
        Matcher open = OPEN.matcher(s);
        if (open.matches()) {
            String want = open.group(1).replaceAll("\\b(app|the)\\b", "").trim();
            String app = bestApp(want, labels);
            if (app != null) return new Command(APP, app);
        }
        String app = bestApp(s, labels);   // just an app's name ("Netflix")
        if (app != null && s.split("\\s+").length <= 3) return new Command(APP, app);
        Matcher play = PLAY.matcher(s);
        if (play.matches()) return new Command(YOUTUBE, play.group(1).trim());
        if (s.matches(".*\\b(song|songs|gaana|gaane|music|playlist)$")) return new Command(YOUTUBE, s);
        return new Command(SEARCH, said.trim());
    }

    /** The app whose name best matches (all its words said, or said words all in its name); null for none. */
    static String bestApp(String said, List<String> labels) {
        String want = Songs.squash(said);
        if (want.length() < 3) return null;
        String best = null;
        for (String l : labels) {
            String name = Songs.squash(l);
            if (name.isEmpty()) continue;
            if (name.equals(want)) return l;
            if ((name.contains(want) || want.contains(name)) && (best == null || l.length() < best.length())) best = l;
        }
        return best;
    }
}
