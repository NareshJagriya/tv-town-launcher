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

        Saver(String name, ComponentName comp) {
            this.name = name;
            this.comp = comp;
        }
    }

    private Screensavers() {
    }

    /** Every installed screensaver, TV Town's first. */
    static List<Saver> all(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Saver> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentServices(new Intent(DreamService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)) {
            ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            String name = String.valueOf(ri.loadLabel(pm));
            if (cn.getPackageName().equals(c.getPackageName())) out.add(0, new Saver("TV Town", cn));
            else out.add(new Saver(name, cn));
        }
        return out;
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
        ComponentName now = current(c);
        if (now != null) for (Saver s : all(c)) if (s.comp.equals(now)) return s.name;
        return "TV's own";
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

    static void openTvSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
