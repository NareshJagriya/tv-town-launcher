package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The picture behind a screen: a title's art over the whole screen, dissolving into the next, with its blurred copy
 * for glass and its colour for what's selected. The same stage on every page, so they all feel like Home.
 */
final class ArtStage {

    private static final long FADE_MS = 450;

    private final View host;
    private final Paint bits = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF screen = new RectF();
    @Nullable
    private Bitmap art, old, frost, oldFrost;
    @Nullable
    private String want, ready, baking;
    private long fadeAt;
    @Nullable
    private Bitmap wantBitmap;

    ArtStage(View host) {
        this.host = host;
    }

    void show(@Nullable String url) {
        want = url;
        wantBitmap = null;
        host.invalidate();
    }

    /** A picture already in memory (a song's cover), known by `key`. */
    void show(String key, Bitmap picture) {
        if (key.equals(want) && wantBitmap == picture) return;
        want = key;
        wantBitmap = picture;
        host.invalidate();
    }

    @Nullable
    Bitmap frost() {
        return frost;
    }

    RectF screen() {
        screen.set(0, 0, host.getWidth(), host.getHeight());
        return screen;
    }

    Paint bits() {
        return bits;
    }

    boolean moving(long now) {
        return old != null && now - fadeAt < FADE_MS;
    }

    void draw(Canvas c, long now) {
        bake();
        c.drawColor(0xFF050506);
        RectF s = screen();
        if (old != null && now - fadeAt < FADE_MS) c.drawBitmap(old, null, s, bits);
        else old = null;
        if (art != null) {
            bits.setAlpha(old != null ? Math.round(255f * (now - fadeAt) / FADE_MS) : 255);
            c.drawBitmap(art, null, s, bits);
            bits.setAlpha(255);
        }
    }

    private final android.graphics.Path clip = new android.graphics.Path();

    /**
     * For pages with a lot to read: the blurred picture behind everything, kept dark enough for white words on any
     * film, and the sharp picture in its own window.
     */
    void drawSoft(Canvas c, long now, RectF window, float rad) {
        bake();
        c.drawColor(0xFF050506);
        RectF s = screen();
        boolean fading = old != null && now - fadeAt < FADE_MS;
        if (!fading) old = null;
        int a = fading ? Math.round(255f * (now - fadeAt) / FADE_MS) : 255;
        if (fading && oldFrost != null) c.drawBitmap(oldFrost, null, s, bits);
        if (frost != null) {
            bits.setAlpha(a);
            c.drawBitmap(frost, null, s, bits);
            bits.setAlpha(255);
        }
        c.drawColor(0x8C060607);
        c.save();
        clip.reset();
        clip.addRoundRect(window, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        if (fading) c.drawBitmap(old, null, window, bits);
        if (art != null) {
            bits.setAlpha(a);
            c.drawBitmap(art, null, window, bits);
            bits.setAlpha(255);
        }
        c.restore();
    }

    /** Fetches and prepares the wanted picture off the main thread. */
    private void bake() {
        String key = want;
        if (key == null || key.equals(ready) || key.equals(baking)) return;
        Bitmap got = wantBitmap != null ? wantBitmap : ImageCache.get(key, host::postInvalidate, null, 1280);
        if (got == null) return;
        baking = key;
        new Thread(() -> {
            Bitmap full = null, blurred = null;
            try {
                full = cover(got);
                blurred = WheelArt.glassCopy(full, 320, 1f);
            } catch (RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("TVTown", "stage", e);
            }
            final Bitmap f = full, b = blurred;
            final int accent = b != null ? Frost.accent(b) : Frost.accentNow;
            host.post(() -> {
                baking = null;
                if (f != null && b != null) {
                    old = art;
                    oldFrost = frost;
                    fadeAt = SystemClock.uptimeMillis();
                    art = f;
                    frost = b;
                    GlassSheet.frost = b;
                    Frost.accentNow = accent;
                    ready = key;
                }
                host.invalidate();
            });
        }, "stage").start();
    }

    /** The picture cropped to the screen's shape at 1280 x 720: a poster fills it without stretching. */
    private static Bitmap cover(Bitmap b) {
        Bitmap out = Bitmap.createBitmap(1280, 720, Bitmap.Config.RGB_565);
        float want = 16 / 9f, have = b.getWidth() / (float) b.getHeight();
        Rect from;
        if (have > want) {
            int w = Math.round(b.getHeight() * want), x = (b.getWidth() - w) / 2;
            from = new Rect(x, 0, x + w, b.getHeight());
        } else {
            int h = Math.round(b.getWidth() / want), y = Math.round((b.getHeight() - h) * 0.2f);
            from = new Rect(0, y, b.getWidth(), y + h);
        }
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(out).drawBitmap(b, from, new Rect(0, 0, 1280, 720), p);
        return out;
    }
}
