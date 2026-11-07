package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The film page, one for the whole launcher: the title's art over the whole screen, its logo, a glass panel with its
 * story and cast, where to watch it, and everything you can do with it. Replaces the separate pop-ups.
 */
public class TitleActivity extends Activity {

    /** The title being opened: too rich for an Intent, and only ever one at a time. */
    private static Tmdb.Title pending;

    static void open(Context ctx, Tmdb.Title t) {
        pending = t;
        Intent i = new Intent(ctx, TitleActivity.class);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private Page page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tmdb.Title t = pending;
        if (t == null) {
            finish();
            return;
        }
        page = new Page(this, t);
        setContentView(page);
        page.setFocusable(true);
        page.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page != null) page.invalidate();   // what you marked may have changed elsewhere
    }

    private void play(Tmdb.Title t, Services.Service s) {
        Toast.makeText(this, getString(R.string.opening, t.title), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DeepLinks.Plan plan = DeepLinks.plan(this, t, s);
            runOnUiThread(() -> {
                if (plan.intent == null) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (plan.outcome == DeepLinks.Outcome.APP) {
                    Toast.makeText(this, getString(R.string.opened_app_search, s.name, t.title), Toast.LENGTH_LONG).show();
                } else if (plan.outcome == DeepLinks.Outcome.STORE) {
                    Toast.makeText(this, getString(R.string.get_app_first, s.name), Toast.LENGTH_LONG).show();
                }
                start(plan.intent);
            });
        }, "open-title").start();
    }

    private void trailer(Tmdb.Title t) {
        new Thread(() -> {
            Intent i = DeepLinks.trailer(this, t);
            runOnUiThread(() -> start(i));
        }, "trailer").start();
    }

    private void start(@Nullable Intent i) {
        if (i == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class Page extends View {

        private static final int B_PLAY = 0, B_TRAILER = 1, B_LIST = 2, B_WATCHED = 3, B_RATE = 4;
        private static final float BOX_L = 80, BOX_T = 500, BOX_W = 1080, BOX_H = 540, BTN_Y = 950, BTN_H = 68;

        private final TitleActivity act;
        private final Tmdb.Title t;
        private final float k;
        private final ArtStage stage;
        private final NoirKit nk;
        private final GlassKit kit;
        private final Paint title, meta, body, label, btn;
        private List<Services.Service> where = new ArrayList<>();
        private String cast = "";
        private boolean whereKnown;
        private int row = 1, sel, app;   // row 0: the apps; row 1: the buttons
        private boolean rating;
        private int ratingValue;
        private StaticLayout story;

        Page(TitleActivity act, Tmdb.Title t) {
            super(act);
            this.act = act;
            this.t = t;
            k = act.getResources().getDisplayMetrics().widthPixels / 1920f;
            stage = new ArtStage(this);
            nk = new NoirKit(act, k);
            kit = new GlassKit(act, null, k);
            title = shade(nk.inter(88, 700, 0, Color.WHITE));
            meta = nk.inter(24, 500, 0, Color.WHITE);
            body = nk.inter(30, 400, 0, Color.WHITE);
            label = nk.inter(24, 400, 0, Frost.MUTE);
            btn = nk.inter(24, 500, 0, Color.WHITE);
            String art = t.backdropLarge();
            stage.show(art != null ? art : t.poster());
            if (t.logoUrl != null) t.logoDone = true;
            t.logoAskedAt = SystemClock.uptimeMillis();
            new Thread(this::load, "title").start();
        }

        private Paint shade(Paint p) {
            p.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
            return p;
        }

        private void load() {
            Context ctx = getContext();
            try {
                Catalog.details(ctx, t);
            } catch (RuntimeException ignored) {
                // shown without them
            }
            if (t.logoUrl == null) t.logoUrl = Catalog.logo(ctx, t);
            t.logoDone = true;
            String names = Catalog.cast(ctx, t);
            List<Services.Service> w = new ArrayList<>();
            try {
                w.addAll(Catalog.whereToWatch(ctx, t));
            } catch (Exception ignored) {
                // nothing known
            }
            // Your own apps first, the one it was found on leading.
            List<Services.Service> sorted = new ArrayList<>();
            if (t.service != null) sorted.add(t.service);
            for (Services.Service s : w) if (!sorted.contains(s) && Services.installed(ctx, s.pkg)) sorted.add(s);
            for (Services.Service s : w) if (!sorted.contains(s)) sorted.add(s);
            post(() -> {
                where = sorted;
                cast = names;
                whereKnown = true;
                story = null;
                if (sel == B_PLAY && where.isEmpty()) sel = B_TRAILER;
                invalidate();
            });
        }

        private String buttonText(int b) {
            Library.Entry e = Library.get(getContext(), t);
            switch (b) {
                case B_PLAY:
                    return where.isEmpty() ? (whereKnown ? "Not streaming" : "Finding where…") : "Play on " + where.get(Math.min(app, where.size() - 1)).name;
                case B_TRAILER:
                    return "Trailer";
                case B_LIST:
                    return e != null && e.status == Library.WANT ? "✓ My List" : "+ My List";
                case B_WATCHED:
                    return e != null && e.status == Library.WATCHED ? "✓ Watched" : "Watched";
                default:
                    if (rating) return "‹  ★ " + (ratingValue == 0 ? "None" : ratingValue) + "  ›";
                    return e != null && e.rating > 0 ? "★ " + e.rating : "Rate";
            }
        }

        private RectF buttonRect(int b) {
            float x = BOX_L + 36;
            for (int i = 0; i < b; i++) x += btn.measureText(buttonText(i)) / k + 64 + 12;
            return new RectF(x * k, BTN_Y * k, (x + btn.measureText(buttonText(b)) / k + 64) * k, (BTN_Y + BTN_H) * k);
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            stage.draw(c, now);
            drawName(c);
            RectF box = new RectF(BOX_L * k, BOX_T * k, (BOX_L + BOX_W) * k, (BOX_T + BOX_H) * k);
            Frost.liquid(c, box, 44 * k, stage.frost(), stage.screen(), stage.bits(), k);

            float x = (BOX_L + 36) * k, room = (BOX_W - 72) * k, y = (BOX_T + 52) * k;
            c.drawText(fit(metaLine(), meta, room), x, y, meta);
            String ov = t.overview == null ? "" : t.overview;
            if (story == null) {
                story = StaticLayout.Builder.obtain(ov, 0, ov.length(), new TextPaint(body), Math.round(room)).setMaxLines(where.size() > 1 ? 5 : 7)
                        .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1).build();
            }
            c.save();
            c.translate(x, y + 20 * k);
            story.draw(c);
            c.restore();
            y += 20 * k + story.getHeight() + 40 * k;
            if (!cast.isEmpty()) c.drawText(fit("With " + cast, label, room), x, Math.min(y, (BTN_Y - (where.size() > 1 ? 98 : 26)) * k), label);

            if (where.size() > 1) drawApps(c);
            for (int b = 0; b < 5; b++) {
                RectF r = buttonRect(b);
                boolean on = row == 1 && sel == b;
                if (on) Frost.plate(c, r, r.height() / 2, k);
                else Frost.ghost(c, r, r.height() / 2, false, k);
                btn.setAlpha(b == B_PLAY && where.isEmpty() ? 150 : 255);
                c.drawText(buttonText(b), r.left + 32 * k, r.centerY() + 9 * k, btn);
                btn.setAlpha(255);
            }
            if (stage.moving(now) || logoWaiting()) postInvalidateOnAnimation();
        }

        private boolean logoWaiting() {
            if (SystemClock.uptimeMillis() - t.logoAskedAt > 2500) return false;
            return !t.logoDone || t.logoUrl != null && ImageCache.get(t.logoUrl, this::postInvalidate, null, 500) == null;
        }

        /** The film's logo above the panel, or its name; nothing for the moment while the logo may still come. */
        private void drawName(Canvas c) {
            if (logoWaiting()) return;
            float x = BOX_L * k, bottom = (BOX_T - 28) * k;
            Bitmap logo = t.logoUrl != null ? ImageCache.get(t.logoUrl, this::postInvalidate, null, 500) : null;
            if (logo != null && logo.getWidth() > 0 && !Frost.darkLogo(logo)) {
                float sc = Math.min(720 * k / logo.getWidth(), 230 * k / logo.getHeight());
                c.drawBitmap(logo, null, new RectF(x, bottom - logo.getHeight() * sc, x + logo.getWidth() * sc, bottom), stage.bits());
                return;
            }
            float size = 88;
            while (size > 48 && title.measureText(t.title) > 1500 * k) title.setTextSize((size -= 4) * k);
            c.drawText(fit(t.title, title, 1700 * k), x, bottom - 8 * k, title);
        }

        private String metaLine() {
            List<String> m = new ArrayList<>();
            m.add(t.isSeries() ? "Series" : "Film");
            if (t.year > 0) m.add(String.valueOf(t.year));
            if (!t.genres.isEmpty()) m.add(t.genres.replace(",", " ·"));
            if (t.runtime > 0) m.add(t.runtime >= 60 ? t.runtime / 60 + " h " + t.runtime % 60 + " min" : t.runtime + " min");
            if (t.rating > 0) m.add((t.jwId != null ? "IMDb " : "TMDB ") + String.format(Locale.US, "%.1f", t.rating));
            if (t.tomato >= 0) m.add("Rotten Tomatoes " + t.tomato + "%");
            return TextUtils.join(" · ", m);
        }

        /** Where else it streams, as a row of app marks above the buttons; choosing one changes what Play opens. */
        private void drawApps(Canvas c) {
            float x = (BOX_L + 36) * k, y = (BTN_Y - 84) * k, h = 68 * k, w = 148 * k;
            for (int i = 0; i < where.size() && i < 6; i++) {
                RectF r = new RectF(x + i * (w + 12 * k), y, x + i * (w + 12 * k) + w, y + h);
                boolean on = row == 0 && app == i;
                if (on) Frost.plate(c, r, h / 2, k);
                else Frost.ghost(c, r, h / 2, false, k);
                Services.Service s = where.get(i);
                Bitmap mark = LineIcon.appMark(s.pkg, s.name, 100 * k, 36 * k, btn.getTypeface());
                stage.bits().setAlpha(Services.installed(getContext(), s.pkg) ? 255 : 120);
                c.drawBitmap(mark, r.centerX() - mark.getWidth() / 2f, r.centerY() - mark.getHeight() / 2f, stage.bits());
                stage.bits().setAlpha(255);
            }
        }

        private static String fit(String s, Paint p, float room) {
            return TextUtils.ellipsize(s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (rating) {
                if (left || right) ratingValue = Math.max(0, Math.min(10, ratingValue + (right ? 1 : -1)));
                else if (ok || code == KeyEvent.KEYCODE_BACK) {
                    Library.rate(getContext(), t, ratingValue);
                    rating = false;
                } else return super.onKeyDown(code, e);
                invalidate();
                return true;
            }
            if (left || right) {
                if (row == 0) app = Math.max(0, Math.min(Math.min(where.size(), 6) - 1, app + (right ? 1 : -1)));
                else {
                    int low = where.isEmpty() ? B_TRAILER : B_PLAY;
                    sel = Math.max(low, Math.min(B_RATE, sel + (right ? 1 : -1)));
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (where.size() > 1) row = 0;
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                row = 1;
            } else if (ok) {
                press();
            } else return super.onKeyDown(code, e);
            invalidate();
            return true;
        }

        private void press() {
            if (row == 0) {   // play it on the chosen app
                row = 1;
                sel = B_PLAY;
                act.play(t, where.get(app));
                return;
            }
            Context ctx = getContext();
            Library.Entry en = Library.get(ctx, t);
            switch (sel) {
                case B_PLAY:
                    if (!where.isEmpty()) act.play(t, where.get(Math.min(app, where.size() - 1)));
                    break;
                case B_TRAILER:
                    act.trailer(t);
                    break;
                case B_LIST:
                    Library.setStatus(ctx, t, en != null && en.status == Library.WANT ? Library.NONE : Library.WANT);
                    break;
                case B_WATCHED:
                    Library.setStatus(ctx, t, en != null && en.status == Library.WATCHED ? Library.NONE : Library.WATCHED);
                    break;
                default:
                    rating = true;
                    ratingValue = en != null && en.rating > 0 ? en.rating : 7;
            }
        }
    }
}
