package com.nareshjagriya.tvtown;

import android.app.Activity;
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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Discover: shelves of big covers with each title's name, year and rating, and a small preview beside them. What's new, each of your
 * apps, the Must-Watch lists and moods all live here, so there is one place to look for something to watch.
 */
public class DiscoverActivity extends Activity {

    /** A search to run straight away (voice, or a suggestion from the keyboard). */
    static final String EXTRA_QUERY = "query";

    private Page page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new Page(this);
        setContentView(page);
        page.setFocusable(true);
        page.requestFocus();
        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (q != null && !q.trim().isEmpty()) page.search(q.trim());
    }

    @Override
    public void onBackPressed() {
        if (!TvKeyboard.back()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        page.net.shutdownNow();
    }

    private interface Loader {
        List<Tmdb.Title> load() throws Exception;
    }

    private static final class Shelf {
        final String name;
        final Loader loader;
        final List<Tmdb.Title> items = new ArrayList<>();
        boolean asked, loaded;
        int sel;
        float fromX, toX;
        long at;

        Shelf(String name, Loader loader) {
            this.name = name;
            this.loader = loader;
        }
    }

    private static final class Page extends View {

        private static final String[] TYPES = {"Movies & series", "Movies", "Series"};
        private static final int CAP_SEARCH = 0, CAP_TYPE = 1, CAP_LANG = 2, CAP_SURPRISE = 3, CAP_ALL = 4, CAPS = 5;
        private static final float CARD_W = 240, CARD_H = 360, PITCH = 262, SHELF_H = 512, FIRST_Y = 166, LEFT = 80, SIDE = 1410;

        final ExecutorService net = Work.pool(3);
        private final Activity act;
        private final float k;
        private final ArtStage stage;
        private final GlassKit kit;
        private final Paint heading, meta, over, shelfName, capText;
        private final Random random = new Random();
        private final Runnable settle = this::settled;
        private final List<Shelf> shelves = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private int row, cap, type, lang;   // row -1: the capsule
        private float rowFrom, rowTo;
        private long rowAt;
        @Nullable
        private String query;
        @Nullable
        private Tmdb.Title shown;
        private final StaticLayout[] laid = new StaticLayout[3];
        private final String[] laidFor = new String[3];

        Page(Activity act) {
            super(act);
            this.act = act;
            k = act.getResources().getDisplayMetrics().widthPixels / 1920f;
            stage = new ArtStage(this);
            NoirKit nk = new NoirKit(act, k);
            kit = new GlassKit(act, null, k);
            heading = shade(nk.inter(40, 600, 0, Color.WHITE));
            meta = shade(nk.inter(24, 500, 0, Color.WHITE));
            over = shade(nk.inter(30, 400, 0, Color.WHITE));
            shelfName = shade(nk.inter(30, 600, 0, Color.WHITE));
            capText = nk.inter(24, 500, 0, Color.WHITE);
            buildShelves();
        }

        private Paint shade(Paint p) {
            p.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
            return p;
        }

        void search(String q) {
            query = q;
            buildShelves();
        }

        // ---- what's on the shelves ---------------------------------------------------------------------

        private void buildShelves() {
            shelves.clear();
            seen.clear();
            row = 0;
            rowFrom = rowTo = 0;
            shown = null;
            Context ctx = getContext();
            List<Services.Service> mine = Services.installedOrPopular(ctx);
            String code = Tmdb.languages()[lang][1];
            if (query != null) {
                final String q = query;
                shelves.add(new Shelf("Results for “" + q + "”", () -> {
                    List<Tmdb.Title> out = new ArrayList<>();
                    for (Tmdb.Title t : Catalog.search(ctx, q, 1).titles) if (typeOk(t)) out.add(t);
                    return out;
                }));
            }
            List<Library.Entry> saved = new ArrayList<>();
            for (Library.Entry e : Library.all(ctx)) if (e.status == Library.WANT) saved.add(e);
            if (!saved.isEmpty()) {
                shelves.add(new Shelf("My List", () -> {
                    List<Tmdb.Title> out = new ArrayList<>();
                    for (Library.Entry e : saved) {
                        Tmdb.Title t = MustWatchView.fromEntry(e);
                        if (typeOk(t)) out.add(t);
                    }
                    return out;
                }));
            }
            shelves.add(new Shelf("New on your apps", () -> {
                List<Tmdb.Title> out = new ArrayList<>();
                for (Tmdb.Title t : Catalog.newOn(ctx, mine, 30)) if (typeOk(t) && hasPicture(t)) out.add(t);
                return out;
            }));
            Tmdb.Category popular = category("Popular");
            for (Services.Service s : mine) {
                if (popular != null) shelves.add(new Shelf("Popular on " + s.name, () -> discover(popular, Collections.singletonList(s), code)));
            }
            shelves.add(new Shelf("Top rated", () -> top(0, code)));
            shelves.add(new Shelf("Award winners", () -> top(1, code)));
            shelves.add(new Shelf("Critics' picks", () -> top(2, code)));
            for (String mood : new String[]{"Hidden gems", "Thriller", "Comedy", "Romance", "Sci-Fi", "Action", "Drama", "Kids"}) {
                Tmdb.Category m = category(mood);
                if (m != null) shelves.add(new Shelf(mood, () -> discover(m, mine, code)));
            }
            invalidate();
        }

        @Nullable
        private Tmdb.Category category(String n) {
            for (Tmdb.Category c : Tmdb.categories()) if (c.name.equals(n)) return c;
            return null;
        }

        private boolean typeOk(Tmdb.Title t) {
            return type == 0 || (type == 1) != t.isSeries();
        }

        private boolean hasPicture(Tmdb.Title t) {
            return t.backdropCard() != null;
        }

        /** A category across up to three apps, their titles in turn. */
        private List<Tmdb.Title> discover(Tmdb.Category c, List<Services.Service> apps, String code) throws Exception {
            List<List<Tmdb.Title>> lists = new ArrayList<>();
            for (Services.Service s : apps.subList(0, Math.min(3, apps.size()))) {
                for (String kind : type == 0 ? new String[]{"movie", "tv"} : new String[]{type == 1 ? "movie" : "tv"}) {
                    Tmdb.Page p = Catalog.discover(getContext(), c, s, kind, 1, code);
                    if (p != null) lists.add(p.titles);
                }
            }
            List<Tmdb.Title> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; out.size() < 24 && i < 24; i++) {
                for (List<Tmdb.Title> l : lists) {
                    if (i < l.size() && hasPicture(l.get(i)) && seen.add(l.get(i).key())) out.add(l.get(i));
                }
            }
            return out;
        }

        /** Must-Watch's lists: 0 top rated, 1 award winners, 2 critics' picks. */
        private List<Tmdb.Title> top(int list, String code) throws Exception {
            JustWatch.Category c = new JustWatch.Category("Must-Watch", "list" + list);
            Catalog.applyLanguage(c, code.isEmpty() ? null : code);
            c.sort = "IMDB_SCORE";
            String kind = type == 2 ? "SHOW" : "MOVIE";
            int base = java.util.Arrays.asList("hi", "ml", "ta", "te", "mr", "gu", "ko").contains(code) ? 3000 : type == 2 ? 10000 : 25000;
            if (list == 0) c.minImdb = 7.5;
            if (list == 1) c.minImdb = 6.0;
            Set<String> awards = list == 1 ? Awards.keys(getContext()) : null;
            List<Tmdb.Title> out = new ArrayList<>();
            int at = 0;
            for (int tries = 0; tries < (awards != null ? 6 : 1) && out.size() < 20; tries++) {
                JustWatch.Chunk chunk = JustWatch.top(c, kind, list == 1 ? base / 10 : list == 2 ? base / 5 : base, list == 2 ? 85 : 0, at, 30);
                at += 30;
                for (JustWatch.Title j : chunk.titles) {
                    Tmdb.Title t = Catalog.fromJw(j, code.isEmpty() ? null : code);
                    if (t == null || !hasPicture(t) || awards != null && !awards.contains(Library.keyOf(t))) continue;
                    out.add(t);
                }
                if (chunk.fetched < 30) break;
            }
            return out;
        }

        private void ask(int i) {
            if (i < 0 || i >= shelves.size()) return;
            Shelf s = shelves.get(i);
            if (s.asked) return;
            s.asked = true;
            net.execute(() -> {
                List<Tmdb.Title> got = new ArrayList<>();
                try {
                    got = s.loader.load();
                } catch (Exception e) {
                    android.util.Log.w("TVTown", "shelf " + s.name + ": " + e);
                }
                final List<Tmdb.Title> list = got;
                post(() -> {
                    // A title shows once, on the first shelf that has it; your list and search results keep theirs.
                    boolean own = s.name.equals("My List") || s.name.startsWith("Results");
                    for (Tmdb.Title t : list) if (own || seen.add(t.key())) s.items.add(t);
                    s.loaded = true;
                    if (s.items.isEmpty()) removeEmpty(s);
                    focusChanged();
                });
            });
        }

        /** A shelf that came back empty goes, so there are no holes. */
        private void removeEmpty(Shelf s) {
            int i = shelves.indexOf(s);
            if (i < 0 || shelves.size() == 1) return;
            shelves.remove(i);
            if (row > i) row--;
            row = Math.min(row, shelves.size() - 1);
            rowFrom = rowTo = Math.max(0, row);
        }

        // ---- the title in focus -----------------------------------------------------------------------

        @Nullable
        private Tmdb.Title focused() {
            int r = Math.max(0, row);
            if (r >= shelves.size()) return null;
            Shelf s = shelves.get(r);
            return s.items.isEmpty() ? null : s.items.get(Math.min(s.sel, s.items.size() - 1));
        }

        private void focusChanged() {
            removeCallbacks(settle);
            postDelayed(settle, 260);   // while you move quickly, nothing is fetched for what you pass
            invalidate();
        }

        private void settled() {
            Tmdb.Title t = focused();
            if (t == null || t == shown) return;
            shown = t;
            String art = t.backdropLarge();
            stage.show(art != null ? art : t.poster());
            if (!t.logoAsked) {
                t.logoAsked = true;
                t.logoAskedAt = SystemClock.uptimeMillis();
                Context ctx = getContext();
                net.execute(() -> {
                    try {
                        Catalog.details(ctx, t);
                        if (t.logoUrl == null) t.logoUrl = Catalog.logo(ctx, t);
                    } catch (RuntimeException ignored) {
                        // shown without them
                    }
                    t.logoDone = true;
                    postInvalidate();
                });
            }
            invalidate();
        }

        // ---- drawing -----------------------------------------------------------------------------------

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            stage.drawSoft(c, now, new RectF(SIDE * k, 136 * k, 1840 * k, 378 * k), 24 * k);
            c.drawText("Discover", LEFT * k, 92 * k, heading);
            drawCapsule(c);
            ask(Math.max(0, row));
            ask(Math.max(0, row) + 1);
            drawHero(c);
            float scroll = Frost.glide(rowFrom, rowTo, now, rowAt);
            int first = Math.max(0, (int) Math.floor(scroll));
            c.save();
            c.clipRect(0, 124 * k, (SIDE - 30) * k, getHeight());
            for (int i = first; i < shelves.size() && i <= first + 2; i++) drawShelf(c, shelves.get(i), i, FIRST_Y + (i - scroll) * SHELF_H, now);
            c.restore();
            boolean moving = stage.moving(now) || now - rowAt < Frost.GLIDE_MS;
            for (Shelf s : shelves) moving |= now - s.at < Frost.GLIDE_MS;
            if (moving) postInvalidateOnAnimation();
        }

        private String capWord(int i) {
            switch (i) {
                case CAP_SEARCH: return "Search";
                case CAP_TYPE: return TYPES[type];
                case CAP_LANG: return lang == 0 ? "All languages" : Tmdb.languages()[lang][0];
                case CAP_SURPRISE: return "Surprise me";
                default: return "Must-Watch";
            }
        }

        private RectF capRect(int i) {
            float right = 1840;
            for (int j = CAPS - 1; j > i; j--) right -= capText.measureText(capWord(j)) / k + 60;
            float w = capText.measureText(capWord(i)) / k + 60;
            return new RectF((right - w) * k, 40 * k, right * k, 112 * k);
        }

        private void drawCapsule(Canvas c) {
            RectF bar = new RectF(capRect(0).left, 40 * k, 1840 * k, 112 * k);
            Frost.liquid(c, bar, 36 * k, stage.frost(), stage.screen(), stage.bits(), k);
            for (int i = 0; i < CAPS; i++) {
                RectF r = capRect(i);
                if (row == -1 && cap == i) Frost.plate(c, r, 36 * k, k);
                c.drawText(capWord(i), r.left + 30 * k, r.centerY() + 9 * k, capText);
            }
        }

        /** The title in focus, in the column beside the covers: a small picture (drawn by the stage), then everything about it. */
        private void drawHero(Canvas c) {
            Tmdb.Title t = shown;
            if (t == null) return;
            List<String> m = new ArrayList<>();
            if (t.service != null) m.add(t.service.name);
            m.add(t.isSeries() ? "Series" : "Film");
            if (t.year > 0) m.add(String.valueOf(t.year));
            if (!t.genres.isEmpty()) m.add(t.genres.replace(",", " ·"));
            if (t.runtime > 0) m.add(t.runtime >= 60 ? t.runtime / 60 + " h " + t.runtime % 60 + " min" : t.runtime + " min");
            if (t.rating > 0) m.add(String.format(java.util.Locale.US, "%.1f", t.rating) + " ★");
            float y = 402;
            y = side(c, 0, t.title, heading, 2, y) + 12;
            y = side(c, 1, TextUtils.join(" · ", m), meta, 3, y) + 18;
            side(c, 2, t.overview == null ? "" : t.overview, over, Math.max(1, (int) ((1040 - y) / 40)), y);
        }

        /** A block of wrapped text in the side column (in design pixels); returns where it ends. */
        private float side(Canvas c, int slot, String text, Paint p, int lines, float y) {
            String key = lines + "|" + text;
            if (!key.equals(laidFor[slot])) {
                laidFor[slot] = key;
                laid[slot] = StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(p), Math.round((1840 - SIDE) * k)).setMaxLines(lines)
                        .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4 * k, 1).build();
            }
            c.save();
            c.translate(SIDE * k, y * k);
            laid[slot].draw(c);
            c.restore();
            return y + laid[slot].getHeight() / k;
        }

        private void drawShelf(Canvas c, Shelf s, int index, float y, long now) {
            if (y > 1080) return;
            c.drawText(s.name, LEFT * k, y * k, shelfName);
            float top = y + 18;
            if (s.items.isEmpty()) {
                for (int i = 0; i < 5; i++) {
                    RectF r = new RectF((LEFT + i * PITCH) * k, top * k, (LEFT + i * PITCH + CARD_W) * k, (top + CARD_H) * k);
                    Frost.liquid(c, r, 24 * k, stage.frost(), stage.screen(), stage.bits(), k);
                }
                return;
            }
            boolean inRow = index == row;
            float scroll = Frost.glide(s.fromX, s.toX, now, s.at);
            int from = Math.max(0, (int) (scroll / PITCH) - 1);
            for (int i = from; i < s.items.size() && i < from + 7; i++) {
                float x = LEFT + i * PITCH - scroll;
                if (x > SIDE) break;
                RectF r = new RectF(x * k, top * k, (x + CARD_W) * k, (top + CARD_H) * k);
                Tmdb.Title t = s.items.get(i);
                if (inRow && i == s.sel) Frost.plate(c, new RectF(r.left - 10 * k, r.top - 10 * k, r.right + 10 * k, r.bottom + 96 * k), 24 * k, k);
                String pic = t.poster() != null ? t.poster() : t.backdropCard();
                Bitmap b = pic != null ? ImageCache.get(pic, this::postInvalidate, null, Math.round(r.width())) : null;
                if (b != null) drawCover(c, b, r);
                else Frost.liquid(c, r, 24 * k, stage.frost(), stage.screen(), stage.bits(), k);
                c.drawText(fit(t.title, shelfName, r.width()), r.left, r.bottom + 42 * k, shelfName);
                List<String> line = new ArrayList<>();
                if (t.year > 0) line.add(String.valueOf(t.year));
                if (t.rating > 0) line.add("★ " + String.format(java.util.Locale.US, "%.1f", t.rating));
                c.drawText(fit(TextUtils.join("  ·  ", line), meta, r.width()), r.left, r.bottom + 78 * k, meta);
            }
        }

        private final android.graphics.Path clip = new android.graphics.Path();

        private void drawCover(Canvas c, Bitmap b, RectF r) {
            float want = r.width() / r.height(), have = b.getWidth() / (float) b.getHeight();
            android.graphics.Rect from;
            if (have > want) {
                int w = Math.round(b.getHeight() * want), x0 = (b.getWidth() - w) / 2;
                from = new android.graphics.Rect(x0, 0, x0 + w, b.getHeight());
            } else {
                int h = Math.round(b.getWidth() / want), y0 = Math.round((b.getHeight() - h) * 0.2f);
                from = new android.graphics.Rect(0, y0, b.getWidth(), y0 + h);
            }
            c.save();
            clip.reset();
            clip.addRoundRect(r, 24 * k, 24 * k, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(b, from, r, stage.bits());
            c.restore();
        }

        private static String fit(String s, Paint p, float room) {
            return TextUtils.ellipsize(s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
        }

        // ---- the remote -----------------------------------------------------------------------------------

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            long now = SystemClock.uptimeMillis();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row > 0) moveRow(row - 1, now);
                    else row = -1;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (row == -1) row = 0;
                    else if (row < shelves.size() - 1) moveRow(row + 1, now);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (row == -1) cap = Math.max(0, Math.min(CAPS - 1, cap + dir));
                    else if (row < shelves.size()) {
                        Shelf s = shelves.get(row);
                        int to = Math.max(0, Math.min(s.items.size() - 1, s.sel + dir));
                        if (to != s.sel) {
                            s.fromX = Frost.glide(s.fromX, s.toX, now, s.at);
                            s.sel = to;
                            s.toX = Math.max(0, (to - 1) * PITCH);
                            s.at = now;
                        }
                    }
                    break;
                }
                default:
                    if (!ok) return super.onKeyDown(code, e);
                    press();
            }
            focusChanged();
            return true;
        }

        private void moveRow(int to, long now) {
            rowFrom = Frost.glide(rowFrom, rowTo, now, rowAt);
            row = to;
            rowTo = to;
            rowAt = now;
        }

        private void press() {
            if (row >= 0) {
                Tmdb.Title t = focused();
                if (t != null) TitleActivity.open(act, t);
                return;
            }
            switch (cap) {
                case CAP_SEARCH:
                    TvKeyboard.open(act, "Film, series, song, singer or app", query, true, new TvKeyboard.Listener() {
                        @Override
                        public void onSearch(String text) {
                            if (!text.trim().isEmpty()) search(text.trim());
                        }

                        @Override
                        public boolean onPick(TvKeyboard.Hit hit) {
                            if (hit.kind != TvKeyboard.STREAM) return false;
                            search(hit.title);
                            return true;
                        }

                        @Override
                        public void onClosed() {
                            requestFocus();
                        }
                    });
                    break;
                case CAP_TYPE:
                    type = (type + 1) % TYPES.length;
                    buildShelves();
                    row = -1;
                    break;
                case CAP_LANG:
                    lang = (lang + 1) % Tmdb.languages().length;
                    buildShelves();
                    row = -1;
                    break;
                case CAP_SURPRISE: {   // something from what's been loaded, straight to its page
                    List<Tmdb.Title> all = new ArrayList<>();
                    for (Shelf s : shelves) all.addAll(s.items);
                    if (!all.isEmpty()) TitleActivity.open(act, all.get(random.nextInt(all.size())));
                    break;
                }
                default:
                    act.startActivity(new Intent(act, MustWatchActivity.class));
            }
        }
    }
}
