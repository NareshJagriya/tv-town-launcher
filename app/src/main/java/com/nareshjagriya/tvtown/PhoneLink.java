package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Your phone as TV Town's remote, for good: a small web app the TV keeps on the home Wi-Fi, for iPhone and Android alike.
 * Scan its QR code once and add it to the phone's home screen. It presses the remote's fixed buttons, opens apps, types,
 * and saves from YouTube (PhoneRemote).
 *
 * Its address holds a long random key, kept on this TV, and it answers nothing without it. Only named actions exist:
 * nothing from the phone ever runs as a command. Plain http, like any page on a home network, so it's for home Wi-Fi.
 */
final class PhoneLink {

    private static final int PORT = 8642, MAX_BODY = 2000;
    private static final Semaphore busy = new Semaphore(4), files = new Semaphore(1);
    private static volatile ServerSocket server;
    private static volatile boolean running;

    private PhoneLink() {
    }

    private static String key(Context c) {
        SharedPreferences p = c.getSharedPreferences("save", Context.MODE_PRIVATE);
        String k = p.getString("phone_key", null);
        if (k != null) return k;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        k = s.toString();
        p.edit().putString("phone_key", k).apply();
        return k;
    }

    /** The page's address, or null with no network. */
    @Nullable
    static String url(Context c) {
        InetAddress ip = PhoneKeyboard.localAddress();
        return ip == null ? null : "http://" + ip.getHostAddress() + ":" + PORT + "/" + key(c);
    }

