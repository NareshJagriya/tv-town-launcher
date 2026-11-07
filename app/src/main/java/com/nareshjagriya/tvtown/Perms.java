package com.nareshjagriya.tvtown;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Reading the drive's songs and films: one permission up to Android 12, three (music, video, photos) from 13. */
final class Perms {

    private Perms() {
    }

    static String[] media() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    static boolean media(Context c) {
        for (String p : media()) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    static void listen(Context c, android.content.BroadcastReceiver r, android.content.IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f);
    }

    /** Asked once on a new TV: Bluetooth (12 and later) for the sound list, and notifications (13 and later) for what's playing. */
    static void askOnce(android.app.Activity a) {
        android.content.SharedPreferences p = a.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (p.getBoolean("asked_newer", false)) return;
        p.edit().putBoolean("asked_newer", true).apply();
        java.util.List<String> want = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!want.isEmpty()) a.requestPermissions(want.toArray(new String[0]), 77);
    }
}
