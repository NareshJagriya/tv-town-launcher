package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Type on your phone: while one of TV Town's text boxes is open, a tiny web page is served on the home Wi-Fi and a QR
 * code points the phone's camera at it. What is typed on the phone appears on the TV as it is typed; Enter (or
 * "Send to TV") submits it. No app is needed on the phone.
 *
 * Safe by design: it listens only on the TV's Wi-Fi address, the address holds a random single-use key, it answers
 * only that key, takes at most 100 characters, closes after the text is sent, after 10 minutes, or when the box
 * closes, and never shows anything from the TV.
 */
final class PhoneKeyboard {

    private static final long LIFETIME_MS = 10 * 60_000;
    private static final int MAX_CHARS = 400;   // a TMDB read token is long

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> typing, sent;
    private final String key;
    private ServerSocket server;
    private volatile boolean open;
    private String url;

    /** typing: the text so far, as it is typed; sent: the text when sent. Both run on the main thread. */
    PhoneKeyboard(Consumer<String> typing, Consumer<String> sent) {
        this.typing = typing;
        this.sent = sent;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        key = s.toString();
    }

    /** Starts serving; the page's address, or null when the TV has no Wi-Fi or wired address. */
    @Nullable
    String start() {
        InetAddress ip = localAddress();
        if (ip == null) return null;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(ip, 0), 4);
        } catch (Exception e) {
            return null;
        }
        open = true;
        url = "http://" + ip.getHostAddress() + ":" + server.getLocalPort() + "/" + key;
        Thread t = new Thread(this::serve, "phone-keyboard");
        t.setDaemon(true);
        t.start();
        main.postDelayed(this::stop, LIFETIME_MS);
        return url;
    }

    void stop() {
        open = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // already closed
        }
    }

    /** The QR code for the page, black on white with a quiet border, `size` pixels square; null if not started. */
    @Nullable
    Bitmap qr(int size) {
        return url == null ? null : qrFor(url, size);
    }

    /** A QR code for any text (a web address), black on white with a quiet border, `size` pixels square. */
    @Nullable
    static Bitmap qrFor(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet), not mobile or loopback. */
    @Nullable
    static InetAddress localAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }

    private void serve() {
        while (open) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                handle(s);
            } catch (Exception e) {
                if (!open) return;
            }
        }
    }

    /** GET /key: the page. POST /key/t (typing) or /key/s (sent): the text. Anything else: 404. */
    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        String[] parts = request.split(" ");
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(h.substring(15).trim());
                } catch (NumberFormatException ignored) {
                    // no body
                }
            }
        }
        String method = parts.length > 1 ? parts[0] : "", path = parts.length > 1 ? parts[1] : "";
        OutputStream out = s.getOutputStream();
        if (method.equals("GET") && path.equals("/" + key)) {
            reply(out, "200 OK", "text/html; charset=utf-8", page());
            return;
        }
        boolean typed = path.equals("/" + key + "/t"), send = path.equals("/" + key + "/s");
        if (method.equals("POST") && (typed || send) && length >= 0 && length <= 1000) {
            char[] body = new char[length];
            int n = 0;
            while (n < length) {
                int r = in.read(body, n, length - n);
                if (r < 0) break;
                n += r;
            }
            String text = URLDecoder.decode(new String(body, 0, n).replaceFirst("^t=", ""), "UTF-8")
                    .replaceAll("[\\p{Cntrl}]", " ");
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
            final String t = text;
            reply(out, "200 OK", "text/plain", "ok");
            if (send) {
                main.post(() -> sent.accept(t));
                main.post(this::stop);
            } else {
                main.post(() -> typing.accept(t));
            }
            return;
        }
        reply(out, "404 Not Found", "text/plain", "");
    }

    private static void reply(OutputStream out, String status, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /** The phone's page: a big text box and a Send button; what is typed reaches the TV as it is typed. */
    /** Steps shown on the phone above the box (simple HTML), for the next page served; null for none. */
    static volatile String note;

    private static String page() {
        String n = note;
        return n == null ? PAGE : PAGE.replace("<h1>Type on the TV</h1>", "<h1>Type on the TV</h1><div class=n>" + n + "</div>")
                .replace("p{color:#aab;margin:0}", "p{color:#aab;margin:0}.n{color:#cfd6f0;line-height:1.5}.n a{color:#59d8ff}.n li{margin:6px 0}");
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Type on the TV</title><style>"
            + "body{margin:0;font-family:system-ui,sans-serif;background:#0b0e1a;color:#fff;display:flex;flex-direction:column;"
            + "padding:24px;gap:16px}h1{font-size:20px;font-weight:600;margin:0}"
            + "input{font-size:22px;padding:16px;border-radius:14px;border:2px solid #6a4be0;background:#161a2e;color:#fff}"
            + "button{font-size:20px;padding:16px;border-radius:14px;border:0;background:#fff;color:#0b0e1a;font-weight:700}"
            + "p{color:#aab;margin:0}</style></head><body><h1>Type on the TV</h1>"
            + "<input id=t maxlength=400 autofocus autocomplete=off placeholder='Type here'>"
            + "<button id=b>Send to TV</button><p id=m>It shows on the TV as you type.</p><script>"
            + "var k=location.pathname,t=document.getElementById('t'),w=0;"
            + "function post(p,v){return fetch(k+p,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
            + "body:'t='+encodeURIComponent(v)})}"
            + "t.oninput=function(){clearTimeout(w);w=setTimeout(function(){post('/t',t.value)},250)};"
            + "function send(){post('/s',t.value).then(function(){document.getElementById('m').textContent='Sent. You can close this page.';"
            + "t.disabled=true;document.getElementById('b').disabled=true})}"
            + "document.getElementById('b').onclick=send;t.onkeydown=function(e){if(e.key==='Enter')send()};"
            + "</script></body></html>";
}
