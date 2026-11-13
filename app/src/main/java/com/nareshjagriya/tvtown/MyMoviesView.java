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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * My Movies, like a streaming app: a grid of wide cards, four to a row, that scrolls up and down. Each card is the
 * film's picture with its name, year, rating, genre and length under it (✓ when watched, ❤️ a favourite, a bar for
 * how far you got). OK on a card opens its details over the screen: a big picture, the summary, its language and
 * tags, and every button (Play or Resume, Start over, Episodes, Trailer, Favourite, Watched, Rate, Tags,
 * Fix match). The ▶ key on the remote plays a card straight away. The focused film's artwork fills the dimmed
 * background. Filter tabs at the top (All, Favourites, Unwatched, In progress, Movies, Series, Filter, Sort), and a
 * line at the bottom saying what the keys do. Files that are not matched yet (or with no internet) show their
 * cleaned name on a plain picture and still play.
 */
public class MyMoviesView extends View implements Choreographer.FrameCallback {

    interface Listener {
        /** Play a file from a point (ms); VLC when installed, else the TV's player. */
        void onPlay(MyMovies.Video video, long fromMs, String title);

        void onTrailer(Tmdb.Title title);

        /** Fix match: ask for a name with the keyboard; the answer comes back through fixSearch(). */
        void onTypeName(String current);

        /** A new tag: ask for its name with the keyboard; `done` gets it (null when cancelled). */
        void onTypeTag(java.util.function.Consumer<String> done);

        /** A name to change (File → Rename): the keyboard, starting from `current`. */
        void onAskName(String title, String current, java.util.function.Consumer<String> done);

        /** The search line: `typed` gets every change, `closed` runs when it goes away. */
        void onSearch(String initial, java.util.function.Consumer<String> typed,
                      java.util.function.Function<String, List<String>> suggest, Runnable closed);
    }

    /** A card: one movie file, or a show with its episodes. */
    static final class Item {
        final String key;                     // the match key (file, or "show:name")
        final MyMovies.Parsed parsed;
        final List<MyMovies.Video> videos = new ArrayList<>();   // one for a movie; episodes in order for a show
        MyMovies.Match match;

        Item(String key, MyMovies.Parsed parsed) {
            this.key = key;
            this.parsed = parsed;
        }

        boolean saved;   // from the YouTube downloader: under Videos, not a film

        boolean show() {
            return parsed.series && !saved;
        }

        String name() {
            return match != null ? match.title : parsed.title.isEmpty() ? videos.get(0).name : parsed.title;
        }

        int year() {
            return match != null && match.year > 0 ? match.year : parsed.year;
        }

        long added() {
            long a = 0;
            for (MyMovies.Video v : videos) a = Math.max(a, v.added);
            return a;
        }
    }

    // Tabs: views (All, Favourites, Unwatched, In progress, Movies, Series), then Filter (a panel) and Sort (a list).
    private static final int V_ALL = 0, V_FAV = 1, V_UNWATCHED = 2, V_STARTED = 3, V_MOVIES = 4, V_SERIES = 5, T_FILTER = 6,
            T_SORT = 7, T_SEARCH = 8, T_GRID = 9;
    private static final String[] VIEWS = {L10n.t("All"), L10n.t("Favourites"), L10n.t("Unwatched"), L10n.t("In progress"), L10n.t("Movies"), L10n.t("Series")};
    private static final String[] VIEW_EMOJI = {"", "❤", "", "⏯", "", ""};
    private static final String[] SORTS = {L10n.t("Recently added"), "A–Z", L10n.t("Year"), L10n.t("IMDb rating"), L10n.t("Your rating")};
    // Language filter choices, kept with a prefix for their section: o: origin, a: audio, s: subtitles.
    private static final String HAS_SUBS = "Has subtitles", NO_SUBS = "No subtitles";
    /** Languages that need no subtitles, by content style. */
    private List<String> understood() {
        return Style.myLanguages(getContext());
    }

    private static final float PAD = 80, GRID_TOP = 136, COL_GAP = 20, TEXT_H = 92, ROW_GAP = 26, POSTER_H = 330;
    private static final int COLS = 4;   // tall poster cards, three to a row; the bar a sidebar over the left
    private static final ExecutorService net = Work.pool(3);

    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArtBackdrop bg;
    private GlassKit kit;
    private float k = 1;
    private final List<Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private int generation, matching;
    private boolean scanned, permission = true, drive = true;

    // Focus: the tabs or a card in the grid; with a card's details open, one of its buttons.
    private boolean inBar, menuOpen, detailOpen;
    private Item detailItem;   // the film whose details are open
    private int barSel, menuSel, sel, btn;
    private int view, sort;
    private final java.util.Set<String> fGenres = new java.util.LinkedHashSet<>(), fTags = new java.util.LinkedHashSet<>(),
            fLangs = new java.util.LinkedHashSet<>();
    // A film's languages (by its first file's key), read in the background.
    private final Map<String, Tracks.Info> tracks = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService probe = Work.pool(1);

    // Panels: the filter, a film's tags, its subtitles. Each a set of chips moved through with the arrows.
    private static final int P_FILTER = 1, P_TAGS = 2, P_FILE = 3;
    private static final String S_FOLDER = L10n.t("Choose a folder");
    private String fileStep = "main";       // the File panel: its first page, or choosing where to move / copy
    private boolean deleteArmed;            // Delete pressed once: the second press deletes
    private List<java.io.File> fileFolders = new ArrayList<>();
    private int panel;
    private final List<Chip> chips = new ArrayList<>();
    private Chip focusChip;
    private float panelScroll;

    /** A chip in a panel: a choice to tick, or a button. */
    private static final class Chip {
        final String section, value, label;
        boolean on;
        final RectF rect = new RectF();

        Chip(String section, String value, String label, boolean on) {
            this.section = section;
            this.value = value;
            this.label = label;
            this.on = on;
        }
    }
    private boolean firstFilter = true;   // the first time: focus the film last watched
    private float scrollFrom, scrollTo;   // the grid's first row in view (fractional while it glides)
    private long scrollStart;

    // Overlays: a show's episodes, the rating, Fix match.
    private Item target;
    private boolean episodesOpen, rating, fixing, fixLoading, onSeasons;
    private int season, episodeSel, rateSel, fixSel;
    private final List<Integer> seasons = new ArrayList<>();
    private final List<Tmdb.Title> fixFound = new ArrayList<>();

    // Drawing.
    private final RectF tmp2 = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private Paint title, sub, cardTitle, cardMeta, badge, button, buttonInk, big, chip, chipBold, icon;
    private TextPaint body, placeholder;
    private Paint help;
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private Bitmap disc, check;
    private boolean running, scheduled;
    private int vsyncs;

    public MyMoviesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        ImageCache.init(context);
        bg = new ArtBackdrop(context);
        stage = new ArtStage(this);
        stage.black = true;
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        SharedPreferences p = prefs();
        view = Math.max(0, Math.min(VIEWS.length - 1, p.getInt("mm_view", 0)));
        seriesShown = p.getBoolean("mm_series", view == V_SERIES);
        videosShown = p.getBoolean("mm_videos", false);
        if (view >= V_MOVIES) view = V_ALL;   // Movies and Series are the toggle now
        sort = Math.max(0, Math.min(SORTS.length - 1, p.getInt("mm_sort", 0)));
        // Filters (and the search) start fresh each time My Movies opens; the view and the order are remembered.
        p.edit().remove("mm_genres").remove("mm_tags").remove("mm_langs").remove("mm_rating").apply();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setRunning(boolean run) {
        running = run;
        if (run && !scheduled) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        if (!run) bg.release();
        else showArt();
    }

    /** Storage permission granted or not; scans when it is. */
    void setPermission(boolean granted) {
        permission = granted;
        if (granted) rescan();
        invalidate();
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
        cardTitle = kit.font(64, 700);
        cardMeta = kit.font(25, 500);
        badge = kit.font(21, 700);
        badge.clearShadowLayer();
        button = kit.font(24, 600);
        buttonInk = kit.font(24, 700);
        buttonInk.setColor(Theme.INK);
        buttonInk.clearShadowLayer();
        big = kit.font(30, 700);
        chip = kit.font(24, 500);
        chipBold = kit.font(24, 700);
        icon = kit.font(23, 400);
        icon.clearShadowLayer();
        body = new TextPaint(kit.font(26, 400));
        placeholder = new TextPaint(kit.font(30, 700));
        help = kit.font(24, 500);
        NoirKit nk = new NoirKit(getContext(), k);
        heroHead = nk.inter(40, 600, 0, Color.WHITE);
        heroName = nk.inter(88, 700, 0, Color.WHITE);
        heroMeta = nk.inter(24, 500, 0, Color.WHITE);
        heroBody = nk.inter(30, 400, 0, Color.WHITE);
        shelfName = nk.inter(30, 600, 0, Color.WHITE);
        for (Paint q : new Paint[]{heroHead, heroName, heroMeta, heroBody, shelfName}) q.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
        button = nk.inter(24, 500, 0, Color.WHITE);
        disc = WheelArt.glyphDisc(WheelArt.Glyph.FILM, Math.round(72 * k), Theme.ROSE_A, Theme.ROSE_B);
        check = kit.glyph(WheelArt.Glyph.CHECK, 22);
        layouts.clear();
    }

    // ---- scanning and matching ---------------------------------------------------------------------

