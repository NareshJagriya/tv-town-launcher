package com.nareshjagriya.tvtown;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Full-screen effects for the player, moved by the music: bars on both sides, a beating colour flower, wavy lines,
 * flying stars and more. They draw with shapes only, a limited number per frame, to stay light
 * on a small TV. bands: the music's loudness from bass to treble (0 to about 1); beat: a jolt that fades.
 */
final class MusicEffects {

    /** The first SIDE effects keep to the left and right, leaving the middle clear for the lyrics. */
    static final int SIDE = 5;
    static final String[] NAMES = {L10n.t("Rising bars"), L10n.t("Rising lines"), L10n.t("Side waves"), L10n.t("Side rings"), L10n.t("Rising dots"), L10n.t("Beat flower"), L10n.t("Colour waves"),
            L10n.t("Spectrum"), L10n.t("Liquid light"), L10n.t("Star warp"), L10n.t("Fireworks")};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] hsv = {0, 0.85f, 1};
    private final float[] starA = new float[220], starD = new float[220], starH = new float[220];
    private final float[] px = new float[160], py = new float[160], vx = new float[160], vy = new float[160], life = new float[160], hue = new float[160];
    private int nextParticle;
    private long lastBurst, lastFrame;
    private float travel, turn;

    MusicEffects() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        Random r = new Random(7);
        for (int i = 0; i < starA.length; i++) {
            starA[i] = r.nextFloat() * (float) (Math.PI * 2);
            starD[i] = r.nextFloat();
            starH[i] = r.nextFloat() * 360;
        }
    }

    private int colour(float h, float sat, float val, int alpha) {
        hsv[0] = ((h % 360) + 360) % 360;
        hsv[1] = sat;
        hsv[2] = val;
        return Color.HSVToColor(alpha, hsv);
    }

    void draw(Canvas c, int effect, int w, int h, float k, long now, float[] bands, float energy, float beat) {
        c.drawColor(Color.BLACK);
        float dt = lastFrame == 0 ? 0.03f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float drive = Math.min(1.4f, energy + beat * 0.8f);
        travel += dt * (0.35f + drive * 1.1f);
        turn += dt * (0.25f + drive * 0.7f);
        int e = effect;
        float cx = w / 2f, cy = h / 2f, far = (float) Math.hypot(w, h) / 2f, tone = now / 90f;
        float midY = h * 0.425f;   // the middle of the space above the bottom bar: where the words are
        switch (e) {
            case 0: sideBars(c, w, midY, h, k, tone, bands, beat); break;
            case 1: sideLines(c, w, midY, h, k, tone, now, bands, beat); break;
            case 2: sideWaves(c, w, h, k, tone, now, bands, beat); break;
            case 3: sideRings(c, w, midY, k, tone, bands, beat); break;
            case 4: sideDots(c, w, midY, h, k, tone, now, bands, beat); break;
            case 5: flower(c, cx, cy, h, k, tone, bands, beat); break;
            case 6: waves(c, w, h, k, tone, now, bands, beat); break;
            case 7: spectrum(c, w, h, k, tone, bands); break;
            case 8: liquid(c, w, h, tone, now, bands, beat); break;
            case 9: warp(c, cx, cy, far, k, dt, drive, tone); break;
            default: fireworks(c, w, h, k, dt, now, tone, beat);
        }
    }

    /** How far each side may reach in from its edge: the middle half of the screen stays clear. */
    private static final float REACH = 0.25f;

    /**
     * How tall something rising from the foot of the screen may grow at a place across it (t 0 to 1): just up to the
     * words in the middle, nearly to the top at the sides.
     */
    private static float riseLimit(float t, int h) {
        float s = Math.max(0, Math.min(1, (Math.abs(t - 0.5f) * 2 - 0.45f) / 0.45f));
        s = s * s * (3 - 2 * s);
        return h * (0.43f + 0.50f * s);
    }

    /** The music at a place across the screen: the bass in the middle, the treble at the edges, the same on both sides. */
    private float riseLevel(float t, float[] bands, float beat) {
        int b = Math.min(bands.length - 1, (int) (Math.abs(t - 0.5f) * 2 * bands.length * 0.9f));
        // Each bar has its own flicker, so loud bass never leaves a flat, stuck row.
        double seed = t * 311.7;
        float flicker = 0.62f + 0.38f * (float) Math.abs(Math.sin(seed + lastFrame / 150.0) * Math.cos(seed * 1.7 + lastFrame / 410.0));
        return Math.min(1, (0.06f + Math.min(1, bands[b] * 1.1f + beat * 0.15f) * 0.94f) * flicker);
    }

    /** A rainbow equaliser across the whole screen, each bar moving straight up and down from behind the bottom bar. */
    private void sideBars(Canvas c, int w, float mid, int h, float k, float tone, float[] bands, float beat) {
        int n = 56;
        float bw = w / (float) n;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n, top = h - riseLevel(t, bands, beat) * riseLimit(t, h);
            fill.setColor(colour(tone + i * 6.4f, 0.88f, 1, 255));
            c.drawRoundRect(i * bw + 3 * k, top, (i + 1) * bw - 3 * k, h + 20 * k, 7 * k, 7 * k, fill);
        }
    }

    /** The same with many fine lines, like a graphic equaliser. */
    private void sideLines(Canvas c, int w, float mid, int h, float k, float tone, long now, float[] bands, float beat) {
        int n = 110;
        float step = w / (float) n;
        line.setStrokeWidth(4 * k);
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n, wobble = 0.72f + 0.28f * (float) Math.abs(Math.sin(i * 1.3 + now / 300.0));
            float top = h - riseLevel(t, bands, beat) * wobble * riseLimit(t, h);
            line.setColor(colour(tone + i * 3.2f, 0.75f, 1, 255));
            c.drawLine((i + 0.5f) * step, h, (i + 0.5f) * step, top, line);
        }
    }

    /** Waving ribbons running up each side. */
    private void sideWaves(Canvas c, int w, int h, float k, float tone, long now, float[] bands, float beat) {
        int ribbons = 6;
        float area = w * REACH;
        line.setStrokeWidth(5 * k);
        for (int r = 0; r < ribbons; r++) {
            for (int side = 0; side < 2; side++) {
                path.reset();
                float base = area * (r + 0.6f) / ribbons;
                for (int i = 0; i <= 36; i++) {
                    float t = i / 36f;
                    float amp = (0.03f + bands[Math.min(bands.length - 1, r * 7)] * 0.5f + beat * 0.12f) * area * 0.6f;
                    float x = base + (float) Math.sin(t * Math.PI * 4 + now / (520.0 + r * 60) + r) * amp;
                    x = Math.max(0, Math.min(area * 1.08f, x));
                    float px = side == 0 ? x : w - x, py = t * h;
                    if (i == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                line.setColor(colour(tone + r * 45 + side * 60, 0.8f, 1, 255));
                c.drawPath(path, line);
            }
        }
    }

    /** Rings spreading in from each edge and fading before the middle. */
    private void sideRings(Canvas c, int w, float mid, float k, float tone, float[] bands, float beat) {
        int n = 12;
        float area = w * REACH * 1.25f;
        for (int i = 0; i < n; i++) {
            float t = (travel * 0.32f + i / (float) n) % 1;
            line.setStrokeWidth((5 + bands[i * 3] * 24 + beat * 6) * k * (1.15f - t * 0.6f));
            line.setColor(colour(tone + t * 240, 0.85f, 1, Math.round(255 * (1 - t))));
            c.drawCircle(0, mid, t * area, line);
            line.setColor(colour(tone + 120 + t * 240, 0.85f, 1, Math.round(255 * (1 - t))));
            c.drawCircle(w, mid, t * area, line);
        }
    }

    /** Columns of dots lighting up from the bottom with the music, like an old hi-fi. */
    private void sideDots(Canvas c, int w, float mid, int h, float k, float tone, long now, float[] bands, float beat) {
        int cols = 40;
        float cw = w / (float) cols, r = cw * 0.33f, step = cw * 0.95f;
        fill.setStyle(Paint.Style.FILL);
        for (int x = 0; x < cols; x++) {
            float t = (x + 0.5f) / cols, reach = riseLimit(t, h);
            int most = (int) (reach / step), lit = Math.round(riseLevel(t, bands, beat) * most);
            for (int d = 0; d < lit; d++) {
                fill.setColor(colour(tone + x * 9 + d * 7, 0.85f, 1, 255));
                c.drawCircle((x + 0.5f) * cw, h - (d + 0.5f) * step, r, fill);
            }
        }
    }

    /** Colourful bars bursting round a beating circle: the bass moves it most. */
    private void flower(Canvas c, float cx, float cy, int h, float k, float tone, float[] bands, float beat) {
        int n = 96;
        float base = (150 + beat * 60) * k, span = h * 0.34f;
        line.setStrokeWidth(9 * k);
        for (int i = 0; i < n; i++) {
            int b = Math.min(bands.length - 1, (int) (Math.abs(i / (float) n - 0.5f) * 2 * bands.length * 0.8f));
            float v = Math.min(1.2f, bands[b] * 1.3f), a = i * 360f / n + turn * 18;
            double rad = Math.toRadians(a);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad), r2 = base + 8 * k + v * span;
            line.setColor(colour(tone + i * 3.75f, 0.9f, 1, 255));
            c.drawLine(cx + cos * base, cy + sin * base, cx + cos * r2, cy + sin * r2, line);
        }
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colour(tone + 180, 0.85f, 0.9f, 255));
        c.drawCircle(cx, cy, base - 14 * k, fill);
        fill.setColor(Color.BLACK);
        c.drawCircle(cx, cy, (base - 14 * k) * 0.72f, fill);
    }

    /** Rainbow lines swaying across the whole screen, taller with the music. */
    private void waves(Canvas c, int w, int h, float k, float tone, long now, float[] bands, float beat) {
        int lines = 10;
        line.setStrokeWidth(5 * k);
        for (int l = 0; l < lines; l++) {
            path.reset();
            float base = h * (l + 1f) / (lines + 1f);
            for (int i = 0; i <= 56; i++) {
                float t = i / 56f;
                float amp = (0.12f + bands[Math.min(bands.length - 1, (int) (t * bands.length))] * 0.9f + beat * 0.25f) * h * 0.16f;
                float y = base + (float) Math.sin(t * Math.PI * (3 + l * 0.6) + now / (620.0 - l * 25) + l) * amp;
                if (i == 0) path.moveTo(0, y);
                else path.lineTo(t * w, y);
            }
            line.setColor(colour(tone + l * 36, 0.8f, 1, 255));
            c.drawPath(path, line);
        }
    }

    /** Flying through coloured stars, faster with the music. */
    private void warp(Canvas c, float cx, float cy, float far, float k, float dt, float drive, float tone) {
        for (int i = 0; i < starA.length; i++) {
            starD[i] += dt * (0.12f + drive * 0.5f) * (0.25f + starD[i]);
            if (starD[i] > 1) starD[i] -= 1;
            float d = starD[i], r = d * d * far * 1.3f, tail = r * (0.05f + drive * 0.12f);
            float cos = (float) Math.cos(starA[i]), sin = (float) Math.sin(starA[i]);
            line.setStrokeWidth((1 + d * 5) * k);
            line.setColor(colour(tone + starH[i], 0.6f, 1, Math.round(255 * Math.min(1, d * 4))));
            c.drawLine(cx + cos * r, cy + sin * r, cx + cos * (r + tail), cy + sin * (r + tail), line);
        }
    }

    /** Rainbow bars mirrored about the middle of the screen. */
    private void spectrum(Canvas c, int w, int h, float k, float tone, float[] bands) {
        int n = 64;
        float bw = w / (float) n, mid = h / 2f;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            int b = Math.min(bands.length - 1, (int) (Math.abs(i / (float) (n - 1) - 0.5f) * 2 * bands.length * 0.9f));
            float v = (0.03f + Math.min(1.1f, bands[b] * 1.2f)) * h * 0.46f;
            fill.setColor(colour(tone + i * 5.6f, 0.85f, 1, 255));
            c.drawRoundRect(i * bw + 3 * k, mid - v, (i + 1) * bw - 3 * k, mid + v, 6 * k, 6 * k, fill);
        }
    }

    /** Pools of coloured light melting into each other. */
    private void liquid(Canvas c, int w, int h, float tone, long now, float[] bands, float beat) {
        double t = now / 1000.0;
        for (int i = 0; i < 6; i++) {
            float x = w * (0.5f + 0.42f * (float) Math.sin(t * (0.31 + i * 0.07) + i * 1.3));
            float y = h * (0.5f + 0.42f * (float) Math.cos(t * (0.27 + i * 0.09) + i * 2.1));
            float r = h * (0.42f + bands[i * 7] * 0.4f + beat * 0.1f);
            int col = colour(tone + i * 60, 0.9f, 1, 255);
            add.setShader(new RadialGradient(x, y, r, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, add);
        }
        add.setShader(null);
    }

    /** A burst of sparks on every beat, somewhere on the screen. */
    private void fireworks(Canvas c, int w, int h, float k, float dt, long now, float tone, float beat) {
        if ((beat > 0.45f && now - lastBurst > 240) || now - lastBurst > 1100) {
            lastBurst = now;
            float x = w * (0.15f + 0.7f * (float) Math.random()), y = h * (0.15f + 0.6f * (float) Math.random()), h0 = tone + (float) Math.random() * 360;
            for (int i = 0; i < 40; i++) {
                int p = nextParticle;
                nextParticle = (nextParticle + 1) % px.length;
                double a = Math.random() * Math.PI * 2;
                float sp = (120 + (float) Math.random() * 420) * k;
                px[p] = x;
                py[p] = y;
                vx[p] = (float) Math.cos(a) * sp;
                vy[p] = (float) Math.sin(a) * sp;
                life[p] = 1;
                hue[p] = h0 + (float) Math.random() * 50;
            }
        }
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < px.length; i++) {
            if (life[i] <= 0) continue;
            life[i] -= dt * 0.8f;
            vy[i] += dt * 170 * k;
            vx[i] *= 1 - dt * 0.7f;
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            fill.setColor(colour(hue[i], 0.75f, 1, Math.round(255 * Math.max(0, life[i]))));
            c.drawCircle(px[i], py[i], (3 + life[i] * 5) * k, fill);
        }
    }
}
