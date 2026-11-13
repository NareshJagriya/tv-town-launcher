package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Customize → Phone remote: how to connect, and the QR code that opens the phone remote's page. Scanning it is the
 * only step: the page works in any phone browser on the same Wi-Fi, nothing to install.
 */
final class PhonePane implements GlassSheet.Pane {

    private final Context ctx;
    private final float k;
    private final Paint title, line, small, num, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Bitmap qr;
    private String qrFor = "";

    PhonePane(Context c) {
        ctx = c.getApplicationContext();
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        NoirKit n = new NoirKit(c, k);
        title = n.inter(40, 800, -0.01f, Color.WHITE);
        line = n.inter(26, 600, 0, Color.WHITE);
        small = n.inter(21, 500, 0, Frost.MUTE);
        num = n.inter(22, 800, 0, Color.WHITE);
        num.setTextAlign(Paint.Align.CENTER);
        if (Features.on(ctx, Features.PHONE)) PhoneLink.start(ctx);
    }

    @Override
    public void draw(Canvas c, RectF area, float e, boolean focused) {
        int a = Math.round(255 * e);
        float x = area.left + 48 * k, y = area.top + 78 * k;
        title.setAlpha(a);
        c.drawText(L10n.t("Phone remote"), x, y, title);
        small.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
        c.drawText(L10n.t("Your phone as a remote and keyboard. Nothing to install."), x, y + 46 * k, small);

        boolean on = Features.on(ctx, Features.PHONE);
        String url = on ? PhoneLink.url(ctx) : null;
        float qs = 270 * k, qx = area.right - 64 * k - qs, qy = y + 96 * k;
        float colR = qx - 48 * k;

        String[] steps = {L10n.t("Same Wi-Fi as the TV"), L10n.t("Scan the code with your phone"),
                L10n.t("Apps, search, play, volume")};
        float sy = y + 130 * k;
        for (int i = 0; i < steps.length; i++) {
            float cy = sy + i * 86 * k;
            fill.setShader(null);
            fill.setColor(0x2EFFFFFF);
            fill.setAlpha(Math.round(0x2E * e));
            c.drawCircle(x + 22 * k, cy, 22 * k, fill);
            num.setAlpha(a);
            c.drawText(String.valueOf(i + 1), x + 22 * k, cy + 8 * k, num);
            line.setAlpha(a);
            c.drawText(android.text.TextUtils.ellipsize(steps[i], new android.text.TextPaint(line), colR - x - 64 * k,
                    android.text.TextUtils.TruncateAt.END).toString(), x + 64 * k, cy + 9 * k, line);
        }
        small.setTextAlign(Paint.Align.LEFT);
        small.setTextSize(20 * k);
        c.drawText(L10n.t("Tip: add it to your phone's home screen"), x, sy + 3 * 86 * k + 10 * k, small);

        if (url != null && !url.equals(qrFor)) {
            qr = PhoneKeyboard.qrFor(url, Math.round(qs));
            qrFor = url;
        }
        small.setTextAlign(Paint.Align.CENTER);
        if (url != null && qr != null) {
            r.set(qx - 16 * k, qy - 16 * k, qx + qs + 16 * k, qy + qs + 16 * k);
            fill.setColor(Color.WHITE);
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF7C5CFF, 0xFF2EC4B6, Shader.TileMode.CLAMP));
            fill.setAlpha(a);
            c.drawRoundRect(r, 26 * k, 26 * k, fill);
            fill.setShader(null);
            r.inset(5 * k, 5 * k);
            fill.setColor(Color.WHITE);
            fill.setAlpha(a);
            c.drawRoundRect(r, 21 * k, 21 * k, fill);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(a);
            c.drawBitmap(qr, qx, qy, p);
        } else {
            small.setTextSize(22 * k);
            c.drawText(on ? L10n.t("The TV isn't connected to the network") : L10n.t("Turn on Phone remote in Features"),
                    qx + qs / 2, qy + qs / 2, small);
        }
        small.setTextAlign(Paint.Align.LEFT);
        small.setTextSize(21 * k);
    }

    @Override
    public boolean key(int keyCode) {
        return false;
    }
}