    /** Reads the drive's videos (off the main thread), groups episodes into shows, then matches what is new. */
    void rescan() {
        final int gen = ++generation;
        net.execute(() -> {
            Context ctx = getContext();
            long t0 = SystemClock.uptimeMillis();
            boolean mounted = MyMovies.driveMounted(ctx);
            List<MyMovies.Video> videos = mounted ? MyMovies.scan(ctx) : new ArrayList<>();
            android.util.Log.d("TVTownTime", "movies scan " + videos.size() + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
            Map<String, Item> byKey = new LinkedHashMap<>();
            for (MyMovies.Video v : videos) {
                MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
                String key = MyMovies.matchKey(v, p);
                Item it = byKey.get(key);
                if (it == null) {
                    it = new Item(key, p);
                    it.match = MyMovies.saved(ctx, key);
                    byKey.put(key, it);
                }
                it.videos.add(v);
            }
            for (Item it : byKey.values()) {
                if (it.show()) Collections.sort(it.videos, (a, b) -> {
                    MyMovies.Parsed pa = MyMovies.parse(a.name, a.folder, ""), pb = MyMovies.parse(b.name, b.folder, "");
                    return pa.season != pb.season ? Integer.compare(pa.season, pb.season) : Integer.compare(pa.episode, pb.episode);
                });
            }
            List<Item> items = new ArrayList<>(byKey.values());
            if (mounted) for (MyMovies.Video v : MyMovies.scan(ctx, true)) items.add(savedItem(ctx, v));
            main.post(() -> {
                if (gen != generation) return;
                drive = mounted;
                scanned = true;
                all.clear();
                all.addAll(items);
                filter();
                matchMissing(gen);
                probeAll(gen);
            });
        });
    }

    /** A saved video: its own name and YouTube picture, never looked up as a film. */
    private static Item savedItem(Context ctx, MyMovies.Video v) {
        String name = v.name.replaceFirst("\\.[^.]+$", "");
        Item it = new Item("saved:" + v.name, MyMovies.parse(v.name, v.folder, ""));
        it.saved = true;
        it.videos.add(v);
        MyMovies.Match m = new MyMovies.Match();
        m.type = "movie";
        m.title = name;
        m.backdrop = YtSaver.picture(ctx, v.name);
        m.poster = m.backdrop;
        m.manual = true;
        m.tagged = true;
        it.match = m;
        return it;
    }

    /** Matches files with no saved match (a few at a time); each one updates the grid as it arrives. */
    /** Each film's audio and subtitle languages (one file at a time, kept after the first read). */
    private void probeAll(int gen) {
        long t0 = SystemClock.uptimeMillis();
        int[] left = {all.size()};
        for (Item it : all) {
            MyMovies.Video v = it.videos.get(0);
            probe.execute(() -> {
                Tracks.Info info = Tracks.of(getContext(), v);
                main.post(() -> {
                    if (--left[0] == 0) android.util.Log.d("TVTownTime", "movies tracks in " + (SystemClock.uptimeMillis() - t0) + " ms");
                    if (gen != generation) return;
                    tracks.put(v.key(), info);
                    if (!fLangs.isEmpty()) filter();
                    invalidate();
                });
            });
        }
    }

    private void matchMissing(int gen) {
        for (Item it : all) {   // matched before tags existed: tag them now
            MyMovies.Match old = it.match;
            if (old == null || old.tagged || old.id <= 0) continue;
            net.execute(() -> {
                MyMovies.tag(getContext(), old);
                if (!old.tagged) return;
                MyMovies.save(getContext(), it.key, old);
                main.post(() -> {
                    if (gen == generation) filter();
                });
            });
        }
        for (Item it : all) {
            if (it.match != null || MyMovies.missedRecently(getContext(), it.key)) continue;
            matching++;
            net.execute(() -> {
                MyMovies.Match m = MyMovies.find(getContext(), it.parsed);
                if (m != null) MyMovies.save(getContext(), it.key, m);
                else if (Net.online(getContext())) MyMovies.missed(getContext(), it.key);
                if (m != null) ImageCache.prefetch(m.backdrop != null ? m.backdrop : m.poster);
                main.post(() -> {
                    matching--;
                    if (gen != generation) return;
                    if (m != null) {
                        it.match = m;
                        filter();
                    }
                    invalidate();
                });
            });
        }
    }

    // ---- filters -----------------------------------------------------------------------------------

    /** The films that pass the tab and the filters, in the chosen order. */
    private void filter() {
        Item focused = sel < shown.size() ? shown.get(sel) : null;
        shown.clear();
        for (Item it : all) if (passes(it, view)) shown.add(it);
        Collections.sort(shown, (a, b) -> {
            switch (sort) {
                case 1: return a.name().compareToIgnoreCase(b.name());
                case 2: return Integer.compare(b.year(), a.year());
                case 3: return Double.compare(b.match != null ? b.match.imdb : 0, a.match != null ? a.match.imdb : 0);
                case 4: return Integer.compare(myRating(b), myRating(a));
                default: return Long.compare(b.added(), a.added());
            }
        });
        if (firstFilter && !shown.isEmpty()) {   // start on the film last watched, so OK carries on with it
            firstFilter = false;
            long best = 0;
            for (Item it : shown) for (MyMovies.Video v : it.videos) {
                long at = MyMovies.lastPlayed(getContext(), v);
                if (at > best && (MyMovies.position(getContext(), v) > 0 || it.show())) {
                    best = at;
                    focused = it;
                }
            }
        }
        int at = focused != null ? shown.indexOf(focused) : -1;
        jumpTo(at >= 0 ? at : Math.min(sel, Math.max(0, shown.size() - 1)));
        if (shown.isEmpty()) inBar = true;
        buildShelves();
        if (pendingShot != null && scanned && !shown.isEmpty()) showShot();
        invalidate();
    }

    /**
     * Whether a film shows in a tab with the filters: within a section any ticked choice will do (Hindi or English),
     * and every section with something ticked must agree (Comedy and Hindi).
     */
    private boolean passes(Item it, int tab) {
        if (!searchMatches(it)) return false;
        if (tab == V_FAV && !Tags.favourite(getContext(), it.key)) return false;
        if (it.saved != videosShown) return false;
        if (!videosShown && it.show() != seriesShown) return false;
        if (tab == V_UNWATCHED && watched(it) || tab == V_STARTED && !inProgress(it)) return false;
        if (!fGenres.isEmpty() && java.util.Collections.disjoint(fGenres, genresOf(it))) return false;
        if (!fTags.isEmpty() && java.util.Collections.disjoint(fTags, tagsOf(it))) return false;
        if (!fLangs.isEmpty()) {   // origin, audio and subtitles each a section of their own
            List<String> mine = languagesOf(it);
            for (String sec : new String[]{"o:", "a:", "s:"}) {
                boolean ticked = false, match = false;
                for (String f : fLangs) {
                    if (!f.startsWith(sec)) continue;
                    ticked = true;
                    if (mine.contains(f)) match = true;
                }
                if (ticked && !match) return false;
            }
        }
        return true;
    }

    private int myRating(Item it) {
        if (it.match == null) return 0;
        Library.Entry e = Library.get(getContext(), it.match.type + ":" + it.match.id);
        return e != null ? e.rating : 0;
    }

    private List<String> tagsOf(Item it) {
        return Tags.of(getContext(), it.key, it.match != null ? it.match.tags : new ArrayList<>());
    }

    @Nullable
    private Tracks.Label languageOf(Item it) {
        Tracks.Info info = tracks.get(it.videos.get(0).key());
        if (info == null) return null;
        return Tracks.label(it.match != null ? it.match.language : "", info, understood());
    }

    /** The Language filter's choices a film matches: "o:Hollywood", "a:Hindi", "s:Has subtitles". */
    private List<String> languagesOf(Item it) {
        List<String> out = new ArrayList<>();
        Tracks.Label l = languageOf(it);
        if (l == null) return out;
        if (!l.origin.isEmpty()) out.add("o:" + l.origin);
        for (String a : l.audio) out.add("a:" + Tracks.name(a));
        out.add("s:" + (l.hasSubs ? HAS_SUBS : NO_SUBS));
        return out;
    }

    private void saveFilters() {
        prefs().edit().putString("mm_genres", String.join("\n", fGenres))
                .putString("mm_tags", String.join("\n", fTags)).putString("mm_langs", String.join("\n", fLangs)).apply();
    }

    private int filterCount() {
        return fGenres.size() + fTags.size() + fLangs.size();
    }

    private static List<String> genresOf(Item it) {
        List<String> out = new ArrayList<>();
        if (it.match == null || it.match.genres.isEmpty()) return out;
        for (String g : it.match.genres.split(",\\s*")) if (!g.trim().isEmpty()) out.add(g.trim());
        return out;
    }

    private boolean watched(Item it) {
        Context ctx = getContext();
        if (it.match != null) {
            Library.Entry e = Library.get(ctx, it.match.type + ":" + it.match.id);
            if (e != null && e.status == Library.WATCHED) return true;
        }
        for (MyMovies.Video v : it.videos) if (!MyMovies.seen(ctx, v)) return false;
        return true;
    }

    private boolean inProgress(Item it) {
        for (MyMovies.Video v : it.videos) if (MyMovies.position(getContext(), v) > 0 || MyMovies.began(getContext(), v)) return true;
        return false;
    }

    private String query = "";

    /** Search: the films whose name (or file name) has every word typed, in the view and filters chosen. */
    private void openSearch() {
        if (listener == null) return;
        listener.onSearch(query, t -> {
            query = t;
            filter();
        }, typed -> {   // names to offer: the films' and shows' names
            List<String> names = new ArrayList<>();
            for (Item it : all) names.add(it.name());
            return SearchBox.suggest(typed, names);
        }, () -> {
            if (!shown.isEmpty()) inBar = false;
            requestFocus();
            invalidate();
        });
    }

    private boolean searchMatches(Item it) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        String hay = (it.name() + " " + it.parsed.title + " " + it.videos.get(0).name + " " + (it.year() > 0 ? it.year() : "")
                + (it.match != null ? " " + it.match.genres : "")).toLowerCase(Locale.ROOT);
        for (String w : q.split("\\s+")) if (!hay.contains(w)) return false;
        return true;
    }

    private List<String> menuItems() {
        return barSel == 0 ? java.util.Arrays.asList(VIEWS).subList(0, V_MOVIES) : java.util.Arrays.asList(SORTS);
    }

    private int menuCurrent() {
        return barSel == 0 ? view : sort;
    }

    // The bar: the views as tabs on the left; Filter, Sort and Search as round buttons on the right.
    /** The sidebar's rows: the views, a line, then Filter, Sort and Search. */
    private boolean hoverBuilt;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String tabWord(int t) {
        if (t == 0) return VIEWS[view];
        if (t == T_SEARCH) return query.isEmpty() ? L10n.t("Search") : query;
        String text = SIDE_LABEL[t];
        return t == T_FILTER && filterCount() > 0 ? L10n.t("Filter · ") + filterCount() : text;
    }

    private float tabWidth(int t) {
        if (kit != null) barPaint.setTypeface(kit.font(10, 500).getTypeface());
        barPaint.setTextSize(24 * k);
        if (t == BAR_KIND) return barPaint.measureText(L10n.t("Movies")) + barPaint.measureText(L10n.t("Series")) + barPaint.measureText(L10n.t("Videos")) + 96 * k;
        if (t == T_SEARCH) return 330 * k;   // the search bar, the same on every page
        return Math.min(240 * k, barPaint.measureText(tabWord(t))) + 56 * k;
    }

    /** One capsule at the top right, like Home's. */
    private RectF tabRect(int t) {
        float x = 1840 * k;
        for (int i = BAR.length - 1; i >= 0; i--) {
            x -= tabWidth(BAR[i]);
            if (BAR[i] == T_SEARCH) x -= 16 * k;   // the search bar stands on its own
            if (BAR[i] == t) break;
        }
        return new RectF(x, 40 * k, x + tabWidth(t), 112 * k);
    }

    // ---- the row ------------------------------------------------------------------------------------

    /** Straight to film i, without gliding (a new filter, the first time). */
    private void jumpTo(int i) {
        sel = shown.isEmpty() ? 0 : Math.max(0, Math.min(shown.size() - 1, i));
        scrollFrom = scrollTo = rowInView(sel, 0);
        showArt();
    }

    /** To film i: the grid glides when its row is out of view. */
    private void moveTo(int i) {
        if (shown.isEmpty()) return;
        i = Math.max(0, Math.min(shown.size() - 1, i));
        if (i == sel) return;
        sel = i;
        float want = rowInView(sel, scrollTo);
        if (want != scrollTo) {
            long now = SystemClock.uptimeMillis();
            scrollFrom = scroll(now);
            scrollTo = want;
            scrollStart = now;
        }
        showArt();
    }

    /** The first row to show so film i's row is in view, moving as little as possible from `first`. */
    private float rowInView(int i, float first) {
        int row = i / COLS, rows = rowsInView();
        if (row < first) return row;
        if (row > first + rows - 1) return row - rows + 1;
        return first;
    }

    /** Whole rows that fit under the tabs. */
    private int rowsInView() {
        return Math.max(1, (int) ((1080 - GRID_TOP - 60) / rowStep()));
    }

    private static float cardW() {
        return (1920 - 2 * PAD - (COLS - 1) * COL_GAP) / COLS;
    }

    private static float rowStep() {
        return POSTER_H + ROW_GAP;
    }

    /** Where the grid is, as a (fractional) first row: it glides from row to row. */
    private float scroll(long now) {
        return scrollFrom + (scrollTo - scrollFrom) * Theme.progress(now, scrollStart, Theme.MOVE_MS);
    }

    /** A card's buttons: Play / Resume, Start over (when started), Episodes (a show), Trailer, Watched, Rate, Fix match. */
    private List<String> buttons(@Nullable Item it) {
        List<String> out = new ArrayList<>();
        if (it == null) return out;
        out.add("play");
        if (!it.show() && (MyMovies.position(getContext(), it.videos.get(0)) > 0 || MyMovies.began(getContext(), it.videos.get(0)))) out.add("restart");
        if (it.show()) out.add("episodes");
        if (it.match != null && (it.match.trailer != null || it.match.id > 0)) out.add("trailer");
        out.add("fav");
        out.add("watched");
        if (it.saved) return out;   // not a film: no rating, subtitles or match to fix
        if (it.match != null) out.add("rate");
        out.add("tags");
        if (!it.show()) out.add("file");
        out.add("fix");
        return out;
    }

    private String buttonName(Item it, String b) {
        Context c = getContext();
        switch (b) {
            case "play": {
                MyMovies.Video v = nextVideo(it);
                long pos = MyMovies.position(c, v);
                if (it.show()) {
                    MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
                    return c.getString(pos > 0 || MyMovies.began(c, v) ? R.string.resume : R.string.play_now) + "  S" + p.season + " E" + p.episode;
                }
                return pos > 0 ? c.getString(R.string.resume_at, MyMovies.clock(pos))
                        : c.getString(MyMovies.began(c, v) ? R.string.resume : R.string.play_now);
            }
            case "restart": return c.getString(R.string.start_over);
            case "episodes": return c.getString(R.string.episodes);
            case "trailer": return c.getString(R.string.trailer);
            case "fav": return c.getString(R.string.favourite);
            case "tags": return c.getString(R.string.tags);
            case "file": return L10n.t("File");
            case "watched": return c.getString(watched(it) ? R.string.watched : R.string.mark_watched);
            case "rate": {
                Library.Entry e = Library.get(c, it.match.type + ":" + it.match.id);
                return e != null && e.rating > 0 ? c.getString(R.string.your_rating, e.rating) : c.getString(R.string.rate);
            }
            default: return c.getString(R.string.fix_match);
        }
    }

    /** The episode to play next in a show (the one in progress, else the first not seen), or the movie. */
    private MyMovies.Video nextVideo(Item it) {
        Context c = getContext();
        for (MyMovies.Video v : it.videos) if (MyMovies.position(c, v) > 0 || MyMovies.began(c, v)) return v;
        for (MyMovies.Video v : it.videos) if (!MyMovies.seen(c, v)) return v;
        return it.videos.get(0);
    }

