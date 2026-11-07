package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * Home's full-screen backdrop: the featured title's artwork with a slow zoom and pan (30 s cycle) and 800 ms
 * cross-fades, dark gradients from the left and the bottom, and a pre-blurred copy of the same artwork that
 * glass panels show through. Without artwork (no key, offline, All apps) it shows the illustrated scenes.
 */
final class ArtBackdrop implements GlassKit.Source {

    private final GlassBackdrop scenes;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float w, h;

    // Current and previous artwork, each with its blurred copy.
    private Bitmap art, blur, oldArt, oldBlur;
    private BitmapShader blurShader, oldBlurShader;
    private String artUrl;
    private long fadeStart;
    private final long start = SystemClock.uptimeMillis();
    private final Matrix matrix = new Matrix(), blurMatrix = new Matrix();
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG), oldPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint leftShade = new Paint(), bottomShade = new Paint();
    private int generation;

    ArtBackdrop(Context ctx) {
        scenes = new GlassBackdrop(ctx);
    }

    void setSize(int width, int height) {
        w = width;
        h = height;
        scenes.setSize(width, height);
        // Legibility: dark from the left (hero text) and from the bottom (rows).
        leftShade.setShader(new LinearGradient(0, 0, w * 0.62f, 0, new int[]{0xE60B0B0D, 0x990B0B0D, 0x000B0B0D},
                new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        bottomShade.setShader(new LinearGradient(0, h * 0.42f, 0, h, new int[]{0x000B0B0D, 0xB30B0B0D, 0xF20B0B0D},
                new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
    }

    /** Shows this artwork (null: the scenes). The image is fetched if needed and cross-faded in. */
    void show(@Nullable String url, Runnable invalidate) {
        if (url == null ? artUrl == null : url.equals(artUrl)) return;
        artUrl = url;
        final int gen = ++generation;
        if (url == null) {
            swap(null, null);
            return;
        }
        Bitmap b = ImageCache.get(url, () -> {
            if (gen == generation) show(url, invalidate, true);
        });
        if (b != null) show(url, invalidate, true);
    }

    private void show(String url, Runnable invalidate, boolean ready) {
        Bitmap b = ImageCache.get(url, null);
        if (b == null || !ready) return;
        final int gen = generation;
        new Thread(() -> {
            Bitmap blurred = WheelArt.glassCopy(b, 240);   // small and strongly blurred: cheap to draw scaled up
            main.post(() -> {
                if (gen != generation) return;
                swap(b, blurred);
                invalidate.run();
            });
        }, "art-blur").start();
    }

    private void swap(@Nullable Bitmap a, @Nullable Bitmap b) {
        oldArt = art;
        oldBlur = blur;
        oldBlurShader = blurShader;
        art = a;
        blur = b;
        blurShader = b != null ? new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        fadeStart = SystemClock.uptimeMillis();
    }

    /** Drops the artwork bitmaps (leaving Home); the scenes stay. */
    void release() {
        art = blur = oldArt = oldBlur = null;
        blurShader = oldBlurShader = null;
        artUrl = null;
        generation++;
    }

    private float fade(long now) {
        return Math.min(1f, (now - fadeStart) / (float) Theme.BACKDROP_FADE_MS);
    }

    /** Scale 1.0 to 1.08 and a small pan, eased back and forth over 30 s. */
    private void kenBurns(Bitmap b, Matrix m, long now) {
        float t = ((now - start) % Theme.KEN_BURNS_MS) / (float) Theme.KEN_BURNS_MS;
        float e = (1 - (float) Math.cos(t * 2 * Math.PI)) / 2;
        float s = Math.max(w / b.getWidth(), h / b.getHeight()) * (1.02f + 0.06f * e);
        m.setScale(s, s);
        m.postTranslate((w - b.getWidth() * s) / 2 + w * 0.02f * (e - 0.5f), (h - b.getHeight() * s) / 2 - h * 0.012f * e);
    }

    void step(long now) {
        scenes.step(now);
    }

    void draw(Canvas c, long now) {
        float f = fade(now);
        boolean sceneUnder = art == null || (f < 1 && oldArt == null);
        if (sceneUnder) scenes.draw(c, now);
        if (oldArt != null && f < 1) {
            kenBurns(oldArt, matrix, now);
            oldPaint.setAlpha(255);
            c.drawBitmap(oldArt, matrix, oldPaint);
        }
        if (art != null) {
            kenBurns(art, matrix, now);
            artPaint.setAlpha(Math.round(255 * (oldArt == null && !sceneUnder ? 1 : f)));
            c.drawBitmap(art, matrix, artPaint);
        }
        c.drawRect(0, 0, w, h, leftShade);
        c.drawRect(0, h * 0.42f, w, h, bottomShade);
    }

    // ---- glass -----------------------------------------------------------------------------------

    @Override
    public void round(Canvas c, RectF r, float radius, float a) {
        if (!glassPaint(SystemClock.uptimeMillis(), a)) {
            scenes.round(c, r, radius, a);
            return;
        }
        c.drawRoundRect(r, radius, radius, glass);
    }

    @Override
    public void circle(Canvas c, float x, float y, float r, float a) {
        if (!glassPaint(SystemClock.uptimeMillis(), a)) {
            scenes.circle(c, x, y, r, a);
            return;
        }
        c.drawCircle(x, y, r, glass);
    }

    /** Points the glass paint at the blurred artwork, moving with the zoom; false when there is none. */
    private boolean glassPaint(long now, float a) {
        BitmapShader sh = blurShader != null ? blurShader : oldBlurShader;
        Bitmap b = blurShader != null ? blur : oldBlur;
        Bitmap full = blurShader != null ? art : oldArt;
        if (sh == null || b == null || full == null) return false;
        kenBurns(full, matrix, now);
        blurMatrix.setScale((float) full.getWidth() / b.getWidth(), (float) full.getHeight() / b.getHeight());
        blurMatrix.postConcat(matrix);
        sh.setLocalMatrix(blurMatrix);
        glass.setShader(sh);
        glass.setAlpha(Math.round(255 * a));
        return true;
    }
}
