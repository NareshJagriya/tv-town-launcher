package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The illustrated wallpaper shared by Home and Discover: scenes that change every few seconds with a
 * cross-fade and a small random colour shift, a slow drift, a dark overlay, and glass shapes that show the
 * matching region of a pre-blurred copy. Scenes are rendered once per process.
 */
final class GlassBackdrop implements GlassKit.Source {

    static final int[] SCENE_SECONDS = {6, 12, 30, 60};
    private static final long FADE_MS = 2500;
    private static final float DRIFT_S = 40;

    private static final Bitmap[] scenes = new Bitmap[Scenes.COUNT], glass = new Bitmap[Scenes.COUNT];
    private static boolean started;

    /** Renders all scenes and their 480 x 270 blurred copies once, on a background thread. */
    static synchronized void build() {
        if (started) return;
        started = true;
        new Thread(() -> {
            for (int i = 0; i < Scenes.COUNT; i++) {
                Bitmap s = Scenes.render(i);
                Bitmap g = WheelArt.glassCopy(s, 480);
                synchronized (scenes) {
                    scenes[i] = s;
                    glass[i] = g;
                }
            }
        }, "scenes").start();
    }

    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final List<Integer> order = new ArrayList<>();
    private int scene = -1, oldScene = -1;
    private long shown, fadeStart;
    private final long startMs = SystemClock.uptimeMillis();
    private final Paint scenePaint = new Paint(Paint.FILTER_BITMAP_FLAG), oldPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private BitmapShader shader, oldShader;
    private ColorMatrixColorFilter tint, oldTint;
    private final Matrix wall = new Matrix(), glassMatrix = new Matrix();
    private float k = 1, w, h;

    GlassBackdrop(Context ctx) {
        prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        build();
    }

    void setSize(int width, int height) {
        w = width;
        h = height;
        k = width / 1920f;
    }

    static int intervalSeconds(SharedPreferences prefs) {
        return 6;   // the setting was removed: six seconds suits every picture
    }

    /** Moves to the next scene when its time is up (the first as soon as one is ready). */
    void step(long now) {
        if (scene >= 0 && now - shown < intervalSeconds(prefs) * 1000L) return;
        int next = nextScene();
        if (next < 0) return;
        oldScene = scene;
        oldTint = tint;
        oldShader = shader;
        scene = next;
        tint = randomTint();
        shader = new BitmapShader(glass[next], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shown = now;
        fadeStart = oldScene < 0 ? 0 : now;
    }

    private int nextScene() {
        synchronized (scenes) {
            for (int tries = 0; tries < Scenes.COUNT * 2; tries++) {
                if (order.isEmpty()) {
                    for (int i = 0; i < Scenes.COUNT; i++) order.add(i);
                    Collections.shuffle(order, random);
                }
                int s = order.remove(0);
                if (s != scene && scenes[s] != null) return s;
            }
        }
        return -1;
    }

    /** A small random shift in hue, saturation and brightness, new each time a scene comes round. */
    private ColorMatrixColorFilter randomTint() {
        ColorMatrix m = hueMatrix((random.nextFloat() - 0.5f) * 28);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.9f + random.nextFloat() * 0.25f);
        m.postConcat(sat);
        float b = 0.93f + random.nextFloat() * 0.14f;
        ColorMatrix bright = new ColorMatrix();
        bright.setScale(b, b, b, 1);
        m.postConcat(bright);
        return new ColorMatrixColorFilter(m);
    }

    private static ColorMatrix hueMatrix(float degrees) {
        float r = (float) Math.toRadians(degrees), c = (float) Math.cos(r), s = (float) Math.sin(r);
        float lr = 0.213f, lg = 0.715f, lb = 0.072f;
        return new ColorMatrix(new float[]{
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0, 0,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0, 0,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0, 0,
                0, 0, 0, 1, 0});
    }

    private float fade(long now) {
        return oldScene < 0 ? 1 : Math.min(1f, (now - fadeStart) / (float) FADE_MS);
    }

    /** Wallpaper with drift (scale 1.06, out to 48 px left and 17 px down and back over 40 s), then the overlay. */
    void draw(Canvas c, long now) {
        c.drawColor(0xFF0A0C18);
        if (scene >= 0) {
            Bitmap b = scenes[scene];
            float secs = (now - startMs) / 1000f;
            float e = (1 - (float) Math.cos(2 * Math.PI * (secs % DRIFT_S) / DRIFT_S)) / 2f;
            float s = Math.max(w / b.getWidth(), h / b.getHeight()) * 1.06f;
            wall.setScale(s, s);
            wall.postTranslate((w - b.getWidth() * s) / 2f - 48 * k * e, (h - b.getHeight() * s) / 2f + 17 * k * e);
            float f = fade(now);
            if (f < 1 && oldScene >= 0) {
                oldPaint.setColorFilter(oldTint);
                c.drawBitmap(scenes[oldScene], wall, oldPaint);
            }
            scenePaint.setColorFilter(tint);
            scenePaint.setAlpha(Math.round(255 * f));
            c.drawBitmap(b, wall, scenePaint);
        }
        c.drawColor(0x470A0C18);   // rgba(10,12,24,0.28) for legibility
    }

    // ---- glass -----------------------------------------------------------------------------------

    @Override
    public void circle(Canvas c, float x, float y, float r, float alpha) {
        long now = SystemClock.uptimeMillis();
        float f = fade(now);
        if (f < 1 && use(true, alpha)) c.drawCircle(x, y, r, glassPaint);
        if (use(false, alpha * f)) c.drawCircle(x, y, r, glassPaint);
    }

    @Override
    public void round(Canvas c, RectF rect, float radius, float alpha) {
        long now = SystemClock.uptimeMillis();
        float f = fade(now);
        if (f < 1 && use(true, alpha)) c.drawRoundRect(rect, radius, radius, glassPaint);
        if (use(false, alpha * f)) c.drawRoundRect(rect, radius, radius, glassPaint);
    }

    /** Points the glass paint at the old (old = true) or current blurred scene; false when there is none. */
    private boolean use(boolean old, float alpha) {
        BitmapShader sh = old ? oldShader : shader;
        int idx = old ? oldScene : scene;
        if (sh == null || idx < 0) return false;
        Bitmap g = glass[idx];
        glassMatrix.setScale((float) Scenes.W / g.getWidth(), (float) Scenes.H / g.getHeight());
        glassMatrix.postConcat(wall);
        sh.setLocalMatrix(glassMatrix);
        glassPaint.setShader(sh);
        glassPaint.setColorFilter(old ? oldTint : tint);
        glassPaint.setAlpha(Math.round(255 * alpha));
        return true;
    }
}
