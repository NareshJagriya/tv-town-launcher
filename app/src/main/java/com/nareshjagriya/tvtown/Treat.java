package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

/**
 * Support TV Town: its own place in Settings, drawn right on the page. A few words, India (UPI) or anywhere else
 * (PayPal), a few treats, and a QR code that opens the payment on a phone with the amount filled in. Right moves in
 * from the categories; Left and Right pick; Up and Down switch between India / anywhere else and the treats.
 */
final class Treat implements GlassSheet.Pane {

    static final String UPI = "nareshjagriya@okhdfcbank", PAYPAL = "https://paypal.me/jagriya";
    static final String TITLE = "Support TV Town";
    private static final String[][] INDIA = {{"☕", "₹99", "99"}, {"🥟", "₹199", "199"}, {"🍿", "₹499", "499"},
            {"🎬", "₹999", "999"}, {"✨", L10n.t("Any"), ""}};
    private static final String[][] WORLD = {{"☕", "$3", "3"}, {"🍕", "$5", "5"}, {"🍿", "$10", "10"},
            {"🎬", "$25", "25"}, {"✨", L10n.t("Any"), ""}};

    private final float k;
    private final Paint title, line, small, chip, fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path();
    private boolean world;
    private int row = 1, pick;   // row 0: India / anywhere else; row 1: the treats
    private Bitmap qr;
    private String qrFor = "";

    Treat(Context c) {
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        NoirKit n = new NoirKit(c, k);
        title = n.inter(40, 800, -0.01f, Color.WHITE);
        line = n.inter(27, 600, 0, Color.WHITE);
        small = n.inter(22, 500, 0, Frost.MUTE);
        chip = n.inter(22, 700, 0, Color.WHITE);
        chip.setTextAlign(Paint.Align.CENTER);
        edge.setStyle(Paint.Style.STROKE);
    }

    /** The link a phone opens for this treat. */
    static String link(boolean world, String amount) {
        if (world) return PAYPAL + (amount.isEmpty() ? "" : "/" + amount + "USD");
        return "upi://pay?pa=" + UPI + "&pn=TV%20Town&cu=INR&tn=Thanks%20for%20TV%20Town" + (amount.isEmpty() ? "" : "&am=" + amount);
    }

    private String[][] treats() {
        return world ? WORLD : INDIA;
    }

