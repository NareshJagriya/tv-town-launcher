package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The streaming services TV Town knows: their Android TV app, and the names TMDB uses for them. */
final class Services {

    static final class Service {
        final String key, name, pkg;
        final String[] tmdbNames;   // provider names on TMDB, matched ignoring case, spaces and "+"

        Service(String key, String name, String pkg, String... tmdbNames) {
            this.key = key;
            this.name = name;
            this.pkg = pkg;
            this.tmdbNames = tmdbNames;
        }
    }

    static final Service NETFLIX = new Service("netflix", "Netflix", "com.netflix.ninja", "Netflix", "Netflix basic with Ads",
            "Netflix Standard with Ads");
    static final Service PRIME = new Service("prime", "Prime Video", "com.amazon.amazonvideo.livingroom",
            "Amazon Prime Video", "Amazon Prime Video with Ads");
    static final Service HOTSTAR = new Service("hotstar", "JioHotstar", "in.startv.hotstar", "JioHotstar", "Hotstar",
            "Disney Plus Hotstar");
    static final Service APPLE = new Service("apple", "Apple TV+", "com.apple.atve.androidtv.appletv", "Apple TV", "Apple TV Plus", "Apple TV+");
    static final Service ZEE5 = new Service("zee5", "Zee5", "com.graymatrix.did", "Zee5");
    static final Service SONYLIV = new Service("sonyliv", "Sony LIV", "com.sonyliv", "Sony Liv", "SonyLIV");

    static final Service DISNEY = new Service("disney", "Disney+", "com.disney.disneyplus", "Disney Plus");
    static final Service MAX = new Service("max", "HBO Max", "com.wbd.stream", "HBO Max", "Max", "Max Amazon Channel");
    static final Service HULU = new Service("hulu", "Hulu", "com.hulu.livingroomplus", "Hulu");
    static final Service PEACOCK = new Service("peacock", "Peacock", "com.peacocktv.peacockandroid", "Peacock",
            "Peacock Premium", "Peacock Premium Plus");
    static final Service PARAMOUNT = new Service("paramount", "Paramount+", "com.cbs.ott", "Paramount Plus",
            "Paramount Plus Premium", "Paramount Plus Essential", "Paramount+ with Showtime");

    /** Every service TV Town knows, in row order. */
    static final Service[] STREAMING = {NETFLIX, PRIME, HOTSTAR, DISNEY, MAX, HULU, APPLE, PEACOCK, PARAMOUNT, ZEE5, SONYLIV};

    /** The services of the streaming country, in row order for Discover and New this week. */
    static List<Service> streaming() {
        switch (Style.country()) {
            case "IN": return java.util.Arrays.asList(NETFLIX, PRIME, HOTSTAR, APPLE, ZEE5, SONYLIV);
            case "US": return java.util.Arrays.asList(NETFLIX, PRIME, DISNEY, MAX, HULU, APPLE, PEACOCK, PARAMOUNT);
            default: return java.util.Arrays.asList(NETFLIX, PRIME, DISNEY, MAX, APPLE, PARAMOUNT);
        }
    }

    static final String YOUTUBE_PKG = "com.google.android.youtube.tv";
    static final String YT_MUSIC_PKG = "com.google.android.youtube.tvmusic";

    private Services() {
    }

    @Nullable
    static Service byPackage(String pkg) {
        for (Service s : STREAMING) if (s.pkg.equals(pkg)) return s;
        return null;
    }

    static boolean installed(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    static List<Service> installed(Context ctx) {
        List<Service> out = new ArrayList<>();
        for (Service s : streaming()) if (installed(ctx, s.pkg)) out.add(s);
        return out;
    }

    /** The streaming apps on this TV; on a new TV with none yet, the popular ones, so picks and lists still fill. */
    static List<Service> installedOrPopular(Context ctx) {
        List<Service> mine = installed(ctx);
        return mine.isEmpty() ? streaming() : mine;
    }

    /** "Apple TV+" and "apple tv plus" compare equal. */
    static String norm(String name) {
        return name.toLowerCase(Locale.ROOT).replace("+", "plus").replaceAll("[^a-z0-9]", "");
    }

    static boolean matches(Service s, String providerName) {
        String n = norm(providerName);
        for (String t : s.tmdbNames) if (norm(t).equals(n)) return true;
        return false;
    }
}
