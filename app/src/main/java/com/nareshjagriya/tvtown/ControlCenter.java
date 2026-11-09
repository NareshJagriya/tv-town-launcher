package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Control Center: the TV's quick controls, most needed first (home screen, sound, inputs, free up the TV; then speed
 * test, sleep timer, what opens at power-on, sports), as tiles on a glass card over the blurred screen and on the
 * Customize page.
 */
final class ControlCenter extends View {

    // ---- the TV going to sleep; this outlives the card -----------------------------------------

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 60, 90, 120};
    private static final Handler timer = new Handler(Looper.getMainLooper());
    private static long sleepAt;
    private static int sleepChoice;

    private static void setSleep(Context ctx, int choice) {
        Context c = ctx.getApplicationContext();
        timer.removeCallbacksAndMessages(null);
        sleepChoice = choice;
        int m = SLEEP_MINUTES[choice];
        sleepAt = m == 0 ? 0 : System.currentTimeMillis() + m * 60_000L;
        if (m == 0) return;
        timer.postDelayed(() -> Toast.makeText(c, "The TV goes to sleep in 1 minute", Toast.LENGTH_LONG).show(), (m - 1) * 60_000L);
        timer.postDelayed(() -> {
            sleepAt = 0;
            sleepChoice = 0;
            turnOff(c);
        }, m * 60_000L);
    }

    /** Sleep, through the home helper; without it, the phone remote's way of pressing keys. */
    static void turnOff(Context c) {
        if (HomeKeyService.sleepNow()) return;
        if (RemoteKeys.send(c, "d 26") && RemoteKeys.send(c, "u 26")) return;
        Toast.makeText(c, "Turn on TV Town's home helper to use this", Toast.LENGTH_LONG).show();
    }

    // ---- the tiles: most needed first; the same on the pop-up card and on the Customize page ----------

    /** What the tiles open that lives on Home. */
    interface Actions {
        void sound();

        void inputs();

        void sports();

        /** Android's own screen where TV Town's home helper is switched on. */
        void setUpHome();

        /** The TV's own home instead of TV Town (TV Town comes back when opened from the app list). */
        void stockHome();
    }

    private static final int T_HOME = 0, T_SOUND = 1, T_INPUTS = 2, T_MEMORY = 3, T_SPEED = 4, T_SLEEP = 5, T_WAKE = 6, T_SPORTS = 7, TILES = 8, COLS = 4;
    private static final String[] TILE_NAME = {"Home screen", "Sound", "Inputs", "Speed up TV", "Speed test", "Sleep timer", "At power-on", "Sports"};
    private static final String[] TILE_ICON = {"home", "bt", "tv", "chip", "pulse", "moon", "power", "trophy"};

    /** What a tile's second line says now, and what OK on it does: kept here for both the card and the page. */
    private static final class Tiles {
        final Context ctx;
        final Actions act;
        final Runnable redraw;
        final Handler main = new Handler(Looper.getMainLooper());
        String speed = "Press OK", soundName = "TV speakers";
        boolean testing;
        long homeAsk;   // when OK was pressed once on Home screen (switching away asks for a second OK)

        Tiles(Context ctx, Actions act, Runnable redraw) {
            this.ctx = ctx;
            this.act = act;
            this.redraw = redraw;
            TvTools.audio(ctx, p -> {
                for (android.bluetooth.BluetoothDevice d : TvTools.speakers(ctx)) if (TvTools.connected(p, d)) soundName = TvTools.name(d);
                redraw.run();
            });
            if (Cricket.today().isEmpty()) Cricket.refreshNow(redraw);
        }

        String value(int t) {
            switch (t) {
                case T_HOME:
                    if (!HomeKeyService.enabled(ctx)) return "Set up TV Town";
                    return System.currentTimeMillis() - homeAsk < 4000 ? "OK again: TV's own" : "TV Town";
                case T_SOUND:
                    return soundName;
                case T_INPUTS:
                    return "HDMI, Live TV";
                case T_MEMORY:
                    return TvTools.freeMemoryMb(ctx) + " MB free";
                case T_SPEED:
                    return speed;
                case T_SLEEP:
                    return sleepAt == 0 ? "Off" : Math.max(1, Math.round((sleepAt - System.currentTimeMillis()) / 60_000f)) + " min left";
                case T_WAKE:
                    return HomeKeyService.wakeHome(ctx) ? "Open TV Town" : "Last app";
                default: {
                    int live = 0;
                    List<Cricket.Match> l = Cricket.today();
                    for (Cricket.Match m : l) if (m.live) live++;
                    return live > 0 ? live + " live now" : l.size() + " today";
                }
            }
        }

        /** True when the tile opened something else (the card closes). */
        boolean press(int t) {
            switch (t) {
                case T_HOME:
                    if (!HomeKeyService.enabled(ctx)) {
                        act.setUpHome();
                        return true;
                    }
                    if (System.currentTimeMillis() - homeAsk < 4000) {   // the second OK: really switch
                        homeAsk = 0;
                        act.stockHome();
                        return true;
                    }
                    homeAsk = System.currentTimeMillis();
                    main.postDelayed(redraw, 4100);
                    return false;
                case T_SOUND:
                    act.sound();
                    return true;
                case T_INPUTS:
                    act.inputs();
                    return true;
                case T_MEMORY:
                    ctx.startActivity(new android.content.Intent(ctx, CleanupActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                case T_SPEED:
                    if (testing) return false;
                    testing = true;
                    speed = "Testing…";
                    new Thread(() -> {
                        String out = speedTestNow(ctx);
                        main.post(() -> {
                            testing = false;
                            speed = out;
                            redraw.run();
                        });
                    }, "speed-test").start();
                    return false;
                case T_SLEEP:
                    setSleep(ctx, (sleepChoice + 1) % SLEEP_MINUTES.length);
                    return false;
                case T_WAKE:
                    HomeKeyService.setWakeHome(ctx, !HomeKeyService.wakeHome(ctx));
                    return false;
                default:
                    act.sports();
                    return true;
            }
        }

        /** Arrows over four by two; false when Left leaves the first column (the page's categories). */
        static int move(int sel, int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: return sel % COLS == 0 ? -1 : sel - 1;
                case KeyEvent.KEYCODE_DPAD_RIGHT: return sel % COLS < COLS - 1 ? sel + 1 : sel;
                case KeyEvent.KEYCODE_DPAD_UP: return sel >= COLS ? sel - COLS : sel;
                case KeyEvent.KEYCODE_DPAD_DOWN: return sel + COLS < TILES ? sel + COLS : sel;
                default: return sel;
            }
        }

        /** One tile: its line icon, name and value; lit when chosen. */
        static void draw(Canvas c, RectF r, int t, String value, boolean on, Paint name, Paint sub, Paint fill, Paint bits, float k) {
            if (on) Frost.plate(c, r, 28 * k, k);
            else {
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, 28 * k, 28 * k, fill);
            }
            Bitmap g = LineIcon.icon(TILE_ICON[t], 52 * k);
            float top = r.top + (r.height() - g.getHeight() - 84 * k) / 2;
            c.drawBitmap(g, r.centerX() - g.getWidth() / 2f, top, bits);
            name.setTextAlign(Paint.Align.CENTER);
            sub.setTextAlign(Paint.Align.CENTER);
            c.drawText(fitText(TILE_NAME[t], name, r.width() - 20 * k), r.centerX(), top + g.getHeight() + 44 * k, name);
            sub.setColor(on ? Color.WHITE : Frost.MUTE);
            c.drawText(fitText(value, sub, r.width() - 20 * k), r.centerX(), top + g.getHeight() + 80 * k, sub);
        }
    }

    private static String fitText(String s, Paint p, float room) {
        return TextUtils.ellipsize(s == null ? "" : s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
    }

    // ---- the pop-up card ----------------------------------------------------------------------------------

    private static final float L = 240, R = 1680, PAD = 40, GAP = 16, TILE_H = 190, T = 540 - (96 + 2 * 190 + 16 + 40) / 2f, B = 1080 - T;

    private static ControlCenter current;

    private final float k;
    private final Paint title, name, sub, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bits = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), blur = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Runnable closed;
    private final Tiles tiles;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int sel;
    private boolean blurReady, okDown;
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            invalidate();
            main.postDelayed(this, 1000);
        }
    };

    static void open(Activity a, Runnable closed, Actions actions) {
        if (current != null) current.close();
        ControlCenter v = new ControlCenter(a, closed, actions);
        Frost.capture(a, () -> {
            v.blurReady = true;
            v.invalidate();
        });
        a.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        current = v;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.requestFocus();
        v.post(() -> {
            if (current == v) v.requestFocus();
        });
    }

    static void closeIfOpen() {
        if (current != null) current.close();
    }

    static boolean isOpen() {
        return current != null;
    }

    private ControlCenter(Context ctx, Runnable closed, Actions actions) {
        super(ctx);
        this.closed = closed;
        k = ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
        NoirKit n = new NoirKit(ctx, k);
        title = n.inter(40, 700, 0, Color.WHITE);
        name = n.inter(27, 600, 0, Color.WHITE);
        sub = n.inter(23, 500, 0, Frost.MUTE);
        tiles = new Tiles(ctx, actions, this::invalidate);
        main.post(beat);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && current == this) requestFocus();
    }

    private void close() {
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        closed.run();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sel = Math.max(0, Tiles.move(sel, code) < 0 ? sel : Tiles.move(sel, code));
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) okDown = true;
                return true;
            default:
                return super.onKeyDown(code, e);
        }
        invalidate();
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            close();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (okDown) {   // not the OK that opened this card, being let go
                okDown = false;
                int t = sel;
                if (t == T_HOME || t == T_SOUND || t == T_INPUTS || t == T_MEMORY || t == T_SPORTS) {
                    // these open something else: the card goes first, unless Home screen only asks for a second OK
                    boolean asks = t == T_HOME && HomeKeyService.enabled(getContext()) && System.currentTimeMillis() - tiles.homeAsk >= 4000;
                    if (!asks) close();
                }
                tiles.press(t);
                invalidate();
            }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    /**
     * How fast the internet is here, like the speed-test sites measure it: four downloads at once (one alone can't fill
     * a fast line), timed after a two-second warm-up (connecting and TCP's slow start would count against it), for
     * about eight seconds in all. Then how the TV is connected, since a TV on 2.4 GHz Wi-Fi is often the limit.
     */
    static String speedTestNow(@androidx.annotation.Nullable Context c) {
        final int streams = 4;
        final long warm = 2000, total = 8000;
        final java.util.concurrent.atomic.AtomicLong counted = new java.util.concurrent.atomic.AtomicLong();
        final long start = System.currentTimeMillis();
        Thread[] t = new Thread[streams];
        for (int i = 0; i < streams; i++) {
            t[i] = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                // 50 MB at a time (the server refuses 100 MB or more), again and again until the time is up.
                while (System.currentTimeMillis() - start < total) {
                    HttpURLConnection h = null;
                    try {
                        h = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection();
                        h.setConnectTimeout(6000);
                        h.setReadTimeout(6000);
                        try (InputStream in = h.getInputStream()) {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                long now = System.currentTimeMillis() - start;
                                if (now >= total) break;
                                if (now >= warm) counted.addAndGet(n);
                            }
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        android.util.Log.d("TVTown", "speed test: " + e.getMessage());
                        break;
                    } finally {
                        if (h != null) h.disconnect();
                    }
                }
            }, "speed-" + i);
            t[i].start();
        }
        for (Thread x : t) {
            try {
                x.join(total + 7000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long got = counted.get();
        if (got <= 0) return "Couldn't test";
        String out = Math.round(got * 8 / 1000.0 / (total - warm)) + " Mbps";
        String how = c != null ? link(c) : "";
        return how.isEmpty() ? out : out + "  \u00B7  " + how;
    }

    /** "Cable", "2.4 GHz Wi-Fi" or "5 GHz Wi-Fi"; empty when unknown. */
    static String link(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Cable";
            if (!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "";
            int f = 0;
            if (android.os.Build.VERSION.SDK_INT >= 29 && nc.getTransportInfo() instanceof android.net.wifi.WifiInfo)
                f = ((android.net.wifi.WifiInfo) nc.getTransportInfo()).getFrequency();
            return f >= 5900 ? "6 GHz Wi-Fi" : f >= 4900 ? "5 GHz Wi-Fi" : f > 2000 ? "2.4 GHz Wi-Fi" : "Wi-Fi";
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        if (blurReady) Frost.blurBehind(c, getWidth(), getHeight(), blur);
        else c.drawColor(0xFF050506);
        r.set(L * k, T * k, R * k, B * k);
        Frost.panel(c, r, 44 * k, 1, k);
        float x0 = L + PAD, w = R - L - 2 * PAD;
        c.drawText("Control Center", x0 * k, (T + 70) * k, title);
        float tw = (w - (COLS - 1) * GAP) / COLS, ty = T + 96;
        for (int t = 0; t < TILES; t++) {
            float x = x0 + (t % COLS) * (tw + GAP), y = ty + (t / COLS) * (TILE_H + GAP);
            r.set(x * k, y * k, (x + tw) * k, (y + TILE_H) * k);
            Tiles.draw(c, r, t, tiles.value(t), sel == t, name, sub, fill, bits, k);
        }
    }

    /** The same tiles drawn right on the Customize page, four by two, so they show as soon as Control Center is chosen. */
    static final class Pane implements GlassSheet.Pane {
        private final float k;
        private final Paint name, sub, fill = new Paint(Paint.ANTI_ALIAS_FLAG), bits = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Tiles tiles;
        private final Runnable redraw;
        private int sel;

        Pane(Context ctx, Runnable redraw, Actions actions) {
            this.redraw = redraw;
            k = ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
            NoirKit n = new NoirKit(ctx, k);
            name = n.inter(26, 600, 0, Color.WHITE);
            sub = n.inter(22, 500, 0, Frost.MUTE);
            tiles = new Tiles(ctx, actions, redraw);
        }

        @Override
        public void draw(Canvas c, RectF area, float e, boolean focused) {
            float pad = 28 * k, gap = 16 * k, top = area.top + 36 * k;
            float tw = (area.width() - 2 * pad - (COLS - 1) * gap) / COLS, th = Math.min(230 * k, (area.bottom - top - pad - gap) / 2);
            for (int t = 0; t < TILES; t++) {
                float x = area.left + pad + (t % COLS) * (tw + gap), y = top + (t / COLS) * (th + gap);
                r.set(x, y, x + tw, y + th);
                Tiles.draw(c, r, t, tiles.value(t), focused && sel == t, name, sub, fill, bits, k);
            }
        }

        @Override
        public boolean key(int code) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                tiles.press(sel);
                redraw.run();
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN)
                return false;
            int to = Tiles.move(sel, code);
            if (to < 0) return false;   // Left from the first column: to the categories
            sel = to;
            redraw.run();
            return true;
        }
    }
}
