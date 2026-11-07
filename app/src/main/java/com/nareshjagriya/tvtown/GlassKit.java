package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;
import java.util.Map;

/**
 * The home screen's glass look for panels and focus, shared by Home and Discover: frosted backdrop, dark
 * smoke and white tint, 1 px edge, a highlight along the top-left, a soft drop shadow, and a focus state
 * with a brighter fill, a 2 px edge and a white glow. Sizes are in 1920 x 1080 design pixels times k.
 */
final class GlassKit {

    /** Anything glass can show through: the illustrated scenes, or a title's artwork. */
    interface Source {
        void round(Canvas c, RectF r, float radius, float alpha);

        void circle(Canvas c, float x, float y, float r, float alpha);
    }

    final float k;
    final Source bg;
    private final Context ctx;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG), sprite = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Bitmap> shadows = new HashMap<>(), glows = new HashMap<>(), glyphs = new HashMap<>();
    private final RectF tmp = new RectF();

    Context context() {
        return ctx;
    }

    GlassKit(Context ctx, Source bg, float k) {
        this.ctx = ctx.getApplicationContext();
        this.bg = bg;
        this.k = k;
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        hi.setStyle(Paint.Style.STROKE);
        neon = neon(ctx);
    }

    /** The neon glass look is on (Home look: Neon glass): every screen drawn with this kit follows it. */
    static boolean neon(Context ctx) {
        return true;   // the one look (Classic removed)
    }

    private final boolean neon;
    private NoirKit nk;

    /** The shared font (Theme; Inter in the neon glass look), sized in design pixels. */
    Paint font(float designPx, int weight) {
        Paint p = Theme.font(ctx, designPx * k * (neon ? 1.15f : 1), weight);   // larger in the neon glass look
        if (neon) {
            if (nk == null) nk = new NoirKit(ctx, k);
            p.setTypeface(nk.inter(10, weight <= 450 ? 500 : weight <= 550 ? 600 : 700, 0, 0).getTypeface());
        }
        return p;
    }

    /**
     * A screen's background, at full strength. Call after drawing the screen's own picture, if it has one.
     */
    void haze(Canvas c, float w, float h, long now) {
        haze(c, w, h, now, 0xF2);
    }

    private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF frostRect = new RectF();

    /**
     * Every screen's background: Home's picture, frosted and neutral, under a light veil, so the app is one place.
     * A lower base lets a screen's own picture show through instead.
     */
    void haze(Canvas c, float w, float h, long now, int base) {
        Bitmap frost = GlassSheet.frost;
        if (base >= 0xE0 && frost != null && !Frost.lighter) {
            frostRect.set(0, 0, w, h);
            c.drawBitmap(frost, null, frostRect, frostPaint);
            if (!Frost.lighter) c.drawColor(0xE80A0A0B);   // Lighter: only the blur, nothing over it
        } else c.drawColor(((Frost.lighter ? 0xFF : base) << 24) | (Frost.base() & 0x00FFFFFF));
    }

    /** See-through glass (neon look): faint fill, a sheen inside the top, a fine light edge; lifted when focused. */
    private void neonGlass(Canvas c, RectF r, float radius, float alpha, boolean focused) {
        if (focused) {
            fill.setColor(Color.argb(Math.round(82 * alpha), 0, 0, 0));
            fill.setMaskFilter(new android.graphics.BlurMaskFilter(16 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(r.left, r.top + 10 * k, r.right, r.bottom + 10 * k, radius, radius, fill);
            fill.setMaskFilter(null);
        }
        int a = Math.round(255 * alpha);
        fill.setColor(Color.argb(a, 255, 255, 255));
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, focused ? 0x24FFFFFF : 0x08FFFFFF, focused ? 0x0DFFFFFF : 0x02FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, fill);
        c.save();
        android.graphics.Path p = new android.graphics.Path();
        p.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        c.clipPath(p);
        fill.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height() / 2, 60 * k), focused ? 0x40FFFFFF : 0x22FFFFFF, 0, Shader.TileMode.CLAMP));
        c.drawRect(r, fill);
        c.restore();
        fill.setShader(null);
        edge.setStrokeWidth(Math.max(1, k));
        edge.setColor(focused ? 0x99FFFFFF : 0x2EFFFFFF);   // Home's hairline; the cyan focus line
        edge.setAlpha(Math.round(Color.alpha(edge.getColor()) * alpha));
        if (focused) edge.setStrokeWidth(1.8f * k);
        if (focused) edge.setShadowLayer(6 * k, 0, 0, 0x33FFFFFF);
        c.drawRoundRect(r, radius, radius, edge);
        edge.clearShadowLayer();
        edge.setColor(Color.WHITE);
    }

    /** A glass panel: shadow, blurred backdrop, smoke, tint, edge and top-left highlight. */
    void panel(Canvas c, RectF r, float radius, float alpha, float tint) {
        if (neon && Frost.lighter) {   // Picture card: the page is plain, so the blur comes from the pop-up's backdrop
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(190 * alpha), 12, 12, 14));   // glass over the blurred screen
            c.drawRoundRect(r, radius, radius, fill);
            Frost.topEdge(c, r, radius, k, 0x4DFFFFFF);
            return;
        }
        if (neon) {   // dark see-through navy glass over the blurred picture behind
            bg.round(c, r, radius, alpha);
            fill.setColor(Color.argb(Math.round(180 * alpha), 14, 14, 16));   // Home's dark glass
            c.drawRoundRect(r, radius, radius, fill);
            neonGlass(c, r, radius, alpha, false);
            return;
        }
        Bitmap sh = shadow(r.width(), r.height(), radius);
        sprite.setColor(Color.BLACK);
        sprite.setAlpha(Math.round(70 * alpha));
        c.drawBitmap(sh, r.centerX() - sh.getWidth() / 2f, r.centerY() + 18 * k - sh.getHeight() / 2f, sprite);
        bg.round(c, r, radius, alpha);
        fill.setColor(Color.argb(Math.round(170 * alpha), 14, 14, 16));   // dark frosted glass: white text stays legible
        c.drawRoundRect(r, radius, radius, fill);
        fill.setColor(Color.argb(Math.round(255 * tint * alpha), 255, 255, 255));
        c.drawRoundRect(r, radius, radius, fill);
        edge.setStrokeWidth(1);
        edge.setAlpha(Math.round(97 * alpha));
        tmp.set(r);
        tmp.inset(0.5f, 0.5f);
        c.drawRoundRect(tmp, radius, radius, edge);
        float w = Math.max(1f, 2 * k);
        hi.setStrokeWidth(w);
        hi.setShader(new LinearGradient(r.left, r.top, r.left + Math.min(r.width(), r.height()) * 0.9f,
                r.top + Math.min(r.width(), r.height()) * 0.9f, Color.argb(Math.round(64 * alpha), 255, 255, 255),
                0x00FFFFFF, Shader.TileMode.CLAMP));
        tmp.set(r);
        tmp.inset(1 + w / 2, 1 + w / 2);
        c.drawRoundRect(tmp, radius - 1, radius - 1, hi);
    }

    /** The focus state over a row, button or card: brighter fill, a 2 px white edge and a soft white glow. */
    void focus(Canvas c, RectF r, float radius, float alpha) {
        if (neon) {
            Frost.plate(c, r, radius, k);
            return;
        }
        Bitmap g = glow(r.width(), r.height(), radius);
        sprite.setColor(Color.WHITE);
        sprite.setAlpha(Math.round(70 * alpha));
        c.drawBitmap(g, r.centerX() - g.getWidth() / 2f, r.centerY() - g.getHeight() / 2f, sprite);
        fill.setColor(Color.argb(Math.round(38 * alpha), 255, 255, 255));
        c.drawRoundRect(r, radius, radius, fill);
        edge.setStrokeWidth(Math.max(1f, 2 * k));
        edge.setAlpha(Math.round(255 * alpha));
        tmp.set(r);
        tmp.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
        c.drawRoundRect(tmp, radius, radius, edge);
    }

    /** A plain glass pill (unfocused buttons): blurred backdrop, 14% tint, 38% edge. */
    void pill(Canvas c, RectF r, float radius, float alpha) {
        if (neon) {
            fill.setColor(Color.argb(Math.round(150 * alpha), 14, 14, 16));
            c.drawRoundRect(r, radius, radius, fill);
            neonGlass(c, r, radius, alpha, false);
            return;
        }
        bg.round(c, r, radius, alpha);
        fill.setColor(Color.argb(Math.round(170 * alpha), 14, 14, 16));
        c.drawRoundRect(r, radius, radius, fill);
        fill.setColor(Color.argb(Math.round(13 * alpha), 255, 255, 255));
        c.drawRoundRect(r, radius, radius, fill);
        edge.setStrokeWidth(1);
        edge.setAlpha(Math.round(97 * alpha));
        c.drawRoundRect(r, radius, radius, edge);
    }

    /** A white outline symbol of the given design size, cached. */
    Bitmap glyph(WheelArt.Glyph g, float designSize) {
        String key = g.name() + "@" + designSize;
        Bitmap b = glyphs.get(key);
        if (b == null) {
            String n = g == WheelArt.Glyph.TELESCOPE ? "compass" : g == WheelArt.Glyph.NOTE ? "music" : g == WheelArt.Glyph.GRID ? "apps" : g.name().toLowerCase(java.util.Locale.ROOT);
            b = LineIcon.icon(n, designSize * k);
            glyphs.put(key, b);
        }
        return b;
    }

    /** Draws a glyph bitmap centred at (x, y). */
    void drawGlyph(Canvas c, Bitmap b, float x, float y, int alpha) {
        sprite.setColor(Color.WHITE);
        sprite.setAlpha(alpha);
        c.drawBitmap(b, x - b.getWidth() / 2f, y - b.getHeight() / 2f, sprite);
    }

    private Bitmap shadow(float w, float h, float radius) {
        String key = Math.round(w) + "x" + Math.round(h) + "r" + Math.round(radius);
        Bitmap b = shadows.get(key);
        if (b == null) {
            b = WheelArt.roundGlow(w, h, radius, 30 * k);
            shadows.put(key, b);
        }
        return b;
    }

    private Bitmap glow(float w, float h, float radius) {
        String key = Math.round(w) + "x" + Math.round(h) + "r" + Math.round(radius);
        Bitmap b = glows.get(key);
        if (b == null) {
            b = WheelArt.roundGlow(w, h, radius, 12 * k);
            glows.put(key, b);
        }
        return b;
    }
}
