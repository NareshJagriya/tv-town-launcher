package com.nareshjagriya.tvtown;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.service.dreams.DreamService;

import java.util.ArrayList;
import java.util.List;

/**
 * The TV's screensaver, chosen from Settings: TV Town's own, Aerial Views, or any other installed. Switching it writes
 * the TV's own setting, which needs WRITE_SECURE_SETTINGS; TV Town asks the key helper (over the TV's own debugging
 * link) to grant that once. Without debugging, the TV's screensaver settings open instead.
 */
final class Screensavers {

    static final String AERIAL = "com.neilturner.aerialviews";

    static final class Saver {
        final String name;
        final ComponentName comp;
        final int kind;   // for TV Town's own: what it shows (TvTownDream.kind); -1 for another app's

        Saver(String name, ComponentName comp, int kind) {
            this.name = name;
            this.comp = comp;
            this.kind = kind;
        }
    }

    private Screensavers() {
    }

    /**
     * One list to pick from, nothing to set up: TV Town's film pictures (from Home's picks) and clock, your own
     * videos only when a drive has some, then every other screensaver installed (Aerial Views…).
     */
    private static List<Saver> cached;
    private static long cachedAt;

    static List<Saver> all(Context c) {
        long now = android.os.SystemClock.uptimeMillis();   // Settings asks on every frame: look again every 10 s
        if (cached != null && now - cachedAt < 10_000) return cached;
        cached = find(c);
        cachedAt = now;
        return cached;
    }

    private static List<Saver> find(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Saver> out = new ArrayList<>();
        ComponentName mine = new ComponentName(c, TvTownDream.class);
        out.add(new Saver(L10n.t("Film pictures"), mine, 1));
        out.add(new Saver(L10n.t("Clock"), mine, 2));
        if (TvTownDream.hasVideos(c)) out.add(new Saver(L10n.t("Your videos"), mine, 0));
        for (ResolveInfo ri : pm.queryIntentServices(new Intent(DreamService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)) {
            if (ri.serviceInfo.packageName.equals(c.getPackageName())) continue;
            out.add(new Saver(String.valueOf(ri.loadLabel(pm)), new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name), -1));
        }
        return out;
    }

    /** Where the TV's screensaver is in that list; -1 when it's none of them. */
    static int currentIndex(Context c, List<Saver> all) {
        ComponentName now = current(c);
        int kind = TvTownDream.kind(c);
        int fallback = -1;
        for (int i = 0; i < all.size(); i++) {
            Saver s = all.get(i);
            if (!s.comp.equals(now)) continue;
            if (s.kind < 0 || s.kind == kind) return i;
            if (s.kind == 1) fallback = i;   // L10n.t("your videos") with none on the drive shows the film pictures
        }
        return fallback;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The one the TV uses now, or null. */
    @androidx.annotation.Nullable
    static ComponentName current(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        if (s == null || s.isEmpty()) return null;
        return ComponentName.unflattenFromString(s.split(",")[0]);
    }

    static String currentName(Context c) {
        List<Saver> all = all(c);
        int i = currentIndex(c, all);
        return i >= 0 ? all.get(i).name : L10n.t("None");
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks the key helper to grant the setting permission (it runs as the TV's shell, which may). */
    static void askToAllow(Context c) {
        RemoteKeys.send(c, "grant " + c.getPackageName());
    }

    /** Makes it the TV's screensaver. False when TV Town isn't allowed to (yet). */
    static boolean use(Context c, ComponentName comp) {
        if (!allowed(c)) return false;
        try {
            Settings.Secure.putString(c.getContentResolver(), "screensaver_components", comp.flattenToString());
            Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Opens the TV's screensaver screen; false when this TV only has its main settings (the screensaver is inside). */
    static boolean openTvSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return false;
        }
    }

    /** Starts the chosen screensaver now. False when the TV has no way to. */
    static boolean tryNow(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void getFromStore(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
