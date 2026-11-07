package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/** A line of text drawn once into a bitmap (with its shadow) and redrawn only when the text changes. */
final class TextSprite {
    private String text;
    private Bitmap bitmap;
    private float pad, ascent, width, height;

    void set(String t, Paint p, int color, float shadow) {
        if (t.equals(text)) return;
        text = t;
        Paint q = new Paint(p);
        q.setColor(color);
        if (shadow > 0) q.setShadowLayer(shadow * 1.3f, 0, shadow * 0.4f, 0x99000000);
        Paint.FontMetrics fm = q.getFontMetrics();
        pad = shadow * 3 + 2;
        ascent = -fm.ascent;
        width = q.measureText(t);
        height = fm.descent - fm.ascent;
        if (bitmap != null) bitmap.recycle();
        bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width + pad * 2)), Math.max(1, Math.round(height + pad * 2)), Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText(t, pad, pad + ascent, q);
    }

    float textWidth() {
        return width;
    }

    float textHeight() {
        return height;
    }

    float baseline() {
        return ascent;
    }

    void drawTopLeft(Canvas c, float x, float top, Paint p) {
        if (bitmap != null) c.drawBitmap(bitmap, x - pad, top - pad, p);
    }

    void drawBaseline(Canvas c, float x, float baselineY, Paint p) {
        if (bitmap != null) c.drawBitmap(bitmap, x - pad, baselineY - ascent - pad, p);
    }
}
