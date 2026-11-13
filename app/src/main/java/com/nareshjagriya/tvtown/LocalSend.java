package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Receiving from the LocalSend app (free, on phones and computers), protocol v2 over plain HTTP. It runs only while
 * the Receive files screen is open: TV Town announces itself on the Wi-Fi, a phone picks it, and songs, videos and
 * subtitles land on the USB drive (Music, Movies) the way the phone remote's Send to TV does. Ten minutes at most.
 */
final class LocalSend {

    private static final int PORT = 53317;
    private static final String GROUP = "224.0.0.167", API = "/api/localsend/v2/";
    private static final long MAX_ON = 10 * 60_000L;

    private LocalSend() {
    }

    private static volatile boolean running;
    private static ServerSocket server;
    private static MulticastSocket multicast;
    private static WifiManager.MulticastLock lock;
    private static Consumer<String> status;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final String fingerprint = UUID.randomUUID().toString().replace("-", "");

    // the one transfer at a time: its id and each accepted file's token, name and size
    private static String session;
    private static final Map<String, String[]> files = new HashMap<>();   // fileId → {token, name, size}
    private static int saved, total;

    static String alias() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        return "TV Town" + (m.isEmpty() ? "" : " (" + m + ")");
    }

    private static JSONObject info() throws Exception {
        return new JSONObject().put("alias", alias()).put("version", "2.1").put("deviceModel", "TV").put("deviceType", "desktop")
                .put("fingerprint", fingerprint).put("port", PORT).put("protocol", "http").put("download", false);
    }

    /** Starts listening; `onStatus` hears what is happening (on the main thread). */
    static synchronized void start(Context ctx, Consumer<String> onStatus) {
        status = onStatus;
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        WifiManager wm = c.getSystemService(WifiManager.class);
        if (wm != null) {
            lock = wm.createMulticastLock("tvtown-localsend");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        new Thread(() -> serve(c), "localsend-http").start();
        new Thread(LocalSend::announce, "localsend-announce").start();
        say(L10n.t("Waiting for files…"));
        main.postDelayed(LocalSend::stop, MAX_ON);
    }

    static synchronized void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // closed
        }
        if (multicast != null) multicast.close();
        if (lock != null && lock.isHeld()) lock.release();
        session = null;
        files.clear();
        status = null;
    }

    static boolean running() {
        return running;
    }

    private static void say(String s) {
        Consumer<String> st = status;
        if (st != null) main.post(() -> st.accept(s));
    }

    // ---- being found: announce ourselves, and answer phones that announce themselves ------------------------

    private static void announce() {
        try (MulticastSocket m = new MulticastSocket(PORT)) {
            multicast = m;
            InetAddress group = InetAddress.getByName(GROUP);
            m.joinGroup(group);
            m.setSoTimeout(2000);
            byte[] hello = info().put("announce", true).toString().getBytes(StandardCharsets.UTF_8);
            long next = 0;
            byte[] buf = new byte[4096];
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= next) {
                    m.send(new DatagramPacket(hello, hello.length, group, PORT));
                    next = now + 3000;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    m.receive(p);
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (fingerprint.equals(o.optString("fingerprint")) || !o.optBoolean("announce")) continue;
                    byte[] reply = info().put("announce", false).toString().getBytes(StandardCharsets.UTF_8);
                    m.send(new DatagramPacket(reply, reply.length, group, PORT));   // a phone said hello: we're here too
                } catch (java.net.SocketTimeoutException quiet) {
                    // nobody spoke
                }
            }
        } catch (Exception e) {
            if (running) say(L10n.t("The TV isn't connected to the network"));
        }
    }

    // ---- the HTTP side ------------------------------------------------------------------------------------

    private static void serve(Context c) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT), 8);
            server = s;
            while (running) {
                Socket client = s.accept();
                new Thread(() -> {
                    try (Socket x = client) {
                        x.setSoTimeout(30_000);
                        handle(c, x);
                    } catch (Exception ignored) {
                        // the phone went away
                    }
                }, "localsend-call").start();
            }
        } catch (Exception e) {
            if (running) say(L10n.t("Couldn't start receiving. Is LocalSend open on this TV too?"));
        }
    }

    private static void handle(Context c, Socket s) throws Exception {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String request = line(in);
        if (request == null) return;
        String[] parts = request.split(" ");
        long length = 0;
        for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Long.parseLong(h.substring(15).trim());
        }
        OutputStream out = s.getOutputStream();
        if (parts.length < 2) {
            reply(out, 400, "");
            return;
        }
        String target = parts[1], query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            query = target.substring(q + 1);
            target = target.substring(0, q);
        }
        String path = target.replace("/api/localsend/v1/", API);
        if (path.equals(API + "register") || path.equals(API + "info")) {
            if (length > 0 && length < 64 * 1024) body(in, (int) length);
            reply(out, 200, info().toString());
        } else if (path.equals(API + "prepare-upload")) {
            if (length <= 0 || length > 4 * 1024 * 1024) {
                reply(out, 400, "");
                return;
            }
            reply(out, prepare(new JSONObject(body(in, (int) length))), lastAnswer);
        } else if (path.equals(API + "upload")) {
            reply(out, upload(c, value(query, "sessionId"), value(query, "fileId"), value(query, "token"), length, in), "");
        } else if (path.equals(API + "cancel")) {
            synchronized (LocalSend.class) {
                session = null;
                files.clear();
            }
            say(L10n.t("Cancelled on the phone"));
            reply(out, 200, "");
        } else reply(out, 404, "");
    }

    private static String lastAnswer = "";

    /** A phone wants to send: which of its files we take (songs, videos, subtitles), each with a token. */
    private static synchronized int prepare(JSONObject o) throws Exception {
        if (session != null) {
            lastAnswer = "";
            return 409;   // another phone is sending
        }
        JSONObject list = o.optJSONObject("files"), tokens = new JSONObject();
        files.clear();
        if (list != null) {
            for (Iterator<String> it = list.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject f = list.optJSONObject(id);
                if (f == null) continue;
                String name = PhoneFiles.cleanName(f.optString("fileName"));
                long size = f.optLong("size");
                if (name == null || !PhoneFiles.isMedia(name) || size <= 0) continue;
                String token = UUID.randomUUID().toString();
                files.put(id, new String[]{token, name, String.valueOf(size)});
                tokens.put(id, token);
            }
        }
        if (files.isEmpty()) {
            lastAnswer = "";
            say(L10n.t("Only songs, videos and subtitles can be sent to the TV"));
            return 403;
        }
        session = UUID.randomUUID().toString();
        saved = 0;
        total = files.size();
        String from = o.optJSONObject("info") != null ? o.optJSONObject("info").optString("alias", "") : "";
        say(String.format(L10n.t("Receiving %1$d files from %2$s…"), total, from.isEmpty() ? L10n.t("a phone") : from));
        lastAnswer = new JSONObject().put("sessionId", session).put("files", tokens).toString();
        return 200;
    }

    private static int upload(Context c, String sid, String fileId, String token, long length, InputStream in) throws Exception {
        String[] f;
        synchronized (LocalSend.class) {
            if (session == null || !session.equals(sid)) return 403;
            f = files.get(fileId);
            if (f == null || !f[0].equals(token)) return 403;
        }
        long size = length > 0 ? length : Long.parseLong(f[2]);
        String rel = folderFor(c, f[1]);
        String answer = PhoneFiles.receive(c, rel, f[1], size, in);
        synchronized (LocalSend.class) {
            files.remove(fileId);
            if ("ok".equals(answer)) saved++;
            if (files.isEmpty()) session = null;
        }
        if ("ok".equals(answer)) say(String.format(L10n.t("Saved %1$s to %2$s  (%3$d of %4$d)"), f[1], rel, saved, total));
        else if ("nodrive".equals(answer)) say(L10n.t("Plug a drive into the TV first"));
        else if ("room".equals(answer)) say(L10n.t("Not enough room on the drive"));
        else say(L10n.t("Couldn't save ") + f[1]);
        return "ok".equals(answer) ? 200 : 500;
    }

    /** Songs to Music, everything else (videos, subtitles) to Movies; made on the drive when missing. */
    private static String folderFor(Context c, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean song = n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".aac") || n.endsWith(".ogg")
                || n.endsWith(".wav") || n.endsWith(".opus");
        String rel = song ? "Music" : "Movies";
        File drive = DriveStore.anyDrive(c);
        if (drive != null) //noinspection ResultOfMethodCallIgnored
            new File(drive, rel).mkdirs();
        return rel;
    }

    // ---- plain HTTP bits ----------------------------------------------------------------------------------

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) return null;
        }
        return ch < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static String body(InputStream in, int length) throws Exception {
        byte[] b = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(b, got, length - got);
            if (n < 0) break;
            got += n;
        }
        return new String(b, 0, got, StandardCharsets.UTF_8);
    }

    private static String value(String query, String name) throws Exception {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
        }
        return "";
    }

    private static void reply(OutputStream out, int code, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                : code == 409 ? "Conflict" : code == 400 ? "Bad Request" : "Internal Server Error";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
