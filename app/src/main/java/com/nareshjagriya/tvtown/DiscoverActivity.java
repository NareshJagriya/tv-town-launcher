package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Discover. Down the left, one list: New, Trending, Top rated and Hidden gems, then the genres (DiscoverKinds). On the
 * right, a row for each of your apps: four covers in view, sliding sideways. Search, Movies / Series and the language
 * sit in the bar at the top (the last two drop down).
 *
 * The remote, everywhere the same: Right goes towards the covers, Left back towards the list, Up to the bar from the
 * top of either, Back steps out one level. Links can open it at a kind (EXTRA_KIND, e.g. "anime") or with a search.
 */
public class DiscoverActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    /** A search to run straight away (voice, or a suggestion from the keyboard). */
    static final String EXTRA_QUERY = "query";
    /** A kind to open at: an id from DiscoverKinds ("anime", "top-rated") or loose words ("K Drama"). */
    static final String EXTRA_KIND = "kind";

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
        DiscoverKinds.Kind kd = DiscoverKinds.find(getIntent().getStringExtra(EXTRA_KIND));
        if (kd != null) page.openAt(kd.id);
    }

    @Override
    public void onBackPressed() {
        if (!TvKeyboard.back() && !page.back()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        page.net.shutdownNow();
    }

    private interface Loader {
        List<Tmdb.Title> load() throws Exception;
    }

    /** One app's row of titles on the right. */
    private static final class Shelf {
        final String name;
        @Nullable
        final Loader loader;   // null: filled already (search results)
        final List<Tmdb.Title> items = new ArrayList<>();
        boolean asked, loaded;
        int sel;
        float fromX, toX;      // the first cover in view, gliding
        long at;

        Shelf(String name, @Nullable Loader loader) {
            this.name = name;
            this.loader = loader;
            if (loader == null) asked = loaded = true;
        }
    }

    /** A kind on the left with its rows on the right, built the first time you stop on it. */
    private static final class Entry {
        final String id, name;
        final boolean genre;
        final List<Shelf> rows = new ArrayList<>();
        boolean built, searched;   // searched: the search's answer came (results only)
        int row;
        float fromY, toY;
        long at;

        Entry(String id, String name, boolean genre) {
            this.id = id;
            this.name = name;
            this.genre = genre;
        }
    }

    private static final class Page extends View {

        private static final String RESULTS = "results";
        private static final String[] TYPES = {L10n.t("Movies and series"), L10n.t("Movies"), L10n.t("Series")};
        private static final int CAP_SEARCH = 0, CAP_TYPE = 1, CAP_LANG = 2, CAPS = 3;
        private static final int RAIL = 0, ROWS = 1, BAR = 2;   // where the focus is
        // Layout, in design pixels: the list on the left, the rows from ROWS_X to the screen's edge.
        private static final float LEFT = 80, RAIL_W = 380, TOP = 150, ITEM_H = 64, GENRES_GAP = 58, ROWS_X = 520,
                ROWS_R = 1840, GAP = 24, COLS = 4, HEAD_H = 52, TEXT_H = 84, MENU_ROW = 70;
        private static final int MENU_ROWS = 9;

        final ExecutorService net = Work.pool(3);
        private final Activity act;
        private final float k;
        private final ArtStage stage;
        private final GlassKit kit;
        private final Paint heading, meta, coverName, rowHead, rowCount, capText, railText, label;
        private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable settle = this::settled;
        private final List<Entry> kinds = new ArrayList<>();
        private int focus = RAIL, focusBeforeBar = RAIL;
        private int kindSel, cap, type, lang;
        private float railFrom, railTo;
        private long railAt;
        private int menu = -1, menuSel, menuTop;   // a drop-down under Movies / Series or the language
        private int shownKind = -1;                // the kind whose rows are loading or loaded
        private boolean enterWhenLoaded;           // Right pressed before the rows came: go in when they do
        @Nullable
        private String query;
        @Nullable
        private Tmdb.Title shown;

        Page(Activity act) {
            super(act);
            this.act = act;
            k = act.getResources().getDisplayMetrics().widthPixels / 1920f;
            stage = new ArtStage(this);
            stage.black = true;
            NoirKit nk = new NoirKit(act, k);
            kit = new GlassKit(act, null, k);
            heading = shade(nk.inter(40, 600, 0, Color.WHITE));
            meta = shade(nk.inter(24, 500, 0, Color.WHITE));
            coverName = shade(nk.inter(28, 600, 0, Color.WHITE));
            rowHead = shade(nk.inter(30, 600, 0, Color.WHITE));
            rowCount = shade(nk.inter(24, 500, 0, 0xB4FFFFFF));
            capText = nk.inter(24, 500, 0, Color.WHITE);
            railText = nk.inter(27, 500, 0, Color.WHITE);
            label = nk.inter(21, 600, 0.08f, 0xA0FFFFFF);
            buildKinds();
            focusChanged();   // the first kind's rows start loading
        }

        private Paint shade(Paint p) {
            p.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
            return p;
        }

        // ---- the kinds and their rows ------------------------------------------------------------------

        private void buildKinds() {
            kinds.clear();
            shownKind = -1;
            shown = null;
            if (query != null) kinds.add(new Entry(RESULTS, "“" + query + "”", false));
            for (DiscoverKinds.Kind kd : DiscoverKinds.ALL) kinds.add(new Entry(kd.id, kd.name, kd.genre));
            kindSel = Math.max(0, Math.min(kindSel, kinds.size() - 1));
            invalidate();
        }

        void search(String q) {
            query = q;
            buildKinds();
            kindSel = 0;
            focus = RAIL;
            focusChanged();
        }

        /** Opens on a kind, its rows loading. */
        void openAt(String id) {
            for (int i = 0; i < kinds.size(); i++) if (kinds.get(i).id.equals(id)) kindSel = i;
            focus = RAIL;
            focusChanged();
        }

        private WheelArt.Glyph glyphOf(Entry e) {
            switch (e.id) {
                case RESULTS: return WheelArt.Glyph.SEARCH;
                case DiscoverKinds.NEW: return WheelArt.Glyph.SUN;
                case DiscoverKinds.TRENDING: return WheelArt.Glyph.PULSE;
                case DiscoverKinds.TOP_RATED: return WheelArt.Glyph.STAR;
                default: return WheelArt.Glyph.TELESCOPE;
            }
        }

        /** The rows for a kind: one per app (search results: by the app each is on). */
        private void build(Entry e) {
            if (e.built) return;
            e.built = true;
            Context ctx = getContext();
            String code = Tmdb.languages()[lang][1];
            if (e.id.equals(RESULTS)) {
                final String q = query;
                net.execute(() -> {
                    List<Tmdb.Title> got = new ArrayList<>();
                    try {
                        for (Tmdb.Title t : Catalog.search(ctx, q, 1).titles) if (typeOk(t)) got.add(t);
                    } catch (Exception ex) {
                        android.util.Log.w("TVTown", "search: " + ex);
                    }
                    post(() -> {
                        Map<String, Shelf> by = new LinkedHashMap<>();
                        for (Tmdb.Title t : got) {
                            String app = t.service != null ? t.service.name : L10n.t("Elsewhere");
                            Shelf sh = by.get(app);
                            if (sh == null) by.put(app, sh = new Shelf(app, null));
                            sh.items.add(t);
                        }
                        e.rows.clear();
                        e.rows.addAll(by.values());
                        e.searched = true;
                        loaded();
                    });
                });
                return;
            }
            DiscoverKinds.Kind kind = DiscoverKinds.find(e.id);
            Tmdb.Category cat = kind != null ? DiscoverKinds.category(kind) : null;
            if (e.id.equals(DiscoverKinds.NEW)) becauseYouWatched(e);
            for (Services.Service s : Services.installedOrPopular(ctx)) {
                Loader l;
                if (e.id.equals(DiscoverKinds.NEW)) {
                    List<Services.Service> one = Collections.singletonList(s);
                    l = () -> {
                        List<Tmdb.Title> out = new ArrayList<>();
                        for (Tmdb.Title t : Catalog.newOn(ctx, one, 30)) if (typeOk(t) && hasPicture(t)) out.add(t);
                        return out;
                    };
                } else l = () -> discover(cat, s, code);
                e.rows.add(new Shelf(s.name, l));
            }
        }

        /**
         * The first row where Discover opens: titles like the last film or series you marked Watched (the best rated
         * of your last five), leaving out what you've already seen. No watched titles, or no TMDB: no row.
         */
        private void becauseYouWatched(Entry e) {
            Context ctx = getContext();
            if (!Catalog.tmdb()) return;
            List<Library.Entry> seen = new ArrayList<>();
            for (Library.Entry x : Library.all(ctx)) if (x.status == Library.WATCHED && x.tmdbId > 0) seen.add(x);
            if (seen.isEmpty()) return;
            Collections.sort(seen, (a, b) -> Long.compare(b.time, a.time));
            Library.Entry from = seen.get(0);
            for (int i = 1; i < Math.min(5, seen.size()); i++) if (seen.get(i).rating > from.rating) from = seen.get(i);
            final Library.Entry src = from;
            final java.util.Set<String> watched = Library.watched(ctx);
            e.rows.add(new Shelf(L10n.t("Because you watched ") + src.title, () -> {
                List<Tmdb.Title> out = new ArrayList<>();
                for (Tmdb.Title t : Tmdb.recommendations(ctx, "tv".equals(src.type) ? "tv" : "movie", src.tmdbId)) {
                    if (out.size() < 20 && typeOk(t) && hasPicture(t) && !watched.contains(Library.keyOf(t))) out.add(t);
                }
                return out;
            }));
        }

        private boolean typeOk(Tmdb.Title t) {
            return type == 0 || (type == 1) != t.isSeries();
        }

        private static boolean hasPicture(Tmdb.Title t) {
            return t.backdropCard() != null || t.poster() != null;
        }

        /** A category on one app, films and series in turn. A kind with its own language (Anime, K-Drama) keeps it. */
        private List<Tmdb.Title> discover(@Nullable Tmdb.Category c, Services.Service s, String code) throws Exception {
            List<Tmdb.Title> out = new ArrayList<>();
            if (c == null) return out;
            String lc = c.language != null ? "" : code;
            List<List<Tmdb.Title>> lists = new ArrayList<>();
            for (String kind : type == 0 ? new String[]{"movie", "tv"} : new String[]{type == 1 ? "movie" : "tv"}) {
                Tmdb.Page p = Catalog.discover(getContext(), c, s, kind, 1, lc);
                if (p != null) lists.add(p.titles);
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; out.size() < 24 && i < 24; i++) {
                for (List<Tmdb.Title> l : lists) {
                    if (i < l.size() && hasPicture(l.get(i)) && seen.add(l.get(i).key())) out.add(l.get(i));
                }
            }
            return out;
        }

        private void ask(Entry e, Shelf s) {
            if (s.asked || s.loader == null) return;
            s.asked = true;
            net.execute(() -> {
                List<Tmdb.Title> got = new ArrayList<>();
                try {
                    got = s.loader.load();
                } catch (Exception ex) {
                    android.util.Log.w("TVTown", "row " + s.name + ": " + ex);
                }
                final List<Tmdb.Title> list = got;
                post(() -> {
                    s.items.addAll(list);
                    s.loaded = true;
                    if (s.items.isEmpty()) {   // an app with nothing here: its row goes, so there are no holes
                        int i = e.rows.indexOf(s);
                        if (i >= 0) {
                            e.rows.remove(i);
                            if (e.row > i) e.row--;
                            e.row = Math.max(0, Math.min(e.row, e.rows.size() - 1));
                            e.fromY = e.toY = e.row;
                        }
                    }
                    loaded();
                });
            });
        }

        /** Something arrived: Right pressed while loading goes in now; with nothing at all, the focus stays left. */
        private void loaded() {
            Entry e = kinds.get(kindSel);
            if (enterWhenLoaded && hasCovers(e)) {
                enterWhenLoaded = false;
                enterRows();
            }
            if (focus == ROWS && !hasCovers(e)) focus = RAIL;
            focusChanged();
        }

        private static boolean hasCovers(Entry e) {
            for (Shelf s : e.rows) if (!s.items.isEmpty()) return true;
            return false;
        }

        private static boolean stillLoading(Entry e) {
            if (!e.built || e.id.equals(RESULTS) && !e.searched) return true;
            for (Shelf s : e.rows) if (!s.loaded) return true;
            return false;
        }

        // ---- the title in focus -----------------------------------------------------------------------

        @Nullable
        private Shelf focusedRow() {
            Entry e = kinds.get(kindSel);
            return e.rows.isEmpty() ? null : e.rows.get(Math.min(e.row, e.rows.size() - 1));
        }

        @Nullable
        private Tmdb.Title focused() {
            Shelf s = focusedRow();
            return s == null || s.items.isEmpty() ? null : s.items.get(Math.min(s.sel, s.items.size() - 1));
        }

        private void focusChanged() {
            removeCallbacks(settle);
            postDelayed(settle, 260);   // while you move quickly, nothing is fetched for what you pass
            invalidate();
        }

        private void settled() {
            if (shownKind != kindSel) {   // the kind you stopped on: its rows load
                shownKind = kindSel;
                Entry e = kinds.get(kindSel);
                build(e);
                for (Shelf s : e.rows) ask(e, s);
            }
            Tmdb.Title t = focus == ROWS ? focused() : null;
            if (t == null) t = firstCover(kinds.get(kindSel));   // the picture behind: the focused cover, else the kind's first
            if (t != null && t != shown) {
                shown = t;
                String art = t.backdropLarge();
                stage.show(art != null ? art : t.poster());
            }
            invalidate();
        }

        @Nullable
        private static Tmdb.Title firstCover(Entry e) {
            for (Shelf s : e.rows) if (!s.items.isEmpty()) return s.items.get(0);
            return null;
        }

        // ---- drawing -----------------------------------------------------------------------------------

        private RectF gr(float x, float y, float w, float h) {
            return new RectF(x * k, y * k, (x + w) * k, (y + h) * k);
        }

        @Override
        protected void onDraw(Canvas c) {
            Frost.frame(this);   // selections glide (Frost.plate)
            long now = SystemClock.uptimeMillis();
            stage.drawSoft(c, now, new RectF(), 24 * k);
            c.drawText(L10n.t("Discover"), LEFT * k, 92 * k, heading);
            Entry e = kinds.get(kindSel);
            drawRail(c, now);
            drawRows(c, e, now);
            drawBar(c);
            if (menu >= 0) drawMenu(c);
            boolean moving = stage.moving(now) || now - railAt < Frost.GLIDE_MS || now - e.at < Frost.GLIDE_MS;
            for (Shelf sh : e.rows) moving |= now - sh.at < Frost.GLIDE_MS;
            if (moving) postInvalidateOnAnimation();
        }

        /** Where a kind sits in the list, before scrolling: the first ones together, the genres after a heading. */
        private float itemY(int i) {
            float y = TOP + i * ITEM_H;
            int firstGenre = -1;
            for (int j = 0; j < kinds.size(); j++) if (kinds.get(j).genre) {
                firstGenre = j;
                break;
            }
            return firstGenre >= 0 && i >= firstGenre ? y + GENRES_GAP : y;
        }

        /** The list down the left: one column, scrolling to keep the chosen kind in view. */
        private void drawRail(Canvas c, long now) {
            float want = railTo, y = itemY(kindSel);
            if (y - want > 1000 - ITEM_H) want = y - (1000 - ITEM_H);
            if (y - want < TOP + (kindSel == 0 ? 0 : ITEM_H)) want = Math.max(0, y - TOP - (kindSel == 0 ? 0 : ITEM_H));
            if (want != railTo) {
                railFrom = Frost.glide(railFrom, railTo, now, railAt);
                railTo = want;
                railAt = now;
            }
            float scroll = Frost.glide(railFrom, railTo, now, railAt);
            // The rail is one piece of Home's smoked glass, from the title to the foot of the screen
            RectF panel = gr(LEFT - 18, 132, RAIL_W + 36, 1040 - 132);
            Frost.smoked(c, panel, 36 * k, k);
            c.save();
            c.clipRect(panel.left, panel.top + 8 * k, panel.right, panel.bottom - 8 * k);
            for (int i = 0; i < kinds.size(); i++) {
                Entry e = kinds.get(i);
                float top = itemY(i) - scroll;
                if (top + ITEM_H < 120 || top > 1080) continue;
                if (e.genre && (i == 0 || !kinds.get(i - 1).genre)) {   // a fine line, then the heading
                    plain.setColor(0x26FFFFFF);
                    c.drawRect((LEFT + 18) * k, (top - 50) * k, (LEFT + RAIL_W - 18) * k, (top - 50) * k + Math.max(1, k), plain);
                    c.drawText(L10n.t("GENRES"), (LEFT + 22) * k, (top - 16) * k, label);
                }
                RectF r = gr(LEFT, top, RAIL_W, ITEM_H - 6);
                boolean on = i == kindSel, here = on && focus == RAIL && menu < 0;
                if (here) Frost.plate(c, r, 22 * k, k);   // the focus: the clear glass every list in TV Town has
                else if (on) {   // chosen, but the focus is on the posters: a quiet pill
                    plain.setColor(0x1FFFFFFF);
                    c.drawRoundRect(r, 22 * k, 22 * k, plain);
                }
                if (on) {   // the chosen kind's mark: a short jewel bar at the left edge
                    plain.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, 0xFF8A6BFF, 0xFFD9468F, android.graphics.Shader.TileMode.CLAMP));
                    plain.setAlpha(here ? 255 : 200);
                    float bh = r.height() * 0.46f;
                    c.drawRoundRect(r.left + 8 * k, r.centerY() - bh / 2, r.left + 13 * k, r.centerY() + bh / 2, 3 * k, 3 * k, plain);
                    plain.setShader(null);
                    plain.setAlpha(255);
                }
                railText.setAlpha(on ? 255 : 190);
                railText.setFakeBoldText(on);
                float tx = r.left + 30 * k;
                if (!e.genre) {
                    kit.drawGlyph(c, kit.glyph(glyphOf(e), 28), r.left + 48 * k, r.centerY(), on ? 255 : 190);
                    tx = r.left + 80 * k;
                } else {   // a genre: a small dot in place of an icon, so the column lines up
                    plain.setColor(on ? 0xFFE7DCFF : 0x66FFFFFF);
                    c.drawCircle(r.left + 48 * k, r.centerY(), 4.5f * k, plain);
                    tx = r.left + 80 * k;
                }
                c.drawText(fit(e.name, railText, r.right - tx - 20 * k), tx, r.centerY() + 10 * k, railText);
            }
            railText.setAlpha(255);
            railText.setFakeBoldText(false);
            c.restore();
        }

        private static float cardW() {
            return (ROWS_R - ROWS_X - (COLS - 1) * GAP) / COLS;
        }

        private static float cardH() {
            return cardW() * 4 / 3f;
        }

        private static float rowStep() {
            return HEAD_H + cardH() + TEXT_H + GAP;
        }

        /** A row for each app: its name and count, then its covers, four in view and the next peeking in at the edge. */
        private void drawRows(Canvas c, Entry e, long now) {
            float w = cardW(), h = cardH();
            c.save();
            c.clipRect((ROWS_X - 20) * k, 128 * k, getWidth(), getHeight());
            if (shownKind != kindSel || e.rows.isEmpty() && stillLoading(e)) {   // on its way
                for (int i = 0; i < COLS; i++) blank(c, gr(ROWS_X + i * (w + GAP), TOP + HEAD_H, w, h));
                c.restore();
                return;
            }
            if (!hasCovers(e) && !stillLoading(e)) {
                boolean narrowed = type != 0 || lang != 0;
                c.drawText(e.id.equals(RESULTS) ? L10n.t("Nothing found") : L10n.t("Nothing here on your apps"), ROWS_X * k, (TOP + 40) * k, rowHead);
                c.drawText(narrowed ? "Try Movies and series, or All languages" : e.id.equals(RESULTS) ? "Try other words" : "Try another kind on the left",
                        ROWS_X * k, (TOP + 84) * k, meta);
                c.restore();
                return;
            }
            float first = Frost.glide(e.fromY, e.toY, now, e.at);
            for (int r = Math.max(0, (int) Math.floor(first)); r < e.rows.size(); r++) {
                float y = TOP + (r - first) * rowStep();
                if (y > 1080) break;
                Shelf sh = e.rows.get(r);
                c.drawText(sh.name, ROWS_X * k, (y + 34) * k, rowHead);
                if (sh.loaded && !sh.items.isEmpty()) {
                    String n = sh.items.size() + (sh.items.size() == 1 ? L10n.t(" title") : L10n.t(" titles"));
                    c.drawText(n, ROWS_X * k + rowHead.measureText(sh.name) + 18 * k, (y + 34) * k, rowCount);
                }
                if (!sh.loaded) {
                    for (int i = 0; i < COLS; i++) blank(c, gr(ROWS_X + i * (w + GAP), y + HEAD_H, w, h));
                    continue;
                }
                float scroll = Frost.glide(sh.fromX, sh.toX, now, sh.at);
                for (int i = Math.max(0, (int) Math.floor(scroll)); i < sh.items.size() && i < scroll + COLS + 1; i++) {
                    RectF rc = gr(ROWS_X + (i - scroll) * (w + GAP), y + HEAD_H, w, h);
                    Tmdb.Title t = sh.items.get(i);
                    boolean chosen = focus == ROWS && r == e.row && i == sh.sel, rowOn = focus == ROWS && r == e.row;
                    RectF pr = chosen ? Frost.posterGrown(rc, Frost.POSTER_GROW) : rc;   // the chosen poster grows; no frame round it
                    if (chosen) Frost.posterShadow(c, pr, 24 * k, k);
                    String pic = t.poster() != null ? t.poster() : t.backdropCard();
                    Bitmap b = pic != null ? ImageCache.get(pic, this::postInvalidate, null, Math.round(rc.width())) : null;
                    if (b != null) drawCover(c, b, pr);
                    else blank(c, pr);
                    if (chosen) Frost.posterRing(c, pr, 24 * k, k);
                    else if (rowOn) Frost.posterDim(c, rc, 24 * k);
                    coverName.setAlpha(chosen || !rowOn ? 255 : 170);
                    meta.setAlpha(chosen || !rowOn ? 255 : 150);
                    float ty = chosen ? pr.bottom - rc.bottom : 0;   // the words move down with the grown poster
                    c.drawText(fit(t.title, coverName, rc.width()), rc.left, rc.bottom + 38 * k + ty, coverName);
                    List<String> line = new ArrayList<>();
                    line.add(t.isSeries() ? L10n.t("Series") : L10n.t("Film"));
                    if (t.year > 0) line.add(String.valueOf(t.year));
                    if (t.rating > 0) line.add("★ " + String.format(java.util.Locale.US, "%.1f", t.rating));
                    c.drawText(fit(TextUtils.join("  ·  ", line), meta, rc.width()), rc.left, rc.bottom + 70 * k + ty, meta);
                    coverName.setAlpha(255);
                    meta.setAlpha(255);
                }
            }
            c.restore();
        }

        private void blank(Canvas c, RectF r) {
            Frost.surface(c, r, 24 * k, k);
        }

        private String capWord(int i) {
            switch (i) {
                case CAP_SEARCH: return query != null ? query : L10n.t("Search");
                case CAP_TYPE: return TYPES[type] + "  ▾";
                default: return (lang == 0 ? L10n.t("All languages") : Tmdb.languages()[lang][0]) + "  ▾";
            }
        }

        private RectF capRect(int i) {
            float right = 1840;
            for (int j = CAPS - 1; j > i; j--) right -= capW(j);
            if (i == CAP_SEARCH) right -= 16;   // the search bar stands on its own
            return new RectF((right - capW(i)) * k, 40 * k, right * k, 112 * k);
        }

        private float capW(int i) {
            if (i == CAP_SEARCH) return 330;   // a search bar, the same on every page
            return capText.measureText(capWord(i)) / k + 60;
        }

        private void drawBar(Canvas c) {
            RectF bar = new RectF(capRect(CAP_SEARCH + 1).left, 40 * k, 1840 * k, 112 * k);
            Frost.surface(c, bar, 36 * k, k);
            for (int i = 0; i < CAPS; i++) {
                RectF r = capRect(i);
                if (i == CAP_SEARCH) {   // a black box to type in, ringed when chosen
                    Frost.searchBar(c, r, k, focus == BAR && cap == i, query != null ? query : L10n.t("Search films"), query == null, capText);
                    continue;
                }
                if (focus == BAR && cap == i && menu != i) Frost.plate(c, r, 36 * k, k);
                c.drawText(capWord(i), r.left + 30 * k, r.centerY() + 9 * k, capText);
            }
        }

        private String[] menuItems() {
            if (menu == CAP_TYPE) return TYPES;
            String[][] l = Tmdb.languages();
            String[] out = new String[l.length];
            for (int i = 0; i < l.length; i++) out[i] = i == 0 ? L10n.t("All languages") : l[i][0];
            return out;
        }

        /** Movies / Series or the language: a list dropping from its button, the current one ticked. */
        private void drawMenu(Canvas c) {
            String[] it = menuItems();
            int n = Math.min(MENU_ROWS, it.length);
            if (menuSel < menuTop) menuTop = menuSel;
            if (menuSel >= menuTop + n) menuTop = menuSel - n + 1;
            RectF from = capRect(menu);
            float rowH = MENU_ROW * k, w = Math.max(from.width(), 400 * k);
            RectF box = new RectF(from.right - w, 124 * k, from.right, 124 * k + n * rowH + 24 * k);
            Frost.dropdown(c, box, 30 * k, new RectF(0, 0, getWidth(), getHeight()), stage.bits(), k);
            int now = menu == CAP_TYPE ? type : lang;
            for (int v = 0; v < n; v++) {
                int i = menuTop + v;
                RectF r = new RectF(box.left + 12 * k, box.top + 12 * k + v * rowH, box.right - 12 * k, box.top + 12 * k + (v + 1) * rowH);
                if (i == menuSel) Frost.lift(c, r, 20 * k, k);
                c.drawText(fit(it[i], railText, r.width() - 90 * k), r.left + 26 * k, r.centerY() + 10 * k, railText);
                if (i == now) Frost.tick(c, r.right - 32 * k, r.centerY(), 13 * k, k);
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
        public boolean onKeyDown(int code, KeyEvent ev) {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN,
                    left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (!ok && !up && !down && !left && !right) return super.onKeyDown(code, ev);
            long now = SystemClock.uptimeMillis();
            if (menu >= 0) menuKey(ok, up, down);
            else if (focus == BAR) barKey(ok, down, left, right);
            else if (focus == ROWS) rowsKey(ok, up, down, left, now);
            else railKey(ok, up, down, right);
            focusChanged();
            return true;
        }

        /** Back: closes a drop-down, then steps from the bar or the covers to the list, then leaves. */
        boolean back() {
            if (menu >= 0) menu = -1;
            else if (focus != RAIL) focus = RAIL;
            else return false;
            invalidate();
            return true;
        }

        private void toBar() {
            focusBeforeBar = focus;
            focus = BAR;
        }

        private void railKey(boolean ok, boolean up, boolean down, boolean right) {
            enterWhenLoaded = false;
            if (up) {
                if (kindSel > 0) kindSel--;
                else toBar();
            } else if (down) {
                if (kindSel < kinds.size() - 1) kindSel++;
            } else if (right || ok) enterRows();
        }

        /** Into the covers: at once when they're there, else as soon as they come. */
        private void enterRows() {
            if (shownKind != kindSel) settled();
            Entry e = kinds.get(kindSel);
            if (!hasCovers(e)) {
                enterWhenLoaded = stillLoading(e);
                return;
            }
            focus = ROWS;
            if (e.rows.get(Math.min(e.row, e.rows.size() - 1)).items.isEmpty())
                for (int i = 0; i < e.rows.size(); i++) if (!e.rows.get(i).items.isEmpty()) {
                    rowTo(e, i, SystemClock.uptimeMillis());
                    break;
                }
        }

        private void rowsKey(boolean ok, boolean up, boolean down, boolean left, long now) {
            Entry e = kinds.get(kindSel);
            Shelf sh = focusedRow();
            if (sh == null) {
                focus = RAIL;
                return;
            }
            if (ok) {
                Tmdb.Title t = focused();
                if (t != null) TitleActivity.open(act, t);
            } else if (up) {
                if (e.row > 0) rowTo(e, e.row - 1, now);
                else toBar();
            } else if (down) {
                if (e.row < e.rows.size() - 1) rowTo(e, e.row + 1, now);
            } else if (left) {
                if (sh.sel == 0) focus = RAIL;   // back to the list
                else selTo(sh, sh.sel - 1, now);
            } else if (sh.sel + 1 < sh.items.size()) selTo(sh, sh.sel + 1, now);
        }

        /** Another row, the focus staying in the same place across the screen. */
        private void rowTo(Entry e, int r, long now) {
            Shelf from = focusedRow(), to = e.rows.get(r);
            int col = from == null ? 0 : from.sel - Math.round(from.toX);
            if (!to.items.isEmpty()) to.sel = Math.max(0, Math.min(to.items.size() - 1, Math.round(to.toX) + col));
            e.fromY = Frost.glide(e.fromY, e.toY, now, e.at);
            e.row = r;
            e.toY = r;
            e.at = now;
        }

        /** A cover chosen in its row, the row sliding so it shows. */
        private void selTo(Shelf sh, int to, long now) {
            sh.fromX = Frost.glide(sh.fromX, sh.toX, now, sh.at);
            sh.sel = to;
            float first = sh.toX;
            if (to < first) first = to;
            if (to > first + COLS - 1) first = to - COLS + 1;
            sh.toX = first;
            sh.at = now;
        }

        private void barKey(boolean ok, boolean down, boolean left, boolean right) {
            if (left) cap = Math.max(0, cap - 1);
            else if (right) cap = Math.min(CAPS - 1, cap + 1);
            else if (down) {
                if (cap == CAP_SEARCH) focus = focusBeforeBar == ROWS && hasCovers(kinds.get(kindSel)) ? ROWS : RAIL;
                else openMenu(cap);   // Movies / Series and the language: Down opens their list, as in My Movies
            } else if (ok) {
                if (cap == CAP_SEARCH) askSearch();
                else openMenu(cap);
            }
        }

        private void openMenu(int which) {
            menu = which;
            menuSel = which == CAP_TYPE ? type : lang;
            menuTop = Math.max(0, menuSel - MENU_ROWS / 2);
        }

        private void menuKey(boolean ok, boolean up, boolean down) {
            int n = menuItems().length;
            if (up) {
                if (menuSel > 0) menuSel--;
                else menu = -1;   // up off the top: closed, back on its button
            } else if (down) menuSel = Math.min(n - 1, menuSel + 1);
            else if (ok) {
                boolean changed = menu == CAP_TYPE ? type != menuSel : lang != menuSel;
                if (menu == CAP_TYPE) type = menuSel;
                else lang = menuSel;
                menu = -1;
                if (changed) {   // everything loads again for the new choice, on the same kind
                    String id = kinds.get(kindSel).id;
                    buildKinds();
                    openAt(id);
                    focus = BAR;
                }
            } else menu = -1;   // Left or Right: closed
        }

        private void askSearch() {
            TvKeyboard.open(act, L10n.t("Film, series, song, singer or app"), query, true, new TvKeyboard.Listener() {
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
        }
    }
}
