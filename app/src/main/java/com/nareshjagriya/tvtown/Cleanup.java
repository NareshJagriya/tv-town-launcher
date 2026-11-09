package com.nareshjagriya.tvtown;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What "Free up this TV" may switch off, as plain data, and the rules that keep it safe.
 *
 * Only apps on these two lists can ever be switched off, and only with "pm disable-user" (nothing is uninstalled, and
 * "Turn all back on" undoes it). SAFE holds what does nothing for someone watching TV: feedback senders, setup
 * leftovers, ad services, sponsored rows. MAYBE holds real features some people use, each saying what stops working.
 * The choice was informed by how often the 457 tested lists of the CleanYourTV project (MIT licence) switch each one off
 * and how often they keep it; the wording and the rules are TV Town's own. Things a TV needs (its Live TV and inputs,
 * the keyboard, setup, Google Play, the remote's pairing, its settings) are on no list, and CleanupActivity checks the
 * TV again before each one. Close apps never touches a home screen.
 */
final class Cleanup {

    private Cleanup() {
    }

    /** One app that can be switched off. */
    static final class App {
        final String pkg, name, what, ifOff;   // ifOff: what you lose (empty for the safe ones)
        final boolean safe;

        App(String pkg, String name, String what, String ifOff, boolean safe) {
            this.pkg = pkg;
            this.name = name;
            this.what = what;
            this.ifOff = ifOff;
            this.safe = safe;
        }
    }

    /** Things that do nothing for someone watching TV. */
    static final List<App> SAFE = Collections.unmodifiableList(Arrays.asList(
            safe("com.google.android.feedback", "Send feedback", "Sends Google feedback and crash reports."),
            safe("com.google.android.tv.bugreportsender", "Bug report sender", "Uploads bug reports to Google."),
            safe("com.android.tv.feedbackconsent", "Feedback prompts", "Asks whether to send usage and feedback data."),
            safe("com.google.android.partnersetup", "Partner setup", "Left over from setup; tells partners about your TV."),
            safe("com.google.android.onetimeinitializer", "One-time setup", "Ran once when the TV was new."),
            safe("com.google.android.syncadapters.calendar", "Calendar sync", "Keeps a Google Calendar copy on the TV."),
            safe("com.android.printspooler", "Printing", "Sends things to a printer."),
            safe("com.google.android.tvrecommendations", "Sponsored rows", "Fills Google's Home with recommended and sponsored rows."),
            safe("com.google.android.leanbacklauncher.recommendations", "Home recommendations", "Recommendations for the older Android TV Home."),
            safe("com.google.android.leanbacklauncher.partnercustomizer", "Partner rows", "The TV maker's rows and apps on Google's Home."),
            safe("com.android.adservices.api", "Ad services", "Android's advertising services."),
            safe("com.android.ondevicepersonalization.services", "Ad personalisation", "Shapes ads to what you do."),
            safe("com.android.federatedcompute.services", "Ad learning", "Learns from your use for the ad services."),
            safe("com.google.android.play.games", "Play Games", "Google's games hub."),
            safe("com.android.settings.intelligence", "Settings suggestions", "Suggestions in the TV's settings search."),
            safe("org.droidtv.usagelogger", "Usage logger", "Philips: logs how the TV and its remote are used."),
            safe("android.autoinstalls.config.*", "App auto-installer", "Puts the maker's promoted apps back after updates.")));

    /** Real features: switched off only if you don't use them. */
    static final List<App> MAYBE = Collections.unmodifiableList(Arrays.asList(
            maybe("com.google.android.katniss", "Google Assistant", "Voice search from the remote's mic.",
                    "The remote's mic button stops working, in TV Town too."),
            maybe("com.google.android.apps.mediashell", "Chromecast built-in", "Casting from your phone to the TV.",
                    "You can't cast to the TV."),
            maybe("com.google.android.videos", "Google TV app", "Google's film shop and watchlist.",
                    "Films bought on Google Play won't play here."),
            maybe("com.google.android.youtube.tvmusic", "YouTube Music", "YouTube's music app.",
                    "YouTube Music won't open."),
            maybe("com.google.android.backdrop", "Ambient mode", "Google's photo screensaver.",
                    "Choose another screensaver first."),
            maybe("com.google.android.apps.tv.dreamx", "Ambient mode", "Google TV's photo screensaver, with promotions.",
                    "Choose another screensaver first."),
            maybe("com.google.android.marvin.talkback", "TalkBack", "Reads the screen aloud.",
                    "The screen reader is gone.")));