    static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        String key = key(c);
        Thread t = new Thread(() -> {
            while (running) {
                try (ServerSocket s = new ServerSocket()) {
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(PORT), 8);
                    server = s;
                    while (running) {
                        Socket client = s.accept();
                        if (!busy.tryAcquire()) {
                            client.close();
                            continue;
                        }
                        new Thread(() -> {
                            try (Socket x = client) {
                                x.setSoTimeout(8000);
                                handle(c, key, x);
                            } catch (Exception ignored) {
                                // the phone went away
                            } finally {
                                busy.release();
                            }
                        }, "phone-link-call").start();
                    }
                } catch (Exception e) {
                    if (!running) return;
                    try {
                        Thread.sleep(30_000);   // the port wasn't free yet: try again
                    } catch (InterruptedException i) {
                        return;
                    }
                }
            }
        }, "phone-link");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void handle(Context c, String key, Socket s) throws Exception {
        java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream());
        String request = line(in);
        if (request == null) return;
        String[] parts = request.split(" ");
        long length = 0;
        for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try {
                    length = Long.parseLong(h.substring(15).trim());
                } catch (NumberFormatException ignored) {
                    // no body
                }
            }
        }
        OutputStream out = s.getOutputStream();
        boolean upload = parts.length >= 2 && parts[1].contains("/upload?");
        if (parts.length < 2 || length < 0 || (!upload && length > MAX_BODY)) {
            reply(out, "400 Bad Request", "text/plain", "");
            return;
        }
        String method = parts[0], target = parts[1], query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            query = target.substring(q + 1);
            target = target.substring(0, q);
        }
        String[] path = target.split("/");
        if (path.length < 2 || !same(path[1], key)) {
            reply(out, "404 Not Found", "text/plain", "");
            return;
        }
        String action = path.length > 2 ? path[2] : "";
        if (method.equals("GET") && action.isEmpty() || method.equals("POST")) HomeKeyService.phoneConnected();
        if (method.equals("POST") && action.equals("upload")) {
            if (!files.tryAcquire()) {
                reply(out, "200 OK", "text/plain", "busy");
                return;
            }
            try {
                s.setSoTimeout(60_000);
                reply(out, "200 OK", "text/plain", PhoneFiles.receive(c, value(query, "dir"), value(query, "name"), length, in));
            } finally {
                files.release();
            }
            return;
        }
        if (method.equals("GET") && action.equals("file")) {
            if (!files.tryAcquire()) {
                reply(out, "503 Service Unavailable", "text/plain", "One file at a time, please.");
                return;
            }
            try {
                s.setSoTimeout(60_000);
                if (!PhoneFiles.send(c, value(query, "path"), out)) reply(out, "404 Not Found", "text/plain", "");
            } finally {
                files.release();
            }
            return;
        }
        if (method.equals("GET") && action.isEmpty()) {
            reply(out, "200 OK", "text/html; charset=utf-8", page(c, key));
        } else if (method.equals("GET") && action.equals("manifest.json")) {
            reply(out, "200 OK", "application/manifest+json", "{\"name\":\"TV Town\",\"short_name\":\"TV Town\",\"display\":\"standalone\",\"background_color\":\"#0c0c0e\",\"theme_color\":\"#0c0c0e\",\"start_url\":\"/" + key + "\",\"icons\":[{\"src\":\"/" + key + "/icon.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"}]}");
        } else if (method.equals("GET") && action.equals("icon.png")) {
            replyBytes(out, "image/png", icon(c.getApplicationInfo().loadIcon(c.getPackageManager()), 192));
        } else if (method.equals("GET") && action.equals("appicon") && path.length > 3) {
            byte[] b = PhoneRemote.appIcon(c, URLDecoder.decode(path[3], "UTF-8"));
            if (b == null) reply(out, "404 Not Found", "text/plain", "");
            else replyBytes(out, "image/png", b);
        } else if (method.equals("GET") && action.equals("apptile") && path.length > 3) {
            byte[] b = PhoneRemote.appBanner(c, URLDecoder.decode(path[3], "UTF-8"));
            if (b == null) reply(out, "404 Not Found", "text/plain", "");
            else replyBytes(out, "image/png", b);
        } else if (method.equals("GET") && action.equals("state")) {
            reply(out, "200 OK", "application/json; charset=utf-8", PhoneRemote.state(c));
        } else if (method.equals("GET") && action.equals("apps")) {
            reply(out, "200 OK", "application/json; charset=utf-8", PhoneRemote.apps(c));
        } else if (method.equals("POST") && !action.equals("save")) {
            String form = body(in, (int) length);
            String answer = null;
            if (action.equals("key")) answer = PhoneRemote.key(c, value(form, "k"), value(form, "a"));
            else if (action.equals("text")) answer = PhoneRemote.text(c, value(form, "t"));
            else if (action.equals("find")) answer = PhoneRemote.find(c, value(form, "q"));
            else if (action.equals("game")) answer = PhoneRemote.game(c, value(form, "b"), value(form, "a"), value(form, "j"));
            else if (action.equals("open")) answer = PhoneRemote.open(c, value(form, "id"), value(form, "q"));
            else if (action.equals("savefolder")) answer = PhoneFiles.setSaveFolder(c, value(form, "dir"), "1".equals(value(form, "video")));
            else if (action.equals("mkdir")) answer = PhoneFiles.makeFolder(c, value(form, "dir"), value(form, "name"));
            else if (action.equals("do")) answer = MyMusicView.fromPhone(value(form, "c"));
            else if (action.equals("savenow")) answer = HomeKeyService.saveNowFromPhone("1".equals(value(form, "video")));
            if (answer == null) reply(out, "404 Not Found", "text/plain", "");
            else reply(out, "200 OK", "text/plain", answer);
        } else if (method.equals("GET") && action.equals("files")) {
            reply(out, "200 OK", "application/json; charset=utf-8", PhoneFiles.list(c, value(query, "dir")));
        } else if (method.equals("GET") && action.equals("savefolders")) {
            reply(out, "200 OK", "application/json; charset=utf-8", PhoneFiles.saveFolders(c));
        } else if (method.equals("GET") && action.equals("search")) {
            reply(out, "200 OK", "application/json; charset=utf-8", results(value(query, "q")));
        } else if (method.equals("POST") && action.equals("save")) {
            String form = body(in, (int) length), link = value(form, "url");
            boolean video = "1".equals(value(form, "video"));
            if (link.isEmpty()) {
                reply(out, "400 Bad Request", "text/plain", "");
                return;
            }
            HomeKeyService.saveFromPhone(link, video);
            reply(out, "200 OK", "text/plain", "ok");
        } else {
            reply(out, "404 Not Found", "text/plain", "");
        }
    }

    /** One line of the request, without its ending; null at the end. */
    @Nullable
    private static String line(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return b.toString("UTF-8").replaceAll("\r$", "");
            if (b.size() > 16384) throw new java.io.IOException("too long");
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static String body(java.io.InputStream in, int length) throws Exception {
        byte[] body = new byte[length];
        int n = 0;
        while (n < length) {
            int r = in.read(body, n, length - n);
            if (r < 0) break;
            n += r;
        }
        return new String(body, 0, n, StandardCharsets.UTF_8);
    }

    private static String page(Context c, String key) throws Exception {
        try (java.io.InputStream i = c.getAssets().open("phone_remote.html")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] t = new byte[16384];
            for (int n; (n = i.read(t)) > 0; ) b.write(t, 0, n);
            return b.toString("UTF-8").replace("%KEY%", key);
        }
    }

    /** A TV banner (16:9), w x h pixels, as a PNG. */
    static byte[] banner(android.graphics.drawable.Drawable d, int w, int h) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        android.graphics.Bitmap out = fill(bmp, w, h);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, b);
        if (out != bmp) out.recycle();
        bmp.recycle();
        return b.toByteArray();
    }

    /**
     * Banners leave very different room round their logos (VLC's is small in the middle). This finds the logo (what
     * differs from the corner colour) and zooms in, so every app's logo fills about the same part of its tile.
     */
    private static android.graphics.Bitmap fill(android.graphics.Bitmap src, int w, int h) {
        int bg = src.getPixel(2, 2), minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int p = src.getPixel(x, y);
                int diff = Math.abs(((p >> 16) & 255) - ((bg >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((bg >> 8) & 255)) + Math.abs((p & 255) - (bg & 255))
                        + Math.abs((p >>> 24) - (bg >>> 24));
                if (diff < 60) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return src;
        // the logo's box, grown to 16:9 with room round it (the logo takes about 70% of the height or 80% of the width)
        float lw = maxX - minX + 2, lh = maxY - minY + 2, cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
        float scale = Math.min(w * 0.8f / lw, h * 0.7f / lh);
        if (scale <= 1.05f) return src;   // already fills its banner
        scale = Math.min(scale, 3f);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(bg);
        c.translate(w / 2f, h / 2f);
        c.scale(scale, scale);
        c.translate(-cx, -cy);
        c.drawBitmap(src, 0, 0, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    static byte[] icon(android.graphics.drawable.Drawable d, int size) {
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(canvas);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, b);
        bmp.recycle();
        return b.toByteArray();
    }

    private static void replyBytes(OutputStream out, String type, byte[] b) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String value(String form, String name) throws Exception {
        for (String pair : form.split("&")) {
            int e = pair.indexOf('=');
            if (e > 0 && pair.substring(0, e).equals(name)) return URLDecoder.decode(pair.substring(e + 1), "UTF-8");
        }
        return "";
    }

    private static String results(String typed) throws Exception {
        JSONArray a = new JSONArray();
        if (typed.trim().length() >= 2) {
            List<YtSaver.Result> found = YtSaver.search(typed);
            for (YtSaver.Result r : found) {
                a.put(new JSONObject().put("title", r.title).put("by", r.by).put("length", YtSaver.clock(r.seconds))
                        .put("url", r.url).put("picture", r.picture == null ? "" : r.picture));
            }
        }
        return a.toString();
    }

    private static void reply(OutputStream out, String status, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

}
