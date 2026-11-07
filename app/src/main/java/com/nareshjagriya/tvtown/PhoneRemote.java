package com.nareshjagriya.tvtown;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.net.Uri;
import android.util.Base64;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the phone remote can do: only these named buttons and actions. */
final class PhoneRemote {

    private static final Map<String, Integer> BUTTONS = new HashMap<>();

    static {
        Object[][] b = {{"up", KeyEvent.KEYCODE_DPAD_UP}, {"down", KeyEvent.KEYCODE_DPAD_DOWN}, {"left", KeyEvent.KEYCODE_DPAD_LEFT},
                {"right", KeyEvent.KEYCODE_DPAD_RIGHT}, {"ok", KeyEvent.KEYCODE_DPAD_CENTER}, {"back", KeyEvent.KEYCODE_BACK},
                {"home", KeyEvent.KEYCODE_HOME}, {"subtitle", KeyEvent.KEYCODE_CAPTIONS}, {"menu", KeyEvent.KEYCODE_MENU}, {"play", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
                {"prev", KeyEvent.KEYCODE_MEDIA_PREVIOUS}, {"next", KeyEvent.KEYCODE_MEDIA_NEXT}, {"rew", KeyEvent.KEYCODE_MEDIA_REWIND},
                {"ff", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD}, {"volup", KeyEvent.KEYCODE_VOLUME_UP}, {"voldown", KeyEvent.KEYCODE_VOLUME_DOWN},
                {"mute", KeyEvent.KEYCODE_VOLUME_MUTE}, {"red", KeyEvent.KEYCODE_PROG_RED}, {"green", KeyEvent.KEYCODE_PROG_GREEN},
                {"yellow", KeyEvent.KEYCODE_PROG_YELLOW}, {"blue", KeyEvent.KEYCODE_PROG_BLUE}, {"power", KeyEvent.KEYCODE_POWER}};
        for (Object[] x : b) BUTTONS.put((String) x[0], (Integer) x[1]);
    }

    private PhoneRemote() {
    }

    /** k: a button's name; a: d pressed, u released. The answer tells the page how things stand; null: no such button. */
    @Nullable
    /** The game controller's buttons, as Android names them. */
    private static final Map<String, Integer> PAD = new HashMap<>();

    static {
        Object[][] b = {{"a", KeyEvent.KEYCODE_BUTTON_A}, {"b", KeyEvent.KEYCODE_BUTTON_B}, {"x", KeyEvent.KEYCODE_BUTTON_X}, {"y", KeyEvent.KEYCODE_BUTTON_Y},
                {"l1", KeyEvent.KEYCODE_BUTTON_L1}, {"r1", KeyEvent.KEYCODE_BUTTON_R1}, {"l2", KeyEvent.KEYCODE_BUTTON_L2}, {"r2", KeyEvent.KEYCODE_BUTTON_R2},
                {"start", KeyEvent.KEYCODE_BUTTON_START}, {"select", KeyEvent.KEYCODE_BUTTON_SELECT}, {"mode", KeyEvent.KEYCODE_BUTTON_MODE},
                {"up", KeyEvent.KEYCODE_DPAD_UP}, {"down", KeyEvent.KEYCODE_DPAD_DOWN}, {"left", KeyEvent.KEYCODE_DPAD_LEFT}, {"right", KeyEvent.KEYCODE_DPAD_RIGHT}};
        for (Object[] x : b) PAD.put((String) x[0], (Integer) x[1]);
    }

    /** A controller button pressed (a "d") or let go ("u"), or the stick moved (j: "x,y", each -100 to 100). */
    static String game(Context c, String button, String a, String j) {
        if (!j.isEmpty()) {
            if (!j.matches("-?\\d{1,3},-?\\d{1,3}")) return null;
            return RemoteKeys.send(c, "j " + j) ? "ok" : RemoteKeys.state();
        }
        Integer code = PAD.get(button);
        if (code == null || !(a.equals("d") || a.equals("u"))) return null;
        return RemoteKeys.send(c, (a.equals("d") ? "gd " : "gu ") + code) ? "ok" : RemoteKeys.state();
    }

    static String key(Context c, String k, String a) {
        Integer code = BUTTONS.get(k);
        if (code == null || !(a.equals("d") || a.equals("u"))) return null;
        if (k.equals("home") && HomeKeyService.tvTownIsHome(c)) {   // straight home, without the TV's own launcher in between
            if (a.equals("u")) open(c, "tvtown:home");
            return "ok";
        }
        if (RemoteKeys.send(c, a + " " + code)) return "ok";
        if (a.equals("d")) fallback(c, k, code);   // it's starting, or it can't: what Android allows without it
        return RemoteKeys.state();
    }

    private static void fallback(Context c, String k, int code) {
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (k) {
            case "back":
                HomeKeyService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case "home":
                HomeKeyService.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                break;
            case "volup":
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                break;
            case "voldown":
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                break;
            case "mute":
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                break;
            case "play":
            case "prev":
            case "next":
            case "rew":
            case "ff":
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                break;
            default:
                break;
        }
    }

    static String text(Context c, String t) {
        if (t.isEmpty() || t.length() > 500) return RemoteKeys.state();
        String line = "t " + Base64.encodeToString(t.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return RemoteKeys.send(c, line) ? "ok" : RemoteKeys.state();
    }

    static String state(Context c) throws Exception {
        JSONObject o = new JSONObject().put("remote", RemoteKeys.state());
        MediaController m = TvTools.nowPlaying(c);
        if (m != null && TvTools.playing(m)) o.put("now", TvTools.title(m));
        String app = searchableApp(c);
        o.put("app", app == null ? "TV Town" : label(c, app));
        return o.toString();
    }

    /** The TV's own tools that also call themselves apps: not for a list of things to watch. */
    private static boolean hidden(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String w : new String[]{"factory", "disclaimer", "diagnos", "droidtv.eum", "mediaplayer", ".service", "documentsui", "deskclock", "android.vending", "tvcenter"}) {
            if (p.contains(w)) return true;
        }
        return false;
    }

    /** The apps with a place on the TV's own home row, without its tools. */
    private static List<ResolveInfo> tvApps(Context c) {
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (!hidden(r.activityInfo.packageName)) out.add(r);
        }
        return out;
    }