    @Override
    public void draw(Canvas c, RectF area, float e, boolean focused) {
        int a = Math.round(255 * e);   // the same black pop-up as every other section: no colour washed into it

        float x = area.left + 48 * k, y = area.top + 78 * k;
        title.setAlpha(a);
        c.drawText(L10n.t(TITLE), x, y, title);
        line.setAlpha(a);
        c.drawText(L10n.t("Happiness is creating something you love."), x, y + 58 * k, line);
        c.drawText(L10n.t("Even better when someone loves it too!"), x, y + 96 * k, line);
        small.setTextSize(23 * k);
        small.setColor(0xE6FFFFFF);
        small.setAlpha(Math.round(0xE6 * e));
        c.drawText(L10n.t("Want to make a creator smile? You know what to do. 😁"), x, y + 138 * k, small);
        small.setColor(Frost.MUTE);
        small.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
        c.drawText(L10n.t("No pressure! ❤️"), x, y + 172 * k, small);

        // left: India or anywhere else, then the treats; right: the QR code
        float qs = 230 * k, qx = area.right - 48 * k - qs, qy = y + 214 * k;
        float colR = qx - 40 * k;
        float ty = y + 214 * k;
        String[] tabs = {L10n.t("🇮🇳  India (UPI)"), L10n.t("🌍  Anywhere else")};
        float tw = (colR - x - 12 * k) / 2;
        for (int i = 0; i < 2; i++) {
            r.set(x + i * (tw + 12 * k), ty, x + i * (tw + 12 * k) + tw, ty + 58 * k);
            boolean on = (i == 1) == world;
            gem(c, r, 29, on, focused && row == 0 && on, e);
            chip.setColor(on ? Color.WHITE : 0xB3FFFFFF);
            chip.setAlpha(Math.round(Color.alpha(chip.getColor()) * e));
            chip.setTextSize(22 * k);
            c.drawText(tabs[i], r.centerX(), r.centerY() + 8 * k, chip);
        }
        String[][] t = treats();
        float cy = ty + 82 * k, gap = 10 * k, cw = (colR - x - gap * (t.length - 1)) / t.length;
        for (int i = 0; i < t.length; i++) {
            r.set(x + i * (cw + gap), cy, x + i * (cw + gap) + cw, cy + 112 * k);
            boolean on = i == pick;
            gem(c, r, 24, on, focused && row == 1 && on, e);
            chip.setColor(Color.WHITE);
            chip.setAlpha(a);
            chip.setTextSize(36 * k);
            c.drawText(t[i][0], r.centerX(), r.top + 52 * k, chip);
            chip.setTextSize(22 * k);
            chip.setColor(on ? Color.WHITE : 0xCCFFFFFF);
            chip.setAlpha(Math.round(Color.alpha(chip.getColor()) * e));
            c.drawText(t[i][1], r.centerX(), r.top + 92 * k, chip);
        }
        small.setTextSize(19 * k);
        small.setColor(0x99FFFFFF);
        small.setAlpha(Math.round(0x99 * e));
        c.drawText(focused ? L10n.t("◄ ► choose    ▲ ▼ India or anywhere else") : L10n.t("► to choose a treat"), x, cy + 150 * k, small);

        String want = link(world, t[pick][2]);
        if (!want.equals(qrFor)) {
            qr = PhoneKeyboard.qrFor(want, Math.round(qs));
            qrFor = want;
        }
        if (qr != null) {
            r.set(qx - 16 * k, qy - 16 * k, qx + qs + 16 * k, qy + qs + 16 * k);
            fill.setColor(Color.WHITE);
            fill.setAlpha(a);
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF7C5CFF, 0xFFFF3C8A, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 26 * k, 26 * k, fill);
            fill.setShader(null);
            r.inset(5 * k, 5 * k);
            fill.setColor(Color.WHITE);
            fill.setAlpha(a);
            c.drawRoundRect(r, 21 * k, 21 * k, fill);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(a);
            c.drawBitmap(qr, qx, qy, p);
        }
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(21 * k);
        small.setColor(Color.WHITE);
        small.setAlpha(a);
        c.drawText(world ? L10n.t("Scan to open PayPal") : L10n.t("Scan with any UPI app"), qx + qs / 2, qy + qs + 52 * k, small);
        small.setTextSize(18 * k);
        small.setColor(Frost.MUTE);
        small.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
        c.drawText(world ? "paypal.me/jagriya" : UPI, qx + qs / 2, qy + qs + 82 * k, small);
        small.setTextSize(21 * k);
        c.drawText(L10n.t("Thank you! 🙏"), qx + qs / 2, qy + qs + 120 * k, small);
        small.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * A choice, drawn as everywhere else: the violet glass when the remote is on it; chosen, a soft violet tint with a
     * light edge; otherwise clear glass.
     */
    private void gem(Canvas c, RectF b, float rad, boolean chosen, boolean focused, float e) {
        if (focused) {
            Frost.plate(c, b, rad * k, k);
        } else if (chosen) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(0x4D * e), 0x7B, 0x5C, 0xF0));
            c.drawRoundRect(b, rad * k, rad * k, fill);
            Frost.rim(c, b, rad * k, k);
        } else Frost.ghost(c, b, rad * k, false, k);
    }

    @Override
    public boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row == 0) {
                    if (!world) return false;   // at the edge: back to the categories
                    world = false;
                } else {
                    if (pick == 0) return false;
                    pick--;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == 0) world = true;
                else pick = Math.min(treats().length - 1, pick + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                row = 0;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = 1;
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (row == 0) world = !world;
                return true;
            default:
                return false;
        }
    }

    /** At most once a month, after two weeks with TV Town: a one-line note on Home that goes by itself. */
    static void maybeNudge(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), first = p.getLong("first_used", 0);
        if (first == 0) {
            p.edit().putLong("first_used", now).apply();
            return;
        }
        if (now - first < 14 * 86_400_000L || now - p.getLong("treat_nudged", 0) < 30 * 86_400_000L) return;
        p.edit().putLong("treat_nudged", now).apply();
        HomeKeyService.note(L10n.t("Enjoying TV Town? 😁 Want to make a creator smile? Settings → Support TV Town"), 7000);
    }
}
