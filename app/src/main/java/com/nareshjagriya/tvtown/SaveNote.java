package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The downloader's only face: a small glass card in the corner, over whatever is on. A note, the Audio / Video choice
 * (it starts by itself), or the QR code for pasting a link. Other apps' pictures can't be read to blur them, so here
 * the glass is a little more solid than TV Town's own.
 */
final class SaveNote extends View {

    interface Chosen {
        void start(boolean video);
    }

    private static final int NOTE = 0, CHOICE = 1, QR = 2, ASK = 3;
    private static final long CHOICE_MS = 6000, ASK_MS = 12_000;
    // ASK (OK pressed twice): the format row (focus starts here, so pressing OK again only switches MP3 / Video),
    // then Download / Cancel. Nothing saves by itself: left alone it closes.
    private int askRow, askBtn;

    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big, small;
    private final float k;
    private final Runnable closer = this::close, starter = this::start;
    private boolean shown;
    private int mode = NOTE;
    private String title = "", by = "";
    private boolean video;
    private Chosen chosen;
    private Runnable cancelled;
    private Bitmap code;
    private int w, h;

    SaveNote(Context c, WindowManager wm) {
        super(c);
        this.wm = wm;
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        plate.setColor(0xE6141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, 1.5f * k));
        edge.setColor(0x38FFFFFF);
        big = Theme.font(c, 30 * k, 600);
        small = Theme.font(c, 24 * k, 400);
        small.setColor(0xB3FFFFFF);
    }

    /** True while it needs the remote's buttons. */
    boolean wantsKeys() {
        return shown && mode != NOTE;
    }

    void note(String text, long ms) {
        if (wantsKeys()) return;   // never over a choice being made
        mode = NOTE;
        title = text;
        by = "";
        float tw = Math.min(big.measureText(text), 760 * k);
        show(Math.round(tw + 72 * k), Math.round(84 * k));
        main.removeCallbacks(closer);
        main.postDelayed(closer, ms);
    }

    void choice(String title, String by, Chosen chosen, Runnable cancelled) {
        mode = CHOICE;
        this.title = title;
        this.by = by;
        this.chosen = chosen;
        this.cancelled = cancelled;
        video = false;
        show(Math.round(700 * k), Math.round(238 * k));
        main.removeCallbacks(closer);
        main.removeCallbacks(starter);
        main.postDelayed(starter, CHOICE_MS);
    }

    /** "Save this?" in the middle of the screen: MP3 or Video, then Download or Cancel, all with the arrows and OK. */
    void ask(String title, String by, boolean videoFirst, Chosen chosen) {
        mode = ASK;
        this.title = title;
        this.by = by;
        this.chosen = chosen;
        this.cancelled = null;
        video = videoFirst;
        askRow = 0;
        askBtn = 0;
        show(Math.round(820 * k), Math.round(380 * k), Gravity.CENTER);
        main.removeCallbacks(starter);
        main.removeCallbacks(closer);
        main.postDelayed(closer, ASK_MS);
    }

    void qr(Bitmap code, Runnable cancelled) {
        mode = QR;
        this.code = code;
        this.cancelled = cancelled;
        main.removeCallbacks(closer);
        main.removeCallbacks(starter);
        show(Math.round(520 * k), Math.round(500 * k));
    }

    /** Pressed while it wants the buttons; every press is taken, so nothing reaches the app behind. */
    boolean key(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
            Runnable r = cancelled;
            close();
            if (r != null) r.run();
            return true;
        }
        if (mode == ASK) {
            main.removeCallbacks(closer);
            main.postDelayed(closer, ASK_MS);
            boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
            if (kc == KeyEvent.KEYCODE_DPAD_UP) askRow = 0;
            else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (askRow == 0) askBtn = 0;
                askRow = 1;
            } else if (askRow == 0 && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) video = kc == KeyEvent.KEYCODE_DPAD_RIGHT;
            else if (askRow == 0 && ok) video = !video;
            else if (askRow == 1 && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) askBtn = kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            else if (askRow == 1 && ok) {
                if (askBtn == 0) start();
                else close();
                return true;
            }
            invalidate();
            return true;
        }
        if (mode != CHOICE) return true;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            video = kc == KeyEvent.KEYCODE_DPAD_RIGHT;
            main.removeCallbacks(starter);
            main.postDelayed(starter, CHOICE_MS);
            invalidate();
        } else if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            start();
        }
        return true;
    }

    private void start() {
        Chosen c = chosen;
        boolean v = video;
        close();
        if (c != null) c.start(v);
    }

    void close() {
        main.removeCallbacks(closer);
        main.removeCallbacks(starter);
        chosen = null;
        cancelled = null;
        code = null;
        mode = NOTE;
        if (shown) {
            shown = false;
            try {
                wm.removeView(this);
            } catch (Exception ignored) {
                // already gone
            }
        }
        HomeKeyService.keysChanged();
    }

    private void show(int width, int height) {
        show(width, height, Gravity.BOTTOM | Gravity.END);
    }

    private void show(int width, int height, int gravity) {
        w = width;
        h = height;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = gravity == Gravity.CENTER ? 0 : Math.round(48 * k);
        p.y = gravity == Gravity.CENTER ? 0 : Math.round(48 * k);
        try {
            if (shown) wm.updateViewLayout(this, p);
            else wm.addView(this, p);
            shown = true;
        } catch (Exception e) {
            android.util.Log.w("YtSaver", "card", e);
            shown = false;
        }
        invalidate();
        HomeKeyService.keysChanged();
    }

    private String fit(String s, Paint p, float width) {
        return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }

    private void chip(Canvas c, RectF r, String label, boolean on) {
        float rad = r.height() / 2;
        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (on) {   // the chosen one is solid white; the other is only an outline, so there's no doubt which is on
            shape.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, shape);
        } else {
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeWidth(Math.max(1f, 2f * k));
            shape.setColor(0x59FFFFFF);
            c.drawRoundRect(r, rad, rad, shape);
        }
        Paint p = Theme.font(getContext(), 26 * k, on ? 700 : 400);
        p.clearShadowLayer();
        p.setColor(on ? 0xFF111114 : 0x99FFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p);
    }

    /** A soft white ring just outside the focused choice. */
    private void ring(Canvas c, RectF r) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(3 * k);
        g.setColor(0xE6FFFFFF);
        RectF o = new RectF(r.left - 6 * k, r.top - 6 * k, r.right + 6 * k, r.bottom + 6 * k);
        c.drawRoundRect(o, o.height() / 2, o.height() / 2, g);
    }

    private void button(Canvas c, RectF r, String label, boolean focused) {
        float rad = r.height() / 2;
        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        shape.setColor(focused ? 0xFFFFFFFF : 0x1FFFFFFF);
        c.drawRoundRect(r, rad, rad, shape);
        Paint p = Theme.font(getContext(), 27 * k, 700);
        p.clearShadowLayer();
        p.setColor(focused ? 0xFF111114 : 0xD9FFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        float rad = (mode == NOTE ? 26 : 34) * k, in = edge.getStrokeWidth();
        RectF box = new RectF(in, in, w - in, h - in);
        c.drawRoundRect(box, rad, rad, plate);
        c.drawRoundRect(box, rad, rad, edge);
        float pad = 36 * k;
        if (mode == NOTE) {
            c.drawText(fit(title, big, w - 2 * pad), pad, h / 2f - (big.ascent() + big.descent()) / 2, big);
        } else if (mode == CHOICE) {
            c.drawText(fit(title, big, w - 2 * pad), pad, 62 * k, big);
            c.drawText(fit(by, small, w - 2 * pad), pad, 100 * k, small);
            float top = 132 * k, ch = 68 * k, cw = 150 * k;
            chip(c, new RectF(pad, top, pad + cw, top + ch), L10n.t("Audio"), !video);
            chip(c, new RectF(pad + cw + 14 * k, top, pad + 2 * cw + 14 * k, top + ch), L10n.t("Video"), video);
            Paint hint = new Paint(small);
            hint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(L10n.t("Back to cancel"), w - pad, top + ch / 2 - (hint.ascent() + hint.descent()) / 2, hint);
        } else if (mode == ASK) {
            Paint head = new Paint(small);
            head.setTextSize(22 * k);
            head.setColor(0x99FFFFFF);
            c.drawText(L10n.t("Save from YouTube"), pad + 4 * k, 58 * k, head);
            c.drawText(fit(title, big, w - 2 * pad), pad + 4 * k, 104 * k, big);
            c.drawText(fit(by, small, w - 2 * pad), pad + 4 * k, 140 * k, small);
            // the format: the chosen one solid; a ring round the row while it has the focus
            float top = 172 * k, ch = 64 * k, cw = (w - 2 * pad - 14 * k) / 2;
            RectF a = new RectF(pad, top, pad + cw, top + ch), v = new RectF(pad + cw + 14 * k, top, w - pad, top + ch);
            if (askRow == 0) ring(c, video ? v : a);
            chip(c, a, L10n.t("Audio \u00b7 MP3"), !video);
            chip(c, v, L10n.t("Video \u00b7 MP4"), video);
            // Download / Cancel: the focused one solid white
            float bt = top + ch + 30 * k, bh = 68 * k;
            RectF d = new RectF(pad, bt, pad + cw, bt + bh), x = new RectF(pad + cw + 14 * k, bt, w - pad, bt + bh);
            button(c, d, L10n.t("Download"), askRow == 1 && askBtn == 0);
            button(c, x, L10n.t("Cancel"), askRow == 1 && askBtn == 1);
        } else if (code != null) {
            float q = 300 * k, x = (w - q) / 2, y = 42 * k;
            c.drawBitmap(code, null, new RectF(x, y, x + q, y + q), null);
            Paint a = new Paint(big);
            a.setTextAlign(Paint.Align.CENTER);
            c.drawText(L10n.t("Your phone as a remote"), w / 2f, y + q + 58 * k, a);
            Paint b = new Paint(small);
            b.setTextAlign(Paint.Align.CENTER);
            c.drawText(L10n.t("Scan once, add to home screen"), w / 2f, y + q + 98 * k, b);
        }
    }
}