    // ---- keys --------------------------------------------------------------------------------------

    /** Back: closes the rating, Fix match, the episodes or a list; false when the screen should close. */
    boolean back() {
        if (panel != 0) closePanel();
        else if (rating) rating = false;
        else if (fixing) fixing = false;
        else if (episodesOpen) episodesOpen = false;
        else if (detailOpen) detailOpen = false;
        else if (menuOpen) menuOpen = false;
        else if (inBar && barSel >= T_FILTER && !shown.isEmpty()) inBar = false;
        else return false;
        invalidate();
        return true;
    }

    private long lastRepeat;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // A held (or repeating) arrow moves at a steady pace, not as fast as the remote repeats.
        if (event.getRepeatCount() > 0 && keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            long t = SystemClock.uptimeMillis();
            if (t - lastRepeat < 160) return true;
            lastRepeat = t;
        }
        // The remote's TEXT button opens Search.
        if (MyMusicView.textKey(event)) {
            if (event.getRepeatCount() > 0) return true;
            inBar = true;
            barSel = T_SEARCH;
            openSearch();
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) select();
            invalidate();
            return true;
        }
        removeCallbacks(snapBehind);
        postDelayed(snapBehind, 700);
        boolean handled;
        if (panel != 0) handled = panelKey(keyCode);
        else if (rating) handled = rateKey(keyCode);
        else if (fixing) handled = listKey(keyCode, fixFound.size() + 1, true);
        else if (episodesOpen) handled = episodeKey(keyCode);
        else if (detailOpen) handled = detailKey(keyCode);
        else if (menuOpen) handled = listKey(keyCode, menuItems().size(), false);
        else if (inBar) handled = barKey(keyCode);
        else handled = gridKey(keyCode);
        if (handled) invalidate();
        return handled || super.onKeyDown(keyCode, event);
    }

    private final Runnable snapBehind = () -> {
        if (panel == 0 && !detailOpen && !menuOpen && !episodesOpen && !rating && !fixing && !(inBar && barSel >= T_FILTER)
                && getContext() instanceof android.app.Activity) Frost.capture((android.app.Activity) getContext());
    };

    private boolean barKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int at = 0;
                for (int i = 0; i < BAR.length; i++) if (BAR[i] == barSel) at = i;
                barSel = BAR[Math.max(0, Math.min(BAR.length - 1, at + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)))];
                hoverBuilt = false;
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:   // into the menu that shows under All, Filter or Sort; else to the films
                if (barSel == 0 || barSel == T_SORT) {
                    menuOpen = true;
                    menuSel = menuCurrent();
                } else if (barSel == T_FILTER) openFilter();
                else if (!shown.isEmpty()) inBar = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: return true;
            default: return false;
        }
    }

    private static final int BAR_KIND = 1;
    private static final int[] BAR = {T_SEARCH, 0, BAR_KIND, T_FILTER, T_SORT};
    private boolean seriesShown, videosShown;

    /** Movies or Series: OK flips it. */
    private void toggleKind() {
        if (videosShown) {   // OK steps Movies, Series, Videos
            videosShown = false;
            seriesShown = false;
        } else if (seriesShown) {
            videosShown = true;
        } else {
            seriesShown = true;
        }
        prefs().edit().putBoolean("mm_series", seriesShown).putBoolean("mm_videos", videosShown).apply();
        filter();
    }

    /**
     * In the grid: the arrows move from card to card (Up from the top row goes to the tabs), Channel / Page keys a
     * screen at a time, OK opens the details, ▶ plays.
     */
    private boolean gridKey(int key) {
        if (shown.isEmpty()) return isDpad(key);
        if (shelfOn()) return shelfKey(key);
        int page = rowsInView() * COLS;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel % COLS > 0) moveTo(sel - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel % COLS < COLS - 1) moveTo(sel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel >= COLS) moveTo(sel - COLS);
                else {
                    inBar = true;
                    barSel = T_SEARCH;   // Search first: no menu opens on the way up
                    hoverBuilt = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel / COLS < (shown.size() - 1) / COLS) moveTo(Math.min(shown.size() - 1, sel + COLS));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                moveTo(sel + page);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_MEDIA_REWIND:
                moveTo(sel - page);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                act(shown.get(sel), "play");
                return true;
            default: return false;
        }
    }

    /** A card's details: Left / Right along the buttons, Up / Down to the line above or below. */
    private boolean detailKey(int key) {
        List<RectF> r = buttonRects(detailItem);
        if (r.isEmpty()) return isDpad(key);
        btn = Math.min(btn, r.size() - 1);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: btn = Math.max(0, btn - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: btn = Math.min(r.size() - 1, btn + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: {   // the nearest button on the next line
                RectF from = r.get(btn);
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < r.size(); i++) {
                    RectF to = r.get(i);
                    if (down ? to.top <= from.top + 1 : to.top >= from.top - 1) continue;
                    float d = Math.abs(to.top - from.top) * 4 + Math.abs(to.centerX() - from.centerX());
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                if (best >= 0) btn = best;
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                act(detailItem, "play");
                return true;
            default: return false;
        }
    }

    private static boolean isDpad(int key) {
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** A filter list or the Fix match list: Up / Down; Left / Right close a filter list. */
    private boolean listKey(int key, int n, boolean fix) {
        int cur = fix ? fixSel : menuSel;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!fix && cur == 0) {
                    menuOpen = false;
                    return true;
                }
                cur = Math.max(0, cur - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN: cur = Math.min(n - 1, cur + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!fix) menuOpen = false;
                return true;
            default: return false;
        }
        if (fix) fixSel = cur;
        else menuSel = cur;
        return true;
    }

    private boolean episodeKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (onSeasons) setSeason(seasons.indexOf(season) - 1);
                else episodeSel = Math.max(0, episodeSel - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (onSeasons) setSeason(seasons.indexOf(season) + 1);
                else episodeSel = Math.min(episodes().size() - 1, episodeSel + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: onSeasons = true; return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: onSeasons = false; return true;
            default: return false;
        }
    }

    private boolean rateKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: rateSel = Math.max(0, rateSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: rateSel = Math.min(10, rateSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: return true;
            default:
                if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                    rateSel = key == KeyEvent.KEYCODE_0 ? 10 : key - KeyEvent.KEYCODE_0;
                    return true;
                }
                return false;
        }
    }

    private void select() {
        if (panel != 0) {
            panelSelect();
            return;
        }
        if (rating) {
            Library.rate(getContext(), target.match.title(), rateSel);
            rating = false;
            return;
        }
        if (fixing) {
            if (fixSel >= fixFound.size()) {
                if (listener != null) listener.onTypeName(target.parsed.title);
            } else {
                choose(fixFound.get(fixSel));
            }
            return;
        }
        if (episodesOpen) {
            if (!onSeasons && episodeSel < episodes().size()) {
                MyMovies.Video v = episodes().get(episodeSel);
                play(target, v, MyMovies.position(getContext(), v));
            }
            return;
        }
        if (menuOpen) {
            menuOpen = false;
            if (barSel == 0) {   // All, Favourites, Unwatched or In progress: then on to the films
                view = menuSel;
                prefs().edit().putInt("mm_view", view).apply();
                filter();
                if (!shown.isEmpty()) inBar = false;
                return;
            }
            sort = menuSel;
            prefs().edit().putInt("mm_sort", sort).apply();
            filter();
            return;
        }
        if (inBar) {
            if (barSel == BAR_KIND) toggleKind();
            else if (barSel == 0) {
                menuOpen = true;
                menuSel = menuCurrent();
            } else if (barSel == T_FILTER) {
                openFilter();
            } else if (barSel == T_SEARCH) {
                openSearch();
            } else {
                menuOpen = true;
                menuSel = menuCurrent();
            }
            return;
        }
        if (detailOpen) {
            List<String> b = buttons(detailItem);
            if (btn < b.size()) act(detailItem, b.get(btn));
            return;
        }
        if (sel >= shown.size()) return;
        detailItem = shown.get(sel);   // OK on a card: its details
        detailOpen = true;
        btn = 0;
    }

    /** One of a card's buttons. */
    private void act(Item it, String action) {
        switch (action) {
            case "play": {   // where you stopped; or, started without a known place, where VLC itself left it (-1)
                MyMovies.Video v = nextVideo(it);
                long pos = MyMovies.position(getContext(), v);
                play(it, v, pos > 0 ? pos : MyMovies.began(getContext(), v) ? -1 : 0);
                break;
            }
            case "restart": play(it, it.videos.get(0), 0); break;
            case "episodes": openEpisodes(it); break;
            case "trailer": if (listener != null) listener.onTrailer(it.match.title()); break;
            case "fav":
                Tags.setFavourite(getContext(), it.key, !Tags.favourite(getContext(), it.key));
                if (view == V_FAV) filter();
                break;
            case "tags": openTags(it); break;
            case "file": openFile(it); break;
            case "watched": {
                boolean now = !watched(it);
                for (MyMovies.Video x : it.videos) MyMovies.setSeen(getContext(), x, now);
                if (it.match != null) Library.setStatus(getContext(), it.match.title(), now ? Library.WATCHED : Library.NONE);
                btn = Math.max(0, buttons(it).indexOf("watched"));   // Start over may go: stay on Watched
                if (view == V_UNWATCHED || view == V_STARTED) filter();
                break;
            }
            case "rate": {
                Library.Entry e = Library.get(getContext(), it.match.type + ":" + it.match.id);
                target = it;
                rating = true;
                rateSel = e != null && e.rating > 0 ? e.rating : 8;
                break;
            }
            default:
                target = it;
                fixing = true;
                fixSel = 0;
                fixSearch(it.parsed.title);
        }
    }

    // ---- shows and playing ------------------------------------------------------------------------

    private void openEpisodes(Item it) {
        target = it;
        episodesOpen = true;
        onSeasons = false;
        seasons.clear();
        for (MyMovies.Video v : it.videos) {
            int s = MyMovies.parse(v.name, v.folder, "").season;
            if (!seasons.contains(s)) seasons.add(s);
        }
        Collections.sort(seasons);
        MyMovies.Video next = nextVideo(it);
        season = MyMovies.parse(next.name, next.folder, "").season;
        episodeSel = Math.max(0, episodes().indexOf(next));
    }

    private void setSeason(int index) {
        if (index < 0 || index >= seasons.size()) return;
        season = seasons.get(index);
        episodeSel = 0;
    }

    private List<MyMovies.Video> episodes() {
        List<MyMovies.Video> out = new ArrayList<>();
        if (target == null) return out;
        for (MyMovies.Video v : target.videos) if (MyMovies.parse(v.name, v.folder, "").season == season) out.add(v);
        return out;
    }

    private void play(Item it, MyMovies.Video v, long from) {
        if (listener == null) return;
        MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
        String name = it.show() ? it.name() + "  ·  S" + p.season + " E" + p.episode : it.name();
        MyMovies.started(getContext(), v);
        listener.onPlay(v, from, name);
    }

    /** After the player closes: saves where you stopped; near the end marks it watched (and the title, for a movie). */
    void played(MyMovies.Video v, long position, long duration) {
        boolean done = MyMovies.played(getContext(), v, position, duration);
        if (done) {
            for (Item it : all) {
                if (!it.videos.contains(v) || it.show() || it.match == null) continue;
                Library.setStatus(getContext(), it.match.title(), Library.WATCHED);
            }
        }
        refresh();
    }

    private String pendingShot;

    private String pendingName;

    /** The keyboard chose this film or show: its page, once the drive has been read. */
    void openNamed(String name) {
        pendingName = name;
        pendingShot = "named";
        if (scanned && !all.isEmpty()) showShot();
    }

    private void showShot() {
        String what = pendingShot;
        pendingShot = null;
        inBar = false;
        if ("named".equals(what)) {
            for (Item it : all) {
                if (it.name().equals(pendingName)) {
                    detailItem = it;
                    detailOpen = true;
                    btn = 0;
                    break;
                }
            }
            pendingName = null;
            invalidate();
            return;
        }
        if ("my-movies-filter".equals(what)) openFilter();
        else if (sel < shown.size()) {
            detailItem = shown.get(sel);
            detailOpen = true;
            btn = 0;
        }
        invalidate();
    }

    /** Back from the player: positions may have changed. */
    void refresh() {
        if (scanned) filter();
    }

    // ---- Fix match ---------------------------------------------------------------------------------

    /** Movies and series for a name, best first. */
    void fixSearch(String name) {
        if (target == null || name == null || name.trim().isEmpty()) return;
        fixing = true;
        fixLoading = true;
        fixFound.clear();
        final Item it = target;
        net.execute(() -> {
            List<Tmdb.Title> got = new ArrayList<>();
            try {
                String first = it.show() ? "tv" : "movie", second = it.show() ? "movie" : "tv";
                got.addAll(Tmdb.searchType(getContext(), first, name, 0));
                got.addAll(Tmdb.searchType(getContext(), second, name, 0));
            } catch (Exception ignored) {
                // offline: nothing to choose from
            }
            while (got.size() > 10) got.remove(got.size() - 1);
            main.post(() -> {
                if (target != it) return;
                fixLoading = false;
                fixFound.addAll(got);
                fixSel = 0;
                invalidate();
            });
        });
    }

    private void choose(Tmdb.Title t) {
        final Item it = target;
        fixing = false;
        net.execute(() -> {
            MyMovies.Match m = MyMovies.fill(getContext(), t);
            m.manual = true;
            MyMovies.save(getContext(), it.key, m);
            main.post(() -> {
                it.match = m;
                filter();
            });
        });
    }

    // ---- drawing -----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        if (kit == null) return;
        long now = SystemClock.uptimeMillis();
        boolean shelves = shelfOn() && !detailOpen;
        if (detailOpen) stage.draw(c, now);
        else stage.drawSoft(c, now, shelves ? new RectF(S_SIDE * k, 136 * k, 1840 * k, 378 * k) : new RectF(), 24 * k);
        if (detailOpen) {
            drawDetail(c);
        } else {
            c.drawText(getContext().getString(R.string.my_movies), PAD * k, 92 * k, heroHead);
            if (!permission || !drive || !scanned || all.isEmpty()) {
                String msg = getContext().getString(!permission ? R.string.my_movies_permission : !scanned ? R.string.my_movies_reading
                        : !drive ? R.string.my_movies_no_drive : R.string.my_movies_empty);
                StaticLayout l = layout("msg" + msg, msg, new TextPaint(big), 1100 * k, 4);
                c.save();
                c.translate(getWidth() / 2f - 550 * k, getHeight() / 2f - l.getHeight() / 2f);
                l.draw(c);
                c.restore();
                return;
            }
            if (shown.isEmpty()) {
                String msg = getContext().getString(R.string.nothing_matches_filters);
                sub.setAlpha(230);
                c.drawText(msg, getWidth() / 2f - sub.measureText(msg) / 2, getHeight() / 2f, sub);
            } else if (shelves) drawShelves(c, now);
            else drawGrid(c, now);
            RectF bar = new RectF(tabRect(BAR[1]).left, 40 * k, 1840 * k, 112 * k);
            Frost.surface(c, bar, 36 * k, k);
            for (int t : BAR) drawTab(c, t);
        }
        boolean quiet = !detailOpen && !episodesOpen && !rating && !fixing;
        if (quiet && panel == 0 && inBar && barSel == T_FILTER) {   // on Filter: its menu opens by itself
            if (!hoverBuilt) {
                buildFilter();
                focusChip = null;
                hoverBuilt = true;
            }
            drawFilter(c);
        } else if (quiet && panel == 0 && inBar && (barSel == T_SORT || barSel == 0) && !menuOpen) drawMenu(c);
        if (menuOpen) drawMenu(c);
        if (episodesOpen) drawEpisodes(c);
        if (rating) drawRating(c);
        if (fixing) drawFix(c);
        if (panel != 0) drawPanel(c);
    }

    // ---- shelves: the library arranged by itself ---------------------------------------------------------

    private final ArtStage stage;
    private Paint heroHead, heroName, heroMeta, heroBody, shelfName;

    private static final class Shelf {
        final String name;
        final List<Item> items;
        int sel;
        float fromX, toX;
        long at;

        Shelf(String name, List<Item> items) {
            this.name = name;
            this.items = items;
        }
    }

    private static final float S_CARD_W = 240, S_CARD_H = 360, S_PITCH = 262, S_ROW = 512, S_FIRST = 166, S_SIDE = 1410;
    private static final int S_ACROSS = 5;
    private final List<Shelf> shelfList = new ArrayList<>();
    private int shelfRow;
    private float shelfFrom, shelfTo;
    private long shelfAt;
    private final Map<String, String> logos = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> logoAsked = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Covers for films and series, whichever view, filter or search you're on; your other videos keep the wide grid. */
    private boolean shelfOn() {
        return false;   // one clean grid, four to a row, each with its name (the shelves had a details sidebar)
    }

    private void buildShelves() {
        Map<String, Integer> kept = new HashMap<>();
        for (Shelf s : shelfList) kept.put(s.name, s.sel);
        String was = shelfRow < shelfList.size() ? shelfList.get(shelfRow).name : "";
        shelfList.clear();
        if (shown.isEmpty()) return;
        Context ctx = getContext();
        // Continue, then everything: no categories, each film once, in the order Sort says.
        List<Item> on = new ArrayList<>();
        for (Item it : shown) if (inProgress(it) && !watched(it)) on.add(it);
        Collections.sort(on, (a, b) -> Long.compare(lastPlayed(b), lastPlayed(a)));
        if (!on.isEmpty()) shelfList.add(new Shelf(L10n.t("Continue"), on));
        List<Item> rest = new ArrayList<>(shown);
        rest.removeAll(on);
        for (int i = 0; i < rest.size(); i += S_ACROSS) {
            String head = i > 0 ? "" : (seriesShown ? L10n.t("All series") : L10n.t("All movies")) + " · " + rest.size();
            shelfList.add(new Shelf(head.isEmpty() ? "row" + i : head, new ArrayList<>(rest.subList(i, Math.min(rest.size(), i + S_ACROSS)))));
        }
        shelfRow = 0;
        for (int i = 0; i < shelfList.size(); i++) {
            Shelf s = shelfList.get(i);
            if (kept.containsKey(s.name)) {
                s.sel = Math.min(kept.get(s.name), s.items.size() - 1);
                s.toX = s.fromX = s.items.size() > S_ACROSS ? Math.max(0, (s.sel - 1) * S_PITCH) : 0;
            }
            if (s.name.equals(was)) shelfRow = i;
        }
        shelfFrom = shelfTo = shelfRow;
        if (shelfOn() && !detailOpen) shelfFocus();
    }

    private long lastPlayed(Item it) {
        long best = 0;
        for (MyMovies.Video v : it.videos) best = Math.max(best, MyMovies.lastPlayed(getContext(), v));
        return best;
    }

    /** The shelf card in focus is the film everything else (details, the ▶ key, subtitles) acts on. */
    private void shelfFocus() {
        Shelf s = shelfList.get(Math.min(shelfRow, shelfList.size() - 1));
        int i = shown.indexOf(s.items.get(Math.min(s.sel, s.items.size() - 1)));
        if (i >= 0) sel = i;
        showArt();
    }

    private boolean shelfKey(int key) {
        long now = SystemClock.uptimeMillis();
        Shelf s = shelfList.get(Math.min(shelfRow, shelfList.size() - 1));
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int to = Math.max(0, Math.min(s.items.size() - 1, s.sel + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                if (to != s.sel) {
                    s.fromX = Frost.glide(s.fromX, s.toX, now, s.at);
                    s.sel = to;
                    s.toX = s.items.size() > S_ACROSS ? Math.max(0, (to - 1) * S_PITCH) : 0;
                    s.at = now;
                    shelfFocus();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (shelfRow > 0) shelfMove(shelfRow - 1, now);
                else {
                    inBar = true;
                    barSel = T_SEARCH;
                    hoverBuilt = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (shelfRow < shelfList.size() - 1) shelfMove(shelfRow + 1, now);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                shelfMove(Math.min(shelfList.size() - 1, shelfRow + 3), now);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                shelfMove(Math.max(0, shelfRow - 3), now);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                act(shown.get(sel), "play");
                return true;
            default:
                return false;
        }
    }

    private void shelfMove(int to, long now) {
        // Straight up or down: the same column on the row you land on.
        Shelf a = shelfList.get(shelfRow), b = shelfList.get(to);
        int col = Math.round(a.sel - a.toX / S_PITCH);
        b.sel = Math.max(0, Math.min(b.items.size() - 1, col + Math.round(b.toX / S_PITCH)));
        shelfFrom = Frost.glide(shelfFrom, shelfTo, now, shelfAt);
        shelfRow = to;
        shelfTo = to;
        shelfAt = now;
        shelfFocus();
    }

    private void drawShelves(Canvas c, long now) {
        if (sel < shown.size()) drawHero(c, shown.get(sel));
        float scroll = Frost.glide(shelfFrom, shelfTo, now, shelfAt);
        int from = Math.max(0, (int) Math.floor(scroll));
        c.save();
        c.clipRect(0, 124 * k, (S_SIDE - 30) * k, getHeight());
        for (int i = from; i < shelfList.size() && i <= from + 2; i++) {
            float y = S_FIRST + (i - scroll) * S_ROW;
            if (y > 1080) continue;
            Shelf s = shelfList.get(i);
            if (!s.name.startsWith("row")) c.drawText(s.name, PAD * k, y * k, shelfName);
            float top = y + 18, sx = Frost.glide(s.fromX, s.toX, now, s.at);
            int start = Math.max(0, (int) (sx / S_PITCH) - 1);
            for (int j = start; j < s.items.size() && j < start + 7; j++) {
                float x = PAD + j * S_PITCH - sx;
                if (x > S_SIDE) break;
                Item it = s.items.get(j);
                boolean on = !inBar && i == shelfRow && j == s.sel;
                RectF r = new RectF(x * k, top * k, (x + S_CARD_W) * k, (top + S_CARD_H) * k);
                if (on) Frost.plate(c, new RectF(r.left - 10 * k, r.top - 10 * k, r.right + 10 * k, r.bottom + 96 * k), 24 * k, k);
                drawPicture(c, it, r, false);
                badges(c, it, r);
                c.drawText(ellipsize(it.name(), shelfName, r.width()), r.left, r.bottom + 42 * k, shelfName);
                c.drawText(ellipsize(coverLine(it), heroMeta, r.width()), r.left, r.bottom + 78 * k, heroMeta);
            }
        }
        c.restore();
    }

    /** Under a cover: its year and rating. */
    private String coverLine(Item it) {
        List<String> parts = new ArrayList<>();
        if (it.year() > 0) parts.add(String.valueOf(it.year()));
        if (it.match != null && it.match.imdb > 0) parts.add("★ " + String.format(Locale.US, "%.1f", it.match.imdb));
        return String.join("  ·  ", parts);
    }

    /** The film in focus, in the column beside the covers: a small picture (drawn by the stage), then everything about it. */
    private void drawHero(Canvas c, Item it) {
        float x = S_SIDE * k, room = (1840 - S_SIDE) * k, y = 402 * k;
        y = sideText(c, "n" + it.key + it.name(), it.name(), heroHead, room, 2, x, y) + 12 * k;
        y = sideText(c, "m" + it.key + cardLine(it, true), cardLine(it, true), heroMeta, room, 3, x, y) + 18 * k;
        MyMovies.Match m = it.match;
        String ov = m != null ? m.overview : "";
        if (!ov.isEmpty()) sideText(c, "s" + it.key + ov.hashCode(), ov, heroBody, room, Math.max(1, (int) ((1040 * k - y) / (40 * k))), x, y);
    }

    private float sideText(Canvas c, String key, String text, Paint p, float room, int lines, float x, float y) {
        StaticLayout l = layout("side" + lines + key, text, new TextPaint(p), room, lines);
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return y + l.getHeight();
    }

    /** Its logo above the details; true when drawn (then the details leave the name out). */
    private boolean drawName(Canvas c, Item it, float x, float bottom, float boxW, float boxH, float room) {
        String url = logos.get(it.key);
        Bitmap logo = url != null ? ImageCache.get(url, this::postInvalidate, null, 500) : null;
        if (logo != null && logo.getWidth() > 0 && !Frost.darkLogo(logo)) {
            float sc = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
            c.drawBitmap(logo, null, new RectF(x, bottom - logo.getHeight() * sc, x + logo.getWidth() * sc, bottom), bmp);
            return true;
        }
        return false;   // no logo: the name is in the pop-up, beside the cover
    }

    private void ensureLogo(Item it) {
        if (it.match == null || it.match.id <= 0 || !logoAsked.add(it.key)) return;
        MyMovies.Match m = it.match;
        net.execute(() -> {
            String u = Catalog.logo(getContext(), m.title());
            if (u != null) {
                logos.put(it.key, u);
                postInvalidate();
            }
        });
    }

    /** A tab: filled when it is the view (or filters are on), with the focus ring when focused. */
    private static final String[] SIDE_LABEL = {L10n.t("All"), L10n.t("Favourites"), L10n.t("Unwatched"), L10n.t("In progress"), L10n.t("Movies"), L10n.t("Series"), L10n.t("Filter"), L10n.t("Sort"), L10n.t("Search"), L10n.t("Grid")};

    private void drawTab(Canvas c, int t) {
        RectF r = tabRect(t);
        boolean focused = inBar && !menuOpen && panel == 0 && barSel == t;
        float rad = r.height() / 2;
        barPaint.setTextSize(24 * k);
        if (t == T_SEARCH) {
            Frost.searchBar(c, r, k, focused, query.isEmpty() ? L10n.t("Search your films") : query, query.isEmpty(), barPaint);
            return;
        }
        if (focused) Frost.plate(c, r, rad, k);
        if (t == BAR_KIND) {   // all three words always visible; the one showing is bright
            String[] words = {L10n.t("Movies"), L10n.t("Series"), L10n.t("Videos")};
            int showing = videosShown ? 2 : seriesShown ? 1 : 0;
            float x = r.left + 18 * k;
            for (int i = 0; i < words.length; i++) {
                barPaint.setColor(i == showing ? Color.WHITE : 0x8CFFFFFF);
                c.drawText(words[i], x + 10 * k, r.centerY() + 9 * k, barPaint);
                x += barPaint.measureText(words[i]) + 20 * k;
            }
            return;
        }
        barPaint.setColor(Color.WHITE);
        c.drawText(ellipsize(tabWord(t), barPaint, r.width() - 40 * k), r.left + 28 * k, r.centerY() + 9 * k, barPaint);
    }

    private final RectF screenBox = new RectF();

    private void drawMenu(Canvas c) {
        RectF chipR = tabRect(barSel);
        List<String> items = menuItems();
        int n = items.size(), visible = Math.min(n, 11);
        int first = Math.max(0, Math.min(n - visible, menuSel - visible / 2));
        float itemH = 58 * k, w = Math.max(chipR.width(), 380 * k);
        float left = Math.min(chipR.left, getWidth() - PAD * k - w);   // kept on the screen under a button at the right
        RectF box = new RectF(left, chipR.bottom + 10 * k, left + w, chipR.bottom + 10 * k + visible * itemH + 20 * k);
        screenBox.set(0, 0, getWidth(), getHeight());
        Frost.dropdown(c, box, 28 * k, screenBox, bmp, k);
        for (int v = 0; v < visible; v++) {
            int i = first + v;
            RectF r = new RectF(box.left + 10 * k, box.top + 10 * k + v * itemH, box.right - 10 * k, box.top + 10 * k + (v + 1) * itemH - 4 * k);
            boolean cur = i == menuCurrent(), focused = menuOpen && i == menuSel;
            if (focused) Frost.plate(c, r, 20 * k, k);
            Paint p = cur || focused ? chipBold : chip;
            p.setAlpha(255);
            c.drawText(items.get(i), r.left + 22 * k, r.centerY() + 9 * k, p);
            if (cur) Frost.tick(c, r.right - 28 * k, r.centerY(), 14 * k, k);
        }
    }

    /** The focused film's artwork behind everything, after the focus rests a moment (not on every step). */
    private void showArt() {
        main.removeCallbacks(artNow);
        main.postDelayed(artNow, 350);
    }

    private final Runnable artNow = this::showArtNow;

    private void showArtNow() {
        Item it = sel < shown.size() ? shown.get(sel) : null;
        String url = it == null || it.match == null ? null : it.match.backdrop != null ? it.match.backdrop : it.match.poster;
        stage.show(url);
        if (it != null) ensureLogo(it);
    }

    /** The grid: wide cards, four to a row, the focused one raised with a ring; the rest a little dimmed. */
    private static final int[] NEON = {0xFF7546F5, 0xFF1685FF, 0xFFF03CBF, 0xFF00D8E8, 0xFF5146C8};

    private boolean plateOn;
    private float plateX0, plateX1, plateY0, plateY1;
    private long plateAt;

    private void drawGrid(Canvas c, long now) {
        float first = scroll(now), w = cardW() * k, step = rowStep() * k, top = GRID_TOP * k, pic = 228 * k, rad = 24 * k;
        c.save();
        c.clipRect(0, top - 30 * k, getWidth(), getHeight());
        int fromRow = Math.max(0, (int) Math.floor(first)), toRow = (int) Math.ceil(first + rowsInView() + 1);
        Paint name = Theme.font(getContext(), 32 * k, 600);
        if (kit != null) name.setTypeface(kit.font(10, 600).getTypeface());
        name.setLetterSpacing(-0.018f);
        name.clearShadowLayer();
        if (!inBar && sel < shown.size()) {
            float gridX = PAD * k + (sel % COLS) * (w + COL_GAP * k), gridY = (sel / COLS) * step;   // y before scrolling
            if (!plateOn) {
                plateOn = true;
                plateX0 = plateX1 = gridX;
                plateY0 = plateY1 = gridY;
                plateAt = 0;
            } else if (gridX != plateX1 || gridY != plateY1) {
                plateX0 = Frost.glide(plateX0, plateX1, now, plateAt);
                plateY0 = Frost.glide(plateY0, plateY1, now, plateAt);
                plateX1 = gridX;
                plateY1 = gridY;
                plateAt = now;
            }
            float px = Frost.glide(plateX0, plateX1, now, plateAt), py = top + Frost.glide(plateY0, plateY1, now, plateAt) - first * step;
            Frost.plate(c, new RectF(px - 10 * k, py - 10 * k, px + w + 10 * k, py + pic + 100 * k), rad + 6 * k, k);
            if (now - plateAt < Frost.GLIDE_MS) postInvalidateOnAnimation();
        } else plateOn = false;
        for (int i = fromRow * COLS; i < shown.size() && i < toRow * COLS; i++) {
            Item it = shown.get(i);
            float x = PAD * k + (i % COLS) * (w + COL_GAP * k), y = top + (i / COLS - first) * step;
            boolean focused = i == sel && !inBar;
            RectF r = new RectF(x, y, x + w, y + pic);
            c.save();
            drawPicture(c, it, r, true);   // the picture fills the card
            if (!focused) {
                fill.setShader(null);
                fill.setColor(0x14060607);
                c.drawRoundRect(r, rad, rad, fill);
            }
            badges(c, it, r);
            c.restore();
            float tw = r.width() - 8 * k, tx = r.left + 4 * k;
            name.setColor(focused ? Color.WHITE : 0xE6F4F4F1);
            c.drawText(ellipsize(it.name(), name, tw), tx, r.bottom + 48 * k, name);
            List<String> parts = new ArrayList<>();   // genre · year · ★ rating · length
            List<String> g = genresOf(it);
            if (!g.isEmpty()) parts.add(g.get(0).replace("Science Fiction", "Sci-Fi"));
            if (it.year() > 0) parts.add(String.valueOf(it.year()));
            MyMovies.Match m = it.match;
            if (m != null && m.imdb > 0) parts.add("★ " + String.format(Locale.US, "%.1f", m.imdb));
            MyMovies.Video v = it.videos.get(0);
            if (it.show()) parts.add(getContext().getString(R.string.n_episodes, it.videos.size()));
            else if (v.durationMs > 0) parts.add(length(v.durationMs).replace(" h ", "h ").replace(" min", "m"));
            cardMeta.setColor(Frost.MUTE);
            float metaSize = cardMeta.getTextSize();
            cardMeta.setTextSize(metaSize * 0.9f);   // genre, year, rating and length all fit
            c.drawText(ellipsize(String.join(" · ", parts), cardMeta, tw), tx, r.bottom + 84 * k, cardMeta);
            cardMeta.setTextSize(metaSize);
        }
        c.restore();
    }

    /** Under a card: year · ⭐ rating · genre · length (or episodes), short; full: every genre, length in words. */
    private String cardLine(Item it, boolean full) {
        List<String> parts = new ArrayList<>();
        if (it.year() > 0) parts.add(String.valueOf(it.year()));
        MyMovies.Match m = it.match;
        if (m != null && m.imdb > 0) parts.add("IMDb " + String.format(Locale.US, "%.1f", m.imdb));
        List<String> g = genresOf(it);
        if (!g.isEmpty()) parts.add(full ? String.join(", ", g) : g.get(0).replace("Science Fiction", "Sci-Fi"));
        MyMovies.Video v = it.videos.get(0);
        if (it.show()) parts.add(getContext().getString(R.string.n_episodes, it.videos.size()));
        else if (v.durationMs > 0) parts.add(full ? length(v.durationMs) : length(v.durationMs).replace(" h ", "h ").replace(" min", "m"));
        return String.join("  ·  ", parts);
    }

    /** ✓ when watched, and a bar for how far you got. */
    private void badges(Canvas c, Item it, RectF r) {
        if (watched(it)) pill(c, r.right - 8 * k, r.top + 8 * k, "✓", 0xF02BB673);
        if (Tags.favourite(getContext(), it.key)) pill(c, r.left + 8 * k + 40 * k, r.top + 8 * k, "❤", 0xCC0B0B0D);
        MyMovies.Video next = nextVideo(it);
        long at = MyMovies.position(getContext(), next);
        if (at > 0 && next.durationMs > 0) {
            fill.setColor(0xAA000000);
            c.drawRect(r.left, r.bottom - 8 * k, r.right, r.bottom, fill);
            fill.setColor(Frost.CYAN);   // how far you got: cyan, as on Home
            c.drawRect(r.left, r.bottom - 8 * k, r.left + r.width() * Math.min(1, at / (float) next.durationMs), r.bottom, fill);
        }
    }

    private static String length(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h " + min % 60 + L10n.t(" min") : min + L10n.t(" min");
    }

    // The details panel, in design pixels.
    private static final float D_W = 1500, D_H = 860, D_PIC_W = 720;

    private RectF detailRect() {
        return new RectF(80 * k, 470 * k, 1560 * k, 1040 * k);
    }

    /** The cover at the left of the details, as tall as the box allows, 2:3. */
    private RectF detailCover(RectF p) {
        float h = p.height() - 72 * k;
        return new RectF(p.left + 36 * k, p.top + 36 * k, p.left + 36 * k + h * 2 / 3, p.top + 36 * k + h);
    }

    /** Where the words and buttons of the details start: right of the cover. */
    private float detailLeft(RectF p) {
        return detailCover(p).right + 40 * k;
    }

    /** Where a card's buttons go in its details: in lines under the picture, each with its icon and name. */
    private static boolean mainButton(String name) {
        return name.equals("play") || name.equals("restart") || name.equals("episodes") || name.equals("trailer");
    }

    private List<RectF> buttonRects(@Nullable Item it) {
        List<RectF> out = new ArrayList<>();
        if (it == null) return out;
        RectF p = detailRect();
        float x = detailLeft(p), xs = detailLeft(p), rowB = p.bottom - 88 * k, rowA = rowB - 84 * k;
        for (String name : buttons(it)) {
            if (mainButton(name)) {   // the main ones in a line, the rest as round icons under them
                float w = button.measureText(buttonName(it, name)) + 96 * k;
                out.add(new RectF(x, rowA, x + w, rowA + 68 * k));
                x += w + 12 * k;
            } else {
                out.add(new RectF(xs, rowB, xs + 64 * k, rowB + 64 * k));
                xs += 76 * k;
            }
        }
        return out;
    }

    /** The film page: its picture over the whole screen, its logo, and a glass panel with everything about it. */
    private void drawDetail(Canvas c) {
        Item it = detailItem;
        if (it == null) return;
        ensureLogo(it);
        RectF p = detailRect();
        boolean logoShown = drawName(c, it, p.left, p.top - 28 * k, 720 * k, 230 * k, 1700 * k);
        Frost.panel(c, p, 44 * k, 1, k);   // the shiny black pop-up, its cover on the left and its name on top
        RectF cov = detailCover(p);
        c.save();
        android.graphics.Path round = new android.graphics.Path();
        round.addRoundRect(cov, 24 * k, 24 * k, android.graphics.Path.Direction.CW);
        c.clipPath(round);
        drawPicture(c, it, cov, false);
        c.restore();
        MyMovies.Match mt = it.match;
        float x = detailLeft(p), right = p.right - 36 * k, y = p.top + 80 * k;
        if (logoShown) y = p.top + 52 * k;   // its logo is right above: the name isn't said twice
        else {
            float was = heroName.getTextSize();
            heroName.setTextSize(48 * k);
            while (heroName.getTextSize() > 32 * k && heroName.measureText(it.name()) > right - x) heroName.setTextSize(heroName.getTextSize() - 2 * k);
            c.drawText(ellipsize(it.name(), heroName, right - x), x, y, heroName);
            heroName.setTextSize(was);
            y += 48 * k;
        }
        c.drawText(ellipsize((it.show() ? L10n.t("Series  ·  ") : L10n.t("Film  ·  ")) + cardLine(it, true), heroMeta, right - x), x, y, heroMeta);
        y += 18 * k;
        float pillsH = pills(c, it, x, y, right);
        y += pillsH > 0 ? pillsH + 18 * k : 0;
        List<RectF> rects = buttonRects(it);
        float rowsTop = rects.isEmpty() ? p.bottom : rects.get(0).top;
        String desc = mt != null && !mt.overview.isEmpty() ? mt.overview : getContext().getString(R.string.not_matched_yet);
        TextPaint story = new TextPaint(heroBody);
        int lines = Math.max(1, Math.min(6, (int) ((rowsTop - 14 * k - y) / story.getFontSpacing())));
        StaticLayout dl = layout("dd" + it.key + "|" + desc.hashCode() + "|" + lines, desc, story, right - x, lines);
        c.save();
        c.translate(x, y);
        dl.draw(c);
        c.restore();
        List<String> b = buttons(it);
        boolean seen = watched(it), fav = Tags.favourite(getContext(), it.key);
        for (int i = 0; i < b.size(); i++) {
            String name = b.get(i);
            RectF r = rects.get(i);
            boolean focused = i == btn, main = mainButton(name);
            boolean on = name.equals("watched") && seen || name.equals("fav") && fav;
            float h = r.height();
            boolean jewel = name.equals("play");   // the one main button: the jewel colour
            if (jewel) Frost.hero(c, r, h / 2, focused, k);
            else Frost.ghost(c, r, h / 2, focused, k);
            Bitmap ic = kit.glyph(buttonGlyph(name), main ? 30 : 30);
            if (jewel) image.setColorFilter(new android.graphics.PorterDuffColorFilter(Frost.HERO_ICON, android.graphics.PorterDuff.Mode.SRC_IN));
            if (on) image.setColorFilter(new android.graphics.PorterDuffColorFilter(name.equals("fav") ? Frost.PINK : 0xFF5EE69A, android.graphics.PorterDuff.Mode.SRC_IN));
            float ix = main ? r.left + 26 * k : r.centerX() - ic.getWidth() / 2f, iy = r.centerY() - ic.getHeight() / 2f;
            c.drawBitmap(ic, ix, iy, image);
            image.setColorFilter(null);
            if (main) c.drawText(buttonName(it, name), r.left + 66 * k, r.centerY() + button.getTextSize() * 0.36f, button);
            else if (focused) {   // its name, at the end of the line of icons
                float end = rects.get(rects.size() - 1).right + 24 * k;
                c.drawText(buttonName(it, name), end, r.centerY() + 9 * k, heroMeta);
            }
        }
    }

    private static WheelArt.Glyph buttonGlyph(String b) {
        switch (b) {
            case "play": return WheelArt.Glyph.PLAY;
            case "restart": return WheelArt.Glyph.REDO;
            case "episodes": return WheelArt.Glyph.LIST;
            case "trailer": return WheelArt.Glyph.TRAILER;
            case "watched": return WheelArt.Glyph.EYE;
            case "rate": return WheelArt.Glyph.STAR;
            case "fav": return WheelArt.Glyph.HEART;
            case "tags": return WheelArt.Glyph.TAG;
            case "file": return WheelArt.Glyph.DRIVE;
            case "subs": return WheelArt.Glyph.SUBS;
            default: return WheelArt.Glyph.WRENCH;
        }
    }

    private static final WheelArt.Glyph[] VIEW_GLYPH = {WheelArt.Glyph.GRID, WheelArt.Glyph.HEART, WheelArt.Glyph.EYE, WheelArt.Glyph.PLAY,
            WheelArt.Glyph.FILM, WheelArt.Glyph.TV, WheelArt.Glyph.SLIDERS, WheelArt.Glyph.LIST, WheelArt.Glyph.SEARCH};

    private WheelArt.Glyph barGlyph(int t) {
        return VIEW_GLYPH[Math.min(VIEW_GLYPH.length - 1, t)];
    }

    private java.io.File posterStore;
    private boolean posterStoreLooked;

    /** Posters and pictures of your films are kept in ".tvtown/posters" on the drive (the TV's memory without one). */
    @Nullable
    private java.io.File posterStore() {
        if (!posterStoreLooked) {
            posterStoreLooked = true;
            posterStore = DriveStore.dir(DriveStore.anyDrive(getContext()), "posters");
        }
        return posterStore;
    }

    /** The backdrop (or poster), or for a file not matched a plain colour with its cleaned name. */
    private void drawPicture(Canvas c, Item it, RectF p, boolean wide) {
        String url = it.match == null ? null : wide && it.match.backdrop != null ? it.match.backdrop
                : it.match.poster != null ? it.match.poster : it.match.backdrop;
        // A copy at the card's own size: many fit in the TV's memory at once, so nothing reloads (flickers).
        Bitmap img = url != null ? ImageCache.get(url, this::invalidate, posterStore(), Math.round(p.width())) : null;
        if (img != null) {
            drawImage(c, img, p, 16 * k, 255);
            return;
        }
        int h = Math.abs(it.key.hashCode());
        int[] cols = {Theme.ROSE_B, Theme.VIOLET_B, Theme.AMBER_B, Theme.MINT_B};
        int col = cols[h % cols.length];
        fill.setShader(new LinearGradient(p.left, p.top, p.right, p.bottom, withAlpha(col, 200), withAlpha(col, 70), Shader.TileMode.CLAMP));
        c.drawRoundRect(p, 16 * k, 16 * k, fill);
        fill.setShader(null);
        placeholder.setAlpha(255);
        c.drawText(it.show() ? "" : "", p.left + 20 * k, p.top + 56 * k, placeholder);
        if (!GlassKit.neon(getContext())) {   // the neon cards write the name themselves
            StaticLayout l = layout("ph" + it.key + "|" + it.name(), it.name(), placeholder, p.width() - 40 * k, 2);
            c.save();
            c.translate(p.left + 20 * k, p.bottom - 20 * k - l.getHeight());
            l.draw(c);
            c.restore();
        }
    }

    private void pill(Canvas c, float right, float top, String text, int color) {
        float w = Math.max(40 * k, badge.measureText(text) + 26 * k), h = 40 * k;
        tmp2.set(right - w, top, right, top + h);
        fill.setColor(color);
        c.drawRoundRect(tmp2, h / 2, h / 2, fill);
        badge.setAlpha(255);
        c.drawText(text, tmp2.centerX() - badge.measureText(text) / 2, tmp2.centerY() + 8 * k, badge);
    }

    /** A dimmed screen with a glass panel in the middle (w x h design pixels). */
    private RectF overlay(Canvas c, float w, float h) {
        Frost.blurBehind(c, getWidth(), getHeight(), bmp);
        RectF p = new RectF((getWidth() - w * k) / 2, (getHeight() - h * k) / 2, (getWidth() + w * k) / 2, (getHeight() + h * k) / 2);
        kit.panel(c, p, 30 * k, 1, Theme.TINT_HIGH);
        fill.setColor(Color.argb(225, 18, 18, 20));
        c.drawRoundRect(p, 30 * k, 30 * k, fill);
        return p;
    }

    /** A show's seasons (chips) and episodes (cards with ✓ seen and how far you got); OK plays one. */
    private void drawEpisodes(Canvas c) {
        RectF p = overlay(c, 1500, 520);
        big.setAlpha(255);
        c.drawText("" + ellipsize(target.name(), big, p.width() - 120 * k), p.left + 48 * k, p.top + 72 * k, big);
        float x = p.left + 48 * k, y = p.top + 110 * k, h = 56 * k;
        for (int s : seasons) {
            String l = getContext().getString(R.string.season_n, s);
            float w = chip.measureText(l) + 48 * k;
            RectF r = new RectF(x, y, x + w, y + h);
            boolean cur = s == season;
            kit.pill(c, r, h / 2, 1);
            if (cur) {
                fill.setColor(0x38FFFFFF /* selected: a cyan wash */);
                c.drawRoundRect(r, h / 2, h / 2, fill);
            }
            if (onSeasons && cur) kit.focus(c, r, h / 2, 1);
            Paint tp = cur ? chipBold : chip;
            tp.setAlpha(255);
            c.drawText(l, r.left + 24 * k, r.centerY() + 9 * k, tp);
            x += w + 14 * k;
        }
        List<MyMovies.Video> eps = episodes();
        float ew = 220 * k, eh = 150 * k, ey = y + h + 40 * k, step = ew + 18 * k;
        int perView = (int) ((p.width() - 96 * k) / step);
        int first = Math.max(0, Math.min(episodeSel - 1, eps.size() - perView));
        c.save();
        c.clipRect(p.left + 30 * k, ey - 20 * k, p.right - 30 * k, ey + eh + 20 * k);
        for (int i = first; i < eps.size() && i < first + perView + 1; i++) {
            MyMovies.Video v = eps.get(i);
            MyMovies.Parsed pp = MyMovies.parse(v.name, v.folder, "");
            float ex = p.left + 48 * k + (i - first) * step;
            RectF r = new RectF(ex, ey, ex + ew, ey + eh);
            kit.pill(c, r, 18 * k, 1);
            if (!onSeasons && i == episodeSel) kit.focus(c, r, 18 * k, 1);
            big.setAlpha(255);
            c.drawText(L10n.t("E") + pp.episode, r.left + 22 * k, r.top + 52 * k, big);
            long pos = MyMovies.position(getContext(), v);
            String info = MyMovies.seen(getContext(), v) ? "✓ " + getContext().getString(R.string.watched)
                    : pos > 0 ? MyMovies.clock(pos) : v.durationMs > 0 ? MyMovies.clock(v.durationMs) : "";
            cardMeta.setAlpha(210);
            c.drawText(info, r.left + 22 * k, r.top + 96 * k, cardMeta);
            if (pos > 0 && v.durationMs > 0) {
                fill.setColor(Frost.CYAN);
                c.drawRect(r.left + 16 * k, r.bottom - 16 * k, r.left + 16 * k + (r.width() - 32 * k) * Math.min(1, pos / (float) v.durationMs),
                        r.bottom - 10 * k, fill);
            }
        }
        c.restore();
    }

    /** Clear, then 1 to 10 in a row; the chosen one large. Number keys also work (0 = 10). */
    private void drawRating(Canvas c) {
        RectF p = overlay(c, 1420, 300);
        big.setAlpha(255);
        c.drawText("" + ellipsize(target.name(), big, p.width() - 120 * k), p.left + 48 * k, p.top + 68 * k, big);
        sub.setAlpha(210);
        c.drawText(getContext().getString(R.string.rate_hint), p.left + 48 * k, p.top + 110 * k, sub);
        float size = 88 * k, gap = 14 * k, x = p.left + 48 * k, y = p.top + 150 * k;
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

    /** Fix match: TMDB results for the name (movies and series), then "Type a name". */
    private void drawFix(Canvas c) {
        int n = fixFound.size() + 1;
        float itemH = 62;
        RectF box = overlay(c, 980, 120 + Math.max(1, n) * itemH);
        big.setAlpha(255);
        c.drawText("" + getContext().getString(R.string.fix_match), box.left + 36 * k, box.top + 64 * k, big);
        if (fixLoading) {
            sub.setAlpha(200);
            c.drawText(getContext().getString(R.string.searching), box.left + 36 * k, box.top + 120 * k, sub);
        }
        for (int i = 0; i < n; i++) {
            RectF r = new RectF(box.left + 24 * k, box.top + (92 + i * itemH) * k, box.right - 24 * k, box.top + (92 + (i + 1) * itemH - 6) * k);
            if (i == fixSel) {
                fill.setColor(0x38FFFFFF /* selected: a cyan wash */);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            }
            String l;
            if (i < fixFound.size()) {
                Tmdb.Title t = fixFound.get(i);
                l = (t.isSeries() ? "" : "") + t.title + (t.year > 0 ? "  (" + t.year + ")" : "");
            } else {
                l = "⌨" + getContext().getString(R.string.type_a_name);
            }
            Paint tp = i == fixSel ? chipBold : chip;
            tp.setAlpha(255);
            c.drawText(ellipsize(l, tp, r.width() - 48 * k), r.left + 24 * k, r.centerY() + 9 * k, tp);
        }
    }

    // ---- panels: filter, tags, subtitles --------------------------------------------------------------

    private static final float PANEL_W = 820, CHIP_H = 54, HEAD = 150;
    // The filter has four short sections, each shown only when the drive gives it at least two choices.
    private static final String S_QUICK = "Quick picks", S_GENRE = "Genre", S_TAGS = "Mood", S_AUDIO = "Spoken in",
            S_INFO = L10n.t("Info"), S_FOUND = L10n.t("Found"), S_ACT = "";
    private Item panelItem;
    private float contentH;

    private void openFilter() {
        panel = P_FILTER;
        focusChip = null;
        panelScroll = 0;
        buildFilter();
    }

    /**
     * The choices, in four plain sections: Quick picks (rated 7+, in a language you follow, under two hours, added
     * lately); the genres on the drive, most common first, with how many films each; the moods (tags) the films
     * have; and the languages they are spoken in. A section shows only with two or more choices. Then Clear and Done.
     */
    private void buildFilter() {
        chips.clear();
        filterCounts.clear();
        Map<String, Integer> genres = new HashMap<>(), moods = new HashMap<>(), spoken = new HashMap<>();
        for (Item it : all) {
            for (String x : genresOf(it)) genres.merge(x, 1, Integer::sum);
            for (String x : tagsOf(it)) moods.merge(x, 1, Integer::sum);
            for (String x : languagesOf(it)) if (x.startsWith("a:")) spoken.merge(x.substring(2), 1, Integer::sum);
        }
        Map<String, List<String[]>> sections = new java.util.LinkedHashMap<>();
        sections.put(S_GENRE, topChoices(genres, fGenres, 1, ""));
        sections.put(S_TAGS, topChoices(moods, fTags, MOOD_MIN, ""));
        java.util.Set<String> ticked = new java.util.HashSet<>();
        for (String x : fLangs) if (x.startsWith("a:")) ticked.add(x.substring(2));
        sections.put(S_AUDIO, topChoices(spoken, ticked, 1, "a:"));
        sections.values().removeIf(l -> l.size() < 2);
        int cols = Math.max(1, sections.size()), most = 0;
        for (List<String[]> l : sections.values()) most = Math.max(most, l.size());
        float colW = 460 * k, gap = 12 * k, w = cols * colW + (cols - 1) * gap + 48 * k, h = (MEGA_HEAD + most * MEGA_ROW + 24) * k;
        float right = getWidth() - (PAD - 8) * k;
        megaBox.set(right - w, 188 * k, right, 188 * k + h);
        int col = 0;
        for (Map.Entry<String, List<String[]>> sec : sections.entrySet()) {
            float left = megaBox.left + 24 * k + col * (colW + gap);
            List<String[]> l = sec.getValue();
            for (int i = 0; i < l.size(); i++) {
                String[] x = l.get(i);
                boolean on = sec.getKey().equals(S_GENRE) ? fGenres.contains(x[0]) : sec.getKey().equals(S_TAGS) ? fTags.contains(x[0]) : fLangs.contains(x[0]);
                Chip ch = new Chip(sec.getKey(), x[0], x[1], on);
                float top = megaBox.top + (MEGA_HEAD + i * MEGA_ROW) * k;
                ch.rect.set(left, top, left + colW, top + (MEGA_ROW - 4) * k);
                filterCounts.put(x[0], x[2]);
                chips.add(ch);
            }
            col++;
        }
        if (filterCount() > 0) {
            Chip clear = new Chip(S_ACT, "clear", getContext().getString(R.string.clear_all), false);
            float cw = chipBold.measureText(clear.label) + 56 * k;
            clear.rect.set(megaBox.right - 24 * k - cw, megaBox.top + 12 * k, megaBox.right - 24 * k, megaBox.top + 64 * k);
            chips.add(clear);
        }
        contentH = 0;
        panelScroll = 0;
        Chip keep = null;
        if (focusChip != null) for (Chip ch : chips) if (ch.section.equals(focusChip.section) && ch.value.equals(focusChip.value)) keep = ch;
        if (keep == null && panel == P_FILTER && !chips.isEmpty()) keep = chips.get(0);
        focusChip = keep;
    }

    /** The choices that find films, most films first, at most ten so a page never scrolls; what is ticked always stays. */
    private List<String[]> topChoices(Map<String, Integer> counts, java.util.Set<String> ticked, int min, String prefix) {
        List<String> names = new ArrayList<>(counts.keySet());
        for (String x : ticked) if (!names.contains(x)) names.add(x);
        names.sort((a, b) -> counts.getOrDefault(b, 0) - counts.getOrDefault(a, 0));
        List<String[]> out = new ArrayList<>();
        for (String x : names) {
            if (ticked.contains(x)) continue;
            if (counts.getOrDefault(x, 0) >= min && out.size() + ticked.size() < FILTER_MAX) out.add(new String[]{prefix + x, x, String.valueOf(counts.getOrDefault(x, 0))});
        }
        int at = 0;
        for (String x : ticked) out.add(at++, new String[]{prefix + x, x, String.valueOf(counts.getOrDefault(x, 0))});
        return out;
    }

    private static final int FILTER_MAX = 10, MOOD_MIN = 3;
    private static final float MEGA_HEAD = 76, MEGA_ROW = 64;
    private final RectF megaBox = new RectF();
    private static final String S_CAT = "Cat";
    private String filterCat = S_QUICK;
    private final Map<String, String> filterCounts = new HashMap<>();

    /** Arrows on the filter page: Up and Down change the kind of filter on the left; Right goes into its choices. */
    private boolean filterKey(int key) {
        if (focusChip == null) return true;
        List<List<Chip>> cols = new ArrayList<>();
        Chip clear = null;
        String last = null;
        for (Chip ch : chips) {
            if (ch.section.equals(S_ACT)) {
                clear = ch;
                continue;
            }
            if (!ch.section.equals(last)) {
                cols.add(new ArrayList<>());
                last = ch.section;
            }
            cols.get(cols.size() - 1).add(ch);
        }
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP, down = key == KeyEvent.KEYCODE_DPAD_DOWN, left = key == KeyEvent.KEYCODE_DPAD_LEFT, right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (focusChip.section.equals(S_ACT)) {
            if ((left || down) && !cols.isEmpty()) focusChip = cols.get(cols.size() - 1).get(0);
            else if (up) closeToBar();
            return true;
        }
        int c = -1, r = -1;
        for (int i = 0; i < cols.size(); i++) {
            int at = cols.get(i).indexOf(focusChip);
            if (at >= 0) {
                c = i;
                r = at;
            }
        }
        if (c < 0) return true;
        if (up) {
            if (r > 0) focusChip = cols.get(c).get(r - 1);
            else if (clear != null) focusChip = clear;   // Clear all, in the menu's top corner
            else closeToBar();
        } else if (down) {
            if (r + 1 < cols.get(c).size()) focusChip = cols.get(c).get(r + 1);
        } else if (left) {
            if (c > 0) focusChip = cols.get(c - 1).get(Math.min(r, cols.get(c - 1).size() - 1));
        } else if (right) {
            if (c + 1 < cols.size()) focusChip = cols.get(c + 1).get(Math.min(r, cols.get(c + 1).size() - 1));
            else if (clear != null) focusChip = clear;
        }
        return true;
    }

    /** Up from the top of the menu: back on Filter in the bar, the menu still showing. */
    private void closeToBar() {
        panel = 0;
        focusChip = null;
        inBar = true;
        barSel = T_FILTER;
        hoverBuilt = false;
    }

    private void drawFilter(Canvas c) {
        fill.setShader(null);
        fill.setColor(0x73060607);   // the films step back a little
        c.drawRect(0, 186 * k, getWidth(), getHeight(), fill);
        screenBox.set(0, 0, getWidth(), getHeight());
        Frost.dropdown(c, megaBox, 36 * k, screenBox, bmp, k);
        Paint lab = new Paint(barPaint);
        lab.setTextSize(28 * k);
        Paint count = new Paint(lab), headP = new Paint(lab);
        count.setTextSize(25 * k);
        headP.setTextSize(26 * k);
        if (kit != null) headP.setTypeface(kit.font(10, 600).getTypeface());
        String section = null;
        for (Chip ch : chips) {
            RectF r = ch.rect;
            boolean focused = ch == focusChip;
            if (ch.section.equals(S_ACT)) {
                Frost.ghost(c, r, r.height() / 2, focused, k);
                lab.setColor(Frost.INK);
                c.drawText(ch.label, r.centerX() - lab.measureText(ch.label) / 2, r.centerY() + 10 * k, lab);
                continue;
            }
            if (!ch.section.equals(section)) {   // the column's name
                section = ch.section;
                headP.setColor(Frost.FAINT);
                c.drawText(section, r.left + 18 * k, megaBox.top + 52 * k, headP);
            }
            if (focused) Frost.plate(c, r, 20 * k, k);
            float cx = r.left + 34 * k, cy = r.centerY(), cr = 15 * k;
            if (ch.on) Frost.tick(c, cx, cy, cr, k);
            else Frost.untick(c, cx, cy, cr, k);
            String num = filterCounts.get(ch.value);
            float room = r.right - 18 * k - (cx + 30 * k) - (num != null ? count.measureText(num) + 14 * k : 0);
            lab.setColor(focused || ch.on ? Frost.INK : 0xDEF4F4F1);
            c.drawText(ellipsize(ch.label, lab, room), cx + 30 * k, cy + 10 * k, lab);
            if (num != null) {
                count.setColor(Frost.MUTE);
                c.drawText(num, r.right - 18 * k - count.measureText(num), cy + 9 * k, count);
            }
        }
    }

    // Quick picks: one tick each, the most asked-for ways to narrow the list.
    private static final String[] QUICK = {"rated7", "follow", "short", "recent"};
    private final java.util.Set<String> fQuick = new java.util.LinkedHashSet<>();

    private void openTags(Item it) {
        panel = P_TAGS;
        panelItem = it;
        focusChip = null;
        panelScroll = 0;
        buildTags();
    }

    /** Every tag, ticked when the film has it (✨ when chosen automatically), then New tag and Done. */
    private void buildTags() {
        Item it = panelItem;
        List<String> on = tagsOf(it), auto = autoTags(it);
        chips.clear();
        for (String x : Tags.choices(getContext())) {
            chips.add(new Chip(S_TAGS, x, Tags.emoji(x) + "  " + x + (auto.contains(x) ? "  " : ""), on.contains(x)));
        }
        chips.add(new Chip(S_ACT, "new", "➕  " + getContext().getString(R.string.new_tag), false));
        chips.add(new Chip(S_ACT, "done", "✓  " + getContext().getString(R.string.done), false));
        layoutChips(false);
    }

    private static List<String> autoTags(Item it) {
        return it.match != null ? it.match.tags : new ArrayList<>();
    }

    /** Places the chips (in panel coordinates, below the heading), keeping the focus on the same choice. */
    private void layoutChips(boolean rows) {
        String keep = focusChip != null ? focusChip.section + "|" + focusChip.value : null;
        float w = (PANEL_W - 96) * k, x = 0, y = 0, h = CHIP_H * k, gap = 12 * k;
        String section = null;
        focusChip = null;
        for (Chip ch : chips) {
            if (!ch.section.equals(section)) {
                if (section != null) y += rows ? 16 * k : h + 28 * k;
                x = 0;
                if (!ch.section.isEmpty() && panel == P_FILTER) y += 46 * k;   // room for the section's name
                section = ch.section;
            }
            float cw = rows ? w : Math.min(w, chipBold.measureText("✓ " + ch.label) + 48 * k);   // room for the tick
            if (x > 0 && x + cw > w) {
                x = 0;
                y += h + gap;
            }
            ch.rect.set(x, y, x + cw, y + h);
            x += cw + gap;
            if (rows) {
                x = 0;
                y += h + gap;
            }
            if (keep != null && keep.equals(ch.section + "|" + ch.value)) focusChip = ch;
        }
        contentH = rows ? y : y + h;
        if (focusChip == null || focusChip.section.equals(S_INFO)) {
            for (Chip ch : chips) {
                if (ch.section.equals(S_INFO)) continue;
                focusChip = ch;
                break;
            }
        }
        keepInView();
    }

    private RectF panelRect() {
        return new RectF(getWidth() - (PANEL_W + 48) * k, 48 * k, getWidth() - 48 * k, getHeight() - 48 * k);
    }

    private float viewH() {
        return panelRect().height() - (HEAD + 40) * k;
    }

    private void keepInView() {
        if (focusChip == null) return;
        float above = panel == P_FILTER ? 56 * k : 0;   // the section's name stays in view
        if (focusChip.rect.top - above < panelScroll) panelScroll = Math.max(0, focusChip.rect.top - above);
        if (focusChip.rect.bottom > panelScroll + viewH()) panelScroll = focusChip.rect.bottom - viewH();
    }

    /** The arrows move to the nearest chip that way. */
    private boolean panelKey(int key) {
        if (!isDpad(key)) return false;
        if (panel == P_FILTER) return filterKey(key);
        if (focusChip == null) return true;
        float cx = focusChip.rect.centerX(), cy = focusChip.rect.centerY();
        Chip best = null;
        float bestScore = Float.MAX_VALUE;
        for (Chip ch : chips) {
            if (ch == focusChip || ch.section.equals(S_INFO)) continue;
            float dx = ch.rect.centerX() - cx, dy = ch.rect.centerY() - cy, score;
            boolean sameRow = Math.abs(dy) < CHIP_H * k / 2;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (!sameRow || dx >= 0) continue; score = -dx; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (!sameRow || dx <= 0) continue; score = dx; break;
                case KeyEvent.KEYCODE_DPAD_UP: if (dy >= -1) continue; score = -dy * 3 + Math.abs(dx); break;
                default: if (dy <= 1) continue; score = dy * 3 + Math.abs(dx); break;
            }
            if (score < bestScore) {
                bestScore = score;
                best = ch;
            }
        }
        if (best != null) focusChip = best;
        else if (key == KeyEvent.KEYCODE_DPAD_UP) panelScroll = 0;   // show the heading again
        keepInView();
        return true;
    }

    private void panelSelect() {
        Chip ch = focusChip;
        if (ch == null) return;
        Context ctx = getContext();
        if (ch.value.equals("done")) {
            closePanel();
            return;
        }
        if (panel == P_FILTER) {
            if (ch.section.equals(S_CAT)) {   // OK on a kind of filter: into its choices
                for (Chip o : chips) {
                    if (o.section.equals(filterCat)) {
                        focusChip = o;
                        break;
                    }
                }
                return;
            }
            switch (ch.section) {
                case S_GENRE: toggle(fGenres, ch.value); break;
                case S_TAGS: toggle(fTags, ch.value); break;
                case S_AUDIO: toggle(fLangs, ch.value); break;
                case S_QUICK: toggle(fQuick, ch.value); break;
                default:   // Clear all
                    fGenres.clear();
                    fTags.clear();
                    fLangs.clear();
                    fQuick.clear();
            }
            saveFilters();
            filter();
            buildFilter();   // ticks, and Clear all only while something is ticked
        } else if (panel == P_TAGS) {
            Item it = panelItem;
            if (ch.value.equals("new")) {
                if (listener != null) listener.onTypeTag(name -> {
                    String n = Tags.addCustom(ctx, name);
                    if (n == null || panel != P_TAGS || panelItem != it) return;
                    Tags.set(ctx, it.key, n, true, autoTags(it));
                    buildTags();
                    if (!fTags.isEmpty()) filter();
                    invalidate();
                });
                return;
            }
            Tags.set(ctx, it.key, ch.value, !ch.on, autoTags(it));
            ch.on = !ch.on;
            layoutChips(false);
            if (!fTags.isEmpty()) filter();
        } else if (panel == P_FILE) fileSelect(ch);
    }

    // ---- File: rename, move, copy, delete subtitles, delete ---------------------------------------------

    private void openFile(Item it) {
        panel = P_FILE;
        panelItem = it;
        focusChip = null;
        panelScroll = 0;
        fileStep = "main";
        deleteArmed = false;
        buildFile();
    }

    @Nullable
    private java.io.File fileOf(Item it) {
        MyMovies.Video v = it.videos.get(0);
        java.io.File dir = Tracks.folder(getContext(), v);
        return dir == null ? null : new java.io.File(dir, v.name);
    }

    private void buildFile() {
        chips.clear();
        java.io.File f = fileOf(panelItem);
        if (f == null || !f.exists()) {
            chips.add(new Chip(S_INFO, "", L10n.t("To change files, TV Town needs All files access"), false));
            chips.add(new Chip(S_ACT, "access", L10n.t("Allow All files access"), false));
        } else if (fileStep.equals("main")) {
            chips.add(new Chip(S_ACT, "rename", "✎  " + L10n.t("Rename"), false));
            chips.add(new Chip(S_ACT, "move", "➜  " + L10n.t("Move to…"), false));
            chips.add(new Chip(S_ACT, "copy", "⧉  " + L10n.t("Copy to…"), false));
            int subs = FileOps.subtitles(f).size();
            if (subs > 0) chips.add(new Chip(S_ACT, "subs", "✕  " + L10n.t("Delete subtitles") + " (" + subs + ")", false));
            chips.add(new Chip(S_ACT, "delete", deleteArmed ? "⚠  " + L10n.t("Press OK again to delete") : "🗑  " + L10n.t("Delete film"), false));
        } else {
            java.io.File drive = FileOps.driveOf(getContext(), panelItem.videos.get(0).volume);
            for (int i = 0; i < fileFolders.size(); i++)
                chips.add(new Chip(S_FOLDER, String.valueOf(i), "📁  " + (drive != null ? FileOps.shown(drive, fileFolders.get(i)) : fileFolders.get(i).getName()), false));
            chips.add(new Chip(S_ACT, "back", "‹  " + L10n.t("Back"), false));
        }
        chips.add(new Chip(S_ACT, "done", "✓  " + getContext().getString(R.string.done), false));
        layoutChips(false);
        invalidate();
    }

    private void fileSelect(Chip ch) {
        Context ctx = getContext();
        java.io.File f = fileOf(panelItem);
        if (ch.value.equals("access")) {
            try {
                ctx.startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.getPackageName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                say(L10n.t("This TV has no page for that"));
            }
            return;
        }
        if (f == null) return;
        switch (ch.value) {
            case "rename":
                if (listener != null) listener.onAskName(L10n.t("Rename"), FileOps.base(f.getName()), typed -> {
                    if (typed == null || typed.trim().isEmpty()) return;
                    fileJob(() -> {
                        String[] why = {""};
                        return FileOps.rename(ctx, f, typed.trim(), why) != null ? L10n.t("Renamed") : why[0];
                    });
                });
                return;
            case "move":
            case "copy": {
                java.io.File drive = FileOps.driveOf(ctx, panelItem.videos.get(0).volume);
                if (drive == null) return;
                fileStep = ch.value;
                fileFolders = FileOps.folders(drive);
                focusChip = null;
                buildFile();
                return;
            }
            case "back":
                fileStep = "main";
                focusChip = null;
                buildFile();
                return;
            case "subs": {
                MyMovies.Video v = panelItem.videos.get(0);
                int n = FileOps.delete(ctx, FileOps.subtitles(f));
                Tracks.setDownloaded(ctx, v, null);
                Tracks.forget(ctx, v);
                say(n + L10n.t(" subtitle files deleted"));
                buildFile();
                return;
            }
            case "delete":
                if (!deleteArmed) {
                    deleteArmed = true;
                    buildFile();
                    return;
                }
                fileJob(() -> {
                    List<java.io.File> all = new ArrayList<>(FileOps.sidecars(f));
                    all.add(0, f);
                    return FileOps.delete(ctx, all) > 0 ? L10n.t("Deleted") : L10n.t("The drive didn't allow it");
                });
                return;
            default:
                if (ch.section.equals(S_FOLDER)) {
                    java.io.File to = fileFolders.get(Integer.parseInt(ch.value));
                    boolean copy = fileStep.equals("copy");
                    say(copy ? L10n.t("Copying…") : L10n.t("Moving…"));
                    fileJob(() -> {
                        String[] why = {""};
                        return FileOps.place(ctx, f, to, copy, why) ? (copy ? L10n.t("Copied") : L10n.t("Moved")) : why[0];
                    });
                }
        }
    }

    /** A file change off the main thread; then its answer, the panel and the film page closed, and the list read again. */
    private void fileJob(java.util.concurrent.Callable<String> job) {
        net.execute(() -> {
            String msg;
            try {
                msg = job.call();
            } catch (Exception e) {
                msg = L10n.t("The drive didn't allow it");
            }
            String m = msg;
            main.postDelayed(() -> {   // the media list catches up first
                say(m);
                closePanel();
                detailOpen = false;
                rescan();
            }, 700);
        });
    }

    private void say(String text) {
        android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static void toggle(java.util.Set<String> set, String v) {
        if (!set.remove(v)) set.add(v);
    }

    private void closePanel() {
        if (panel == P_FILTER) {
            inBar = true;
            barSel = T_FILTER;
        }
        hoverBuilt = false;
        panel = 0;
        panelItem = null;
        focusChip = null;
        invalidate();
    }

    /** The language pill, then the tags (wrapping to a second line). Returns the height used (0: none). */
    private float pills(Canvas c, Item it, float x, float y, float right) {
        float h = 44 * k, gap = 10 * k, left = x, top = y;
        boolean any = false;
        Tracks.Label l = languageOf(it);
        badge.setAlpha(255);
        if (l != null && !l.text.isEmpty()) {
            String s = l.text;
            float w = Math.min(right - x, badge.measureText(s) + 32 * k);
            fill.setColor(l.foreignNoSubs ? 0x66E0457B : 0x24FFFFFF);
            tmp2.set(x, y, x + w, y + h);
            c.drawRoundRect(tmp2, h / 2, h / 2, fill);
            c.drawText(ellipsize(s, badge, w - 32 * k), x + 16 * k, tmp2.centerY() + 8 * k, badge);
            x += w + gap;
            any = true;
        }
        List<String> tags = tagsOf(it), added = Tags.added(getContext(), it.key);
        for (String t : tags) {
            String s = t;
            float w = badge.measureText(s) + 32 * k;
            if (x + w > right) {   // the next line, at most two
                if (y > top) break;
                x = left;
                y += h + gap;
            }
            fill.setColor(added.contains(t) ? 0x38FFFFFF : 0x24FFFFFF);
            tmp2.set(x, y, x + w, y + h);
            c.drawRoundRect(tmp2, h / 2, h / 2, fill);
            c.drawText(s, x + 16 * k, tmp2.centerY() + 8 * k, badge);
            x += w + gap;
            any = true;
        }
        return any ? y - top + h : 0;
    }

    /** A glass panel on the right: heading, a live count (filter), and the chips, scrolled to keep the focus in view. */
    private void drawPanel(Canvas c) {
        if (panel == P_FILTER) {
            drawFilter(c);
            return;
        }
        Frost.blurBehind(c, getWidth(), getHeight(), bmp);
        RectF p = panelRect();
        kit.panel(c, p, 30 * k, 1, Theme.TINT_HIGH);
        fill.setColor(Color.argb(232, 18, 18, 20));
        c.drawRoundRect(p, 30 * k, 30 * k, fill);
        Context ctx = getContext();
        String head, line;
        if (panel == P_FILTER) {
            head = "⚙  " + ctx.getString(R.string.filter);
            int n = 0;
            for (Item it : all) if (passes(it, view)) n++;
            line = ctx.getString(R.string.filter_count, n, all.size(), VIEWS[view]);
        } else {
            head = panelItem.name();
            line = panel == P_FILE ? L10n.t("Rename, move, copy or delete this film's file") : ctx.getString(R.string.tags_hint);
        }
        big.setAlpha(255);
        c.drawText(ellipsize(head, big, p.width() - 96 * k), p.left + 48 * k, p.top + 76 * k, big);
        sub.setAlpha(200);
        c.drawText(ellipsize(line, sub, p.width() - 96 * k), p.left + 48 * k, p.top + 118 * k, sub);
        float top = p.top + HEAD * k, left = p.left + 48 * k;
        c.save();
        c.clipRect(p.left, top - 10 * k, p.right, top + viewH());
        c.translate(left, top - panelScroll);
        String section = null;
        for (Chip ch : chips) {
            if (!ch.section.equals(section)) {
                section = ch.section;
                if (!section.isEmpty() && panel == P_FILTER) {
                    cardMeta.setAlpha(170);
                    c.drawText(section.toUpperCase(Locale.ROOT), 4 * k, ch.rect.top - 16 * k, cardMeta);
                }
            }
            if (ch.rect.bottom < panelScroll - 60 * k || ch.rect.top > panelScroll + viewH() + 60 * k) continue;
            RectF r = ch.rect;
            boolean focused = ch == focusChip, info = ch.section.equals(S_INFO), done = ch.value.equals("done");
            if (info) {
                sub.setAlpha(225);
                c.drawText(ellipsize(ch.label, sub, r.width()), r.left + 4 * k, r.centerY() + 9 * k, sub);
                continue;
            }
            fill.setColor(done ? Color.WHITE : ch.on ? 0x38FFFFFF /* selected: a cyan wash */ : 0x26FFFFFF);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            if (focused) kit.focus(c, r, r.height() / 2, 1);
            Paint tp = done ? buttonInk : ch.on || focused ? chipBold : chip;
            tp.setAlpha(255);
            String l = ellipsize((ch.on ? "✓ " : "") + ch.label, tp, r.width() - 40 * k);
            c.drawText(l, r.left + 22 * k, r.centerY() + 9 * k, tp);
        }
        c.restore();
        if (panelScroll + viewH() < contentH) {   // more below
            help.setAlpha(200);
            c.drawText("⌄", p.centerX() - help.measureText("⌄") / 2, p.bottom - 14 * k, help);
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

    private static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
