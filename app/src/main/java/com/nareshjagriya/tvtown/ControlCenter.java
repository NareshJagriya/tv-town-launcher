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
 * Control Center: what's playing and the things you reach for (sleep timer, speed test, sound, memory, cricket, turn off),
 * on one small glass card over the blurred screen.
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

    // ---- the card --------------------------------------------------------------------------------------------

    private static final int T_SLEEP = 0, T_SPEED = 1, T_SOUND = 2, T_MEMORY = 3, T_CRICKET = 4, T_OFF = 5, TILES = 6;
    private static final String[] TILE_NAME = {"Sleep timer", "Speed test", "Sound", "Free memory", "Sports", "Turn off"};
    private static final String[] TILE_ICON = {"moon", "pulse", "bt", "chip", "trophy", "power"};
    private static final float L = 240, R = 1680, T = 289, B = 791, PAD = 40, GAP = 14;

    private static ControlCenter current;

    private final float k;
    private final Paint title, name, sub, value, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bits = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), blur = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Runnable closed, sound, cricket;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int row = 1, col;
    private boolean blurReady, okDown, testing;
    private String speed = "Press OK", memory = "", soundName = "TV speakers";
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            invalidate();
            main.postDelayed(this, 1000);
        }
    };

    static void open(Activity a, Runnable closed, Runnable sound, Runnable cricket) {
        if (current != null) current.close();
        ControlCenter v = new ControlCenter(a, closed, sound, cricket);
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

    private ControlCenter(Context ctx, Runnable closed, Runnable sound, Runnable cricket) {
        super(ctx);
        this.closed = closed;
        this.sound = sound;
        this.cricket = cricket;
        k = ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
        NoirKit n = new NoirKit(ctx, k);
        title = n.inter(40, 700, 0, Color.WHITE);
        name = n.inter(30, 500, 0, Color.WHITE);
        sub = n.inter(24, 400, 0, Frost.MUTE);
        value = n.inter(24, 500, 0, Color.WHITE);
        memory = TvTools.freeMemoryMb(ctx) + " MB free";
        TvTools.audio(ctx, p -> {
            for (android.bluetooth.BluetoothDevice d : TvTools.speakers(ctx)) if (TvTools.connected(p, d)) soundName = TvTools.name(d);
            invalidate();
        });
        if (Cricket.today().isEmpty()) Cricket.refreshNow(this::postInvalidate);
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
                row = Math.max(0, row - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = Math.min(1, row + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row == 1) col = Math.max(0, col - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == 1) col = Math.min(TILES - 1, col + 1);
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
                press();
            }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void press() {
        Context c = getContext();
        if (row == 0) {
            MediaController m = TvTools.nowPlaying(c);
            if (m != null && !c.getPackageName().equals(m.getPackageName())) {
                if (TvTools.playing(m)) m.getTransportControls().pause();
                else m.getTransportControls().play();
            } else if (MusicPlayer.get(c).current() != null) MusicPlayer.get(c).toggle();
        } else switch (col) {
            case T_SLEEP:
                setSleep(c, (sleepChoice + 1) % SLEEP_MINUTES.length);
                break;
            case T_SPEED:
                speedTest();
                break;
            case T_SOUND:
                close();
                sound.run();
                return;
            case T_MEMORY:
                memory = "Clearing…";
                new Thread(() -> {
                    TvTools.freeUpMemory(c);
                    long free = TvTools.freeMemoryMb(c);
                    main.post(() -> {
                        memory = free + " MB free";
                        invalidate();
                    });
                }, "free-memory").start();
                break;
            case T_CRICKET:
                close();
                cricket.run();
                return;
            default:
                close();
                turnOff(c);
                return;
        }
        invalidate();
    }

    private void speedTest() {
        if (testing) return;
        testing = true;
        speed = "Testing…";
        new Thread(() -> {
            String out = "Couldn't test";
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection();
                h.setConnectTimeout(6000);
                h.setReadTimeout(8000);
                long at = System.currentTimeMillis(), got = 0;
                try (InputStream in = h.getInputStream()) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0 && System.currentTimeMillis() - at < 8000) got += n;
                }
                long ms = Math.max(1, System.currentTimeMillis() - at);
                if (got > 0) out = Math.round(got * 8 / 1000.0 / ms) + " Mbps";
            } catch (java.io.IOException | RuntimeException e) {
                android.util.Log.d("TVTown", "speed test: " + e.getMessage());
            } finally {
                if (h != null) h.disconnect();
            }
            String say = out;
            main.post(() -> {
                testing = false;
                speed = say;
                invalidate();
            });
        }, "speed-test").start();
    }

    private String tileValue(int t) {
        switch (t) {
            case T_SLEEP:
                if (sleepAt == 0) return "Off";
                return Math.max(1, Math.round((sleepAt - System.currentTimeMillis()) / 60_000f)) + " min left";
            case T_SPEED:
                return speed;
            case T_SOUND:
                return soundName;
            case T_MEMORY:
                return memory;
            case T_CRICKET: {
                List<Cricket.Match> l = Cricket.today();
                int live = 0;
                for (Cricket.Match m : l) if (m.live) live++;
                return live > 0 ? live + " live now" : l.size() + " today";
            }
            default:
                return "";
        }
    }

    private String fit(String s, Paint p, float room) {
        return TextUtils.ellipsize(s == null ? "" : s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (blurReady) Frost.blurBehind(c, getWidth(), getHeight(), blur);
        else c.drawColor(0xFF050506);
        r.set(L * k, T * k, R * k, B * k);
        Frost.panel(c, r, 44 * k, 1, k);
        float x0 = L + PAD, w = R - L - 2 * PAD;
        c.drawText("Control Center", x0 * k, (T + 70) * k, title);
        drawPlaying(c, x0, T + 100, w, 150);
        float tw = (w - (TILES - 1) * GAP) / TILES, ty = T + 266, th = 196;
        for (int t = 0; t < TILES; t++) {
            float x = x0 + t * (tw + GAP);
            r.set(x * k, ty * k, (x + tw) * k, (ty + th) * k);
            if (row == 1 && col == t) Frost.plate(c, r, 28 * k, k);
            else {
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, 28 * k, 28 * k, fill);
            }
            Bitmap g = LineIcon.icon(TILE_ICON[t], 56 * k);
            c.drawBitmap(g, r.centerX() - g.getWidth() / 2f, r.top + 28 * k, bits);
            value.setTextAlign(Paint.Align.CENTER);
            c.drawText(fit(TILE_NAME[t], value, r.width() - 16 * k), r.centerX(), r.top + 124 * k, value);
            sub.setTextAlign(Paint.Align.CENTER);
            sub.setColor(row == 1 && col == t ? Color.WHITE : Frost.MUTE);
            c.drawText(fit(tileValue(t), sub, r.width() - 16 * k), r.centerX(), r.top + 164 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            sub.setColor(Frost.MUTE);
            value.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawPlaying(Canvas c, float x, float y, float w, float h) {
        Context ctx = getContext();
        r.set(x * k, y * k, (x + w) * k, (y + h) * k);
        if (row == 0) Frost.plate(c, r, 28 * k, k);
        else {
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
        }
        MediaController m = TvTools.nowPlaying(ctx);
        if (m != null && ctx.getPackageName().equals(m.getPackageName())) m = null;
        MyMusic.Song song = MusicPlayer.get(ctx).current();
        String head, line = "";
        Bitmap art = null;
        boolean playing = false;
        if (m != null) {
            head = TvTools.title(m);
            line = TvTools.artist(m);
            if (line.isEmpty()) line = appLabel(m.getPackageName());
            art = TvTools.art(m);
            playing = TvTools.playing(m);
        } else if (song != null) {
            head = song.title;
            line = MyMusic.mainArtist(song.artist);
            art = MyMusic.cover(ctx, song, this::invalidate);
            playing = MusicPlayer.get(ctx).playing();
        } else head = "Nothing playing";
        float pic = (h - 28) * k, px = r.left + 14 * k, py = r.top + 14 * k;
        if (art != null) c.drawBitmap(art, null, new RectF(px, py, px + pic, py + pic), bits);
        else {
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(px, py, px + pic, py + pic, 16 * k, 16 * k, fill);
            Bitmap g = LineIcon.icon("music", 48 * k);
            c.drawBitmap(g, px + (pic - g.getWidth()) / 2, py + (pic - g.getHeight()) / 2, bits);
        }
        float tx = px + pic + 28 * k, room = r.right - tx - 120 * k;
        c.drawText(fit(head, name, room), tx, r.centerY() + (line.isEmpty() ? 11 : -4) * k, name);
        if (!line.isEmpty()) c.drawText(fit(line, sub, room), tx, r.centerY() + 34 * k, sub);
        if (m != null || song != null) {
            Bitmap g = LineIcon.icon(playing ? "pause" : "play", 52 * k);
            c.drawBitmap(g, r.right - 44 * k - g.getWidth(), r.centerY() - g.getHeight() / 2f, bits);
        }
    }
}
