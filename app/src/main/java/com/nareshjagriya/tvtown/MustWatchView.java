package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Must-Watch: the best rated films and series, streaming in India or not, as a grid of big posters. Each card shows
 * the year, IMDb rating, Rotten Tomatoes score, where it streams (or "Not streaming"), a ★ for award winners, and
 * your own mark (✅ watched, 🔖 want to watch, ★ your rating). Movies / Series at the top right; under the heading a
 * bar of chips, each opening a short list: which list (Top rated, Award winners, Critics' picks), language, genre,
 * decade, status (All, Hide watched, Want to Watch, Watched, Rated by me) and sort (IMDb, critics, year, my rating).
 * OK on a card opens its details with Play, Trailer, Watched, Want to Watch and Rate (1 to 10).
 */
public class MustWatchView extends View implements Choreographer.FrameCallback {

    interface Listener {
        void onOpen(Tmdb.Title title, Services.Service service);

        void onTrailer(Tmdb.Title title);
    }

    // ---- the choices -------------------------------------------------------------------------------

    static final String[][] LISTS = {{"", L10n.t("Top rated")}, {"", L10n.t("Award winners")}, {"", L10n.t("Critics' picks")}};
    static final String[][] GENRES = {{"", L10n.t("All genres"), ""}, {"act", L10n.t("Action"), ""}, {"ani", L10n.t("Animation"), ""},
            {"cmy", L10n.t("Comedy"), ""}, {"crm", L10n.t("Crime"), ""}, {"doc", L10n.t("Documentary"), ""}, {"drm", L10n.t("Drama"), ""},
            {"fml", L10n.t("Family"), ""}, {"fnt", L10n.t("Fantasy"), ""}, {"hst", L10n.t("History"), ""}, {"hrr", L10n.t("Horror"), ""},
            {"msc", L10n.t("Music"), ""}, {"rma", L10n.t("Romance"), ""}, {"scf", L10n.t("Sci-Fi"), ""}, {"spt", L10n.t("Sport"), "★"},
            {"trl", L10n.t("Thriller"), ""}, {"war", L10n.t("War"), ""}, {"wsn", L10n.t("Western"), ""}};
    static final String[][] LANGUAGES = {{L10n.t("All"), ""}, {L10n.t("Hindi"), "hi"}, {L10n.t("English"), "en"}, {L10n.t("Marathi"), "mr"},
            {L10n.t("Gujarati"), "gu"}, {L10n.t("Malayalam"), "ml"}, {L10n.t("Tamil"), "ta"}, {L10n.t("Telugu"), "te"}, {L10n.t("Korean"), "ko"}};
    static final String[][] GLOBAL_LANGUAGES = {{L10n.t("All"), ""}, {L10n.t("English"), "en"}, {L10n.t("Spanish"), "es"}, {L10n.t("French"), "fr"},
            {L10n.t("Korean"), "ko"}, {L10n.t("Japanese"), "ja"}, {L10n.t("German"), "de"}, {L10n.t("Italian"), "it"}, {L10n.t("Hindi"), "hi"}};

    /** The language chip's list for the content style. */
    static String[][] languages() {
        return Style.global() ? GLOBAL_LANGUAGES : LANGUAGES;
    }
    static final String[] DECADES = {L10n.t("Any decade"), "2020s", "2010s", "2000s", "1990s", "1980s", "1970s", L10n.t("Before 1970")};
    static final String[][] STATUSES = {{"", L10n.t("All")}, {"", L10n.t("Hide watched")}, {"", L10n.t("Want to Watch")}, {"", L10n.t("Watched")},
            {"", L10n.t("Rated by me")}};
    static final String[] SORTS = {L10n.t("IMDb"), L10n.t("Critics"), L10n.t("Year"), L10n.t("My rating")};
    static final int ALL = 0, HIDE_WATCHED = 1, WANT = 2, WATCHED = 3, RATED = 4;
    static final int BY_IMDB = 0, BY_CRITICS = 1, BY_YEAR = 2, BY_MINE = 3;

    private static final int TAB_MOVIES = 0, TAB_SERIES = 1, CHIP_LIST = 2, CHIP_LANG = 3, CHIP_GENRE = 4, CHIP_DECADE = 5,
            CHIP_STATUS = 6, CHIP_SORT = 7;
    private static final int COLS = 4, BATCH = 40;
    private static final float PAD = 104, GAP = 24, BAR_Y = 150, BAR_H = 60, GRID_Y = 252, INFO_H = 118;
    private static final ExecutorService net = Work.pool(2);

    private int tab, list, lang, genre, decade, status, sort;

    // ---- state -------------------------------------------------------------------------------------

    private enum Zone { BAR, GRID, DETAIL }

    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArtBackdrop bg;
    private GlassKit kit;
    private float k = 1;
    private final List<Tmdb.Title> items = new ArrayList<>();
    private int offset, generation;
    private boolean loading, done, failed;
    private Zone zone = Zone.BAR;
    private int barSel = CHIP_LIST, sel;
    private boolean menuOpen;
    private int menuSel;
    private float scrollFrom, scrollTo;
    private long scrollStart;

    // Details.
    private Tmdb.Title shown;
    private int detailSel;
    private boolean rating;
    private int rateSel;
    private List<Services.Service> where;
    private final List<String> buttons = new ArrayList<>();

    // Drawing.
    private final RectF tmp = new RectF(), tmp2 = new RectF();
    private final RectF[] chipRects = new RectF[8];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private Paint title, sub, chip, chipBold, cardTitle, cardMeta, badge, detailMeta, button, buttonInk, big;
    private TextPaint body, detailTitle;
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private final Map<String, Bitmap> appIcons = new HashMap<>();
    private Bitmap disc, check;
    private boolean running, scheduled;
    private int vsyncs;

    public MustWatchView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        ImageCache.init(context);
        bg = new ArtBackdrop(context);
        for (int i = 0; i < chipRects.length; i++) chipRects[i] = new RectF();
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        SharedPreferences p = prefs();
        tab = p.getInt("mw_tab", 0);
        list = p.getInt("mw_list", 0);
        lang = p.getInt("mw_lang", 0);
        genre = p.getInt("mw_genre", 0);
        decade = 0;
        status = p.getInt("mw_status", 0);
        if (p.contains("mw_open_status")) {   // opened as My List (Home, without a drive): Want to Watch, this time only
            status = p.getInt("mw_open_status", status);
            p.edit().remove("mw_open_status").apply();
        }
        sort = p.getInt("mw_sort", 0);
        clampChoices();
        net.execute(() -> Awards.keys(getContext()));   // award badges, cached for a month
    }

    void setListener(Listener l) {
        listener = l;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private void clampChoices() {
        tab = clamp(tab, 1);
        list = clamp(list, LISTS.length - 1);
        lang = clamp(lang, languages().length - 1);
        genre = clamp(genre, GENRES.length - 1);
        decade = clamp(decade, DECADES.length - 1);
        status = clamp(status, STATUSES.length - 1);
        sort = clamp(sort, SORTS.length - 1);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private void saveChoices() {
        prefs().edit().putInt("mw_tab", tab).putInt("mw_list", list).putInt("mw_lang", lang).putInt("mw_genre", genre)
                .putInt("mw_decade", decade).putInt("mw_status", status).putInt("mw_sort", sort).apply();
    }

    void setRunning(boolean run) {
        running = run;
        if (run && !scheduled) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        if (!run) bg.release();
        else {
            bg.show(null, this::invalidate);
            refreshMarks();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        scheduled = false;
        if (!running) return;
        if (++vsyncs % 2 == 0) {   // 30 fps
            bg.step(SystemClock.uptimeMillis());
            invalidate();
        }
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        generation++;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        bg.setSize(w, h);
        kit = new GlassKit(getContext(), bg, k);
        title = kit.font(44, 700);
        title.setLetterSpacing(GlassKit.neon(getContext()) ? -0.01f : 0.12f);
        sub = kit.font(24, 400);
        chip = kit.font(24, 500);
        chipBold = kit.font(24, 600);
        cardTitle = kit.font(24, 700);
        cardMeta = kit.font(20, 500);
        badge = kit.font(22, 700);
        badge.clearShadowLayer();
        detailMeta = kit.font(25, 500);
        button = kit.font(26, 600);
        buttonInk = kit.font(26, 700);
        buttonInk.setColor(Theme.INK);
        buttonInk.clearShadowLayer();
        big = kit.font(30, 700);
        body = new TextPaint(kit.font(25, 400));
        detailTitle = new TextPaint(kit.font(48, 700));
        disc = WheelArt.glyphDisc(WheelArt.Glyph.TELESCOPE, Math.round(72 * k), Theme.AMBER_A, Theme.AMBER_B);
        check = kit.glyph(WheelArt.Glyph.CHECK, 22);
        layouts.clear();
        appIcons.clear();
        layoutBar();
        if (items.isEmpty() && !loading) reload();
    }

    // ---- loading -----------------------------------------------------------------------------------

    private String type() {
        return tab == TAB_SERIES ? "tv" : "movie";
    }

    private boolean localList() {
        return status == WANT || status == WATCHED || status == RATED;
    }

    /** Starts the grid over for the current choices. */
    private void reload() {
        generation++;
        items.clear();
        offset = 0;
        done = false;
        failed = false;
        loading = false;
        sel = 0;
        scrollFrom = scrollTo = 0;
        layouts.clear();
        if (localList() || sort == BY_MINE) loadLocal();
        else more();
    }

    /** Want to Watch, Watched or Rated by me: from your list, filtered and sorted here. */
    private void loadLocal() {
        if (sort == BY_MINE && !localList()) status = RATED;
        String code = languages()[lang][1], g = GENRES[genre][0];
        int[] years = years();
        List<Library.Entry> got = new ArrayList<>();
        for (Library.Entry e : Library.all(getContext())) {
            if (!e.type.equals(type())) continue;
            if (status == WANT && e.status != Library.WANT) continue;
            if (status == WATCHED && e.status != Library.WATCHED) continue;
            if (status == RATED && e.rating == 0) continue;
            if (!code.isEmpty() && !e.language.isEmpty() && !e.language.equals(code)) continue;
            if (!g.isEmpty() && !e.genres.isEmpty() && !e.genres.contains(g)) continue;
            if (years != null && (e.year < years[0] || e.year > years[1])) continue;
            got.add(e);
        }
        Collections.sort(got, (a, b) -> {
            switch (sort) {
                case BY_CRITICS: return Integer.compare(b.tomato, a.tomato);
                case BY_YEAR: return Integer.compare(b.year, a.year);
                case BY_MINE: return Integer.compare(b.rating, a.rating);
                default: return Double.compare(b.imdb, a.imdb);
            }
        });
        for (Library.Entry e : got) items.add(fromEntry(e));
        done = true;
    }

    static Tmdb.Title fromEntry(Library.Entry e) {
        int id = e.tmdbId > 0 ? e.tmdbId : 0;
        Tmdb.Title t = new Tmdb.Title(id, e.type, e.title, "", e.year > 0 ? e.year + "-01-01" : "", 0, e.imdb, null, null);
        if (id == 0 && e.key.startsWith("jw:")) t.jwId = e.key.substring(3);
        t.posterUrl = e.poster;
        t.tomato = e.tomato;
        t.language = e.language;
        t.genreCodes = new ArrayList<>(e.genres);
        return t;
    }

    /** The decade's years, or null for any. */
    private int[] years() {
        switch (decade) {
            case 0: return null;
            case 7: return new int[]{1900, 1969};
            default: {
                int from = 2030 - 10 * decade;
                return new int[]{from, from + 9};
            }
        }
    }

    /** The next page of the chosen list from JustWatch, off the main thread. */
    private void more() {
        if (loading || done) return;
        loading = true;
        final int gen = generation;
        final String type = tab == TAB_SERIES ? "SHOW" : "MOVIE";
        final String code = languages()[lang][1];
        final boolean indian = java.util.Arrays.asList("hi", "mr", "gu", "ml", "ta", "te").contains(code);
        final boolean series = tab == TAB_SERIES;
        JustWatch.Category c = new JustWatch.Category(L10n.t("Must-Watch"), LISTS[list][1]);
        if (!GENRES[genre][0].isEmpty()) c.genres = new String[]{GENRES[genre][0]};
        int[] years = years();
        if (years != null) c.years(years[0], years[1]);
        Catalog.applyLanguage(c, code.isEmpty() ? null : code);
        // JustWatch has no language: Indian films are checked against TMDB's original language instead of the script
        // of their title (most Hindi titles are written in English letters there).
        if (indian) c.script = JustWatch.Script.NONE;
        c.sort = sort == BY_YEAR ? "RELEASE_YEAR" : "IMDB_SCORE";
        // Enough IMDb votes that a title is widely seen: fewer for smaller film industries and for series.
        int base;
        switch (code) {
            case "hi": base = series ? 5000 : 10000; break;
            case "ml": case "ta": case "te": base = series ? 1000 : 3000; break;
            case "mr": case "gu": base = series ? 300 : 1000; break;
            case "ko": base = 3000; break;
            default: base = series ? 10000 : 25000;
        }
        int minVotes, minTomato = 0;
        switch (list) {
            case 1:   // award winners: a wide pool, kept to winners here
                c.minImdb = 6.0;
                minVotes = base / 10;
                break;
            case 2:   // critics' picks
                minTomato = 85;
                minVotes = base / 5;
                break;
            default:  // top rated
                c.minImdb = indian ? 7.0 : series ? 8.0 : 7.5;
                minVotes = base;
        }
        final int votes = minVotes, tomato = minTomato;
        final Set<String> watched = status == HIDE_WATCHED ? Library.watched(getContext()) : null;
        net.execute(() -> {
            List<Tmdb.Title> got = new ArrayList<>();
            boolean end = false, error = false;
            int at = offset, tries = 0;
            Set<String> awards = list == 1 ? Awards.keys(getContext()) : null;
            try {
                // Award winners are a filter on a wide list: keep fetching until a few turn up.
                do {
                    JustWatch.Chunk chunk = JustWatch.top(c, type, votes, tomato, at, BATCH);
                    at += BATCH;
                    if (chunk.fetched < BATCH) end = true;
                    List<Tmdb.Title> batch = new ArrayList<>();
                    for (JustWatch.Title j : chunk.titles) {
                        Tmdb.Title t = Catalog.fromJw(j, code.isEmpty() ? null : code);
                        if (t == null) continue;
                        if (awards != null && !awards.contains(Library.keyOf(t))) continue;
                        if (watched != null && watched.contains(Library.keyOf(t))) continue;
                        batch.add(t);
                    }
                    if (indian) batch = inLanguage(batch, code);
                    got.addAll(batch);
                } while (!end && (awards != null || indian) && got.size() < 14 && ++tries < 8);
            } catch (Exception e) {
                error = true;
            }
            for (int i = 0; i < Math.min(COLS * 2, got.size()); i++) ImageCache.get(cardPicture(got.get(i)), null, null, Math.round(cardW()));
            final boolean fEnd = end, fError = error;
            final int fAt = at;
            main.post(() -> {
                if (gen != generation) return;
                loading = false;
                offset = fAt;
                done = fEnd || fError;
                failed = fError && items.isEmpty();
                for (Tmdb.Title t : got) if (!contains(t)) items.add(t);
                if (sort == BY_CRITICS) {   // JustWatch has no critics sort: order what is loaded
                    Tmdb.Title focused = sel < items.size() ? items.get(sel) : null;
                    Collections.sort(items, (a, b) -> Integer.compare(b.tomato, a.tomato));
                    if (focused != null) sel = items.indexOf(focused);
                }
                invalidate();
            });
        });
    }

    /** Only the titles whose original language (from TMDB, remembered) is this one; looked up side by side. */
    private List<Tmdb.Title> inLanguage(List<Tmdb.Title> titles, String code) {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<String>> langs = new ArrayList<>();
        for (Tmdb.Title t : titles) langs.add(pool.submit(() -> Tmdb.originalLanguage(getContext(), t.type, t.id)));
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            try {
                if (code.equals(langs.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS))) out.add(titles.get(i));
            } catch (Exception ignored) {
                // unknown: left out
            }
        }
        pool.shutdownNow();
        return out;
    }

    private boolean contains(Tmdb.Title t) {
        String key = Library.keyOf(t);
        for (Tmdb.Title x : items) if (Library.keyOf(x).equals(key)) return true;
        return false;
    }

    /** Back from playing or another screen: marks may have changed; local lists are rebuilt. */
    private void refreshMarks() {
        if (localList() && kit != null) reload();
    }

    // ---- layout ------------------------------------------------------------------------------------

    private String chipText(int i) {
        switch (i) {
            case TAB_MOVIES: return "" + getContext().getString(R.string.chip_movies);
            case TAB_SERIES: return "" + getContext().getString(R.string.chip_series);
            case CHIP_LIST: return LISTS[list][0] + "  " + LISTS[list][1] + "  ▾";
            case CHIP_LANG: return "" + (lang == 0 ? getContext().getString(R.string.all_languages) : languages()[lang][0]) + "  ▾";
            case CHIP_GENRE: return GENRES[genre][2] + "  " + GENRES[genre][1] + "  ▾";
            case CHIP_DECADE: return "" + DECADES[decade] + "  ▾";
            case CHIP_STATUS: return STATUSES[status][0] + "  " + STATUSES[status][1] + "  ▾";
            default: return "↕  " + getContext().getString(R.string.sort_by, SORTS[sort]) + "  ▾";
        }
    }

    /** Movies / Series at the top right; the filter chips in a row under the heading. */
    private void layoutBar() {
        float right = getWidth() - PAD * k, h = BAR_H * k, y = 58 * k;
        for (int i = TAB_SERIES; i >= TAB_MOVIES; i--) {
            float w = chip.measureText(chipText(i)) + 56 * k;
            chipRects[i].set(right - w, y, right, y + h);
            right -= w + 12 * k;
        }
        float x = PAD * k;
        y = BAR_Y * k;
        for (int i = CHIP_LIST; i <= CHIP_SORT; i++) {
            if (i == CHIP_DECADE) continue;   // no decade filter
            float w = chip.measureText(chipText(i)) + 52 * k;
            chipRects[i].set(x, y, x + w, y + h);
            x += w + 14 * k;
        }
    }

    private float cardW() {
        return (getWidth() - 2 * PAD * k - (COLS - 1) * GAP * k) / COLS;
    }

    private float rowH() {
        return cardW() * 9 / 16 + INFO_H * k + 30 * k;
    }

    private void setSel(int s) {
        if (items.isEmpty()) return;
        sel = Math.max(0, Math.min(items.size() - 1, s));
        long now = SystemClock.uptimeMillis();
        scrollFrom = scroll(now);
        scrollTo = Math.max(0, sel / COLS - (sel / COLS > 0 ? 0.35f : 0));   // the focused row near the top
        scrollStart = now;
        if (!done && sel >= items.size() - COLS * 2) more();
    }

    private float scroll(long now) {
        return scrollFrom + (scrollTo - scrollFrom) * Theme.progress(now, scrollStart, Theme.MOVE_MS);
    }

    // ---- keys --------------------------------------------------------------------------------------

    /** Back: closes the rating, the details or a list; false when the screen should close. */
    boolean back() {
        if (rating) {
            rating = false;
        } else if (zone == Zone.DETAIL) {
            zone = Zone.GRID;
            shown = null;
            if (localList()) reload();
        } else if (menuOpen) {
            menuOpen = false;
        } else if (zone == Zone.GRID) {
            zone = Zone.BAR;
        } else {
            return false;
        }
        invalidate();
        return true;
    }

    private final Runnable snapBehind = () -> {
        if (!menuOpen && zone != Zone.DETAIL && getContext() instanceof android.app.Activity) Frost.capture((android.app.Activity) getContext());
    };

    /** A moment after each key, at rest: keeps the blurred picture for pop-ups up to date. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean handled = super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_UP) {
            removeCallbacks(snapBehind);
            postDelayed(snapBehind, 700);
        }
        return handled;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) select();
            invalidate();
            return true;
        }
        boolean handled;
        if (rating) handled = rateKey(keyCode);
        else if (zone == Zone.DETAIL) handled = detailKey(keyCode);
        else if (menuOpen) handled = menuKey(keyCode);
        else if (zone == Zone.BAR) handled = barKey(keyCode);
        else handled = gridKey(keyCode);
        if (handled) invalidate();
        return handled || super.onKeyDown(keyCode, event);
    }

    private boolean barKey(int key) {
        // Left/Right along the chips (filters, then Movies / Series); Down into the grid.
        int[] order = {CHIP_LIST, CHIP_LANG, CHIP_GENRE, CHIP_STATUS, CHIP_SORT, TAB_MOVIES, TAB_SERIES};
        int at = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == barSel) at = i;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: barSel = order[Math.max(0, at - 1)]; return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: barSel = order[Math.min(order.length - 1, at + 1)]; return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!items.isEmpty()) zone = Zone.GRID;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: return true;
            default: return false;
        }
    }

    private boolean gridKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (sel % COLS > 0) setSel(sel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel % COLS < COLS - 1 && sel + 1 < items.size()) setSel(sel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel < COLS) zone = Zone.BAR;
                else setSel(sel - COLS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel + COLS < items.size()) setSel(sel + COLS);
                else if (sel / COLS < (items.size() - 1) / COLS) setSel(items.size() - 1);   // a shorter last row: its last one
                else if (sel / COLS < (items.size() - 1) / COLS) setSel(items.size() - 1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: setSel(sel + COLS * 2); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: setSel(sel - COLS * 2); return true;
            default: return false;
        }
    }

    private int menuSize() {
        switch (barSel) {
            case CHIP_LIST: return LISTS.length;
            case CHIP_LANG: return languages().length;
            case CHIP_GENRE: return GENRES.length;
            case CHIP_DECADE: return DECADES.length;
            case CHIP_STATUS: return STATUSES.length;
            default: return SORTS.length;
        }
    }

    private String menuText(int i) {
        switch (barSel) {
            case CHIP_LIST: return LISTS[i][0] + "  " + LISTS[i][1];
            case CHIP_LANG: return i == 0 ? getContext().getString(R.string.all_languages) : languages()[i][0];
            case CHIP_GENRE: return GENRES[i][2] + "  " + GENRES[i][1];
            case CHIP_DECADE: return DECADES[i];
            case CHIP_STATUS: return STATUSES[i][0] + "  " + STATUSES[i][1];
            default: return SORTS[i];
        }
    }

    private int menuCurrent() {
        switch (barSel) {
            case CHIP_LIST: return list;
            case CHIP_LANG: return lang;
            case CHIP_GENRE: return genre;
            case CHIP_DECADE: return decade;
            case CHIP_STATUS: return status;
            default: return sort;
        }
    }

    private boolean menuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: menuSel = Math.max(0, menuSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: menuSel = Math.min(menuSize() - 1, menuSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                menuOpen = false;
                return true;
            default: return false;
        }
    }

    private boolean detailKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: detailSel = Math.max(0, detailSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: detailSel = Math.min(buttons.size() - 1, detailSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: return true;
            default: return false;
        }
    }

    private boolean rateKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: rateSel = Math.max(0, rateSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: rateSel = Math.min(10, rateSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: return true;
            default:
                if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {   // number keys rate directly (0 = 10)
                    rateSel = key == KeyEvent.KEYCODE_0 ? 10 : key - KeyEvent.KEYCODE_0;
                    return true;
                }
                return false;
        }
    }

    private void select() {
        if (rating) {
            Library.rate(getContext(), shown, rateSel);
            rating = false;
            buildButtons();
            return;
        }
        if (zone == Zone.DETAIL) {
            detailAction();
            return;
        }
        if (menuOpen) {
            switch (barSel) {
                case CHIP_LIST: list = menuSel; break;
                case CHIP_LANG: lang = menuSel; break;
                case CHIP_GENRE: genre = menuSel; break;
                case CHIP_DECADE: decade = menuSel; break;
                case CHIP_STATUS: status = menuSel; break;
                default:
                    sort = menuSel;
                    if (sort == BY_MINE && !localList()) status = RATED;   // only your rated titles have your rating
            }
            menuOpen = false;
            saveChoices();
            layoutBar();
            reload();
            return;
        }
        if (zone == Zone.BAR) {
            if (barSel == TAB_MOVIES || barSel == TAB_SERIES) {
                if (tab != barSel) {
                    tab = barSel;
                    saveChoices();
                    reload();
                }
            } else {
                menuOpen = true;
                menuSel = menuCurrent();
            }
            return;
        }
        if (sel < items.size()) TitleActivity.open(getContext(), items.get(sel));
    }

    // ---- details -----------------------------------------------------------------------------------

    /** Play (on the first app you have, else the first that streams it), Trailer, Watched, Want to Watch, Rate. */
    private void buildButtons() {
        String was = detailSel < buttons.size() ? buttons.get(detailSel) : null;
        buttons.clear();
        Services.Service play = playService();
        if (play != null) buttons.add("play");
        buttons.add("trailer");
        buttons.add("watched");
        buttons.add("want");
        buttons.add("rate");
        int same = was == null ? -1 : buttons.indexOf(was);   // focus stays on the same button when Play comes or goes
        detailSel = same >= 0 ? same : Math.min(detailSel, buttons.size() - 1);
    }

    @Nullable
    private Services.Service playService() {
        if (where == null || where.isEmpty()) return null;
        for (Services.Service s : where) if (Services.installed(getContext(), s.pkg)) return s;
        return where.get(0);
    }

    private void detailAction() {
        if (shown == null || listener == null) return;
        Library.Entry e = Library.get(getContext(), shown);
        int st = e != null ? e.status : Library.NONE;
        switch (buttons.get(detailSel)) {
            case "play": listener.onOpen(shown, playService()); break;
            case "trailer": listener.onTrailer(shown); break;
            case "watched": Library.setStatus(getContext(), shown, st == Library.WATCHED ? Library.NONE : Library.WATCHED); break;
            case "want": Library.setStatus(getContext(), shown, st == Library.WANT ? Library.NONE : Library.WANT); break;
            default:
                rating = true;
                rateSel = e != null && e.rating > 0 ? e.rating : 8;
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        if (kit == null) return;
        long now = SystemClock.uptimeMillis();
        bg.draw(c, now);
        boolean neon = GlassKit.neon(getContext());
        float hx = PAD * k + (neon ? 0 : 96 * k);
        if (neon) kit.haze(c, getWidth(), getHeight(), now);
        else {
            c.drawColor(Frost.dim(Color.argb(150, 10, 10, 11)));
            bmp.setAlpha(255);
            c.drawBitmap(disc, PAD * k, 44 * k, bmp);
        }
        title.setAlpha(255);
        String heading = getContext().getString(R.string.must_watch);
        c.drawText(neon ? heading : heading.toUpperCase(Locale.ROOT), hx, 90 * k, title);
        sub.setAlpha(190);
        c.drawText(getContext().getString(R.string.must_watch_sub), hx, 126 * k, sub);
        for (int i = 0; i < chipRects.length; i++) if (i != CHIP_DECADE) drawChip(c, i);
        drawGrid(c, now);
        if (menuOpen) drawMenu(c);
        if (zone == Zone.DETAIL && shown != null) drawDetail(c);
    }

    private void drawChip(Canvas c, int i) {
        RectF r = chipRects[i];
        boolean focused = zone == Zone.BAR && !menuOpen && barSel == i;
        boolean on = (i == TAB_MOVIES && tab == 0) || (i == TAB_SERIES && tab == 1)
                || (i == CHIP_LIST && list > 0) || (i == CHIP_LANG && lang > 0) || (i == CHIP_GENRE && genre > 0)
                || (i == CHIP_DECADE && decade > 0) || (i == CHIP_STATUS && status > 0);
        float rad = r.height() / 2;
        kit.pill(c, r, rad, 1);
        if (on) {
            fill.setColor(i <= TAB_SERIES ? 0x38FFFFFF /* selected: a cyan wash */ : Color.argb(70, 255, 255, 255));
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (focused) kit.focus(c, r, rad, 1);
        Paint p = on || focused ? chipBold : chip;
        p.setAlpha(255);
        c.drawText(chipText(i), r.left + (i <= TAB_SERIES ? 28 : 26) * k, r.centerY() + 9 * k, p);
    }

    /** The list under its chip: Up/Down, OK chooses, a tick by the current choice. */
    private void drawMenu(Canvas c) {
        RectF chipR = chipRects[barSel];
        int n = menuSize();
        float itemH = 58 * k, w = Math.max(chipR.width(), 380 * k);
        int visible = Math.min(n, 12);
        int first = Math.max(0, Math.min(n - visible, menuSel - visible / 2));
        RectF box = new RectF(chipR.left, chipR.bottom + 10 * k, chipR.left + w, chipR.bottom + 10 * k + visible * itemH + 20 * k);
        if (box.right > getWidth() - 40 * k) box.offset(getWidth() - 40 * k - box.right, 0);
        kit.panel(c, box, 22 * k, 1, Theme.TINT_HIGH);
        fill.setColor(Color.argb(220, 18, 18, 20));
        c.drawRoundRect(box, 22 * k, 22 * k, fill);
        for (int v = 0; v < visible; v++) {
            int i = first + v;
            RectF r = new RectF(box.left + 10 * k, box.top + 10 * k + v * itemH, box.right - 10 * k, box.top + 10 * k + (v + 1) * itemH - 4 * k);
            boolean cur = i == menuCurrent(), focused = i == menuSel;
            if (focused) {
                fill.setColor(0x38FFFFFF /* selected: a cyan wash */);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                edge.setStrokeWidth(1.8f * k);   // Home's cyan focus line
                edge.setColor(0x99FFFFFF);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, edge);
                edge.setColor(Color.WHITE);
            }
            Paint p = cur || focused ? chipBold : chip;
            p.setAlpha(255);
            c.drawText(menuText(i), r.left + 22 * k, r.centerY() + 9 * k, p);
            if (cur) {
                bmp.setAlpha(255);
                c.drawBitmap(check, r.right - 22 * k - check.getWidth(), r.centerY() - check.getHeight() / 2f, bmp);
            }
        }
    }

    private void drawGrid(Canvas c, long now) {
        float top = GRID_Y * k, cw = cardW(), ch = cw * 9 / 16, rh = rowH(), s = scroll(now);   // wide pictures, like My Movies
        c.save();
        c.clipRect(0, top - 24 * k, getWidth(), getHeight());
        if (items.isEmpty()) {
            String msg = loading || !done ? null : getContext().getString(failed ? R.string.discover_offline
                    : localList() ? R.string.must_watch_empty_list : R.string.must_watch_empty);
            if (msg == null) {   // loading: shimmering posters
                for (int i = 0; i < COLS * 2; i++) {
                    float x = PAD * k + (i % COLS) * (cw + GAP * k), y = top + (i / COLS) * rh;
                    float sh = 0.5f + 0.5f * (float) Math.sin(now / 300.0 + i);
                    fill.setColor(Color.argb(Math.round(26 + 22 * sh), 255, 255, 255));
                    c.drawRoundRect(x, y, x + cw, y + ch, 18 * k, 18 * k, fill);
                }
            } else {
                sub.setAlpha(230);
                c.drawText(msg, getWidth() / 2f - sub.measureText(msg) / 2, top + 200 * k, sub);
            }
            c.restore();
            return;
        }
        Set<String> awards = Awards.loaded();
        int firstRow = Math.max(0, (int) Math.floor(s) - 1), lastRow = (int) Math.ceil(s + (getHeight() - top) / rh) + 1;
        Map<String, Library.Entry> marks = new HashMap<>();
        for (Library.Entry e : Library.all(getContext())) marks.put(e.key, e);
        for (int i = firstRow * COLS; i < Math.min(items.size(), (lastRow + 1) * COLS); i++) {
            if (i == sel && zone == Zone.GRID) continue;
            drawCard(c, i, cw, ch, top, rh, s, false, awards, marks);
        }
        if (zone == Zone.GRID && sel < items.size()) drawCard(c, sel, cw, ch, top, rh, s, true, awards, marks);
        if (!done && loading) {
            String l = getContext().getString(R.string.loading_more);
            sub.setAlpha(170);
            c.drawText(l, getWidth() - PAD * k - sub.measureText(l), 232 * k, sub);
        }
        c.restore();
    }

    /** The wide picture of a card, or its poster for the few titles that have none. */
    private static String cardPicture(Tmdb.Title t) {
        String wide = t.backdrop();
        return wide != null && !wide.isEmpty() ? wide : t.poster();
    }

    private void drawCard(Canvas c, int i, float cw, float ch, float top, float rh, float s, boolean focused,
                          @Nullable Set<String> awards, Map<String, Library.Entry> marks) {
        Tmdb.Title t = items.get(i);
        float x = PAD * k + (i % COLS) * (cw + GAP * k), y = top + (i / COLS - s) * rh;
        RectF r = new RectF(x, y, x + cw, y + ch);
        c.save();
        if (focused) c.scale(1.04f, 1.04f, r.centerX(), r.top + (ch + INFO_H * k) / 2);
        if (focused) kit.focus(c, r, 18 * k, 1);
        fill.setColor(Color.argb(40, 255, 255, 255));
        c.drawRoundRect(r, 18 * k, 18 * k, fill);
        Bitmap img = ImageCache.get(cardPicture(t), this::invalidate, null, Math.round(cw));   // a small copy: 12 MB must hold them all
        if (img != null) drawImage(c, img, r, 18 * k, 255);
        else {
            cardTitle.setAlpha(200);
            StaticLayout l = layout("p" + t.title, t.title, new TextPaint(cardTitle), cw - 32 * k, 4);
            c.save();
            c.translate(r.left + 16 * k, r.centerY() - l.getHeight() / 2f);
            l.draw(c);
            c.restore();
        }
        String key = Library.keyOf(t);
        if (awards != null && awards.contains(key)) roundBadge(c, r.left + 28 * k, r.top + 28 * k, "★", 0xE6202030);
        Library.Entry e = marks.get(key);
        if (e != null) {
            String mark = e.rating > 0 ? "★" + e.rating : e.status == Library.WATCHED ? "✓" : e.status == Library.WANT ? "" : null;
            if (mark != null) {
                int col = e.status == Library.WATCHED || e.rating > 0 ? 0xF02BB673 : 0xF0F0782A;
                pillBadge(c, r.right - 12 * k, r.top + 12 * k, mark, col);
            }
        }
        if (focused) {
            edge.setStrokeWidth(1.8f * k);   // Home's cyan focus line
            edge.setColor(0x99FFFFFF);
            edge.setShadowLayer(6 * k, 0, 0, 0x33FFFFFF);
            c.drawRoundRect(r, 18 * k, 18 * k, edge);
            edge.clearShadowLayer();
            edge.setColor(Color.WHITE);
        }
        // Under the poster: title, year · IMDb · Rotten Tomatoes, and where it streams.
        float ty = r.bottom + 32 * k;
        cardTitle.setAlpha(255);
        c.drawText(ellipsize(t.title, cardTitle, cw), r.left + 2 * k, ty, cardTitle);
        StringBuilder m = new StringBuilder();
        if (t.year > 0) m.append(t.year);
        if (t.rating > 0) m.append(m.length() > 0 ? " · " : "").append("IMDb ").append(String.format(Locale.US, "%.1f", t.rating));
        if (t.tomato >= 0) m.append(L10n.t(" · RT ")).append(t.tomato).append('%');
        cardMeta.setAlpha(220);
        c.drawText(ellipsize(m.toString(), cardMeta, cw), r.left + 2 * k, ty + 32 * k, cardMeta);
        float wy = ty + 64 * k;
        if (t.streams != null && !t.streams.isEmpty()) {
            float ix = r.left + 2 * k;
            for (int n = 0; n < Math.min(4, t.streams.size()); n++) {
                Bitmap icon = appIcon(t.streams.get(n).pkg);
                if (icon == null) continue;
                bmp.setAlpha(255);
                c.drawBitmap(icon, null, new RectF(ix, wy - 24 * k, ix + 30 * k, wy + 6 * k), bmp);
                ix += 38 * k;
            }
            if (ix == r.left + 2 * k) c.drawText(t.streams.get(0).name, ix, wy, cardMeta);
        } else if (t.streams != null) {
            cardMeta.setAlpha(170);
            c.drawText(getContext().getString(t.rentOrBuy ? R.string.rent_or_buy : R.string.not_streaming_short), r.left + 2 * k, wy, cardMeta);
        } else if (e != null && e.rating > 0) {
            cardMeta.setAlpha(200);
            c.drawText(getContext().getString(R.string.your_rating, e.rating), r.left + 2 * k, wy, cardMeta);
        }
        c.restore();
    }

    private void roundBadge(Canvas c, float cx, float cy, String text, int color) {
        fill.setColor(color);
        c.drawCircle(cx, cy, 22 * k, fill);
        badge.setAlpha(255);
        c.drawText(text, cx - badge.measureText(text) / 2, cy + 8 * k, badge);
    }

    /** A small pill in the poster's top-right corner: ✓, 🔖 or ★8. */
    private void pillBadge(Canvas c, float right, float top, String text, int color) {
        float w = Math.max(44 * k, badge.measureText(text) + 24 * k), h = 40 * k;
        tmp2.set(right - w, top, right, top + h);
        fill.setColor(color);
        c.drawRoundRect(tmp2, h / 2, h / 2, fill);
        badge.setAlpha(255);
        c.drawText(text, tmp2.centerX() - badge.measureText(text) / 2, tmp2.centerY() + 8 * k, badge);
    }

    /** The details: backdrop, poster, title, scores, summary, where it streams, and the buttons (or the rating). */
    private void drawDetail(Canvas c) {
        Tmdb.Title t = shown;
        Frost.blurBehind(c, getWidth(), getHeight(), bmp);
        float w = 1520 * k, h = 820 * k;
        RectF p = new RectF((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        kit.panel(c, p, 32 * k, 1, Theme.TINT_HIGH);
        Bitmap back = ImageCache.get(t.backdrop(), this::invalidate);
        if (back != null) {
            c.save();
            c.clipRect(p.left, p.top, p.right, p.top + 420 * k);
            drawImage(c, back, new RectF(p.left, p.top, p.right, p.top + 420 * k + 32 * k), 32 * k, 120);
            c.restore();
        }
        fill.setShader(new LinearGradient(0, p.top + 120 * k, 0, p.top + 440 * k, 0x000B0B0D, 0xF00B0B0D, Shader.TileMode.CLAMP));
        c.drawRoundRect(p, 32 * k, 32 * k, fill);
        fill.setShader(null);
        float pw = 280 * k, ph = 420 * k, px = p.left + 56 * k, py = p.top + 56 * k;
        RectF pr = new RectF(px, py, px + pw, py + ph);
        fill.setColor(Color.argb(50, 255, 255, 255));
        c.drawRoundRect(pr, 18 * k, 18 * k, fill);
        Bitmap poster = ImageCache.get(t.poster(), this::invalidate);
        if (poster != null) drawImage(c, poster, pr, 18 * k, 255);
        float x = pr.right + 48 * k, right = p.right - 56 * k, y = p.top + 70 * k;
        StaticLayout tl = layout("dt" + t.title, t.title, detailTitle, right - x, 2);
        c.save();
        c.translate(x, y);
        tl.draw(c);
        c.restore();
        y += tl.getHeight() + 44 * k;
        StringBuilder meta = new StringBuilder(t.isSeries() ? L10n.t("Series") : L10n.t("Movie"));
        if (t.year > 0) meta.append("  ·  ").append(t.year);
        if (t.runtime > 0) meta.append("  ·  ").append(t.runtime >= 60 ? t.runtime / 60 + " h " + t.runtime % 60 + L10n.t(" min") : t.runtime + L10n.t(" min"));
        if (!t.genres.isEmpty()) meta.append("  ·  ").append(t.genres);
        detailMeta.setAlpha(230);
        c.drawText(ellipsize(meta.toString(), detailMeta, right - x), x, y, detailMeta);
        y += 58 * k;
        // Scores, big.
        float sx = x;
        if (t.rating > 0) sx = score(c, sx, y, "IMDb " + String.format(Locale.US, "%.1f", t.rating)
                + (t.votes > 0 ? "  (" + votes(t.votes) + ")" : ""));
        if (t.tomato >= 0) sx = score(c, sx, y, "RT  " + t.tomato + "%" + (t.fresh ? "  " + getContext().getString(R.string.certified_fresh) : ""));
        Set<String> awards = Awards.loaded();
        if (awards != null && awards.contains(Library.keyOf(t))) score(c, sx, y, "★ " + getContext().getString(R.string.award_winner));
        y += 46 * k;
        if (!t.overview.isEmpty()) {
            StaticLayout bl = layout("ov" + t.title, t.overview, body, right - x, 4);
            body.setAlpha(240);
            c.save();
            c.translate(x, y);
            bl.draw(c);
            c.restore();
            y += bl.getHeight() + 30 * k;
        }
        // Where it streams in India.
        String whereText;
        if (where == null) whereText = getContext().getString(R.string.checking_where);
        else if (where.isEmpty()) whereText = getContext().getString(t.rentOrBuy ? R.string.rent_or_buy_long : R.string.not_streaming_long);
        else {
            List<String> names = new ArrayList<>();
            for (Services.Service s : where) names.add(s.name);
            whereText = getContext().getString(R.string.streams_on, TextUtils.join(", ", names));
        }
        detailMeta.setAlpha(240);
        c.drawText(ellipsize("" + whereText, detailMeta, right - x), x, Math.max(y + 10 * k, pr.bottom - 10 * k), detailMeta);
        // Buttons, or the rating dots.
        float by = p.bottom - 150 * k;
        if (rating) drawRating(c, p, by);
        else drawButtons(c, p, by);
    }

    private float score(Canvas c, float x, float y, String text) {
        float w = big.measureText(text);
        big.setAlpha(255);
        c.drawText(text, x, y, big);
        return x + w + 44 * k;
    }

    private static String votes(int v) {
        if (v >= 1_000_000) return String.format(Locale.US, "%.1fM votes", v / 1_000_000f);
        if (v >= 1_000) return Math.round(v / 1000f) + L10n.t("K votes");
        return v + L10n.t(" votes");
    }

    private void drawButtons(Canvas c, RectF p, float y) {
        Library.Entry e = Library.get(getContext(), shown);
        int st = e != null ? e.status : Library.NONE;
        float h = 92 * k, x = p.left + 56 * k;
        for (int i = 0; i < buttons.size(); i++) {
            String b = buttons.get(i), label;
            boolean on = false;
            switch (b) {
                case "play": {
                    Services.Service s = playService();
                    label = "▶  " + getContext().getString(R.string.play_on, s != null ? s.name : "");
                    break;
                }
                case "trailer": label = "" + getContext().getString(R.string.trailer); break;
                case "watched": on = st == Library.WATCHED; label = (on ? "" : "") + getContext().getString(R.string.watched); break;
                case "want": on = st == Library.WANT; label = "" + getContext().getString(R.string.want_to_watch); break;
                default:
                    label = "" + (e != null && e.rating > 0 ? getContext().getString(R.string.your_rating, e.rating)
                            : getContext().getString(R.string.rate));
            }
            boolean primary = b.equals("play");
            Paint tp = primary ? buttonInk : button;
            float w = tp.measureText(label) + 72 * k;
            RectF r = new RectF(x, y, x + w, y + h);
            boolean focused = detailSel == i;
            c.save();
            if (focused) c.scale(1.06f, 1.06f, r.centerX(), r.centerY());
            if (primary) {
                fill.setColor(Color.WHITE);
                c.drawRoundRect(r, h / 2, h / 2, fill);
                if (focused) kit.focus(c, r, h / 2, 1);
            } else {
                kit.pill(c, r, h / 2, 1);
                if (on) {
                    fill.setColor(0x38FFFFFF /* selected: a cyan wash */);
                    c.drawRoundRect(r, h / 2, h / 2, fill);
                }
                if (focused) kit.focus(c, r, h / 2, 1);
            }
            tp.setAlpha(255);
            c.drawText(label, r.left + 36 * k, r.centerY() + 10 * k, tp);
            c.restore();
            x += w + 20 * k;
        }
    }

    /** Clear, then 1 to 10 in a row; the chosen one large. Number keys also work (0 = 10). */
    private void drawRating(Canvas c, RectF p, float y) {
        String head = getContext().getString(R.string.rate_hint);
        sub.setAlpha(220);
        c.drawText(head, p.left + 56 * k, y - 22 * k, sub);
        float size = 92 * k, gap = 16 * k, x = p.left + 56 * k;
        for (int i = 0; i <= 10; i++) {
            float w = i == 0 ? 150 * k : size;
            RectF r = new RectF(x, y, x + w, y + size);
            boolean focused = rateSel == i, filled = i > 0 && i <= rateSel;
            if (focused) kit.focus(c, r, size / 2, 1);
            fill.setColor(filled ? withAlpha(Theme.AMBER_B, focused ? 255 : 170) : Color.argb(focused ? 90 : 40, 255, 255, 255));
            c.drawRoundRect(r, size / 2, size / 2, fill);
            String l = i == 0 ? getContext().getString(R.string.clear) : String.valueOf(i);
            Paint tp = focused ? big : button;
            tp.setAlpha(255);
            c.drawText(l, r.centerX() - tp.measureText(l) / 2, r.centerY() + 11 * k, tp);
            x += w + gap;
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private void drawImage(Canvas c, Bitmap img, RectF r, float rad, int alpha) {
        float sc = Math.max(r.width() / img.getWidth(), r.height() / img.getHeight());
        matrix.setScale(sc, sc);
        matrix.postTranslate(r.centerX() - img.getWidth() * sc / 2, r.centerY() - img.getHeight() * sc / 2);
        BitmapShader sh = new BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(matrix);
        image.setShader(sh);
        image.setAlpha(alpha);
        c.drawRoundRect(r, rad, rad, image);
        image.setShader(null);
    }

    private static String ellipsize(String s, Paint p, float w) {
        return TextUtils.ellipsize(s, new TextPaint(p), w, TextUtils.TruncateAt.END).toString();
    }

    private StaticLayout layout(String key, String text, TextPaint p, float w, int lines) {
        StaticLayout l = layouts.get(key);
        if (l == null) {
            l = StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, Math.round(w))).setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END).build();
            if (layouts.size() > 200) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    @Nullable
    private Bitmap appIcon(String pkg) {
        if (appIcons.containsKey(pkg)) return appIcons.get(pkg);
        Bitmap b = null;
        try {
            android.graphics.drawable.Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
            int sz = Math.round(30 * k);
            b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sz, sz);
            d.draw(new Canvas(b));
        } catch (Exception ignored) {
            // not installed: the name is shown instead
        }
        appIcons.put(pkg, b);
        return b;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
