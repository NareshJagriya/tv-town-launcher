package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The home screen. The featured title fills the screen behind a hero text block; on the right a 3D
 * glass cover-flow of apps, with action buttons under it; below, a full-width row of what is new this week on
 * your apps (it moves only with the remote), and the three Random tiles.
 *
 * Navigation is strict: Left/Right move within a row, Up/Down move between rows (top bar, apps, actions, new
 * this week, random), every row remembers its position, and Back returns to the apps. Sizes are in 1920 x 1080
 * design pixels scaled by k. Artwork is rendered once into bitmaps; frames only move, fade and scale them.
 */
public class HomeView extends View implements Choreographer.FrameCallback, GlassSheet.Host {

    interface Listener {
        void onOpenApp(Apps.App app, Rect from);

        void onAppInfo(Apps.App app);

        /** Open a title on its service (deep link, else the app, else the Play Store). */
        void onPlay(Tmdb.Title t);

        /** The title's trailer, in the YouTube app (checked first: removed trailers fall back to a search). */
        void onTrailer(Tmdb.Title t);

        void onDiscover();

        void onInput(Inputs.Source s);

        void onWifi();

        void onSystemSettings();

        /** Opens the TV's accessibility settings so Home button mode can be switched on. */
        void onAccessibilitySettings();

        /** My Movies: the videos on the plugged-in pen drive. */
        void onMyMovies();

        /** Continue watching (the Bento board): a film from My Movies, from a place (-1: where VLC left it). */
        void onResumeFilm(MyMovies.Video video, long fromMs, String title);

        /** My Music: the songs on the plugged-in drive, and TV Town's own player. */
        void onMyMusic();

        /** My Music opened straight on the player (the song on). */
        default void onMusicPlayer() {
            onMyMusic();
        }

        /** Must-Watch: the best rated films and series. */
        void onMustWatch();

        /** The remote's mic button on TV Town: listen, then do what was said. */
        void onVoice();

        /** Opens the stock launcher once, when it is still enabled; false when it is not. */
        boolean onOpenStockHome();

        /** The viewer's own YouTube mix, from a random song. */
        void onMyMix();

        /** Up to `count` new random titles for a card of the Random pop-up; `done` runs on the main thread. */
        void onPickTitles(int kind, Picks.Entry card, List<String> ticked, int count, java.util.function.Consumer<List<Tmdb.Title>> done);

        /** Random Music: songs or playlists for the choice; `done` runs on the main thread. */
        void onFindMusic(int kind, Picks.Entry card, List<String> ticked, List<String> shown, boolean playlists,
                         java.util.function.Consumer<List<Songs.Pick>> done);

        /** A picture for a Random card, or null; `done` runs on the main thread. */
        void onCardArt(int kind, Picks.Entry card, String language, java.util.function.Consumer<String> done);

        /** A line of text typed with the TV's keyboard (null when cancelled); `done` runs on the main thread. */
        void onAskText(String title, String hint, java.util.function.Consumer<String> done);

        /** TV Town's keyboard, searching YouTube for something to save. */
        void onSaveSearch();

        /** TV Town's keyboard, for a search from Home. */
        void onSearchKeys();

        /** As onAskText, with the OK button's own word ("Search"). */
        default void onAskText(String title, String hint, String ok, java.util.function.Consumer<String> done) {
            onAskText(title, hint, done);
        }

        void onPlayMusic(Songs.Pick pick);

        /** Search: films and series (with the app each is on), then YouTube videos; `done` on the main thread. */
        void onSearch(String query, java.util.function.Consumer<List<Object>> done);

        /** A playlist's first songs, into pick.includes; `done` runs on the main thread. */
        void onPeek(Songs.Pick pick, Runnable done);

        void onMessage(String text);
    }

    // ---- carousel items --------------------------------------------------------------------------

    private static final int DISCOVER = 0, SERVICE = 1, YOUTUBE = 2, MUSIC = 3, APP = 4, ALL_APPS = 5, INPUTS = 6;
    // Discover is a tab now; it can still be put back on the carousel in Customize.
    static final String[] DEFAULT_ORDER = {"com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            Services.YOUTUBE_PKG, "in.startv.hotstar", "com.apple.atve.androidtv.appletv", Services.YT_MUSIC_PKG, "allapps"};
    // Outside India: the big Western apps (those not installed are left out, as everywhere).
    static final String[] DEFAULT_ORDER_WEST = {"com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            Services.YOUTUBE_PKG, Services.DISNEY.pkg, Services.MAX.pkg, Services.HULU.pkg,
            "com.apple.atve.androidtv.appletv", Services.YT_MUSIC_PKG, "allapps"};
    static final int MAX_ITEMS = 10;

    private static final class Item {
        final String id, name;
        final int kind;
        final Apps.App app;
        final Services.Service service;
        Bitmap card, reflection, disc;
        int color = 0xFFB8C4D6;
        Tmdb.Title feature;
        boolean loading, loaded;

        Item(String id, String name, int kind, @Nullable Apps.App app, @Nullable Services.Service service) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.app = app;
            this.service = service;
        }
    }

    /** What the hero and backdrop show. */
    private static final class Hero {
        String kicker = "", title = "", meta = "", synopsis = "", backdrop, trailer;
        Bitmap badge;
        Tmdb.Title title_;
    }

    private enum Row { TOP, APPS, ACTIONS, NEW, RANDOM, SIDE, CONT, LIST, NOWP, NOTHING }   // NOTHING: the covers' page when there are none yet   // LIST: My Watchlist, under Continue   // CONT, NOWP: the neon glass look only

    private static final class Action {
        final String label, kind;   // kind: play, trailer, info, open, allapps, inputs, discover
        final boolean primary;

        Action(String label, String kind, boolean primary) {
            this.label = label;
            this.kind = kind;
            this.primary = primary;
        }
    }

    // ---- state -----------------------------------------------------------------------------------

    private Listener listener;
    private final SharedPreferences prefs;
    private final ArtBackdrop art;
    private Paint marqueeP, marqueeSmallP;
    private GlassKit kit;
    private float k = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Work.pool(2);

    private final List<Apps.App> apps = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Tmdb.Title> fresh = new ArrayList<>();   // new this week
    private boolean freshLoading, freshFailed;
    private long freshLoadedAt;
    private volatile String sourceAsked = "";   // what the top picture was loading for
    private final Map<String, Bitmap> freshCards = new HashMap<>();

    private Row row = Row.APPS;
    private int topSel = 5, actSel, randSel;   // the top bar starts on the Discover tab
    // The top bar, left to right: the tabs (Home, Discover, Must-Watch, My Movies), then Search, Customize, Wi-Fi
    // and Settings. topSel holds one of these numbers.
    private static final int T_CUSTOMIZE = 0, T_WIFI = 1, T_SETTINGS = 2, T_SEARCH = 3, T_HOME = 4, T_DISCOVER = 5,
            T_MUST = 6, T_MOVIES = 7, T_MUSIC = 8;
    private static final int[] TOP_ORDER = {T_HOME, T_DISCOVER, T_MUST, T_MOVIES, T_MUSIC, T_SEARCH, T_CUSTOMIZE, T_WIFI, T_SETTINGS};
    private static final int[] TABS = {R.string.tab_home, R.string.discover, R.string.must_watch, R.string.my_movies,
            R.string.my_music};
    private static int carouselPos;      // remembered across the activity being recreated
    private int freshPos;
    private float carFrom, carTo, shiftFrom, shiftTo;
    private long carStart, freshStart, sweepStart, rowChanged, lastInput = SystemClock.uptimeMillis();
    private final List<Action> actions = new ArrayList<>();

    private Hero hero = new Hero(), oldHero;
    private long heroChanged;
    private StaticLayout titleLayout, synopsisLayout;

    // Overlays.
    private GlassSheet sheet;
    private RandomSheet picker, lastPicker;   // lastPicker: the results something was played from
    private long lastPickerAt;
    private static final long KEEP_RESULTS_MS = 2 * 60 * 60_000;
    private final RandomSheet.Listener pickerListener = new RandomSheet.Listener() {
        @Override
        public void myMix() {
            listener.onMyMix();
        }

        @Override
        public void pickTitles(int kind, Picks.Entry card, List<String> ticked, int count, java.util.function.Consumer<List<Tmdb.Title>> done) {
            listener.onPickTitles(kind, card, ticked, count, done);
        }

        @Override
        public void cardArt(int kind, Picks.Entry card, String language, java.util.function.Consumer<String> done) {
            listener.onCardArt(kind, card, language, done);
        }

        @Override
        public void redraw() {
            invalidate();
        }

        @Override
        public void switchKind(int kind) {
            randSel = kind;
            picker = new RandomSheet(getContext(), kit, kind, getWidth(), getHeight()).host(HomeView.this);
            picker.t = 1;   // already open: no opening animation between tabs
            picker.attach(this);
            invalidate();
        }

        @Override
        public void askText(String title, String hint, java.util.function.Consumer<String> done) {
            listener.onAskText(title, hint, done);
        }

        @Override
        public void open(Tmdb.Title title) {
            listener.onPlay(title);
        }

        @Override
        public void findMusic(int kind, Picks.Entry card, List<String> ticked, List<String> shown, boolean playlists,
                              java.util.function.Consumer<List<Songs.Pick>> done) {
            listener.onFindMusic(kind, card, ticked, shown, playlists, done);
        }

        @Override
        public void playMusic(Songs.Pick pick) {
            listener.onPlayMusic(pick);
        }

        @Override
        public void peek(Songs.Pick pick, Runnable done) {
            listener.onPeek(pick, done);
        }

        @Override
        public void search(String query, java.util.function.Consumer<List<Object>> done) {
            listener.onSearch(query, done);
        }

        @Override
        public void trailer(Tmdb.Title title) {
            listener.onTrailer(title);
        }

        @Override
        public void searchAgain() {
            askSearch();
        }
    };

    /** The Search screen. */
    void askSearch() {
        listener.onSearchKeys();
    }

    private boolean allAppsOpen;
    private int allSel, allPage;
    private float allT;
    private Tmdb.Title info;
    private List<Services.Service> infoWhere;
    private int infoSel;
    private float infoT;
    private List<Inputs.Source> inputs;
    private int inputSel;
    private boolean online = true;

    // Drawing.
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG), sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG), fadeEdge = new Paint();
    private final RectF tmp = new RectF(), tmp2 = new RectF();
    private final Path clip = new Path();
    private Paint timeP, ampmP, dateP, kickerP, metaP, chipP, pillP, pillDarkP, labelP, counterP, tileTitleP, tileSubP,
            gridP, infoTitleP, infoMetaP, msgP;
    private TextPaint heroTitleP, synopsisP, infoBodyP;
    private Bitmap wifiIcon, gearIcon, slidersIcon, iconGlow, playGlyph, shuffleGlyph;
    // Random Movie, Series, Music and Extras; and My Movies while a pen drive is plugged in.
    private final int tileCount = 4;
    private boolean driveIn;   // a pen drive is plugged in
    private Bitmap[] tiles = new Bitmap[5];
    private final TextSprite timeText = new TextSprite(), ampmText = new TextSprite(), dateText = new TextSprite();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat ampmFmt = new SimpleDateFormat("a", Locale.ENGLISH);
    private SimpleDateFormat dateFmt = new SimpleDateFormat(Lang.datePattern(), Lang.dates());

    /** The date format, following the streaming country (it can change in Customize). */
    private SimpleDateFormat dateFormat() {
        if (!dateFmt.toPattern().equals(Lang.datePattern())) dateFmt = new SimpleDateFormat(Lang.datePattern(), Lang.dates());
        return dateFmt;
    }

    private boolean running, scheduled, longPressed, idlePaused;
    private int vsyncs;
    private long paceLast;
    private int paceFrames, paceLate;
    private boolean paceSlow;
    private long lastFrame;

    public HomeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        art = new ArtBackdrop(context);
        ImageCache.init(context);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        tint.setColor(Color.WHITE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setOnline(boolean on) {
        boolean back = on && !online;
        online = on;
        if (back) refresh(true);   // the connection came back: fill in what failed
        invalidate();              // called every minute: keeps the clock right while idle-paused
    }

    /** Installed apps changed (or Home resumed): rebuild the carousel and load what is missing. */
    void setApps(List<Apps.App> all) {
        apps.clear();
        apps.addAll(all);
        if (getWidth() > 0) {
            buildItems();
            refresh(false);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void setRunning(boolean run) {
        running = run;
        if (run) {
            lastInput = SystemClock.uptimeMillis();
            idlePaused = false;
            schedule();
            findContinue();   // the film to continue may have changed while away
        }
    }

    /** Leaving Home: release the large images; they come back from the disk cache. */
    void releaseImages() {
        art.release();
        freshCards.clear();
        ImageCache.releaseMemory();
        hero.backdrop = null;
    }

    private void schedule() {
        if (!scheduled && running) {
            scheduled = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        scheduled = false;
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastInput > Theme.IDLE_PAUSE_MS) {   // idle: freeze until a key is pressed
            idlePaused = true;
            return;
        }
        // Every frame (60 a second) on a TV that keeps up; every other one in Lite, or once this TV has shown it can't:
        // a steady 30 looks smoother than a 60 that keeps missing.
        boolean mv = moving(now);
        if (!Lite.on && !paceSlow) {
            if (mv && paceLast != 0) {
                paceFrames++;
                if (frameTimeNanos - paceLast > 25_000_000L) paceLate++;
                if (paceFrames >= 90) {
                    if (paceLate * 100 > paceFrames * 15) paceSlow = true;
                    paceFrames = paceLate = 0;
                }
            }
            paceLast = mv ? frameTimeNanos : 0;
        }
        if (!Lite.on && !paceSlow || ++vsyncs % 2 == 0) {
            step(now);
            if (mv) invalidate();   // the neon glass look otherwise redraws on its own, gentler timer (glassHome)
        }
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
    }

    private void step(long now) {
        art.step(now);
        overlayTimers(now);
        updateHero(now);
    }

    private void overlayTimers(long now) {
        float dt = lastFrame == 0 ? 0 : (now - lastFrame) / (float) Theme.FADE_MS;
        lastFrame = now;
        allT = approach(allT, allAppsOpen ? 1 : 0, dt);
        infoT = approach(infoT, info != null ? 1 : 0, dt);
        if (sheet != null) {
            sheet.t = approach(sheet.t, sheet.open ? 1 : 0, dt);
            if (!sheet.open && sheet.t == 0) sheet = null;
        }
        if (picker != null) {
            picker.t = approach(picker.t, picker.open ? 1 : 0, dt);
            if (!picker.open && picker.t == 0) {
                if (picker.launched) {   // kept, so Back from what was played comes back to these results
                    lastPicker = picker;
                    lastPickerAt = SystemClock.uptimeMillis();
                }
                picker = null;
            }
        }
    }

    private static float approach(float v, float target, float by) {
        return v + Math.signum(target - v) * Math.min(Math.abs(target - v), by);
    }

    private boolean noOverlay() {
        return sheet == null && picker == null && !allAppsOpen && info == null && inputs == null && listAll == 0;
    }

    // ---- data ------------------------------------------------------------------------------------

    private List<String> order() {
        String v = prefs.getString("carousel", null);
        if (v != null && !prefs.getBoolean("discover_tab", false)) {   // once: Discover moved from the carousel to a tab
            v = ("," + v + ",").replace(",discover,", ",").replaceAll("^,|,$", "");
            prefs.edit().putString("carousel", v).putBoolean("discover_tab", true).apply();
        }
        if (v == null) return new ArrayList<>(Arrays.asList("IN".equals(Style.country()) ? DEFAULT_ORDER : DEFAULT_ORDER_WEST));
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
        return out;
    }

    private void saveOrder(List<String> ids) {
        prefs.edit().putString("carousel", TextUtils.join(",", ids)).apply();
    }

    private void buildItems() {
        Map<String, Item> old = new HashMap<>();
        for (Item it : items) old.put(it.id, it);
        items.clear();
        for (String id : order()) {
            Item it = old.get(id);
            if (it == null) it = makeItem(id);
            if (it != null) items.add(it);
        }
        // A TV with few of the usual apps: the row filled up with its other apps (until five), before All apps.
        if (!prefs.contains("carousel")) {
            int apps0 = 0;
            for (Item it : items) if (it.kind != ALL_APPS && it.kind != INPUTS && it.kind != DISCOVER) apps0++;
            int at = items.size();
            for (int i = 0; i < items.size(); i++) if (items.get(i).kind == ALL_APPS) at = i;
            for (Apps.App a : apps) {
                if (apps0 >= 5) break;
                boolean have = false;
                for (Item it : items) if (a.pkg.equals(it.id)) have = true;
                if (have || a.pkg.equals(getContext().getPackageName())) continue;
                Item it = makeItem(a.pkg);
                if (it == null) continue;
                items.add(at++, it);
                apps0++;
            }
        }
        if (items.isEmpty()) items.add(makeItem("allapps"));
        for (Item it : items) if (it.card == null) renderCard(it);
        carouselPos = Math.floorMod(carouselPos, items.size());
        carFrom = carTo = carouselPos;
        buildActions();
    }

    @Nullable
    private Item makeItem(String id) {
        Context c = getContext();
        if ("discover".equals(id)) return new Item(id, c.getString(R.string.discover), DISCOVER, null, null);
        if ("allapps".equals(id)) return new Item(id, c.getString(R.string.all_apps), ALL_APPS, null, null);
        if ("inputs".equals(id)) return new Item(id, c.getString(R.string.inputs), INPUTS, null, null);
        Apps.App app = null;
        for (Apps.App a : apps) if (a.pkg.equals(id)) app = a;
        if (app == null) return null;   // not installed (any more)
        if (Services.YOUTUBE_PKG.equals(id)) return new Item(id, app.label, YOUTUBE, app, null);
        if (Services.YT_MUSIC_PKG.equals(id)) return new Item(id, c.getString(R.string.music), MUSIC, app, null);
        Services.Service s = Services.byPackage(id);
        return new Item(id, s != null ? s.name : app.label, s != null ? SERVICE : APP, app, s);
    }

    /** Loads each item's featured title or video, and New this week. force: reload even fresh data. */
    void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!freshLoading && (force || fresh.isEmpty() || now - freshLoadedAt > 3 * 3600_000L)) {
            freshLoading = true;
            sourceAsked = topSource(getContext());
            // No streaming apps yet (a new TV): what's new across the popular ones, so Home isn't empty
            List<Services.Service> mine = Services.installedOrPopular(getContext());
            final boolean empty = fresh.isEmpty();
            work.execute(() -> {
                if (empty) {   // just started: what was saved last time, at once, while the real answer loads
                    Net.savedOnly(true);
                    try {
                        final List<Tmdb.Title> saved = newThisWeek(mine);
                        if (!saved.isEmpty()) main.post(() -> {
                            if (fresh.isEmpty()) showFresh(saved);
                        });
                    } catch (Exception ignored) {
                        // nothing saved yet
                    } finally {
                        Net.savedOnly(false);
                    }
                }
                List<Tmdb.Title> list = null;
                try {
                    list = newThisWeek(mine);
                } catch (Exception ignored) {
                    // offline: keep what is shown
                }
                final List<Tmdb.Title> got = list;
                main.post(() -> {
                    freshLoading = false;
                    if (!sourceAsked.equals(topSource(getContext()))) {   // the choice changed while loading: load the new one
                        refresh(true);
                        return;
                    }
                    freshFailed = got == null && fresh.isEmpty();
                    if (got != null) {
                        freshLoadedAt = System.currentTimeMillis();
                        if (!sameTitles(got, fresh)) showFresh(got);   // unchanged: nothing on screen moves
                    }
                    invalidate();
                });
            });
        }
    }

    private List<Tmdb.Title> newThisWeek(List<Services.Service> mine) throws java.io.IOException {
        List<Tmdb.Title> picked = topPicks(mine);   // Customize → Home → Top picture shows
        if (picked != null) return picked;
        List<Tmdb.Title> list = reallyNew(Catalog.newOn(getContext(), mine, 7));
        if (list.size() < 6) {   // a quiet week: the label says so
            try {
                list = reallyNew(Catalog.newOn(getContext(), mine, 30));
            } catch (java.io.IOException e) {
                if (list.isEmpty()) throw e;
            }
        }
        return list;
    }

    // ---- what the top picture shows (Customize → Home → Top picture shows) ------------------------------------
    static final String[] TOP_SOURCES = {"new", "popular", "top", "gems", "list", "random", "action", "comedy", "thriller", "romance",
            "sci-fi", "animation", "family", "documentary", "anime", "k-drama"};

    private static final java.util.Map<String, String> GENRE_NAMES = new java.util.HashMap<>();

    static {
        String[][] g = {{"action", "Action"}, {"comedy", "Comedy"}, {"thriller", "Thriller"}, {"romance", "Romance"}, {"sci-fi", "Sci-Fi"},
                {"animation", "Animation"}, {"family", "Family"}, {"documentary", "Documentary"}, {"anime", "Anime"}, {"k-drama", "K-Drama"}};
        for (String[] x : g) GENRE_NAMES.put(x[0], x[1]);
    }

    /** Discover's kind for a source (its ids follow the language shown, so genres go by their English name). */
    @Nullable
    private static DiscoverKinds.Kind kindFor(String src) {
        String en = GENRE_NAMES.get(src);
        return DiscoverKinds.find(en != null ? DiscoverKinds.idOf(L10n.t(en)) : src);
    }

    static String topSource(Context c) {
        String s = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("top_source", "new");
        return java.util.Arrays.asList(TOP_SOURCES).contains(s) ? s : "new";
    }

    static String topSourceName(String s) {
        switch (s) {
            case "new": return L10n.t("New this week");
            case "popular": return L10n.t("Popular now");
            case "top": return L10n.t("Top rated");
            case "gems": return L10n.t("Hidden gems");
            case "list": return L10n.t("My Watchlist");
            case "random": return L10n.t("Something random");
            default: {
                DiscoverKinds.Kind k = kindFor(s);
                return k != null ? k.name : s;
            }
        }
    }

    /** The chosen source's titles on your apps; null for New this week (or when nothing came). */
    @Nullable
    private List<Tmdb.Title> topPicks(List<Services.Service> mine) {
        String src = topSource(getContext());
        if (src.equals("new")) return null;
        if (src.equals("list")) {
            List<Tmdb.Title> l = fromWatchlist();
            return l.isEmpty() ? null : l;
        }
        String id = src.equals("popular") ? DiscoverKinds.TRENDING : src.equals("top") ? DiscoverKinds.TOP_RATED
                : src.equals("gems") ? DiscoverKinds.HIDDEN_GEMS : src;
        if (src.equals("random")) id = TOP_SOURCES[6 + new java.util.Random().nextInt(TOP_SOURCES.length - 6)];
        DiscoverKinds.Kind kd = kindFor(id);
        Tmdb.Category cat = kd != null ? DiscoverKinds.category(kd) : null;
        if (cat == null) return null;
        List<Tmdb.Title> out = fromCategory(mine, cat);
        if (src.equals("random")) java.util.Collections.shuffle(out);
        return out.isEmpty() ? null : out;
    }

    /** A Discover kind on your apps: a few of each app's films and series, in turn. */
    private List<Tmdb.Title> fromCategory(List<Services.Service> mine, Tmdb.Category cat) {
        List<Tmdb.Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Services.Service s : mine) {
            for (String type : new String[]{"movie", "tv"}) {
                try {
                    Tmdb.Page p = Catalog.discover(getContext(), cat, s, type, 1, "");
                    if (p == null) continue;
                    int n = 0;
                    for (Tmdb.Title t : p.titles) {
                        if (n >= 3 || t.backdropPath == null || !seen.add(t.key())) continue;
                        if (t.service == null) t.service = s;
                        out.add(t);
                        n++;
                    }
                } catch (Exception ex) {
                }
            }
        }
        return out;
    }

    /** My Watchlist (what you saved with + My List), latest first. */
    private List<Tmdb.Title> fromWatchlist() {
        List<Library.Entry> want = new ArrayList<>();
        for (Library.Entry e : Library.all(getContext())) if (e.status == Library.WANT && e.tmdbId > 0) want.add(e);
        java.util.Collections.sort(want, (a, b) -> Long.compare(b.time, a.time));
        List<Tmdb.Title> out = new ArrayList<>();
        for (Library.Entry e : want) {
            if (out.size() >= 12) break;
            try {
                Tmdb.Title t = Tmdb.byId(getContext(), "tv".equals(e.type) ? "tv" : "movie", e.tmdbId);
                if (t != null && t.backdropPath != null) out.add(t);
            } catch (Exception ignored) {
                // that one: left out
            }
        }
        return out;
    }

    private static boolean sameTitles(List<Tmdb.Title> a, List<Tmdb.Title> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).key().equals(b.get(i).key())) return false;
        return true;
    }

    private void showFresh(List<Tmdb.Title> got) {
        fresh.clear();
        fresh.addAll(got);
        TvTownDream.keepPictures(getContext(), got);
        freshCards.clear();
        freshPos = Math.max(0, Math.min(freshPos, fresh.size() - 1));
        shiftFrom = shiftTo = freshShift(freshPos);
        invalidate();
    }

    /** Details (runtime, genres, trailer) for a New this week title, fetched when it is focused. */
    private void ensureDetails(Tmdb.Title t) {
        if (t == null || t.detailed) return;
        work.execute(() -> {
            try {
                Catalog.details(getContext(), t);
                main.post(this::invalidate);
            } catch (Exception ignored) {
                // shown without them
            }
        });
    }

    // ---- hero ------------------------------------------------------------------------------------

    @Nullable
    private Item front() {
        return items.isEmpty() ? null : items.get(Math.floorMod(Math.round(carTo), items.size()));
    }

    @Nullable
    private Tmdb.Title freshCentre() {
        return fresh.isEmpty() ? null : fresh.get(Math.max(0, Math.min(freshPos, fresh.size() - 1)));
    }

    /** Recomputes what the hero shows; cross-fades the text and the backdrop when it changes. */
    private void updateHero(long now) {
        Hero h = new Hero();
        Context c = getContext();
        if (row == Row.NEW && freshCentre() != null) {
            Tmdb.Title t = freshCentre();
            ensureDetails(t);
            fill(h, t, c.getString(R.string.kicker_new_on, t.service != null ? t.service.name : "your apps").toUpperCase(Locale.ROOT),
                    t.service != null ? badgeFor(t.service.pkg) : null);
        } else {
            Item it = front();
            if (it != null) {
                h.badge = it.disc;
                if (it.feature != null) {
                    String kicker = it.kind == DISCOVER ? c.getString(R.string.kicker_trending)
                            : c.getString(R.string.kicker_popular_on, it.name);
                    fill(h, it.feature, kicker.toUpperCase(Locale.ROOT), it.disc);
                } else {
                    h.kicker = it.kind == APP ? c.getString(R.string.kicker_open).toUpperCase(Locale.ROOT) : "";
                    h.title = it.name;
                    h.synopsis = it.kind == ALL_APPS ? c.getString(R.string.hero_all_apps, apps.size())
                            : it.kind == INPUTS ? c.getString(R.string.hero_inputs)
                            : it.kind == YOUTUBE ? c.getString(R.string.hero_youtube)
                            : it.kind == MUSIC ? c.getString(R.string.hero_music)
                            : it.loading ? c.getString(R.string.loading)
                            : !online ? c.getString(R.string.hero_offline) : "";
                }
            }
        }
        if (!same(h, hero)) {
            oldHero = hero;
            hero = h;
            heroChanged = now;
            layoutHeroText();
               // the neon glass look has its own wallpaper
        }
    }

    private void fill(Hero h, Tmdb.Title t, String kicker, @Nullable Bitmap badge) {
        h.kicker = kicker;
        h.badge = badge;
        h.title = t.title;
        h.meta = t.meta();
        h.synopsis = t.overview;
        h.backdrop = t.backdrop();
        h.trailer = t.trailer;
        h.title_ = t;
    }

    private static boolean same(Hero a, Hero b) {
        return TextUtils.equals(a.title, b.title) && TextUtils.equals(a.meta, b.meta) && TextUtils.equals(a.kicker, b.kicker)
                && TextUtils.equals(a.backdrop, b.backdrop) && TextUtils.equals(a.trailer, b.trailer)
                && TextUtils.equals(a.synopsis, b.synopsis);
    }

    @Nullable
    private Bitmap badgeFor(String pkg) {
        for (Item it : items) if (pkg.equals(it.id)) return it.disc;
        for (Apps.App a : apps) if (a.pkg.equals(pkg)) return a.icon;
        return null;
    }

    private void layoutHeroText() {
        if (heroTitleP == null) return;
        int w = Math.round(820 * k);
        String t = hero.title == null ? "" : hero.title;
        titleLayout = StaticLayout.Builder.obtain(t, 0, t.length(), heroTitleP, w).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.95f).build();
        String s = hero.synopsis == null ? "" : hero.synopsis;
        synopsisLayout = StaticLayout.Builder.obtain(s, 0, s.length(), synopsisP, Math.round(700 * k))
                .setMaxLines(titleLayout.getLineCount() > 1 ? 1 : 2)   // a long title: one line of summary
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4 * k, 1f).build();
    }

    // ---- layout ----------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        art.setSize(w, h);
        kit = new GlassKit(getContext(), art, k);
        marqueeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        marqueeP.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        marqueeP.setTextSize(96 * k);
        marqueeP.setLetterSpacing(0.14f);
        marqueeP.setColor(0xFFFFE6A0);
        marqueeP.setShadowLayer(18 * k, 0, 0, 0xCCFFB040);
        marqueeSmallP = new Paint(Paint.ANTI_ALIAS_FLAG);
        marqueeSmallP.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        marqueeSmallP.setTextSize(30 * k);
        marqueeSmallP.setColor(0xFFF3E6C8);
        timeP = kit.font(53, 400);
        ampmP = kit.font(20, 500);
        dateP = kit.font(18, 500);
        dateP.setLetterSpacing(0.2f);
        kickerP = kit.font(19, 600);
        kickerP.setLetterSpacing(0.18f);
        heroTitleP = new TextPaint(kit.font(60, 700));
        metaP = kit.font(22, 500);
        synopsisP = new TextPaint(kit.font(25, 400));
        chipP = kit.font(17, 500);
        pillP = kit.font(22, 600);
        pillDarkP = kit.font(22, 700);
        pillDarkP.setColor(Theme.INK);
        pillDarkP.clearShadowLayer();
        labelP = kit.font(17, 600);
        labelP.setLetterSpacing(0.22f);
        counterP = kit.font(17, 600);
        tileTitleP = kit.font(26, 700);
        tileSubP = kit.font(18, 400);
        gridP = kit.font(18, 500);
        infoTitleP = kit.font(44, 700);
        infoMetaP = kit.font(21, 500);
        infoBodyP = new TextPaint(kit.font(25, 400));
        msgP = kit.font(22, 500);
        float s = 31 * k, sw = Math.max(1.3f, 2f * k);
        wifiIcon = WheelArt.symbol(WheelArt.Glyph.WIFI, s, sw, 3 * k);
        gearIcon = WheelArt.symbol(WheelArt.Glyph.GEAR, s, sw, 3 * k);
        slidersIcon = WheelArt.symbol(WheelArt.Glyph.SLIDERS, s, sw, 3 * k);
        playGlyph = WheelArt.symbol(WheelArt.Glyph.PLAY, 22 * k, sw, 0);
        shuffleGlyph = WheelArt.symbol(WheelArt.Glyph.SHUFFLE, 20 * k, Math.max(1.2f, 1.6f * k), 0);
        iconGlow = WheelArt.circleShadow(30 * k, 10 * k);
        fadeEdge.setShader(null);
        noirPaints();   // after the Classic fonts: the title and summary in Fraunces
        renderTiles();
        layoutHeroText();
        buildItems();
        refresh(false);
        if (!prefs.getBoolean("welcomed", false) && sheet == null) post(this::openWelcome);
    }

    // Geometry, in design pixels.
    private static final float CAR_X = 1440, CAR_Y = 312, CARD_W = 240, CARD_H = 285;
    private static final float ACT_Y = 526;
    private static final float NEW_TOP = 610, NEW_H = 312, NEW_W = 360, NEW_HGT = 203, NEW_GAP = 24;
    private static final float RAND_TOP = 944, RAND_H = 100;

    // ---- pre-rendered art ------------------------------------------------------------------------

    /** A clear glass card: app icon in a circle, its name and OPEN. A reflection too, in Full. */
    private void renderCard(Item it) {
        int w = Math.round(CARD_W * k), h = Math.round(CARD_H * k), d = Math.round(112 * k);
        Bitmap disc;
        switch (it.kind) {
            case DISCOVER:
                disc = WheelArt.glyphDisc(WheelArt.Glyph.TELESCOPE, d, 0xFFFFB347, 0xFFF06A2A);
                break;
            case ALL_APPS:
                disc = WheelArt.glyphDisc(WheelArt.Glyph.GRID, d, 0xFF8A919E, 0xFF5B616D);
                break;
            case INPUTS:
                disc = WheelArt.glyphDisc(WheelArt.Glyph.TV, d, 0xFF8A919E, 0xFF5B616D);
                break;
            default: {
                Drawable icon = Apps.hiResIcon(getContext(), it.app.component);
                disc = WheelArt.iconDisc(icon, d);
                if (WheelArt.flat(disc) && it.app.icon != null) disc = WheelArt.circleCrop(it.app.icon, d);
            }
        }
        it.disc = disc;
        it.color = WheelArt.mainColor(disc);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = Theme.RADIUS_CARD * k;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x38FFFFFF, 0x1AFFFFFF, 0x24FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, r, r, p);
        p.setShader(null);
        p.setColor(0x660B0B0D);
        c.drawRoundRect(0, 0, w, h, r, r, p);
        p.setColor(0x24FFFFFF);
        c.drawRoundRect(0, 0, w, h, r, r, p);
        Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
        e.setStyle(Paint.Style.STROKE);
        e.setStrokeWidth(1);
        e.setColor(Color.argb(Math.round(255 * Theme.EDGE), 255, 255, 255));
        c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, e);
        e.setStrokeWidth(Math.max(1f, 2 * k));
        e.setShader(new LinearGradient(0, 0, w * 0.6f, h * 0.5f, Color.argb(Math.round(255 * Theme.HIGHLIGHT), 255, 255, 255),
                0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, r, r, e);
        // Icon in a circle with a soft ring.
        float cx = w / 2f, cy = 98 * k;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(cx, cy, d * 0.85f, new int[]{(it.color & 0xFFFFFF) | 0x66000000, it.color & 0xFFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, d * 0.85f, glow);
        c.drawBitmap(disc, cx - d / 2f, cy - d / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, 2 * k));
        ring.setColor(0x8CFFFFFF);
        c.drawCircle(cx, cy, d / 2f, ring);
        Paint name = kit.font(26, 600);
        name.setTextAlign(Paint.Align.CENTER);
        c.drawText(TextUtils.ellipsize(it.name, new TextPaint(name), w - 28 * k, TextUtils.TruncateAt.END).toString(), cx, 206 * k, name);
        Paint open = kit.font(15, 600);
        open.setLetterSpacing(0.35f);
        open.setTextAlign(Paint.Align.CENTER);
        open.setAlpha(190);
        c.drawText(getContext().getString(R.string.open_caps), cx, 244 * k, open);
        it.card = b;
        it.reflection = reflect(b);
    }

    /** The card upside down, fading out: drawn under the front card. */
    private static Bitmap reflect(Bitmap src) {
        int w = src.getWidth(), h = Math.round(src.getHeight() * 0.45f);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Matrix m = new Matrix();
        m.setScale(1, -1);
        m.postTranslate(0, src.getHeight());
        c.drawBitmap(src, m, null);
        Paint mask = new Paint();
        mask.setShader(new LinearGradient(0, 0, 0, h, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        mask.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, mask);
        return b;
    }

    /** The Random tiles' content: gradient icon circle, title, subtitle and a shuffle circle. */
    private void renderTiles() {
        String[][] text = {{getContext().getString(R.string.random_movie), getContext().getString(R.string.random_movie_sub)},
                {getContext().getString(R.string.random_series), getContext().getString(R.string.random_series_sub)},
                {getContext().getString(R.string.random_song), getContext().getString(R.string.random_song_sub)},
                {getContext().getString(R.string.extras), getContext().getString(R.string.extras_sub)},
                {getContext().getString(R.string.my_movies), getContext().getString(R.string.my_movies_sub)}};
        int[][] colours = {{Theme.AMBER_A, Theme.AMBER_B}, {Theme.VIOLET_A, Theme.VIOLET_B}, {Theme.MINT_A, Theme.MINT_B},
                {Theme.ROSE_A, Theme.ROSE_B}, {Theme.AMBER_A, Theme.ROSE_B}};
        WheelArt.Glyph[] glyphs = {WheelArt.Glyph.FILM, WheelArt.Glyph.TV, WheelArt.Glyph.NOTE, WheelArt.Glyph.PLAY,
                WheelArt.Glyph.FILM};
        float tw = tileWidth();
        for (int i = 0; i < tileCount; i++) {
            int w = Math.round(tw), h = Math.round(RAND_H * k);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            int d = Math.round(64 * k);
            Bitmap disc = WheelArt.glyphDisc(glyphs[i], d, colours[i][0], colours[i][1]);
            c.drawBitmap(disc, 24 * k, (h - d) / 2f, null);
            boolean narrow = tw < 380 * k;   // five tiles: no shuffle circle, so the names fit
            float textW = w - (narrow ? 124 : 190) * k;
            c.drawText(TextUtils.ellipsize(text[i][0], new TextPaint(tileTitleP), textW, TextUtils.TruncateAt.END).toString(),
                    108 * k, h / 2f - 2 * k, tileTitleP);
            tileSubP.setAlpha(200);
            c.drawText(TextUtils.ellipsize(text[i][1], new TextPaint(tileSubP), textW, TextUtils.TruncateAt.END).toString(),
                    108 * k, h / 2f + 26 * k, tileSubP);
            tiles[i] = b;
            if (narrow) continue;
            float sx = w - 46 * k, sy = h / 2f, sr = 20 * k;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x33FFFFFF);
            c.drawCircle(sx, sy, sr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x66FFFFFF);
            c.drawCircle(sx, sy, sr, p);
            c.drawBitmap(shuffleGlyph, sx - shuffleGlyph.getWidth() / 2f, sy - shuffleGlyph.getHeight() / 2f, null);
        }
    }

    /** A pen drive was plugged in or taken out: the My Movies tab shows a dot while one is in. */
    void setDrive(boolean mounted) {
        if (mounted == driveIn) return;
        driveIn = mounted;
        findContinue();
        invalidate();
    }

    private float tileWidth() {
        return (1920 - 2 * Theme.MARGIN - (tileCount - 1) * 24) * k / tileCount;
    }

    /** A New this week card: backdrop, rounded corners, the NEW chip, the app badge and the title along the bottom. */
    @Nullable
    private Bitmap freshCard(Tmdb.Title t) {
        Bitmap c0 = freshCards.get(t.key());
        if (c0 != null) return c0;
        Bitmap img = ImageCache.get(t.backdropCard(), this::invalidate);
        if (img == null) return null;
        int w = Math.round(NEW_W * k), h = Math.round(NEW_HGT * k);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Matrix m = new Matrix();
        float s = Math.max((float) w / img.getWidth(), (float) h / img.getHeight());
        m.setScale(s, s);
        m.postTranslate((w - img.getWidth() * s) / 2, (h - img.getHeight() * s) / 2);
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        float r = 18 * k;
        c.drawRoundRect(0, 0, w, h, r, r, p);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new LinearGradient(0, h * 0.4f, 0, h, 0x000B0B0D, 0xE00B0B0D, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, r, r, shade);
        Paint name = kit.font(20, 600);
        c.drawText(TextUtils.ellipsize(t.title, new TextPaint(name), w - 86 * k, TextUtils.TruncateAt.END).toString(),
                16 * k, h - 18 * k, name);
        // NEW chip, top left.
        String chip = getContext().getString(R.string.new_chip, age(t.added != null ? t.added : t.date)).toUpperCase(Locale.ROOT);
        Paint cp = kit.font(14, 600);
        cp.setLetterSpacing(0.08f);
        float cw = cp.measureText(chip) + 22 * k, ch = 28 * k;
        RectF cr = new RectF(12 * k, 12 * k, 12 * k + cw, 12 * k + ch);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(0xB30B0B0D);
        c.drawRoundRect(cr, ch / 2, ch / 2, f);
        f.setStyle(Paint.Style.STROKE);
        f.setStrokeWidth(1);
        f.setColor(0x66FFFFFF);
        c.drawRoundRect(cr, ch / 2, ch / 2, f);
        c.drawText(chip, cr.left + 11 * k, cr.centerY() + 5 * k, cp);
        // App badge, top right.
        Bitmap badge = t.service != null ? badgeFor(t.service.pkg) : null;
        if (badge != null) {
            float bs = 30 * k;
            c.drawBitmap(badge, null, new RectF(w - 12 * k - bs, 12 * k, w - 12 * k, 12 * k + bs), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        freshCards.put(t.key(), b);
        return b;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        long now = SystemClock.uptimeMillis();
        if (kit != null && nk != null) {
            drawGlass(c, now);
            drawOverlays(c, now);
            return;
        }
        art.draw(c, now);
        if (kit == null) return;
        drawTopBar(c);
        drawHero(c, now);
        drawCarousel(c, now);
        drawActions(c);
        drawFresh(c, now);
        drawTiles(c, now);
        
        drawOverlays(c, now);
    }

    /** Over Home: All apps, More info, Inputs, a sheet, a Random pop-up */
    private void drawOverlays(Canvas c, long now) {
        float overlay = Math.max(Math.max(allT, infoT), Math.max(sheet != null ? sheet.t : 0, picker != null ? picker.t : 0));
        
        if (inputs != null) drawInputs(c);
        if (allT > 0) drawAllApps(c);
        if (infoT > 0 && info != null) drawInfo(c);
        if (sheet != null && sheet.t > 0) {
            sheet.draw(c, this, now);
        }
        if (picker != null && picker.t > 0) picker.draw(c);
        
    }

    // ---- the Noir & Gold look (Customize > Home look; the default) ---------------------------------------
    //
    // The featured title full-bleed, fading into warm black; its name in a large italic serif with a gold line
    // above; the buttons under it (solid gold, bronze outlines); the apps as a row of dark tiles with their icons;
    // New this week as a row of pictures with italic titles and spaced captions; Random as four coloured cards down
    // the right ("Can't decide?"). What is focused sits in a gold double frame.

    private NoirKit nk;
    private Paint nClock, nClockSmall, nTab, nCaps, nEyebrow, nBtn, nTileName, nCardTitle, nCaption, nRandTitle, nRandSub, nCount,
            nSearch;
    private final Paint nFill = new Paint(Paint.ANTI_ALIAS_FLAG), nImg = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix nMatrix = new Matrix();
    private static final float N_X = 96, N_HERO = 158, N_APPS = 576, N_TILE = 168, N_TILE_FOCUS = 188, N_GAP = 20, N_NEW = 796,
            N_CARD_W = 326, N_CARD_H = 140, N_CARD_GAP = 22, N_RAND_W = 470, N_RAND_TOP = 210, N_RAND_H = 98, N_RAND_GAP = 14;
    private static final int[] N_RAND_COLOURS = {0xFFF0A04B, 0xFF9D86F5, 0xFF3CC7A2, 0xFFF06A97, 0xFFE8C877};

    private void noirPaints() {
        nk = new NoirKit(getContext(), k);
        nClock = nk.serif(58, 500, false, NoirKit.IVORY);
        nClockSmall = nk.sans(14, 600, 0.22f, 0xFFB8AC98);
        nTab = nk.sans(17, 500, 0.18f, NoirKit.MUTED);
        nCaps = nk.sans(16, 600, 0.19f, 0xFFC9BCA6);
        nEyebrow = nk.sans(15, 600, 0.33f, NoirKit.GOLD);
        nBtn = nk.sans(17, 600, 0.17f, NoirKit.IVORY);
        nTileName = nk.sans(13, 600, 0.23f, NoirKit.MUTED);
        nCardTitle = nk.serif(22, 600, true, NoirKit.IVORY);
        nCaption = nk.sans(12, 600, 0.25f, NoirKit.CAPTION);
        nRandTitle = nk.sans(20, 600, 0.15f, 0xFFFBF6EC);
        nRandSub = nk.serif(18, 500, true, 0xFFC9BCA6);
        nCount = nk.serif(18, 500, true, 0xFFA99D8A);
        nSearch = nk.sans(21, 400, 0, NoirKit.MUTED);
        heroTitleP = new TextPaint(nk.serif(92, 600, true, 0xFFFBF6EC));
        synopsisP = new TextPaint(nk.serif(23, 500, false, NoirKit.IVORY_SOFT));
    }

    private float noirButtonsTop, heroY = N_HERO;

    // ---- the Bento board (Noir & Gold's layout) ----------------------------------------------------------
    //
    // Tiles on warm black: the featured title top left (its picture inside the tile, italic title, buttons), New
    // this week top right (the current one large, the next three small), and along the bottom Random as four
    // coloured squares, the apps as tiles filled with their own TV banners (six at a time), and Continue watching
    // (the film last watched in My Movies). Arrows move between tiles the way they are laid out.


    private void coverImage(Canvas c, @Nullable Bitmap img, RectF r, float rad, int alpha) {
        coverImage(c, img, r, rad, alpha, 0.5f);
    }

    /** As coverImage, with where the crop sits top to bottom (0 keeps the top, 0.5 the middle). */
    private void coverImage(Canvas c, @Nullable Bitmap img, RectF r, float rad, int alpha, float keep) {
        if (img == null) {
            nk.panel(c, r, rad, NoirKit.PANEL);
            return;
        }
        float sc = Math.max(r.width() / img.getWidth(), r.height() / img.getHeight());
        nMatrix.setScale(sc, sc);
        nMatrix.postTranslate(r.centerX() - img.getWidth() * sc / 2, r.top - (img.getHeight() * sc - r.height()) * keep);
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(nMatrix);
        nImg.setShader(sh);
        nImg.setAlpha(alpha);
        c.drawRoundRect(r, rad, rad, nImg);
        nImg.setShader(null);
        nImg.setAlpha(255);
    }










    // App banners (the picture apps give TV home screens: their logo on their colour), made once per app.
    private final java.util.Map<String, Bitmap> banners = new java.util.HashMap<>();


    private void setFront(int i) {
        if (items.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        carFrom = carTo = Math.max(0, Math.min(items.size() - 1, i));
        carStart = now;
        carouselPos = Math.round(carTo);
        buildActions();
    }

    // ---- One picture (Noir & Gold's layout) ---------------------------------------------------------------
    //
    // Two pages over one full-screen picture. Page 1: the featured title (what's popular on the app you're on), its
    // buttons, and Your apps in a row with a golden Can't decide? tile first. Page 2 (Down from the apps): Continue
    // watching and New this week in one row, a thin gold line between; the picture and the title follow the card.


    // Continue watching: the films part-watched in My Movies, latest first (found in the background).
    private static final class Cont {
        MyMovies.Video video;   // a film on the drive, or…
        String pkg, app, intentUri;   // …something an app says you're in the middle of (Watch Next)
        String title, image, line, overview = "", meta;
        String poster;   // a tall cover, when known (My Watchlist); else found by name for the shelf
        long from, at;
        float frac = -1;
    }

    private List<Cont> conts = new ArrayList<>();
    private List<Cont> appSaved = new ArrayList<>();  // what the apps keep in the TV's watchlist (My Watchlist)
    private volatile int filmCount = -1;   // videos on the drive (My Movies tile)
    private boolean contLooking, contAgain;

    /** Continue watching as last found, kept so Home shows it at once next time (filled in properly by the scan). */
    private static void saveConts(Context ctx, List<Cont> l) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Cont ct : l.subList(0, Math.min(15, l.size()))) {
                a.put(new org.json.JSONObject().put("title", ct.title).put("image", ct.image == null ? "" : ct.image).put("line", ct.line)
                        .put("frac", ct.frac).put("pkg", ct.pkg == null ? "" : ct.pkg).put("intent", ct.intentUri == null ? "" : ct.intentUri));
            }
        } catch (org.json.JSONException ignored) {
            return;
        }
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("conts", a.toString()).apply();
    }

    private void loadConts() {
        if (!conts.isEmpty()) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("conts", "[]"));
            List<Cont> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Cont ct = new Cont();
                ct.title = o.optString("title");
                ct.image = o.optString("image").isEmpty() ? null : o.optString("image");
                ct.line = o.optString("line");
                ct.meta = ct.line;
                ct.frac = (float) o.optDouble("frac", -1);
                ct.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
                ct.intentUri = o.optString("intent").isEmpty() ? null : o.optString("intent");
                l.add(ct);
            }
            conts = withoutHidden(l);
        } catch (org.json.JSONException ignored) {
            // nothing kept
        }
    }

    /** Looks for the films to continue (off the main thread; the drive's list takes a fraction of a second). */
    void findContinue() {
        loadConts();   // last time's list at once
        if (contLooking) {   // e.g. the drive mounted during the scan at start-up: look again when it ends
            contAgain = true;
            return;
        }
        contLooking = true;
        Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            List<MyMovies.Video> going = new ArrayList<>();
            List<MyMovies.Video> all = MyMovies.driveMounted(ctx) ? MyMovies.scan(ctx) : new ArrayList<>();
            filmCount = all.size();
            List<TvTools.Watching> next = TvTools.watchNext(ctx, 8);
            List<Cont> saved = new ArrayList<>();
            for (TvTools.Watching w : TvTools.appWatchlist(ctx, 12)) {
                Cont ct = new Cont();
                ct.pkg = w.pkg;
                ct.app = w.app;
                ct.title = w.title;
                ct.image = w.image;
                ct.intentUri = w.intentUri;
                ct.line = w.app;
                ct.at = w.at;
                saved.add(ct);
            }
            for (TvTools.Watching w : next) {   // a drive film left near the end in VLC (closed in the credits): watched
                if (!MyMoviesActivity.VLC.equals(w.pkg)) continue;
                MyMovies.Video v = videoTitled(ctx, all, w.title);
                long dur = w.dur > 0 ? w.dur : v != null ? v.durationMs : 0;
                if (v != null && !MyMovies.seen(ctx, v) && MyMovies.nearEnd(w.pos, dur)) MyMovies.setSeen(ctx, v, true);
                else if (v != null && w.pos > 0 && (MyMovies.position(ctx, v) <= 0 || w.at > MyMovies.lastPlayed(ctx, v))) MyMovies.played(ctx, v, w.pos, dur);
            }
            for (MyMovies.Video v : all) {
                long pos = MyMovies.position(ctx, v);
                if (pos > 0 && MyMovies.nearEnd(pos, v.durationMs)) {   // kept from before the rule took in the credits
                    MyMovies.setSeen(ctx, v, true);
                    continue;
                }
                if (!MyMovies.seen(ctx, v) && (pos > 0 || MyMovies.began(ctx, v))) going.add(v);
            }
            going.sort((a, b) -> Long.compare(MyMovies.lastPlayed(ctx, b), MyMovies.lastPlayed(ctx, a)));
            List<Cont> found = new ArrayList<>();
            for (MyMovies.Video v : going.subList(0, Math.min(10, going.size()))) {
                Cont ct = new Cont();
                MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
                MyMovies.Match m = MyMovies.saved(ctx, MyMovies.matchKey(v, p));
                ct.video = v;
                ct.title = m != null ? m.title : p.title;
                ct.image = m != null ? (m.backdrop != null ? m.backdrop : m.poster) : null;
                ct.overview = m != null ? m.overview : "";
                long pos = MyMovies.position(ctx, v);
                ct.from = pos > 0 ? pos : -1;
                long left = v.durationMs > 0 && pos > 0 ? v.durationMs - pos : 0;
                String leftText = left > 60_000 ? (left / 3_600_000 > 0 ? left / 3_600_000 + " h " : "") + (left / 60_000) % 60 + L10n.t(" min left") : "";
                ct.line = leftText.isEmpty() ? L10n.t("My Movies") : leftText;
                ct.meta = (m != null && m.year > 0 ? m.year + " · " : "") + (m != null && !m.genres.isEmpty() ? m.genres + " · " : "") + ct.line;
                if (v.durationMs > 0 && pos > 0) ct.frac = Math.min(1, pos / (float) v.durationMs);
                ct.at = MyMovies.lastPlayed(ctx, v);
                found.add(ct);
            }
            for (TvTools.Watching w : next) {   // Netflix, Prime Video… (not YouTube: films and series only)
                if (w.pkg != null && HomeKeyService.youTube(w.pkg)) continue;
                if (w.frac >= 0 && MyMovies.nearEnd(w.pos, w.dur)) continue;   // finished but for the credits
                if (MyMoviesActivity.VLC.equals(w.pkg)) {   // a film from the drive, played in VLC: as My Movies has it
                    MyMovies.Video v = videoTitled(ctx, all, w.title);
                    if (v != null && MyMovies.seen(ctx, v)) continue;   // watched (to the credits)
                    boolean have = false;
                    for (Cont x : found) if (x.video != null && v != null && x.video.key().equals(v.key())) have = true;
                    if (have) continue;
                    if (v != null) {
                        Cont ct = new Cont();
                        MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
                        MyMovies.Match m = MyMovies.saved(ctx, MyMovies.matchKey(v, p));
                        ct.video = v;
                        ct.title = m != null ? m.title : p.title;
                        ct.image = m != null ? (m.backdrop != null ? m.backdrop : m.poster) : w.image;
                        ct.overview = m != null ? m.overview : "";
                        ct.from = -1;   // VLC remembers where
                        ct.line = L10n.t("My Movies");
                        ct.meta = ct.line;
                        ct.frac = w.frac;
                        ct.at = w.at;
                        found.add(ct);
                        continue;
                    }
                }
                Cont ct = new Cont();
                ct.pkg = w.pkg;
                ct.app = w.app;
                ct.intentUri = w.intentUri;
                ct.title = w.title;
                ct.image = w.image;
                ct.line = w.line;
                ct.meta = w.line;
                ct.frac = w.frac;
                ct.at = w.at;
                found.add(ct);
            }
            for (Watched.Item w : Features.on(ctx, Features.APPS) ? Watched.list(ctx) : new ArrayList<Watched.Item>()) {   // what TV Town saw playing in other apps
                boolean have = false;
                for (Cont x : found) have |= x.title != null && x.title.equalsIgnoreCase(w.title);
                if (have) continue;
                Cont ct = new Cont();
                ct.pkg = w.pkg;
                ct.app = appLabel(ctx, w.pkg);
                ct.title = w.title;
                Watched.loadPicture(ctx, w.image);
                ct.image = w.image;
                long left = w.dur > 0 ? w.dur - w.pos : 0;
                ct.line = ct.app + (left > 60_000 ? "  \u00B7  " + (left / 3_600_000 > 0 ? left / 3_600_000 + " h " : "") + (left / 60_000) % 60 + L10n.t(" min left") : "");
                ct.meta = w.sub.isEmpty() ? ct.line : w.sub + " \u00B7 " + ct.line;
                ct.frac = w.dur > 0 && w.pos > 0 ? Math.min(1, w.pos / (float) w.dur) : -1;
                ct.at = w.at;
                if (!HomeKeyService.youTube(w.pkg)) found.add(ct);   // films and series only: YouTube videos stay out
            }
            found.sort((a, b) -> Long.compare(b.at, a.at));
            while (found.size() > 15) found.remove(found.size() - 1);
            saveConts(ctx, found);
            post(() -> {
                contLooking = false;
                conts = withoutHidden(found);
                appSaved = saved;
                contSel = Math.min(contSel, Math.max(0, conts.size() - 1));
                invalidate();
                if (contAgain) {
                    contAgain = false;
                    findContinue();
                }
            });
        }, "continue").start();
    }

    private static String appLabel(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The drive's video with this title (VLC names films by their file), or null. */
    @Nullable
    private static MyMovies.Video videoTitled(Context ctx, List<MyMovies.Video> all, String title) {
        String want = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (want.isEmpty()) return null;
        for (MyMovies.Video v : all) {
            String name = v.name.toLowerCase(Locale.ROOT).replaceAll("\\.[a-z0-9]{2,4}$", "").replaceAll("[^a-z0-9]", "");
            String parsed = MyMovies.parse(v.name, v.folder, "").title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (name.equals(want) || parsed.equals(want) || name.startsWith(want)) return v;
        }
        return null;
    }

    /** OK on a film to continue: it carries on in VLC from where it stopped. */
    /** What you took off Continue watching (hold OK): kept off until it is played again somewhere new. */
    private java.util.Set<String> contHidden() {
        return prefs.getStringSet("cont_hidden", new java.util.HashSet<>());
    }

    private static String contKey(Cont ct) {
        return (ct.pkg == null ? "drive" : ct.pkg) + "|" + (ct.title == null ? "" : ct.title.trim().toLowerCase(Locale.ROOT));
    }

    private List<Cont> withoutHidden(List<Cont> in) {
        java.util.Set<String> h = contHidden();
        if (h.isEmpty()) return in;
        List<Cont> out = new ArrayList<>();
        for (Cont ct : in) if (!h.contains(contKey(ct))) out.add(ct);
        return out;
    }

    /** OK on a Continue title: its details, and what can be done with it, as labelled buttons. */
    private Cont contOptionsFor;

    private void openContDetails(Cont ct) {
        contOptionsFor = ct;
        GlassSheet s = new GlassSheet(kit, ct.title == null ? "" : ct.title, WheelArt.Glyph.PLAY, "contopts", "");
        s.solo = new ContPane(ct);
        s.popupW = 1440;
        s.soloH = 640;
        s.layout(getWidth(), getHeight(), 1440, 1);
        sheet = s;
        invalidate();
    }

    /** The details pop-up: the cover on the left; the name, app, progress and story; then the buttons, three a row. */
    private final class ContPane implements GlassSheet.Pane {
        final Cont ct;
        final List<String[]> buttons = new ArrayList<>();   // tag, label
        final List<WheelArt.Glyph> glyphs = new ArrayList<>();
        int sel;

        ContPane(Cont ct) {
            this.ct = ct;
            add("resume", ct.frac > 0.01f || ct.from > 0 ? L10n.t("Resume") : L10n.t("Play"), WheelArt.Glyph.PLAY);
            if (ct.video != null && (ct.from > 0 || ct.frac > 0.01f)) add("start", L10n.t("Start over"), WheelArt.Glyph.REDO);
            if (ct.title != null && !ct.title.isEmpty()) add("info", L10n.t("More info"), WheelArt.Glyph.INFO);
            add("watched", L10n.t("Mark watched"), WheelArt.Glyph.CHECK);
            add("hide", L10n.t("Remove from row"), WheelArt.Glyph.EYE);
            if (ct.title != null && ct.video == null) add("fix", L10n.t("Fix picture"), WheelArt.Glyph.PENCIL);
        }

        private void add(String tag, String label, WheelArt.Glyph g) {
            buttons.add(new String[]{tag, label});
            glyphs.add(g);
        }

        @Override
        public boolean key(int code) {
            int cols = 3, n = buttons.size();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (sel % cols > 0) sel--; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel % cols < cols - 1 && sel + 1 < n) sel++; break;
                case KeyEvent.KEYCODE_DPAD_UP: if (sel >= cols) sel -= cols; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: sel = Math.min(n - 1, sel + cols >= n && sel / cols == (n - 1) / cols ? sel : sel + cols); break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    contOption(buttons.get(sel)[0]);
                    return true;
                default: return false;
            }
            invalidate();
            return true;
        }

        @Override
        public void draw(Canvas c, RectF area, float e, boolean focused) {
            int a = Math.round(255 * e);
            float pad = 48 * k;
            // the cover: its tall poster, else the wide picture cropped, else a card in the app's colours
            float ch = area.height() - 2 * pad, cw = ch * 2 / 3f;
            RectF cover = new RectF(area.left + pad, area.top + pad, area.left + pad + cw, area.top + pad + ch);
            shelfPoster(c, cover, ct);
            float x = cover.right + 56 * k, right = area.right - pad, room = right - x;
            float y = area.top + pad + 46 * k;
            Paint tp = nk.inter(46, 800, -0.015f, Color.WHITE);
            tp.setAlpha(a);
            for (String l : wrapLines(ct.title == null ? "" : ct.title, tp, room, 2)) {
                c.drawText(l, x, y, tp);
                y += 56 * k;
            }
            // the app it is on (in its colour), and where you are
            String app = source(ct);
            int tint = tintFor(ct.pkg != null && ct.video == null ? ct.pkg + " " + app : app);
            Paint ap = nk.inter(24, 700, 0.01f, Color.WHITE);
            float aw = ap.measureText(app) + 36 * k, ah = 40 * k, ay = y - 18 * k;
            RectF tag = new RectF(x, ay, x + aw, ay + ah);
            gFill.setShader(null);
            gFill.setColor((tint & 0x00FFFFFF) | 0xCC000000);
            c.drawRoundRect(tag, ah / 2, ah / 2, gFill);
            c.drawText(app, x + 18 * k, ay + ah / 2 + 8 * k, ap);
            String line = ct.line != null ? ct.line.replace(" · " + app, "").replace(app, "").trim() : "";
            if (line.startsWith("·")) line = line.substring(1).trim();
            if (line.endsWith("·")) line = line.substring(0, line.length() - 1).trim();
            Paint mp = nk.inter(24, 500, 0, 0xCCFFFFFF);
            if (!line.isEmpty()) c.drawText(fit(line, mp, room - aw - 24 * k), tag.right + 20 * k, ay + ah / 2 + 8 * k, mp);
            y = tag.bottom + 30 * k;
            if (ct.frac > 0) {   // how far in
                float bw = Math.min(room, 520 * k), bh = 8 * k;
                RectF bar = new RectF(x, y, x + bw, y + bh);
                gFill.setColor(0x33FFFFFF);
                c.drawRoundRect(bar, bh / 2, bh / 2, gFill);
                gFill.setColor(Color.WHITE);
                gFill.setShader(new LinearGradient(x, 0, x + bw * Math.min(1, ct.frac), 0, 0xFF8A6BFF, 0xFFD9468F, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(x, y, x + Math.max(bh, bw * Math.min(1, ct.frac)), y + bh), bh / 2, bh / 2, gFill);
                gFill.setShader(null);
                Paint pp = nk.inter(21, 600, 0, Frost.MUTE);
                c.drawText(String.format(L10n.t("%d%% watched"), Math.round(100 * Math.min(1, ct.frac))), bar.right + 18 * k, y + 9 * k, pp);
                y += 40 * k;
            }
            // the story: the app's own, else the one found with the cover
            String story = ct.overview != null && !ct.overview.isEmpty() ? ct.overview : "";
            if (story.isEmpty() && ct.title != null) {
                String[] art = artByName(ct.title);
                if (art != null && art.length > 1 && art[1] != null) story = art[1];
            }
            int rowsN = (buttons.size() + 2) / 3;
            float bh = 78 * k, gap = 18 * k, gridTop = area.bottom - pad - rowsN * bh - (rowsN - 1) * gap;
            Paint sp = nk.inter(25, 400, 0, 0xD9FFFFFF);
            int maxLines = Math.max(1, (int) ((gridTop - 28 * k - y) / (36 * k)));
            if (!story.isEmpty()) {
                float sy = y + 22 * k;
                for (String l : wrapLines(story, sp, room, Math.min(5, maxLines))) {
                    c.drawText(l, x, sy, sp);
                    sy += 36 * k;
                }
            }
            // the buttons: words with a small icon, the first a bright one
            float bw = (room - 2 * gap) / 3;
            Paint lp = nk.inter(25, 700, 0, Color.WHITE), dp = nk.inter(25, 700, 0, 0xFF101218);
            for (int i = 0; i < buttons.size(); i++) {
                float bx = x + (i % 3) * (bw + gap), by = gridTop + (i / 3) * (bh + gap);
                RectF b = new RectF(bx, by, bx + bw, by + bh);
                boolean main = i == 0, on = i == sel;
                if (main) {
                    gFill.setColor(0xF2FFFFFF);
                    if (on) kit.focus(c, b, bh / 2, e);
                    c.drawRoundRect(b, bh / 2, bh / 2, gFill);
                } else {
                    kit.pill(c, b, bh / 2, e);
                    if (on) kit.focus(c, b, bh / 2, e);
                }
                Paint p = main ? dp : lp;
                String label = fit(buttons.get(i)[1], p, bw - 96 * k);
                float iconS = 30 * k, tw = iconS + 14 * k + p.measureText(label), sx = b.centerX() - tw / 2;
                Bitmap g = kit.glyph(glyphs.get(i), 30);
                if (g != null) {
                    gFill.setColor(Color.WHITE);
                    if (main) gFill.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF101218, android.graphics.PorterDuff.Mode.SRC_IN));
                    c.drawBitmap(g, null, new RectF(sx, b.centerY() - iconS / 2, sx + iconS, b.centerY() + iconS / 2), gFill);
                    gFill.setColorFilter(null);
                }
                c.drawText(label, sx + iconS + 14 * k, b.centerY() + 9 * k, p);
            }
        }
    }

    private void contOption(String what) {
        Cont ct = contOptionsFor;
        closeSheet();
        if (ct == null) return;
        if (what.equals("resume")) {
            openContinue(ct);
            return;
        }
        if (what.equals("start") && ct.video != null) {
            listener.onResumeFilm(ct.video, 0, ct.title);
            return;
        }
        if (what.equals("info")) {
            moreInfo(ct);
            return;
        }
        if (what.equals("fix")) {
            fixTitle(ct.title);
            return;
        }
        if (what.equals("watched") && ct.title != null) {
            Library.finished(getContext(), ct.title);   // off My Watchlist too, when it's there
            Watched.forget(getContext(), ct.title);
        }
        java.util.Set<String> h = new java.util.HashSet<>(contHidden());
        h.add(contKey(ct));
        prefs.edit().putStringSet("cont_hidden", h).apply();
        conts = withoutHidden(conts);
        contAt = Math.max(0, Math.min(contAt, conts.size() - 1));
        if (conts.isEmpty()) setRow(watchlist().isEmpty() ? Row.NOTHING : Row.LIST);
        message(L10n.t(what.equals("watched") ? "Marked watched" : "Removed from Continue watching"));
        invalidate();
    }

    /** More info: the title's own page (story, cast, where to watch), found by its name. */
    private void moreInfo(Cont ct) {
        Context ctx = getContext().getApplicationContext();
        message(L10n.t("Looking it up\u2026"));
        new Thread(() -> {
            Tmdb.Title best = null;
            try {
                MyMovies.Parsed p = MyMovies.parse(ct.title.replaceAll("\\((\\d{4})\\)", "$1"), "", "");
                String name = p.title.isEmpty() ? ct.title : p.title, want = SearchMatch.plain(name);
                List<Tmdb.Title> found = Catalog.search(ctx, name, 1).titles;
                for (Tmdb.Title t : found) if (SearchMatch.plain(t.title).equals(want)) { best = t; break; }
                if (best == null) for (Tmdb.Title t : found) if (sharedWords(SearchMatch.plain(t.title), want) >= 0.75f) { best = t; break; }
                if (best == null && !found.isEmpty()) best = found.get(0);
            } catch (java.io.IOException | RuntimeException ignored) {
                // nothing found
            }
            final Tmdb.Title t = best;
            post(() -> {
                if (t != null) TitleActivity.open(getContext(), t);
                else message(L10n.t("Nothing found for ") + ct.title);
            });
        }, "cont-info").start();
    }

    private void openContinue(int i) {
        openContinue(contRow().get(i));
    }

    /** Continue watching opened with Down from an app on the dock: only that app's (null: everything). */
    @Nullable
    private String contApp;

    /** What the Continue row shows: everything, or the dock app's own when it has some. */
    /** Everything you're in the middle of; coming from an app on the dock, that app's titles first (nothing is left out). */
    private List<Cont> contRow() {
        if (contApp == null) return conts;
        List<Cont> out = new ArrayList<>();
        for (Cont ct : conts) if (contApp.equals(ct.pkg)) out.add(ct);
        if (out.isEmpty()) return conts;
        for (Cont ct : conts) if (!contApp.equals(ct.pkg)) out.add(ct);
        return out;
    }

    /**
     * Over the dock, above the app in focus when it has something half-watched: its latest, and how many more. Down
     * opens them as their own row ("Continue on Netflix").
     */
    private void appPeek(Canvas c) {
        String pkg = dockAppWithConts();
        if (pkg == null) return;
        List<Cont> mine = new ArrayList<>();
        for (Cont ct : conts) if (pkg.equals(ct.pkg)) mine.add(ct);
        int[] slots = appSlots();
        RectF cell = dockCell(appSlot(slots));
        Paint p = nk.inter(23, 600, 0, Color.WHITE), q = nk.inter(21, 500, 0, 0xD9FFFFFF);
        String t = fit(mine.get(0).title, p, 380 * k), more = mine.size() > 1 ? "  +" + (mine.size() - 1) : "";
        float w = 44 * k + p.measureText("\u25BE  " + t) + q.measureText(more) + 28 * k, h = 52 * k;
        float x = Math.max(80 * k, Math.min(1840 * k - w, cell.centerX() - w / 2)), y = cell.top - h - 14 * k;
        RectF pill = new RectF(x, y, x + w, y + h);
        Frost.smoked(c, pill, h / 2, k);
        float tx = pill.left + 22 * k;
        c.drawText("\u25BE  " + t, tx, pill.centerY() + 8 * k, p);
        if (!more.isEmpty()) c.drawText(more, tx + p.measureText("\u25BE  " + t), pill.centerY() + 8 * k, q);
    }

    /** The app on the dock now in focus, when it has something half-watched; else null. */
    @Nullable
    private String dockAppWithConts() {
        int[] slots = appSlots();
        if (slots.length == 0) return null;
        Item it = items.get(slots[appSlot(slots)]);
        if (it.app == null) return null;
        for (Cont ct : conts) if (it.app.pkg.equals(ct.pkg)) return it.app.pkg;
        return null;
    }

    private void openContinue(Cont ct) {
        if (ct.video == null && ct.pkg == null) return;   // kept from last time; the drive's scan fills it in a moment
        if (ct.video != null) {
            listener.onResumeFilm(ct.video, ct.from, ct.title);
            return;
        }
        Context c = getContext();
        Intent in = null;
        try {   // the app's own link to that title or episode
            if (ct.intentUri != null) in = Intent.parseUri(ct.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException ignored) {
            // the app itself, below
        }
        if (in == null && PhoneRemote.searchApp(c, ct.pkg, ct.title)) return;   // the app's own search, at that title
        if (in == null) in = c.getPackageManager().getLeanbackLaunchIntentForPackage(ct.pkg);
        if (in == null) return;
        try {
            c.startActivity(in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Intent app = c.getPackageManager().getLeanbackLaunchIntentForPackage(ct.pkg);
            if (app != null) c.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---- Neon glass (the newer look) --------------------------------------------------------------------------
    //
    // After the reference pictures: deep navy with soft bokeh lights drifting; see-through glass panels with a fine
    // light edge; colour only in tiles (violet, green, orange, blue glass with white line icons); the focused item
    // as blue-violet glass with a glowing periwinkle edge. Left: a glass sidebar (the places, Now playing, Surprise
    // me). Right: the time, search and two round buttons; the featured title with Play now and dots; Can't decide?
    // as four tall tiles (the pictures' Quick Access); Your apps; Continue watching and New this week as lists with
    // View all. Text is Inter. Arrows move to the neighbour on screen.

    // Background colour sets (Customize → Background): a base, then six glows, each moving between two colours.
    static final String[] BG_NAMES = {L10n.t("Nebula"), L10n.t("Twilight"), L10n.t("Midnight"), L10n.t("Aurora"), L10n.t("Dusk"), L10n.t("Ocean"), L10n.t("Ember")};
    private static final int[] BG_BASE = {0xFF0B1024, 0xFF111218, 0xFF0A0E1C, 0xFF07131A, 0xFF120E18, 0xFF061424, 0xFF140C0C};
    private static final int[][][] BG_GLOWS = {
            {{0x993A2EA8, 0x99302EA0}, {0x66187080, 0x661A5C80}, {0x70B8602A, 0x70A84A30}, {0x6630509C, 0x664030A0},
                    {0x40603090, 0x40306090}, {0x59C06A30, 0x59A0402A}},   // indigo, teal, ember, blue bokeh
            {{0x8C4E4060, 0x8C5A4258}, {0xA67C5258, 0xA6865A5C}, {0x70243042, 0x702C3446}, {0x5A6D4448, 0x5A4A3A50},
                    {0x8C000000, 0x66000000}, {0x66000000, 0x8C000000}},
            {{0x8C3B2E8C, 0x8C2B3A9C}, {0x801C3A6B, 0x801A4A6A}, {0x786A3D5B, 0x785A3470}, {0x5A7A4A2E, 0x5A6A3A3A}, {0, 0}, {0, 0}},
            {{0x8C1F7A6A, 0x8C1A5E8C}, {0x80553C9C, 0x80284C9C}, {0x7826806E, 0x781E6A8C}, {0x5A4A2E8C, 0x5A2E8C7A}, {0, 0}, {0, 0}},
            {{0x8C5E4A66, 0x8C6A4060}, {0x808A5560, 0x80905A4E}, {0x786D4448, 0x785A3A58}, {0x5A243042, 0x5A3A3050}, {0, 0}, {0, 0}},
            {{0x8C1A4A8C, 0x8C1A6A9C}, {0x80165E7A, 0x801E4A8A}, {0x7830468C, 0x78205E8C}, {0x5A1A7A8A, 0x5A2A5A9C}, {0, 0}, {0, 0}},
            {{0x8C8C3A2E, 0x8C9C5A2A}, {0x806A2E3A, 0x80803A2E}, {0x787A4A1E, 0x786A2E4A}, {0x5A4A2E5E, 0x5A8C4A2E}, {0, 0}, {0, 0}}};
    private static final float[][] GLOW_AT = {{300, 80, 760}, {0, 640, 460}, {1720, 1080, 520}, {1500, 60, 620}, {900, 600, 520}, {1880, 520, 340}};

    private int bgChoice() {
        return Math.max(0, Math.min(BG_NAMES.length - 1, prefs.getInt("glass_bg", 0)));
    }

    // The four Can't decide? tiles (the pictures' Quick Access colours).
    private static final int[][] RAND_TINT = {{0xFF6447E6, 0xFF3A2399}, {0xFF22955F, 0xFF10583A}, {0xFFC06A2C, 0xFF6E3818}, {0xFF3468D8, 0xFF1D3C92}};
    private static final int SIDE_N = 6, SIDE_NOW = 6, SIDE_SURPRISE = 7, LIST_ROWS = 3;
    private int sideSel, contSel, freshSel, navSel;
    private int listAll, listSel;        // the View all list open: 1 Continue watching, 2 New this week (0 none)
    private Paint gTime, gSub, gH2, gNav, gBig, gSmall, gCaps, gBtn, gTile, gMuted;
    private final Paint gFill = new Paint(Paint.ANTI_ALIAS_FLAG), gLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gMatrix = new Matrix();
    private final Path gClip = new Path();

    private void glassPaints() {
        gTime = nk.inter(52, 800, -0.015f, 0xFFFFFFFF);
        gSub = nk.inter(21, 500, 0, 0xFFA9A9A9);
        gH2 = nk.inter(27, 700, -0.01f, 0xFFFFFFFF);
        gNav = nk.inter(23, 500, 0, 0xFFD8D8D6);
        gBig = nk.inter(50, 800, -0.015f, 0xFFFFFFFF);
        gSmall = nk.inter(18, 500, 0, 0xFFA9A9A9);
        gCaps = nk.inter(15, 700, 0.18f, 0xFFD8D8D6);
        gBtn = nk.inter(21, 700, 0, 0xFFFFFFFF);
        gTile = nk.inter(21, 700, 0, 0xFFFFFFFF);
        gMuted = nk.inter(18, 500, 0, 0xFFA9A9A9);
        for (Paint p : new Paint[]{gTime, gH2, gBig, gTile}) p.setShadowLayer(6 * k, 0, 1 * k, 0x66000000);
        gLine.setStyle(Paint.Style.STROKE);
        gLine.setStrokeCap(Paint.Cap.ROUND);
    }

    private RectF gr(float l, float t, float w, float h) {
        return new RectF(l * k, t * k, (l + w) * k, (t + h) * k);
    }

    private static int mix(int a, int b, float f) {
        int ca = a >>> 24, cr = (a >> 16) & 255, cg = (a >> 8) & 255, cb = a & 255;
        return (Math.round(ca + ((b >>> 24) - ca) * f) << 24) | (Math.round(cr + (((b >> 16) & 255) - cr) * f) << 16)
                | (Math.round(cg + (((b >> 8) & 255) - cg) * f) << 8) | Math.round(cb + ((b & 255) - cb) * f);
    }

    /** The edge, and light catching the top (a sheen clipped to the shape). */
    private void glassEdge(Canvas c, RectF r, float rad, int edge, int sheen) {
        c.save();
        gClip.reset();
        gClip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(gClip);
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height() * 0.5f, 60 * k), sheen, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r, gFill);
        gFill.setShader(null);
        c.restore();
        gLine.setStrokeWidth(Math.max(1, k));
        gLine.setColor(edge);
        c.drawRoundRect(r, rad, rad, gLine);
    }

    /** What is focused: blue-violet glass with a glowing periwinkle edge. */
    private void frosted(Canvas c, RectF r, float rad) {
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x8C5060E8, 0x4D2E3AA0, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, gFill);
        gFill.setShader(null);
        glassEdge(c, r, rad, 0x00FFFFFF, 0x33FFFFFF);
        focusEdge(c, r, rad);
    }

    private void focusEdge(Canvas c, RectF r, float rad) {
        gLine.setStrokeWidth(2 * k);
        gLine.setColor(0xF2A9B6FF);
        gLine.setShadowLayer(14 * k, 0, 0, 0xCC7F90FF);
        c.drawRoundRect(r, rad, rad, gLine);
        gLine.clearShadowLayer();
    }

    /** An icon centred on a point. */
    private void icon(Canvas c, WheelArt.Glyph g, float size, float cx, float cy, int alpha) {
        Bitmap b = kit.glyph(g, size);
        nImg.setAlpha(alpha);
        c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, nImg);
        nImg.setAlpha(255);
    }

    private String fit(String s, Paint p, float room) {
        return TextUtils.ellipsize(s == null ? "" : s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
    }

    /** A section heading, with "View all →" on the right (lit when focused). */
    private void heading(Canvas c, String title, float x, float y, float right, boolean viewAll, boolean focused) {
        c.drawText(title, x, y, gH2);
        if (!viewAll) return;
        String v = L10n.t("View all  →");
        float w = gMuted.measureText(v);
        if (focused) {
            RectF r = new RectF(right - w - 32 * k, y - 34 * k, right + 2 * k, y + 14 * k);
            frosted(c, r, 24 * k);
        }
        gMuted.setColor(focused ? 0xFFFFFFFF : 0xFFA9A9A9);
        c.drawText(v, right - w - 14 * k, y - 4 * k, gMuted);
    }

    private void drawGlass(Canvas c, long now) {
        if (gTime == null) glassPaints();
        boolean live = noOverlay();
        glassHome(c, now, live);
        if (listAll != 0) glassListAll(c);
    }

    private static final int[] SIDE_LABELS = {R.string.tab_home, R.string.discover, R.string.must_watch, R.string.my_movies, R.string.my_music, R.string.inputs_label};
    private static final WheelArt.Glyph[] SIDE_ICONS = {WheelArt.Glyph.GRID, WheelArt.Glyph.TELESCOPE, WheelArt.Glyph.CHECK, WheelArt.Glyph.FILM, WheelArt.Glyph.NOTE, WheelArt.Glyph.TV};


    // Home (the 4.png layout): the date and time with search, Inputs, Customize and Settings; New & Popular as a
    // featured card with posters beside it; the apps in one glass dock; Continue Watching, Continue Listening and
    // four feature tiles (Surprise Me, My Movies, My Music, Discover). Behind it all, the focused title's artwork,
    // blurred and darkened, crossfading when it changes (drawn once per change, not every frame).
    private static final int NAVY = 0xFF0B0B0D, CYAN = 0xFF6FDCFF, WHITE = 0xFFF4F4F1, LAVENDER = 0xFFC4C4C4, MUTED_T = 0xFF7886A5;
    private static final int FEED_MAX = 12;
    private int featSel, posSel, posterFirst;
    private Paint hTitle, hMeta, hHead, hCard, hSmall, hLabel, hSub, hBtn, hTime;

    private void homePaints() {
        // Four sizes: 88 for titles, 40 for headings, 30 for reading, 24 for labels.
        hTitle = nk.inter(88, 700, 0, Color.WHITE);
        hMeta = nk.inter(24, 500, 0, Color.WHITE);
        hOver = nk.inter(30, 400, 0, Color.WHITE);
        hHead = nk.inter(30, 600, 0, Color.WHITE);
        hCard = nk.inter(30, 500, 0, Color.WHITE);
        hSmall = nk.inter(24, 400, 0, Frost.MUTE);
        hLabel = nk.inter(24, 500, 0, Color.WHITE);
        hSub = nk.inter(24, 400, 0, Frost.MUTE);
        hBtn = nk.inter(30, 500, 0, Color.WHITE);
        hTime = nk.inter(40, 600, 0, Color.WHITE);
        hAppName = nk.inter(24, 500, 0, Color.WHITE);
        // Words directly on the picture have no glass to help them: a soft edge on the letters only.
        for (Paint p : new Paint[]{hTitle, hHead, hTime, hOver, hMeta}) p.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
    }
    private Paint hOver, hAppName;

    private final Map<String, String> wholeWordsMemo = new HashMap<>();

    /** A story cut to `lines` lines at the end of a word ("the hottest…"), never in the middle of one ("the hottest ap…"). */
    private String wholeWords(String s, TextPaint p, int width, int lines) {
        if (s == null || s.isEmpty() || width <= 0) return s == null ? "" : s;
        String key = width + "/" + lines + "/" + p.getTextSize() + "/" + s;
        String done = wholeWordsMemo.get(key);
        if (done != null) return done;
        StaticLayout all = StaticLayout.Builder.obtain(s, 0, s.length(), p, width).build();
        String out = s;
        if (all.getLineCount() > lines) {
            int end = all.getLineEnd(lines - 1);
            float room = width - p.measureText("\u2026 ");
            int lineStart = all.getLineStart(lines - 1);
            while (end > lineStart && (p.measureText(s, lineStart, end) > room || !Character.isWhitespace(s.charAt(Math.min(end, s.length() - 1))))) end--;
            String cut = s.substring(0, Math.max(end, lineStart)).replaceAll("[\\s,;:\u2013\u2014-]+$", "");
            if (!cut.isEmpty()) out = cut + "\u2026";
        }
        if (wholeWordsMemo.size() > 60) wholeWordsMemo.clear();
        wholeWordsMemo.put(key, out);
        return out;
    }

    /** New this week on your apps: a short list, so it loads quickly. */
    private List<Tmdb.Title> feed() {
        List<Tmdb.Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // each title once
        for (Tmdb.Title t : fresh) if (out.size() < FEED_MAX && seen.add(t.title)) out.add(t);
        return out;
    }

    private List<MyMusic.Song> listening() {
        MusicPlayer mp = MusicPlayer.get(getContext());
        List<MyMusic.Song> out = new ArrayList<>();
        if (mp.current() != null) {
            out.add(mp.current());
            out.addAll(mp.upNext(9));
        } else if (suggestion() != null) out.add(suggestion());
        return out;
    }

    private MyMusic.Song suggested;
    private boolean suggestAsked;

    /** With nothing playing: one song to start, an unplugged one if the drive has some, else any with a cover. */
    @Nullable
    private MyMusic.Song suggestion() {
        if (suggested != null) return suggested;
        List<MyMusic.Song> all = MyMusic.lastRead();
        if (all == null || all.isEmpty()) {
            if (!suggestAsked) {
                suggestAsked = true;
                Context ctx = getContext().getApplicationContext();
                new Thread(() -> {
                    MyMusic.read(ctx);
                    post(this::imageArrived);
                }, "suggest").start();
            }
            return null;
        }
        List<MyMusic.Song> pool = new ArrayList<>();
        for (MyMusic.Song x : all) {
            String f = x.folder == null ? "" : x.folder.toLowerCase(Locale.ROOT);
            if ((f.contains("silent") || f.contains("unplugged") || f.contains("english")) && MyMusic.hasCover(x)) pool.add(x);
        }
        if (pool.isEmpty()) for (MyMusic.Song x : all) if (MyMusic.hasCover(x)) pool.add(x);
        java.util.Collections.shuffle(pool);
        for (MyMusic.Song x : pool) {   // only one whose cover is really there
            if (MyMusic.cover(getContext(), x, this::imageArrived) != null) {
                suggested = x;
                return x;
            }
        }
        return null;   // covers still loading: asked again on the next draw
    }



    /** Smoked glass: rgba(15,23,42,a), a fine light edge, a faint highlight along the top. */
    private void smoked(Canvas c, RectF r, float rad, int alpha) {
        gFill.setShader(null);
        gFill.setColor(Color.argb(alpha, 20, 20, 22));
        c.drawRoundRect(r, rad, rad, gFill);
        gLine.setStrokeWidth(Math.max(1, k));
        gLine.setColor(0x2EFFFFFF);
        c.drawRoundRect(r, rad, rad, gLine);
        gLine.setColor(0x0DFFFFFF);
        c.drawLine(r.left + rad, r.top + 1.5f * k, r.right - rad, r.top + 1.5f * k, gLine);
    }


    /** The apps, in one dock of smoked glass: up to eight, then All apps. */
    private int[] appSlots() {
        int all = -1;
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == ALL_APPS) all = i;
            else if (items.get(i).kind != INPUTS && items.get(i).kind != DISCOVER && l.size() < 5) l.add(i);
        }
        if (all >= 0) l.add(all);
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private int appSlot(int[] slots) {
        int sel = items.isEmpty() ? 0 : Math.floorMod(Math.round(carTo), items.size());
        for (int j = 0; j < slots.length; j++) if (slots[j] == sel) return j;
        return 0;
    }

    // The four feature tiles: accent, gradient ends, border, icon colour.
    private static final int[][] TILE = {
            {0xFF7546F5, 0x4D7546F5, 0x7A311B6A, 0xA6966EFF, 0xFFE4DBFF},   // Surprise Me
            {0xFF1685FF, 0x451685FF, 0x85082B64, 0xA63291FF, 0xFF59B4FF},   // My Movies
            {0xFFF03CBF, 0x47F03CBF, 0x8052144B, 0xA6F03CBF, 0xFFFF6FD2},   // My Music
            {0xFF00D8E8, 0x4000D8E8, 0x8C063D50, 0xA600D8E8, 0xFF5CF0FF}};  // Discover
    private static final WheelArt.Glyph[] TILE_GLYPH = {WheelArt.Glyph.DICE, WheelArt.Glyph.FILM, WheelArt.Glyph.NOTE, WheelArt.Glyph.TELESCOPE};
    private static final int[] TILE_NEON = {0xFFB292FF, 0xFF6FB4FF, 0xFFF06BC4, 0xFF62E6D8};
    private static final String[][] TILE_TEXT = {{L10n.t("Surprise Me"), L10n.t("Random movie or music")}, {L10n.t("My Movies"), L10n.t("Your movie collection")},
            {L10n.t("My Music"), L10n.t("Your music collection")}, {L10n.t("Discover"), L10n.t("Find something new")}};

    // ---- Home after the reference picture 5.png: the hero (the focused title's art blended into the wallpaper,
    // its words on the left, posters along the bottom right), Continue Watching and Continue Listening as glass
    // cards (cover left, words right), the four feature tiles in one row, the apps as glass tiles with neon
    // wordmarks. Neon: the time, the icons, the wordmarks; glass everywhere else.

    // The still parts of Home are drawn once into this picture and reused while only the animation moves (the
    // background blooms and the small equaliser); anything else that asks for a redraw rebuilds it.
    // Recorded once as a display list for the graphics chip and replayed every frame; recorded again only when
    // something on it changes (the state in homeSig, or a picture arriving).
    private int imgEpoch;
    private final Runnable animTick = this::invalidate;

    private void imageArrived() {
        snapSoon();
        imgEpoch++;
        invalidate();
    }

    // Home is drawn in sections, each recorded once as a display list for the graphics chip and replayed every
    // frame; a section is recorded again only when what it shows changes (its signature). Moving the focus between
    // two tiles re-records one section, not the screen.
    private final java.util.Map<String, android.graphics.RenderNode> nodes = new java.util.HashMap<>();
    private final java.util.Map<String, String> nodeSigs = new java.util.HashMap<>();

    /**
     * A section in its own small layer (bounds in design px): rendered into a texture once, then only composited
     * each frame, so its glows and shadows cost nothing until the section changes.
     */
    private void section(Canvas c, String name, float l, float t, float r, float b, String sig, java.util.function.Consumer<Canvas> draw) {
        if (!c.isHardwareAccelerated() || android.os.Build.VERSION.SDK_INT < 29) {   // Android 9: drawn directly
            draw.accept(c);
            return;
        }
        android.graphics.RenderNode n = nodes.get(name);
        if (n == null) {
            nodes.put(name, n = new android.graphics.RenderNode(name));
            n.setUseCompositingLayer(true, null);
        }
        if (!sig.equals(nodeSigs.get(name)) || !n.hasDisplayList()) {
            int L = Math.max(0, Math.round(l * k)), T = Math.max(0, Math.round(t * k)), R = Math.min(getWidth(), Math.round(r * k)),
                    B = Math.min(getHeight(), Math.round(b * k));
            n.setPosition(L, T, R, B);
            Canvas rc = n.beginRecording();
            rc.translate(-L, -T);
            Frost.noGlide = true;   // a recorded picture can't slide: its selection is drawn where it lands
            try {
                draw.accept(rc);
            } finally {
                Frost.noGlide = false;
            }
            n.endRecording();
            nodeSigs.put(name, sig);
        }
        c.drawRenderNode(n);
    }

    private void glassHome(Canvas c, long now, boolean live) {
        if (hTitle == null) homePaints();
        List<Tmdb.Title> feed = feed();
        if (!feed.isEmpty()) featSel = Math.max(0, Math.min(feed.size() - 1, posSel));
        final Tmdb.Title feat = feed.isEmpty() ? null : feed.get(featSel);
        if (live && inShelf()) {   // the shelf's title, never Home's own pick; none yet: the picture stays as it is
            String shelfPic = shelfPicture();
            if (shelfPic != null) homeBackdropKey(shelfPic);
        } else homeBackdropKey(feat == null ? null : feat.backdropLarge());
        c.drawColor(Frost.base());
        MusicPlayer mp = MusicPlayer.get(getContext());
        MyMusic.Song cur = mp.current();
        String common = "|" + live + "," + imgEpoch + "," + getWidth() + Frost.lighter + Lite.on + "," + packGen;
        String ft = feat == null ? "" : feat.title;
        String pic = homeArtFor + "," + getWidth() + Frost.lighter + (homeFrost != null);
        // Pale picture: deeper smoke on the bars (they went milky grey on cream and snow)
        Frost.smokeDeep = Frost.lighter ? 0 : Math.max(0, Math.min(1, (Math.max(brightness(homeFrost, 0f, 0.5f, 0.6f, 1f), brightness(homeFrost, 0f, 0f, 1f, 0.2f)) - 0.4f) / 0.35f));
        long fade = now - homeFadeAt;
        final long fadeMs = inShelf() ? 280 : 700;   // the shelf changes pictures quickly as you move along it
        if (live && inShelf() && shelfPicture() == null) {   // this title has no picture at all: the plain page
            Frost.page(c, wholeScreen());
        } else if (homeArtOld != null && fade < fadeMs) {   // one picture dissolving into the next
            heroPicture(c, homeArtOld, homeArt, Math.round(255 * fade / (float) fadeMs));
        } else {
            homeArtOld = null;
            section(c, "art", 0, 0, 1920, 1080, pic, rc -> heroPicture(rc, null, homeArt, 255));
        }
        ensureLogo(feat);
        final float reveal = revealNow(now, live);
        revealShift = reveal * REVEAL * k;
        if (reveal > 0) {   // Continue is up: the plain page steps back (the full picture stays as it is)
            if (Frost.lighter) {
                gFill.setShader(null);
                gFill.setColor(Math.round(reveal * 217) << 24 | 0x030304);
                c.drawRect(0, 0, getWidth(), getHeight(), gFill);
            }
            // Full picture: it stays sharp as the rows come up (cinema mode); the rows' own fade keeps them readable
        }
        // The dock is glass over the picture's colours. Two layers, so it dissolves along with the picture.
        if (!pic.equals(glassSig)) {
            if (!glassSig.isEmpty()) glassSide ^= 1;
            glassSig = pic;
        }
        float glassFade = homeArtOld != null && fade < fadeMs ? fade / (float) fadeMs : 1;
        float barShown = live ? barShown(now) : 1;   // 0 while the covers are in its place
        c.save();
        c.translate(0, -revealShift);
        int barLayer = barShown < 1 ? barLayer(c, barShown) : -1;
        if (c.isHardwareAccelerated() && android.os.Build.VERSION.SDK_INT >= 29) {
            android.graphics.RenderNode was = nodes.get("glass" + (glassSide ^ 1)), is = nodes.get("glass" + glassSide);
            if (glassFade < 1 && was != null && was.hasDisplayList()) {
                was.setAlpha(1 - glassFade);
                c.drawRenderNode(was);
            }
            if (is != null) is.setAlpha(glassFade);
        }
        section(c, "glass" + glassSide, 40, DOCK_TOP - 8, 1880, DOCK_TOP + DOCK_H + 8, pic + "," + appSlots().length + "," + tilesOn().length, rc -> {
            RectF d = dockBar();
            Frost.smoked(rc, d, 44 * k, k);   // the dock: the search bar's smoked glass
            gFill.setShader(null);
            gFill.setColor(0x33FFFFFF);
            float dx = (dockX0() + appSlots().length * dockCellW() + DOCK_GAP / 2f) * k;
            rc.drawRect(dx, d.top + 30 * k, dx + Math.max(1, k), d.bottom - 30 * k, gFill);
        });
        if (barLayer >= 0) c.restoreToCount(barLayer);
        c.restore();
        if (reveal > 0) {
            c.save();
            c.translate(0, (REVEAL * k - revealShift));
            c.restore();
            // Continue watching and My Watchlist sit straight on the sharp picture, nothing over it (cinema mode)
        }
        heroTurn(now, live, feed);
        if (live && warmedFor != conts) {   // the first Continue titles' clean pictures, found before Down is pressed
            warmedFor = conts;
            for (int i = 0; i < Math.min(3, conts.size()); i++) if (conts.get(i).video == null && conts.get(i).title != null) artByName(conts.get(i).title);
        }
        cricketLayout();
        songLayout();
        final String topPill = (cricketBar != null ? cricketText + cricketBar.left : "") + (songBar != null ? songText + songBar.left : "");
        section(c, "topglass", 0, 28, 1920, 124, pic + topPill, rc -> {
            Frost.smoked(rc, new RectF(CAPS_X * k, TOP_Y * k, 1840 * k, (TOP_Y + TOP_H) * k), TOP_H / 2f * k, k);
            if (cricketBar != null) Frost.smoked(rc, cricketBar, TOP_H / 2f * k, k);
            if (songBar != null) Frost.smoked(rc, songBar, TOP_H / 2f * k, k);
        });
        homePlate(c, now, live);
        Weather.refresh(getContext(), this::postInvalidate);   // every 30 minutes at most, once a town is set
        Weather.Now wn = Weather.current();
        Cricket.refresh(getContext(), this::postInvalidate);   // every minute with a match on, else every 30
        section(c, "top", 0, 0, 1920, 130, System.currentTimeMillis() / 60_000L + "," + (row == Row.TOP ? navSel : -1)
                + (wn != null ? wn.symbol + wn.temperature : "") + topPill + MusicPlayer.get(getContext()).playing() + (Updater.available != null) + common, rc -> homeTop(rc, live));
        if (reveal < 1) {
            if (c.isHardwareAccelerated() && android.os.Build.VERSION.SDK_INT >= 29 && nodes.get("hero") != null) nodes.get("hero").setAlpha(1 - reveal);
            if (live && inShelf()) shelfInfo(c, now);
            else if (live && row == Row.NOTHING) { }   // the empty shelf says what goes there: the film above it steps aside
            else section(c, "hero", 0, 120, 1920, 900, ft + (feat != null ? feat.logoUrl + logoWaiting(feat) : "") + "," + (row == Row.ACTIONS ? actSel : -1) + "," + featSel + "," + feed.size() + homeArtFor + common,
                    rc -> homeHero(rc, feat, live));
        }
        c.save();
        c.translate(0, -revealShift);
        int barLayer2 = barShown < 1 ? barLayer(c, barShown) : -1;
        if (barShown > 0) {
            section(c, "apps", 40, DOCK_TOP - 8, 1880, DOCK_TOP + DOCK_H + 8, (row == Row.APPS ? Math.round(carTo) : -1) + "," + items.size() + "," + tilesOn().length + common, rc -> homeApps(rc, live));
            List<MyMusic.Song> sl = MyMusic.lastRead();
            section(c, "tiles", 40, DOCK_TOP - 8, 1880, DOCK_TOP + DOCK_H + 8, (row == Row.RANDOM ? randSel : -1) + "," + appSlots().length + "," + tilesKey + "," + driveIn + "," + filmCount + ","
                    + (sl == null ? 0 : sl.size()) + "," + fresh.size() + "," + myList().size() + common, rc -> homeButtons(rc, live));
        }
        if (barLayer2 >= 0) c.restoreToCount(barLayer2);
        c.restore();
        if (live && (inShelf() || row == Row.NOTHING)) floatRow(c, now);   // the covers, where the app bar was
        // (the note above a dock app is gone: Down shows "Continue on <app>" in the shelf instead)
        if (reveal > 0) {
            StringBuilder contSig = new StringBuilder();
            List<Cont> cr = contRow();
            for (int i = contScroll(); i < Math.min(cr.size(), contScroll() + 4); i++) contSig.append(cr.get(i).title).append(cr.get(i).frac);
            contSig.append(contApp);
            contSig.append(myList().size());
            c.save();
            c.translate(0, (REVEAL * k - revealShift));
            section(c, "watch", 60, CONT_TOP - 60, 1920, CONT_TOP + CONT_H + 8, (row == Row.CONT ? contAt : -1) + "," + conts.size() + contSig + (cur != null ? cur.key() + mp.playing() : "")
                    + "," + driveIn + common, rc -> homeContinue(rc, live && row == Row.CONT));
            if (reveal > 0.9f) homeWatchlist(c, watchlist());   // drawn as it is: it starts below the screen, where a cached section is cut off
            c.restore();
        }
        removeCallbacks(animTick);
        if (running && !idlePaused) postDelayed(animTick, 140);
    }

    /** The focused title's artwork over the right of the screen, fading into the wallpaper. */

    /** Days since a title came out, or -1 when only its year is known. */
    private static int daysOld(Tmdb.Title t) {
        if (t.date == null || t.date.length() < 10) return -1;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(t.date);
            return d == null ? -1 : (int) ((System.currentTimeMillis() - d.getTime()) / 86_400_000L);
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    /**
     * Only titles that really came out lately, newest kinds first: an older film that merely arrived on an app (the
     * JustWatch fallback lists those) is not new. With no exact date, it must at least be from this year.
     */
    private static List<Tmdb.Title> reallyNew(List<Tmdb.Title> in) {
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : in) {
            int age = daysOld(t);
            if (age > 31 || age < 0 && t.year != year) continue;
            out.add(t);
        }
        java.util.Collections.sort(out, (a, b) -> Boolean.compare(!isThisWeek(a), !isThisWeek(b)));   // stable: keeps popularity order
        return out;
    }

    private static boolean isThisWeek(Tmdb.Title t) {
        int age = daysOld(t);
        return age >= 0 && age <= 7;
    }

    /** What the label over a title may honestly say. */
    private static String newLabel(Tmdb.Title t) {
        String src = topSource(TvTownApp.context());
        if (!src.equals("new") && !src.equals("random")) return topSourceName(src);
        if (src.equals("random")) return L10n.t("Picked for you");
        return isThisWeek(t) ? L10n.t("New this week") : daysOld(t) >= 0 ? L10n.t("New this month") : L10n.t("New this year");
    }

    private Bitmap homeArt, homeFrost;
    private String homeArtFor = "", homeArtBaking, glassSig = "";
    private int glassSide;
    private final RectF screenRect = new RectF(), plateNow = new RectF(), plateA = new RectF(), plateB = new RectF();
    private int plateZone = -1;
    private Bitmap homeArtOld;
    private long homeFadeAt, heroTurnedAt;
    private static final long HERO_MS = 9000;

    /** The picture across the top of Home, down to the apps, then black. */
    private void heroPicture(Canvas c, @Nullable Bitmap under, @Nullable Bitmap art, int alpha) {
        float w = getWidth(), h = w * 9 / 16f, H = getHeight();
        if (Frost.lighter) {   // Lighter: a plain page and the picture as a clean card beside the words, no effects
            // Shiny black: polished black with a band of light across it, like a piano's lid. Lite mode: the same, washed with
            // a soft version of the picture's colour, so the plain page still feels like the film.
            c.drawColor(0xFF030304);
            if (Lite.on) {
                gFill.setColor(Color.WHITE);
                gFill.setShader(new LinearGradient(0, 0, w * 0.8f, H, Frost.liteTone(0.26f), Frost.liteTone(0.05f), Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, H, gFill);
                gFill.setShader(null);
            }
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(0, 0, w, H, new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x0FFFFFFF, 0x00FFFFFF},
                    new float[]{0, 0.18f, 0.38f, 0.66f, 0.82f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, H, gFill);
            gFill.setShader(null);
            RectF card = gr(824, 136, 1016, 740);
            if (under != null) coverImage(c, under, card, 36 * k, 255, 0.2f);
            if (art != null) coverImage(c, art, card, 36 * k, alpha, 0.2f);
            return;
        }
        screenRect.set(0, 0, w, h);
        if (under != null) c.drawBitmap(under, null, screenRect, nImg);
        if (art != null) {
            nImg.setAlpha(alpha);
            c.drawBitmap(art, null, screenRect, nImg);
            nImg.setAlpha(255);
        }
        // Full picture: the whole screen, with only a soft dark fade in the corner the words sit in, so they read
        // on a bright picture too (A Dog's Perfect Christmas, white snow); the rest of the picture is left as it is.
        // How dark it gets follows the picture: a dark one is hardly touched, a pale one gets a deeper corner and a
        // soft band along the top, so the clock and the story are never white on cream.
        float low = brightness(homeFrost, 0f, 0.5f, 0.6f, 1f), top = brightness(homeFrost, 0f, 0f, 1f, 0.2f);
        float deep = Math.max(0, Math.min(1, (low - 0.35f) / 0.4f)), band = Math.max(0, Math.min(1, (top - 0.45f) / 0.35f));
        int a0 = Math.round(0xA6 + deep * (0xEB - 0xA6)), a1 = Math.round(0x59 + deep * (0xA6 - 0x59));
        gFill.setColor(Color.WHITE);
        gFill.setShader(new RadialGradient(w * 0.08f, H * 0.82f, w * (0.62f + 0.1f * deep), new int[]{a0 << 24, a1 << 24, 0x00000000},
                new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, H, gFill);
        if (band > 0) {
            gFill.setShader(new LinearGradient(0, 0, 0, H * 0.24f, Math.round(band * 0x8C) << 24, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, H * 0.24f, gFill);
        }
        gFill.setShader(null);
    }

    private Bitmap brightOf;
    private final float[] brightMemo = new float[2];

    /** How light a part of the (blurred) picture is, 0 to 1; the part given as fractions of its width and height. */
    private float brightness(@Nullable Bitmap b, float l, float t, float r, float bt) {
        if (b == null || b.isRecycled()) return 0;
        int slot = t < 0.25f ? 1 : 0;
        if (b == brightOf && brightMemo[slot] >= 0) return brightMemo[slot];
        if (b != brightOf) {
            brightOf = b;
            brightMemo[0] = brightMemo[1] = -1;
        }
        float sum = 0;
        int n = 0;
        try {
            for (int y = 0; y < 6; y++) for (int x = 0; x < 10; x++) {
                int px = b.getPixel(Math.min(b.getWidth() - 1, Math.round(b.getWidth() * (l + (r - l) * (x + 0.5f) / 10))),
                        Math.min(b.getHeight() - 1, Math.round(b.getHeight() * (t + (bt - t) * (y + 0.5f) / 6))));
                sum += (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f;
                n++;
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return brightMemo[slot] = sum / n;
    }

    /** The top picture moves on by itself, unless you are on its buttons; the next one is fetched ahead of time. */
    private void heroTurn(long now, boolean live, List<Tmdb.Title> feed) {
        int n = feed.size();
        if (n < 2 || Lite.on) return;   // Lite: the picture changes only when you move to another
        if (heroTurnedAt == 0) heroTurnedAt = now;
        ImageCache.get(feed.get((posSel + 1) % n).backdropLarge(), null, null, 1280);
        ensureLogo(feed.get((posSel + 1) % n));   // the next film's title art, ahead of time
        if (!live || row == Row.ACTIONS || row == Row.NEW || now - heroTurnedAt < Math.max(HERO_MS, GlassBackdrop.intervalSeconds(prefs) * 1000L)) return;
        posSel = (posSel + 1) % n;
        heroTurnedAt = now;
    }

    /** Left and Right at the ends of the hero's buttons: the previous or next title. */
    private void heroStep(int dir) {
        int n = feed().size();
        if (n == 0) return;
        posSel = Math.floorMod(posSel + dir, n);
        featSel = posSel;
        heroTurnedAt = SystemClock.uptimeMillis();
    }
    private long plateAt;

    /**
     * The chosen title's picture behind the whole of Home, and a small blurred copy for the glass. Made once per
     * title, away from the screen's own thread, so moving along the posters never stutters.
     */
    /** The picture asked for last: one that finishes after you've moved on is not shown. */
    private String homeArtWant;

    private void homeBackdropKey(@Nullable String key) {
        if (key != null) homeArtWant = key;
        if (key == null || key.equals(homeArtFor) || key.equals(homeArtBaking)) return;
        Bitmap got = ImageCache.get(key, this::imageArrived, null, 1280);
        if (got == null) return;
        homeArtBaking = key;
        new Thread(() -> {
            Bitmap full = null, blurred = null;
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(got, 1280, 720, true);
                full = scaled.copy(ImageCache.config(1280), false);
                blurred = WheelArt.glassCopy(scaled, 320, 1f);   // the plain picture, only blurred: what the glass shows
                if (scaled != got) scaled.recycle();
            } catch (RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("TVTown", "backdrop", e);
            }
            final Bitmap f = full, b = blurred;
            final int accent = b != null ? Frost.accent(b) : Frost.LILAC;
            post(() -> {
                if (f != null && b != null && key.equals(homeArtWant)) {   // still the one wanted
                    homeAccent = accent;
                    Frost.accentNow = accent;
                    if (homeArt != null) {
                        homeArtOld = homeArt;
                        homeFadeAt = SystemClock.uptimeMillis();
                    }
                    homeArt = f;
                    homeFrost = b;
                    GlassSheet.frost = b;
                    homeArtFor = key;
                }
                homeArtBaking = null;
                imageArrived();
            });
        }, "home-art").start();
    }

    /** Where the frosted plate belongs now (in screen pixels), or null when nothing on the glass is selected. */
    @Nullable
    private RectF plateTarget(boolean live, int[] zone, float[] rad) {
        if (!live) return null;
        switch (row) {
            case TOP:
                zone[0] = 0;
                rad[0] = TOP_H / 2f * k;
                if (navSel == NAV_CRICKET && cricketBar != null) return new RectF(cricketBar);
                if (navSel == NAV_SONG && songBar != null) return new RectF(songBar);
                if (navSel == 0) return null;   // the search bar shows its own selection (Frost.searchBar)
                return navSel == 0 || navSel == NAV_CRICKET || navSel == NAV_SONG ? gr(SEARCH_X, TOP_Y, SEARCH_W, TOP_H) : gr(topIconX(navSel), TOP_Y, TOP_ICON, TOP_H);
            case APPS:
                zone[0] = 1;
                rad[0] = 36 * k;
                return dockCell(appSlot(appSlots()));
            case CONT:
                if (inShelf()) return null;
                zone[0] = 2;
                rad[0] = 40 * k;
                return contPlate(contAt);
            case LIST:   // lifted into Continue's place while it has the focus
                if (inShelf()) return null;
                zone[0] = 5;
                rad[0] = 40 * k;
                RectF pp = posterPlate(listAtW, listFilms());   // the rows are lifted LIST_LIFT higher here
                if (!watchlist().isEmpty()) pp.offset(0, -LIST_LIFT * k);
                return pp;
            case RANDOM:
                zone[0] = 3;
                rad[0] = 36 * k;
                return homeTile(randSel);
            default:
                return null;
        }
    }

    /** The frosted plate on Home: it glides within a panel and appears at once when the selection enters another. */
    private void homePlate(Canvas c, long now, boolean live) {
        int[] zone = {-1};
        float[] rad = {32 * k};
        RectF target = plateTarget(live, zone, rad);
        if (target == null) {
            plateZone = -1;
            return;
        }
        if (zone[0] == 2 || zone[0] == 5) {   // Continue and My Watchlist: a crisp white frame round the picture, at once (no trail)
            plateZone = zone[0];
            plateA.set(target);
            plateB.set(target);
            c.save();
            if (zone[0] == 2) c.translate(0, REVEAL * k - revealShift);
            RectF ring = zone[0] == 5 && !watchlist().isEmpty()
                    ? new RectF(target.left + 17 * k, target.top + 11 * k, target.left + (27 + POSTER_W) * k, target.top + (21 + POSTER_H) * k)
                    : new RectF(target.left + 13 * k, target.top + 11 * k, target.right - 13 * k, target.bottom - 11 * k);
            gLine.setShader(null);
            gLine.setStrokeWidth(3.5f * k);
            gLine.setColor(Color.WHITE);
            c.drawRoundRect(ring, 20 * k, 20 * k, gLine);
            c.restore();
            return;
        }
        if (zone[0] != plateZone) {
            plateZone = zone[0];
            plateA.set(target);
            plateB.set(target);
            plateAt = 0;
        } else if (!target.equals(plateB)) {
            plateA.set(Frost.glide(plateA.left, plateB.left, now, plateAt), Frost.glide(plateA.top, plateB.top, now, plateAt),
                    Frost.glide(plateA.right, plateB.right, now, plateAt), Frost.glide(plateA.bottom, plateB.bottom, now, plateAt));
            plateB.set(target);
            plateAt = now;
        }
        plateNow.set(Frost.glide(plateA.left, plateB.left, now, plateAt), Frost.glide(plateA.top, plateB.top, now, plateAt),
                Frost.glide(plateA.right, plateB.right, now, plateAt), Frost.glide(plateA.bottom, plateB.bottom, now, plateAt));
        c.save();
        if (zone[0] == 1 || zone[0] == 3) c.translate(0, -revealShift);
        else if (zone[0] == 2) c.translate(0, REVEAL * k - revealShift);
        int tint = plateTint();
        if (tint != plateColB) {
            plateColA = (int) COLOURS.evaluate(Frost.glide(0, 1, now, plateColAt), plateColA, plateColB);
            plateColB = tint;
            plateColAt = now;
        }
        Frost.plate(c, plateNow, rad[0], k, (int) COLOURS.evaluate(Frost.glide(0, 1, now, plateColAt), plateColA, plateColB));
        c.restore();
    }

    // Home's plan, in design pixels: one margin of 80, the top capsule, the floating dock, and Continue below it.
    private static final int TOP_Y = 40, TOP_H = 72, TOP_ICON = 76, SEARCH_W = 330, CAPS_X = 1840 - 3 * TOP_ICON - 8, SEARCH_X = CAPS_X - 16 - SEARCH_W;   // the search bar stands on its own, left of the icons
    private static final int DOCK_TOP = 908, DOCK_H = 132, DOCK_GAP = 24, REVEAL = 340, CONT_TOP = 760, CONT_H = 272, CONT_FILM = 500, LIST_DROP = 330;   // My Watchlist sits LIST_DROP under Continue
    private static final float FOCUS_SCALE = 1.03f;
    private static final android.animation.ArgbEvaluator COLOURS = new android.animation.ArgbEvaluator();
    private int plateColA = Frost.LILAC, plateColB = Frost.LILAC, homeAccent = Frost.LILAC;
    private long plateColAt, revealAt;
    private float revealA, revealB, revealShift;

    private float topIconX(int i) {
        return 1840 - TOP_ICON * (4 - i);   // inputs, Control Center, Settings
    }

    /** How far Continue has come up (0 to 1): it shows while it has the focus. */
    private float revealNow(long now, boolean live) {
        float target = 0;   // Continue and My Watchlist are a shelf over the dock now (shelf()): nothing moves up
        if (target != revealB) {
            revealA = Frost.glide(revealA, revealB, now, revealAt);
            revealB = target;
            revealAt = now;
        }
        return Frost.glide(revealA, revealB, now, revealAt);
    }

    private float dockCellW() {
        return Math.min(200, (1760f - DOCK_GAP) / (appSlots().length + tilesOn().length));
    }

    private float dockX0() {
        return (1920 - dockCellW() * (appSlots().length + tilesOn().length) - DOCK_GAP) / 2;
    }

    private RectF dockBar() {
        return gr(dockX0(), DOCK_TOP, dockCellW() * (appSlots().length + tilesOn().length) + DOCK_GAP, DOCK_H);
    }

    private RectF dockCell(int j) {
        return gr(dockX0() + j * dockCellW(), DOCK_TOP, dockCellW(), DOCK_H);
    }

    /** The picture's own colour, which what is selected takes; each app has its brand's instead. */
    private int plateTint() {
        if (row == Row.APPS) {
            int[] slots = appSlots();
            if (slots.length > 0) return appTint(items.get(slots[appSlot(slots)]));
        }
        return homeAccent;
    }

    private int appTint(Item it) {
        String p = it.app != null ? it.app.pkg.toLowerCase(Locale.ROOT) : "";
        if (p.contains("netflix")) return 0xFFD40F1B;
        if (p.contains("amazon")) return 0xFF1A8FE3;
        if (p.contains("youtube")) return 0xFFDC1F1F;
        if (p.contains("hotstar")) return 0xFF3D35C8;
        if (p.contains("appletv")) return 0xFF5C5C63;
        if (p.contains("graymatrix")) return 0xFF7B2CBF;
        if (p.contains("sonyliv")) return 0xFFD9621A;
        if (p.contains("videolan")) return 0xFFD97400;
        if (p.contains("spotify")) return 0xFF17A34A;
        return homeAccent;
    }

    /** The whole of a place in Continue, top to bottom, for the selected glass. */
    private RectF contPlate(int i) {
        return slotPlate(i - contScroll(), Integer.MAX_VALUE);   // the row slides: the chosen card stays in view
    }

    /** A place in a row of three wide cards and a small last tile, at Continue's height. */
    private RectF slotPlate(int i, int films) {
        return i >= films ? gr(80 + films * CONT_FILM, CONT_TOP, SEE_ALL_W, CONT_H) : gr(80 + i * CONT_FILM, CONT_TOP, CONT_FILM, CONT_H);
    }

    /** The See all tile: small, after the covers, and only when there are more than fit. */
    private static final int SEE_ALL_W = 240;

    /** My Watchlist's posters: big enough to read from the sofa on a large TV, with room between them. */
    /** Big enough to read from the sofa on a large TV (2:3), with room between them; the row lifts higher to fit them. */
    private static final int POSTER_W = 267, POSTER_H = 400, POSTER_STEP = POSTER_W + 44, LIST_LIFT = 120;

    private RectF posterPlate(int i, int films) {
        if (watchlist().isEmpty()) return slotPlate(0, 1);
        return gr(80 + (i - listScroll()) * POSTER_STEP, CONT_TOP, POSTER_STEP, POSTER_H + 32);
    }

    /** The first poster in view: the row slides so the chosen one is never past the fourth place. */
    private int listScroll() {
        return Math.max(0, Math.min(listAtW - 3, watchlist().size() - 5));
    }

    private boolean listExtra() {
        return false;   // no See all: the row keeps going to the right
    }

    /** True while something is still moving, so the view keeps redrawing at full speed. */
    private boolean moving(long now) {
        if (plateZone >= 0 && now - plateAt < Frost.GLIDE_MS) return true;
        if (now - revealAt < Frost.GLIDE_MS) return true;
        if (homeArtOld != null) return true;
        if (sheet != null && ((sheet.open && sheet.t < 1) || !sheet.open || sheet.moving(now))) return true;
        return picker != null && (picker.open ? picker.t < 1 : picker.t > 0);
    }

    private int contAt;

    /** Continue's places: up to three films, then a small tile (all of them, or the song playing). */
    private RectF contCell(int i) {
        RectF p = contPlate(i);
        return new RectF(p.left + 18 * k, p.top + 16 * k, p.right - 18 * k, p.bottom - 16 * k);
    }

    private int contFilms() {
        return Math.max(1, contShown());
    }

    /** How much of Continue shows: up to three. */
    private int contShown() {
        return Math.min(3, contRow().size());
    }

    /** What the small last tile is: 2 the whole list, 0 nothing (the song playing sits in the top bar). */
    private int contExtra() {
        return 0;   // no See all: the row keeps going to the right
    }

    /** The first card in view: the row slides along so the chosen card is never past the third place. */
    private int contScroll() {
        return Math.max(0, Math.min(contAt - 2, contRow().size() - 3));
    }

    // ---- My Watchlist: what you saved in TV Town (Want to Watch) and in your apps; finished ones drop off ----

    /** One title on the watchlist: from TV Town (an entry) or from an app (a Cont to open). */
    private static final class WItem {
        Library.Entry entry;
        Cont app;

        String title() {
            return entry != null ? entry.title : app.title;
        }
    }

    private int listAtW;

    private List<WItem> watchlist() {
        List<WItem> out = new ArrayList<>();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Library.Entry e : myList()) {
            WItem w = new WItem();
            w.entry = e;
            out.add(w);
            if (e.title != null) have.add(e.title.trim().toLowerCase(Locale.ROOT));
        }
        java.util.Set<String> gone = prefs.getStringSet("wl_hidden", java.util.Collections.emptySet());
        for (Cont ct : appSaved) {
            if (ct.title == null || gone.contains(ct.title.trim().toLowerCase(Locale.ROOT)) || !have.add(ct.title.trim().toLowerCase(Locale.ROOT))) continue;
            WItem w = new WItem();
            w.app = ct;
            out.add(w);
        }
        return out;
    }

    private int listFilms() {
        return Math.max(1, watchlist().size());
    }

    /** The row: three covers with their names, then See all (where titles are removed or added). */
    private void homeWatchlist(Canvas c, List<WItem> wl) {
        float drop = LIST_DROP * k;
        rowHead(c, L10n.t("My Watchlist"), wl.size(), (CONT_TOP - 18) * k + drop);
        if (row == Row.LIST && wl.size() > 1) where(c, listAtW + 1, wl.size(), (CONT_TOP - 18) * k + drop);
        if (wl.isEmpty()) {
            RectF p = slotPlate(0, 1);
            upNext(c, new RectF(p.left + 18 * k, p.top + 16 * k + drop, p.right - 18 * k, p.bottom - 16 * k + drop), null);
            return;
        }
        int films = wl.size();
        for (int i = listScroll(); i < Math.min(films, listScroll() + 6); i++) {   // the sixth peeks in
            RectF p = posterPlate(i, films);
            RectF cell = new RectF(p.left + 22 * k, p.top + 16 * k + drop, p.left + 22 * k + POSTER_W * k, p.top + 16 * k + drop + POSTER_H * k);
            WItem w = wl.get(i);
            if (w.entry != null) posterCard(c, cell, w.entry.poster, w.entry.title, null);
            else posterCard(c, cell, w.app.image, w.app.title, source(w.app));
        }

    }

    private int contLast() {
        return Math.max(0, contRow().size() - 1);
    }

    private void homeContinue(Canvas c, boolean on) {
        List<Cont> cr = contRow();
        boolean own = contApp != null && cr != conts;
        rowHead(c, own ? L10n.t("Continue on ") + source(cr.get(0)) : L10n.t("Continue watching"), conts.isEmpty() ? 0 : cr.size(), (CONT_TOP - 18) * k);
        if (on && cr.size() > 1 && !conts.isEmpty()) where(c, contAt + 1, cr.size(), (CONT_TOP - 18) * k);
        if (conts.isEmpty()) {   // nothing half-watched yet
            RectF p = contCell(0);
            thumb(c, null, new RectF(p.left, p.top, p.right, p.top + 150 * k), WheelArt.Glyph.PLAY);
            boolean allowed = Perms.tvListings(getContext());   // not allowed: say how, OK asks
            c.drawText(fit(allowed ? L10n.t("Nothing half-watched") : L10n.t("See what you're watching"), hCard, p.width()), p.left, p.bottom - 44 * k, hCard);
            c.drawText(fit(allowed ? L10n.t("Start something on any app") : L10n.t("Press OK to allow it from your apps"), hSub, p.width()), p.left, p.bottom - 8 * k, hSub);
        } else for (int i = contScroll(); i < Math.min(cr.size(), contScroll() + 4); i++) homeWatch(c, contCell(i), cr.get(i));   // the fourth peeks in
    }

    /** A row's name, with how many there are in a small pill beside it. */
    private void rowHead(Canvas c, String name, int count, float base) {
        Paint head = new Paint(hHead);   // a soft shadow, so the name reads on a bright picture too
        head.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        c.drawText(name, 96 * k, base, head);
        if (count <= 0) return;
        String n = String.valueOf(count);
        Paint p = nk.inter(22, 700, 0, Color.WHITE);
        float x = 96 * k + hHead.measureText(name) + 16 * k, w = p.measureText(n) + 24 * k;
        RectF pill = new RectF(x, base - 26 * k, x + w, base + 4 * k);
        gFill.setShader(null);
        gFill.setColor(0x8C000000);
        c.drawRoundRect(pill, 15 * k, 15 * k, gFill);
        c.drawText(n, pill.left + 12 * k, base - 4 * k, p);
    }

    /** Where you are in a long row, at the right of its heading: "2 of 12". */
    private void where(Canvas c, int at, int of, float base) {
        Paint p = nk.inter(24, 600, 0, 0xD9FFFFFF);
        p.setShadowLayer(6 * k, 0, 2 * k, 0xCC000000);
        String w = at + L10n.t(" of ") + of;
        c.drawText(w, 1840 * k - p.measureText(w), base, p);
    }

    // ---- The shelf: Continue watching or My Watchlist above the dock, the title you're on filling the screen ----

    private long shelfAt;
    private List<Cont> warmedFor;
    private static final long SHELF_MS = 260;
    /** A watchlist title's wide picture and story, looked up once (TMDB), by its key. */
    private final java.util.Map<String, String[]> shelfArt = new java.util.HashMap<>();
    private final java.util.Set<String> shelfAsked = new java.util.HashSet<>();

    private boolean inShelf() {
        return row == Row.CONT || row == Row.LIST;
    }

    /** What the shelf shows for one title: its words, its picture behind, its card. */
    private static final class Shelved {
        String title, app, picture, card, line, story, hint;
        float frac = -1;
        int colour;
    }

    @Nullable
    private Shelved shelved() {
        if (row == Row.CONT) {
            List<Cont> cr = contRow();
            if (cr.isEmpty()) return null;
            Cont ct = cr.get(Math.max(0, Math.min(contAt, cr.size() - 1)));
            Shelved s = new Shelved();
            s.title = ct.title;
            s.app = source(ct);
            s.colour = tintFor(ct.pkg != null && ct.video == null ? ct.pkg + " " + s.app : s.app);
            s.picture = ct.image;
            s.card = ct.image;
            s.line = ct.line;
            s.story = ct.overview;
            if (!ImageCache.canShow(s.picture)) {   // no picture we can show: the fallback
                String[] art = artByName(ct.title);
                if (art != null && art[0] != null) s.picture = s.card = art[0];
                if (art != null && (s.story == null || s.story.isEmpty())) s.story = art[1];
            } else if (ct.video == null) {
                // An app's own artwork has its name, logo and often a progress bar painted in: behind the whole screen it
                // clashes with TV Town's own title. The film's clean picture is used there when it's found by the same
                // name; the card keeps the app's artwork. Not found: the app's artwork after all.
                String[] art = artByName(ct.title);
                if (art == null) s.picture = null;   // still looking (a moment, once): nothing yet, not the wrong one first
                else if (art[0] != null) s.picture = art[0];
                if (art != null && (s.story == null || s.story.isEmpty())) s.story = art[1];
            }
            s.frac = ct.frac;
            s.hint = L10n.t("OK to resume  ·  hold OK for more");
            return s;
        }
        List<WItem> wl = watchlist();
        if (wl.isEmpty()) return null;
        WItem w = wl.get(Math.max(0, Math.min(listAtW, wl.size() - 1)));
        Shelved s = new Shelved();
        s.hint = L10n.t("OK to open");
        if (w.entry != null) {
            Library.Entry e = w.entry;
            s.title = e.title;
            s.app = L10n.t("My Watchlist");
            s.colour = 0xFF9C84FF;
            s.card = e.poster;
            s.line = ("tv".equals(e.type) ? L10n.t("Series") : L10n.t("Film")) + (e.year > 0 ? " · " + e.year : "") + (e.imdb > 0 ? String.format(Locale.US, " · ★ %.1f", e.imdb) : "");
            String[] art = shelfArt.get(e.key);
            if (art == null) askArt(e);
            s.picture = art != null && art[0] != null ? art[0] : null;
            s.story = art != null ? art[1] : "";
        } else {
            s.title = w.app.title;
            s.app = source(w.app);
            s.colour = tintFor(w.app.pkg != null ? w.app.pkg + " " + s.app : s.app);
            s.picture = w.app.image;
            s.card = w.app.image;
            s.line = w.app.line;
            s.story = w.app.overview;
        }
        return s;
    }

    /**
     * The fallback for a title an app gave no picture for (VLC's films, a Netflix picture that can't be read): its
     * name looked up in the film catalogue, once; the best match's wide picture and story. Null while looking.
     */
    // ---- a wrong picture put right: the title you picked is remembered for that name ------------------------

    @Nullable
    private String[] fixedArt(String title) {
        String v = prefs.getString("fix:" + title.toLowerCase(Locale.ROOT), null);
        if (v == null) return null;
        String[] p = v.split("\u0001", -1);
        return p.length == 3 ? new String[]{p[0].isEmpty() ? null : p[0], p[1], p[2].isEmpty() ? null : p[2]} : null;
    }

    private String fixingTitle;
    private final List<Tmdb.Title> fixFound = new ArrayList<>();

    /** Asks for the right name, then shows what TMDB has for it to pick from. */
    private void fixTitle(String title) {
        fixingTitle = title;
        listener.onAskText(L10n.t("The right title for ") + title, L10n.t("Its name, as on the app"), typed -> {
            if (typed == null || typed.trim().isEmpty()) return;
            Context ctx = getContext().getApplicationContext();
            new Thread(() -> {
                List<Tmdb.Title> got = new ArrayList<>();
                try {
                    for (Tmdb.Title t : Catalog.search(ctx, typed.trim(), 1).titles) if (got.size() < 8 && (t.poster() != null || t.backdropLarge() != null)) got.add(t);
                } catch (Exception ignored) {
                    // nothing found
                }
                post(() -> {
                    fixFound.clear();
                    fixFound.addAll(got);
                    if (got.isEmpty()) {
                        message(L10n.t("Nothing found. Try another name"));
                        return;
                    }
                    GlassSheet s = new GlassSheet(kit, L10n.t("Which one is it?"), WheelArt.Glyph.SEARCH, "fixpick", "");
                    s.popupW = 1000;
                    for (int i = 0; i < got.size(); i++) {
                        Tmdb.Title t = got.get(i);
                        String line = t.title + (t.year > 0 ? "  (" + t.year + ")" : "") + "  ·  " + (t.isSeries() ? L10n.t("Series") : L10n.t("Film"));
                        s.rows.add(new GlassSheet.Row(GlassSheet.ACTION, line, kit.glyph(t.isSeries() ? WheelArt.Glyph.TV : WheelArt.Glyph.FILM, 38), "fixpick:" + i));
                    }
                    s.layout(getWidth(), getHeight(), 1000, Math.min(8, got.size()));
                    sheet = s;
                    invalidate();
                });
            }, "fix-search").start();
        });
    }

    private void fixPicked(int i) {
        if (fixingTitle == null || i < 0 || i >= fixFound.size()) return;
        Tmdb.Title t = fixFound.get(i);
        String b = t.backdropLarge() != null ? t.backdropLarge() : "", p = t.poster() != null ? t.poster() : "";
        prefs.edit().putString("fix:" + fixingTitle.toLowerCase(Locale.ROOT), b + "\u0001" + (t.overview == null ? "" : t.overview) + "\u0001" + p).apply();
        shelfArt.remove("name:" + fixingTitle.toLowerCase(Locale.ROOT));
        shelfAsked.remove("name:" + fixingTitle.toLowerCase(Locale.ROOT));
        sheet = null;
        message(L10n.t("Fixed: ") + t.title);
        invalidate();
    }

    @Nullable
    private String[] artByName(String title) {
        String[] fixedNow = fixedArt(title);
        if (fixedNow != null) return fixedNow;
        String key = "name:" + title.toLowerCase(Locale.ROOT);
        String[] have = shelfArt.get(key);
        if (have != null || !shelfAsked.add(key)) return have;
        Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            String[] got = null;
            try {
                got = findArt(ctx, title);
            } catch (RuntimeException ignored) {
                // nothing found: the card keeps its plain look
            }
            final String[] found = got != null ? got : new String[]{null, "", null};
            post(() -> {
                shelfArt.put(key, found);
                if (found[0] != null) ImageCache.get(found[0], null, null, 1280);   // ready before you get to it
                invalidate();
            });
        }, "name-art").start();
        return null;
    }

    /**
     * A cover for a name an app gave: TMDB with the same name, then a close one, then the name without what apps add
     * (": Episode 3", "Season 2", " - Part 1") and by shared words; then JustWatch. {wide, story, tall}, or null.
     */
    @Nullable
    static String[] findArt(Context ctx, String title) {
        MyMovies.Parsed p = MyMovies.parse(title.replaceAll("\\((\\d{4})\\)", "$1"), "", "");
        String name = p.title.isEmpty() ? title : p.title;
        List<String> tries = new ArrayList<>();
        tries.add(name);
        String shorter = name.replaceAll("(?i)\\s*[:|\\-\u2013\u2014]\\s*(season|series|episode|ep\\.?|chapter|part|s\\d).*$", "")
                .replaceAll("(?i)\\s+(season|series)\\s*\\d+.*$", "").replaceAll("(?i)\\s+s\\d{1,2}\\s*e\\d{1,3}.*$", "").trim();
        if (!shorter.isEmpty() && !shorter.equalsIgnoreCase(name)) tries.add(shorter);
        int cut = name.indexOf(':');
        if (cut > 2 && !tries.contains(name.substring(0, cut).trim())) tries.add(name.substring(0, cut).trim());
        for (String n : tries) {
            try {
                Tmdb.Title best = null;
                String want = SearchMatch.plain(n);
                List<Tmdb.Title> found = Catalog.search(ctx, n, 1).titles;
                for (int pass = 0; pass < 3 && best == null; pass++) {   // the same name; a close one; most words shared
                    for (Tmdb.Title t : found) {
                        String its = SearchMatch.plain(t.title);
                        boolean ok = pass == 0 ? its.equals(want)
                                : pass == 1 ? !want.isEmpty() && (its.startsWith(want) || want.startsWith(its)) && Math.min(its.length(), want.length()) >= 4
                                : sharedWords(its, want) >= 0.75f;
                        if (!ok || p.year > 0 && t.year > 0 && Math.abs(t.year - p.year) > 1) continue;
                        if (t.backdropLarge() == null && t.poster() == null) continue;
                        best = t;
                        break;
                    }
                }
                if (best != null) return new String[]{best.backdropLarge(), best.overview == null ? "" : best.overview, best.poster()};
            } catch (java.io.IOException | RuntimeException ignored) {
                // the next name, or JustWatch
            }
        }
        for (String n : tries) {   // JustWatch knows the streaming apps' own names
            try {
                String want = SearchMatch.plain(n);
                for (JustWatch.Title j : JustWatch.search(n, null, 0, 6)) {
                    String its = SearchMatch.plain(j.title);
                    if (!its.equals(want) && sharedWords(its, want) < 0.75f) continue;
                    if (j.poster == null && j.backdrop == null) continue;
                    return new String[]{j.backdrop, j.description == null ? "" : j.description, j.poster};
                }
            } catch (Exception ignored) {
                // no cover: the card is drawn in the app's colours
            }
        }
        return null;
    }

    /** How many of the shorter name's words the other has (0..1); one-word names must match whole. */
    static float sharedWords(String a, String b) {
        java.util.Set<String> x = new java.util.HashSet<>(java.util.Arrays.asList(a.split(" "))), y = new java.util.HashSet<>(java.util.Arrays.asList(b.split(" ")));
        x.remove("");
        y.remove("");
        x.removeAll(java.util.Arrays.asList("the", "a", "an", "of", "and"));
        y.removeAll(java.util.Arrays.asList("the", "a", "an", "of", "and"));
        java.util.Set<String> small = x.size() <= y.size() ? x : y, big = small == x ? y : x;
        if (small.size() < 2) return 0;
        int n = 0;
        for (String w : small) if (big.contains(w)) n++;
        return n / (float) small.size();
    }

    /** A My Watchlist title as a wide card, like Continue's: its wide picture (else its poster, cropped wide). */
    private Cont asCard(WItem w) {
        if (w.entry == null) return w.app;
        Library.Entry e = w.entry;
        Cont ct = new Cont();
        ct.title = e.title;
        ct.pkg = "watchlist";
        ct.app = L10n.t("My Watchlist");
        String[] art = shelfArt.get(e.key);
        if (art == null) askArt(e);
        ct.image = art != null && art[0] != null ? art[0] : e.poster;
        ct.poster = e.poster;
        ct.line = ("tv".equals(e.type) ? L10n.t("Series") : L10n.t("Film")) + (e.year > 0 ? " · " + e.year : "");
        return ct;
    }

    /** A saved title's wide picture and story, fetched once in the background. */
    private void askArt(Library.Entry e) {
        if (e.tmdbId <= 0 || !shelfAsked.add(e.key) || !Tmdb.available()) return;
        Context ctx = getContext().getApplicationContext();
        String type = "tv".equals(e.type) ? "tv" : "movie";
        new Thread(() -> {
            String[] art = null;
            try {
                art = Tmdb.pictureAndStory(ctx, type, e.tmdbId);
            } catch (java.io.IOException | RuntimeException ignored) {
                // no picture: the top picture stays
            }
            final String[] got = art;
            post(() -> {
                if (got != null) shelfArt.put(e.key, got);
                invalidate();
            });
        }, "shelf-art").start();
    }

    @Nullable
    private String shelfPicture() {
        Shelved s = shelved();
        if (s == null) return null;
        return s.picture != null ? s.picture : s.card;   // no wide picture: its own card's, not someone else's
    }

    /** How far the shelf has risen (0 to 1), smoothed. */
    private float shelfRise(long now) {
        if (Lite.on) return 1;
        float f = Math.max(0, Math.min(1, (now - shelfAt) / (float) SHELF_MS));
        if (f < 1) postInvalidateOnAnimation();
        return 1 - (1 - f) * (1 - f) * (1 - f);
    }

    /** The title you're on, on the left, as the top picture's words are: app, name, details, story, how far you got. */
    private void shelfInfo(Canvas c, long now) {
        Shelved s = shelved();
        if (s == null) return;
        float f = shelfRise(now);
        int a = Math.round(255 * f);
        float x = 80 * k, room = 960 * k;
        c.save();
        c.translate(0, (1 - f) * 16 * k);
        Paint name = nk.inter(64, 800, -0.02f, Color.WHITE);
        name.setShadowLayer(10 * k, 0, 2 * k, 0xB3000000);
        name.setAlpha(a);
        fitSize(name, s.title, room, 64, 40);
        Paint meta = nk.inter(24, 500, 0, Color.WHITE);
        meta.setShadowLayer(6 * k, 0, 1 * k, 0xCC000000);
        meta.setAlpha(a);
        TextPaint story = new TextPaint(nk.inter(24, 400, 0, 0xE6FFFFFF));
        story.setShadowLayer(6 * k, 0, 1 * k, 0xCC000000);
        story.setAlpha(Math.round(0xE6 * f));
        boolean hasLine = s.line != null && !s.line.replace(s.app, "").replace("·", "").trim().isEmpty();
        float y = (380 - (s.frac > 0 ? 44 : 0) - (hasLine ? 14 : 0)) * k;   // where Home's own film details sit, the button at their foot
        appTag(c, x, y, s.app, s.colour);
        y += 34 * k + 66 * k;
        c.drawText(fit(s.title, name, room), x, y, name);
        y += 46 * k;
        String line = s.line == null ? "" : s.line.replace(" · " + s.app, "").replace(s.app, "").trim();
        if (line.endsWith("·")) line = line.substring(0, line.length() - 1).trim();
        if (!line.isEmpty()) {
            c.drawText(fit(line, meta, room), x, y, meta);
            y += 14 * k;
        }
        if (s.story != null && !s.story.isEmpty()) {
            String st = wholeWords(s.story, story, Math.round(room), 2);
            StaticLayout sl = StaticLayout.Builder.obtain(st, 0, st.length(), story, Math.round(room)).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4 * k, 1).build();
            c.save();
            c.translate(x, y + 6 * k);
            sl.draw(c);
            c.restore();
            y += sl.getHeight() + 14 * k;
        }
        if (s.frac > 0) {   // how far you got: a slim violet-to-pink line
            RectF track = new RectF(x, y + 18 * k, x + 260 * k, y + 26 * k);
            gFill.setShader(null);
            gFill.setColor(0x40FFFFFF);
            c.drawRoundRect(track, 4 * k, 4 * k, gFill);
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(track.left, 0, track.right, 0, 0xFF8A6BFF, 0xFFD9468F, Shader.TileMode.CLAMP));
            c.drawRoundRect(track.left, track.top, track.left + track.width() * Math.min(1, s.frac), track.bottom, 4 * k, 4 * k, gFill);
            gFill.setShader(null);
            y += 36 * k;
        }
        Paint hp = nk.inter(26, 700, 0, Color.WHITE);
        float hw = hp.measureText(s.hint) + 52 * k;
        RectF pill = new RectF(x, y + 12 * k, x + hw, y + 68 * k);
        c.saveLayerAlpha(pill.left - 10 * k, pill.top - 10 * k, pill.right + 10 * k, pill.bottom + 10 * k, a);
        Frost.hero(c, pill, pill.height() / 2, false, k);
        c.drawText(s.hint, pill.left + 26 * k, pill.centerY() + 9 * k, hp);
        c.restore();
        c.restore();
    }

    /** The shelf over the dock, on the right: both names above it, its cards sliding along, the chosen one framed. */
    private void shelf(Canvas c, long now) {
        boolean cont = row == Row.CONT;
        List<Cont> cr = contRow();
        List<WItem> wl = watchlist();
        int n = cont ? cr.size() : wl.size(), at = cont ? contAt : listAtW;
        if (n == 0) return;
        float f = shelfRise(now);
        float cw = 400, ch = 225, gap = 26, x0 = 80;   // from the left, as rows are; one card size for both rows, so swapping is smooth
        float top = DOCK_TOP - 40 - ch;
        c.save();
        c.translate(0, (1 - f) * 40 * k);
        int saved = c.saveLayerAlpha(0, (top - 70) * k, getWidth(), DOCK_TOP * k, Math.round(255 * f));
        // both names, the one showing bright; and where you are
        Paint on = nk.inter(30, 800, 0, Color.WHITE), off = nk.inter(30, 700, 0, 0x80FFFFFF), small = nk.inter(24, 600, 0, 0xCCFFFFFF);
        for (Paint p : new Paint[]{on, off, small}) p.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        float tx = x0 * k, ty = (top - 24) * k;
        String a1 = L10n.t("Continue watching"), a2 = L10n.t("My Watchlist");
        if (!conts.isEmpty()) {
            c.drawText(a1, tx, ty, cont ? on : off);
            tx += (cont ? on : off).measureText(a1) + 34 * k;
        }
        if (!wl.isEmpty()) {
            c.drawText(a2, tx, ty, cont ? off : on);
            tx += (cont ? off : on).measureText(a2) + 34 * k;
        }
        c.drawText("\u25BC " + (cont && !wl.isEmpty() ? "next" : "apps"), tx, ty, small);
        if (n > 1) {   // L10n.t("2 of 6"); nothing to count with one
            String where = (at + 1) + L10n.t(" of ") + n;
            c.drawText(where, 1840 * k - small.measureText(where), ty, small);
        }
        // the next titles' pictures, fetched ahead, so moving along doesn't wait for them
        for (int i = at - 1; i <= at + 2; i++) {
            if (i < 0 || i >= n || i == at) continue;
            String pic = cont ? cr.get(i).image : wl.get(i).entry != null ? null : wl.get(i).app.image;
            if (cont && cr.get(i).video == null && ImageCache.canShow(pic)) {   // an app's title: its clean picture, looked up ahead
                String[] clean = artByName(cr.get(i).title);
                if (clean != null && clean[0] != null) pic = clean[0];
            }
            if (!cont && wl.get(i).entry != null) {
                String[] art = shelfArt.get(wl.get(i).entry.key);
                if (art == null) askArt(wl.get(i).entry);
                else pic = art[0];
            }
            if (pic != null) ImageCache.get(pic, null, null, 1280);
        }
        // the cards, sliding so the chosen one is never past the third in view
        int first = Math.max(0, at - 2);
        for (int i = first; i < Math.min(n, first + 5); i++) {
            float x = x0 + (i - first) * (cw + gap);
            if (x > 1920) break;
            RectF r = gr(x, top, cw, ch);
            homeWatch(c, r, cont ? cr.get(i) : asCard(wl.get(i)));
            if (i == at) {   // the chosen one: a crisp white frame
                gLine.setShader(null);
                gLine.setStrokeWidth(3.5f * k);
                gLine.setColor(Color.WHITE);
                c.drawRoundRect(r.left - 5 * k, r.top - 5 * k, r.right + 5 * k, r.bottom + 5 * k, 20 * k, 20 * k, gLine);
            }
        }
        c.restoreToCount(saved);
        c.restore();
    }

    /** Down in the corner: New this week → Continue watching → My Watchlist → the dock (rows with nothing are skipped). */
    private void coverDown() {   // Continue watching → My Watchlist → the app bar (pages with nothing are skipped)
        if (row == Row.CONT && !watchlist().isEmpty()) {
            listAtW = 0;
            setRow(Row.LIST);
        } else setRow(Row.APPS);
    }

    /** Down from the app bar: the first page of covers there is, or the "nothing yet" page. */
    private void openCovers() {
        contAt = 0;
        listAtW = 0;
        if (!conts.isEmpty()) setRow(Row.CONT);
        else if (!watchlist().isEmpty()) setRow(Row.LIST);
        else setRow(Row.NOTHING);
    }

    private long barAt;
    private static final long BAR_MS = 300;

    /** How much of the app bar shows (1 all, 0 none): it turns away as the covers come and back as they go. */
    private float barShown(long now) {
        boolean covers = inShelf() || row == Row.NOTHING;
        float f = Lite.on ? 1 : Math.max(0, Math.min(1, (now - barAt) / (float) BAR_MS));
        if (f < 1) postInvalidateOnAnimation();
        f = 1 - (1 - f) * (1 - f);
        return covers ? 1 - f : f;
    }

    /** The bar turning away: fading, and folding back toward its foot. */
    private int barLayer(Canvas c, float shown) {
        int saved = c.saveLayerAlpha(0, (DOCK_TOP - 12) * k, getWidth(), (DOCK_TOP + DOCK_H + 12) * k, Math.round(255 * shown));
        c.scale(1, 0.55f + 0.45f * shown, getWidth() / 2f, (DOCK_TOP + DOCK_H) * k);
        return saved;
    }

    /**
     * The covers in the app bar's place, with nothing behind them: the page's name, count and dots above; the chosen one
     * framed; a "+ Add more" tile after My Watchlist; a "nothing yet" line when there are none.
     */
    private void floatRow(Canvas c, long now) {
        float f = shelfRise(now);
        float cw = 200, ch = 300, gap = 24, x0 = 80, top = 736;   // tall covers, like Discover's
        int saved = c.saveLayerAlpha(0, (top - 50) * k, getWidth(), getHeight(), Math.round(255 * f));
        c.translate(0, (1 - f) * 46 * k);
        Paint head = nk.inter(28, 800, 0.01f, Color.WHITE), small = nk.inter(24, 700, 0, 0xD9FFFFFF);
        head.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        small.setShadowLayer(6 * k, 0, 2 * k, 0xCC000000);
        float base = (top - 16) * k;
        List<Row> pages = new ArrayList<>();
        if (!conts.isEmpty()) pages.add(Row.CONT);
        if (!watchlist().isEmpty()) pages.add(Row.LIST);
        if (row == Row.NOTHING) {
            c.drawText(L10n.t("Continue watching and My Watchlist"), x0 * k, base, head);
            Paint line = nk.inter(26, 500, 0, Color.WHITE);
            line.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
            String say = L10n.t("Films you start, and titles you save with + My List, appear here.");
            c.drawText(say, x0 * k, (top + 70) * k, line);
            Paint bp = nk.inter(24, 700, 0, Color.WHITE);
            float bx = x0 * k + line.measureText(say) + 32 * k, bw = bp.measureText(L10n.t("+ Browse Discover")) + 48 * k;
            RectF pill = new RectF(bx, (top + 34) * k, bx + bw, (top + 94) * k);
            Frost.hero(c, pill, pill.height() / 2, true, k);
            c.drawText(L10n.t("+ Browse Discover"), pill.left + 24 * k, pill.centerY() + 8 * k, bp);
            c.restoreToCount(saved);
            return;
        }
        boolean cont = row == Row.CONT;
        List<Cont> cr = contRow();
        List<WItem> wl = watchlist();
        int n = cont ? cr.size() : wl.size(), at = cont ? contAt : listAtW;
        String name = cont ? L10n.t("Continue watching") : L10n.t("My Watchlist");
        c.drawText(name, x0 * k, base, head);
        float hx = x0 * k + head.measureText(name) + 14 * k;
        String cnt = String.valueOf(n);
        Paint cp = nk.inter(21, 700, 0, Color.WHITE);
        RectF pillN = new RectF(hx, base - 26 * k, hx + cp.measureText(cnt) + 24 * k, base + 5 * k);
        gFill.setShader(null);
        gFill.setColor(0x8C000000);
        c.drawRoundRect(pillN, 15.5f * k, 15.5f * k, gFill);
        c.drawText(cnt, pillN.left + 12 * k, base - 3 * k, cp);
        float dx = pillN.right + 16 * k;
        for (Row pg : pages) {   // which page of covers this is
            gFill.setColor(pg == row ? Color.WHITE : 0x66FFFFFF);
            float w = pg == row ? 22 * k : 9 * k;
            c.drawRoundRect(dx, base - 15 * k, dx + w, base - 6 * k, 4.5f * k, 4.5f * k, gFill);
            dx += w + 6 * k;
        }
        if (n > 1) {
            String where = Math.min(at + 1, n) + L10n.t(" of ") + n;
            c.drawText(where, 1840 * k - small.measureText(where), base, small);
        }
        // the same feel as the app bar: the frame glides to the next cover, the row slides after it
        float scrollTo = Math.max(0, at - 5);
        if (row != floatRowOf) {   // a new page: start in place
            floatRowOf = row;
            floatFrom = floatTo = at;
            floatScrollFrom = floatScrollTo = scrollTo;
            floatAt = 0;
        } else if (at != floatTo || scrollTo != floatScrollTo) {
            floatFrom = floatPos(now);
            floatScrollFrom = floatScroll(now);
            floatTo = at;
            floatScrollTo = scrollTo;
            floatAt = now;
        }
        float pos = floatPos(now), scroll = floatScroll(now);
        if (!Lite.on && now - floatAt < Theme.MOVE_MS) postInvalidateOnAnimation();
        int last = cont ? n - 1 : n;   // My Watchlist ends with + Add more
        for (int i = Math.max(0, (int) Math.floor(scroll) - 1); i <= last; i++) {
            float x = x0 + (i - scroll) * (cw + gap);
            if (x > 1920) break;
            float gone = Math.max(0, Math.min(1, scroll - i));   // sliding off on the left: fading
            if (gone >= 1) continue;
            RectF r = gr(x, top, cw, ch);
            int fadeLayer = gone > 0 ? c.saveLayerAlpha(r.left - 10 * k, r.top - 10 * k, r.right + 10 * k, r.bottom + 20 * k, Math.round(255 * (1 - gone))) : -1;
            c.save();
            float lift = Math.max(0, 1 - Math.abs(i - pos));   // grows as the focus arrives, shrinks as it leaves
            if (lift > 0) {
                c.scale(1 + 0.07f * lift, 1 + 0.07f * lift, r.centerX(), r.centerY());
                if (lift > 0.5f) Frost.posterShadow(c, r, 18 * k, k);
            }
            if (i == n) {   // + Add more
                Frost.smoked(c, r, 18 * k, k);
                Paint ap = nk.inter(24, 700, 0, Color.WHITE);
                ap.setTextAlign(Paint.Align.CENTER);
                c.drawText("+", r.centerX(), r.centerY() - 4 * k, nk.inter(44, 300, 0, Color.WHITE));
                c.drawText(L10n.t("Add more"), r.centerX(), r.centerY() + 34 * k, ap);
            } else {
                gFill.setShader(null);   // a soft shadow under each cover, so it stands off the picture
                gFill.setColor(0x59000000);
                c.drawRoundRect(r.left + 4 * k, r.top + 10 * k, r.right + 4 * k, r.bottom + 14 * k, 18 * k, 18 * k, gFill);
                shelfPoster(c, r, cont ? cr.get(i) : asCard(wl.get(i)));
            }
            if (lift > 0.5f) Frost.posterRing(c, r, 18 * k, k);   // the white edge hugs the chosen cover
            else Frost.posterDim(c, r, 18 * k);
            if (false) {
            }
            c.restore();
            if (fadeLayer >= 0) c.restoreToCount(fadeLayer);
        }
        c.restoreToCount(saved);
    }

    private Row floatRowOf;
    private float floatFrom, floatTo, floatScrollFrom, floatScrollTo;
    private long floatAt;

    /** Where the frame is now: a quick, soft glide (like the app bar's glass). */
    private float floatPos(long now) {
        if (Lite.on) return floatTo;
        float f = Math.max(0, Math.min(1, (now - floatAt) / 260f));
        f = 1 - (1 - f) * (1 - f) * (1 - f);
        return floatFrom + (floatTo - floatFrom) * f;
    }

    /** How far the row has slid: the app bar's own slide. */
    private float floatScroll(long now) {
        if (Lite.on) return floatScrollTo;
        return floatScrollFrom + (floatScrollTo - floatScrollFrom) * Theme.progress(now, floatAt, Theme.MOVE_MS);
    }

    private final android.graphics.Camera coverCam = new android.graphics.Camera();
    private final android.graphics.Matrix coverM = new android.graphics.Matrix();

    /**
     * The corner over the dock: small posters turning in, the chosen one in front and two on each side turning away
     * and darkening; the row's name above, right-aligned, with three dots for the three rows. No glow.
     */
    private void coverflow(Canvas c, long now) {
        int which = row == Row.CONT ? 1 : row == Row.LIST ? 2 : 0;
        boolean focused = row == Row.NEW || row == Row.CONT || row == Row.LIST;
        List<String[]> cards = new ArrayList<>();   // picture, name, tag, progress (0-1, or empty)
        int at;
        if (which == 0) {
            List<Tmdb.Title> feed = feed();
            for (Tmdb.Title t : feed) cards.add(new String[]{t.poster() != null ? t.poster() : t.backdropLarge(), t.title, "NEW", ""});
            at = featSel;
        } else if (which == 1) {
            for (Cont ct : contRow()) {
                String pic = ImageCache.canShow(ct.image) ? ct.image : null;
                if (pic == null) {
                    String[] art = artByName(ct.title);
                    if (art != null) pic = art[0];
                }
                String left = ct.line != null && ct.line.contains("left") ? ct.line.replaceAll(".*?(\\d[^·]*left).*", "$1").toUpperCase(Locale.ROOT) : "CONTINUE";
                cards.add(new String[]{pic, ct.title, left, ct.frac > 0 ? String.valueOf(ct.frac) : ""});
            }
            at = contAt;
        } else {
            for (WItem w : watchlist()) {
                Cont ct = asCard(w);
                String pic = w.entry != null && w.entry.poster != null ? w.entry.poster : ct.image;
                cards.add(new String[]{pic, ct.title, "SAVED", ""});
            }
            at = listAtW;
        }
        int n = cards.size();
        if (n == 0) return;
        at = Math.max(0, Math.min(at, n - 1));
        // the row's name, right-aligned, with the three dots (the one showing tall)
        String[] names = {L10n.t("NEW THIS WEEK"), L10n.t("CONTINUE WATCHING"), L10n.t("MY WATCHLIST")};
        Paint lab = nk.inter(21, 800, 0.1f, Color.WHITE);
        lab.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        float right = 1860 * k, base = 652 * k;
        c.drawText(names[which], right - lab.measureText(names[which]), base, lab);
        float dx = right - lab.measureText(names[which]) - 18 * k, dy = base - 22 * k;
        gFill.setShader(null);
        for (int i = 0; i < 3; i++) {
            gFill.setColor(i == which ? Color.WHITE : 0x59FFFFFF);
            float h = i == which ? 16 * k : 7 * k;
            c.drawRoundRect(dx - 3.5f * k, dy, dx + 3.5f * k, dy + h, 4 * k, 4 * k, gFill);
            dy += h + 4 * k;
        }
        // the posters: far ones first, the chosen one last (in front)
        float cw = 138, ch = 207, cx = 1640, top = 676;
        for (int ring = 2; ring >= 0; ring--) {
            for (int side : ring == 0 ? new int[]{0} : new int[]{-1, 1}) {
                int i = at + side * ring;
                if (i < 0 || i >= n) continue;
                float off = ring == 0 ? 0 : 117 + (ring - 1) * 69, sc = 1 - ring * 0.07f, deg = ring == 0 ? 0 : -side * 42;
                float x = (cx + side * off) * k, y = (top + ch / 2) * k;
                c.save();
                coverCam.save();
                coverCam.rotateY(deg);
                coverCam.getMatrix(coverM);
                coverCam.restore();
                coverM.preTranslate(-x, -y);
                coverM.postTranslate(x, y);
                c.concat(coverM);
                c.scale(ring == 0 ? 1.06f : sc, ring == 0 ? 1.06f : sc, x, y);
                RectF r = new RectF(x - cw / 2 * k, y - ch / 2 * k, x + cw / 2 * k, y + ch / 2 * k);
                coverCard(c, r, cards.get(i), ring);
                if (ring == 0 && focused) {   // chosen with the remote: a crisp white frame
                    gLine.setShader(null);
                    gLine.setStrokeWidth(3 * k);
                    gLine.setColor(Color.WHITE);
                    c.drawRoundRect(r.left - 4 * k, r.top - 4 * k, r.right + 4 * k, r.bottom + 4 * k, 16 * k, 16 * k, gLine);
                }
                c.restore();
            }
        }
        // the neighbours' pictures, fetched ahead
        for (int i = Math.max(0, at - 3); i <= Math.min(n - 1, at + 3); i++) if (cards.get(i)[0] != null) ImageCache.get(cards.get(i)[0], null, null, 300);
    }

    /** One small poster: the picture, its name and a small tag at the foot, darker the further it is from the front. */
    private void coverCard(Canvas c, RectF r, String[] card, int ring) {
        float rad = 12 * k;
        Bitmap img = card[0] != null ? ImageCache.get(card[0], this::imageArrived, null, 300) : null;
        if (img != null) coverImage(c, img, r, rad, 255, 0.5f);
        else {
            gFill.setShader(null);
            gFill.setColor(0xFF1C1C22);
            c.drawRoundRect(r, rad, rad, gFill);
            icon(c, WheelArt.Glyph.FILM, 40, r.centerX(), r.top + r.height() * 0.38f, 180);
        }
        c.save();
        android.graphics.Path round = new android.graphics.Path();
        round.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(round);
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(0, r.top + r.height() * 0.45f, 0, r.bottom, 0x00000000, 0xE0000000, Shader.TileMode.CLAMP));
        c.drawRect(r, gFill);
        gFill.setShader(null);
        if (!card[3].isEmpty()) {   // how far in: a thin line along the foot
            float f = Float.parseFloat(card[3]);
            gFill.setColor(0x40FFFFFF);
            c.drawRect(r.left, r.bottom - 5 * k, r.right, r.bottom, gFill);
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(r.left, 0, r.right, 0, 0xFF8A6BFF, 0xFFD9468F, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.bottom - 5 * k, r.left + r.width() * Math.min(1, f), r.bottom, gFill);
            gFill.setShader(null);
        }
        if (ring > 0) {   // further away: darker
            gFill.setColor(ring == 1 ? 0x73000000 : 0xA6000000);
            c.drawRect(r, gFill);
        }
        c.restore();
        if (ring == 0) {
            Paint nm = nk.inter(17, 800, 0, Color.WHITE);
            nm.setShadowLayer(4 * k, 0, 1 * k, 0xCC000000);
            nm.setTextAlign(Paint.Align.CENTER);
            c.drawText(fit(card[1], nm, r.width() - 14 * k), r.centerX(), r.bottom - 34 * k, nm);
            Paint tg = nk.inter(11, 800, 0.06f, Color.WHITE);
            float tw = tg.measureText(L10n.t(card[2])) + 16 * k;
            RectF pill = new RectF(r.centerX() - tw / 2, r.bottom - 26 * k, r.centerX() + tw / 2, r.bottom - 10 * k);
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(pill.left, 0, pill.right, 0, 0xFF5D45D6, 0xFF973A8C, Shader.TileMode.CLAMP));
            c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, gFill);
            gFill.setShader(null);
            c.drawText(L10n.t(card[2]), pill.left + 8 * k, pill.bottom - 4.5f * k, tg);
        }
    }

    /** "See all 12 ›": a small glass pill at the end of a row, not a big tile. */
    private void seeAll(Canvas c, RectF t, int count) {
        String w = L10n.t("See all ") + count + "  \u203A";
        Paint p = nk.inter(24, 600, 0, Color.WHITE);
        float pw = Math.min(t.width(), p.measureText(w) + 44 * k), ph = 60 * k;
        RectF pill = new RectF(t.centerX() - pw / 2, t.centerY() - ph / 2, t.centerX() + pw / 2, t.centerY() + ph / 2);
        Frost.ghost(c, pill, ph / 2, false, k);
        c.drawText(fit(w, p, pw - 32 * k), pill.left + 22 * k, pill.centerY() + 8 * k, p);
    }

    /** A My Watchlist poster: the picture, tall, with the app it's on in a small tag at its foot. */
    private void posterCard(Canvas c, RectF r, @Nullable String image, String title, @Nullable String app) {
        float rad = 16 * k;
        Bitmap img = image != null ? ImageCache.get(image, this::imageArrived, null, 400) : null;
        if (img != null) coverImage(c, img, r, rad, 255, 0.5f);
        else {   // no picture yet: its name on a dark card
            gFill.setShader(null);
            gFill.setColor(0x26FFFFFF);
            c.drawRoundRect(r, rad, rad, gFill);
            icon(c, WheelArt.Glyph.FILM, 44, r.centerX(), r.centerY() - 40 * k, 200);
            TextPaint tp = new TextPaint(hSub);
            tp.setColor(Color.WHITE);
            StaticLayout sl = StaticLayout.Builder.obtain(title, 0, title.length(), tp, Math.round(r.width() - 24 * k)).setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build();
            c.save();
            c.translate(r.left + 12 * k, r.centerY());
            sl.draw(c);
            c.restore();
        }
        if (app != null) appTag(c, r.left + 10 * k, r.bottom - 44 * k, app, tintFor(app));
    }

    /** A small dark tag with the app's colour dot and name, on a picture. */
    private void appTag(Canvas c, float x, float top, String app, int colour) {
        Paint p = nk.inter(19, 700, 0, Color.WHITE);
        float w = p.measureText(app) + 46 * k, h = 34 * k;
        RectF t = new RectF(x, top, x + w, top + h);
        gFill.setShader(null);
        gFill.setColor(0xA6000000);
        c.drawRoundRect(t, h / 2, h / 2, gFill);
        gFill.setColor(colour | 0xFF000000);
        c.drawCircle(t.left + 17 * k, t.centerY(), 5 * k, gFill);
        c.drawText(app, t.left + 30 * k, t.centerY() + 7 * k, p);
    }

    /** An app's own colour, by its name or package (Netflix red, Prime blue…); violet for My Movies and the rest. */
    private int tintFor(String app) {
        String a = app.toLowerCase(Locale.ROOT);
        if (a.contains("netflix")) return 0xFFE50914;
        if (a.contains("prime") || a.contains("amazon")) return 0xFF1A98FF;
        if (a.contains("youtube")) return 0xFFFF2D2D;
        if (a.contains("hotstar")) return 0xFF4C6FFF;
        if (a.contains("apple")) return 0xFFB0B0B8;
        if (a.contains("sony")) return 0xFFFF8A2B;
        if (a.contains("vlc") || a.contains("videolan")) return 0xFFFF8800;
        return 0xFF9C84FF;
    }

    private RectF homeTile(int i) {
        return gr(dockX0() + appSlots().length * dockCellW() + DOCK_GAP + Math.max(0, tilePos(i)) * dockCellW(), DOCK_TOP, dockCellW(), DOCK_H);
    }

    /** The dock's own buttons that are switched on (0 Surprise Me, 1 My Movies, 2 My Music, 3 Discover, always on). */
    private int[] tilesOn() {
        Context c = getContext();
        boolean s = Features.on(c, Features.SURPRISE), m = Features.on(c, Features.MOVIES), u = Features.on(c, Features.MUSIC);
        int key = (s ? 1 : 0) | (m ? 2 : 0) | (u ? 4 : 0);
        if (key == tilesKey && tilesMemo != null) return tilesMemo;
        int[] all = new int[4];
        int n = 0;
        if (s) all[n++] = 0;
        if (m) all[n++] = 1;
        if (u) all[n++] = 2;
        all[n++] = 3;
        tilesKey = key;
        return tilesMemo = Arrays.copyOf(all, n);
    }

    private int[] tilesMemo;
    private int tilesKey = -1;

    private int tilePos(int id) {
        int[] t = tilesOn();
        for (int i = 0; i < t.length; i++) if (t[i] == id) return i;
        return -1;
    }

    /** A picture filling a rectangle with rounded corners. */
    private void thumb(Canvas c, @Nullable Bitmap img, RectF r, WheelArt.Glyph blank) {
        if (img != null) coverImage(c, img, r, 20 * k, 255, 0.2f);
        else {
            gFill.setShader(null);
            gFill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, 20 * k, 20 * k, gFill);
            icon(c, blank, 56, r.centerX(), r.centerY(), 170);
        }
    }

    private static final WheelArt.Glyph[] TOP_GLYPHS = {WheelArt.Glyph.SEARCH, WheelArt.Glyph.TV, WheelArt.Glyph.SLIDERS, WheelArt.Glyph.GEAR};
    private static final int[] TOP_NEON = {0xFF8FE6FF, 0xFF8FE6FF, 0xFF8FE6FF, 0xFF8FE6FF};   // one accent: calmer, more premium
    private void homeTop(Canvas c, boolean live) {
        Date d = new Date();
        String t = timeFmt.format(d) + " " + ampmFmt.format(d).toLowerCase(Locale.ROOT);
        Paint tp = nk.inter(40, 600, 0, Color.WHITE);
        tp.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
        c.drawText(t, 80 * k, 90 * k, tp);
        float x = 80 * k + tp.measureText(t) + 28 * k;
        Paint dp = nk.inter(24, 500, 0, Color.WHITE);
        dp.setShadowLayer(4 * k, 0, 1 * k, 0x8C000000);
        String date = new SimpleDateFormat(Lang.datePattern(), Lang.dates()).format(d);
        c.drawText(date, x, 88 * k, dp);
        x += dp.measureText(date);
        Weather.Now w = Weather.current();
        if (w != null) {   // beside the date: the sky and the temperature
            x += 24 * k;
            c.drawText(w.symbol, x, 88 * k, dp);
            c.drawText(w.temperature, x + dp.measureText(w.symbol) + 8 * k, 88 * k, dp);
        }
        if (cricketBar != null) {
            Cricket.Match m = Cricket.forHome();
            Paint p = nk.inter(24, 500, 0, Color.WHITE);
            float tx = cricketBar.left + 28 * k;
            if (m != null && m.live) {
                gFill.setShader(null);
                gFill.setColor(0xFFFF4D4D);
                c.drawCircle(tx + 6 * k, cricketBar.centerY(), 6 * k, gFill);
                tx += 22 * k;
            }
            c.drawText(cricketText, tx, cricketBar.centerY() + 9 * k, p);
        }
        drawSong(c);
        float cy = (TOP_Y + TOP_H / 2f) * k;
        RectF sf = gr(SEARCH_X, TOP_Y, SEARCH_W, TOP_H);   // the search bar: a black box to type in
        Frost.searchBar(c, sf, k, live && row == Row.TOP && navSel == 0, L10n.t("Search films and apps"), true, nk.inter(23, 500, 0, Color.WHITE));
        for (int i : new int[]{1, 3}) {   // your two buttons, Customize between them
            Bitmap b = LineIcon.icon(topIcon(topButton(i)), 34 * k);
            c.drawBitmap(b, (topIconX(i) + TOP_ICON / 2f) * k - b.getWidth() / 2f, cy - b.getHeight() / 2f, nImg);
        }
        icon(c, TOP_GLYPHS[2], 34, (topIconX(2) + TOP_ICON / 2f) * k, cy, 255);
        if (Updater.available != null)   // a new version: a dot on Customize, where Updates is
            Frost.dot(c, (topIconX(2) + TOP_ICON / 2f + 16) * k, cy - 15 * k, k);
    }

    // ---- the top bar's two buttons of your choice (Customize → Home) -------------------------------------

    static final String[] TOP_CHOICES = {"inputs", "settings", "phone", "bluetooth", "wifi", "screensaver", "memory", "control", "sports"};

    static String topName(String id) {
        switch (id) {
            case "inputs": return L10n.t("Inputs");
            case "settings": return L10n.t("TV settings");
            case "phone": return L10n.t("Phone remote");
            case "bluetooth": return L10n.t("Bluetooth");
            case "wifi": return L10n.t("Wi-Fi");
            case "screensaver": return L10n.t("Screensaver");
            case "memory": return L10n.t("Free up memory");
            case "control": return L10n.t("Control Center");
            default: return L10n.t("Sports");
        }
    }

    private static String topIcon(String id) {
        switch (id) {
            case "inputs": return "tv";
            case "settings": return "gear";
            case "phone": return "phone";
            case "bluetooth": return "bt";
            case "wifi": return "wifi";
            case "screensaver": return "moon";
            case "memory": return "chip";
            case "control": return "pulse";
            default: return "trophy";
        }
    }

    /** Slot 1 (left of Customize) or 3 (right of it). */
    private String topButton(int slot) {
        String v = prefs.getString(slot == 1 ? "top_left" : "top_right", slot == 1 ? "inputs" : "settings");
        return java.util.Arrays.asList(TOP_CHOICES).contains(v) ? v : slot == 1 ? "inputs" : "settings";
    }

    private void topAction(String id) {
        Context c = getContext();
        switch (id) {
            case "inputs": openInputs(); break;
            case "settings": listener.onSystemSettings(); break;
            case "phone": openPhonePopup(null); break;
            case "bluetooth": openBluetooth(null); invalidate(); break;
            case "wifi":
                try {
                    c.startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    listener.onSystemSettings();
                }
                break;
            case "screensaver":
                if (!Screensavers.tryNow(c)) message(L10n.t("This TV can't start it from here: it starts on its own when the TV is idle"));
                break;
            case "memory": c.startActivity(new Intent(c, CleanupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
            case "control": openControlCenter(); break;
            default: openCricket();
        }
    }

    /** The phone remote: how to connect, and its QR code, in a pop-up of its own. */
    private void openPhonePopup(@Nullable GlassSheet from) {
        GlassSheet p = new GlassSheet(kit, L10n.t("Phone remote"), WheelArt.Glyph.TV, "phonepop", "");
        p.solo = new PhonePane(getContext());
        p.popupW = 1120;
        p.parent = from;
        p.layout(getWidth(), getHeight(), 1120, 1);
        sheet = p;
        invalidate();
    }

    private final RectF wholeScreenRect = new RectF();

    /** The whole screen, which is what the blurred picture is laid over. */
    private RectF wholeScreen() {
        wholeScreenRect.set(0, 0, getWidth(), getHeight());
        return wholeScreenRect;
    }

    /** Top bar's position for the score, after Search's four; it sits to the left of Search. */
    private static final int NAV_CRICKET = 4;
    @Nullable
    private RectF cricketBar;   // where the score is, or null when none is showing

    private String cricketText = "";

    /** Where the score sits in the top capsule, when a match deserves the space. */
    private void cricketLayout() {
        Cricket.Match m = Cricket.on(getContext()) ? Cricket.forHome() : null;
        cricketBar = null;
        if (m == null) return;
        Paint p = nk.inter(24, 500, 0, Color.WHITE);
        float right = (SEARCH_X - 16) * k, pad = 28 * k, dot = m.live ? 22 * k : 0;
        cricketText = fit(m.state() + "  " + m.score(), p, 600 * k);
        cricketBar = new RectF(right - p.measureText(cricketText) - pad * 2 - dot, TOP_Y * k, right, (TOP_Y + TOP_H) * k);
    }

    /** Top bar's position for the song playing in My Music: a pill of its own, a little right of the middle. */
    private static final int NAV_SONG = 5;
    @Nullable
    private RectF songBar;
    private String songText = "";

    private void songLayout() {
        MyMusic.Song s = MusicPlayer.get(getContext()).current();
        songBar = null;
        if (s == null) return;
        Paint p = nk.inter(24, 500, 0, Color.WHITE);
        String who = s.artist == null || s.artist.trim().isEmpty() ? "" : "  \u00B7  " + s.artist;
        songText = fit(s.title + who, p, 400 * k);
        float right = (cricketBar != null ? cricketBar.left : SEARCH_X * k) - 16 * k;
        float w = TOP_H * k + p.measureText(songText) + 76 * k;   // the cover, the words, the play mark
        float left = Math.max(right - w, 640 * k);
        songBar = new RectF(left, TOP_Y * k, right, (TOP_Y + TOP_H) * k);
        if (right - left < w) songText = fit(s.title + who, p, right - left - TOP_H * k - 76 * k);
    }

    private void drawSong(Canvas c) {
        if (songBar == null) return;
        MusicPlayer mp = MusicPlayer.get(getContext());
        MyMusic.Song s = mp.current();
        if (s == null) return;
        Bitmap cover = MyMusic.cover(getContext(), s, this::imageArrived);
        RectF pic = new RectF(songBar.left + 10 * k, songBar.top + 10 * k, songBar.left + (TOP_H - 10) * k, songBar.bottom - 10 * k);
        if (cover != null) coverImage(c, cover, pic, pic.height() / 2, 255);
        else icon(c, WheelArt.Glyph.NOTE, 30, pic.centerX(), pic.centerY(), 255);
        Paint p = nk.inter(24, 500, 0, Color.WHITE);
        c.drawText(songText, pic.right + 18 * k, songBar.centerY() + 9 * k, p);
        float cx = songBar.right - 36 * k, cy = songBar.centerY();   // playing: two bars; paused: a play arrow
        gFill.setShader(null);
        gFill.setColor(Color.WHITE);
        if (mp.playing()) {
            c.drawRoundRect(cx - 9 * k, cy - 11 * k, cx - 3 * k, cy + 11 * k, 2 * k, 2 * k, gFill);
            c.drawRoundRect(cx + 3 * k, cy - 11 * k, cx + 9 * k, cy + 11 * k, 2 * k, 2 * k, gFill);
        } else {
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(cx - 7 * k, cy - 12 * k);
            tri.lineTo(cx + 11 * k, cy);
            tri.lineTo(cx - 7 * k, cy + 12 * k);
            tri.close();
            c.drawPath(tri, gFill);
        }
    }

    /** Today's matches. */
    private void openCricket() {
        getContext().startActivity(new Intent(getContext(), SportsActivity.class));
    }



    /** The hero's words: a small cyan label, the title large, details, two lines, Watch Now and More Info. */
    private void homeHero(Canvas c, @Nullable Tmdb.Title t, boolean live) {
        float x = 80 * k, wide = (Frost.lighter ? 700 : 960) * k;
        boolean on = live && row == Row.ACTIONS;
        if (t == null) {
            if (!Tmdb.available()) {   // no key and no list: say what it brings, and the button adds it
                hTitle.setTextSize(64 * k);
                c.drawText(fit(L10n.t("See what's new on your apps"), hTitle, wide), x, 650 * k, hTitle);
                hTitle.setTextSize(88 * k);
                c.drawText(fit(L10n.t("Add a free TMDB key: scan a QR code with your phone."), hOver, wide), x, 712 * k, hOver);
                heroButton(c, gr(80, 812, 280, 72), L10n.t("Add the key"), 0, true, on);
            }
            return;
        }
        String ov = t.overview == null ? "" : t.overview;
        float low = ov.trim().isEmpty() ? 84 * k : 0;   // no story: the name and details sit down by the buttons
        Bitmap logo = t.logoUrl != null ? ImageCache.get(t.logoUrl, this::imageArrived, null, 500) : null;
        if (logoWaiting(t)) {
            // Its logo is on the way: the space stays empty rather than flashing the name in type first.
        } else if (logo != null && logo.getWidth() > 0 && !Frost.darkLogo(logo)) {   // the film's own title art; a dark one wouldn't read, so its name is set instead
            float sc = Math.min(Math.min(620 * k, wide) / logo.getWidth(), 170 * k / logo.getHeight());
            float lw = logo.getWidth() * sc, lh = logo.getHeight() * sc;
            c.drawBitmap(logo, null, new RectF(x, 650 * k + low - lh, x + lw, 650 * k + low), nImg);
        } else {
            fitSize(hTitle, t.title, wide * 0.97f, 88, 48);
            c.drawText(fit(t.title, hTitle, wide), x, 650 * k + low, hTitle);
            hTitle.setTextSize(88 * k);
        }
        List<String> meta = new ArrayList<>();
        if (t.service != null) meta.add(t.service.name);
        meta.add(newLabel(t));
        if (t.year > 0) meta.add(String.valueOf(t.year));
        if (!t.genres.isEmpty()) meta.add(t.genres.replace(",", " ·"));
        if (t.runtime > 0) meta.add(t.runtime >= 60 ? t.runtime / 60 + " h " + t.runtime % 60 + L10n.t(" min") : t.runtime + L10n.t(" min"));
        c.drawText(fit(TextUtils.join(" · ", meta), hMeta, wide), x, 698 * k + low, hMeta);
        TextPaint tp = new TextPaint(hOver);
        ov = wholeWords(ov, tp, Math.round(wide), 2);
        StaticLayout sl = StaticLayout.Builder.obtain(ov, 0, ov.length(), tp, Math.round(wide)).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4 * k, 1).build();
        c.save();
        c.translate(x, 712 * k);
        sl.draw(c);
        c.restore();
        float gap = 16 * k, wW = hBtn.measureText(L10n.t("Watch now")) + 112 * k, mW = hBtn.measureText(L10n.t("More info")) + 112 * k;
        RectF w = new RectF(x, 812 * k, x + wW, 884 * k), m = new RectF(w.right + gap, 812 * k, w.right + gap + mW, 884 * k);
        heroButton(c, w, L10n.t("Watch now"), 1, true, on && actSel == 0);
        heroButton(c, m, L10n.t("More info"), 2, false, on && actSel == 1);
    }

    /** Asks once for the title's logo; until it comes (or if it has none) the name is set in type. */
    private void ensureLogo(@Nullable Tmdb.Title t) {
        if (t == null) return;
        if (!t.logoAsked) {
            t.logoAsked = true;
            t.logoAskedAt = SystemClock.uptimeMillis();
            if (t.logoUrl != null) t.logoDone = true;
            else {
                Context ctx = getContext();
                work.execute(() -> {
                    t.logoUrl = Catalog.logo(ctx, t);
                    t.logoDone = true;
                    postInvalidate();
                });
            }
        }
        if (t.logoUrl != null) ImageCache.get(t.logoUrl, this::imageArrived, null, 500);   // fetched ahead, so it's ready when shown
    }

    /** True while a logo may still arrive: for at most 2.5 seconds, then the name is set in type after all. */
    private boolean logoWaiting(Tmdb.Title t) {
        if (SystemClock.uptimeMillis() - t.logoAskedAt > 2500) return false;
        if (!t.logoDone) return true;
        return t.logoUrl != null && ImageCache.get(t.logoUrl, this::imageArrived, null, 500) == null;
    }

    /** Watch now (neon) or More info (glass), with a play (1) or info (2) mark; selected, it lifts a little. */
    private void heroButton(Canvas c, RectF r, String label, int mark, boolean primary, boolean selected) {
        float rad = r.height() / 2;
        c.save();
        if (selected) c.scale(FOCUS_SCALE, FOCUS_SCALE, r.centerX(), r.centerY());
        if (primary) Frost.hero(c, r, rad, selected, k);   // Watch now: the main button's jewel, as everywhere
        else if (selected) Frost.plate(c, r, rad, k);       // More info: the clear glass of everything selected
        else Frost.smoked(c, r, rad, k);
        float tx = r.left + (mark != 0 ? 74 : 36) * k;
        hBtn.setAlpha(255);
        if (mark == 1) {
            Path pl = new Path();
            pl.moveTo(r.left + 36 * k, r.centerY() - 14 * k);
            pl.lineTo(r.left + 60 * k, r.centerY());
            pl.lineTo(r.left + 36 * k, r.centerY() + 14 * k);
            pl.close();
            gFill.setShader(null);
            gFill.setColor(Color.WHITE);
            c.drawPath(pl, gFill);
        } else if (mark == 2) {
            float ix = r.left + 50 * k, iy = r.centerY();
            gLine.setStrokeWidth(2.4f * k);
            gLine.setColor(0xFFF4F4F1);
            c.drawCircle(ix, iy, 14 * k, gLine);
            c.drawLine(ix, iy - 1 * k, ix, iy + 7 * k, gLine);
            gFill.setShader(null);
            gFill.setColor(0xFFF4F4F1);
            c.drawCircle(ix, iy - 7 * k, 1.8f * k, gFill);
        }
        c.drawText(label, tx, r.centerY() + hBtn.getTextSize() * 0.36f, hBtn);
        c.restore();
    }


    private void poster(Canvas c, Tmdb.Title t, RectF p, float rad, boolean chosen, boolean rowOn) {
        c.save();
        if (chosen && rowOn) {
            c.translate(0, -4 * k);
            c.scale(1.045f, 1.045f, p.centerX(), p.centerY());
            gFill.setShader(null);
            gFill.setColor(0xFF000000);
            gFill.setShadowLayer(18 * k, 0, 10 * k, 0x80000000);
            c.drawRoundRect(p, rad, rad, gFill);
            gFill.clearShadowLayer();
        }
        Bitmap pi = ImageCache.get(t.poster(), this::imageArrived, null, 320);   // decoded at about its shown size
        if (pi != null) coverImage(c, pi, p, rad, 255);
        else smoked(c, p, rad, 110);
        if (chosen && rowOn) Frost.sheen(c, p, rad, k);
        else {
            gFill.setShader(null);
            gFill.setColor(chosen ? 0x0F060810 : 0x38060810);
            c.drawRoundRect(p, rad, rad, gFill);
        }
        c.restore();
    }

    /** A glowing progress line: a dim track, the neon fill, a small bright knob. */
    private void neonProgress(Canvas c, float x, float y, float w, float frac, int a, int b) {
        gFill.setShader(null);
        gFill.setColor(0x38C4C4C4);
        c.drawRoundRect(x, y - 3 * k, x + w, y + 3 * k, 3 * k, 3 * k, gFill);
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(x, 0, x + w, 0, a, b, Shader.TileMode.CLAMP));
        gFill.setShadowLayer(10 * k, 0, 0, (b & 0x00FFFFFF) | 0xCC000000);
        c.drawRoundRect(x, y - 3 * k, x + w * frac, y + 3 * k, 3 * k, 3 * k, gFill);
        gFill.setShader(null);
        if (frac > 0) {
            gFill.setColor(Color.WHITE);
            c.drawCircle(x + w * frac, y, 6 * k, gFill);
        }
        gFill.clearShadowLayer();
    }

    /** The largest size (from max down to min, design px) at which the text fits the room. */
    private void fitSize(Paint p, String text, float room, float max, float min) {
        p.setTextSize(max * k);
        float w = p.measureText(text == null ? "" : text);
        if (w > room) p.setTextSize(Math.max(min * k, max * k * room / w));
    }

    // ---- without a drive -------------------------------------------------------------------------------
    // Most TVs have no drive plugged in, so My Movies and My Music never stand empty: My Movies becomes My List
    // (the films and series you saved with 🔖, opened on your apps) and My Music opens your music app (YouTube Music,
    // Spotify…, else the Random Music picks). Continue Watching, with nothing half-watched, offers the next title on
    // your list.

    private boolean noFilms() {
        return !driveIn && filmCount <= 0;
    }

    private boolean noSongs() {
        List<MyMusic.Song> s = MyMusic.lastRead();
        return s == null || s.isEmpty();
    }

    private List<Library.Entry> myList = new ArrayList<>();
    private long myListAt;

    /** Your Want to Watch titles, newest first (read again at most every few seconds). */
    private List<Library.Entry> myList() {
        long now = SystemClock.uptimeMillis();
        if (now - myListAt > 5000) {
            myListAt = now;
            List<Library.Entry> l = new ArrayList<>();
            for (Library.Entry e : Library.all(getContext())) if (e.status == Library.WANT) l.add(e);
            myList = l;
        }
        return myList;
    }

    /** My Watchlist's See all: every title as a card, by where it was saved, with Remove titles. */
    private void openMyList() {
        openList(3, 0);
        wlEdit = false;
        wlOnEdit = false;
        wlOnEdit = watchlist().isEmpty();
    }

    private boolean wlEdit, wlOnEdit;   // removing titles; the focus is on the Remove titles button

    /** Off the watchlist: TV Town's own titles lose Want to Watch; an app's are hidden here (the app keeps its own list). */
    private void wlRemove(WItem w) {
        if (w.entry != null) {
            Library.setStatus(getContext(), MustWatchView.fromEntry(w.entry), Library.NONE);
            myListAt = 0;
        } else if (w.app != null && w.app.title != null) {
            java.util.Set<String> gone = new java.util.HashSet<>(prefs.getStringSet("wl_hidden", java.util.Collections.emptySet()));
            gone.add(w.app.title.trim().toLowerCase(Locale.ROOT));
            prefs.edit().putStringSet("wl_hidden", gone).apply();
        }
    }

    /** The watchlist: one row of cards (picture, name, where it's from) and Remove titles at the top right. */
    private void glassWishGrid(Canvas c) {
        List<WItem> items = watchlist();
        stripPanel(c, L10n.t("My Watchlist"), items.size(), items.isEmpty() ? L10n.t("Browse Discover") : wlEdit ? L10n.t("Done") : L10n.t("Remove titles"), wlOnEdit);
        for (int i = listTop; i < items.size() && i < listTop + STRIP_SHOWN + 1; i++) {
            WItem wi = items.get(i);
            RectF pic = stripCard(c, i, i == listSel, wlEdit ? 0xFFFF5A5A : 0);
            String img = wi.entry != null ? wi.entry.poster : wi.app.image;
            thumb(c, img != null && !img.isEmpty() ? ImageCache.get(img, this::imageArrived, null, Math.round(pic.width())) : null, pic, WheelArt.Glyph.FILM);
            if (wlEdit) {   // a round ✕ on each card: OK takes it off
                gFill.setShader(null);
                gFill.setColor(0xCC000000);
                c.drawCircle(pic.right - 30 * k, pic.top + 30 * k, 22 * k, gFill);
                Paint xp = nk.inter(26, 700, 0, Color.WHITE);
                c.drawText("\u2715", pic.right - 30 * k - xp.measureText("\u2715") / 2, pic.top + 39 * k, xp);
            }
            String ln;
            if (wi.entry != null) {
                Library.Entry e = wi.entry;
                ln = ("tv".equals(e.type) ? L10n.t("Series") : L10n.t("Film")) + (e.year > 0 ? " · " + e.year : "") + (e.imdb > 0 ? String.format(Locale.US, " · ★ %.1f", e.imdb) : "") + "  ·  TV Town";
            } else ln = source(wi.app);
            stripText(c, pic, wi.title(), ln);
        }
    }

    /** Up to Remove titles, Down back to the cards, Left and Right along them. */
    private boolean wishKey(int keyCode) {
        int n = watchlist().size();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) wlOnEdit = true;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (n > 0) wlOnEdit = false;
        } else if (wlOnEdit) return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        else return stripKey(keyCode, n);
        invalidate();
        return true;
    }

    private void wishSelect() {
        if (wlOnEdit) {
            if (watchlist().isEmpty()) {   // nothing to remove: the button finds something to add instead
                if (listener != null) listener.onDiscover();
                return;
            }
            wlEdit = !wlEdit;
            if (wlEdit && !watchlist().isEmpty()) wlOnEdit = false;   // straight onto the cards to take some off
            invalidate();
            return;
        }
        List<WItem> items = watchlist();
        if (listSel >= items.size()) return;
        WItem w = items.get(listSel);
        if (wlEdit) {   // stays open, on the next title
            wlRemove(w);
            if (watchlist().isEmpty()) {
                wlOnEdit = true;
                wlEdit = false;
            }
            invalidate();
            return;
        }
        listAll = 0;
        if (w.entry != null) TitleActivity.open(getContext(), MustWatchView.fromEntry(w.entry));
        else openContinue(w.app);
    }

    /** My Music without a drive: the music app (the one playing, else the first installed), else Random Music. */
    private void openMusicApp() {
        android.media.session.MediaController om = otherMusic();
        String pkg = om != null ? om.getPackageName() : null;
        if (pkg == null) {
            String[] app = TvTools.musicApp(getContext());
            pkg = app != null ? app[0] : null;
        }
        Intent in = null;
        if (pkg != null) {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            in = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (in == null) in = pm.getLaunchIntentForPackage(pkg);
        }
        if (in != null) getContext().startActivity(in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else openRandom(2);
    }

    /** A title from My List among Continue's places, or how to fill the list. */
    private void upNext(Canvas c, RectF p, @Nullable Library.Entry e) {
        RectF th = new RectF(p.left, p.top, p.right, p.top + 150 * k);
        float room = p.width();
        if (e == null) {
            thumb(c, null, th, WheelArt.Glyph.FILM);
            c.drawText(fit(L10n.t("Save something for tonight"), hCard, room), p.left, p.bottom - 44 * k, hCard);
            c.drawText(fit(L10n.t("Choose + My List on any film's page"), hSub, room), p.left, p.bottom - 8 * k, hSub);
            return;
        }
        Bitmap img = e.poster != null ? ImageCache.get(e.poster, this::imageArrived, null, 640) : null;
        thumb(c, img, th, WheelArt.Glyph.FILM);
        c.drawText(fit(e.title, hCard, room), p.left, p.bottom - 44 * k, hCard);
        String line = ("tv".equals(e.type) ? L10n.t("Series") : L10n.t("Film")) + (e.year > 0 ? " · " + e.year : "")
                + (e.imdb > 0 ? String.format(Locale.US, " · ★ %.1f", e.imdb) : "");
        c.drawText(fit(line, hSub, room), p.left, p.bottom - 8 * k, hSub);
    }

    /**
     * No cover anywhere (not from the app, TMDB or JustWatch): a card of its own in the app's colour, so it never
     * looks empty. The name large; on a tall card it sits in the middle, on a wide one its words are drawn over it.
     */
    private void blankCover(Canvas c, RectF p, float rad, Cont ct, boolean wide) {
        String app = source(ct);
        int tint = tintFor(ct.pkg != null && ct.video == null ? ct.pkg + " " + app : app);
        int deep = Color.rgb(Color.red(tint) / 4 + 8, Color.green(tint) / 4 + 8, Color.blue(tint) / 4 + 12);
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(p.left, p.top, p.right, p.bottom, (tint & 0x00FFFFFF) | 0xB0000000, deep | 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(p, rad, rad, gFill);
        gFill.setShader(new android.graphics.RadialGradient(p.left + p.width() * 0.25f, p.top + p.height() * 0.2f, Math.max(p.width(), p.height()) * 0.7f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));   // a soft light from the top corner
        c.drawRoundRect(p, rad, rad, gFill);
        gFill.setShader(null);
        if (wide) return;   // the wide card's own name and app are drawn over it
        Paint np = nk.inter(p.width() > 260 * k ? 30 : 25, 800, -0.01f, Color.WHITE);
        np.setTextAlign(Paint.Align.CENTER);
        List<String> lines = wrapLines(ct.title == null ? "" : ct.title, np, p.width() - 36 * k, 4);
        float lh = np.getTextSize() * 1.18f, y = p.centerY() - (lines.size() - 1) * lh / 2 + np.getTextSize() * 0.35f;
        for (String l : lines) {
            c.drawText(l, p.centerX(), y, np);
            y += lh;
        }
        Paint ap = nk.inter(19, 600, 0.02f, 0xCCFFFFFF);
        ap.setTextAlign(Paint.Align.CENTER);
        c.drawText(fit(app, ap, p.width() - 32 * k), p.centerX(), p.bottom - 26 * k, ap);
    }

    /** Words over at most `max` lines of `width`, the last cut short with an ellipsis. */
    static List<String> wrapLines(String text, Paint paint, float width, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
            if (paint.measureText(tryLine) <= width || line.length() == 0) {
                line.setLength(0);
                line.append(tryLine);
                continue;
            }
            out.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
            if (out.size() == max - 1) {   // the rest on the last line
                StringBuilder rest = new StringBuilder(line);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                line.setLength(0);
                line.append(android.text.TextUtils.ellipsize(rest, new android.text.TextPaint(paint), width, android.text.TextUtils.TruncateAt.END));
                break;
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** A Continue card: a wide picture with its progress along the foot, then the name and the time left. */
    /** A shelf cover: the title's tall poster (found by name when the app gave only a wide picture), and how far in. */
    private void shelfPoster(Canvas c, RectF p, Cont ct) {
        float rad = 18 * k;
        String url = ct.poster;
        if (url == null && ct.title != null && ct.video == null || url == null && ct.video != null && ct.title != null) {
            String[] art = artByName(ct.title);
            if (art != null && art.length > 2) url = art[2];
        }
        Bitmap img = url != null ? ImageCache.get(url, this::imageArrived, null, Math.round(p.width())) : null;
        if (img == null && ct.image != null && ImageCache.canShow(ct.image)) img = ImageCache.get(ct.image, this::imageArrived, null, 640);
        if (img != null) coverImage(c, img, p, rad, 255, 0.2f);   // a wide picture is cropped to the middle
        else blankCover(c, p, rad, ct, false);
        if (ct.frac > 0) {   // how far in: a thin violet-to-pink line along the foot
            c.save();
            android.graphics.Path round = new android.graphics.Path();
            round.addRoundRect(p, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(round);
            float h = 7 * k, w = p.width() * Math.min(1, ct.frac);
            gFill.setShader(null);
            gFill.setColor(0x66000000);
            c.drawRect(p.left, p.bottom - h, p.right, p.bottom, gFill);
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(p.left, 0, p.left + Math.max(w, 1), 0, 0xFF8A6BFF, 0xFFD9468F, Shader.TileMode.CLAMP));
            c.drawRect(p.left, p.bottom - h, p.left + w, p.bottom, gFill);
            gFill.setShader(null);
            c.restore();
        }
    }

    private void homeWatch(Canvas c, RectF p, @Nullable Cont ct) {
        float rad = 16 * k;
        Bitmap img = ct.image != null ? ImageCache.get(ct.image, this::imageArrived, null, 640) : null;
        if (img == null && !ImageCache.canShow(ct.image) && inShelf()) {   // the app gave none we can read: the fallback
            String[] art = artByName(ct.title);
            if (art != null && art[0] != null) img = ImageCache.get(art[0], this::imageArrived, null, 640);
        }
        if (img != null) coverImage(c, img, p, rad, 255, 0.2f);
        else blankCover(c, p, rad, ct, true);
        c.save();   // a dark fade up from the foot, so the words read on any picture
        android.graphics.Path round = new android.graphics.Path();
        round.addRoundRect(p, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(round);
        gFill.setColor(Color.WHITE);
        gFill.setShader(new LinearGradient(0, p.top + p.height() * 0.35f, 0, p.bottom, 0x00000000, 0xEB000000, Shader.TileMode.CLAMP));
        c.drawRect(p, gFill);
        gFill.setShader(null);
        if (ct.frac > 0) {   // how far in: a thin violet-to-pink line right along the foot
            float h = 6 * k, w = p.width() * Math.min(1, ct.frac);
            gFill.setColor(0x33FFFFFF);
            c.drawRect(p.left, p.bottom - h, p.right, p.bottom, gFill);
            gFill.setColor(Color.WHITE);
            gFill.setShader(new LinearGradient(p.left, 0, p.left + Math.max(w, 1), 0, 0xFF8A6BFF, 0xFFD9468F, Shader.TileMode.CLAMP));
            c.drawRect(p.left, p.bottom - h, p.left + w, p.bottom, gFill);
            gFill.setShader(null);
        }
        c.restore();
        String app = source(ct);
        appTag(c, p.left + 12 * k, p.top + 12 * k, app, tintFor(ct.pkg != null && ct.video == null ? ct.pkg + " " + app : app));
        float room = p.width() - 36 * k;
        c.drawText(fit(ct.title, hCard, room), p.left + 18 * k, p.bottom - 52 * k, hCard);
        String line = ct.line != null ? ct.line.replace(" · " + app, "").replace(app, "").trim() : "";
        if (line.endsWith("·")) line = line.substring(0, line.length() - 1).trim();
        c.drawText(fit(line, hSub, room), p.left + 18 * k, p.bottom - 22 * k, hSub);
    }

    // Without a drive (or nothing from it): what's playing in a music app (YouTube Music, Spotify…), else that app.
    private android.media.session.MediaController otherMusic;
    private long otherAt;

    @Nullable
    private android.media.session.MediaController otherMusic() {
        long now = SystemClock.uptimeMillis();
        if (now - otherAt > 3000) {
            otherAt = now;
            android.media.session.MediaController m = TvTools.nowPlaying(getContext());
            otherMusic = m != null && !getContext().getPackageName().equals(m.getPackageName()) ? m : null;
        }
        return otherMusic;
    }








    /**
     * The neon waveform (as in the reference picture): thin bars mirrored about a glowing centre line, shading
     * violet, pink, green, orange, cyan along it; two thin flowing waves through them. Moves while music plays.
     */
    private final Path[] waves = {new Path(), new Path()};


    private final java.util.Map<String, Boolean> lyricsHome = new java.util.HashMap<>();

    private void homeButtons(Canvas c, boolean live) {
        for (int i : tilesOn()) {
            RectF r = homeTile(i);
            boolean f = live && row == Row.RANDOM && randSel == i;
            c.save();
            if (f) c.scale(FOCUS_SCALE, FOCUS_SCALE, r.centerX(), r.centerY());
            String name = i == 1 && noFilms() ? L10n.t("My List") : i == 2 && noSongs() && !driveIn ? L10n.t("Music") : TILE_TEXT[i][0];
            icon(c, TILE_GLYPH[i], 50, r.centerX(), r.top + 52 * k, 255);
            String label = fit(name, hAppName, r.width() - 16 * k);
            c.drawText(label, r.centerX() - hAppName.measureText(label) / 2, r.top + 108 * k, hAppName);
            c.restore();
        }
    }

    /** An app's neon wordmark: its name in its brand colour, glowing, on clear glass. */
    private static final String[][] WORDMARKS = {{"netflix", "NETFLIX", "#FF2A36", "#1C0709"}, {"amazon", L10n.t("prime video"), "#3DB8FF", "#071A33"}, {"youtube", L10n.t("YouTube"), "#FF4D5E", "#1E0A10"},
            {"hotstar", L10n.t("JioHotstar"), "#B49AFF", "#150B33"}, {"apple", L10n.t("Apple TV"), "#F4F6FF", "#10141F"}, {"videolan", "VLC", "#FFA040", "#241205"},
            {"zee5", "ZEE5", "#D58BFF", "#1A0A26"}, {"sonyliv", L10n.t("SonyLIV"), "#59D8FF", "#061C26"}, {"disney", L10n.t("Disney+"), "#59D8FF", "#071433"},
            {"spotify", L10n.t("Spotify"), "#2BEA88", "#06200F"}};

    private final java.util.Map<Bitmap, Integer> brandColours = new java.util.HashMap<>();

    private void homeApps(Canvas c, boolean live) {
        int[] slots = appSlots();
        int at = appSlot(slots);
        boolean inRow = live && row == Row.APPS;
        for (int j = 0; j < slots.length; j++) appTile(c, items.get(slots[j]), dockCell(j), inRow && j == at);
    }

    // The icon pack's picture per app (Customize → Home → Icon pack), loaded once off the main thread.
    private final java.util.Map<String, Bitmap> packIcons = new java.util.HashMap<>();
    private volatile int packGen;

    @Nullable
    private Bitmap packIcon(Apps.App a) {
        Context c = getContext().getApplicationContext();
        if (IconPacks.chosen(c).isEmpty()) return null;
        synchronized (packIcons) {
            if (packIcons.containsKey(a.pkg)) return packIcons.get(a.pkg);
            packIcons.put(a.pkg, null);
        }
        new Thread(() -> {
            Bitmap b = IconPacks.picture(c, a.pkg, a.component.getClassName());
            synchronized (packIcons) {
                packIcons.put(a.pkg, b);
            }
            packGen++;
            postInvalidate();
        }, "iconpack").start();
        return null;
    }

    /** One place in the dock: the app's mark, all at the same visual size, and its name below. */
    private void appTile(Canvas c, Item it, RectF r, boolean f) {
        c.save();
        if (f) c.scale(FOCUS_SCALE, FOCUS_SCALE, r.centerX(), r.centerY());
        float cx = r.centerX(), cy = r.top + 52 * k;
        if (it.kind == ALL_APPS) {
            icon(c, WheelArt.Glyph.GRID, 50, cx, cy, 255);
        } else if (it.app != null && packIcon(it.app) != null) {   // the icon pack's picture, at the marks' size
            Bitmap pi = packIcon(it.app);
            float ph = 64 * k, pw = Math.min(150 * k, ph * pi.getWidth() / (float) pi.getHeight());
            if (pw >= 150 * k) ph = pw * pi.getHeight() / (float) pi.getWidth();
            c.drawBitmap(pi, null, new RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2), nImg);
        } else if (it.app != null) {
            Bitmap mark = LineIcon.appMark(it.app.pkg, it.name, 124 * k, 60 * k, hAppName.getTypeface());
            float sc = Math.min(1, (float) Math.sqrt(2600 * k * k / (mark.getWidth() * (float) mark.getHeight())));
            float mw = mark.getWidth() * sc, mh = mark.getHeight() * sc;
            c.drawBitmap(mark, null, new RectF(cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2), nImg);
        } else if (it.disc != null) c.drawBitmap(it.disc, null, new RectF(cx - 28 * k, cy - 28 * k, cx + 28 * k, cy + 28 * k), nImg);
        String label = fit(it.kind == ALL_APPS ? L10n.t("All apps") : it.name, hAppName, r.width() - 16 * k);
        c.drawText(label, cx - hAppName.measureText(label) / 2, r.top + 108 * k, hAppName);
        c.restore();
    }

    /** Arrows: to the neighbour on screen. */
    private boolean glassKey(int keyCode) {
        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP, down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT, right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!up && !down && !left && !right) return false;
        int[] slots = appSlots();
        int at = appSlot(slots);
        switch (row) {
            case TOP:
                if (navSel == NAV_CRICKET && cricketBar == null) navSel = 0;   // the match has gone
                if (navSel == NAV_SONG && songBar == null) navSel = 0;         // the song has stopped
                if (left || right) {   // round at the ends; the song and the score, when there are, come first
                    List<Integer> ord = new ArrayList<>();
                    if (songBar != null) ord.add(NAV_SONG);
                    if (cricketBar != null) ord.add(NAV_CRICKET);
                    for (int o = 0; o < 4; o++) ord.add(o);
                    int[] order = new int[ord.size()];
                    for (int o = 0; o < order.length; o++) order[o] = ord.get(o);
                    int at2 = 0;
                    for (int i = 0; i < order.length; i++) if (order[i] == navSel) at2 = i;
                    navSel = order[Math.floorMod(at2 + (left ? -1 : 1), order.length)];
                } else if (up) {   // round to the bottom: the dock
                    setRow(Row.APPS);
                } else {
                    actSel = 0;
                    setRow(Row.ACTIONS);
                }
                return true;
            case ACTIONS:
                if (up) setRow(Row.TOP);
                else if (down) setRow(Row.APPS);
                else if (left) {
                    if (actSel == 1) actSel = 0;
                    else heroStep(-1);
                } else if (actSel == 0) actSel = 1;   // More info works without a TMDB key too (from the other sources)
                else heroStep(1);
                return true;
            case NEW: {
                int n = feed().size();
                if (left) {
                    if (posSel == 0) {
                        actSel = 1;
                        setRow(Row.ACTIONS);
                    } else posSel--;
                } else if (right) {
                    if (posSel < n - 1) posSel++;
                } else if (up) setRow(Row.TOP);
                else coverDown();   // the next row in the corner: Continue watching, My Watchlist, then the dock
                featSel = posSel;
                heroTurnedAt = SystemClock.uptimeMillis();
                return true;
            }
            case APPS:   // the dock is one row: its apps, then its four buttons
                if (up) {
                    actSel = 0;
                    setRow(Row.ACTIONS);
                } else if (down) {   // the covers in the bar's place: from an app with something half-watched, its titles first
                    contApp = dockAppWithConts();
                    openCovers();
                } else if (left) {
                    if (at > 0 && slots.length > 0) setFront(slots[at - 1]);
                    else {
                        randSel = tilesOn()[tilesOn().length - 1];
                        setRow(Row.RANDOM);
                    }
                } else if (right) {
                    if (at < slots.length - 1) setFront(slots[at + 1]);
                    else {
                        randSel = tilesOn()[0];
                        setRow(Row.RANDOM);
                    }
                }
                return true;
            case CONT:
                contAt = Math.min(contAt, contLast());
                if (up) setRow(Row.APPS);   // the app bar again
                else if (down) coverDown();
                else if (right) contAt = Math.min(contLast(), contAt + 1);
                else if (left) contAt = Math.max(0, contAt - 1);
                return true;
            case LIST:
                if (up) setRow(Row.APPS);
                else if (down) coverDown();
                else if (right) listAtW = Math.min(watchlist().size(), listAtW + 1);   // past the last: + Add more
                else if (left) listAtW = Math.max(0, listAtW - 1);
                return true;
            case NOTHING:
                setRow(Row.APPS);
                return true;
            case RANDOM:
                if (tilePos(randSel) < 0) randSel = 3;   // switched off while chosen: Discover
                if (up) {
                    actSel = 0;
                    setRow(Row.ACTIONS);
                } else if (down) {
                    contApp = null;
                    openCovers();
                } else if (left) {
                    if (tilePos(randSel) > 0) randSel = tilesOn()[tilePos(randSel) - 1];
                    else {
                        if (slots.length > 0) setFront(slots[slots.length - 1]);
                        setRow(Row.APPS);
                    }
                } else if (right) {
                    if (tilePos(randSel) < tilesOn().length - 1) randSel = tilesOn()[tilePos(randSel) + 1];
                    else {
                        if (slots.length > 0) setFront(slots[0]);
                        setRow(Row.APPS);
                    }
                }
                return true;
            default:
                setRow(Row.APPS);
                return true;
        }
    }

    /** OK on the neon glass Home, for the rows that differ from the classic look; false for the others. */
    private boolean glassSelect() {
        List<Tmdb.Title> f = feed();
        Tmdb.Title t = f.isEmpty() ? null : f.get(Math.max(0, Math.min(f.size() - 1, featSel)));
        switch (row) {
            case TOP:
                if (navSel == NAV_SONG) {
                    if (songBar != null) listener.onMusicPlayer();
                    else navSel = 0;
                } else if (navSel == NAV_CRICKET) {
                    if (cricketBar != null) openCricket();
                    else navSel = 0;
                } else if (navSel == 0) askSearch();
                else if (navSel == 2) openSettings();   // Customize TV Town
                else topAction(topButton(navSel));       // your buttons: Inputs and the TV's settings unless changed
                return true;
            case ACTIONS:
                if (t != null) {
                    if (actSel == 0) listener.onPlay(t);
                    else openInfo(t);
                } else if (!Tmdb.available()) openKeySetup();
                return true;
            case NEW:
                if (t != null) listener.onPlay(t);
                return true;
            case CONT:
                if (contAt < contRow().size()) openContDetails(contRow().get(contAt));
                else if (conts.isEmpty() && !Perms.tvListings(getContext()) && getContext() instanceof android.app.Activity)
                    Perms.askTvListings((android.app.Activity) getContext(), true);
                return true;
            case NOTHING:
                if (listener != null) listener.onDiscover();
                return true;
            case LIST: {
                List<WItem> wl = watchlist();
                if (listAtW >= wl.size()) {   // + Add more: find something in Discover
                    if (listener != null) listener.onDiscover();
                    return true;
                }
                else if (wl.get(listAtW).entry != null) TitleActivity.open(getContext(), MustWatchView.fromEntry(wl.get(listAtW).entry));
                else openContinue(wl.get(listAtW).app);
                return true;
            }
            case NOWP: {
                MusicPlayer mp = MusicPlayer.get(getContext());
                List<MyMusic.Song> ll = listenList();
                if (!mp.playing() && ll.size() > 1) {   // nothing playing: the songs to pick up, to choose from
                    openList(2, listenShown(ll));
                    return true;
                }
                if (mp.current() == null && suggested == null) {   // no drive music: the music app
                    android.media.session.MediaController om = otherMusic();
                    if (om != null) om.getTransportControls().play();
                    openMusicApp();
                    return true;
                }
                if (mp.current() != null) {
                    if (!mp.playing()) mp.toggle();
                } else if (suggested != null) {
                    List<MyMusic.Song> folder = new ArrayList<>();
                    List<MyMusic.Song> all = MyMusic.lastRead();
                    if (all != null) for (MyMusic.Song x : all) if (x.folder != null && x.folder.equals(suggested.folder)) folder.add(x);
                    int at = -1;   // the song on the card, found by its file (the list may hold fresh copies)
                    for (int i = 0; i < folder.size(); i++) if (folder.get(i).key().equals(suggested.key())) at = i;
                    if (at < 0) {
                        folder.add(0, suggested);
                        at = 0;
                    }
                    mp.play(folder, at, false, suggested.folder);
                }
                listener.onMusicPlayer();   // straight to the player, the song playing
                return true;
            }
            case RANDOM:
                if (randSel == 0) openRandom(0);
                else if (randSel == 1) {
                    if (noFilms()) openMyList();
                    else listener.onMyMovies();
                } else if (randSel == 2) {
                    if (noSongs() && !driveIn) openMusicApp();
                    else listener.onMyMusic();
                }
                else listener.onDiscover();
                return true;
            default:
                return false;
        }
    }

    // ---- Continue Watching and Continue Listening ------------------------------------------------------
    // Each card shows one at a time and moves on by itself every few seconds; OK lists them (at most five) in a
    // pop-up to choose from. Left and Right move between the two cards.

    private static final int LIST_MAX = 5;
    private int listTop;          // the first row showing

    /** Where a Continue item plays: My Movies for the drive, else its app's name. */
    private String source(Cont c) {
        if (c.video != null || c.pkg == null) return L10n.t("My Movies");
        String a = c.app != null && !c.app.isEmpty() ? c.app : appLabel(getContext(), c.pkg);
        return a.isEmpty() ? L10n.t("Apps") : a;
    }

    private static final long ROTATE_MS = 6000;

    /** The song the Continue Listening card shows now: the one playing, else each recent one in turn. */
    private int listenShown(List<MyMusic.Song> l) {
        if (l.size() <= 1 || listAll != 0 || MusicPlayer.get(getContext()).current() != null) return 0;
        return (int) ((System.currentTimeMillis() / ROTATE_MS + 2) % l.size());   // a little out of step with the films
    }

    /** Songs to pick up: the one playing, then those played lately (still on the drive), else a suggestion. At most five. */
    private List<MyMusic.Song> listenList() {
        List<MyMusic.Song> out = new ArrayList<>();
        MusicPlayer mp = MusicPlayer.get(getContext());
        if (mp.current() != null) out.add(mp.current());
        List<MyMusic.Song> all = MyMusic.lastRead();
        if (all != null && !all.isEmpty()) {
            Map<String, MyMusic.Song> byKey = new HashMap<>();
            for (MyMusic.Song x : all) byKey.put(x.key(), x);
            for (String key : MusicPlayer.recent(getContext())) {
                MyMusic.Song x = byKey.get(key);
                if (x != null && (out.isEmpty() || !out.get(0).key().equals(key))) out.add(x);
                if (out.size() >= LIST_MAX) break;
            }
        }
        if (out.isEmpty() && suggestion() != null) out.add(suggestion());
        return out;
    }

    private void openList(int which, int at) {
        listAll = which;
        listSel = at;
        listTop = 0;
        invalidate();
    }

    /** The pop-up list (OK on a Continue card): up to five rows, Up and Down to choose, OK to play, Back to close. */

    /** See all for Continue watching: one row of cards (picture with progress, name, where) sliding sideways. */
    private void glassWatchGrid(Canvas c) {
        List<Cont> items = contRow();
        stripPanel(c, contApp != null && items != conts ? L10n.t("Continue on ") + source(items.get(0)) : L10n.t("Continue watching"), items.size(), null, false);
        for (int i = listTop; i < items.size() && i < listTop + STRIP_SHOWN + 1; i++) {
            Cont ct = items.get(i);
            RectF pic = stripCard(c, i, i == listSel, 0);
            thumb(c, ct.image != null ? ImageCache.get(ct.image, this::imageArrived, null, Math.round(pic.width())) : null, pic, WheelArt.Glyph.FILM);
            if (ct.frac > 0) {
                RectF strip = new RectF(pic.left + 10 * k, pic.bottom - 28 * k, pic.right - 10 * k, pic.bottom - 8 * k);
                gFill.setShader(null);
                gFill.setColor(0x99000000);
                c.drawRoundRect(strip, 10 * k, 10 * k, gFill);
                neonProgress(c, strip.left + 12 * k, strip.centerY(), strip.width() - 24 * k, Math.min(1, ct.frac), Frost.CYAN, Frost.CYAN);
            }
            String ln = ct.video == null && ct.pkg != null && !ct.line.startsWith(source(ct)) ? source(ct) + "  \u00B7  " + ct.line : ct.line;
            stripText(c, pic, ct.title, ln);
        }
    }

    // The See all pop-ups (Continue watching, My Watchlist): a low panel with one row of cards that slides sideways.
    private static final int STRIP_SHOWN = 4;   // whole cards in view; the next peeks in at the edge
    private static final float STRIP_W = 1760, STRIP_PAD = 40, STRIP_GAP = 24;
    private final RectF stripRect = new RectF();

    private float stripCardW() {
        return (STRIP_W - 2 * STRIP_PAD - STRIP_GAP * STRIP_SHOWN) / (STRIP_SHOWN + 0.3f);
    }

    /** The panel, its title and count (and a button on the right, when given); keeps the chosen card in view. */
    private void stripPanel(Canvas c, String title, int total, @Nullable String button, boolean buttonOn) {
        listSel = Math.max(0, Math.min(Math.max(0, total - 1), listSel));
        if (listSel < listTop) listTop = listSel;
        if (listSel >= listTop + STRIP_SHOWN) listTop = listSel - STRIP_SHOWN + 1;
        float cw = stripCardW(), ih = cw * 9 / 16, h = 120 + ih + 100 + 30;
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, 1, nImg);
        stripRect.set(gr((1920 - STRIP_W) / 2, (1080 - h) / 2, STRIP_W, h));
        RectF p = stripRect;
        Frost.panel(c, p, 40 * k, 1, k);
        Paint head = nk.inter(44, 500, -0.03f, Frost.INK), e2 = nk.inter(24, 500, 0, Frost.MUTE);
        c.drawText(title, p.left + 48 * k, p.top + 76 * k, head);
        if (total > 0) c.drawText((listSel + 1) + L10n.t(" of ") + total, p.left + 64 * k + head.measureText(title), p.top + 76 * k, e2);
        if (button != null) {
            Paint tp = nk.inter(26, 600, 0, Frost.INK);
            float bw = tp.measureText(button) + 92 * k;
            RectF eb = new RectF(p.right - 40 * k - bw, p.top + 30 * k, p.right - 40 * k, p.top + 90 * k);
            Frost.ghost(c, eb, 30 * k, buttonOn, k);
            icon(c, wlEdit ? WheelArt.Glyph.CHECK : listAll == 3 && watchlist().isEmpty() ? WheelArt.Glyph.TELESCOPE : WheelArt.Glyph.PENCIL, 26, eb.left + 36 * k, eb.centerY(), 255);
            c.drawText(button, eb.left + 62 * k, eb.centerY() + 9 * k, tp);
        }
        if (total == 0) {
            Paint e3 = nk.inter(28, 500, 0, Frost.MUTE);
            c.drawText(listAll == 3 ? L10n.t("Nothing saved yet. Choose + My List on any film's page") : L10n.t("Nothing half-watched"), p.left + 48 * k, p.top + 170 * k, e3);
        }
        c.save();
        c.clipRect(p.left + 8 * k, p.top, p.right - 8 * k, p.bottom);
    }

    /** Card i's picture place (its plate drawn when chosen); the caller fills it, then stripText. */
    private RectF stripCard(Canvas c, int i, boolean chosen, int plateColour) {
        float cw = stripCardW(), ih = cw * 9 / 16;
        float x = stripRect.left / k + STRIP_PAD + (i - listTop) * (cw + STRIP_GAP), y = stripRect.top / k + 120;
        if (chosen && !(listAll == 3 && wlOnEdit)) {
            RectF cell = gr(x - 10, y - 10, cw + 20, ih + 100);
            if (plateColour != 0) Frost.plate(c, cell, 28 * k, k, plateColour);
            else Frost.plate(c, cell, 28 * k, k);
        }
        return gr(x, y, cw, ih);
    }

    private void stripText(Canvas c, RectF pic, String title, String line) {
        Paint name = nk.inter(30, 600, -0.01f, Frost.INK), sub = nk.inter(23, 500, 0, Frost.MUTE);
        c.drawText(fit(title, name, pic.width()), pic.left, pic.bottom + 42 * k, name);
        c.drawText(fit(line, sub, pic.width()), pic.left, pic.bottom + 76 * k, sub);
    }

    /** Left and Right along the row of a See all pop-up. */
    private boolean stripKey(int keyCode, int n) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (listSel + 1 < n) listSel++;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (listSel > 0) listSel--;
        } else if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        invalidate();
        return true;
    }

    private void glassListAll(Canvas c) {
        if (listAll == 4) {
            glassAppsEditor(c);
            return;
        }
        if (listAll == 3 || listAll == 1) {
            int save = c.getSaveCount();
            if (listAll == 3) glassWishGrid(c);
            else glassWatchGrid(c);
            c.restoreToCount(save);
            return;
        }
        boolean watching = listAll == 1;
        List<MyMusic.Song> songs = watching ? null : listenList();
        List<Cont> items = watching ? conts : null;
        int total = watching ? items.size() : songs.size();
        if (total == 0 && !(watching && !conts.isEmpty())) {
            listAll = 0;
            return;
        }
        listSel = Math.max(0, Math.min(Math.max(0, total - 1), listSel));
        int shown = watching ? 5 : total;   // five rows at a time; the list scrolls
        if (listSel < listTop) listTop = listSel;
        if (listSel >= listTop + shown) listTop = listSel - shown + 1;
        int n = Math.min(shown, total - listTop);
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, 1, nImg);
        float tabsH = watching ? 76 : 0;
        float rowH = 124, w = 1180, h = 116 + tabsH + (watching ? shown : n) * rowH + 28;
        RectF p = gr((1920 - w) / 2, (1080 - h) / 2, w, h);
        Frost.panel(c, p, 40 * k, 1, k);
        float x = p.left + 20 * k, room = p.width() - 40 * k;
        Paint head = nk.inter(46, 500, -0.03f, Frost.INK);
        c.drawText(watching ? L10n.t("Continue watching") : L10n.t("Continue listening"), p.left + 48 * k, p.top + 76 * k, head);
        Paint rowName = nk.inter(34, 600, -0.016f, Frost.INK), rowLine = nk.inter(26, 500, 0, Frost.MUTE), pill = nk.inter(26, 600, 0, Frost.INK);
        for (int v = 0; v < n; v++) {
            int i = watching ? listTop + v : v;
            RectF r = new RectF(x, p.top + (112 + tabsH + v * rowH + 4) * k, x + room, p.top + (112 + tabsH + v * rowH + rowH - 4) * k);
            boolean f = i == listSel;
            String title, line;
            Bitmap img;
            float frac;
            Cont cont = watching ? items.get(i) : null;
            if (watching) {
                title = cont.title;
                line = cont.video == null && cont.pkg != null && !cont.line.startsWith(source(cont)) ? source(cont) + "  \u00B7  " + cont.line : cont.line;
                frac = cont.frac;
                img = cont.image != null ? ImageCache.get(cont.image, this::imageArrived, null, 320) : null;
            } else {
                MyMusic.Song s = songs.get(i);
                title = s.title;
                line = s.artist + (s.album.isEmpty() ? "" : " · " + s.album);
                frac = -1;
                img = MyMusic.cover(getContext(), s, this::imageArrived);
            }
            boolean canMark = watching && cont.video != null;
            float markW = watching ? 300 * k : 0;   // Mark watched has a column of its own, apart from the title
            RectF film = new RectF(r.left, r.top, r.right - markW, r.bottom);
            if (f && (listBtn == 0 || !canMark)) Frost.plate(c, film, 28 * k, k);
            float th = r.height() - 24 * k, tw = watching ? th * 16 / 9f : th;
            RectF pic = new RectF(r.left + 16 * k, r.top + 12 * k, r.left + 16 * k + tw, r.bottom - 12 * k);
            thumb(c, img, pic, watching ? WheelArt.Glyph.FILM : WheelArt.Glyph.NOTE);
            float tx = pic.right + 24 * k, tr = film.right - 90 * k - tx;
            c.drawText(fit(title, rowName, tr), tx, r.centerY() - 4 * k, rowName);
            c.drawText(fit(line, rowLine, tr), tx, r.centerY() + 32 * k, rowLine);
            if (frac > 0) neonProgress(c, tx, r.bottom - 8 * k, tr, frac, Frost.CYAN, Frost.CYAN);
            if (f && listBtn == 0) icon(c, WheelArt.Glyph.PLAY, 34, film.right - 48 * k, film.centerY(), 255);
            if (canMark) {   // always there, one press to the right of the film
                String word = L10n.t("Mark watched");
                RectF b = new RectF(film.right + 12 * k, r.centerY() - 34 * k, r.right - 8 * k, r.centerY() + 34 * k);
                boolean on = f && listBtn == 1;
                Frost.ghost(c, b, 34 * k, on, k);
                icon(c, WheelArt.Glyph.EYE, 28, b.left + 32 * k, b.centerY(), on ? 255 : f ? 220 : 140);
                pill.setAlpha(on ? 255 : f ? 225 : 130);
                c.drawText(fit(word, pill, b.width() - 66 * k), b.left + 56 * k, b.centerY() + 9 * k, pill);
            }
        }
    }
    private int listBtn;

    private boolean listAllKey(int keyCode) {
        if (listAll == 4) return appsKey(keyCode);
        if (listAll == 3) return wishKey(keyCode);
        if (listAll == 1) return stripKey(keyCode, contRow().size());
        int n = listenList().size();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            listSel = Math.floorMod(listSel + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1), Math.max(1, n));
        } else if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        invalidate();
        return true;
    }

    private void listAllSelect() {
        if (listAll == 4) {
            appsOk();
            return;
        }
        if (listAll == 3) {
            wishSelect();
            return;
        }
        int which = listAll, at = listSel;
        List<Cont> items = which == 1 ? contRow() : null;
        if (which == 1 && listBtn == 1 && at < items.size() && items.get(at).video != null) {   // Mark watched: stays open
            Context ctx = getContext();
            MyMovies.setSeen(ctx, items.get(at).video, true);
            conts.remove(items.get(at));
            saveConts(ctx, conts);
            listBtn = 0;
            if (conts.isEmpty()) listAll = 0;
            invalidate();
            findContinue();
            return;
        }
        listAll = 0;
        if (which == 1) {
            if (at < items.size()) openContDetails(items.get(at));
            return;
        }
        List<MyMusic.Song> l = listenList();
        if (at >= l.size()) return;
        MyMusic.Song pick = l.get(at);
        MusicPlayer mp = MusicPlayer.get(getContext());
        if (mp.current() != null && mp.current().key().equals(pick.key())) {
            if (!mp.playing()) mp.toggle();
        } else {   // its folder, from that song
            List<MyMusic.Song> folder = new ArrayList<>();
            List<MyMusic.Song> all = MyMusic.lastRead();
            if (all != null) for (MyMusic.Song x : all) if (x.folder != null && x.folder.equals(pick.folder)) folder.add(x);
            int i = -1;
            for (int j = 0; j < folder.size(); j++) if (folder.get(j).key().equals(pick.key())) i = j;
            if (i < 0) {
                folder.add(0, pick);
                i = 0;
            }
            mp.play(folder, i, false, pick.folder);
        }
        listener.onMusicPlayer();
    }

    /** OK in the sidebar. */
    private void openSide(int i) {
        switch (i) {
            case 1: listener.onDiscover(); return;
            case 2: listener.onMustWatch(); return;
            case 3: listener.onMyMovies(); return;
            case 4: listener.onMyMusic(); return;
            case 5: openInputs(); return;
            case SIDE_NOW: listener.onMyMusic(); return;
            case SIDE_SURPRISE: openRandom(0); return;
            default: setRow(Row.APPS);   // Home: already here
        }
    }




    private void drawTopBar(Canvas c) {
        Date now = new Date();
        float sh = 3 * k;
        timeText.set(timeFmt.format(now), timeP, 0xFFFFFFFF, sh);
        ampmText.set(ampmFmt.format(now).toLowerCase(Locale.ROOT), ampmP, 0xFFFFFFFF, sh);
        dateText.set(dateFormat().format(now).toUpperCase(Locale.ROOT), dateP, 0xD9FFFFFF, sh);
        float x = Theme.MARGIN * k, top = 56 * k;
        bmp.setAlpha(255);
        timeText.drawTopLeft(c, x, top, bmp);
        ampmText.drawBaseline(c, x + timeText.textWidth() + 6 * k, top + timeText.baseline(), bmp);
        dateText.drawTopLeft(c, x + 2 * k, top + timeText.textHeight() + 6 * k, bmp);
        float s = 31 * k, right = getWidth() - Theme.MARGIN * k, iy = 66 * k, step = s + 34 * k;
        boolean bar = row == Row.TOP && noOverlay();
        topIcon(c, gearIcon, right - s, iy, s, 255, bar && topSel == T_SETTINGS);
        topIcon(c, wifiIcon, right - s - step, iy, s, online ? 255 : 110, bar && topSel == T_WIFI);
        topIcon(c, slidersIcon, right - s - 2 * step, iy, s, 255, bar && topSel == T_CUSTOMIZE);
        drawSearchPill(c, right - s - 2 * step - 40 * k, bar && topSel == T_SEARCH);
        drawTabs(c, TABS_X * k, bar);
    }

    private static final float TABS_X = 520;

    /**
     * Home · Discover · Must-Watch · My Movies · My Music: Home is lit (it is this page); the others open their
     * screen. My Music shows three small moving bars while a song plays.
     */
    private void drawTabs(Canvas c, float x, boolean bar) {
        float h = 58 * k, top = 66 * k + 31 * k / 2 - h / 2;
        for (int i = 0; i < TABS.length; i++) {
            String l = getContext().getString(TABS[i]);
            boolean music = i == 4 && MusicPlayer.get(getContext()).playing();
            float w = msgP.measureText(l) + 56 * k + (music ? 34 * k : 0);
            tmp.set(x, top, x + w, top + h);
            boolean focused = bar && topSel == T_HOME + i;
            if (i == 0) kit.pill(c, tmp, h / 2, 1);
            if (focused) kit.focus(c, tmp, h / 2, 1);
            msgP.setAlpha(focused || i == 0 ? 255 : 190);
            c.drawText(l, tmp.left + 28 * k, tmp.centerY() + 8 * k, msgP);
            if ((i == 3 || i == 4) && driveIn && !music) {   // a pen drive is in: a small dot on My Movies and My Music
                fill.setColor(0xFF3DDC97);
                c.drawCircle(tmp.right - 14 * k, tmp.top + 14 * k, 5 * k, fill);
            }
            if (music) {   // playing: three bars bouncing at the end of the tab
                long t = SystemClock.uptimeMillis();
                fill.setColor(0xFF3DDC97);
                for (int b = 0; b < 3; b++) {
                    float bh = (8 + 14 * (0.5f + 0.5f * (float) Math.sin(t / (160.0 + b * 45) + b * 1.9))) * k;
                    float bx = tmp.right - 44 * k + b * 9 * k;
                    c.drawRect(bx, tmp.centerY() + 11 * k - bh, bx + 5 * k, tmp.centerY() + 11 * k, fill);
                }
            }
            x += w + 10 * k;
        }
        msgP.setAlpha(255);
    }

    /** "🔍 Search films, shows, YouTube…": a glass pill in the top bar, ending at `right`. */
    private void drawSearchPill(Canvas c, float right, boolean focused) {
        String l = "🔍   " + getContext().getString(R.string.search_pill);
        float h = 64 * k, w = msgP.measureText(l) + 64 * k, top = 66 * k + 31 * k / 2 - h / 2;
        tmp.set(right - w, top, right, top + h);
        kit.pill(c, tmp, h / 2, 1);
        if (focused) kit.focus(c, tmp, h / 2, 1);
        msgP.setAlpha(focused ? 255 : 215);
        c.drawText(l, tmp.left + 32 * k, tmp.centerY() + 8 * k, msgP);
        msgP.setAlpha(255);
    }

    private void topIcon(Canvas c, Bitmap b, float x, float y, float s, int alpha, boolean focused) {
        if (focused) {
            float mx = x + s / 2, my = y + s / 2, r = 30 * k;
            tint.setColor(Color.WHITE);
            tint.setAlpha(110);
            c.drawBitmap(iconGlow, mx - iconGlow.getWidth() / 2f, my - iconGlow.getHeight() / 2f, tint);
            art.circle(c, mx, my, r, 1);
            fill.setColor(0x9E0B0B0D);
            c.drawCircle(mx, my, r, fill);
            fill.setColor(0x26FFFFFF);
            c.drawCircle(mx, my, r, fill);
            edge.setStrokeWidth(Math.max(1f, 2 * k));
            edge.setAlpha(230);
            c.drawCircle(mx, my, r, edge);
        }
        bmp.setAlpha(alpha);
        float pad = (b.getWidth() - s) / 2f;
        c.drawBitmap(b, Math.round(x - pad), Math.round(y - pad), bmp);
        bmp.setAlpha(255);
    }

    /** Hero text on the left: badge and kicker, title, meta and a two-line synopsis. */
    private void drawHero(Canvas c, long now) {
        if (titleLayout == null) return;
        float a = Theme.progress(now, heroChanged, Theme.FADE_MS);
        float x = Theme.MARGIN * k, y = 200 * k, lift = (1 - a) * 14 * k;
        int alpha = Math.round(255 * a);
        if (hero.badge != null || !hero.kicker.isEmpty()) {
            float bx = x;
            if (hero.badge != null) {
                float bs = 34 * k;
                tmp.set(x, y - bs + 8 * k + lift, x + bs, y + 8 * k + lift);
                bmp.setAlpha(alpha);
                c.drawBitmap(hero.badge, null, tmp, bmp);
                bx += bs + 14 * k;
            }
            kickerP.setAlpha(Math.round(alpha * 0.92f));
            c.drawText(hero.kicker, bx, y + lift, kickerP);
        }
        float ty = y + 26 * k + lift;
        c.save();
        c.translate(x, ty);
        heroTitleP.setAlpha(alpha);
        titleLayout.draw(c);
        c.restore();
        ty += titleLayout.getHeight() + 14 * k;
        if (!TextUtils.isEmpty(hero.meta)) {
            metaP.setAlpha(Math.round(alpha * 0.85f));
            c.drawText(hero.meta, x, ty + 22 * k, metaP);
            ty += 40 * k;
        }
        if (synopsisLayout != null && !TextUtils.isEmpty(hero.synopsis)) {
            c.save();
            c.translate(x, ty + 6 * k);
            synopsisP.setAlpha(Math.round(alpha * 0.95f));
            synopsisLayout.draw(c);
            c.restore();
        }
    }

    /** Position along the carousel, gliding between steps. */
    private float carPos(long now) {
        return carFrom + (carTo - carFrom) * Theme.progress(now, carStart, Theme.MOVE_MS);
    }

    private void drawCarousel(Canvas c, long now) {
        int n = items.size();
        if (n == 0) return;
        float pos = carPos(now), cx = CAR_X * k, cy = CAR_Y * k;
        int reach = 2;
        // Back to front, so the front card is drawn last.
        List<float[]> order = new ArrayList<>();
        for (int i = -reach - 1; i <= reach + 1; i++) {
            int idx = Math.round(pos) + i;
            float d = idx - pos;
            if (Math.abs(d) > reach + 0.6f) continue;
            order.add(new float[]{Math.abs(d), d, Math.floorMod(idx, n)});
        }
        order.sort((p, q) -> Float.compare(q[0], p[0]));
        for (float[] o : order) {
            Item it = items.get((int) o[2]);
            if (it.card == null) continue;
            Bitmap card = it.card;
            float d = o[1], ad = Math.abs(d), sign = Math.signum(d);
            float x = sign * (ad <= 1 ? 210 * ad : 210 + 120 * (ad - 1)) * k;
            float rot = -sign * (ad <= 1 ? 42 * ad : 42 + 13 * (ad - 1));
            float z = 150 * ad * k;
            float alpha = Math.max(0, 1 - 0.38f * ad);
            boolean isFront = ad < 0.5f;
            matrix.reset();
            camera.save();
            camera.setLocation(0, 0, -18);
            camera.translate(0, 0, z);
            camera.rotateY(rot);
            camera.getMatrix(matrix);
            camera.restore();
            float w = card.getWidth(), h = card.getHeight();
            matrix.preTranslate(-w / 2, -h / 2);
            matrix.postTranslate(cx + x, cy);
            if (isFront) drawFrontExtras(c, it, cx + x, cy, w, h, now, true);
            bmp.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(card, matrix, bmp);
            if (isFront) drawFrontExtras(c, it, cx + x, cy, w, h, now, false);
        }
        bmp.setAlpha(255);
    }

    /** Under the front card: its glow, reflection and frosted backing. Over it: white edge and light sweep. */
    private void drawFrontExtras(Canvas c, Item it, float x, float y, float w, float h, long now, boolean under) {
        boolean focused = row == Row.APPS && noOverlay();
        float r = Theme.RADIUS_CARD * k;
        tmp.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        if (under) {
            if (focused) {
                Bitmap g = WheelArt.roundGlow(w, h, r, 26 * k);
                tint.setColorFilter(new PorterDuffColorFilter(it.color, PorterDuff.Mode.SRC_IN));
                tint.setAlpha(170);
                c.drawBitmap(g, x - g.getWidth() / 2f, y - g.getHeight() / 2f, tint);
                tint.setColorFilter(null);
            }
               // a ticket: paper, no glass behind it
            if (it.reflection != null) {
                bmp.setAlpha(255);
                c.drawBitmap(it.reflection, x - w / 2, y + h / 2 + 8 * k, bmp);
            }
            art.round(c, tmp, r, 1);
            return;
        }
        if (focused) {
            edge.setStrokeWidth(Math.max(1f, 2 * k));
            edge.setAlpha(255);
            c.drawRoundRect(tmp, r, r, edge);
            drawSweep(c, tmp, r, now);
        }
    }

    /** A one-time diagonal band of light across a focused surface. */
    private void drawSweep(Canvas c, RectF r, float radius, long now) {
        float t = (now - sweepStart) / 700f;
        if (t < 0 || t > 1) return;
        float e = Theme.ease(t), bandW = r.width() * 0.45f;
        float x = r.left - bandW + (r.width() + 2 * bandW) * e;
        sweep.setShader(new LinearGradient(x, r.top, x + bandW, r.top + bandW * 0.6f,
                new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.save();
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(r, sweep);
        c.restore();
    }

    private void buildActions() {
        actions.clear();
        Context c = getContext();
        Item it = front();
        if (it == null) return;
        if (it.kind == ALL_APPS) {
            actions.add(new Action(c.getString(R.string.browse_all_apps), "allapps", true));
        } else if (it.kind == INPUTS) {
            actions.add(new Action(c.getString(R.string.choose_input), "inputs", true));
        } else if (it.feature != null) {
            actions.add(new Action(c.getString(R.string.play_now), "play", true));
            if (it.feature.trailer != null) actions.add(new Action(c.getString(R.string.watch_trailer), "trailer", false));
            actions.add(new Action(c.getString(R.string.more_info), "info", false));
            if (it.kind == DISCOVER) actions.add(new Action(c.getString(R.string.open_discover), "discover", false));
        } else if (it.kind == DISCOVER) {
            actions.add(new Action(c.getString(R.string.open_discover), "discover", true));
        } else {
            actions.add(new Action(c.getString(R.string.open_app, it.name), "open", true));
        }
        actSel = Math.min(actSel, actions.size() - 1);
    }

    private void drawActions(Canvas c) {
        if (actions.isEmpty()) return;
        float h = Theme.PILL_H * k, gap = Theme.GAP * k, total = -gap;
        float[] widths = new float[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            widths[i] = pillP.measureText(actions.get(i).label) + 64 * k;
            total += widths[i] + gap;
        }
        float x = CAR_X * k - total / 2, y = ACT_Y * k;
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            boolean focused = row == Row.ACTIONS && actSel == i && noOverlay();
            tmp.set(x, y, x + widths[i], y + h);
            if (a.primary) {
                fill.setColor(focused ? 0xFFFFFFFF : 0xEBFFFFFF);
                if (focused) kit.focus(c, tmp, h / 2, 1);
                c.drawRoundRect(tmp, h / 2, h / 2, fill);
                float tw = pillDarkP.measureText(a.label);
                c.drawText(a.label, tmp.centerX() - tw / 2, tmp.centerY() + 8 * k, pillDarkP);
            } else {
                kit.pill(c, tmp, h / 2, 1);
                if (focused) kit.focus(c, tmp, h / 2, 1);
                float tw = pillP.measureText(a.label);
                pillP.setAlpha(focused ? 255 : 225);
                c.drawText(a.label, tmp.centerX() - tw / 2, tmp.centerY() + 8 * k, pillP);
            }
            x += widths[i] + gap;
        }
    }

    /** How far the row is slid so the focused card sits one card in from the left, without running past the end. */
    private float freshShift(int pos) {
        float step = (NEW_W + NEW_GAP) * k, visible = getWidth() - 2 * (Theme.MARGIN + 30) * k;
        float max = Math.max(0, fresh.size() * step - NEW_GAP * k - visible);
        return Math.max(0, Math.min((pos - 1) * step, max));
    }

    private float freshShiftNow(long now) {
        return shiftFrom + (shiftTo - shiftFrom) * Theme.progress(now, freshStart, Theme.MOVE_MS);
    }

    /**
     * New this week: a full-width glass panel with a row of 16:9 cards, about four and a half in view. It never moves
     * by itself; Left and Right move the focus and the row slides to keep it in view.
     */
    private void drawFresh(Canvas c, long now) {
        float left = Theme.MARGIN * k, right = getWidth() - Theme.MARGIN * k, top = NEW_TOP * k, bottom = top + NEW_H * k;
        tmp2.set(left, top, right, bottom);
        {
            kit.panel(c, tmp2, Theme.RADIUS_PANEL * k, 1, Theme.TINT_LOW);
            labelP.setAlpha(225);
            c.drawText(getContext().getString(R.string.new_this_week).toUpperCase(Locale.ROOT), left + 30 * k, top + 40 * k, labelP);
        }
        boolean focused = row == Row.NEW && noOverlay();
        float w = NEW_W * k, h = NEW_HGT * k, step = (NEW_W + NEW_GAP) * k, cardTop = top + 74 * k;
        if (fresh.isEmpty()) {
            float cx = (left + right) / 2, cy = cardTop + h / 2;
            String s = freshLoading ? getContext().getString(R.string.loading)
                    : freshFailed ? getContext().getString(R.string.hero_offline) : getContext().getString(R.string.new_none);
            if (freshLoading) drawSkeletons(c, left + 30 * k, cardTop, now);
            else {
                msgP.setAlpha(210);
                c.drawText(s, cx - msgP.measureText(s) / 2, cy + 8 * k, msgP);
            }
            if (focused) {   // nothing to pick: the panel itself shows focus
                edge.setStrokeWidth(Math.max(1f, 2 * k));
                edge.setAlpha(230);
                c.drawRoundRect(tmp2, Theme.RADIUS_PANEL * k, Theme.RADIUS_PANEL * k, edge);
            }
            return;
        }
        int n = fresh.size();
        String counter = (freshPos + 1) + " / " + n;
        c.drawText(counter, right - 30 * k - counterP.measureText(counter), top + 40 * k, counterP);
        float shift = freshShiftNow(now), x0 = left + 30 * k - shift;
        c.save();
        c.clipRect(left + 1, top + 52 * k, right - 1, bottom - 1);
        int first = Math.max(0, (int) Math.floor(shift / step) - 1), last = Math.min(n - 1, first + 7);
        for (int i = first; i <= last; i++) {
            if (i == freshPos && focused) continue;   // drawn last, on top
            drawFreshCard(c, fresh.get(i), x0 + i * step, cardTop, w, h, false, focused ? 0.72f : 0.92f);
        }
        if (focused) drawFreshCard(c, fresh.get(freshPos), x0 + freshPos * step, cardTop, w, h, true, 1);
        c.restore();
        // Cards running off either end fade into the panel.
        float fw = 140 * k;
        if (shift > 1) {
            fadeEdge.setShader(new LinearGradient(left, 0, left + fw, 0, 0xD90B0B0D, 0x000B0B0D, Shader.TileMode.CLAMP));
            c.drawRect(left + 1, top + 52 * k, left + fw, bottom - 2, fadeEdge);
        }
        if (shift < freshShift(n - 1) - 1 || x0 + n * step > right) {
            fadeEdge.setShader(new LinearGradient(right - fw, 0, right, 0, 0x000B0B0D, 0xD90B0B0D, Shader.TileMode.CLAMP));
            c.drawRect(right - fw, top + 52 * k, right - 1, bottom - 2, fadeEdge);
        }
    }

    /** One New this week card; the focused one is lifted to 1.08x with a white edge, violet glow and play button. */
    private void drawFreshCard(Canvas c, Tmdb.Title t, float x, float y, float w, float h, boolean focus, float alpha) {
        float r = 18 * k, cx = x + w / 2, cy = y + h / 2;
        float grow = focus ? 1 + 0.08f * Theme.progress(SystemClock.uptimeMillis(), freshStart, Theme.MOVE_MS) : 1;
        tmp.set(cx - w * grow / 2, cy - h * grow / 2, cx + w * grow / 2, cy + h * grow / 2);
        if (focus) {
            Bitmap g = WheelArt.roundGlow(tmp.width(), tmp.height(), r, 22 * k);
            tint.setColorFilter(new PorterDuffColorFilter(Theme.NEW_GLOW, PorterDuff.Mode.SRC_IN));
            tint.setAlpha(180);
            c.drawBitmap(g, cx - g.getWidth() / 2f, cy - g.getHeight() / 2f, tint);
            tint.setColorFilter(null);
        }
        Bitmap card = freshCard(t);
        
        if (card != null) {
            Paint p = bmp;
            p.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(card, null, tmp, p);
            p.setAlpha(255);
        } else {
            fill.setColor(Color.argb(Math.round(50 * alpha), 255, 255, 255));
            c.drawRoundRect(tmp, r, r, fill);
            msgP.setAlpha(Math.round(200 * alpha));
            c.drawText(TextUtils.ellipsize(t.title, new TextPaint(msgP), tmp.width() - 30 * k, TextUtils.TruncateAt.END).toString(),
                    tmp.left + 16 * k, tmp.bottom - 18 * k, msgP);
        }
        if (!focus) return;
        edge.setStrokeWidth(Math.max(1f, 2.5f * k));
        edge.setAlpha(255);
        c.drawRoundRect(tmp, r, r, edge);
        float pr = 22 * k, px = tmp.right - 14 * k - pr, py = tmp.bottom - 12 * k - pr;
        fill.setColor(0x660B0B0D);
        c.drawCircle(px, py, pr, fill);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(px, py, pr, fill);
        edge.setStrokeWidth(1);
        c.drawCircle(px, py, pr, edge);
        c.drawBitmap(playGlyph, px - playGlyph.getWidth() / 2f + 2 * k, py - playGlyph.getHeight() / 2f, bmp);
        drawSweep(c, tmp, r, SystemClock.uptimeMillis());
    }

    /** "today", "yesterday", "3 days ago". */
    private String age(String date) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
            long days = d == null ? -1 : (System.currentTimeMillis() - d.getTime()) / 86_400_000L;
            if (days <= 0) return getContext().getString(R.string.today);
            if (days == 1) return getContext().getString(R.string.yesterday);
            return getContext().getString(R.string.days_ago, days);
        } catch (Exception e) {
            return getContext().getString(R.string.this_week);
        }
    }

    /** Loading placeholders with a slow shimmer. */
    private void drawSkeletons(Canvas c, float x, float y, long now) {
        float w = NEW_W * k, h = NEW_HGT * k, step = (NEW_W + NEW_GAP) * k;
        for (int i = 0; i < 5; i++) {
            float shimmer = 0.5f + 0.5f * (float) Math.sin(now / 300.0 - i * 0.6);
            fill.setColor(Color.argb(Math.round(28 + 22 * shimmer), 255, 255, 255));
            c.drawRoundRect(x + i * step, y, x + i * step + w, y + h, 18 * k, 18 * k, fill);
        }
    }

    /** Random Movie, Random Series and Random Song tiles. */
    private void drawTiles(Canvas c, long now) {
        float tw = tileWidth(), h = RAND_H * k, y0 = RAND_TOP * k, x = Theme.MARGIN * k;
        int[] colours = {Theme.AMBER_B, Theme.VIOLET_B, Theme.MINT_B, Theme.ROSE_B, Theme.AMBER_B};
        for (int i = 0; i < tileCount; i++) {
            boolean focused = row == Row.RANDOM && randSel == i && noOverlay();
            float lift = focused ? 4 * k * Theme.progress(now, rowChanged, Theme.MOVE_MS) : 0;
            tmp.set(x, y0 - lift, x + tw, y0 - lift + h);
            float r = Theme.RADIUS_TILE * k;
            if (focused) {
                Bitmap g = WheelArt.roundGlow(tw, h, r, 20 * k);
                tint.setColorFilter(new PorterDuffColorFilter(colours[i], PorterDuff.Mode.SRC_IN));
                tint.setAlpha(150);
                c.drawBitmap(g, tmp.centerX() - g.getWidth() / 2f, tmp.centerY() - g.getHeight() / 2f, tint);
                tint.setColorFilter(null);
            }
            {
                kit.panel(c, tmp, r, 1, focused ? Theme.TINT_HIGH : Theme.TINT_LOW);
                if (focused) {
                    edge.setStrokeWidth(Math.max(1f, 2 * k));
                    edge.setAlpha(255);
                    c.drawRoundRect(tmp, r, r, edge);
                }
            }
            bmp.setAlpha(255);
            c.drawBitmap(tiles[i], tmp.left, tmp.top, bmp);
            if (focused) drawSweep(c, tmp, r, now);
            x += tw + 24 * k;
        }
    }

    // ---- overlays: All apps, More info, Inputs ---------------------------------------------------

    private static final int GRID_COLS = 5, GRID_ROWS = 3;

    private void drawAllApps(Canvas c) {
        float e = Theme.ease(allT);
        int per = GRID_COLS * GRID_ROWS, pages = Math.max(1, (apps.size() + per - 1) / per);
        int rows = pages > 1 ? GRID_ROWS : Math.max(1, (apps.size() + GRID_COLS - 1) / GRID_COLS);
        float cellW = 276 * k, cellH = 184 * k, w = GRID_COLS * cellW + 72 * k, h = 112 * k + rows * cellH + 28 * k;
        RectF p = new RectF((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, e, nImg);
        Frost.panel(c, p, 40 * k, e, k);
        Paint head = nk.inter(46, 500, -0.03f, Frost.INK), name = nk.inter(28, 500, -0.012f, Frost.INK), count = nk.inter(26, 500, 0, Frost.MUTE);
        head.setAlpha(Math.round(255 * e));
        c.drawText(getContext().getString(R.string.all_apps), p.left + 48 * k, p.top + 76 * k, head);
        if (pages > 1) {
            String pg = (allPage + 1) + L10n.t(" of ") + pages;
            c.drawText(pg, p.right - 48 * k - count.measureText(pg), p.top + 74 * k, count);
        }
        for (int i = 0; i < per; i++) {
            int idx = allPage * per + i;
            if (idx >= apps.size()) break;
            Apps.App a = apps.get(idx);
            boolean f = allSel == i;
            float cx = p.left + 36 * k + (i % GRID_COLS + 0.5f) * cellW, top = p.top + 112 * k + (i / GRID_COLS) * cellH;
            RectF cell = new RectF(cx - cellW / 2 + 6 * k, top + 6 * k, cx + cellW / 2 - 6 * k, top + cellH - 6 * k);
            if (f) Frost.plate(c, cell, 30 * k, k);
            Bitmap mark = LineIcon.appMark(a.pkg, a.label, 150 * k, 76 * k, name.getTypeface());
            nImg.setAlpha(Math.round((f ? 255 : 225) * e));
            c.drawBitmap(mark, cx - mark.getWidth() / 2f, cell.top + 60 * k - mark.getHeight() / 2f, nImg);
            nImg.setAlpha(255);
            String lb = fit(a.label, name, cell.width() - 24 * k);
            name.setAlpha(Math.round((f ? 255 : 220) * e));
            c.drawText(lb, cx - name.measureText(lb) / 2, cell.bottom - 24 * k, name);
        }
    }

    private void drawInfo(Canvas c) {
        float e = Theme.ease(infoT), w = 1180 * k, h = 640 * k;
        tmp2.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        c.save();
        float s = 0.94f + 0.06f * e;
        c.scale(s, s, tmp2.centerX(), tmp2.centerY());
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, e, nImg);
        Frost.panel(c, tmp2, 40 * k, e, k);
        float x = tmp2.left + 48 * k, y = tmp2.top + 82 * k;
        infoTitleP.setAlpha(Math.round(255 * e));
        c.drawText(TextUtils.ellipsize(info.title, new TextPaint(infoTitleP), w - 96 * k, TextUtils.TruncateAt.END).toString(), x, y, infoTitleP);
        infoMetaP.setAlpha(Math.round(220 * e));
        String rating = info.rating > 0 ? "  ·  TMDB " + String.format(Locale.US, "%.1f", info.rating) : "";
        c.drawText(info.meta() + rating, x, y + 44 * k, infoMetaP);
        String body = info.overview.isEmpty() ? getContext().getString(R.string.no_synopsis) : info.overview;
        StaticLayout l = StaticLayout.Builder.obtain(body, 0, body.length(), infoBodyP, Math.round(w - 96 * k)).setMaxLines(9)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        c.save();
        c.translate(x, y + 76 * k);
        infoBodyP.setAlpha(Math.round(242 * e));
        l.draw(c);
        c.restore();
        String where = infoWhere == null ? getContext().getString(R.string.where_loading)
                : infoWhere.isEmpty() ? getContext().getString(R.string.where_none) : whereText(infoWhere);
        infoMetaP.setAlpha(Math.round(235 * e));
        c.drawText(where, x, tmp2.bottom - 118 * k, infoMetaP);
        String[] buttons = infoButtons();
        float bx = x, by = tmp2.bottom - 92 * k, bh = Theme.PILL_H * k;
        for (int i = 0; i < buttons.length; i++) {
            float bw = pillP.measureText(buttons[i]) + 64 * k;
            tmp.set(bx, by, bx + bw, by + bh);
            if (i == 0) {
                fill.setColor(0xF2FFFFFF);
                if (infoSel == 0) kit.focus(c, tmp, bh / 2, e);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
                c.drawText(buttons[i], tmp.centerX() - pillDarkP.measureText(buttons[i]) / 2, tmp.centerY() + 8 * k, pillDarkP);
            } else {
                kit.pill(c, tmp, bh / 2, e);
                if (infoSel == i) kit.focus(c, tmp, bh / 2, e);
                c.drawText(buttons[i], tmp.centerX() - pillP.measureText(buttons[i]) / 2, tmp.centerY() + 8 * k, pillP);
            }
            bx += bw + Theme.GAP * k;
        }
        c.restore();
    }

    private String whereText(List<Services.Service> where) {
        List<String> names = new ArrayList<>();
        for (Services.Service s : where) names.add(s.name + (Services.installed(getContext(), s.pkg) ? "" : L10n.t(" (not installed)")));
        return getContext().getString(R.string.where_watch, TextUtils.join(", ", names));
    }

    private String[] infoButtons() {
        List<String> b = new ArrayList<>();
        b.add(getContext().getString(R.string.play_now));
        if (info != null && info.trailer != null) b.add(getContext().getString(R.string.watch_trailer));
        return b.toArray(new String[0]);
    }

    private void openInfo(Tmdb.Title t) {
        TitleActivity.open(getContext(), t);
    }

    private void openInputs() {
        inputs = Inputs.list(getContext());
        inputSel = 0;
        if (inputs.isEmpty()) {
            inputs = null;
            message(getContext().getString(R.string.no_inputs));
        }
    }

    private void drawInputs(Canvas c) {
        float rowH = 64 * k, w = 420 * k, h = rowH * inputs.size() + 32 * k;
        tmp2.set(CAR_X * k - w / 2, CAR_Y * k - h / 2, CAR_X * k + w / 2, CAR_Y * k + h / 2);
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, 1, nImg);
        Frost.panel(c, tmp2, 40 * k, 1, k);
        for (int i = 0; i < inputs.size(); i++) {
            float top = tmp2.top + 16 * k + i * rowH;
            if (i == inputSel) {
                tmp.set(tmp2.left + 10 * k, top + 4 * k, tmp2.right - 10 * k, top + rowH - 4 * k);
                kit.focus(c, tmp, 14 * k, 1);
            }
            pillP.setAlpha(i == inputSel ? 255 : 210);
            c.drawText(inputs.get(i).label, tmp2.left + 32 * k, top + rowH / 2 + 8 * k, pillP);
        }
        pillP.setAlpha(255);
    }

    private void message(String text) {
        if (listener != null) listener.onMessage(text);
    }

    // ---- glass pages -----------------------------------------------------------------------------

    private GlassSheet newSheet(int title, WheelArt.Glyph glyph, String id) {
        return new GlassSheet(kit, getContext().getString(title), glyph, id, getContext().getString(R.string.page_scroll_hint));
    }

    private void openControlCenter() {
        ControlCenter.open((android.app.Activity) getContext(), this::requestFocus, controlActions(true));
    }

    /** What Control Center's tiles open. fromCard: the pop-up card (Customize isn't open behind it). */
    private ControlCenter.Actions controlActions(boolean fromCard) {
        return new ControlCenter.Actions() {
            @Override
            public void sound() {
                if (fromCard) openSettings();
                activate(sheet, new GlassSheet.Row(GlassSheet.ACTION, "", null, "bluetooth"));
            }

            @Override
            public void inputs() {
                sheet = null;
                openInputs();
            }

            @Override
            public void sports() {
                openCricket();
            }

            @Override
            public void setUpHome() {
                if (fromCard) openSettings();
                activate(sheet, new GlassSheet.Row(GlassSheet.ACTION, "", null, "helper"));
            }

            @Override
            public void stockHome() {
                if (fromCard) openSettings();
                activate(sheet, new GlassSheet.Row(GlassSheet.ACTION, "", null, "stockhome"));
            }
        };
    }

    /** One Settings page for everything: categories on the left, their rows on the right. */
    private void openSettings() {
        Context c = getContext();
        TvTools.audio(c, p -> btProxy = p);
        styleAtOpen = Style.get() + "/" + Style.country();
        GlassSheet s = new GlassSheet(kit, L10n.t("Customize TV Town"), WheelArt.Glyph.SLIDERS, "customize", c.getString(R.string.page_scroll_hint));
        s.addSection(L10n.t("Control Center"), kit.glyph(WheelArt.Glyph.PULSE, 34)).pane(new ControlCenter.Pane(c, this::invalidate, controlActions(false)));
        s.addSection(L10n.t("Home"), kit.glyph(WheelArt.Glyph.FILM, 34));
        s.add(GlassSheet.VALUE, L10n.t("Top picture"), kit.glyph(WheelArt.Glyph.FILM, 38), "appearance");
        s.add(GlassSheet.VALUE, L10n.t("Top picture shows"), kit.glyph(WheelArt.Glyph.STAR, 38), "topsource");
        s.add(GlassSheet.VALUE, L10n.t("Top bar: left button"), kit.glyph(WheelArt.Glyph.GRID, 38), "topleft");
        s.add(GlassSheet.VALUE, L10n.t("Top bar: right button"), kit.glyph(WheelArt.Glyph.GRID, 38), "topright");
        s.add(GlassSheet.VALUE, L10n.t("Icon pack"), kit.glyph(WheelArt.Glyph.STAR, 38), "iconpack");
        s.add(GlassSheet.ACTION, L10n.t("Apps on Home"), kit.glyph(WheelArt.Glyph.GRID, 38), "apps");
        s.add(GlassSheet.ACTION, c.getString(R.string.weather_row), kit.glyph(WheelArt.Glyph.SUN, 38), "weather");
        s.addSection(L10n.t("Features"), kit.glyph(WheelArt.Glyph.SLIDERS, 34));
        s.add(GlassSheet.VALUE, L10n.t("Surprise Me"), kit.glyph(WheelArt.Glyph.DICE, 38), "f:" + Features.SURPRISE);
        s.add(GlassSheet.VALUE, L10n.t("My Movies"), kit.glyph(WheelArt.Glyph.FILM, 38), "f:" + Features.MOVIES);
        s.add(GlassSheet.VALUE, L10n.t("My Music"), kit.glyph(WheelArt.Glyph.NOTE, 38), "f:" + Features.MUSIC);
        s.add(GlassSheet.VALUE, L10n.t("Live scores"), kit.glyph(WheelArt.Glyph.PULSE, 38), "cricketon");
        s.add(GlassSheet.VALUE, L10n.t("Phone remote"), kit.glyph(WheelArt.Glyph.KEY, 38), "f:" + Features.PHONE);
        s.add(GlassSheet.VALUE, L10n.t("Continue watching from apps"), kit.glyph(WheelArt.Glyph.PLAY, 38), "f:" + Features.APPS);
        s.add(GlassSheet.VALUE, L10n.t("Save from YouTube (OK twice)"), kit.glyph(WheelArt.Glyph.DOWN, 38), "f:" + Features.YT_SAVE);
        s.addSection(L10n.t("Screensaver"), kit.glyph(WheelArt.Glyph.TV, 34));
        s.add(GlassSheet.ACTION, L10n.t("Screensaver"), kit.glyph(WheelArt.Glyph.TV, 38), "dreamapp");
        if (!Screensavers.installed(c, Screensavers.AERIAL))
            s.add(GlassSheet.ACTION, L10n.t("Get Aerial Views (free)"), kit.glyph(WheelArt.Glyph.DOWN, 38), "dreamget");
        s.add(GlassSheet.ACTION, L10n.t("Try it now"), kit.glyph(WheelArt.Glyph.PLAY, 38), "dreamtry");
        s.add(GlassSheet.ACTION, L10n.t("TV's screensaver settings"), kit.glyph(WheelArt.Glyph.GEAR, 38), "dreamsys");
        s.addSection(L10n.t("Films and music"), kit.glyph(WheelArt.Glyph.STAR, 34));
        s.add(GlassSheet.VALUE, c.getString(R.string.app_language), kit.glyph(WheelArt.Glyph.NOTE, 38), "applang");
        s.add(GlassSheet.ACTION, c.getString(R.string.content_style), kit.glyph(WheelArt.Glyph.STAR, 38), "style");
        s.add(GlassSheet.ACTION, c.getString(R.string.streaming_country), kit.glyph(WheelArt.Glyph.TV, 38), "country");
        s.add(GlassSheet.ACTION, c.getString(R.string.my_languages), kit.glyph(WheelArt.Glyph.NOTE, 38), "mylangs");
        if (StreamRadar.usable()) s.add(GlassSheet.VALUE, L10n.t("Picks from"), kit.glyph(WheelArt.Glyph.STAR, 38), "picksfrom");
        if (OpenIn.choices(c).size() > 1) s.add(GlassSheet.VALUE, L10n.t("Play films in"), kit.glyph(WheelArt.Glyph.PLAY, 38), "openin");
        s.addSection(L10n.t("Phone and downloads"), kit.glyph(WheelArt.Glyph.DRIVE, 34));
        s.add(GlassSheet.ACTION, L10n.t("Connect my phone"), kit.glyph(WheelArt.Glyph.KEY, 38), "savephone");
        s.add(GlassSheet.ACTION, L10n.t("Receive files (LocalSend)"), kit.glyph(WheelArt.Glyph.DOWN, 38), "receive");
        s.add(GlassSheet.VALUE, L10n.t("Save songs to"), kit.glyph(WheelArt.Glyph.NOTE, 38), "saveaudio");
        s.add(GlassSheet.VALUE, L10n.t("Save videos to"), kit.glyph(WheelArt.Glyph.FILM, 38), "savevideo");
        s.add(GlassSheet.ACTION, c.getString(R.string.storage), kit.glyph(WheelArt.Glyph.DRIVE, 38), "storage");
        s.addSection(L10n.t("Updates and backup"), kit.glyph(WheelArt.Glyph.INFO, 34));
        if (Updater.available != null) s.dot();
        s.add(GlassSheet.ACTION, c.getString(R.string.updates), kit.glyph(WheelArt.Glyph.REDO, 38), "update");
        if (Updater.available != null) s.dotRow();
        s.add(GlassSheet.ACTION, c.getString(R.string.backup_row), kit.glyph(WheelArt.Glyph.UPLOAD, 38), "backup");
        s.add(GlassSheet.ACTION, c.getString(R.string.restore_row), kit.glyph(WheelArt.Glyph.DOWN, 38), "restore");
        s.add(GlassSheet.ACTION, c.getString(R.string.tmdb_key_row), kit.glyph(WheelArt.Glyph.KEY, 38), "tmdbkey");
        s.addSection(L10n.t(Treat.TITLE), kit.glyph(WheelArt.Glyph.HEART, 34)).pane(new Treat(c));   // its own place, last on the left
        s.markMore("apps", "weather", "interval", "dreamapp", "style", "country", "mylangs", "savephone", "saver", "wifi", "bluetooth", "storage", "restore", "tmdbkey");
        s.layoutPage(getWidth(), getHeight(), 1);
        sheet = s;
    }

    /**
     * Adding the free TMDB key (new films and series, Discover, the Random picks): typed or pasted on a phone (the
     * box shows a QR code; the phone's page lists the steps), checked with TMDB, then kept on the TV.
     */
    void openKeySetup() {
        Context c = getContext();
        PhoneKeyboard.note = "<p><b>Get a free TMDB key</b> (takes two minutes):</p><ol>"
                + "<li>Sign up at <a href='https://www.themoviedb.org/signup'>themoviedb.org/signup</a> (free).</li>"
                + "<li>Open <a href='https://www.themoviedb.org/settings/api'>Settings → API</a> and ask for a key: choose "
                + "<i>Developer</i>, type of use <i>Personal</i>, and any short description.</li>"
                + "<li>Copy the <b>API Key</b> (the short one) and paste it below, then tap Send.</li></ol>";
        listener.onAskText(c.getString(R.string.tmdb_key_title), c.getString(R.string.tmdb_key_hint), typed -> {
            PhoneKeyboard.note = null;
            String key = typed == null ? "" : typed.trim();
            if (key.isEmpty()) return;
            new Thread(() -> {
                boolean ok = tmdbKeyWorks(key);
                if (ok) Keys.set(Keys.TMDB, key);
                post(() -> {
                    android.widget.Toast.makeText(c, ok ? R.string.key_ok : R.string.key_bad, android.widget.Toast.LENGTH_LONG).show();
                    if (ok) refresh(true);
                    invalidate();
                });
            }, "key").start();
        });
    }

    /** Weather: the town typed (on the remote or a phone); an empty answer turns it off. */
    private void openWeatherSetup(GlassSheet s) {
        Context c = getContext();
        listener.onAskText(c.getString(R.string.weather_title), c.getString(R.string.weather_hint), typed -> {
            if (typed == null) return;   // backed out: leave it as it was
            String town = typed.trim();
            if (town.isEmpty()) {
                Weather.off(c);
                s.say(c.getString(R.string.weather_off));
                invalidate();
                return;
            }
            new Thread(() -> {
                String found = Weather.setPlace(c, town);
                post(() -> {
                    s.say(found != null ? c.getString(R.string.weather_set, found) : c.getString(R.string.weather_not_found, town));
                    Weather.refresh(c, this::postInvalidate);
                    invalidate();
                });
            }, "place").start();
        });
    }

    /** Opens TV Town afresh (after a restore), so every screen reads the restored settings. */
    private void restart() {
        Context c = getContext();
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (i == null) return;
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Receive files: LocalSend on a phone finds this TV while this is open; songs and videos land on the drive. */
    private void openReceive(@Nullable GlassSheet from) {
        Context c = getContext();
        GlassSheet s = new GlassSheet(kit, L10n.t("Receive files"), WheelArt.Glyph.DOWN, "localsend", "");
        s.add(GlassSheet.NOTE, L10n.t("On your phone: open LocalSend, tap Send, pick songs or videos, then choose"), null, null);
        s.add(GlassSheet.ACTION, LocalSend.alias(), kit.glyph(WheelArt.Glyph.TV, 38), "lsname");
        s.add(GlassSheet.NOTE, L10n.t("Same Wi-Fi as the TV. Saved on the USB drive: songs in Music, videos in Movies"), null, null);
        s.add(GlassSheet.NOTE, L10n.t("Findable only while this is open. LocalSend is free in your phone's app store"), null, null);
        s.popupW = 1240;
        s.parent = from;
        s.layout(getWidth(), getHeight(), 1240, 5);
        sheet = s;
        invalidate();
        LocalSend.start(c, text -> {
            s.say(text);
            invalidate();
        });
    }

    /** Whether TMDB accepts a key (a short v3 key in the address, or a long read token in the header). */
    private static boolean tmdbKeyWorks(String key) {
        try {
            boolean v3 = key.length() < 60;
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.themoviedb.org/3/configuration" + (v3 ? "?api_key=" + android.net.Uri.encode(key) : "")).openConnection();
            if (!v3) h.setRequestProperty("Authorization", "Bearer " + key);
            h.setConnectTimeout(10_000);
            h.setReadTimeout(10_000);
            return h.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private android.bluetooth.BluetoothA2dp btProxy;

    @Nullable
    private android.bluetooth.BluetoothDevice btDevice(String address) {
        for (android.bluetooth.BluetoothDevice d : TvTools.paired(getContext())) if (d.getAddress().equals(address)) return d;
        return null;
    }

    /**
     * Bluetooth: where the sound goes (the TV's speakers, or paired headphones and speakers: OK moves it there), then
     * the game controllers, remotes and keyboards, each with its battery when it says, and pairing a new one.
     */
    private void openBluetooth(GlassSheet from) {
        Context c = getContext();
        GlassSheet s = newSheet(R.string.bluetooth, WheelArt.Glyph.BT, "bluetooth");
        List<android.bluetooth.BluetoothDevice> list = TvTools.paired(c);
        s.add(GlassSheet.NOTE, L10n.t("Sound plays on"), null, null);
        s.add(GlassSheet.ACTION, L10n.t("TV speakers"), kit.glyph(WheelArt.Glyph.TV, 38), "bttv");
        int was = -1;
        for (android.bluetooth.BluetoothDevice d : list) {
            int kind = TvTools.kind(d);
            boolean sound = kind <= TvTools.BT_SPEAKER;
            if (!sound && was <= TvTools.BT_SPEAKER) s.add(GlassSheet.NOTE, L10n.t("Controllers, remotes and more"), null, null);
            was = Math.max(was, sound ? kind : TvTools.BT_CONTROLLER);
            WheelArt.Glyph g = kind == TvTools.BT_HEADPHONES ? WheelArt.Glyph.NOTE : kind == TvTools.BT_SPEAKER ? WheelArt.Glyph.NOTE
                    : kind == TvTools.BT_CONTROLLER ? WheelArt.Glyph.DICE : WheelArt.Glyph.BT;
            s.add(GlassSheet.ACTION, TvTools.name(d), kit.glyph(g, 38), "bt:" + d.getAddress());
        }
        if (list.isEmpty()) s.add(GlassSheet.NOTE, c.getString(R.string.bt_none_paired), null, null);
        s.add(GlassSheet.ACTION, c.getString(R.string.bt_pair_new), kit.glyph(WheelArt.Glyph.GEAR, 38), "btsettings");
        s.layout(getWidth(), getHeight(), 900, 9);
        s.parent = from;
        sheet = s;
    }

    /** Now playing needs notification access (Android shows other apps' players only to such an app). */
    private boolean mediaAccess() {
        android.app.NotificationManager nm = getContext().getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new android.content.ComponentName(getContext(), MediaWatch.class));
    }

    private static String clip(String t, int max) {
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    private boolean stockIsGoogleTv() {
        return HomeKeyService.stockLaunchers(getContext()).contains("com.google.android.apps.tv.launcherx");
    }

    private String styleAtOpen = "";   // style and country when Customize (or Welcome) opened

    /** The first start: what TV Town should suggest, already set from the TV's country; Start keeps it. */
    private void openWelcome() {
        GlassSheet s = newSheet(R.string.welcome, WheelArt.Glyph.SLIDERS, "welcome");
        s.add(GlassSheet.NOTE, getContext().getString(R.string.welcome_note), null, null);
        s.add(GlassSheet.VALUE, getContext().getString(R.string.content_style), kit.glyph(WheelArt.Glyph.SHUFFLE, 38), "style");
        s.add(GlassSheet.VALUE, getContext().getString(R.string.streaming_country), kit.glyph(WheelArt.Glyph.TV, 38), "country");
        s.add(GlassSheet.VALUE, getContext().getString(R.string.my_languages), kit.glyph(WheelArt.Glyph.NOTE, 38), "mylangs");
        // The two switches TV Town needs, each opening the right screen of the TV's settings (no developer mode).
        s.add(GlassSheet.ACTION, getContext().getString(R.string.setup_home), kit.glyph(WheelArt.Glyph.TV, 38), "helper");
        s.add(GlassSheet.ACTION, getContext().getString(R.string.setup_playing), kit.glyph(WheelArt.Glyph.NOTE, 38), "mediaaccess");
        if (!Tmdb.available()) s.add(GlassSheet.ACTION, getContext().getString(R.string.tmdb_key_row), kit.glyph(WheelArt.Glyph.KEY, 38), "tmdbkey");
        s.add(GlassSheet.ACTION, getContext().getString(R.string.weather_row), kit.glyph(WheelArt.Glyph.SUN, 38), "weather");
        s.add(GlassSheet.ACTION, getContext().getString(R.string.welcome_start), kit.glyph(WheelArt.Glyph.PLAY, 38), "start");
        styleAtOpen = Style.get() + "/" + Style.country();
        s.layout(getWidth(), getHeight(), 900, 9);
        sheet = s;
    }

    private void openCustomize() {
        openSettings();
    }

    /** The red button's settings: which button, and where songs and videos go. */
    private void openSaver(GlassSheet from) {
        GlassSheet s = new GlassSheet(kit, L10n.t("YouTube downloader"), WheelArt.Glyph.DRIVE, "saver", "");
        s.parent = from;
        s.add(GlassSheet.VALUE, L10n.t("Save songs to"), kit.glyph(WheelArt.Glyph.NOTE, 38), "saveaudio");
        s.add(GlassSheet.VALUE, L10n.t("Save videos to"), kit.glyph(WheelArt.Glyph.FILM, 38), "savevideo");
        s.layout(getWidth(), getHeight(), 900, 9);
        sheet = s;
    }

    // ---- Apps on Home: the dock's apps in order on top, every other app below to add ----

    private static final int DOCK_APPS = 5, ED_COLS = 6, ED_ROWS = 3;
    @Nullable
    private GlassSheet appsFrom;
    private boolean appsInTop = true, appsMoving;
    private int appsTopSel, appsSel, appsScroll;
    private String appsNote;
    private long appsNoteUntil;

    /** The apps on the dock now, in order (All apps, always last, is not among them). */
    private List<String> dockIds() {
        List<String> out = new ArrayList<>();
        for (int i : appSlots()) if (items.get(i).kind != ALL_APPS) out.add(items.get(i).id);
        return out;
    }

    private List<Item> otherApps() {
        List<String> on = dockIds();
        List<Apps.App> sorted = new ArrayList<>(apps);
        java.util.Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        List<Item> out = new ArrayList<>();
        for (Apps.App a : sorted) {
            if (on.contains(a.pkg) || a.pkg.equals(getContext().getPackageName())) continue;
            Item it = makeItem(a.pkg);
            if (it != null && it.app != null) out.add(it);
        }
        return out;
    }

    private void saveDock(List<String> ids) {
        List<String> o = new ArrayList<>(ids);
        o.add("allapps");
        saveOrder(o);
        buildItems();
    }

    private void openAppsEditor(GlassSheet from) {
        appsFrom = from;
        sheet = null;
        appsInTop = true;
        appsMoving = false;
        appsTopSel = appsSel = appsScroll = 0;
        openList(4, 0);
    }

    /** As tall as it needs: the dock row, then up to three rows of other apps. */
    private RectF edPanel(int moreRows) {
        float w = 1560, h = 162 + 150 + 92 + Math.max(1, Math.min(ED_ROWS, moreRows)) * 166 + 30;
        return gr((1920 - w) / 2, (1080 - h) / 2, w, h);
    }

    private void edTile(Canvas c, Item it, RectF r, boolean focused, boolean moving) {
        if (moving) Frost.plate(c, r, 26 * k, k, Frost.LILAC);
        else if (focused) Frost.plate(c, r, 26 * k, k);
        else {
            gFill.setShader(null);
            gFill.setColor(0x14FFFFFF);
            c.drawRoundRect(r, 26 * k, 26 * k, gFill);
        }
        float cx = r.centerX(), cy = r.top + r.height() * 0.40f;
        if (it.kind == ALL_APPS) icon(c, WheelArt.Glyph.GRID, 50, cx, cy, 200);
        else if (it.app != null) {
            Bitmap mark = LineIcon.appMark(it.app.pkg, it.name, 124 * k, 60 * k, hAppName.getTypeface());
            float sc = Math.min(1, (float) Math.sqrt(2600 * k * k / (mark.getWidth() * (float) mark.getHeight())));
            float mw = mark.getWidth() * sc, mh = mark.getHeight() * sc;
            c.drawBitmap(mark, null, new RectF(cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2), nImg);
        }
        String label = fit(it.kind == ALL_APPS ? L10n.t("All apps") : it.name, hAppName, r.width() - 20 * k);
        c.drawText(label, cx - hAppName.measureText(label) / 2, r.bottom - 26 * k, hAppName);
        if (moving) {   // arrows either side: it can move
            Paint ap = nk.inter(30, 700, 0, Color.WHITE);
            c.drawText("\u2039", r.left + 14 * k, cy + 10 * k, ap);
            c.drawText("\u203A", r.right - 14 * k - ap.measureText("\u203A"), cy + 10 * k, ap);
        }
    }

    private void glassAppsEditor(Canvas c) {
        screenRect.set(0, 0, getWidth(), getHeight());
        Frost.scrim(c, screenRect, 1, nImg);
        List<Item> more = otherApps();
        RectF p = edPanel((more.size() + ED_COLS - 1) / ED_COLS);
        Frost.panel(c, p, 40 * k, 1, k);
        Paint head = nk.inter(46, 500, -0.03f, Frost.INK), sec = nk.inter(26, 600, 0, Frost.INK), hint = nk.inter(22, 500, 0, Frost.MUTE);
        c.drawText(L10n.t("Apps on Home"), p.left + 48 * k, p.top + 76 * k, head);
        float pad = 40, gap = 16, cw = (1560 - 2 * pad - (ED_COLS - 1) * gap) / ED_COLS, ch = 150;
        // On Home: the dock's apps, then All apps (always there, always last)
        List<String> dock = dockIds();
        c.drawText(L10n.t("On Home"), p.left + 48 * k, p.top + 140 * k, sec);
        String h1 = appsMoving ? "\u2190 \u2192 to move   \u2193 takes it off Home   OK when done" : L10n.t("OK on an app to move it or take it off");
        c.drawText(h1, p.right - 48 * k - hint.measureText(h1), p.top + 140 * k, hint);
        float y = p.top / k + 162;
        for (int i = 0; i <= dock.size(); i++) {
            RectF r = gr(p.left / k + pad + i * (cw + gap), y, cw, ch);
            Item it = i < dock.size() ? makeItemCached(dock.get(i)) : makeItemCached("allapps");
            if (it != null) edTile(c, it, r, appsInTop && i == appsTopSel && i < dock.size(), appsMoving && i == appsTopSel);
        }
        // More apps: everything else on the TV
        float y2 = y + ch + 70;
        c.drawText(L10n.t("More apps"), p.left + 48 * k, y2 * k, sec);
        String h2 = dock.size() >= DOCK_APPS ? L10n.t("Home holds ") + DOCK_APPS + L10n.t(" apps: take one off to add another") : L10n.t("OK adds it to Home");
        c.drawText(h2, p.right - 48 * k - hint.measureText(h2), y2 * k, hint);
        if (more.isEmpty()) {
            c.drawText(L10n.t("Every app is on Home"), p.left + 48 * k, (y2 + 70) * k, hint);
        }
        int selRow = appsSel / ED_COLS;
        if (selRow < appsScroll) appsScroll = selRow;
        if (selRow >= appsScroll + ED_ROWS) appsScroll = selRow - ED_ROWS + 1;
        for (int i = appsScroll * ED_COLS; i < more.size() && i < (appsScroll + ED_ROWS) * ED_COLS; i++) {
            int col = i % ED_COLS, rw = i / ED_COLS - appsScroll;
            RectF r = gr(p.left / k + pad + col * (cw + gap), y2 + 22 + rw * (ch + gap), cw, ch);
            edTile(c, more.get(i), r, !appsInTop && i == appsSel, false);
        }
        int rows = (more.size() + ED_COLS - 1) / ED_COLS;
        if (rows > ED_ROWS) {
            String w = (appsScroll + 1) + "\u2013" + Math.min(rows, appsScroll + ED_ROWS) + L10n.t(" of ") + rows + L10n.t(" rows");
            c.drawText(w, p.right - 48 * k - hint.measureText(w), p.top + 76 * k, hint);
        }
        if (appsNote != null && SystemClock.uptimeMillis() < appsNoteUntil) {
            Paint np = nk.inter(26, 600, 0, Color.WHITE);
            float nw = np.measureText(appsNote) + 60 * k;
            RectF nb = new RectF(p.centerX() - nw / 2, p.bottom - 90 * k, p.centerX() + nw / 2, p.bottom - 30 * k);
            Frost.plate(c, nb, 30 * k, k, 0xFFFF5A5A);
            c.drawText(appsNote, nb.left + 30 * k, nb.centerY() + 9 * k, np);
            postInvalidateDelayed(appsNoteUntil - SystemClock.uptimeMillis() + 20);
        }
    }

    private final Map<String, Item> edItems = new HashMap<>();

    @Nullable
    private Item makeItemCached(String id) {
        Item it = edItems.get(id);
        if (it == null) {
            it = makeItem(id);
            if (it != null) edItems.put(id, it);
        }
        return it;
    }

    private boolean appsKey(int keyCode) {
        boolean l = keyCode == KeyEvent.KEYCODE_DPAD_LEFT, r = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT,
                u = keyCode == KeyEvent.KEYCODE_DPAD_UP, d = keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!l && !r && !u && !d) return false;
        List<String> dock = dockIds();
        int nMore = otherApps().size();
        if (appsInTop) {
            if (appsMoving) {
                if (l || r) {   // swap with the neighbour
                    int j = appsTopSel + (r ? 1 : -1);
                    if (j >= 0 && j < dock.size()) {
                        java.util.Collections.swap(dock, appsTopSel, j);
                        appsTopSel = j;
                        saveDock(dock);
                    }
                } else if (d) {   // off Home
                    if (dock.size() <= 1) {
                        appsNote = L10n.t("Keep at least one app on Home");
                        appsNoteUntil = SystemClock.uptimeMillis() + 2400;
                    } else {
                        dock.remove(appsTopSel);
                        saveDock(dock);
                        appsMoving = false;
                        appsTopSel = Math.min(appsTopSel, dock.size() - 1);
                    }
                }
            } else if (l) appsTopSel = Math.max(0, appsTopSel - 1);
            else if (r) appsTopSel = Math.min(dock.size() - 1, appsTopSel + 1);
            else if (d && nMore > 0) {
                appsInTop = false;
                appsSel = Math.min(nMore - 1, Math.min(appsTopSel, ED_COLS - 1));
            }
        } else {
            if (u) {
                if (appsSel < ED_COLS) {
                    appsInTop = true;
                    appsTopSel = Math.min(dock.size() - 1, appsSel % ED_COLS);
                } else appsSel -= ED_COLS;
            } else if (d) {
                if (appsSel + ED_COLS < nMore) appsSel += ED_COLS;
                else if (appsSel / ED_COLS < (nMore - 1) / ED_COLS) appsSel = nMore - 1;
            } else if (r) {
                if (appsSel % ED_COLS < ED_COLS - 1 && appsSel + 1 < nMore) appsSel++;
            } else if (appsSel % ED_COLS > 0) appsSel--;
        }
        invalidate();
        return true;
    }

    private void appsOk() {
        List<String> dock = dockIds();
        if (appsInTop) {
            if (!dock.isEmpty()) appsMoving = !appsMoving;
        } else {
            List<Item> more = otherApps();
            if (appsSel >= more.size()) return;
            if (dock.size() >= DOCK_APPS) {
                appsNote = L10n.t("Home holds ") + DOCK_APPS + L10n.t(" apps: take one off first");
                appsNoteUntil = SystemClock.uptimeMillis() + 2400;
            } else {
                dock.add(more.get(appsSel).id);
                saveDock(dock);
                appsSel = Math.max(0, Math.min(appsSel, more.size() - 2));
                if (otherApps().isEmpty()) appsInTop = true;
            }
        }
        invalidate();
    }

    /** Which apps sit on Home, as tiles. OK adds or takes one away; they sit in the order you add them. */
    private void openAppsGrid(GlassSheet from) {
        GlassSheet s = new GlassSheet(kit, L10n.t("Apps on Home"), WheelArt.Glyph.GRID, "appsgrid", "");
        s.gridCols = 5;
        List<String> chosen = order();
        List<String> candidates = new ArrayList<>(chosen);
        for (String id : new String[]{"discover", Services.YT_MUSIC_PKG, "inputs", "allapps"}) if (!candidates.contains(id)) candidates.add(id);
        List<Apps.App> sorted = new ArrayList<>(apps);
        java.util.Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        for (Apps.App a : sorted) if (!candidates.contains(a.pkg)) candidates.add(a.pkg);
        for (String id : candidates) {
            Item it = makeItem(id);
            if (it == null) continue;
            Bitmap mark = it.kind == ALL_APPS ? kit.glyph(WheelArt.Glyph.GRID, 64)
                    : it.app != null ? LineIcon.appMark(it.app.pkg, it.name, 124 * k, 60 * k, Typeface.DEFAULT) : LineIcon.appMark(id, it.name, 124 * k, 60 * k, Typeface.DEFAULT);
            GlassSheet.Row r = new GlassSheet.Row(GlassSheet.CHECK, it.name, mark, "item:" + id);
            r.checked = chosen.contains(id);
            s.rows.add(r);
        }
        s.layout(getWidth(), getHeight(), 900, 4);
        s.parent = from;
        sheet = s;
    }

    /** A short popup of choices: OK on one picks it (several, for languages). */
    private void openPicker(GlassSheet from, String title, String kind, String[] ids, String[] labels, java.util.Collection<String> now) {
        GlassSheet s = new GlassSheet(kit, title, WheelArt.Glyph.CHECK, "pick", "");
        s.popupW = 820;
        for (int i = 0; i < ids.length; i++) {
            GlassSheet.Row r = new GlassSheet.Row(GlassSheet.CHECK, labels[i], null, "pick:" + kind + ":" + ids[i]);
            r.checked = now.contains(ids[i]);
            s.rows.add(r);
        }
        s.layout(getWidth(), getHeight(), 900, 9);
        for (int i = 0; i < s.rows.size(); i++) {
            if (s.rows.get(i).checked) {
                s.sel = i;
                if (i >= s.visible) s.scroll = i - s.visible + 1;
                break;
            }
        }
        s.parent = from;
        sheet = s;
    }

    private void pickerFor(GlassSheet s, String tag) {
        Context c = getContext();
        if (tag.equals("interval")) {
            int[] v = GlassBackdrop.SCENE_SECONDS;
            String[] ids = new String[v.length], names = new String[v.length];
            for (int i = 0; i < v.length; i++) {
                ids[i] = String.valueOf(v[i]);
                names[i] = v[i] >= 60 ? (v[i] / 60) + L10n.t(" min") : v[i] + L10n.t(" seconds");
            }
            openPicker(s, L10n.t("Top picture changes every"), "interval", ids, names, java.util.Collections.singletonList(String.valueOf(GlassBackdrop.intervalSeconds(prefs))));
        } else if (tag.equals("dreamapp")) {
            List<Screensavers.Saver> all = Screensavers.all(c);
            String[] ids = new String[all.size()], names = new String[all.size()];
            for (int i = 0; i < all.size(); i++) {
                ids[i] = String.valueOf(i);
                names[i] = all.get(i).name;
            }
            int now = Screensavers.currentIndex(c, all);
            openPicker(s, L10n.t("Screensaver"), "saver", ids, names, java.util.Collections.singletonList(String.valueOf(now)));
        } else if (tag.equals("applang")) {
            String[] names = new String[Lang.CODES.length];
            for (int i = 0; i < names.length; i++) names[i] = Lang.name(Lang.CODES[i]);
            openPicker(s, c.getString(R.string.app_language), "applang", Lang.CODES, names, java.util.Collections.singletonList(Lang.chosen()));
        } else if (tag.equals("topleft") || tag.equals("topright")) {
            String[] names = new String[TOP_CHOICES.length];
            for (int i = 0; i < names.length; i++) names[i] = topName(TOP_CHOICES[i]);
            openPicker(s, L10n.t(tag.equals("topleft") ? "Top bar: left button" : "Top bar: right button"), tag, TOP_CHOICES, names,
                    java.util.Collections.singletonList(topButton(tag.equals("topleft") ? 1 : 3)));
        } else if (tag.equals("topsource")) {
            String[] names = new String[TOP_SOURCES.length];
            for (int i = 0; i < names.length; i++) names[i] = topSourceName(TOP_SOURCES[i]);
            openPicker(s, L10n.t("Top picture shows"), "topsource", TOP_SOURCES, names, java.util.Collections.singletonList(topSource(c)));
        } else if (tag.equals("iconpack")) {
            List<String[]> ch = IconPacks.choices(c);
            String[] ids = new String[ch.size()], names = new String[ch.size()];
            for (int i = 0; i < ch.size(); i++) {
                ids[i] = ch.get(i)[0].isEmpty() ? "none" : ch.get(i)[0];
                names[i] = ch.get(i)[1];
            }
            String now = IconPacks.chosen(c);
            openPicker(s, L10n.t("Icon pack"), "iconpack", ids, names, java.util.Collections.singletonList(now.isEmpty() ? "none" : now));
        } else if (tag.equals("openin")) {
            List<String> ch = OpenIn.choices(c);
            String[] ids = ch.toArray(new String[0]), names = new String[ids.length];
            for (int i = 0; i < ids.length; i++) names[i] = OpenIn.name(ids[i]);
            openPicker(s, L10n.t("Play films in"), "openin", ids, names, java.util.Collections.singletonList(OpenIn.chosen(c)));
        } else if (tag.equals("style")) {
            String[] names = new String[Style.STYLES.length];
            for (int i = 0; i < names.length; i++) names[i] = Style.name(Style.STYLES[i]);
            openPicker(s, c.getString(R.string.content_style), "style", Style.STYLES, names, java.util.Collections.singletonList(Style.get()));
        } else if (tag.equals("country")) {
            String[] ids = new String[Style.COUNTRIES.length + 1], names = new String[ids.length];
            ids[0] = "auto";
            names[0] = L10n.t("Automatic");
            for (int i = 0; i < Style.COUNTRIES.length; i++) {
                ids[i + 1] = Style.COUNTRIES[i][0];
                names[i + 1] = Style.COUNTRIES[i][1];
            }
            openPicker(s, c.getString(R.string.streaming_country), "country", ids, names, java.util.Collections.singletonList(Style.countryAuto(c) ? "auto" : Style.country()));
        } else {
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            for (String[] set : Style.MY_LANGUAGES) codes.addAll(Arrays.asList(set));
            String[] ids = codes.toArray(new String[0]), names = new String[ids.length];
            for (int i = 0; i < ids.length; i++) names[i] = Style.languagesName(java.util.Collections.singletonList(ids[i]));
            openPicker(s, c.getString(R.string.my_languages), "lang", ids, names, Style.myLanguages(c));
        }
    }

    private void pickerChose(GlassSheet s, GlassSheet.Row r, String tag) {
        Context c = getContext();
        String[] p = tag.split(":", 3);
        if (p[1].equals("lang")) {   // several may be ticked; at least one stays
            List<String> now = new ArrayList<>(Style.myLanguages(c));
            if (now.contains(p[2])) {
                if (now.size() <= 1) return;
                now.remove(p[2]);
            } else now.add(p[2]);
            Style.setMyLanguages(c, now);
            r.checked = now.contains(p[2]);
            return;
        }
        if (p[1].equals("applang")) {
            String code = p.length > 2 ? p[2] : "";
            if (!code.equals(Lang.chosen())) {
                Lang.choose(c, code);
                langChanged = true;   // TV Town opens again in it when Customize closes
            }
        } else if (p[1].equals("openin")) OpenIn.choose(c, p[2]);
        else if (p[1].equals("topleft")) prefs.edit().putString("top_left", p[2]).apply();
        else if (p[1].equals("topright")) prefs.edit().putString("top_right", p[2]).apply();
        else if (p[1].equals("topsource")) {
            prefs.edit().putString("top_source", p[2]).apply();
            freshLoadedAt = 0;   // the top picture: the new choice
            fresh.clear();
            refresh(true);
        } else if (p[1].equals("iconpack") && p[2].equals("get")) IconPacks.getMore(c);   // the Play Store's icon packs
        else if (p[1].equals("iconpack")) {
            IconPacks.choose(c, p[2].equals("none") ? "" : p[2]);
            synchronized (packIcons) {
                packIcons.clear();
            }
            packGen++;
        }
        else if (p[1].equals("interval")) prefs.edit().putInt("scene_seconds", Integer.parseInt(p[2])).apply();
        else if (p[1].equals("saver")) {
            List<Screensavers.Saver> all = Screensavers.all(c);
            int i = Integer.parseInt(p[2]);
            closeSheet();
            if (i < all.size()) useSaver(s.parent != null ? s.parent : s, all.get(i));
            return;
        } else if (p[1].equals("style")) Style.set(c, p[2]);
        else Style.setCountry(c, p[2].equals("auto") ? "" : p[2]);
        closeSheet();
    }

    /** Makes it the screensaver: straight away when TV Town may, else the TV's own screen with what to pick. */
    private void useSaver(GlassSheet s, Screensavers.Saver next) {
        Context c = getContext();
        if (next.kind >= 0) TvTownDream.setKind(c, next.kind);
        if (Screensavers.use(c, next.comp)) {
            s.say(next.name + L10n.t(" is the screensaver"));
            return;
        }
        if (RemoteKeys.READY.equals(RemoteKeys.state())) {   // the debugging link is already allowed: TV Town asks for the switch itself
            Screensavers.askToAllow(c);
            s.say(L10n.t("Switching\u2026"));
            postDelayed(() -> {
                if (Screensavers.use(c, next.comp)) s.say(next.name + L10n.t(" is the screensaver"));
                invalidate();
            }, 1500);
            return;
        }
        // No prompts on a new TV: the TV's own screen, and what to choose there.
        String pick = next.kind >= 0 ? c.getString(R.string.dream_name) : next.name;
        boolean direct = Screensavers.openTvSettings(c);
        android.widget.Toast.makeText(c, direct ? "Choose \"" + pick + "\" here"
                : "Device Preferences \u2192 Screen saver \u2192 choose \"" + pick + "\"", android.widget.Toast.LENGTH_LONG).show();
    }

    /** OK on Updates with a new version: what's new in it, then Install or Later. */
    private void openWhatsNew(GlassSheet from, Updater.Release u) {
        GlassSheet s = new GlassSheet(kit, L10n.t("What's new in ") + u.version, WheelArt.Glyph.REDO, "whatsnew", "");
        s.popupW = 1240;
        s.rows.add(new GlassSheet.Row(GlassSheet.ACTION, L10n.t("Install ") + u.version, kit.glyph(WheelArt.Glyph.DOWN, 38), "upd:install"));
        s.rows.add(new GlassSheet.Row(GlassSheet.ACTION, L10n.t("Later"), kit.glyph(WheelArt.Glyph.REDO, 38), "upd:later"));
        List<String> lines = Updater.noteLines(u.notes, 100);
        if (lines.isEmpty()) lines.add(L10n.t("Improvements and fixes."));
        for (String l : lines) {
            GlassSheet.Row r = new GlassSheet.Row(GlassSheet.NOTE, l, null, null);
            r.readable = !l.isEmpty();   // Down steps through the notes, so a long list scrolls
            s.rows.add(r);
        }
        s.layout(getWidth(), getHeight(), 1240, 9);
        s.parent = from;
        sheet = s;
        invalidate();
    }

    private void closeSheet() {
        if (sheet == null) return;
        if ("localsend".equals(sheet.id)) LocalSend.stop();   // not found any more once this closes
        if (sheet.parent != null) {
            sheet = sheet.parent;
            return;
        }
        if (sheet.sectionRows != null && !sheet.inRail) {   // Back from the rows: to the categories first
            sheet.inRail = true;
            return;
        }
        sheet.open = false;
        if ("welcome".equals(sheet.id)) {   // Back or Start: the choice is kept, and not asked again
            Style.set(getContext(), Style.get());
            prefs.edit().putBoolean("welcomed", true).apply();
        }
        if ("customize".equals(sheet.id) || "welcome".equals(sheet.id)) {
            buildItems();
            // A new style or country: the featured titles and New this week are for the new country.
            if (!(Style.get() + "/" + Style.country()).equals(styleAtOpen)) refresh(true);
            if (langChanged) {   // a new language: TV Town opens again, every screen and every film's words in it
                langChanged = false;
                restart();
            }
        }
    }

    private boolean langChanged;

    @Override
    @Nullable
    public String value(GlassSheet.Row r) {
        Context c = getContext();
        if (r.tag == null) return null;
        String tag = String.valueOf(r.tag);
        if (tag.startsWith("bt:")) {
            android.bluetooth.BluetoothDevice d = btDevice(tag.substring(3));
            if (d == null) return null;
            int battery = TvTools.battery(d);
            String b = battery >= 0 ? "  ·  " + battery + "%" : "";
            if (TvTools.kind(d) <= TvTools.BT_SPEAKER)
                return TvTools.connected(btProxy, d) ? L10n.t("Playing here") + b : L10n.t("Tap to play here");
            return (TvTools.linked(d) ? c.getString(R.string.bt_connected) : L10n.t("Not connected")) + b;
        }
        if (tag.equals("bttv")) {
            for (android.bluetooth.BluetoothDevice d : TvTools.speakers(c)) if (TvTools.connected(btProxy, d)) return L10n.t("Tap to play here");
            return L10n.t("Playing here");
        }
        switch (tag) {
            case "nowplaying": {
                android.media.session.MediaController m = TvTools.nowPlaying(c);
                if (m == null) return c.getString(mediaAccess() ? R.string.nothing_playing : R.string.needs_access);
                String a = TvTools.artist(m);
                return (TvTools.playing(m) ? "▶  " : "❚❚  ") + clip(TvTools.title(m) + (a.isEmpty() ? "" : "  ·  " + a), 34);
            }
            case "memory":
                return c.getString(R.string.mb_free, TvTools.freeMemoryMb(c));
            case "tmdbkey":
                return c.getString(Tmdb.available() ? R.string.key_added : R.string.key_missing);
            case "weather":
                return Weather.place(c).isEmpty() ? c.getString(R.string.weather_off) : Weather.place(c);
            case "update": {
                Updater.Release u = Updater.available;
                return u != null ? c.getString(R.string.update_available, u.version) : c.getString(R.string.update_current, BuildConfig.VERSION_NAME);
            }
            case "helper":
                return c.getString(HomeKeyService.enabled(c) ? R.string.setup_done : R.string.setup_todo);
            case "mediaaccess":
                return c.getString(mediaAccess() ? R.string.setup_done : R.string.setup_todo);
            case "storage":
                return TvTools.storage(c);
            case "bluetooth": {
                for (android.bluetooth.BluetoothDevice d : TvTools.speakers(c))
                    if (TvTools.connected(btProxy, d)) return TvTools.name(d);
                return c.getString(R.string.bt_none);
            }
            case "wakehome":
                return c.getString(HomeKeyService.wakeHome(c) ? R.string.wake_tvtown : R.string.wake_last);
            case "cricketon":
                return c.getString(Cricket.on(c) ? R.string.on : R.string.off);
            case "dreamapp":
                return Screensavers.currentName(c);
            case "picksfrom":
                return StreamRadar.chosen(c) ? L10n.t("StreamRadar") : "TMDB";
            case "saveaudio":
            case "savevideo": {
                java.io.File f = YtSaver.folder(c, tag.equals("savevideo"));
                return f == null ? L10n.t("No drive") : f.getName();
            }
            case "interval": {
                int s = GlassBackdrop.intervalSeconds(prefs);
                return s >= 60 ? (s / 60) + L10n.t(" min") : s + " s";
            }
            case "skipwatched":
                return c.getString(Library.skipWatched(c) ? R.string.on : R.string.off);
            case "colourkeys":
                return c.getString(HomeKeyService.colourKeys(c) ? R.string.on : R.string.off);
            case "apps":
                return order().size() + L10n.t(" apps");
            case "appearance":
                return Frost.lighter ? L10n.t("In a card") : L10n.t("Full screen");
            case "openin":
                return OpenIn.name(OpenIn.chosen(c));
            case "topsource":
                return topSourceName(topSource(c));
            case "topleft":
                return topName(topButton(1));
            case "topright":
                return topName(topButton(3));
            case "iconpack": {
                String pk = IconPacks.chosen(c);
                return pk.isEmpty() ? L10n.t("None") : IconPacks.installed(c).containsKey(pk) ? IconPacks.installed(c).get(pk) : L10n.t("None");
            }
            case "applang":
                return Lang.name(Lang.chosen());
            case "style":
                return Style.name(Style.get());
            case "look":
                return L10n.t("Neon glass");
            case "glassbg":
                return BG_NAMES[bgChoice()];
            case "mylangs":
                return Style.languagesName(Style.myLanguages(c));
            case "country":
                return Style.countryAuto(c) ? c.getString(R.string.country_auto, Style.countryName(Style.country()))
                        : Style.countryName(Style.country());
            default:
                if (String.valueOf(r.tag).startsWith("f:")) return c.getString(Features.on(c, String.valueOf(r.tag).substring(2)) ? R.string.on : R.string.off);
                if (r.kind == GlassSheet.CHECK && r.checked && String.valueOf(r.tag).startsWith("item:")) {
                    int i = order().indexOf(String.valueOf(r.tag).substring(5));
                    return i >= 0 ? String.valueOf(i + 1) : null;
                }
                return null;
        }
    }

    @Override
    public void change(GlassSheet s, GlassSheet.Row r, int dir, boolean wrap) {
        Context c = getContext();
        String tag = String.valueOf(r.tag);
        switch (tag) {
            case "applang": {   // the next language; every screen opens again in it
                int at = java.util.Arrays.asList(Lang.CODES).indexOf(Lang.chosen());
                Lang.choose(c, Lang.CODES[Math.floorMod(at + (dir == 0 ? 1 : dir), Lang.CODES.length)]);
                langChanged = true;
                return;
            }
            case "openin": {
                List<String> ch = OpenIn.choices(c);
                OpenIn.choose(c, ch.get(Math.floorMod(ch.indexOf(OpenIn.chosen(c)) + (dir == 0 ? 1 : dir), ch.size())));
                return;
            }
            case "appearance":
                Frost.setLighter(c, !Frost.lighter);
                homeArtFor = "";   // the blurred picture is made differently for each look
                return;
            case "wakehome":
                HomeKeyService.setWakeHome(c, !HomeKeyService.wakeHome(c));
                return;
            case "f:" + Features.PHONE:
            case "f:" + Features.APPS:
            case "f:" + Features.YT_SAVE:
            case "f:" + Features.SURPRISE:
            case "f:" + Features.MOVIES:
            case "f:" + Features.MUSIC: {
                String f = tag.substring(2);
                Features.set(c, f, !Features.on(c, f));
                invalidate();
                return;
            }
            case "cricketon":
                Cricket.setOn(c, !Cricket.on(c));
                return;
            case "dreamapp": {
                List<Screensavers.Saver> all = Screensavers.all(c);
                if (all.isEmpty()) return;
                int at = Screensavers.currentIndex(c, all);
                useSaver(s, all.get(Math.floorMod(at + dir, all.size())));
                return;
            }
            case "picksfrom":
                if (!Tmdb.available()) {
                    s.say(L10n.t("Add a TMDB key to switch"));
                    return;
                }
                StreamRadar.setChosen(c, !StreamRadar.chosen(c));
                refresh(true);
                return;
            case "saveaudio":
            case "savevideo":
                c.startActivity(new android.content.Intent(c, SaveFolderActivity.class).putExtra(SaveFolderActivity.EXTRA_VIDEO, tag.equals("savevideo")));
                return;
            case "interval": {
                int[] v = GlassBackdrop.SCENE_SECONDS;
                int cur = GlassBackdrop.intervalSeconds(prefs), i = 1;
                for (int j = 0; j < v.length; j++) if (v[j] == cur) i = j;
                i = wrap ? Math.floorMod(i + dir, v.length) : Math.max(0, Math.min(v.length - 1, i + dir));
                prefs.edit().putInt("scene_seconds", v[i]).apply();
                return;
            }
            case "skipwatched":
                Library.setSkipWatched(c, !Library.skipWatched(c));
                return;
            case "colourkeys":
                HomeKeyService.setColourKeys(c, !HomeKeyService.colourKeys(c));
                return;
            case "style":
                Style.set(c, step(Style.STYLES, Style.get(), dir, true));
                return;
            case "look":
                
                return;
            case "glassbg":
                prefs.edit().putInt("glass_bg", Math.floorMod(bgChoice() + dir, BG_NAMES.length)).apply();
                return;
            case "mylangs": {
                String[] all = new String[Style.MY_LANGUAGES.length];
                for (int i = 0; i < all.length; i++) all[i] = String.join(",", Style.MY_LANGUAGES[i]);
                String now = String.join(",", Style.myLanguages(c));
                Style.setMyLanguages(c, java.util.Arrays.asList(step(all, now, dir, true).split(",")));
                return;
            }
            case "country": {   // Automatic, then each country
                String[] codes = new String[Style.COUNTRIES.length + 1];
                codes[0] = "";
                for (int i = 0; i < Style.COUNTRIES.length; i++) codes[i + 1] = Style.COUNTRIES[i][0];
                Style.setCountry(c, step(codes, Style.countryAuto(c) ? "" : Style.country(), dir, true));
                return;
            }
            default:
                if (tag.startsWith("item:") && r.checked) {   // Left/Right moves a chosen app earlier or later
                    List<String> o = order();
                    String id = tag.substring(5);
                    int i = o.indexOf(id), j = i + dir;
                    if (i < 0 || j < 0 || j >= o.size()) return;
                    o.set(i, o.get(j));
                    o.set(j, id);
                    saveOrder(o);
                }
        }
    }

    private static String step(String[] values, String current, int dir, boolean wrap) {
        int i = Math.max(0, Arrays.asList(values).indexOf(current));
        i = wrap ? Math.floorMod(i + dir, values.length) : Math.max(0, Math.min(values.length - 1, i + dir));
        return values[i];
    }

    @Override
    public void activate(GlassSheet s, GlassSheet.Row r) {
        String tag = String.valueOf(r.tag);
        if (tag.equals("control")) {
            openControlCenter();
            return;
        }
        if (tag.equals("style") || tag.equals("country") || tag.equals("mylangs") || tag.equals("interval") || tag.equals("dreamapp")
                || tag.equals("applang") || tag.equals("iconpack") || tag.equals("topsource") || tag.equals("topleft") || tag.equals("topright") || tag.equals("openin") && OpenIn.choices(getContext()).size() > 2) {
            pickerFor(s, tag);
            return;
        }
        if (tag.startsWith("upd:")) {
            Updater.Release u = Updater.available;
            if (tag.equals("upd:install") && u != null) Updater.install(getContext(), u, msg -> s.say(msg));
            else closeSheet();
            return;
        }
        if (tag.startsWith("fixpick:")) {
            fixPicked(Integer.parseInt(tag.substring(8)));
            return;
        }
        if (tag.startsWith("cont:")) {
            contOption(tag.substring(5));
            return;
        }
        if (tag.startsWith("pick:")) {
            pickerChose(s, r, tag);
            return;
        }
        if (tag.equals("apps")) {
            openAppsEditor(s);
            return;
        }
        if (tag.equals("saver")) {
            openSaver(s);
            return;
        }
        if (tag.equals("dreamtry")) {
            if (!Screensavers.tryNow(getContext())) s.say(L10n.t("This TV can't start it from here: it starts on its own when the TV is idle"));
            return;
        }
        if (tag.equals("dreamget")) {
            Screensavers.getFromStore(getContext(), Screensavers.AERIAL);
            return;
        }
        if (tag.equals("dreamsys")) {
            Screensavers.openTvSettings(getContext());
            return;
        }
        if (tag.equals("receive")) {
            openReceive(s);
            return;
        }
        if (tag.equals("savephone")) {
            HomeKeyService.pasteLink();
            return;
        }
        if (r.kind == GlassSheet.VALUE) {
            change(s, r, 1, true);
            return;
        }
        if (tag.equals("bttv")) {   // sound back on the TV: every Bluetooth speaker let go
            boolean any = false;
            for (android.bluetooth.BluetoothDevice d : TvTools.speakers(getContext()))
                if (TvTools.connected(btProxy, d)) any |= TvTools.connect(btProxy, d, false);
            s.say(any ? L10n.t("Sound back on the TV") : L10n.t("Sound is on the TV"));
            return;
        }
        if (tag.startsWith("bt:")) {   // sound to this speaker (the one playing now lets go); others: Android's own page
            android.bluetooth.BluetoothDevice d = btDevice(tag.substring(3));
            if (d == null) return;
            if (TvTools.kind(d) > TvTools.BT_SPEAKER) {
                s.say(TvTools.linked(d) ? L10n.t("Connected. To change it, open Pair a new device") : L10n.t("Turn it on to connect it"));
                return;
            }
            boolean on = TvTools.connected(btProxy, d);
            s.say(getContext().getString(TvTools.connect(btProxy, d, !on) ? (on ? R.string.bt_disconnecting : R.string.bt_connecting) : R.string.bt_failed));
            return;
        }
        switch (tag) {
            case "nowplaying": {
                android.media.session.MediaController m = TvTools.nowPlaying(getContext());
                if (m == null) {
                    if (!mediaAccess()) s.say(getContext().getString(R.string.needs_access_hint));
                    return;
                }
                if (TvTools.playing(m)) m.getTransportControls().pause();
                else m.getTransportControls().play();
                return;
            }
            case "memory":   // Free up this TV: its own page
                getContext().startActivity(new Intent(getContext(), CleanupActivity.class));
                return;
            case "storage":
                s.say(TvTools.storage(getContext()));
                return;
            case "tmdbkey":
                openKeySetup();
                return;
            case "weather":
                openWeatherSetup(s);
                return;
            case "update": {
                Updater.Release u = Updater.available;
                if (u != null) {
                    openWhatsNew(s, u);
                    return;
                }
                s.say(getContext().getString(R.string.updates_checking_now));
                Updater.check(getContext(), true, found -> post(() -> {
                    s.say(found != null ? getContext().getString(R.string.update_available, found.version)
                            : getContext().getString(R.string.update_current, BuildConfig.VERSION_NAME));
                    invalidate();
                }));
                return;
            }
            case "mediaaccess":   // notification access: what's playing, and Continue from your apps
                s.say(getContext().getString(R.string.setup_playing_hint));
                try {
                    getContext().startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    s.say(getContext().getString(R.string.setup_playing_manual));
                }
                return;
            case "bluetooth":
                openBluetooth(s);
                return;
            case "btsettings":
                try {
                    getContext().startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    s.say(getContext().getString(R.string.not_available));
                }
                return;
            case "start": closeSheet(); return;
            case "wifi": listener.onWifi(); return;
            case "system": listener.onSystemSettings(); return;
            case "backup": {   // everything you set up, to the drive when there is one, else Downloads/TV Town
                String at = Backup.save(getContext());
                s.say(at != null ? getContext().getString(R.string.backup_saved, at) : getContext().getString(R.string.export_failed));
                return;
            }
            case "restore": {
                int n = Backup.restore(getContext());
                if (n < 0) {   // no full backup: an older watched-list export still counts
                    int titles = Library.importFrom(getContext());
                    s.say(titles >= 0 ? getContext().getString(R.string.imported_n, titles) : getContext().getString(R.string.backup_none));
                    return;
                }
                s.say(getContext().getString(R.string.restored_n, n));
                postDelayed(this::restart, 1500);   // everything read again from the restored settings
                return;
            }
            case "helper":   // Android lets only the viewer switch an accessibility service on
                s.say(getContext().getString(R.string.helper_enable_hint));
                listener.onAccessibilitySettings();
                return;
            case "stockhome":
                // The TV's own home becomes the default until TV Town is opened from the app list again.
                HomeKeyService.setMode(getContext(), HomeKeyService.STOCK);
                if (!listener.onOpenStockHome()) {
                    HomeKeyService.setMode(getContext(), HomeKeyService.TV_TOWN);
                    s.say(getContext().getString(R.string.stock_home_off));
                } else {
                    sheet = null;
                }
                return;
            default:
        }
        if (tag.startsWith("item:")) {
            List<String> o = order();
            String id = tag.substring(5);
            if (o.contains(id)) {
                if (o.size() <= 1) return;
                o.remove(id);
                r.checked = false;
            } else if (o.size() >= MAX_ITEMS) {
                s.say(getContext().getString(R.string.carousel_full, MAX_ITEMS));
                return;
            } else {
                o.add("allapps".equals(o.get(o.size() - 1)) ? o.size() - 1 : o.size(), id);   // keep All apps last
                r.checked = true;
            }
            saveOrder(o);
        }
    }

    // ---- keys ------------------------------------------------------------------------------------

    /** Back: closes the topmost layer, else returns to the apps row. Always handled. */
    boolean back() {
        touch();
        if (listAll == 4 && appsMoving) {   // put the app down where it is
            appsMoving = false;
            invalidate();
            return true;
        }
        if (listAll == 4 && appsFrom != null) {   // Apps on Home: back to Customize
            listAll = 0;
            sheet = appsFrom;
            appsFrom = null;
            invalidate();
            return true;
        }
        if (listAll != 0) {   // Show all: back to Home
            listAll = 0;
            invalidate();
            return true;
        }
        if (sheet != null && sheet.open) closeSheet();
        else if (picker != null && picker.open) {
            if (!picker.back()) picker.open = false;   // from the three picks, Back returns to the moods
        }
        else if (info != null) info = null;
        else if (allAppsOpen) allAppsOpen = false;
        else if (inputs != null) inputs = null;
        else setRow(Row.APPS);
        invalidate();
        return true;
    }

    void reset() {
        ControlCenter.closeIfOpen();
        boolean setup = sheet != null && "welcome".equals(sheet.id) && !prefs.getBoolean("welcomed", false);
        if (!setup) sheet = null;   // Home in the middle of the first setup keeps the setup open
        picker = null;
        lastPicker = null;   // Home is Home: the last results are forgotten
        info = null;
        allAppsOpen = false;
        allT = infoT = 0;
        inputs = null;
        listAll = 0;   // the Continue list, too
        setRow(Row.APPS);
        invalidate();
    }

    /**
     * Back from something played from the Random pop-up (within two hours): the same results again, on the same
     * card. True when they were shown.
     */
    boolean returnToResults() {
        RandomSheet p = lastPicker;
        boolean fresh = p != null && SystemClock.uptimeMillis() - lastPickerAt <= KEEP_RESULTS_MS;
        lastPicker = null;
        // TV Town stops animating while the app is in front, so the pop-up may not have finished closing yet.
        if (picker != null && picker.launched) {
            p = picker;
            fresh = true;
        }
        if (p == null || !fresh || kit == null) return false;
        sheet = null;
        info = null;
        allAppsOpen = false;
        inputs = null;
        p.reopen();
        p.attach(pickerListener);
        picker = p;
        invalidate();
        return true;
    }

    /** A coloured button: that Random pop-up (0 Movie, 1 Series, 2 Music, 3 Extras), from anywhere. */
    /** A coloured button (0 to 3): red My Movies, green My Music, yellow Random Movie, blue Random Series. */
    /** The phone remote asked for one of these. */
    void fromPhone(String place) {
        reset();
        switch (place) {
            case "surprise": openRandom(0); break;
            case "search": askSearch(); break;
            case "control": openControlCenter(); break;
            case "settings": openSettings(); break;
            default: break;
        }
    }

    void colourButton(int button) {
        if (picker != null && picker.open && picker.kind != Picks.SEARCH) {   // in Can't decide?: the four tabs
            pickerListener.switchKind(button);
            return;
        }
        Context fc = getContext();
        if (button == 0 && listener != null && Features.on(fc, Features.MOVIES)) listener.onMyMovies();
        else if (button == 1 && listener != null && Features.on(fc, Features.MUSIC)) listener.onMyMusic();
        else if (button == 2 && Features.on(fc, Features.SURPRISE)) openRandom(0);
        else if (button == 3 && Features.on(fc, Features.SURPRISE)) openRandom(1);
    }

    void openRandom(int kind) {
        if (!Features.on(getContext(), Features.SURPRISE)) return;   // switched off in Customize
        if (kit == null || kind < 0 || kind > 3) return;
        reset();
        randSel = kind;
           // the picture layout has no Random row: the focus stays where it was
        picker = new RandomSheet(getContext(), kit, kind, getWidth(), getHeight()).host(this);
        picker.attach(pickerListener);
        invalidate();
    }

    /**
     * Red, green, yellow and blue on the remote, as 0 to 3 (-1 for other keys): My Movies, My Music, Random Movie and
     * Random Series (colourButton).
     */
    static int colourKind(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PROG_RED: return 0;
            case KeyEvent.KEYCODE_PROG_GREEN: return 1;
            case KeyEvent.KEYCODE_PROG_YELLOW: return 2;
            case KeyEvent.KEYCODE_PROG_BLUE: return 3;
            default: return -1;
        }
    }

    private boolean redDown, redHeld;
    private final Runnable redHold = () -> {
        redHeld = true;
        if (listener != null) listener.onSaveSearch();
    };

    private final Runnable snapBehind = () -> {
        if (noOverlay() && running && getContext() instanceof android.app.Activity) Frost.capture((android.app.Activity) getContext());
    };

    /** A moment after the screen comes to rest: keep the blurred picture for pop-ups up to date. */
    private void snapSoon() {
        removeCallbacks(snapBehind);
        postDelayed(snapBehind, 700);
    }

    private void touch() {
        snapSoon();
        lastInput = SystemClock.uptimeMillis();
        if (idlePaused) {
            idlePaused = false;
            schedule();
        }
    }

    private void setRow(Row r) {
        if (r == row) return;
        boolean wasCovers = row == Row.CONT || row == Row.LIST || row == Row.NOTHING, covers = r == Row.CONT || r == Row.LIST || r == Row.NOTHING;
        if (covers && !wasCovers) shelfAt = barAt = SystemClock.uptimeMillis();   // the bar turns away, the covers rise
        else if (covers) shelfAt = SystemClock.uptimeMillis();                     // the next page of covers rises
        if (wasCovers && !covers) barAt = SystemClock.uptimeMillis();               // the bar comes back
        row = r;
        rowChanged = sweepStart = SystemClock.uptimeMillis();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        touch();
        long t = SystemClock.uptimeMillis();
        
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {   // the mic button
            if (event.getRepeatCount() == 0 && listener != null) listener.onVoice();
            return true;
        }
        if (MusicPlayer.get(getContext()).mediaKey(keyCode)) {   // a song is on: the remote's ⏯ ⏭ ⏮ work here too
            invalidate();
            return true;
        }
        if (keyCode == YtSaver.BUTTONS[YtSaver.button(getContext())]) {   // a press as before; held, a link from the phone to save
            if (event.getRepeatCount() == 0) {
                redDown = true;
                redHeld = false;
                removeCallbacks(redHold);
                postDelayed(redHold, 700);
            }
            return true;
        }
        if (colourKind(keyCode) >= 0) {   // without the home helper the coloured buttons still work here
            colourButton(colourKind(keyCode));
            return true;
        }
        if (isSelect(keyCode)) {
            if (event.getRepeatCount() == 0) {
                longPressed = false;
                event.startTracking();
            }
            return true;
        }
        boolean handled;
        if (sheet != null && sheet.open) handled = sheet.key(keyCode, this);
        else if (picker != null && picker.open) handled = picker.key(keyCode);
        else if (info != null) handled = infoKey(keyCode);
        else if (allAppsOpen) handled = gridKey(keyCode);
        else if (inputs != null) handled = inputKey(keyCode);
        else if (listAll != 0) handled = listAllKey(keyCode);
        else handled = glassKey(keyCode);
        if (handled) invalidate();
        return handled || super.onKeyDown(keyCode, event);
    }

    private boolean gridKey(int keyCode) {
        int per = GRID_COLS * GRID_ROWS, pages = Math.max(1, (apps.size() + per - 1) / per);
        int onPage = Math.min(per, apps.size() - allPage * per);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (allSel % GRID_COLS > 0) allSel--;
                else if (allPage > 0) {
                    allPage--;
                    allSel = Math.min(allSel + GRID_COLS - 1, per - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (allSel % GRID_COLS < GRID_COLS - 1 && allSel + 1 < onPage) allSel++;
                else if (allPage < pages - 1) {
                    allPage++;
                    allSel = Math.min(allSel - allSel % GRID_COLS, Math.min(per, apps.size() - allPage * per) - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (allSel >= GRID_COLS) allSel -= GRID_COLS;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (allSel + GRID_COLS < onPage) allSel += GRID_COLS;
                else if (allSel / GRID_COLS < (onPage - 1) / GRID_COLS) allSel = onPage - 1;   // a shorter last row: its last app
                return true;
            default:
                return false;
        }
    }

    private boolean infoKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) infoSel = Math.max(0, infoSel - 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) infoSel = Math.min(infoButtons().length - 1, infoSel + 1);
        return isDpad(keyCode);
    }

    private boolean inputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) inputSel = Math.max(0, inputSel - 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) inputSel = Math.min(inputs.size() - 1, inputSel + 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) inputs = null;
        return isDpad(keyCode);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (!isSelect(keyCode)) return super.onKeyLongPress(keyCode, event);
        longPressed = true;
        if (noOverlay() && row == Row.CONT && contAt < contRow().size()) {   // held: the same details pop-up as a press
            openContDetails(contRow().get(contAt));
            return true;
        }
        Apps.App app = null;
        if (allAppsOpen) {
            int idx = allPage * GRID_COLS * GRID_ROWS + allSel;
            if (idx < apps.size()) app = apps.get(idx);
        } else if (noOverlay() && row == Row.APPS && front() != null) {
            app = front().app;
        }
        if (app != null && listener != null) listener.onAppInfo(app);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == YtSaver.BUTTONS[YtSaver.button(getContext())]) {
            removeCallbacks(redHold);
            if (redDown && !redHeld && !event.isCanceled() && colourKind(keyCode) >= 0) colourButton(colourKind(keyCode));
            redDown = false;
            return true;
        }
        if (!isSelect(keyCode)) return super.onKeyUp(keyCode, event);
        if (!longPressed && !event.isCanceled() && event.isTracking()) select();
        invalidate();
        return true;
    }

    private void select() {
        if (listAll != 0) {   // Show all: play the one chosen
            listAllSelect();
            return;
        }
        if (listener == null) return;
        if (sheet != null && sheet.open) {
            if (sheet.sectionRows != null && sheet.inRail) sheet.railOk(this);
            else if (sheet.solo != null) {
                if (!sheet.solo.key(KeyEvent.KEYCODE_DPAD_CENTER)) closeSheet();
            } else if (sheet.hasPane()) sheet.key(KeyEvent.KEYCODE_DPAD_CENTER, this);
            else activate(sheet, sheet.rows.get(sheet.sel));
            return;
        }
        if (picker != null && picker.open) {
            picker.select(pickerListener);
            return;
        }
        if (info != null) {
            Tmdb.Title t = info;
            if (infoSel == 0) listener.onPlay(t);
            else if (t.trailer != null) listener.onTrailer(t);
            info = null;
            return;
        }
        if (allAppsOpen) {
            int idx = allPage * GRID_COLS * GRID_ROWS + allSel;
            if (idx < apps.size()) {
                allAppsOpen = false;
                listener.onOpenApp(apps.get(idx), null);
            }
            return;
        }
        if (inputs != null) {
            Inputs.Source s = inputs.get(inputSel);
            inputs = null;
            listener.onInput(s);
            return;
        }
        if (glassSelect()) return;
        switch (row) {
            case TOP:
                switch (topSel) {
                    case T_SEARCH: askSearch(); return;
                    case T_CUSTOMIZE: openCustomize(); return;
                    case T_WIFI: listener.onWifi(); return;
                    case T_HOME: setRow(Row.APPS); return;   // already here: down to the apps
                    case T_DISCOVER: listener.onDiscover(); return;
                    case T_MUST: listener.onMustWatch(); return;
                    case T_MOVIES: listener.onMyMovies(); return;
                    case T_MUSIC: listener.onMyMusic(); return;
                    default: openSettings();
                }
                return;
            case APPS:
                openItem(front(), false);
                return;
            case ACTIONS:
                if (actSel < actions.size()) runAction(actions.get(actSel));
                return;
            case SIDE:
                openSide(sideSel);
                return;
            case NOWP:   // Now playing: the player
                listener.onMyMusic();
                return;
            case CONT:   // View all (above the rows), or the film
                if (contSel < 0) {
                    listAll = 1;
                    listSel = 0;
                } else if (contSel < contRow().size()) openContDetails(contRow().get(contSel));
                return;
            case NEW: {
                {
                    if (freshSel < 0) {
                        listAll = 2;
                        listSel = 0;
                    } else if (freshSel < fresh.size()) listener.onPlay(fresh.get(freshSel));
                    return;
                }
            }

            default:
                picker = new RandomSheet(getContext(), kit, randSel, getWidth(), getHeight()).host(this);   // picture cards
                picker.attach(pickerListener);
        }
    }

    /** OK on an app card opens the app itself; the actions row is for the featured title. */
    private void openItem(@Nullable Item it, boolean fromAction) {
        if (it == null) return;
        switch (it.kind) {
            case DISCOVER: listener.onDiscover(); return;
            case ALL_APPS: openAllApps(); return;
            case INPUTS: openInputs(); return;
            default:
                if (it.app != null) listener.onOpenApp(it.app, centre());
        }
    }

    private void runAction(Action a) {
        Item it = front();
        switch (a.kind) {
            case "play": if (it != null && it.feature != null) listener.onPlay(it.feature); break;
            case "trailer": if (it != null && it.feature != null && it.feature.trailer != null) listener.onTrailer(it.feature); break;
            case "info": if (it != null && it.feature != null) openInfo(it.feature); break;
            case "discover": listener.onDiscover(); break;
            case "allapps": openAllApps(); break;
            case "inputs": openInputs(); break;
            default: openItem(it, true);
        }
    }

    private void openAllApps() {
        allAppsOpen = true;
        allPage = 0;
        allSel = 0;
    }

    private Rect centre() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int x = Math.round(CAR_X * k) + loc[0], y = Math.round(CAR_Y * k) + loc[1], r = Math.round(120 * k);
        return new Rect(x - r, y - r, x + r, y + r);
    }

    private static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }
}
