package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Today's cricket: the matches on the left, the chosen one large on the right; OK opens the app that shows it. */
public class SportsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    private Page page;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        page = new Page(this);
        setContentView(page);
        page.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        page.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        page.stop();
    }

    private static final class Page extends View {

        private static final float LIST_X = 80, LIST_W = 660, LIST_T = 136, ROW = 112, BOX_B = 1040;
        private float BOX_X = 780, BOX_R = 1840;   // the match card; narrower beside the sports home's rows
        private static final int SHOWN = 8;
        private final float k;
        private final Paint head, name, small, team, score, status, btn, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge;
        private final RectF r = new RectF();
        private List<Cricket.Match> list = new ArrayList<>();
        private int sel, from, chip = -1;   // chip: on the row of sports at the top (-1: on the list)
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                Cricket.refreshNow(() -> post(Page.this::reload));
                reload();
                postDelayed(this, list.isEmpty() ? 3_000 : 30_000);
            }
        };

        Page(Context ctx) {
            super(ctx);
            k = ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
            NoirKit n = new NoirKit(ctx, k);
            head = n.inter(40, 700, 0, Color.WHITE);
            name = n.inter(30, 500, 0, Color.WHITE);
            small = n.inter(24, 400, 0, Frost.MUTE);
            team = n.inter(40, 600, 0, Color.WHITE);
            score = n.inter(88, 700, 0, Color.WHITE);
            status = n.inter(30, 400, 0, Color.WHITE);
            btn = n.inter(24, 500, 0, Color.WHITE);
            badge = n.inter(24, 700, 0, Color.WHITE);
            badge.setTextAlign(Paint.Align.CENTER);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        void start() {
            Cricket.useSports(Scores.chosen(getContext()));
            running = true;
            removeCallbacks(tick);
            post(tick);
        }

        void stop() {
            running = false;
            removeCallbacks(tick);
        }

        private void reload() {
            String was = sel < list.size() ? list.get(sel).t1 + list.get(sel).t2 : null;
            list = new ArrayList<>(Cricket.today());
            for (int i = 0; was != null && i < list.size(); i++) if (was.equals(list.get(i).t1 + list.get(i).t2)) sel = i;
            sel = Math.max(0, Math.min(sel, list.size() - 1));
            invalidate();
        }

        private static String appName(String pkg) {
            if (Services.HOTSTAR.pkg.equals(pkg)) return Services.HOTSTAR.name;
            if (Services.SONYLIV.pkg.equals(pkg)) return Services.SONYLIV.name;
            return L10n.t("FanCode");
        }

        private static int teamColour(String t) {
            switch (t == null ? "" : t.toUpperCase(Locale.ROOT)) {
                case "IND": return 0xFF1565C0;
                case "AUS": return 0xFFD9A400;
                case "ENG": return 0xFFC62828;
                case "NZ": return 0xFF37474F;
                case "PAK": return 0xFF1B7A3D;
                case "SA": return 0xFF0B7A4B;
                case "SL": return 0xFF23398A;
                case "BAN": return 0xFF00796B;
                case "AFG": return 0xFF0D47A1;
                case "WI": return 0xFF7B1E3A;
                default:
                    int[] p = {0xFF5E35B1, 0xFF00838F, 0xFFAD1457, 0xFF2E7D32, 0xFFEF6C00, 0xFF3949AB};
                    return p[Math.floorMod(t == null ? 0 : t.hashCode(), p.length)];
            }
        }

        /** Draws it whole: smaller (down to two thirds) when it's long, and cut with "…" only if even that is too wide. */
        private void put(Canvas c, String s, Paint p, float room, float x, float y) {
            float was = p.getTextSize(), w = p.measureText(s);
            if (w > room) p.setTextSize(Math.max(was * 0.66f, was * room / w));
            c.drawText(fit(s, p, room), x, y, p);
            p.setTextSize(was);
        }

        private String fit(String s, Paint p, float room) {
            return TextUtils.ellipsize(s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
        }

        private final android.graphics.Rect src = new android.graphics.Rect();

        private static int dim(int col, float by) {
            return Color.rgb(Math.round(Color.red(col) * by), Math.round(Color.green(col) * by), Math.round(Color.blue(col) * by));
        }

        /** A team's badge, fitted in a square; its colour and short name while it loads or when there is none. */
        private void badge(Canvas c, @androidx.annotation.Nullable String url, String code, int col, float cx, float cy, float size, int alpha) {
            // Small copies (two sizes): a page of logos then fits in memory at once instead of dropping and
            // reloading each other, which flickered between the logo and its letters.
            Bitmap b = url == null ? null : ImageCache.get(url, this::postInvalidate, null, size > 170 * k ? 400 : 160);
            if (b != null) {
                float s = size / Math.max(b.getWidth(), b.getHeight());
                r.set(cx - b.getWidth() * s / 2, cy - b.getHeight() * s / 2, cx + b.getWidth() * s / 2, cy + b.getHeight() * s / 2);
                fill.setAlpha(alpha);
                c.drawBitmap(b, null, r, fill);
                fill.setAlpha(255);
                return;
            }
            if (alpha < 255) return;
            fill.setColor(col != 0 ? col : teamColour(code));
            c.drawCircle(cx, cy, size / 2, fill);
            team.setTextAlign(Paint.Align.CENTER);
            team.setTextSize(Math.min(40, size / 3.2f / k) * k);
            c.drawText(code.length() > 4 ? code.substring(0, 3).toUpperCase(Locale.ROOT) : code, cx, cy + team.getTextSize() * 0.35f, team);
            team.setTextSize(40 * k);
            team.setTextAlign(Paint.Align.LEFT);
        }

        // ---- the scorecard: fetched for the match chosen, again every 30 seconds while it is live ----
        private final java.util.Map<String, Scores.Card> cards = new java.util.HashMap<>();
        private final java.util.Set<String> asking = new java.util.HashSet<>();

        @androidx.annotation.Nullable
        private Scores.Card card(Cricket.Match m) {
            String key = m.key();
            Scores.Card had = cards.get(key);
            boolean stale = had == null || (m.live && System.currentTimeMillis() - had.at > 30_000);
            if (stale && !asking.contains(key) && !m.event.isEmpty()) {
                asking.add(key);
                new Thread(() -> {
                    Scores.Card got = Scores.card(m);
                    post(() -> {
                        asking.remove(key);
                        if (got != null) cards.put(key, got);
                        invalidate();
                    });
                }, "scorecard").start();
            }
            return had;
        }

        /** A team colour light enough to see on the dark card. */
        private static int bright(int col) {
            float[] hsv = new float[3];
            Color.colorToHSV(col, hsv);
            hsv[2] = Math.max(hsv[2], 0.75f);
            return Color.HSVToColor(hsv);
        }

        private static float num(String v) {
            try {
                return Math.max(0, Float.parseFloat(v.replace("%", "").trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** A small table: a title, column heads, then one line per player. */
        private void drawTable(Canvas c, String title, String[] heads, List<String[]> rows, float x, float y, float w, float limit) {
            if (rows.isEmpty()) return;
            small.setTextAlign(Paint.Align.LEFT);
            small.setColor(Color.WHITE);
            small.setTextSize(26 * k);
            put(c, title, small, w, x, y);
            y += 44 * k;
            float col = 70 * k, nameW = w - col * heads.length;
            small.setTextSize(22 * k);
            small.setColor(Frost.MUTE);
            small.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i < heads.length; i++) c.drawText(heads[i], x + nameW + col * (i + 1) - 8 * k, y, small);
            y += 40 * k;
            small.setTextSize(25 * k);
            for (String[] r2 : rows) {
                if (y > limit) break;
                small.setTextAlign(Paint.Align.LEFT);
                small.setColor(Color.WHITE);
                put(c, r2[0], small, nameW - 12 * k, x, y);
                small.setTextAlign(Paint.Align.RIGHT);
                for (int i = 1; i < r2.length && i <= heads.length; i++) {
                    small.setColor(i == 1 ? Color.WHITE : 0xCCFFFFFF);
                    c.drawText(r2[i], x + nameW + col * i - 8 * k, y, small);
                }
                y += 42 * k;
            }
            small.setTextAlign(Paint.Align.LEFT);
            small.setTextSize(24 * k);
            small.setColor(Frost.MUTE);
        }

        // ---- the sports home: rows of live games, today's, results, news and tables; OK on a game opens it ----
        private boolean hub = true;
        private int hRow, hScroll;
        private final int[] hCol = new int[6];
        private static final int R_CHIPS = 0, R_LIVE = 1, R_TODAY = 2, R_DONE = 3;
        private static final String[] R_TITLE = {"", L10n.t("Live now"), L10n.t("Coming up today"), L10n.t("Results")};
        private boolean inPanel;   // the focus is on the match card's buttons, on the right
        private int panelBtn;      // 0: Watch on…, 1: Full scorecard

        /** The games in a row of the home. */
        private List<Cricket.Match> games(int row) {
            List<Cricket.Match> out = new ArrayList<>();
            for (Cricket.Match m : list) {
                boolean live = m.live || m.paused;
                if (row == R_LIVE && live || row == R_TODAY && !live && !m.ended || row == R_DONE && m.ended) out.add(m);
            }
            return out;
        }

        private int count(int row) {
            switch (row) {
                case R_CHIPS: return Scores.SPORTS.length;
                default: return games(row).size();
            }
        }

        private static float rowHeight(int row) {
            switch (row) {
                case R_CHIPS: return 0;
                case R_LIVE: return 360;
                default: return 200;
            }
        }

        private void drawHub(Canvas c) {
            float W = getWidth(), H = getHeight();
            fill.setShader(new LinearGradient(0, 0, W, H, 0xFF0B1F17, 0xFF05070A, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(new RadialGradient(W * 0.8f, 0, W * 0.6f, 0x3315803D, 0x0015803D, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(null);
            c.drawText(L10n.t("Sports"), LIST_X * k, 92 * k, head);
            String day = new SimpleDateFormat(Lang.datePattern(), Lang.dates()).format(new Date());
            c.drawText(day, 1840 * k - small.measureText(day), 88 * k, small);
            java.util.Set<String> on = Scores.chosen(getContext());
            float cx = (LIST_X + 200) * k;
            for (int i = 0; i < Scores.SPORTS.length; i++) {
                boolean picked = on.contains(Scores.SPORTS[i]);
                String label = (picked ? "\u2713 " : "") + Scores.NAMES[i];
                float w = btn.measureText(label) + 44 * k;
                r.set(cx, 52 * k, cx + w, 104 * k);
                if (hRow == R_CHIPS && hCol[0] == i) Frost.plate(c, r, r.height() / 2, k, 0xFF15803D);
                else {
                    fill.setColor(picked ? 0x4415803D : 0x1AFFFFFF);
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                }
                btn.setColor(picked || hRow == R_CHIPS && hCol[0] == i ? Color.WHITE : Frost.MUTE);
                c.drawText(label, r.left + 22 * k, r.centerY() + 9 * k, btn);
                btn.setColor(Color.WHITE);
                cx += w + 12 * k;
            }
            // the rows, scrolled so the chosen one shows
            float y = 150, focusTop = 150;
            for (int row = 1; row <= R_DONE; row++) {
                if (count(row) == 0) continue;
                if (row == hRow) focusTop = y;
                y += 64 + rowHeight(row) + 30;
            }
            float want = hRow == R_CHIPS ? 0 : Math.max(0, focusTop - 150);
            hScroll += (int) ((want - hScroll) * 0.35f);
            if (Math.abs(want - hScroll) > 1) postInvalidateOnAnimation();
            else hScroll = (int) want;
            c.save();
            c.clipRect(0, 128 * k, (HUB_R + 10) * k, H);
            c.translate(0, -hScroll * k);
            y = 150;
            boolean any = false;
            for (int row = 1; row <= R_DONE; row++) {
                int n = count(row);
                if (n == 0) continue;
                any = true;
                name.setTextSize(32 * k);
                c.drawText(R_TITLE[row], LIST_X * k, (y + 44) * k, name);
                name.setTextSize(30 * k);
                float top = y + 64, x = LIST_X;
                int col = Math.min(hCol[row], n - 1), first = Math.max(0, col - (row == R_LIVE ? 2 : 3));
                for (int i = first; i < n && x < HUB_R; i++) {
                    boolean focused = hRow == row && col == i && !inPanel;
                    x += drawItem(c, row, i, x, top, focused) + 24;
                }
                y += 64 + rowHeight(row) + 30;
            }
            c.restore();
            if (!any) c.drawText(on.isEmpty() ? L10n.t("Press Up and choose the sports you follow") : L10n.t("Loading the scores\u2026"), LIST_X * k, 200 * k, name);
            // beside the rows: the card of the game chosen (or the first live one), the story, or the table
            BOX_X = HUB_R + 30;
            Cricket.Match pick = null;
            if (hRow >= R_LIVE && hRow <= R_DONE && count(hRow) > 0) pick = games(hRow).get(Math.min(hCol[hRow], count(hRow) - 1));
            if (pick == null) for (int row = R_LIVE; row <= R_DONE && pick == null; row++) if (count(row) > 0) pick = games(row).get(0);
            if (pick != null) {
                sel = Math.max(0, list.indexOf(pick));
                drawDetail(c, pick);
            }
        }

        private static final float HUB_R = 1150;

        /** One card in a row; returns its width. */
        private float drawItem(Canvas c, int row, int i, float x, float top, boolean focused) {
            float w, h = rowHeight(row);
            Cricket.Match m = games(row).get(i);
            boolean big = row == R_LIVE;
            w = big ? 520 : 380;
            RectF card = new RectF(x * k, top * k, (x + w) * k, (top + h) * k);
            // the two teams' colours across the card
            int ca = m.col1 != 0 ? dim(m.col1, 0.55f) : 0xFF1F2937, cb = m.col2 != 0 ? dim(m.col2, 0.55f) : 0xFF111827;
            fill.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom, ca, cb, Shader.TileMode.CLAMP));
            c.drawRoundRect(card, 26 * k, 26 * k, fill);
            fill.setShader(new LinearGradient(0, card.top, 0, card.bottom, 0x22000000, 0x99000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(card, 26 * k, 26 * k, fill);
            fill.setShader(null);
            small.setColor(0xCCFFFFFF);
            small.setTextSize((big ? 22 : 19) * k);
            put(c, m.series, small, (w - 150) * k, card.left + 22 * k, card.top + (big ? 40 : 34) * k);
            String stt = m.live ? "\u25CF LIVE" : m.state();
            badge.setTextSize((big ? 20 : 18) * k);
            float bw = badge.measureText(stt) + 26 * k;
            RectF pill = new RectF(card.right - bw - 18 * k, card.top + 16 * k, card.right - 18 * k, card.top + (big ? 50 : 46) * k);
            fill.setColor(m.live ? 0xFFE5484D : 0x40FFFFFF);
            c.drawRoundRect(pill, 15 * k, 15 * k, fill);
            c.drawText(stt, pill.centerX(), pill.centerY() + 7 * k, badge);
            badge.setTextSize(24 * k);
            if (big) {
                boolean shortS = m.s1.length() <= 4 && m.s2.length() <= 4;
                // Each side a column: badge, name, and (for cricket's long scores) the score under the name.
                float cy = card.top + (shortS ? 150 : 128) * k, side = (w / 4f) * k;
                float lx = card.left + side, rx = card.right - side;
                badge(c, m.logo1, m.t1, m.col1, lx, cy, (shortS ? 110 : 92) * k, 255);
                if (!m.t2.isEmpty()) badge(c, m.logo2, m.t2, m.col2, rx, cy, (shortS ? 110 : 92) * k, 255);
                name.setTextAlign(Paint.Align.CENTER);
                name.setTextSize(28 * k);
                put(c, m.t1, name, side * 2 - 24 * k, lx, cy + (shortS ? 92 : 78) * k);
                if (!m.t2.isEmpty()) put(c, m.t2, name, side * 2 - 24 * k, rx, cy + (shortS ? 92 : 78) * k);
                score.setTextAlign(Paint.Align.CENTER);
                if (shortS) {
                    score.setTextSize(72 * k);
                    c.drawText(m.s1 + "\u2013" + m.s2, card.centerX(), cy + 24 * k, score);
                } else {
                    score.setTextSize(30 * k);
                    put(c, m.s1, score, side * 2 - 24 * k, lx, cy + 120 * k);
                    if (!m.t2.isEmpty()) put(c, m.s2, score, side * 2 - 24 * k, rx, cy + 120 * k);
                    small.setTextAlign(Paint.Align.CENTER);
                    small.setTextSize(24 * k);
                    small.setColor(0x99FFFFFF);
                    if (!m.t2.isEmpty()) c.drawText("v", card.centerX(), cy + 8 * k, small);
                }
                score.setTextAlign(Paint.Align.LEFT);
                score.setTextSize(88 * k);
                small.setTextAlign(Paint.Align.CENTER);
                small.setTextSize(22 * k);
                small.setColor(Color.WHITE);
                put(c, !m.status.isEmpty() ? m.status : m.state(), small, (w - 40) * k, card.centerX(), card.bottom - 28 * k);
                small.setTextAlign(Paint.Align.LEFT);
                name.setTextAlign(Paint.Align.LEFT);
                name.setTextSize(30 * k);
            } else {
                for (int s2 = 0; s2 < (m.t2.isEmpty() ? 1 : 2); s2++) {
                    float yy = card.top + (88 + s2 * 52) * k;
                    badge(c, s2 == 0 ? m.logo1 : m.logo2, s2 == 0 ? m.t1 : m.t2, s2 == 0 ? m.col1 : m.col2, card.left + 40 * k, yy - 10 * k, 38 * k, 255);
                    name.setTextSize(28 * k);
                    put(c, s2 == 0 ? m.t1 : m.t2, name, 150 * k, card.left + 76 * k, yy);
                    score.setTextSize(28 * k);
                    score.setTextAlign(Paint.Align.RIGHT);
                    put(c, s2 == 0 ? m.s1 : m.s2, score, 180 * k, card.right - 22 * k, yy);
                    score.setTextAlign(Paint.Align.LEFT);
                    score.setTextSize(88 * k);
                    name.setTextSize(30 * k);
                }
            }
            small.setTextSize(24 * k);
            small.setColor(Frost.MUTE);
            if (focused) {
                st2.setStrokeWidth(4 * k);
                c.drawRoundRect(card, 26 * k, 26 * k, st2);
            }
            return w;
        }

        private final Paint st2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        {
            st2.setStyle(Paint.Style.STROKE);
            st2.setColor(Color.WHITE);
        }

        private Cricket.Match shownMatch() {
            if (hRow < R_LIVE || hRow > R_DONE || count(hRow) == 0) return null;
            return games(hRow).get(Math.min(hCol[hRow], count(hRow) - 1));
        }

        private boolean panelKey(int code, KeyEvent e) {
            Cricket.Match m = shownMatch();
            boolean watch = m != null && m.pkg() != null;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (panelBtn == 1 && watch) panelBtn = 0;
                    else inPanel = false;   // back to the rows
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    panelBtn = 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    inPanel = false;
                    return hubKey(code, e);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() != 0 || m == null) return true;
                    if (panelBtn == 0 && watch) watch(m);
                    else {   // the whole scorecard, on its own page
                        sel = Math.max(0, list.indexOf(m));
                        chip = -1;
                        hub = false;
                        inPanel = false;
                    }
                    break;
                default:
                    return false;
            }
            invalidate();
            return true;
        }

        private void toPanel() {
            Cricket.Match m = shownMatch();
            if (m == null) return;
            inPanel = true;
            panelBtn = m.pkg() != null ? 0 : 1;
        }

        private boolean hubKey(int code, KeyEvent e) {
            if (inPanel) return panelKey(code, e);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1, r2 = hRow + dir;
                    while (r2 > 0 && r2 <= R_DONE && count(r2) == 0) r2 += dir;
                    if (r2 >= 0 && r2 <= R_DONE) hRow = r2;
                    invalidate();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT && hRow != R_CHIPS && hCol[hRow] >= count(hRow) - 1) toPanel();   // past the last game: its card
                    else hCol[hRow] = Math.max(0, Math.min(count(hRow) - 1, hCol[hRow] + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() != 0) return true;
                    if (hRow == R_CHIPS) {
                        Scores.toggle(getContext(), Scores.SPORTS[hCol[0]]);
                        Cricket.useSports(Scores.chosen(getContext()));
                        Cricket.refreshNow(() -> post(this::reload));
                    } else toPanel();   // OK on a game: its card on the right, Watch on… chosen
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_BACK && hub && inPanel) {   // from the card back to the rows
                inPanel = false;
                invalidate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK && !hub) {   // from a match back to the sports home
                hub = true;
                invalidate();
                return true;
            }
            return super.onKeyUp(code, e);
        }

        @Override
        protected void onDraw(Canvas c) {
            Frost.frame(this);   // selections glide (Frost.plate)
            if (hub) {
                if (hRow > 0 && count(hRow) == 0) hRow = 0;
                drawHub(c);
                return;
            }
            BOX_X = 780;
            float W = getWidth(), H = getHeight();
            Cricket.Match shown = list.isEmpty() ? null : list.get(Math.min(sel, list.size() - 1));
            // the backdrop: the two teams' colours, dark, with their badges large and faint behind the card
            int ca = shown != null && shown.col1 != 0 ? dim(shown.col1, 0.42f) : 0xFF0A1A12;
            int cb = shown != null && shown.col2 != 0 ? dim(shown.col2, 0.42f) : 0xFF050607;
            fill.setShader(new LinearGradient(0, 0, W, H, ca, cb, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(new LinearGradient(0, 0, 0, H, 0x66000000, 0xE6000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(null);
            if (shown != null) {
                badge(c, shown.logo1, shown.t1, 0, 1000 * k, 560 * k, 620 * k, 26);
                badge(c, shown.logo2, shown.t2, 0, 1640 * k, 560 * k, 620 * k, 26);
            }
            c.drawText(L10n.t("Sports"), LIST_X * k, 92 * k, head);
            String day = new SimpleDateFormat(Lang.datePattern(), Lang.dates()).format(new Date());
            c.drawText(day, 1840 * k - small.measureText(day), 88 * k, small);
            java.util.Set<String> on = Scores.chosen(getContext());
            float cx = (LIST_X + 200) * k;
            for (int i = 0; i < Scores.SPORTS.length; i++) {
                boolean picked = on.contains(Scores.SPORTS[i]);
                String label = (picked ? "\u2713 " : "") + Scores.NAMES[i];
                float w = btn.measureText(label) + 44 * k;
                r.set(cx, 52 * k, cx + w, 104 * k);
                if (chip == i) Frost.plate(c, r, r.height() / 2, k, 0xFF15803D);
                else {
                    fill.setColor(picked ? 0x4415803D : 0x1AFFFFFF);
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                }
                btn.setColor(picked || chip == i ? Color.WHITE : Frost.MUTE);
                c.drawText(label, r.left + 22 * k, r.centerY() + 9 * k, btn);
                btn.setColor(Color.WHITE);
                cx += w + 12 * k;
            }
            if (shown == null) {
                c.drawText(on.isEmpty() ? L10n.t("Press Up and choose the sports you follow") : L10n.t("No matches today"), LIST_X * k, 180 * k, name);
                return;
            }
            if (sel < from) from = sel;
            if (sel >= from + SHOWN) from = sel - SHOWN + 1;
            for (int v = 0; v < SHOWN && from + v < list.size(); v++) {
                int i = from + v;
                Cricket.Match m = list.get(i);
                float top = (LIST_T + v * ROW) * k;
                RectF row = new RectF(LIST_X * k, top, (LIST_X + LIST_W) * k, top + (ROW - 10) * k);
                if (i == sel) Frost.plate(c, row, 24 * k, k, m.live ? 0xFF15803D : 0xFF2F4858);
                else {
                    fill.setColor(0x14FFFFFF);
                    c.drawRoundRect(row, 24 * k, 24 * k, fill);
                }
                float rowTop = row.top, x = row.left + 22 * k, colScore = row.left + 210 * k, colRight = row.right - 20 * k;
                // the right column: the state on top, the competition under it
                String st = m.live ? "\u25CF LIVE" : m.state();
                badge.setTextSize(19 * k);
                float bw = badge.measureText(st) + 26 * k;
                RectF pill = new RectF(colRight - bw, rowTop + 16 * k, colRight, rowTop + 46 * k);
                fill.setColor(m.live ? 0xFFE5484D : 0x33FFFFFF);
                c.drawRoundRect(pill, 15 * k, 15 * k, fill);
                c.drawText(st, pill.centerX(), pill.centerY() + 7 * k, badge);
                badge.setTextSize(24 * k);
                small.setTextSize(20 * k);
                small.setColor(i == sel ? 0xCCFFFFFF : Frost.MUTE);
                String lg = fit(m.series, small, 190 * k);
                c.drawText(lg, colRight - small.measureText(lg), rowTop + 82 * k, small);
                small.setColor(Frost.MUTE);
                small.setTextSize(24 * k);
                float scoreRoom = colRight - 200 * k - colScore;
                // one line per side: badge, short name, score
                for (int s2 = 0; s2 < (m.t2.isEmpty() ? 1 : 2); s2++) {
                    float y = rowTop + (m.t2.isEmpty() ? 58 : 38 + s2 * 40) * k;
                    badge(c, s2 == 0 ? m.logo1 : m.logo2, s2 == 0 ? m.t1 : m.t2, s2 == 0 ? m.col1 : m.col2, x + 15 * k, y - 9 * k, 30 * k, 255);
                    name.setTextSize(27 * k);
                    put(c, s2 == 0 ? m.t1 : m.t2, name, colScore - x - 52 * k, x + 44 * k, y);
                    score.setTextSize(27 * k);
                    put(c, s2 == 0 ? m.s1 : m.s2, score, scoreRoom, colScore, y);
                    score.setTextSize(88 * k);
                    name.setTextSize(30 * k);
                }
            }
            drawDetail(c, shown);
        }

        private void drawDetail(Canvas c, Cricket.Match m) {
            r.set(BOX_X * k, LIST_T * k, BOX_R * k, BOX_B * k);
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(r, 40 * k, 40 * k, fill);
            Frost.rim(c, r, 40 * k, k);
            float x = (BOX_X + 56) * k, right = (BOX_R - 56) * k, room = right - x, mid = (BOX_X + BOX_R) / 2f * k;
            name.setTextAlign(Paint.Align.CENTER);
            put(c, m.series + (m.kind.isEmpty() ? "" : " \u00B7 " + m.kind), name, room, mid, (LIST_T + 66) * k);
            small.setTextAlign(Paint.Align.CENTER);
            if (!m.venue.isEmpty()) put(c, m.venue, small, room, mid, (LIST_T + 104) * k);
            // the two sides facing each other: big badges, names and scores
            boolean narrow = BOX_R - BOX_X < 900;
            float f = narrow ? 0.68f : 1, inset = narrow ? 150 : 230;
            float cy = (LIST_T + (narrow ? 205 : 250)) * k, lx = (BOX_X + inset) * k, rx = (BOX_R - inset) * k;
            boolean two = !m.t2.isEmpty();
            badge(c, m.logo1, m.t1, m.col1, two ? lx : mid, cy, 190 * f * k, 255);
            if (two) badge(c, m.logo2, m.t2, m.col2, rx, cy, 190 * f * k, 255);
            team.setTextAlign(Paint.Align.CENTER);
            team.setTextSize(40 * (narrow ? 0.8f : 1) * k);
            put(c, m.t1, team, 260 * k, two ? lx : mid, cy + 150 * f * k);
            if (two) put(c, m.t2, team, 260 * k, rx, cy + 150 * f * k);
            team.setTextSize(40 * k);
            team.setTextAlign(Paint.Align.LEFT);
            boolean shortScores = m.s1.length() <= 4 && m.s2.length() <= 4 && !Scores.CRICKET.equals(m.sport);
            score.setTextAlign(Paint.Align.CENTER);
            if (shortScores && two && !(m.s1.isEmpty() && m.s2.isEmpty())) {
                score.setTextSize(120 * f * k);
                c.drawText(m.s1 + "  \u2013  " + m.s2, mid, cy + 40 * f * k, score);
            } else {
                score.setTextSize(52 * f * k);
                if (!m.s1.isEmpty()) put(c, m.s1, score, (narrow ? 280 : 420) * k, two ? lx : mid, cy + 214 * f * k);
                if (two && !m.s2.isEmpty()) put(c, m.s2, score, (narrow ? 280 : 420) * k, rx, cy + 214 * f * k);
                if (two) {
                    small.setTextSize(40 * k);
                    c.drawText("v", mid, cy + 14 * k, small);
                    small.setTextSize(24 * k);
                }
            }
            score.setTextAlign(Paint.Align.LEFT);
            score.setTextSize(88 * k);
            // the state: a red LIVE pill with the minute, or the result or start time
            float y = cy + (shortScores ? 110 : 280) * f * k;
            String say = !m.status.isEmpty() ? m.status : m.state();
            if (m.live) {
                badge.setTextSize(26 * k);
                float bw = badge.measureText("\u25CF LIVE") + 40 * k;
                RectF pill = new RectF(mid - bw / 2, y - 36 * k, mid + bw / 2, y + 8 * k);
                fill.setColor(0xFFE5484D);
                c.drawRoundRect(pill, 22 * k, 22 * k, fill);
                c.drawText("\u25CF LIVE", mid, pill.centerY() + 9 * k, badge);
                badge.setTextSize(24 * k);
                y += 52 * k;
            }
            status.setTextAlign(Paint.Align.CENTER);
            put(c, say, status, room, mid, y);
            status.setTextAlign(Paint.Align.LEFT);
            float ty = y + 58 * k;
            Scores.Card card = card(m);
            float limit = (BOX_B - (m.pkg() == null ? 40 : 120)) * k;
            if (card != null && (!card.bat.isEmpty() || !card.bowl.isEmpty())) {
                if (narrow) {   // one under the other: batting, then bowling
                    List<String[]> bat = card.bat.subList(0, Math.min(3, card.bat.size())), bowl = card.bowl.subList(0, Math.min(2, card.bowl.size()));
                    drawTable(c, card.batTitle, new String[]{"R", "B", "4s", "6s"}, bat, x, ty, room, limit);
                    if (!bat.isEmpty()) ty += (84 + 42 * bat.size() + 20) * k;
                    drawTable(c, card.bowlTitle, new String[]{"O", "R", "W", L10n.t("Econ")}, bowl, x, ty, room, limit);
                } else {
                    drawTable(c, card.batTitle, new String[]{"R", "B", "4s", "6s"}, card.bat, x, ty, room / 2 - 20 * k, limit);
                    drawTable(c, card.bowlTitle, new String[]{"O", "R", "W", L10n.t("Econ")}, card.bowl, x + room / 2 + 20 * k, ty, room / 2 - 20 * k, limit);
                }
                ty = limit;
            } else if (card != null && !card.stats.isEmpty()) {
                // match stats: one bar per stat, split in the two teams' colours
                int keep = Math.min(m.plays.size(), 2);
                for (int i = m.plays.size() - keep; i < m.plays.size(); i++) {
                    small.setTextAlign(Paint.Align.CENTER);
                    small.setColor(Color.WHITE);
                    put(c, m.plays.get(i), small, room, mid, ty);
                    ty += 36 * k;
                }
                ty += 10 * k;
                for (String[] st2 : card.stats) {
                    if (ty + 40 * k > limit) break;
                    float va = num(st2[1]), vb = num(st2[2]), tot = va + vb;
                    small.setColor(Color.WHITE);
                    small.setTextAlign(Paint.Align.LEFT);
                    c.drawText(st2[1] + (st2[0].equals("Possession") ? "%" : ""), x, ty, small);
                    small.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(st2[2] + (st2[0].equals("Possession") ? "%" : ""), right, ty, small);
                    small.setTextAlign(Paint.Align.CENTER);
                    small.setColor(Frost.MUTE);
                    c.drawText(st2[0], mid, ty, small);
                    float bt = ty + 12 * k, split = tot > 0 ? x + room * va / tot : mid;
                    fill.setColor(m.col1 != 0 ? bright(m.col1) : 0xFF3E8EF7);
                    c.drawRoundRect(x, bt, split - 3 * k, bt + 10 * k, 5 * k, 5 * k, fill);
                    fill.setColor(m.col2 != 0 && m.col2 != m.col1 ? bright(m.col2) : 0xFFE5484D);
                    c.drawRoundRect(split + 3 * k, bt, right, bt + 10 * k, 5 * k, 5 * k, fill);
                    ty += 62 * k;
                }
                small.setColor(Frost.MUTE);
                small.setTextAlign(Paint.Align.LEFT);
                ty = limit;
            }
            // what happened: goals and cards, or the quarters, newest at the bottom
            int n = m.plays.size(), first = Math.max(0, n - Math.max(0, (int) ((limit - ty) / (44 * k))));
            for (int i = first; i < n; i++) {
                fill.setColor(0x33FFFFFF);
                c.drawCircle(mid - 300 * k, ty - 9 * k, 5 * k, fill);
                small.setTextAlign(Paint.Align.LEFT);
                small.setColor(Color.WHITE);
                small.setTextSize(28 * k);
                put(c, m.plays.get(i), small, 580 * k, mid - 280 * k, ty);
                ty += 44 * k;
            }
            small.setTextSize(24 * k);
            small.setColor(Frost.MUTE);
            small.setTextAlign(Paint.Align.LEFT);
            name.setTextAlign(Paint.Align.LEFT);
            if (hub) {   // beside the rows: Watch on… and Full scorecard, reached with OK or Right
                String w = m.pkg() == null ? null : L10n.t("Watch on ") + appName(m.pkg()), full = L10n.t("Full scorecard");
                float gap = 16 * k, ww = w == null ? 0 : btn.measureText(w) + 64 * k, fw = btn.measureText(full) + 64 * k;
                float total = ww + fw + (w == null ? 0 : gap), x0 = mid - total / 2;
                if (w != null) {
                    r.set(x0, (BOX_B - 100) * k, x0 + ww, (BOX_B - 36) * k);
                    hubButton(c, w, inPanel && panelBtn == 0, true);
                    x0 += ww + gap;
                }
                r.set(x0, (BOX_B - 100) * k, x0 + fw, (BOX_B - 36) * k);
                hubButton(c, full, inPanel && panelBtn == 1, w == null);
                return;
            }
            if (m.pkg() == null) return;
            String w = L10n.t("Watch on ") + appName(m.pkg());
            float bw = btn.measureText(w) + 64 * k;
            r.set(mid - bw / 2, (BOX_B - 100) * k, mid + bw / 2, (BOX_B - 36) * k);
            Frost.plate(c, r, r.height() / 2, k, 0xFF15803D);
            c.drawText(w, r.left + 32 * k, r.centerY() + 9 * k, btn);
        }

        /** A button on the match card (in r): green and lifted when chosen, glass otherwise. */
        private void hubButton(Canvas c, String label, boolean chosen, boolean main) {
            if (chosen) Frost.plate(c, r, r.height() / 2, k, 0xFF15803D);
            else {
                fill.setColor(0x22FFFFFF);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            }
            btn.setColor(chosen || main ? Color.WHITE : 0xDDFFFFFF);
            c.drawText(label, r.left + 32 * k, r.centerY() + 9 * k, btn);
            btn.setColor(Color.WHITE);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (hub) {
                if (code == KeyEvent.KEYCODE_BACK && inPanel) return true;
                if (code == KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, e);
                return hubKey(code, e) || super.onKeyDown(code, e);
            }
            if (code == KeyEvent.KEYCODE_BACK) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (chip < 0 && (sel == 0 || list.isEmpty())) chip = 0;
                    else if (chip < 0) sel = Math.max(0, sel - 1);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (chip >= 0) chip = -1;
                    else sel = Math.min(Math.max(0, list.size() - 1), sel + 1);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (chip >= 0) chip = Math.max(0, Math.min(Scores.SPORTS.length - 1, chip + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() != 0) return true;
                    if (chip >= 0) {
                        Scores.toggle(getContext(), Scores.SPORTS[chip]);
                        Cricket.useSports(Scores.chosen(getContext()));
                        Cricket.refreshNow(() -> post(this::reload));
                        invalidate();
                    } else if (sel < list.size() && list.get(sel).pkg() != null) watch(list.get(sel));
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }

        private void watch(Cricket.Match m) {
            Context c = getContext();
            String pkg = m.pkg();
            Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) i = DeepLinks.playStore(c, pkg);
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ex) {
                Toast.makeText(c, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
