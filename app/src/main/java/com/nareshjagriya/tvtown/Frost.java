package com.nareshjagriya.tvtown;

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

    /** The frosted plate, drawn behind what is selected. */
    static void plate(Canvas c, RectF r, float rad, float k) {
        plate(c, r, rad, k, accentNow);
    }

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

    /** The same light laid over a picture that is selected: a sheen fading down, a fine edge all round, brighter on top. */
    static void sheen(Canvas c, RectF r, float rad, float k) {
        fill.setColor(Color.WHITE);
        fill.setShader(new LinearGradient(r.left, r.top, r.left + r.height() * 0.18f, r.top + r.height() * 0.44f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1, k));
        line.setColor(0x2EFFFFFF);
        c.drawRoundRect(r, rad, rad, line);
        topEdge(c, r, rad, k, 0x99FFFFFF);
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
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        if (behind != null) c.drawBitmap(behind, null, screen, bitmapPaint);
        else c.drawColor(0xFF111114);
        c.drawColor(0xD90C0C0E);
        c.restore();
        topEdge(c, r, rad, k, 0x57FFFFFF);
    }

    /** A pop-up, only as large as it needs: dark see-through glass and one fine light edge. */
    static void panel(Canvas c, RectF r, float rad, float e, float k) {
        Bitmap frost = behind != null ? behind : GlassSheet.frost;
        int a = Math.round(255 * e);
        int saved = a < 255 ? c.saveLayerAlpha(r.left - 4 * k, r.top - 4 * k, r.right + 4 * k, r.bottom + 4 * k, a) : -1;
        panelScreen.set(0, 0, c.getWidth(), c.getHeight());
        liquid(c, r, rad, frost, panelScreen, panelBits, k);
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(0x66060607);   // a pop-up holds a lot of words: a little darker than the glass on Home
        c.restore();
        if (saved >= 0) c.restoreToCount(saved);
    }

    private static final RectF panelScreen = new RectF();
    private static final Paint panelBits = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

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

    /** The eased position of a glide from a to b, started at `at`. */
    static float glide(float a, float b, long now, long at) {
        float f = Math.max(0, Math.min(1, (now - at) / (float) GLIDE_MS));
        f = 1 - (1 - f) * (1 - f) * (1 - f);
        return a + (b - a) * f;
    }
}
