package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Posters, backdrops and thumbnails: a memory cache capped at an eighth of the app's heap, a disk cache
 * capped at 100 MB, and downloads limited to the hosts TV Town uses. Images are decoded as RGB_565 (half the
 * memory of full colour; artwork has no transparency).
 */
final class ImageCache {

    private static final long DISK_CAP = 100L * 1024 * 1024;
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "image.tmdb.org", "www.bing.com",   // Home's landscape wallpapers
            "images.justwatch.com", "i.ytimg.com", "a.espncdn.com", "s.espncdn.com",   // team logos and news photos (Sports)
            "images.metahub.space", "btttr.cc", "m.media-amazon.com",   // StreamRadar's pictures
            // song covers from Find details (My Music)
            "thumb.wikimedia.org", "upload.wikimedia.org",   // singers' photos (My Music)
            "is1-ssl.mzstatic.com", "is2-ssl.mzstatic.com", "is3-ssl.mzstatic.com", "is4-ssl.mzstatic.com", "is5-ssl.mzstatic.com"));

    /** Decoded images: 1/16 of the app's memory on a low-memory TV (12 MB on a 1 GB Philips), else 1/8. */
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(
            (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService pool = Work.pool(3);
    /** Images are decoded no wider than this (the screen's width; half of it for YouTube thumbnails). */
    private static int maxWidth = 1920;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Images being loaded, each with whoever is waiting for it. */
    private static final java.util.Map<String, java.util.List<Runnable>> pending = new java.util.HashMap<>();
    private static File dir;
    private static int writes;

    private ImageCache() {
    }

    static void init(Context ctx) {
        if (dir != null) return;
        maxWidth = Math.max(640, ctx.getResources().getDisplayMetrics().widthPixels);
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        if (am != null && !am.isLowRamDevice()) memory.resize((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8));
        dir = new File(ctx.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** The image if it is in memory; otherwise starts loading it (disk, then network) and calls ready when done. */
    @Nullable
    static Bitmap get(@Nullable String url, @Nullable Runnable ready) {
        return get(url, ready, null);
    }

    /**
     * The same, keeping the file in `store` (a folder on the drive, for pictures of your own songs and films)
     * instead of the TV's memory; the TV's is used when store is null.
     */
    @Nullable
    static Bitmap get(@Nullable String url, @Nullable Runnable ready, @Nullable File store) {
        return get(url, ready, store, 0);
    }

    /**
     * The same, made no wider than `width` pixels (0: the usual size). Small cards ask for small copies: a TV with
     * little memory then holds many of them at once, instead of dropping and reloading large ones (flicker).
     */
    @Nullable
    static Bitmap get(@Nullable String url, @Nullable Runnable ready, @Nullable File store, int width) {
        if (url == null) return null;
        String key = width > 0 ? url + "@" + width : url;
        Bitmap hit = memory.get(key);
        if (hit == null && width > 0) hit = memory.get(url);   // put() keeps pictures at one size
        if (hit != null) return hit;
        if (!allowed(url)) return null;
        synchronized (pending) {
            java.util.List<Runnable> waiting = pending.get(key);
            if (waiting != null) {   // already on its way: just wait for it too
                if (ready != null) waiting.add(ready);
                return null;
            }
            waiting = new java.util.ArrayList<>();
            if (ready != null) waiting.add(ready);
            pending.put(key, waiting);
        }
        pool.execute(() -> {
            Bitmap b = load(url, store, width);
            java.util.List<Runnable> waiting;
            synchronized (pending) {
                waiting = pending.remove(key);
            }
            if (b == null) return;
            memory.put(key, b);
            if (waiting != null) for (Runnable r : waiting) main.post(r);
        });
        return null;
    }

    /** A picture read elsewhere (an app's own artwork), kept under its address. */
    static void put(String url, Bitmap b) {
        memory.put(url, b);
    }

    @Nullable
    static Bitmap peek(String url) {
        return memory.get(url);
    }

    /** Starts fetching an image so it is ready later (next page, next backdrop). */
    static void prefetch(@Nullable String url) {
        get(url, null);
    }

    /** Leaving Home: drop decoded images; the disk cache keeps them for next time. */
    static void releaseMemory() {
        memory.evictAll();
    }

    /** Memory is getting short while TV Town is in front: keep only the most recent half. */
    static void trim() {
        memory.trimToSize(memory.size() / 2);
    }

    /** How much to shrink an image while decoding, so it is no wider than needed (a power of two). */
    static int sampleFor(int width, String url, int wanted) {
        int want = wanted > 0 ? wanted : url.contains("i.ytimg.com") ? maxWidth / 2 : maxWidth, s = 1;
        while (width / (s * 2) >= want) s *= 2;
        return s;
    }

    static boolean allowed(String url) {
        if (!url.startsWith("https://")) return false;
        int start = 8, end = url.indexOf('/', start);
        String host = end < 0 ? url.substring(start) : url.substring(start, end);
        return HOSTS.contains(host);
    }

    @Nullable
    private static Bitmap load(String url, @Nullable File store, int width) {
        File folder = store != null ? store : dir;
        File f = folder != null ? new File(folder, Net.sha1(url)) : null;
        if (f != null && f.exists()) {
            Bitmap b = decode(f, url, width);
            if (b != null) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());   // keeps recently used images on disk
                return b;
            }
        }
        try {
            byte[] bytes = Net.get(url, null, 10_000, 1);
            if (f != null) {
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(bytes);
                }
                if (store == null && ++writes % 25 == 0) trimDisk();   // the drive's pictures are kept
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = sampleFor(o.outWidth, url, width);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decode(File f, String url, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleFor(o.outWidth, url, width);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Deletes the least recently used files until the cache is under its cap. */
    private static synchronized void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File x : files) total += x.length();
        if (total <= DISK_CAP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File x : files) {
            if (total <= DISK_CAP * 0.8) break;
            total -= x.length();
            //noinspection ResultOfMethodCallIgnored
            x.delete();
        }
    }
}
