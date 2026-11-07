package com.nareshjagriya.tvtown;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * Updates from the project's GitHub Releases, so a TV never needs a computer again: once a day TV Town asks GitHub
 * for the latest release, and Settings → Updates shows it. Installing downloads the APK straight into Android's
 * package installer, which asks the viewer to confirm (the first time, Android asks to allow TV Town to install
 * apps). The update keeps every setting, as long as it is signed with the same key as the copy on the TV.
 */
final class Updater {

    static final String REPO = "NareshJagriya/tv-town-launcher";
    private static final long DAY = 24 * 3600_000L;

    /** A newer release: its version, its APK, and what's new. */
    static final class Release {
        String version = "", apkUrl = "", notes = "";
    }

    @Nullable
    static volatile Release available;

    private Updater() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Asks GitHub (at most once a day unless forced); `done` gets a newer release or null, on a background thread. */
    static void check(Context ctx, boolean force, @Nullable Consumer<Release> done) {
        long last = prefs(ctx).getLong("update_checked", 0);
        if (!force && System.currentTimeMillis() - last < DAY) {
            if (done != null) done.accept(available);
            return;
        }
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Release r = null;
            try {
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Accept", "application/vnd.github+json");
                JSONObject o = new JSONObject(Net.text(Net.get("https://api.github.com/repos/" + REPO + "/releases/latest", h, 10_000, 1)));
                r = parse(o, BuildConfig.VERSION_NAME);
                prefs(app).edit().putLong("update_checked", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // offline, or no release yet: try again next time
            }
            available = r;
            if (done != null) done.accept(r);
        }, "update-check").start();
    }

    /** The release when it is newer than `current` and has an APK (not a debug build), else null. */
    @Nullable
    static Release parse(JSONObject o, String current) {
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
        String v = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        if (!newer(v, current)) return null;
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            String name = a == null ? "" : a.optString("name", "");
            String url = a == null ? "" : a.optString("browser_download_url", "");
            if (name.endsWith(".apk") && !name.contains("debug") && url.startsWith("https://")) {
                Release r = new Release();
                r.version = v;
                r.apkUrl = url;
                r.notes = o.optString("body", "").trim();
                return r;
            }
        }
        return null;
    }

    /** "1.10" is newer than "1.9"; "1.2.1" newer than "1.2". */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        String d = s.replaceAll("[^0-9].*$", "");
        try {
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads the release into Android's installer, which then asks the viewer to confirm. When TV Town may not
     * install apps yet, opens the switch for it instead (and returns false).
     */
    static boolean install(Context ctx, Release r, Consumer<String> say) {
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // some TVs have no such screen; the installer asks instead
            }
            say.accept(ctx.getString(R.string.update_allow));
            return false;
        }
        Context app = ctx.getApplicationContext();
        say.accept(ctx.getString(R.string.update_downloading, r.version));
        new Thread(() -> {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    download(r.apkUrl, s);
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent done = PendingIntent.getBroadcast(app, id, new Intent(app, Result.class), flags | PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(done.getIntentSender());
                }
            } catch (Exception e) {
                if (id >= 0) pi.abandonSession(id);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, app.getString(R.string.update_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show());
            }
        }, "update").start();
        return true;
    }

    private static void download(String url, PackageInstaller.Session s) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();   // GitHub sends it on to its file host
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        if (!c.getURL().getProtocol().equals("https")) throw new IOException("Only HTTPS is allowed");
        long size = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("tvtown.apk", 0, size > 0 ? size : -1)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > 200L * 1024 * 1024) throw new IOException("Too large");
                out.write(buf, 0, n);
            }
            s.fsync(out);
        } finally {
            c.disconnect();
        }
    }

    /** What the installer says: ask the viewer to confirm, or report a failure. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, ctx.getString(status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        ? R.string.update_conflict : R.string.update_failed, String.valueOf(msg)), Toast.LENGTH_LONG).show();
            }
        }
    }
}
