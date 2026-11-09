package com.nareshjagriya.tvtown;

import java.util.List;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The redesign's shared selection and buttons. What is selected sits on a frosted white plate with one fine light
 * edge along its top, never a coloured ring; neon stays on the main buttons, which lift when selected. Glass itself is
 * a pre-blurred picture, never a live blur, so it stays cheap on a 1 GB TV.
 */
final class Frost {

    private Frost() {
    }

    static final int INK = 0xFFF4F4F1, MUTE = 0xDBF4F4F1, FAINT = 0xADF4F4F1, CYAN = 0xFF6FDCFF, PINK = 0xFFF06BC4, GREEN = 0xFF22C55E;
    static final long GLIDE_MS = 180;

    /** Customize > Home style: true is the picture as a card beside the words, on plain black. */
    static boolean lighter;

    static void init(android.content.Context ctx) {
        lighter = ctx.getSharedPreferences("home", android.content.Context.MODE_PRIVATE).getBoolean("appearance_lighter", false);
    }

    static void setLighter(android.content.Context ctx, boolean on) {
        lighter = on;
        ctx.getSharedPreferences("home", android.content.Context.MODE_PRIVATE).edit().putBoolean("appearance_lighter", on).apply();
    }

    /** The page behind everything. */
    static int base() {
        return lighter ? 0xFF000000 : 0xFF0B0B0D;
    }

    /** Over a screen, before a pop-up: plain black in Picture card, else the given dimming. */
    static int dim(int colour) {
        return lighter ? 0xFF000000 : colour;
    }

    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path clip = new Path();

    static {
        line.setStyle(Paint.Style.STROKE);
    }

    /** TV Town's own focus colours: royal violet, and the pink the main buttons blend into. */
    static final int VIOLET = 0xFF7B5CF0, VIOLET_DEEP = 0xFF5B3FD1, VIOLET_PINK = 0xFFD9468F;

