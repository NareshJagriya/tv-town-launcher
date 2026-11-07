package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * The one set of design tokens every screen uses: colours, glass levels, sizes, motion timings and the
 * ease-out curve, and the font. Sizes are in 1920 x 1080 design pixels; multiply by the screen's scale k.
 */
final class Theme {

    private Theme() {
    }

    // ---- colour ----------------------------------------------------------------------------------

    static final int INK = 0xFF0B0B0D;            // the dark behind everything
    static final int SMOKE = 0xFF0B0B0D;          // dark layer inside glass, for legible white text
    static final int WHITE = 0xFFFFFFFF;

    /** Glass: white tint 8 to 20 %, edge 25 to 40 %, top-left highlight. */
    static final float TINT_LOW = 0.08f, TINT = 0.14f, TINT_HIGH = 0.20f;
    static final float EDGE = 0.32f, EDGE_FOCUS = 1f, HIGHLIGHT = 0.28f;
    static final float SMOKE_PANEL = 0.55f, SMOKE_PILL = 0.45f;

    /** Accents: Random tiles and the New this week glow. Each is a gradient pair. */
    static final int AMBER_A = 0xFFFFC46B, AMBER_B = 0xFFF0782A;
    static final int VIOLET_A = 0xFFB69CFF, VIOLET_B = 0xFF6A4BE0;
    static final int MINT_A = 0xFF7BF0CF, MINT_B = 0xFF1FB394;
    static final int ROSE_A = 0xFFFFA3C7, ROSE_B = 0xFFE0457B;
    static final int NEW_GLOW = 0xFFC9B6FF;

    // ---- size ------------------------------------------------------------------------------------

    static final float MARGIN = 86;                // screen edge to content
    static final float RADIUS_CARD = 28, RADIUS_PANEL = 30, RADIUS_TILE = 26;
    static final float PILL_H = 60, GAP = 18;

    // ---- type ------------------------------------------------------------------------------------

    private static final java.util.Map<Integer, Typeface> roboto = new java.util.HashMap<>();

    /**
     * Roboto (Hindi and Marathi fall back to the TV's own Noto Devanagari) at a given weight:
     * regular (400), medium (500) or bold (600 and up). Nothing lighter than 400: thin strokes fade on a TV at sofa
     * distance. Text gets a soft shadow so it reads on artwork and glass alike.
     */
    static Paint font(Context ctx, float sizePx, int weight) {
        int w = weight <= 450 ? 400 : weight <= 550 ? 500 : 700;
        Typeface face = roboto.get(w);
        if (face == null) {
            face = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false);
            roboto.put(w, face);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(sizePx);
        p.setColor(WHITE);
        p.setShadowLayer(Math.max(1f, sizePx * 0.08f), 0, Math.max(1f, sizePx * 0.03f), 0x99000000);
        return p;
    }

    // ---- motion ----------------------------------------------------------------------------------

    /** Focus and card moves. */
    static final long MOVE_MS = 500;
    /** Panels and overlays opening, chips appearing. */
    static final long FADE_MS = 240;
    static final long BACKDROP_FADE_MS = 800;
    static final long KEN_BURNS_MS = 30_000;
    static final long IDLE_PAUSE_MS = 5 * 60_000;

    /**
     * cubic-bezier(.22, 1, .36, 1): a quick start that settles gently, with no overshoot. Solved for x with
     * a few Newton steps, then evaluated for y.
     */
    static float ease(float t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        final float x1 = 0.22f, y1 = 1f, x2 = 0.36f, y2 = 1f;
        float u = t;
        for (int i = 0; i < 6; i++) {
            float x = bezier(u, x1, x2) - t;
            float dx = bezierSlope(u, x1, x2);
            if (Math.abs(x) < 1e-5f || Math.abs(dx) < 1e-6f) break;
            u -= x / dx;
            u = Math.max(0, Math.min(1, u));
        }
        return bezier(u, y1, y2);
    }

    private static float bezier(float u, float p1, float p2) {
        float v = 1 - u;
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
    }

    private static float bezierSlope(float u, float p1, float p2) {
        float v = 1 - u;
        return 3 * v * v * p1 + 6 * v * u * (p2 - p1) + 3 * u * u * (1 - p2);
    }

    /** Progress 0..1 of an animation that started at `start`, eased. */
    static float progress(long now, long start, long duration) {
        if (duration <= 0) return 1;
        return ease(Math.max(0, Math.min(1, (now - start) / (float) duration)));
    }
}