    static String apps(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<String[]> list = new ArrayList<>();
        for (ResolveInfo r : tvApps(c)) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(c.getPackageName())) list.add(new String[]{pkg, String.valueOf(r.loadLabel(pm))});
        }
        Collections.sort(list, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        JSONArray a = new JSONArray();
        for (String[] x : list) a.put(new JSONObject().put("id", x[0]).put("label", x[1]).put("icon", true));
        return a.toString();
    }

    @Nullable
    static byte[] appIcon(Context c, String pkg) {
        for (ResolveInfo r : tvApps(c)) {
            if (r.activityInfo.packageName.equals(pkg)) return PhoneLink.icon(r.loadIcon(c.getPackageManager()), 96);
        }
        return null;
    }

    /** The app's TV banner, the wide picture the TV's own home shows; null when it has none. */
    @Nullable
    static byte[] appBanner(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        for (ResolveInfo r : tvApps(c)) {
            if (!r.activityInfo.packageName.equals(pkg)) continue;
            android.graphics.drawable.Drawable d = r.activityInfo.loadBanner(pm);
            if (d == null) d = r.activityInfo.applicationInfo.loadBanner(pm);
            return d == null ? null : PhoneLink.banner(d, 320, 180);
        }
        return null;
    }

    private static Intent main(Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_HELPER, true).putExtra(MainActivity.EXTRA_HOME_KEY, true);
    }

    /** The app the phone's search goes into: the one on the screen, or null for TV Town's own search. */
    @Nullable
    private static String searchableApp(Context c) {
        String pkg = HomeKeyService.frontApp();
        if (pkg.isEmpty() || pkg.equals(c.getPackageName()) || hidden(pkg)) return null;
        for (ResolveInfo r : tvApps(c)) if (r.activityInfo.packageName.equals(pkg)) return pkg;
        return null;
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "TV Town";
        }
    }

    /**
     * Search from the phone, in the app on the screen: its own search results when it offers a way in (YouTube,
     * Netflix, Prime Video and apps that take Android's search), else the words typed into its search box. On TV
     * Town, or with no app open, TV Town's search. Answers "app", "typed" or "tvtown".
     */
    static String find(Context c, String q) {
        q = q.trim();
        if (q.isEmpty()) return "empty";
        String pkg = searchableApp(c);
        if (pkg == null) {
            open(c, "tvtown:search", q);
            return "tvtown";
        }
        if (searchApp(c, pkg, q)) return "app";
        text(c, q);   // no way in: type the words into the app's own search box
        return "typed";
    }

    /** Opens this app's own search results for the words, when it offers a way in; false when it doesn't. */
    static boolean searchApp(Context c, String pkg, String q) {
        String w = Uri.encode(q);
        List<Intent> ways = new ArrayList<>();
        if (HomeKeyService.youTube(pkg)) ways.add(view("https://www.youtube.com/results?search_query=" + w, pkg));
        if (pkg.contains("netflix")) {
            ways.add(view("nflx://www.netflix.com/search?q=" + w, pkg));
            ways.add(view("https://www.netflix.com/search?q=" + w, pkg));
        }
        if (pkg.contains("amazon")) ways.add(view("https://app.primevideo.com/search?phrase=" + w, pkg));
        if (pkg.contains("hotstar")) ways.add(view("https://www.hotstar.com/in/explore?search_query=" + w, pkg));
        ways.add(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q));
        ways.add(new Intent("android.search.action.GLOBAL_SEARCH").setPackage(pkg).putExtra(SearchManager.QUERY, q));
        PackageManager pm = c.getPackageManager();
        for (Intent i : ways) {
            if (i.resolveActivity(pm) == null) continue;
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException e) {
                // that way in is closed: try the next
            }
        }
        return false;
    }

    private static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
    }

    /** Only something in the list above, or one of TV Town's own places. */
    @Nullable
    static String open(Context c, String id) {
        return open(c, id, "");
    }

    /** q: words for Search. */
    @Nullable
    static String open(Context c, String id, String q) {
        Intent i = null;
        if (id.equals("tvtown:home")) i = main(c);
        else if (id.equals("tvtown:surprise") || id.equals("tvtown:search") || id.equals("tvtown:control") || id.equals("tvtown:settings")) i = main(c).putExtra(MainActivity.EXTRA_PHONE, id.substring(7)).putExtra(MainActivity.EXTRA_PHONE_WORDS, q);
        else if (id.equals("tvtown:movies")) i = new Intent(c, MyMoviesActivity.class);
        else if (id.equals("tvtown:music")) i = new Intent(c, MyMusicActivity.class);
        else if (id.equals("tvtown:player")) i = new Intent(c, MyMusicActivity.class).putExtra(MyMusicActivity.EXTRA_PLAYER, true);
        else if (id.equals("tvtown:discover")) i = new Intent(c, DiscoverActivity.class);
        else if (id.equals("tvtown:must")) i = new Intent(c, MustWatchActivity.class);
        else if (id.equals("tvtown:cricket")) i = new Intent(c, SportsActivity.class);
        else {
            for (ResolveInfo r : tvApps(c)) {
                if (!r.activityInfo.packageName.equals(id)) continue;
                PackageManager pm = c.getPackageManager();
                i = pm.getLeanbackLaunchIntentForPackage(id);
                if (i == null) i = pm.getLaunchIntentForPackage(id);
                break;
            }
        }
        if (i == null) return null;
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "ok";
    }
}