    /**
     * What's selected, everywhere but the dock: a pane of clear glass laid over it, landing at once (no slide, so no
     * trail): see-through white, light falling on its top half, a crisp white edge all round, no glow.
     */
    static void plate(Canvas c, RectF r, float rad, float k) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x38FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        gloss(c, r, rad);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1.5f, 1.6f * k));
        line.setColor(0x8CFFFFFF);
        float in = 0.8f * k;
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad - in, rad - in, line);
        topEdge(c, r, rad, k, 0xF2FFFFFF);
    }

    /**
     * What's selected in a list going down (Customize, Discover's list, menus): the same clear glass, lifted a little
     * toward you with a soft shadow under it, as if pulled out of the stack.
     */
    static void lift(Canvas c, RectF r, float rad, float k) {
        float gx = r.width() * 0.015f, up = 2 * k;
        RectF g = new RectF(r.left - gx, r.top - gx - up, r.right + gx, r.bottom + gx - up);
        fill.setShader(null);   // the shadow: a few dark layers spreading down (no blur, which is slow on a small TV)
        for (int i = 4; i >= 1; i--) {
            float s = i * 3 * k;
            fill.setColor(Color.argb(28, 0, 0, 0));
            c.drawRoundRect(g.left - s * 0.5f, g.top + s, g.right + s * 0.5f, g.bottom + s * 1.6f, rad + s, rad + s, fill);
        }
        fill.setColor(0xF0101013);   // solid under the glass, so the shadow doesn't show through it
        c.drawRoundRect(g, rad, rad, fill);
        plate(c, g, rad, k);
    }

    /** The shine of glass: a bright top half that fades out, as light falls on a glass pane from above. */
    private static void gloss(Canvas c, RectF r, float rad) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f, new int[]{0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
    }

    // ---- the selection glides, everywhere, like the dock's -------------------------------------------------
    // Each view calls frame(this) as it starts drawing. A plate drawn this frame is matched to one of about the same
    // size drawn last frame; when it has moved, it slides from where it was shown to where it is now (Frost.GLIDE_MS),
    // and the view keeps drawing until it lands. Lite mode: no sliding.

    private static final class Glide {
        final RectF from = new RectF(), to = new RectF(), shown = new RectF();
        long at;
    }

    private static android.view.View frameView;
    private static List<Glide> lastFrame = new java.util.ArrayList<>(), thisFrame = new java.util.ArrayList<>();
    private static final java.util.Map<android.view.View, List<Glide>> byView = new java.util.WeakHashMap<>();

    /** Called by a view at the start of its onDraw, so its selections glide. */
    static void frame(android.view.View v) {
        if (frameView != null) byView.put(frameView, thisFrame);
        frameView = v;
        List<Glide> before = byView.get(v);
        lastFrame = before != null ? before : new java.util.ArrayList<>();
        thisFrame = new java.util.ArrayList<>();
        byView.put(v, thisFrame);
    }

    /** True while a cached picture is recorded (HomeView.section): no sliding inside it. */
    static boolean noGlide;

    private static RectF glidePlate(RectF target) {
        if (frameView == null || Lite.on || noGlide) return target;
        long now = android.os.SystemClock.uptimeMillis();
        Glide best = null;
        float bestD = Float.MAX_VALUE;
        for (Glide g : lastFrame) {   // the nearest one of about the same size, not taken yet
            float rw = target.width() / Math.max(1, g.to.width()), rh = target.height() / Math.max(1, g.to.height());
            if (rw < 0.6f || rw > 1.65f || rh < 0.6f || rh > 1.65f) continue;
            float d = Math.abs(target.centerX() - g.to.centerX()) + Math.abs(target.centerY() - g.to.centerY());
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        Glide g = new Glide();
        g.to.set(target);
        float far = 1.6f * (target.width() + target.height());
        if (best == null || best != null && bestD > far && !best.to.equals(target)) {
            // New, or far away: a quick pop where it lands (from a little smaller) rather than a long slide.
            if (best != null) lastFrame.remove(best);
            float dx = target.width() * 0.04f, dy = target.height() * 0.04f;
            g.from.set(target.left + dx, target.top + dy, target.right - dx, target.bottom - dy);
            g.at = best == null && lastFrame.isEmpty() && thisFrame.isEmpty() ? 0 : now;
        } else {
            lastFrame.remove(best);
            if (best.to.equals(target)) {   // still going where it was going
                g.from.set(best.from);
                g.at = best.at;
            } else {   // a new place: from where it is shown now
                g.from.set(best.shown);
                g.at = now;
            }
        }
        float f = g.at == 0 ? 1 : Math.max(0, Math.min(1, (now - g.at) / (float) FOCUS_MS));
        f = 1 - (1 - f) * (1 - f) * (1 - f) * (1 - f);   // quick out of the start, soft landing
        g.shown.set(g.from.left + (g.to.left - g.from.left) * f, g.from.top + (g.to.top - g.from.top) * f,
                g.from.right + (g.to.right - g.from.right) * f, g.from.bottom + (g.to.bottom - g.from.bottom) * f);
        thisFrame.add(g);
        if (f < 1) frameView.postInvalidateOnAnimation();
        return g.shown;
    }

    /** How long the selection takes to reach a new place. */
    static final long FOCUS_MS = 120;

    /**
     * A soft light of the selection's colour round it, as on the dock: two faint rings outside it (no blur, which is
     * slow on a small TV). None in Lite mode.
     */
    static void glow(Canvas c, RectF r, float rad, float k, int colour) {
        if (Lite.on) return;
        fill.setShader(null);
        int rgb = colour & 0x00FFFFFF;
        float a = 9 * k, b = 4 * k;
        fill.setColor(rgb | 0x1F000000);
        c.drawRoundRect(r.left - a, r.top - a, r.right + a, r.bottom + a, rad + a, rad + a, fill);
        fill.setColor(rgb | 0x33000000);
        c.drawRoundRect(r.left - b, r.top - b, r.right + b, r.bottom + b, rad + b, rad + b, fill);
    }

    /**
     * The main button of a page (Play, Trailer, Speed it all up, Surprise me): deep jewel glass, dark violet into plum,
     * a sharp shine on its top half and a fine lilac edge. No glow. Selected, it brightens and gains a white edge.
     */
    static void hero(Canvas c, RectF r, float rad, boolean selected, float k) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, selected ? 0xFF5D45D6 : 0xFF4B34B8, selected ? 0xFF973A8C : 0xFF7A2A72,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        c.save();   // the shine: the top half, with a crisp line where it ends, like a glass bead
        c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.48f);
        fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.48f, 0x38FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        c.restore();
        fill.setShader(null);
        line.setShader(null);
        float in = 0.8f * k;
        if (selected) {
            line.setStrokeWidth(Math.max(1.5f, 2 * k));
            line.setColor(0xD9FFFFFF);
        } else {
            line.setStrokeWidth(Math.max(1, 1.2f * k));
            line.setColor(0x4DC8B4FF);
        }
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad - in, rad - in, line);
        topEdge(c, r, rad, k, selected ? 0xFFFFFFFF : 0x8CFFFFFF);
    }

    /** The colour of a main button's icon: a light lilac, so it reads as part of the jewel. */
    static final int HERO_ICON = 0xFFE7DCFF;

    /** The colour of what's selected, everywhere: taken from the picture on Home, so every screen matches it. */
    static volatile int accentNow = 0xFF8C72F0;

    /** Glass colours for what's selected: deep enough for white words, the idea of Stremio Origin's lilac pill. */
    static final int LILAC = 0xFF8C72F0, SKY = 0xFF3D8FE8, ROSE = 0xFFD94FA8, TEAL = 0xFF1FA89A, AMBER = 0xFFD98A2B, CORAL = 0xFFE0605C;

    /** The selected pane in a colour (its alpha is ignored), with sharp edges and no glow or shadow round it. */
    static void plate(Canvas c, RectF r, float rad, float k, int colour) {
        int rgb = colour & 0x00FFFFFF;
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{0xCC000000 | rgb, 0x99000000 | rgb, 0xB3000000 | rgb},
                new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        rim(c, r, rad, k);
    }

    /** Clear glass over a picture that is selected: a shine fading down from the top and a crisp white frame. */
    static void sheen(Canvas c, RectF r, float rad, float k) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.45f, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        line.setShader(null);
        line.setStrokeWidth(Math.max(2, 2 * k));
        line.setColor(0xD9FFFFFF);
        float in = k;
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad - in, rad - in, line);
    }

    /** One fine light edge across the top, following the corners and fading out down the sides. */
    static void topEdge(Canvas c, RectF r, float rad, float k, int colour) {
        line.setStrokeWidth(Math.max(1, k));
        line.setColor(Color.WHITE);
        line.setShader(new LinearGradient(0, r.top, 0, r.top + Math.max(rad, 2 * k), colour, colour & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, rad, rad, line);
        line.setShader(null);
    }

    /** The main button: flat colour on the glass with no glow; selected, it brightens and gains a clear light edge. */
    static void neon(Canvas c, RectF r, float rad, boolean selected, float k) {
        ghost(c, r, rad, selected, k);   // only what is selected has colour, so one thing at a time looks selected
    }

    /** The second button: clear glass, or the frosted plate when selected. */
    static void ghost(Canvas c, RectF r, float rad, boolean selected, float k) {
        if (selected) {
            plate(c, r, rad, k);
            return;
        }
        fill.setShader(null);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(r, rad, rad, fill);
        sheenFrom(c, r, rad, 0x2EFFFFFF);
        rim(c, r, rad, k);
    }

    /**
     * Apple-style glass, the look of Stremio Origin's bar: the blurred picture really shows through, under a thin veil so
     * white words stay readable, with a soft sheen from above and a bright rim lit from the top left. No live blur: the
     * picture was blurred once, so this is cheap on a small TV.
     */
    static void liquid(Canvas c, RectF r, float rad, @androidx.annotation.Nullable android.graphics.Bitmap frost, RectF screen, Paint bitmapPaint, float k) {
        if (Lite.on) {   // Lite: no picture behind; a rich, soft tint of the picture's colour, lighter at the top
            fill.setColor(Color.WHITE);
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, (0xE6 << 24) | (liteTone(0.36f) & 0xFFFFFF), (0xE6 << 24) | (liteTone(0.24f) & 0xFFFFFF), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            rim(c, r, rad, k);
            return;
        }
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        if (frost != null) {   // blurred enough that nothing behind competes with the words
            int was = bitmapPaint.getAlpha();
            bitmapPaint.setAlpha(LIQUID_FROST);
            c.drawBitmap(frost, null, screen, bitmapPaint);
            bitmapPaint.setAlpha(was);
            c.drawColor(veil(r, frost, screen));
        } else c.drawColor(0x8017171A);
        c.restore();
        sheenFrom(c, r, rad, 0x24FFFFFF);
        rim(c, r, rad, k);
    }

    /** The most colourful part of a blurred picture, deep enough for white words on it; lilac for a colourless one. */
    static int accent(android.graphics.Bitmap frost) {
        float[] hsv = new float[3], best = null;
        float top = 0.12f;
        try {
            for (int y = 1; y < 9; y++) for (int x = 1; x < 16; x++) {
                Color.colorToHSV(frost.getPixel(frost.getWidth() * x / 16, frost.getHeight() * y / 9), hsv);
                float score = hsv[1] * hsv[2];
                if (score > top) {
                    top = score;
                    best = hsv.clone();
                }
            }
        } catch (RuntimeException e) {
            return LILAC;
        }
        if (best == null) return LILAC;
        best[1] = Math.max(0.55f, Math.min(0.85f, best[1]));
        best[2] = 0.72f;
        return Color.HSVToColor(best);
    }

    private static final java.util.Map<Bitmap, Boolean> darkLogos = new java.util.WeakHashMap<>();

    /** A logo too dark to read on a picture isn't used: its name is set in type instead. Remembered per picture. */
    static boolean darkLogo(Bitmap b) {
        Boolean have = darkLogos.get(b);
        if (have != null) return have;
        float sum = 0;
        int n = 0;
        for (int y = 1; y < 12; y++) for (int x = 1; x < 24; x++) {
            int p = b.getPixel(b.getWidth() * x / 24, b.getHeight() * y / 12);
            if (Color.alpha(p) < 128) continue;
            sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
            n++;
        }
        boolean dark = n == 0 || sum / n < 0.4f;
        darkLogos.put(b, dark);
        return dark;
    }

    /** How much of the blurred picture covers the sharp one in a glass panel (0-255): lower is clearer. */
    private static final int LIQUID_FROST = 225;
    /** The brightness (0-1) the glass is brought down to, so white words read on any picture. */
    private static final float GLASS_BRIGHTNESS = 0.30f;

    /**
     * The dark tint this pane needs: only as much as what's behind it calls for, so glass over a dark picture stays
     * clear and glass over a bright one is darkened. Measured from the small blurred picture, once per picture.
     */
    private static int veil(RectF r, android.graphics.Bitmap frost, RectF screen) {
        float sum = 0;
        int n = 0;
        try {
            float sx = frost.getWidth() / screen.width(), sy = frost.getHeight() / screen.height();
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 4; j++) {
                    int x = Math.max(0, Math.min(frost.getWidth() - 1, (int) ((r.left + r.width() * (i + 0.5f) / 8) * sx)));
                    int y = Math.max(0, Math.min(frost.getHeight() - 1, (int) ((r.top + r.height() * (j + 0.5f) / 4) * sy)));
                    int p = frost.getPixel(x, y);
                    sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
                    n++;
                }
            }
        } catch (RuntimeException e) {
            return 0x73060607;
        }
        float a = 1 - GLASS_BRIGHTNESS / Math.max(0.01f, sum / n);
        return (Math.round(Math.max(0.12f, Math.min(0.78f, a)) * 255) << 24) | 0x060607;
    }

    /** Light falling on the glass from above. */
    private static void sheenFrom(Canvas c, RectF r, float rad, int top) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{top, 0x05FFFFFF, 0x12FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
    }

    /**
     * Glass's edge: bright where the light lands (top left and bottom right), with the faint pink and blue a thick glass
     * edge splits light into, and a soft inner band that gives the pane its thickness.
     */
    static void rim(Canvas c, RectF r, float rad, float k) {
        line.setShader(null);
        line.setStrokeWidth(Math.max(1, k));   // a fine dark line outside, so the pane stands out on a bright picture too
        line.setColor(0x26000000);
        c.drawRoundRect(r.left - k, r.top - k, r.right + k, r.bottom + k, rad + k, rad + k, line);
        line.setColor(Color.WHITE);
        line.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{0x8CFFFFFF, 0x26FFD0F0, 0x12FFFFFF, 0x26B0E8FF, 0x59FFFFFF},
                new float[]{0, 0.22f, 0.5f, 0.78f, 1}, Shader.TileMode.CLAMP));
        float in = 0.5f * k;
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad, rad, line);
        line.setShader(null);
    }

    /** Behind a pop-up: Home's picture, blurred, under a light veil, so the pop-up reads as glass over Home. */
    static void scrim(Canvas c, RectF screen, float e, Paint bitmapPaint) {
        fill.setShader(null);
        Bitmap frost = behind != null ? behind : GlassSheet.frost;
        if (frost != null) {
            bitmapPaint.setAlpha(Math.round(255 * e));
            c.drawBitmap(frost, null, screen, bitmapPaint);
            bitmapPaint.setAlpha(255);
            fill.setColor(Color.argb(Math.round(70 * e), 8, 8, 9));
        } else fill.setColor((Math.round(255 * e) << 24) | (base() & 0x00FFFFFF));
        c.drawRect(screen, fill);
    }

    /** The whole screen behind a pop-up: the blurred picture, as scrim gives it. */
    static void blurBehind(Canvas c, int width, int height, Paint bitmapPaint) {
        scrim(c, new RectF(0, 0, width, height), 1, bitmapPaint);
    }

    /** The screen as it is now, small and blurred: what every pop-up shows behind it. Made when the screen is at rest. */
    static Bitmap behind;
    private static boolean capturing;

    static void capture(android.app.Activity a) {
        capture(a, null);
    }

    /** As capture, then `done` on the main thread, whether or not a new picture came of it. */
    static void capture(android.app.Activity a, @androidx.annotation.Nullable Runnable done) {
        if (done == null && (TvKeyboard.isOpen() || ControlCenter.isOpen())) return;   // these must not end up in their own background
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable finish = () -> {
            if (done != null) main.post(done);
        };
        android.view.View v = a == null || a.isFinishing() || a.getWindow() == null ? null : a.getWindow().peekDecorView();
        if (capturing || v == null || v.getWidth() == 0 || !v.isAttachedToWindow()) {
            finish.run();
            return;
        }
        capturing = true;
        Bitmap shot = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(a.getWindow(), shot, r -> {
                if (r != android.view.PixelCopy.SUCCESS) {
                    capturing = false;
                    finish.run();
                    return;
                }
                new Thread(() -> {
                    Bitmap b = null;
                    try {
                        b = WheelArt.glassCopy(shot, 320, 0.9f);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        android.util.Log.w("TVTown", "capture", e);
                    }
                    if (b != null) behind = b;
                    capturing = false;
                    finish.run();
                }, "behind").start();
            }, main);
        } catch (RuntimeException e) {
            capturing = false;
            finish.run();
        }
    }

    /** A menu dropping from a bar: the blurred screen inside it, a veil, a fine edge. */
    static void dropdown(Canvas c, RectF r, float rad, RectF screen, Paint bitmapPaint, float k) {
        fill.setShader(null);   // solid underneath: nothing of the covers shows through a menu
        fill.setColor(0xFF060607);
        c.drawRoundRect(r, rad, rad, fill);
        panel(c, r, rad, 1, k);   // the same shiny black as every pop-up
    }

    /**
     * A pop-up, only as large as it needs: polished black, like a piano lid. Deep black, a touch lighter at the top, a
     * faint band of light across it and one fine light edge. Black in Lite mode too (it costs nothing).
     */
    static void panel(Canvas c, RectF r, float rad, float e, float k) {
        int a = Math.round(255 * e);
        int saved = a < 255 ? c.saveLayerAlpha(r.left - 4 * k, r.top - 4 * k, r.right + 4 * k, r.bottom + 4 * k, a) : -1;
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0xF71A1A1F, 0xF70C0C0F, 0xF7060607},
                new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        float w = r.width(), h = r.height();   // the band of light, from top left to bottom right
        fill.setShader(new LinearGradient(r.left + w * 0.30f, r.top, r.left + w * 0.30f + h * 0.55f, r.top + h * 0.55f,
                new int[]{0x00FFFFFF, 0x10FFFFFF, 0x00FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1, k));
        line.setColor(0x24FFFFFF);
        c.drawRoundRect(r.left + 0.5f * k, r.top + 0.5f * k, r.right - 0.5f * k, r.bottom - 0.5f * k, rad, rad, line);
        topEdge(c, r, rad, k, 0x66FFFFFF);
        if (saved >= 0) c.restoreToCount(saved);
    }


    /** A whole page in polished black: a little lighter at the top, a faint band of light across it. */
    static void page(Canvas c, RectF s) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, s.top, 0, s.bottom, new int[]{0xFF141418, 0xFF0A0A0C, 0xFF050506},
                new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(s, fill);
        fill.setShader(new LinearGradient(s.left + s.width() * 0.25f, s.top, s.left + s.width() * 0.25f + s.height() * 0.6f, s.top + s.height() * 0.6f,
                new int[]{0x00FFFFFF, 0x0AFFFFFF, 0x00FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(s, fill);
        fill.setShader(null);
    }

    /** A bar or an empty card on a black page: smoky glass, a little lighter than the black, with a fine edge. */
    static void surface(Canvas c, RectF r, float rad, float k) {
        fill.setShader(null);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(r, rad, rad, fill);
        sheenFrom(c, r, rad, 0x1FFFFFFF);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1, k));
        line.setColor(0x1FFFFFFF);
        c.drawRoundRect(r.left + 0.5f * k, r.top + 0.5f * k, r.right - 0.5f * k, r.bottom - 0.5f * k, rad, rad, line);
        topEdge(c, r, rad, k, 0x4DFFFFFF);
    }

    /**
     * The search bar, the same on every page: smoked glass a step lighter than what's around it (a black box looked
     * like a hole on a TV), the magnifier in a small jewel circle, then the words: what was searched in white, or a
     * hint. Selected: clear glass with a white edge, a typing line and an "OK to type" tag on the right.
     * `words` gives the font and size; its colour is set here and put back.
     */
    static void searchBar(Canvas c, RectF r, float k, boolean selected, String text, boolean isHint, Paint words) {
        float rad = r.height() / 2;
        fill.setShader(null);   // a dark base first, so the words read over a bright picture too
        fill.setColor(0x73000000);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, selected ? 0x47FFFFFF : 0x29FFFFFF, selected ? 0x29FFFFFF : 0x17FFFFFF,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        line.setShader(null);
        float in = 0.8f * k;
        line.setStrokeWidth(selected ? Math.max(2, 2.2f * k) : Math.max(1, 1.2f * k));
        line.setColor(selected ? 0xE6FFFFFF : 0x38FFFFFF);
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad - in, rad - in, line);
        topEdge(c, r, rad, k, selected ? 0xFFFFFFFF : 0x73FFFFFF);
        // the magnifier in a jewel circle
        float d = r.height() - 16 * k, cx = r.left + 8 * k + d / 2, cy = r.centerY();
        RectF disc = new RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
        fill.setShader(new LinearGradient(disc.left, disc.top, disc.right, disc.bottom, 0xFF4B34B8, 0xFF7A2A72, Shader.TileMode.CLAMP));
        c.drawOval(disc, fill);
        fill.setShader(null);
        line.setStrokeWidth(Math.max(1, k));
        line.setColor(0x4DC8B4FF);
        c.drawOval(disc, line);
        Bitmap mag = searchIcon(Math.round(d * 0.5f));
        iconPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(HERO_ICON, android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(mag, cx - mag.getWidth() / 2f, cy - mag.getHeight() / 2f, iconPaint);
        // the words, and when selected the typing line and what OK does
        float x = disc.right + 14 * k, right = r.right - rad * 0.6f;
        if (selected) {
            tagPaint.setTextSize(17 * k);
            String tag = "OK to type";
            float tw = tagPaint.measureText(tag) + 22 * k, th = 30 * k;
            RectF t = new RectF(r.right - 12 * k - tw, cy - th / 2, r.right - 12 * k, cy + th / 2);
            fill.setColor(0x59000000);
            c.drawRoundRect(t, th / 2, th / 2, fill);
            c.drawText(tag, t.left + 11 * k, cy + 6 * k, tagPaint);
            right = t.left - 10 * k;
            fill.setColor(Color.WHITE);
            c.drawRect(x, cy - 13 * k, x + 2 * k, cy + 13 * k, fill);
            x += 8 * k;
        }
        int was = words.getColor();
        if (isHint && selected && words.measureText(text) > right - x) text = "Search";   // the tag needs the room
        words.setColor(isHint ? (selected ? 0xE6FFFFFF : 0xC7FFFFFF) : Color.WHITE);
        String fitted = android.text.TextUtils.ellipsize(text, new android.text.TextPaint(words), Math.max(0, right - x),
                android.text.TextUtils.TruncateAt.END).toString();
        c.drawText(fitted, x, cy + words.getTextSize() * 0.36f, words);
        words.setColor(was);
    }

    private static final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        tagPaint.setColor(Color.WHITE);
        tagPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
    }

    private static Bitmap searchIconBmp;

    private static Bitmap searchIcon(int px) {
        if (searchIconBmp == null || searchIconBmp.getWidth() != px && searchIconBmp.getHeight() != px) searchIconBmp = LineIcon.icon("search", px);
        return searchIconBmp;
    }

    /** The one tick of the app: a small green circle with a white tick. */
    static void tick(Canvas c, float cx, float cy, float r, float k) {
        fill.setShader(null);
        fill.setColor(GREEN);
        c.drawCircle(cx, cy, r, fill);
        line.setShader(null);
        line.setColor(Color.WHITE);
        line.setStrokeWidth(Math.max(1.4f, r * 0.17f));
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - r * 0.42f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.36f, line);
        c.drawLine(cx - r * 0.1f, cy + r * 0.36f, cx + r * 0.46f, cy - r * 0.32f, line);
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    /** The empty circle of a choice not ticked. */
    static void untick(Canvas c, float cx, float cy, float r, float k) {
        line.setShader(null);
        line.setColor(0x57FFFFFF);
        line.setStrokeWidth(Math.max(1, 2 * k));
        c.drawCircle(cx, cy, r, line);
    }

    /**
     * Lite mode's colour: the picture's own colour, softened (gentle saturation) at the given brightness (0-1), so plain
     * glass still feels like the picture behind it: deep enough for white words, never grey.
     */
    static int liteTone(float brightness) {
        float[] hsv = new float[3];
        Color.colorToHSV(accentNow, hsv);
        hsv[1] = Math.min(hsv[1], 0.42f);
        hsv[2] = brightness;
        return Color.HSVToColor(hsv);
    }

    /** The eased position of a glide from a to b, started at `at`. */
    static float glide(float a, float b, long now, long at) {
        if (Lite.on) return b;   // Lite: straight there
        float f = Math.max(0, Math.min(1, (now - at) / (float) GLIDE_MS));
        f = 1 - (1 - f) * (1 - f) * (1 - f);
        return a + (b - a) * f;
    }
}
