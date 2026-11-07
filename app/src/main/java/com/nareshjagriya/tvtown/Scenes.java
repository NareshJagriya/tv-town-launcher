package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Ten illustrated wallpapers drawn in code: flat layered silhouettes over soft sky gradients, with a few
 * details each (sun or moon glow, reflections, aurora, stars, birds). Drawn at a fixed design size; the
 * caller scales them to the screen.
 */
final class Scenes {

    static final int COUNT = 10;
    static final int W = 960, H = 540;

    private Scenes() {
    }

    /** Renders scene i into a new RGB_565 bitmap (dithered, so the gradients do not band). */
    static Bitmap render(int i) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        Random r = new Random(1000 + i * 7919L);
        switch (i) {
            case 0: dusk(c, r); break;
            case 1: glacier(c, r); break;
            case 2: aurora(c, r); break;
            case 3: dunes(c, r); break;
            case 4: ocean(c, r); break;
            case 5: forest(c, r); break;
            case 6: starryNight(c, r); break;
            case 7: pinkLake(c, r); break;
            case 8: autumn(c, r); break;
            default: city(c, r); break;
        }
        return b;
    }

    // ---- scenes ----------------------------------------------------------------------------------

    private static void dusk(Canvas c, Random r) {
        sky(c, new int[]{0xFF2B2459, 0xFF7B3F6E, 0xFFE07A5F, 0xFFF4B183}, new float[]{0, 0.45f, 0.78f, 1}, H * 0.75f);
        glow(c, W * 0.62f, H * 0.6f, 220, 0xFFFFD39A, 0.75f);
        disc(c, W * 0.62f, H * 0.6f, 34, 0xFFFFE6B8);
        birds(c, r, W * 0.3f, H * 0.3f, 5, 0xCC3B2440);
        ridge(c, r, H * 0.58f, 34, 0xFF8E4F72, 3);
        ridge(c, r, H * 0.66f, 40, 0xFF6A3A62, 3);
        ridge(c, r, H * 0.75f, 46, 0xFF482A50, 2);
        ridge(c, r, H * 0.86f, 40, 0xFF2A1B38, 2);
    }

    private static void glacier(Canvas c, Random r) {
        sky(c, new int[]{0xFF7FB3D9, 0xFFBFDCEC, 0xFFEAF4F7}, new float[]{0, 0.6f, 1}, H * 0.55f);
        glow(c, W * 0.25f, H * 0.18f, 160, 0xFFFFFFFF, 0.5f);
        mountains(c, r, H * 0.5f, 150, 0xFF8FA9C4, 0xFFF4F8FB, 7);
        mountains(c, r, H * 0.56f, 110, 0xFF6E8FAE, 0xFFE3EEF5, 9);
        water(c, H * 0.58f, 0xFF4FA3B5, 0xFF2D6E86);
        reflectRidge(c, r, H * 0.58f, 60, 0x406E8FAE);
        shimmer(c, r, H * 0.6f, H, 0x33FFFFFF, 40);
        ridge(c, r, H * 0.9f, 22, 0xFF2E4B5E, 2);
    }

    private static void aurora(Canvas c, Random r) {
        sky(c, new int[]{0xFF050B1E, 0xFF0B1F3A, 0xFF12385A}, new float[]{0, 0.6f, 1}, H * 0.8f);
        stars(c, r, 220, H * 0.7f, 0.9f);
        ribbon(c, r, H * 0.42f, 120, 0xFF3DF5A6, 0.75f);
        ribbon(c, r, H * 0.34f, 90, 0xFF5BD8FF, 0.45f);
        ribbon(c, r, H * 0.5f, 70, 0xFFB57BFF, 0.3f);
        ridge(c, r, H * 0.7f, 60, 0xFF0D1C2C, 4);
        pines(c, r, H * 0.86f, 34, 70, 0xFF050C14);
        ground(c, H * 0.9f, 0xFFB9CADB, 0xFF7C93AB);
    }

    private static void dunes(Canvas c, Random r) {
        sky(c, new int[]{0xFF3C2C6B, 0xFFC0567B, 0xFFF39A63, 0xFFFFD08A}, new float[]{0, 0.4f, 0.75f, 1}, H * 0.7f);
        glow(c, W * 0.35f, H * 0.58f, 240, 0xFFFFC27A, 0.8f);
        disc(c, W * 0.35f, H * 0.58f, 46, 0xFFFFE2A8);
        dune(c, r, H * 0.6f, 50, 0xFFD9824E, 0xFFB45E3A);
        dune(c, r, H * 0.7f, 60, 0xFFC0643C, 0xFF8E4429);
        dune(c, r, H * 0.82f, 70, 0xFF8C3F2A, 0xFF5E2A20);
        birds(c, r, W * 0.68f, H * 0.28f, 3, 0xAA4A2A40);
    }

    private static void ocean(Canvas c, Random r) {
        sky(c, new int[]{0xFF07122E, 0xFF15305A, 0xFF2D5684}, new float[]{0, 0.65f, 1}, H * 0.6f);
        stars(c, r, 120, H * 0.5f, 0.7f);
        glow(c, W * 0.7f, H * 0.28f, 170, 0xFFCFE3FF, 0.55f);
        disc(c, W * 0.7f, H * 0.28f, 30, 0xFFF2F6FF);
        water(c, H * 0.6f, 0xFF1E3E6B, 0xFF0A1A33);
        moonPath(c, r, W * 0.7f, H * 0.6f, 0x99DDE8FF);
        shimmer(c, r, H * 0.62f, H, 0x22FFFFFF, 60);
    }

    private static void forest(Canvas c, Random r) {
        sky(c, new int[]{0xFFB9C9C4, 0xFFDDE6E1, 0xFFEEF2EF}, new float[]{0, 0.6f, 1}, H);
        glow(c, W * 0.55f, H * 0.3f, 200, 0xFFFFFFFF, 0.45f);
        int[] shades = {0xFF8FA69E, 0xFF6C877F, 0xFF4E6A62, 0xFF2F4741, 0xFF19292A};
        for (int l = 0; l < shades.length; l++) {
            pines(c, r, H * (0.52f + l * 0.1f), 26 + l * 8, 46 + l * 18, shades[l]);
            if (l < shades.length - 1) mist(c, H * (0.5f + l * 0.1f), 60, 0x55EEF2EF);
        }
        Paint floor = paint();
        floor.setColor(shades[shades.length - 1]);
        c.drawRect(0, H * 0.92f - 2, W, H, floor);
    }

    private static void starryNight(Canvas c, Random r) {
        sky(c, new int[]{0xFF02030C, 0xFF0B1030, 0xFF1F2456}, new float[]{0, 0.6f, 1}, H * 0.85f);
        stars(c, r, 380, H * 0.8f, 1f);
        glow(c, W * 0.78f, H * 0.22f, 120, 0xFFE8EDFF, 0.4f);
        crescent(c, W * 0.78f, H * 0.22f, 28, 0xFFF3F1E2);
        ridge(c, r, H * 0.78f, 50, 0xFF0E1230, 3);
        ridge(c, r, H * 0.88f, 34, 0xFF05070F, 2);
    }

    private static void pinkLake(Canvas c, Random r) {
        sky(c, new int[]{0xFF4A3F7A, 0xFFB07AAE, 0xFFF0A9B6, 0xFFF9D2C4}, new float[]{0, 0.4f, 0.8f, 1}, H * 0.6f);
        stars(c, r, 40, H * 0.3f, 0.5f);
        glow(c, W * 0.5f, H * 0.58f, 260, 0xFFFFD1D6, 0.5f);
        ridge(c, r, H * 0.5f, 40, 0xFF8C6A9E, 3);
        ridge(c, r, H * 0.57f, 30, 0xFF6A4E82, 2);
        water(c, H * 0.6f, 0xFFD48EA9, 0xFF6E4F82);
        reflectRidge(c, r, H * 0.6f, 40, 0x406A4E82);
        shimmer(c, r, H * 0.62f, H, 0x30FFFFFF, 50);
        birds(c, r, W * 0.6f, H * 0.32f, 4, 0x994A3060);
    }

    private static void autumn(Canvas c, Random r) {
        sky(c, new int[]{0xFF6E8FBF, 0xFFE9B98A, 0xFFF6D6A5}, new float[]{0, 0.7f, 1}, H * 0.6f);
        glow(c, W * 0.8f, H * 0.32f, 180, 0xFFFFE1A6, 0.55f);
        ridge(c, r, H * 0.48f, 50, 0xFFB5876E, 3);
        ridge(c, r, H * 0.58f, 50, 0xFFC9683A, 3);
        trees(c, r, H * 0.64f, 14, 0xFFD9822B, 0xFFB5452A);
        ridge(c, r, H * 0.7f, 40, 0xFF9C3E22, 2);
        river(c, 0xFF7FA6C9);
        trees(c, r, H * 0.86f, 22, 0xFF8A2E1A, 0xFF5E2316);
        birds(c, r, W * 0.4f, H * 0.22f, 4, 0x995A3A30);
    }

    private static void city(Canvas c, Random r) {
        sky(c, new int[]{0xFF070A1F, 0xFF1C1B4A, 0xFF4B2E6E}, new float[]{0, 0.65f, 1}, H * 0.7f);
        stars(c, r, 90, H * 0.45f, 0.6f);
        glow(c, W * 0.2f, H * 0.2f, 120, 0xFFE8E4FF, 0.35f);
        disc(c, W * 0.2f, H * 0.2f, 20, 0xFFF4F0FF);
        buildings(c, r, H * 0.62f, 0xFF231F44, 0.25f);
        buildings(c, r, H * 0.7f, 0xFF120F28, 0.6f);
        water(c, H * 0.74f, 0xFF1B1840, 0xFF07061A);
        shimmer(c, r, H * 0.75f, H, 0x40FFC870, 70);
    }

    // ---- elements --------------------------------------------------------------------------------

    private static Paint paint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);
        return p;
    }

    private static void sky(Canvas c, int[] colors, float[] stops, float bottom) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, 0, 0, bottom, colors, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
    }

    private static void glow(Canvas c, float x, float y, float r, int color, float strength) {
        Paint p = paint();
        int a = Math.round(255 * strength);
        p.setShader(new RadialGradient(x, y, r, new int[]{(a << 24) | (color & 0xFFFFFF),
                ((a / 3) << 24) | (color & 0xFFFFFF), color & 0xFFFFFF}, new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }

    private static void disc(Canvas c, float x, float y, float r, int color) {
        Paint p = paint();
        p.setColor(color);
        c.drawCircle(x, y, r, p);
    }

    private static void crescent(Canvas c, float x, float y, float r, int color) {
        Path moon = new Path(), bite = new Path();
        moon.addCircle(x, y, r, Path.Direction.CW);
        bite.addCircle(x + r * 0.45f, y - r * 0.25f, r * 0.9f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        Paint p = paint();
        p.setColor(color);
        c.drawPath(moon, p);
    }

    private static void stars(Canvas c, Random r, int n, float maxY, float bright) {
        Paint p = paint();
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * W, y = r.nextFloat() * r.nextFloat() * maxY;   // denser near the top
            float s = r.nextFloat();
            p.setColor(Color.argb(Math.round(255 * bright * (0.3f + 0.7f * s)), 255, 255, 255));
            c.drawCircle(x, y, s > 0.95f ? 1.6f : s > 0.7f ? 1.1f : 0.7f, p);
        }
    }

    private static void birds(Canvas c, Random r, float x, float y, int n, int color) {
        Paint p = paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        for (int i = 0; i < n; i++) {
            float bx = x + (r.nextFloat() - 0.5f) * 120, by = y + (r.nextFloat() - 0.5f) * 50, s = 5 + r.nextFloat() * 5;
            Path b = new Path();
            b.moveTo(bx - s, by - s * 0.3f);
            b.quadTo(bx - s * 0.4f, by - s * 0.5f, bx, by);
            b.quadTo(bx + s * 0.4f, by - s * 0.5f, bx + s, by - s * 0.3f);
            c.drawPath(b, p);
        }
    }

    /** A rolling skyline from a few random sine waves, filled to the bottom. */
    private static void ridge(Canvas c, Random r, float base, float amp, int color, int waves) {
        Path p = new Path();
        float[] f = new float[waves], ph = new float[waves], a = new float[waves];
        for (int i = 0; i < waves; i++) {
            f[i] = (1 + r.nextFloat() * 3) * (i + 1);
            ph[i] = r.nextFloat() * 6.28f;
            a[i] = amp / (i + 1) * (0.6f + 0.6f * r.nextFloat());
        }
        p.moveTo(0, H);
        for (int x = 0; x <= W; x += 6) {
            float y = base;
            for (int i = 0; i < waves; i++) y -= a[i] * (float) Math.sin(x / (float) W * f[i] * Math.PI + ph[i]);
            p.lineTo(x, y);
        }
        p.lineTo(W, H);
        p.close();
        Paint pt = paint();
        pt.setColor(color);
        c.drawPath(p, pt);
    }

    /** A row of peaks, each a shaded triangle with a jagged snow cap down to about a third of its height. */
    private static void mountains(Canvas c, Random r, float base, float height, int rock, int snow, int peaks) {
        float step = W / (float) (peaks - 1);
        Paint rockPaint = paint(), shade = paint(), snowPaint = paint();
        rockPaint.setShader(new LinearGradient(0, base - height, 0, base, rock, darker(rock, 0.78f), Shader.TileMode.CLAMP));
        shade.setColor(0x26000000);
        snowPaint.setColor(snow);
        for (int i = 0; i < peaks; i++) {
            float x = step * i + (r.nextFloat() - 0.5f) * step * 0.5f;
            float top = base - height * (0.5f + 0.5f * r.nextFloat());
            float half = step * (0.7f + 0.4f * r.nextFloat());
            Path m = new Path();
            m.moveTo(x - half, base + 2);
            m.lineTo(x, top);
            m.lineTo(x + half, base + 2);
            m.close();
            c.drawPath(m, rockPaint);
            Path dark = new Path();   // the side away from the light
            dark.moveTo(x, top);
            dark.lineTo(x + half, base + 2);
            dark.lineTo(x + half * 0.15f, base + 2);
            dark.close();
            c.drawPath(dark, shade);
            float f = 0.3f + 0.12f * r.nextFloat();   // how far down the cap reaches
            float ly = top + (base - top) * f, lx = x - half * f, rx = x + half * f;
            Path cap = new Path();
            cap.moveTo(x, top);
            cap.lineTo(rx, ly);
            int teeth = 4;
            for (int t = 1; t < teeth; t++) {
                float tx = rx - (rx - lx) * t / teeth;
                float ty = ly + (t % 2 == 1 ? 1 : -1) * (base - top) * 0.06f * (0.5f + r.nextFloat());
                cap.lineTo(tx, ty);
            }
            cap.lineTo(lx, ly);
            cap.close();
            c.drawPath(cap, snowPaint);
        }
    }

    private static void water(Canvas c, float top, int near, int far) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, top, 0, H, near, far, Shader.TileMode.CLAMP));
        c.drawRect(0, top, W, H, p);
    }

    /** A faint upside-down copy of a ridge line on the water, kept below the shoreline. */
    private static void reflectRidge(Canvas c, Random r, float line, float amp, int color) {
        c.save();
        c.clipRect(0, line, W, H);
        c.scale(1, -0.5f, 0, line);
        ridge(c, r, line - 1, amp, color, 3);
        c.restore();
    }

    private static void shimmer(Canvas c, Random r, float top, float bottom, int color, int n) {
        Paint p = paint();
        p.setColor(color);
        p.setStrokeWidth(1.2f);
        for (int i = 0; i < n; i++) {
            float y = top + (bottom - top) * r.nextFloat() * r.nextFloat();
            float x = r.nextFloat() * W, len = 10 + r.nextFloat() * 60 * (1 + (y - top) / (bottom - top));
            c.drawLine(x, y, x + len, y, p);
        }
    }

    /** The moon's glitter path on the water: short horizontal strokes, wider further down. */
    private static void moonPath(Canvas c, Random r, float x, float top, int color) {
        Paint p = paint();
        p.setColor(color);
        p.setStrokeWidth(1.5f);
        for (int i = 0; i < 70; i++) {
            float t = r.nextFloat();
            float y = top + 4 + t * (H - top);
            float spread = 10 + t * 70, cx = x + (r.nextFloat() - 0.5f) * spread;
            float len = 6 + r.nextFloat() * 24 * (0.4f + t);
            c.drawLine(cx - len / 2, y, cx + len / 2, y, p);
        }
    }

    /** Aurora: a curtain fading upwards from a wavy lower edge, softened with a blur. */
    private static void ribbon(Canvas c, Random r, float base, float height, int color, float strength) {
        Path lower = new Path(), shape = new Path();
        float ph = r.nextFloat() * 6.28f, ph2 = r.nextFloat() * 6.28f;
        shape.moveTo(-20, base);
        for (int x = -20; x <= W + 20; x += 8) {
            float y = base + 30 * (float) Math.sin(x / 140f + ph) + 14 * (float) Math.sin(x / 47f + ph2);
            shape.lineTo(x, y);
        }
        for (int x = W + 20; x >= -20; x -= 8) {
            float y = base - height * (0.6f + 0.4f * (float) Math.sin(x / 90f + ph2))
                    + 30 * (float) Math.sin(x / 140f + ph);
            shape.lineTo(x, y);
        }
        shape.close();
        Paint p = paint();
        int a = Math.round(255 * strength);
        p.setShader(new LinearGradient(0, base + 30, 0, base - height, new int[]{(a << 24) | (color & 0xFFFFFF),
                ((a / 3) << 24) | (color & 0xFFFFFF), color & 0xFFFFFF}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(14, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(shape, p);
    }

    private static void pines(Canvas c, Random r, float base, float minH, float maxH, int color) {
        Paint p = paint();
        p.setColor(color);
        float x = -10;
        while (x < W + 10) {
            float h = minH + r.nextFloat() * (maxH - minH), w = h * 0.36f;
            Path t = new Path();
            for (int tier = 0; tier < 3; tier++) {
                float top = base - h + tier * h * 0.28f, tw = w * (0.55f + tier * 0.25f);
                t.moveTo(x, top);
                t.lineTo(x - tw / 2, top + h * 0.42f);
                t.lineTo(x + tw / 2, top + h * 0.42f);
                t.close();
            }
            t.addRect(x - w * 0.06f, base - h * 0.2f, x + w * 0.06f, base + 1, Path.Direction.CW);
            c.drawPath(t, p);
            x += w * (0.35f + r.nextFloat() * 0.55f);
        }
    }

    private static void mist(Canvas c, float y, float h, int color) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, y - h, 0, y + h, new int[]{color & 0xFFFFFF, color, color & 0xFFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, y - h, W, y + h, p);
    }

    private static void ground(Canvas c, float top, int light, int dark) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, top, 0, H, light, dark, Shader.TileMode.CLAMP));
        Path g = new Path();
        g.moveTo(0, top + 8);
        g.quadTo(W * 0.4f, top - 10, W, top + 6);
        g.lineTo(W, H);
        g.lineTo(0, H);
        g.close();
        c.drawPath(g, p);
    }

    private static void dune(Canvas c, Random r, float base, float amp, int light, int dark) {
        Path p = new Path();
        float ph = r.nextFloat() * 6.28f, f = 1.2f + r.nextFloat();
        p.moveTo(0, H);
        for (int x = 0; x <= W; x += 6) {
            float y = base - amp * (float) Math.sin(x / (float) W * f * Math.PI + ph) * (0.7f + 0.3f * (float) Math.sin(x / 70f));
            p.lineTo(x, y);
        }
        p.lineTo(W, H);
        p.close();
        Paint pt = paint();
        pt.setShader(new LinearGradient(0, 0, W, 0, light, dark, Shader.TileMode.CLAMP));
        c.drawPath(p, pt);
    }

    /** Round-crowned trees along a line. */
    private static void trees(Canvas c, Random r, float base, int n, int a, int b) {
        Paint p = paint();
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * W, s = 10 + r.nextFloat() * 16;
            p.setColor(blend(a, b, r.nextFloat()));
            c.drawCircle(x, base - s, s, p);
            c.drawCircle(x - s * 0.6f, base - s * 0.6f, s * 0.75f, p);
            c.drawCircle(x + s * 0.6f, base - s * 0.55f, s * 0.7f, p);
        }
    }

    private static void river(Canvas c, int color) {
        Path p = new Path();
        p.moveTo(W * 0.46f, H * 0.68f);
        p.cubicTo(W * 0.56f, H * 0.76f, W * 0.3f, H * 0.84f, W * 0.42f, H);
        p.lineTo(W * 0.66f, H);
        p.cubicTo(W * 0.48f, H * 0.86f, W * 0.62f, H * 0.76f, W * 0.5f, H * 0.68f);
        p.close();
        Paint pt = paint();
        pt.setShader(new LinearGradient(0, H * 0.68f, 0, H, blend(color, 0xFFFFFFFF, 0.3f), color, Shader.TileMode.CLAMP));
        c.drawPath(p, pt);
    }

    private static void buildings(Canvas c, Random r, float base, int color, float litChance) {
        Paint p = paint();
        p.setColor(color);
        Paint win = paint();
        float x = -5;
        while (x < W) {
            float w = 26 + r.nextFloat() * 50, h = 40 + r.nextFloat() * r.nextFloat() * 190;
            c.drawRect(x, base - h, x + w, H, p);
            if (r.nextFloat() < 0.25f) c.drawRect(x + w * 0.45f, base - h - 18, x + w * 0.5f, base - h, p);   // mast
            for (float wy = base - h + 8; wy < base - 6; wy += 9) {
                for (float wx = x + 5; wx < x + w - 6; wx += 8) {
                    if (r.nextFloat() < litChance * 0.5f) {
                        win.setColor(Color.argb(150 + r.nextInt(100), 255, 200 + r.nextInt(40), 120 + r.nextInt(60)));
                        c.drawRect(wx, wy, wx + 3, wy + 4, win);
                    }
                }
            }
            x += w + 2 + r.nextFloat() * 6;
        }
    }

    private static int darker(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
