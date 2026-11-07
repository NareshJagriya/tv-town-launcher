package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;


/** Everything the glass wheel draws, rendered once into bitmaps; WheelView only places and fades them. */
final class WheelArt {

    private WheelArt() {
    }

    // ---- outline symbols (24 x 24 design grid) ---------------------------------------------------

    enum Glyph { TELESCOPE, NOTE, TV, GRID, WIFI, GEAR, FILM, SHUFFLE, SLIDERS, CHECK, SEARCH, PLAY,
        HEART, EYE, STAR, TAG, SUBS, WRENCH, PULSE, CHIP, DRIVE, BT, POWER, SUN, KEY, DICE, REDO, UPLOAD, DOWN, LIST, INFO, TRAILER, PAUSE, PENCIL }

    static Path glyph(Glyph g) {
        Path p = new Path();
        switch (g) {
            case TELESCOPE: {
                Path tube = new Path(), cap = new Path(), eye = new Path();
                tube.addRect(-6.5f, -1.9f, 5.5f, 1.9f, Path.Direction.CW);
                cap.addRect(5.5f, -2.9f, 7.6f, 2.9f, Path.Direction.CW);
                eye.addRect(-8.6f, -1f, -6.5f, 1f, Path.Direction.CW);
                tube.op(cap, Path.Op.UNION);
                tube.op(eye, Path.Op.UNION);
                Matrix m = new Matrix();
                m.setRotate(-28);
                m.postTranslate(12.4f, 8.6f);
                tube.transform(m);
                p.addPath(tube);
                p.moveTo(12f, 11.4f);
                p.lineTo(7.6f, 21f);
                p.moveTo(12f, 11.4f);
                p.lineTo(12f, 21f);
                p.moveTo(12f, 11.4f);
                p.lineTo(16.4f, 21f);
                break;
            }
            case NOTE:
                p.addCircle(8.8f, 17.4f, 3.1f, Path.Direction.CW);
                p.moveTo(11.9f, 17.4f);
                p.lineTo(11.9f, 3.6f);
                p.cubicTo(13.8f, 5f, 17.4f, 5.6f, 17.4f, 9.6f);
                break;
            case TV:
                p.addRoundRect(new RectF(2.5f, 4.5f, 21.5f, 17.5f), 2f, 2f, Path.Direction.CW);
                p.moveTo(8.5f, 20.5f);
                p.lineTo(15.5f, 20.5f);
                p.moveTo(12f, 17.5f);
                p.lineTo(12f, 20.5f);
                break;
            case GRID:
                p.addRoundRect(new RectF(3.5f, 3.5f, 10.5f, 10.5f), 1.6f, 1.6f, Path.Direction.CW);
                p.addRoundRect(new RectF(13.5f, 3.5f, 20.5f, 10.5f), 1.6f, 1.6f, Path.Direction.CW);
                p.addRoundRect(new RectF(3.5f, 13.5f, 10.5f, 20.5f), 1.6f, 1.6f, Path.Direction.CW);
                p.addRoundRect(new RectF(13.5f, 13.5f, 20.5f, 20.5f), 1.6f, 1.6f, Path.Direction.CW);
                break;
            case WIFI:
                for (float r : new float[]{4.6f, 9.2f, 13.8f}) {
                    p.addArc(new RectF(12 - r, 19.5f - r, 12 + r, 19.5f + r), 225, 90);
                }
                p.addCircle(12f, 19.3f, 0.9f, Path.Direction.CW);
                break;
            case FILM: {
                p.addRoundRect(new RectF(3f, 4.5f, 21f, 19.5f), 1.8f, 1.8f, Path.Direction.CW);
                p.moveTo(7f, 4.5f);
                p.lineTo(7f, 19.5f);
                p.moveTo(17f, 4.5f);
                p.lineTo(17f, 19.5f);
                for (float y : new float[]{8.2f, 12f, 15.8f}) {
                    p.moveTo(3f, y);
                    p.lineTo(7f, y);
                    p.moveTo(17f, y);
                    p.lineTo(21f, y);
                }
                break;
            }
            case SLIDERS:
                for (float[] l : new float[][]{{6f, 15f}, {12f, 8f}, {18f, 13f}}) {
                    p.moveTo(l[0], 3.5f);
                    p.lineTo(l[0], 20.5f);
                    p.addCircle(l[0], l[1], 2.3f, Path.Direction.CW);
                }
                break;
            case SEARCH:
                p.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW);
                p.moveTo(15.3f, 15.3f);
                p.lineTo(20.5f, 20.5f);
                break;
            case PLAY:
                p.moveTo(8f, 5f);
                p.lineTo(19f, 12f);
                p.lineTo(8f, 19f);
                p.close();
                break;
            case CHECK:
                p.moveTo(5f, 12.5f);
                p.lineTo(10f, 17.5f);
                p.lineTo(19.5f, 7f);
                break;
            case SHUFFLE:
                p.moveTo(3f, 7f);
                p.lineTo(7f, 7f);
                p.cubicTo(12f, 7f, 12f, 17f, 17f, 17f);
                p.lineTo(21f, 17f);
                p.moveTo(3f, 17f);
                p.lineTo(7f, 17f);
                p.cubicTo(12f, 17f, 12f, 7f, 17f, 7f);
                p.lineTo(21f, 7f);
                p.moveTo(18.5f, 4.5f);
                p.lineTo(21f, 7f);
                p.lineTo(18.5f, 9.5f);
                p.moveTo(18.5f, 14.5f);
                p.lineTo(21f, 17f);
                p.lineTo(18.5f, 19.5f);
                break;
            case GEAR:
            default: {
                float ro = 10.6f, ri = 8.2f;
                for (int k = 0; k < 8; k++) {
                    double a = Math.toRadians(k * 45);
                    double[] off = {-15, -8, 8, 15};
                    float[] rad = {ri, ro, ro, ri};
                    for (int j = 0; j < 4; j++) {
                        double t = a + Math.toRadians(off[j]);
                        float x = 12 + rad[j] * (float) Math.sin(t), y = 12 - rad[j] * (float) Math.cos(t);
                        if (k == 0 && j == 0) p.moveTo(x, y);
                        else p.lineTo(x, y);
                    }
                }
                p.close();
                p.addCircle(12f, 12f, 3.5f, Path.Direction.CW);
                break;
            }
        }
        return p;
    }

    /** A white outline symbol `size` pixels square with a faint shadow; the bitmap has a margin of `pad`. */
    static Bitmap symbol(Glyph g, float size, float stroke, float shadow) {
        int pad = Math.round(shadow * 3 + stroke);   // room for the shadow
        int s = Math.round(size) + pad * 2;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path p = glyph(g);
        Matrix m = new Matrix();
        m.setScale(size / 24f, size / 24f);
        m.postTranslate(pad, pad);
        p.transform(m);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE);
        if (shadow > 0) paint.setShadowLayer(shadow * 1.3f, 0, shadow * 0.4f, 0xA6000000);
        c.drawPath(p, paint);
        return b;
    }

    /** A white glyph on a coloured disc, for the hub. */
    static Bitmap glyphDisc(Glyph g, int size, int top, int bottom) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new android.graphics.LinearGradient(0, 0, size, size, top, bottom, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        float gs = size * 0.5f;
        Path p = glyph(g);
        Matrix m = new Matrix();
        m.setScale(gs / 24f, gs / 24f);
        m.postTranslate((size - gs) / 2f, (size - gs) / 2f);
        p.transform(m);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.035f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        c.drawPath(p, stroke);
        return b;
    }

    // ---- app icons -------------------------------------------------------------------------------

    /** The icon as it appears on screen, full bleed: adaptive icons edge to edge, legacy icons as drawn. */
    private static Bitmap render(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d.getConstantState() != null) d = d.getConstantState().newDrawable().mutate();
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Rect r = new Rect(-s / 4, -s / 4, s + s / 4, s + s / 4);   // 108dp layers, middle 72dp visible
            if (a.getBackground() != null) {
                a.getBackground().setBounds(r);
                a.getBackground().draw(c);
            }
            if (a.getForeground() != null) {
                a.getForeground().setBounds(r);
                a.getForeground().draw(c);
            }
        } else {
            d.setBounds(0, 0, s, s);
            d.draw(c);
        }
        return b;
    }

    /** Average colour of a ring just inside the icon's edge, and how much it varies (0 = flat). */
    private static float[] edge(Bitmap b) {
        int s = b.getWidth(), in = Math.max(1, s / 14);
        long r = 0, g = 0, bl = 0;
        int n = 0;
        float[] samples = new float[64 * 3];
        for (int i = 0; i < 64; i++) {
            double t = i * Math.PI * 2 / 64;
            int x = (int) (s / 2f + (s / 2f - in) * Math.cos(t)), y = (int) (s / 2f + (s / 2f - in) * Math.sin(t));
            int p = b.getPixel(Math.max(0, Math.min(s - 1, x)), Math.max(0, Math.min(s - 1, y)));
            if (Color.alpha(p) < 128) continue;
            samples[n * 3] = Color.red(p);
            samples[n * 3 + 1] = Color.green(p);
            samples[n * 3 + 2] = Color.blue(p);
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
            n++;
        }
        if (n == 0) return new float[]{-1, 0, 0, 0, 0};
        float mr = r / (float) n, mg = g / (float) n, mb = bl / (float) n, var = 0;
        for (int i = 0; i < n; i++) {
            var += Math.abs(samples[i * 3] - mr) + Math.abs(samples[i * 3 + 1] - mg) + Math.abs(samples[i * 3 + 2] - mb);
        }
        return new float[]{mr, mg, mb, var / n, n};
    }

    /** The app's icon as a full-colour disc: adaptive icons masked to a circle, legacy ones at 70% on their edge colour. */
    static Bitmap iconDisc(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap full = render(d, size);
        if (d instanceof AdaptiveIconDrawable && flat(full)) {
            // Layers that render as one flat colour (a logo the layer split loses): use the icon's own drawing.
            full.recycle();
            full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, size, size);
            copy.draw(new Canvas(full));
            p.setShader(new android.graphics.BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        } else if (d instanceof AdaptiveIconDrawable) {
            p.setShader(new android.graphics.BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        } else {
            float[] e = edge(full);
            // A transparent edge (a logo on nothing) gets a neutral dark disc, not the logo's own colour.
            p.setColor(e[0] < 0 || e[4] < 48 ? 0xFF2C3038 : Color.rgb((int) e[0], (int) e[1], (int) e[2]));
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
            float in = size * 0.15f;
            c.drawBitmap(full, null, new RectF(in, in, size - in, size - in), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        full.recycle();
        return b;
    }

    /** A full-bleed square bitmap cropped to a circle of `size`. */
    static Bitmap circleCrop(Bitmap src, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale((float) size / src.getWidth(), (float) size / src.getHeight());
        sh.setLocalMatrix(m);
        p.setShader(sh);
        new Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return b;
    }

    /** True when the picture is (nearly) one colour. */
    static boolean flat(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 12, 12, true);
        int first = small.getPixel(6, 6);
        float diff = 0;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = small.getPixel(x, y);
                diff += Math.abs(Color.red(p) - Color.red(first)) + Math.abs(Color.green(p) - Color.green(first))
                        + Math.abs(Color.blue(p) - Color.blue(first));
            }
        }
        small.recycle();
        return diff / 144 < 12;
    }

    /** A vivid colour from the picture, for its glow. */
    static int mainColor(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 12, 12, true);
        float[] hsv = new float[3];
        int best = 0xFF8899AA;
        float score = -1;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = small.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                Color.colorToHSV(p, hsv);
                float sc = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (sc > score) {
                    score = sc;
                    best = p;
                }
            }
        }
        small.recycle();
        if (score < 0.2f) return 0xFFB8C4D6;   // nearly grey: a cool white glow
        Color.colorToHSV(best, hsv);
        hsv[2] = Math.max(hsv[2], 0.7f);
        return Color.HSVToColor(hsv);
    }

    // ---- curved names ----------------------------------------------------------------------------

    /** A name set along an arc around the wheel centre; left/top place the bitmap relative to that centre. */
    static final class Label {
        final Bitmap bitmap;
        final float left, top;

        Label(Bitmap bitmap, float left, float top) {
            this.bitmap = bitmap;
            this.left = left;
            this.top = top;
        }
    }

    /**
     * Text centred on the segment at `deg` (clockwise from the top) on an 80 degree arc. Upper half: clockwise
     * on `upperR`; lower half: anticlockwise on `lowerR`, so it reads upright.
     */
    static Label label(String text, Paint font, float deg, float upperR, float lowerR, float shadow) {
        boolean lower = deg > 90.5f && deg < 269.5f;
        float r = lower ? lowerR : upperR;
        Path path = new Path();
        RectF oval = new RectF(-r, -r, r, r);
        float centre = deg - 90;   // Android angles start at 3 o'clock
        if (lower) path.addArc(oval, centre + 40, -80);
        else path.addArc(oval, centre - 40, 80);
        Paint p = new Paint(font);
        p.setColor(Color.WHITE);
        if (shadow > 0) p.setShadowLayer(shadow * 1.5f, 0, shadow * 0.4f, 0xCC000000);
        float len = (float) (r * Math.toRadians(80));
        float h = (len - p.measureText(text)) / 2f;
        RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        float m = p.getTextSize() * 1.4f + shadow * 2;
        bounds.inset(-m, -m);
        Bitmap b = Bitmap.createBitmap(Math.round(bounds.width()), Math.round(bounds.height()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-bounds.left, -bounds.top);
        c.drawTextOnPath(text, path, h, 0, p);
        return new Label(b, bounds.left, bounds.top);
    }

    // ---- glass -----------------------------------------------------------------------------------

    /**
     * The glass backing: the wallpaper shrunk to `w` pixels wide, strongly blurred and 1.6x saturated, stored
     * as dithered RGB_565 to halve its memory. Done once per wallpaper.
     */
    static Bitmap glassCopy(Bitmap wall, int w) {
        return glassCopy(wall, w, 1.6f);
    }

    /** The same at a chosen saturation: the redesign's frost keeps the picture's own colours (0.92). */
    static Bitmap glassCopy(Bitmap wall, int w, float saturation) {
        int h = Math.round((float) w * wall.getHeight() / wall.getWidth());
        int radius = Math.round(w / 34f);   // the same blur relative to the picture at any size
        Bitmap small = Bitmap.createScaledBitmap(wall, w, h, true);
        if (small.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap argb = small.copy(Bitmap.Config.ARGB_8888, true);
            if (small != wall) small.recycle();
            small = argb;
        } else if (small == wall || !small.isMutable()) {
            small = small.copy(Bitmap.Config.ARGB_8888, true);
        }
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, w, h, radius, true);
            boxBlur(px, w, h, radius, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setDither(true);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(small, 0, 0, p);
        small.recycle();
        return out;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[n];
        for (int li = 0; li < lines; li++) {
            for (int i = 0; i < n; i++) line[i] = px[horizontal ? li * w + i : i * w + li];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = line[Math.max(0, Math.min(n - 1, i))];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            int div = 2 * r + 1;
            for (int i = 0; i < n; i++) {
                px[horizontal ? li * w + i : i * w + li] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = line[Math.min(n - 1, i + r + 1)], sub = line[Math.max(0, i - r)];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** A soft rounded-rectangle glow (alpha only), `blur` pixels wide, centred in its bitmap. */
    static Bitmap roundGlow(float w, float h, float radius, float blur) {
        int pad = Math.round(blur * 2.5f);
        Bitmap b = Bitmap.createBitmap(Math.round(w) + pad * 2, Math.round(h) + pad * 2, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, blur), BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawRoundRect(pad, pad, pad + w, pad + h, radius, radius, p);
        return b;
    }

    /** A soft round shadow (alpha only), centred in its bitmap. */
    static Bitmap circleShadow(float radius, float sigma) {
        float blur = Math.max(0.5f, (sigma - 0.5f) / 0.57735f);
        int s = Math.round((radius + sigma * 3) * 2);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawCircle(s / 2f, s / 2f, radius, p);
        return b;
    }

}
