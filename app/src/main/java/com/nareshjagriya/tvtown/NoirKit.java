package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The Noir & Gold look's materials: its two typefaces (Fraunces, a serif for titles and the clock, upright and
 * italic; Outfit for spaced capitals and labels, both from the app's fonts folder, at any weight), its colours (warm
 * black, ivory, one gold), and its parts: the solid gold button, the bronze outline, the gold double frame round what
 * is focused, a gold rule before a heading.
 */
final class NoirKit {

    static final int INK = 0xFF0E0C0B, PANEL = 0xFF151210, IVORY = 0xFFF4EDE1, IVORY_SOFT = 0xFFD8CDBB, MUTED = 0xFF9C9184,
            CAPTION = 0xFF8C7A5C, LINE = 0xFF3A322C, LINE_LIGHT = 0xFF4A4036, GOLD = 0xFFD4A64A, GOLD_LIGHT = 0xFFE8C877;

    private static final Map<String, Typeface> faces = new HashMap<>();

    private final Context ctx;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    NoirKit(Context ctx, float k) {
        this.ctx = ctx;
        this.k = k;
        stroke.setStyle(Paint.Style.STROKE);
    }

    /** All of TV Town's text is Roboto, the TV's own font, whichever style a screen asks for. Loaded once per weight. */
    private Typeface face(String file, int weight) {
        int w = weight <= 450 ? 400 : weight <= 550 ? 500 : 700;
        String key = "roboto@" + w;
        Typeface t = faces.get(key);
        if (t == null) {
            t = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false);
            faces.put(key, t);
        }
        return t;
    }

    /** Fraunces, upright or italic, in design pixels. */
    Paint serif(float size, int weight, boolean italic, int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face(italic ? "fraunces-italic.ttf" : "fraunces.ttf", weight));
        p.setTextSize(size * k);
        p.setColor(colour);
        return p;
    }

    /** Manrope, the one typeface of the app, at a weight from 400 to 700. */
    Paint inter(float size, int weight, float spacing, int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face("manrope.ttf", Math.max(400, Math.min(700, weight))));
        p.setTextSize(size * k);
        p.setLetterSpacing(0);   // Roboto is drawn to be set as it is
        p.setColor(colour);
        return p;
    }

    /** Outfit, spaced (letterSpacing in ems: 0.2 for the spaced capitals). */
    Paint sans(float size, int weight, float spacing, int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face("outfit.ttf", weight));
        p.setTextSize(size * k);
        p.setLetterSpacing(spacing);
        p.setColor(colour);
        return p;
    }

    /**
     * What is focused, as frosted glass laid over it: a soft light glow round it, a pale glass sheen brighter at the
     * top, and a fine bright edge.
     */
    void focus(Canvas c, RectF r, float radius) {
        Frost.sheen(c, r, radius, k);
    }

    /** A fine outline. */
    void outline(Canvas c, RectF r, float radius, int colour, float width) {
        stroke.setColor(colour);
        stroke.setStrokeWidth(Math.max(1, width * k));
        c.drawRoundRect(r, radius, radius, stroke);
    }

    /** A solid panel. */
    void panel(Canvas c, RectF r, float radius, int colour) {
        fill.setShader(null);
        fill.setColor(colour);
        c.drawRoundRect(r, radius, radius, fill);
    }

    /**
     * A button: primary solid gold with dark capitals, else a bronze outline with ivory capitals (gold when focused);
     * the focused one in the gold double frame.
     */
    void button(Canvas c, RectF r, String label, Paint text, boolean primary, boolean focused) {
        float rad = 4 * k;
        if (primary) {
            panel(c, r, rad, focused ? GOLD_LIGHT : GOLD);
            text.setColor(0xFF14100C);
        } else {
            panel(c, r, rad, 0x99100C0B);
            outline(c, r, rad, focused ? GOLD : CAPTION, focused ? 1.6f : 1);
            text.setColor(focused ? GOLD_LIGHT : IVORY);
        }
        c.drawText(label, r.centerX() - text.measureText(label) / 2, r.centerY() + text.getTextSize() * 0.36f, text);
        if (focused) focus(c, r, rad);
    }

}