    private static App safe(String pkg, String name, String what) {
        return new App(pkg, name, what, "", true);
    }

    private static App maybe(String pkg, String name, String what, String ifOff) {
        return new App(pkg, name, what, ifOff, false);
    }

    /** The listed app a package is, or null. "x.*" on the list matches anything starting "x.". */
    @Nullable
    static App find(String pkg) {
        for (List<App> l : Arrays.asList(SAFE, MAYBE)) {
            for (App a : l) {
                if (a.pkg.endsWith(".*") ? pkg.startsWith(a.pkg.substring(0, a.pkg.length() - 1)) : a.pkg.equals(pkg)) return a;
            }
        }
        return null;
    }

    /** A package name, and nothing else, may go into a command. */
    static boolean cleanName(String pkg) {
        return pkg != null && pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+");
    }

    // ---- closing what runs in the background ----------------------------------------------------------

    /** Never closed: the TV itself, its inputs and remote, Google Play's services, keyboards, TV Town. */
    private static final String[] KEEP = {"system", "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
            "com.google.android.gms", "com.google.android.gsf", "com.google.process.", "com.android.bluetooth", "com.android.phone",
            "com.android.providers.", "com.android.se", "com.android.networkstack", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.google.android.ext.services", "com.android.inputmethod",
            "com.google.android.inputmethod", "com.google.android.tv.remote.service", "com.google.android.tungsten.setupwraith",
            "com.android.keychain", "com.google.android.webview", "com.android.webview", "com.android.externalstorage",
            "com.mediatek.", "mediatek.", "com.mstar.", "org.droidtv.", "com.tpv.", "com.sony.dtv.", "com.android.tv.", "com.nareshjagriya.tvtown",
            // The TV's home screens: closed, the TV starts its own again at once and TV Town's Home comes up over everything.
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher", "com.mitv.tvhome"};

    /** Whether a running package may be closed (the app on screen and the one playing are checked by the caller). */
    static boolean closable(String pkg) {
        if (!cleanName(pkg)) return false;
        for (String k : KEEP) if (k.endsWith(".") ? pkg.startsWith(k) : pkg.equals(k) || pkg.startsWith(k + ":")) return false;
        return true;
    }

    private static final Pattern PSS = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid \\d+");

    /**
     * "dumpsys meminfo"'s list of processes, as memory per app in KB (an app's processes added up, ":service"
     * parts counted with it). Native processes (no package name) are left out.
     */
    static Map<String, Long> memoryByApp(String meminfo) {
        Map<String, Long> out = new LinkedHashMap<>();
        boolean in = false;
        for (String line : meminfo.split("\n")) {
            if (line.startsWith("Total PSS by process")) {
                in = true;
                continue;
            }
            if (!in) continue;
            if (line.trim().isEmpty() || line.startsWith("Total PSS by")) break;
            Matcher m = PSS.matcher(line);
            if (!m.find()) continue;
            String pkg = m.group(2);
            int colon = pkg.indexOf(':');
            if (colon > 0) pkg = pkg.substring(0, colon);
            if (!cleanName(pkg) && !pkg.equals("system")) continue;
            long kb = Long.parseLong(m.group(1).replace(",", ""));
            Long was = out.get(pkg);
            out.put(pkg, was == null ? kb : was + kb);
        }
        return out;
    }

    /** "34 MB" from KB. */
    static String mb(long kb) {
        return String.format(Locale.US, "%d MB", Math.round(kb / 1024.0));
    }

    /** The packages a "pm list packages" answer names. */
    static List<String> packages(String pmList) {
        List<String> out = new ArrayList<>();
        for (String line : pmList.split("\n")) {
            String p = line.trim();
            if (p.startsWith("package:")) out.add(p.substring(8).trim());
        }
        return out;
    }
}
