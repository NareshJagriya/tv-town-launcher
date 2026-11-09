package com.nareshjagriya.tvtown;

import android.app.Application;
import android.content.Context;

/** Reads the content style and streaming country before any screen or service starts. */
public class TvTownApp extends Application {

    private static Context app;

    /** The app's context, for things that live outside any screen (Keys). */
    static Context context() {
        return app;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        Style.init(this);
        Frost.init(this);
        Lite.init(this);
        ServiceRepair.restore(this);   // the home helper, if a force stop switched it off
        freeOldLookups();
    }

    /**
     * Once: song details, lyrics and singers' lookups are kept on the drive now, so the answers an earlier version
     * kept in the TV's memory go (anything else there, like TMDB answers, simply loads again).
     */
    private void freeOldLookups() {
        android.content.SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        if (p.getBoolean("lookups_on_drive", false)) return;
        p.edit().putBoolean("lookups_on_drive", true).apply();
        new Thread(() -> {
            java.io.File[] old = new java.io.File(getCacheDir(), "http").listFiles();
            if (old != null) for (java.io.File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }, "free").start();
    }
}
