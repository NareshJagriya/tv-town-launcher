package com.nareshjagriya.tvtown;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Every network request goes through here: HTTPS only, connect and read timeouts, a size limit, retries
 * with back-off on network errors and server errors, and an on-disk cache of responses so screens can show
 * saved data when the TV is offline.
 */
final class Net {

    static final int MAX_BYTES = 6 * 1024 * 1024;

    /** A failed request, with the HTTP status when there was one (0 for network errors). */
    static final class Failure extends IOException {
        final int status;

        Failure(String msg, int status) {
            super(msg);
            this.status = status;
        }
    }

    private Net() {
    }

    /** GET with retries. Throws Failure; 4xx responses are not retried. */
    static byte[] get(String url, @Nullable Map<String, String> headers, int timeoutMs, int retries) throws IOException {
        if (!url.startsWith("https://")) throw new Failure("Only HTTPS is allowed", 0);
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Failure("Interrupted", 0);
                }
            }
            try {
                return once(url, headers, timeoutMs);
            } catch (Failure f) {
                if (f.status >= 400 && f.status < 500) throw f;   // our mistake, or rate-limited: do not hammer
                last = f;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new Failure("Request failed", 0);
    }

    private static byte[] once(String url, @Nullable Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TVTownLauncher/" + BuildConfig.VERSION_NAME + " (Android TV)");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new Failure("HTTP " + status, status);
            if (!"https".equals(c.getURL().getProtocol())) throw new Failure("Redirected off HTTPS", 0);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new Failure("Response too large", 0);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** True when the TV has a network connection. */
    static boolean online(android.content.Context ctx) {
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            return cm != null && cm.getActiveNetwork() != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---- response cache --------------------------------------------------------------------------

    /**
     * A cached GET: a fresh copy is used when younger than maxAgeMs; otherwise the network is tried, and on
     * failure a stale copy (up to staleMs old) is used rather than nothing.
     */
    static String cached(Context ctx, String url, @Nullable Map<String, String> headers, long maxAgeMs, long staleMs,
                         int timeoutMs) throws IOException {
        return cached(ctx, url, url, headers, maxAgeMs, staleMs, timeoutMs);
    }

    /** Set on a thread that wants only what is already saved (Home's first picture after a start): no network at all. */
    private static final ThreadLocal<Boolean> SAVED_ONLY = new ThreadLocal<>();

    static boolean savedOnly() {
        return Boolean.TRUE.equals(SAVED_ONLY.get());
    }

    static void savedOnly(boolean on) {
        SAVED_ONLY.set(on);
    }

    /**
     * The same, saved under `key` instead of the address: for addresses that change every day (they carry today's
     * date) but ask the same question, so yesterday's answer can still be shown while today's loads.
     */
    static String cached(Context ctx, String key, String url, @Nullable Map<String, String> headers, long maxAgeMs,
                         long staleMs, int timeoutMs) throws IOException {
        File f = cacheFile(ctx, key);
        long age = f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
        if (age < maxAgeMs || savedOnly() && age < staleMs) {
            String hit = read(f);
            if (hit != null) return hit;
        }
        if (savedOnly()) throw new Failure("Not saved", 0);
        boolean spare = age < staleMs;   // an older copy is at hand: one quick try, not three slow ones
        try {
            byte[] b = get(url, headers, spare ? Math.min(timeoutMs, 4000) : timeoutMs, spare ? 0 : 2);
            write(f, b);
            return text(b);
        } catch (IOException e) {
            if (spare) {
                String stale = read(f);
                if (stale != null) return stale;
            }
            throw e;
        }
    }

    private static File cacheFile(Context ctx, String url) {
        File dir = new File(ctx.getCacheDir(), "http");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, sha1(url));
    }

    @Nullable
    private static String read(File f) {
        try {
            return text(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(b);
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
