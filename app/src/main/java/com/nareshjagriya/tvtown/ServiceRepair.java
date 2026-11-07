package com.nareshjagriya.tvtown;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * A force stop takes the home helper (HomeKeyService) off Android's accessibility list, and Home then goes to the TV's
 * own launcher. So while the helper runs we remember it; you turning it off clears that, a force stop doesn't. When TV
 * Town starts again (boot, update or opening it) a remembered helper goes back on the list. This needs
 * WRITE_SECURE_SETTINGS, granted once over adb:
 * adb shell pm grant com.nareshjagriya.tvtown android.permission.WRITE_SECURE_SETTINGS. Without it nothing happens.
 */
final class ServiceRepair {

    private static final String MEMORY = "helper_repair", WANTED = "on";
    private static final String LIST = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

    private ServiceRepair() {
    }

    /** Boot and updates: bring the helper back without waiting for TV Town to open. */
    public static class Starter extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            restore(c);
        }
    }

    static void connected(Context c) {
        c.getSharedPreferences(MEMORY, Context.MODE_PRIVATE).edit().putBoolean(WANTED, true).apply();
    }

    static void disconnected(Context c) {
        c.getSharedPreferences(MEMORY, Context.MODE_PRIVATE).edit().remove(WANTED).apply();
    }

    static void restore(Context c) {
        boolean allowed = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        if (!allowed || !c.getSharedPreferences(MEMORY, Context.MODE_PRIVATE).getBoolean(WANTED, false)) return;
        ComponentName helper = new ComponentName(c, HomeKeyService.class);
        ContentResolver settings = c.getContentResolver();
        String before = Settings.Secure.getString(settings, LIST);
        List<String> rest = new ArrayList<>();
        if (before != null) {
            for (String service : before.split(":")) {
                if (service.isEmpty()) continue;
                if (helper.equals(ComponentName.unflattenFromString(service))) return;   // already on
                rest.add(service);
            }
        }
        String without = String.join(":", rest);
        rest.add(helper.flattenToShortString());
        String with = String.join(":", rest);
        // Android marks a force-stopped service as crashed and won't start it just for being listed; the mark goes when
        // it sees the service leave the list. So it's added, taken off and added again, stopping if anyone else changes it.
        new Thread(() -> {
            try {
                if (!swap(settings, before, with)) return;
                Thread.sleep(1000);
                if (!swap(settings, with, without)) return;
                Thread.sleep(1000);
                if (swap(settings, without, with)) Settings.Secure.putInt(settings, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (InterruptedException | RuntimeException leftAsItIs) {
                // nothing more to do
            }
        }, "repair").start();
    }

    /** Sets the list, only if it's still what we last saw. */
    private static boolean swap(ContentResolver settings, String expected, String next) {
        String now = Settings.Secure.getString(settings, LIST);
        String was = expected == null ? "" : expected;
        return (now == null ? "" : now).equals(was) && Settings.Secure.putString(settings, LIST, next);
    }
}
