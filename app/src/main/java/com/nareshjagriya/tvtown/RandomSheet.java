package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Random pop-up behind Random Movie, Series, Music and Extras: up to 12 big picture cards (Comedy, Thriller &
 * Crime… or Romantic, Top Singers… or Live News, Cartoons…), the languages down the right (music also has Song /
 * Playlist under them), Surprise me at the bottom and ✏️ Customize at the top. OK on a card starts it; the remote
 * moves to the nearest item in each direction.
 *
 * A card turns the panel into a row of big result cards that scrolls sideways: films and series (picture, title,
 * year, length, rating, genres, app, summary) or songs, playlists and videos, with Not for me under the focused card
 * and Just play, Shuffle and Categories at the bottom. My Mix plays straight away.
 *
 * Customize lists the cards on the left; on the right, what the chosen card plays (its kinds of film, singers,
 * shows, channels or searches) to switch on or off, and "Add" to type in more. Its last page chooses which languages
 * are on the list.
 */
final class RandomSheet {

    interface Listener {
        /** The viewer's own YouTube mix. */
        void myMix();

        /** Up to `count` new random titles for a card; `done` runs on the main thread. */
        void pickTitles(int kind, Picks.Entry card, List<String> ticked, int count, Consumer<List<Tmdb.Title>> done);

        void open(Tmdb.Title title);

        /** Songs, playlists or videos for a card; `done` runs on the main thread. */
        void findMusic(int kind, Picks.Entry card, List<String> ticked, List<String> shown, boolean playlists,
                       Consumer<List<Songs.Pick>> done);

        void playMusic(Songs.Pick pick);

        /** Fills in a playlist's first songs; `done` runs on the main thread. */
        void peek(Songs.Pick pick, Runnable done);

        /** Search: films and series on your apps, then YouTube videos; `done` runs on the main thread. */
        void search(String query, Consumer<List<Object>> done);

        void trailer(Tmdb.Title title);

        /** Search again: ask for new words. */
        void searchAgain();

        /** A picture for a card (or null); `done` runs on the main thread. */
        void cardArt(int kind, Picks.Entry card, String language, Consumer<String> done);

        /** Asks for a line of text with the TV's keyboard; `done` runs on the main thread with what was typed. */
        void askText(String title, String hint, Consumer<String> done);

        /** A picture arrived: draw again. */
        default void redraw() {
        }

        /** Another tab (Movie, Series, Music, Extras): the pop-up opens again on it. */
        void switchKind(int kind);
    }

    private static final int TILE = 0, LANG = 2, PLAY = 3, TAB = 5, FORMAT = 8, CUSTOMIZE = 9, SKIP = 15, SEG = 16;   // the cards screen
    // The language column: a two-by-two switch for the two main languages, then a tick for each other one.
    private static final String MAIN_A = "English", MAIN_B = "Hindi";
    private float moreLabelY = -1, langCaptionY = -1;
    private String langNote;          // L10n.t("Keep at least one language"), for a moment
    private long langNoteUntil;
    private static final int C_CARD = 10, C_CHIP = 11, C_ADD = 12, C_HIDE = 13, C_DELETE = 14;   // Customize
    private static final int CARDS = 0, RESULTS = 1, CUSTOM = 2, TOPICS = 3;                         // what the panel shows
    private static final int TITLES_PER_ROUND = 8, LANGUAGES_PAGE = -1, NEW_CARD = -2;

    // Layout, in design pixels.
    private static final float PANEL_W = 1560, PANEL_H = 1040, PAD = 56, LEFT_W = 1040, LANG_W = 330;
    private static final float TILE_H = 160, TILE_GAP = 20, PLAY_W = 480, PLAY_H = 96, TOP = 200;
    private static final float C_LIST_W = 400, C_ROW = 54, C_CHIP_H = 56;

    private static final class Item {
        final int group, index;
        final String emoji, label;
        final int color;
        final RectF rect = new RectF();
        boolean on, added;

        Item(int group, int index, String emoji, String label, int color) {
            this.group = group;
            this.index = index;
            this.emoji = emoji;
            this.label = label;
            this.color = color;
        }
    }

    final int kind;
    float t;                 // 0 closed .. 1 open
    boolean open = true;
    boolean launched;        // closed because something was played: Back from it comes back here
    private int mode = CARDS;
    private final GlassKit kit;
    private final float k;
    private final Context ctx;
    private final java.util.Random random = new java.util.Random();
    private final RectF panel = new RectF(), tmp = new RectF(), tmp2 = new RectF();
    private float langBottom;
    private final RectF screen = new RectF();
    private final Paint title, sub, label, tileEmoji, smallEmoji, langText, langBold, chipEmoji, playText;
    private final TextPaint tileText;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Bitmap disc, check;
    private final int accent;
    private final String heading;
    private Listener lastListener;

    // The cards screen.
    private List<Picks.Entry> cards;
    private List<Picks.Entry> allCards = new ArrayList<>();   // Customize: hidden cards too
    private String cNote;                                     // Customize: a short message…
    private long cNoteUntil;                                  // …shown until then
    private List<String> ticked, shown;
    private final List<Item> items = new ArrayList<>();
    private Item focus, tileFocus;
    private boolean playlists;               // the Song / Playlist switch (Song by default)
    private float formatLabelY, langY;
    private static final int COLS = 4;   // four to a row, big pictures; more moods scroll
    private int ROWS_SHOWN = 4;   // mood rows in view: all twelve at once
    private int tileTop, tileRows;           // the first mood row in view, and how many rows there are
    private final RectF tilesClip = new RectF();
    private final Map<String, String> art = new HashMap<>();   // card label -> picture
    private NoirKit nk;
    private Paint nMoodPlain;
    private Paint nTitle, nEyebrow, nTab, nLabel, nPill, nPillBold, nMood, nMoodEmoji, nPickTitle, nPickMeta, nSurprise, nSurpriseSub, nFoot;
    // Home's neon colours: Movie violet, Series blue, Music pink, Extras cyan.
    static final int[] TAB_COLOURS = {0xFF8B5CFF, 0xFF2E8BFF, 0xFFF03CBF, 0xFF00C2D8};
    static final int[] REMOTE = {0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5};   // the remote's red, green, yellow, blue

    // Results: a sideways row of cards, then Not for me (under the focused card), then the buttons.
    private Picks.Entry chosen;
    private boolean loading;
    private final List<Object> results = new ArrayList<>();   // Tmdb.Title or Songs.Pick
    private int mcol, mrow, mbtn, generation;   // focused card; row 0 cards, 1 Trailer (search), 2 buttons; button
    private String query;                       // Search: what was searched
    // Results found ahead, while a card is focused (by card and Song / Playlist), and searches on their way.
    private final Map<String, List<Object>> ready = new HashMap<>();
    private final Map<String, List<Consumer<List<Object>>>> coming = new HashMap<>();
    private final android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
    private float mFrom, mTo;
    private static final long SLIDE_MS = 280;   // the results row's slide: quick, like Home's rows
    private long mStart;
    private final java.util.Set<String> peeked = new java.util.HashSet<>();
    private final TextPaint cardTitle, cardBody;
    private final Paint cardMeta, nopeText, nextText, backText;
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private final Map<String, Bitmap> appIcons = new HashMap<>();

    // Customize: the cards (and Languages) on the left, what the chosen one plays on the right.
    private final List<Item> cList = new ArrayList<>(), cChips = new ArrayList<>();
    private int cSel;                        // index into cList
    private boolean cRight;
    private Item cFocus;
    private float cScroll, cListScroll;

    RandomSheet(Context ctx, GlassKit kit, int kind, int width, int height) {
        this.ctx = ctx;
        this.kit = kit;
        this.k = kit.k;
        this.kind = kind;
        title = kit.font(44, 700);
        title.setLetterSpacing(0.12f);
        sub = kit.font(24, 400);
        label = kit.font(18, 600);
        label.setLetterSpacing(0.25f);
        tileText = new TextPaint(kit.font(25, 700));
        tileEmoji = kit.font(42, 400);
        tileEmoji.setTextAlign(Paint.Align.CENTER);
        tileEmoji.clearShadowLayer();
        smallEmoji = kit.font(26, 400);
        smallEmoji.clearShadowLayer();
        chipEmoji = kit.font(28, 400);
        chipEmoji.clearShadowLayer();
        langText = kit.font(24, 500);
        langBold = kit.font(24, 600);
        cardTitle = new TextPaint(kit.font(32, 700));
        cardBody = new TextPaint(kit.font(24, 400));
        cardMeta = kit.font(23, 500);
        nopeText = kit.font(22, 600);
        nextText = kit.font(30, 700);
        nextText.setColor(Theme.INK);
        nextText.clearShadowLayer();
        backText = kit.font(28, 600);
        playText = kit.font(36, 700);
        playText.setColor(Theme.INK);
        playText.clearShadowLayer();
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        int[][] colours = {{Theme.AMBER_A, Theme.AMBER_B}, {Theme.VIOLET_A, Theme.VIOLET_B}, {Theme.MINT_A, Theme.MINT_B},
                {Theme.ROSE_A, Theme.ROSE_B}, {Theme.VIOLET_A, Theme.AMBER_B}};
        WheelArt.Glyph[] glyphs = {WheelArt.Glyph.FILM, WheelArt.Glyph.TV, WheelArt.Glyph.NOTE, WheelArt.Glyph.PLAY,
                WheelArt.Glyph.SEARCH};
        disc = WheelArt.glyphDisc(glyphs[kind], Math.round(72 * k), colours[kind][0], colours[kind][1]);
        accent = colours[kind][1];
        check = kit.glyph(WheelArt.Glyph.CHECK, 22);
        heading = ctx.getString(new int[]{R.string.random_movie, R.string.random_series, R.string.random_song, R.string.extras,
                R.string.search}[kind]);
        float pw = PANEL_W * k, ph = PANEL_H * k;
        panel.set((width - pw) / 2, (height - ph) / 2, (width + pw) / 2, (height + ph) / 2);
        screen.set(0, 0, width, height);
        nk = new NoirKit(ctx, k);
        // The neon glass look: Inter, white and soft blue-grey on see-through navy glass.
        nTitle = nk.inter(46, 500, -0.03f, 0xFFF4F4F1);
        nEyebrow = nk.inter(28, 500, 0, Frost.MUTE);
        nTab = nk.inter(30, 500, -0.012f, 0xFFF4F4F1);
        nLabel = nk.inter(26, 600, 0, Frost.FAINT);
        nPill = nk.inter(28, 500, -0.01f, 0xFFF4F4F1);
        nPillBold = nk.inter(28, 600, -0.01f, 0xFFFFFFFF);
        nMood = nk.inter(27, 600, -0.012f, 0xFFFFFFFF);
        nMoodPlain = nk.inter(28, 600, 0, Color.WHITE);
        nMoodPlain.setTextAlign(Paint.Align.CENTER);
        nMoodEmoji = kit.font(26, 400);
        nMoodEmoji.clearShadowLayer();
        nPickTitle = nk.inter(22, 700, 0, 0xFFFFFFFF);
        nPickMeta = nk.inter(13, 600, 0.08f, 0xFFD8D8D6);
        nSurprise = nk.inter(32, 600, -0.012f, 0xFFFFFFFF);
        nSurpriseSub = nk.inter(16, 500, 0, 0xFFE6E6E4);
        nFoot = nk.inter(13, 600, 0.14f, 0xFFA9A9A9);
        for (Paint p : new Paint[]{nTitle, nMood, nPickTitle, nSurprise}) p.setShadowLayer(6 * k, 0, 1 * k, 0x66000000);
        if (kind == Picks.SEARCH) {
            cards = new ArrayList<>();
            shown = new ArrayList<>();
            ticked = new ArrayList<>();
            return;
        }
        reload();
        focus = tabItem();   // the focus starts on the tabs: Left and Right switch Movie, Series, Music, Extras
    }

    private Item tabItem() {
        for (Item it : items) if (it.group == TAB && it.index == kind) return it;
        return playItem();
    }

    /** Search: the results for what was typed or said, straight away. */
    void search(String q, Listener l) {
        query = q;
        lastListener = l;
        mode = RESULTS;
        load(l);
    }

    private boolean hasLanguages() {
        return kind != Picks.SEARCH;
    }

    /** Cards and languages as customised, then the layout. */
    private void reload() {
        cards = Picks.cards(ctx, kind);
        allCards = Picks.allCards(ctx, kind);
        shown = hasLanguages() ? Picks.shownLanguages(ctx, kind) : new ArrayList<>();
        ticked = hasLanguages() ? Picks.ticked(ctx, kind) : new ArrayList<>();
        if (hasLanguages() && ticked.isEmpty() && !Picks.languagesSet(ctx, kind)) {   // L10n.t("Any") from before: both main ones, once
            for (String l : new String[]{MAIN_A, MAIN_B}) {
                Picks.setShown(ctx, kind, l, true);
                Picks.setTicked(ctx, kind, l, true);
            }
            shown = Picks.shownLanguages(ctx, kind);
            ticked = Picks.ticked(ctx, kind);
        }
        layout();
    }

    private Item playItem() {
        for (Item it : items) if (it.group == PLAY) return it;
        return items.get(0);
    }

    /**
     * The tabs top right; on the left the mood cards in rows of four with the languages (and Song / Playlist) under
     * them; on the right Surprise me and three picks ready to play; Customize at the bottom left.
     */
    /**
     * The moods screen, full screen like Home: the title, the tabs in one glass capsule and Customize along the top;
     * the moods as big pictures, four to a row; along the foot, one row of chips (Skip watched, the languages, Songs /
     * Playlists) and Surprise me. The arrows find the nearest item, so the places are all that is set here.
     */
    private void layout() {
        sizePanel(PANEL_H);
        items.clear();
        float W = screen.width(), x0 = 96 * k, right = W - 96 * k;
        // Tabs: side by side in one capsule, beside the title
        int[] names = {R.string.tab_movie, R.string.tab_series, R.string.tab_music, R.string.tab_extras};
        float tx = 640 * k, tg = 6 * k;
        for (int i = 0; i < 4; i++) {
            float tw = nTab.measureText(ctx.getString(names[i])) + 92 * k;
            Item it = new Item(TAB, i, null, ctx.getString(names[i]), TAB_COLOURS[i]);
            it.rect.set(tx, 52 * k, tx + tw, 120 * k);
            items.add(it);
            tx += tw + tg;
        }
        tabsRight = tx - tg;
        String cl = ctx.getString(R.string.customize);
        Item cust = new Item(CUSTOMIZE, 0, null, cl, NoirKit.GOLD);
        float cw = nPill.measureText(cl) + 104 * k;
        cust.rect.set(right - cw, 52 * k, right, 120 * k);
        items.add(cust);
        // The moods: four to a row, three rows in view (more scroll)
        ROWS_SHOWN = 3;
        float top = 172 * k, gap = 22 * k, gridW = right - x0;
        float tileW = (gridW - (COLS - 1) * gap) / COLS, tileH = (884 * k - top - (ROWS_SHOWN - 1) * gap) / ROWS_SHOWN;
        int rows = Math.max(1, (cards.size() + COLS - 1) / COLS);
        tileTop = Math.max(0, Math.min(Math.max(0, rows - ROWS_SHOWN), tileTop));
        tilesClip.set(x0 - 40 * k, top - 30 * k, right + 40 * k, top + ROWS_SHOWN * tileH + (ROWS_SHOWN - 1) * gap + 40 * k);
        for (int i = 0; i < cards.size(); i++) {
            Picks.Entry c = cards.get(i);
            Item it = new Item(TILE, i, c.emoji, c.label, TAB_COLOURS[kind]);
            int r = i / COLS - tileTop;
            float x = x0 + (i % COLS) * (tileW + gap), y = top + r * (tileH + gap);
            if (r < 0 || r >= ROWS_SHOWN) y = -5000 * k;   // out of view: not reached by the arrows' search
            it.rect.set(x, y, x + tileW, y + tileH);
            items.add(it);
        }
        tileRows = rows;
        // The foot: Surprise me on the right; the chips from the left, as many as fit
        float playW = 430 * k, fy = 930 * k, fh = 96 * k, chipH = 64 * k, cy = fy + (fh - chipH) / 2;
        Item play = new Item(PLAY, 0, "", ctx.getString(R.string.surprise_me), TAB_COLOURS[kind]);
        play.rect.set(right - playW, fy, right, fy + fh);
        List<Item> chips = new ArrayList<>();
        if (kind == Picks.MOVIE || kind == Picks.SERIES) {
            Item it = new Item(SKIP, 0, null, "Skip watched", TAB_COLOURS[kind]);   // short: the tab says movies or series
            it.on = Library.skipWatched(ctx);
            chips.add(it);
        }
        if (hasLanguages()) {
            for (int i = 0; i < 2; i++) {
                String name = i == 0 ? MAIN_A : MAIN_B;
                Item it = new Item(SEG, i, null, name, TAB_COLOURS[kind]);
                it.on = ticked.contains(name);
                chips.add(it);
            }
            int n = 1;
            for (String l : shown) if (!l.equals(MAIN_A) && !l.equals(MAIN_B)) {
                Item it = new Item(LANG, n++, null, l, TAB_COLOURS[kind]);
                it.on = ticked.contains(l);
                chips.add(it);
            }
            if (kind == Picks.SONG) {
                String[] f = {ctx.getString(R.string.format_songs), ctx.getString(R.string.format_playlists)};
                for (int i = 0; i < 2; i++) {
                    Item it = new Item(FORMAT, i, null, f[i], TAB_COLOURS[kind]);
                    it.on = (i == 1) == playlists;
                    chips.add(it);
                }
            }
        }
        float room = play.rect.left - 28 * k - x0, cg = 12 * k, total = 0;
        chipText.set(nPill);
        chipText.setTextSize(25 * k);
        for (Item it : chips) total += chipW(it) + cg;
        if (total - cg > room) {   // a long list: smaller words, then only as many as fit
            chipText.setTextSize(22 * k);
            total = 0;
            for (Item it : chips) total += chipW(it) + cg;
        }
        float x = x0;
        for (Item it : chips) {
            float w = chipW(it);
            if (x + w > x0 + room) break;
            it.rect.set(x, cy, x + w, cy + chipH);
            items.add(it);
            x += w + cg;
        }
        items.add(play);
        moreLabelY = -1;
        langCaptionY = -1;
        langBottom = fy;
    }

    private final TextPaint chipText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float tabsRight;

    /** A chip's width: a round tick, the words, room either side. */
    private float chipW(Item it) {
        return chipText.measureText(L10n.t(it.label)) + 92 * k;
    }

    private final android.graphics.Path cardClip = new android.graphics.Path();
    private final java.util.Set<String> artAsked = new java.util.HashSet<>();

    /** One picture per card, found once and kept (CardArt); null while it's on its way, or for My Mix. */
    @androidx.annotation.Nullable
    private Bitmap coverOf(Picks.Entry card, int width) {
        if (card.myMix || kind == Picks.SEARCH) return null;
        String lang = CardArt.LANGUAGE;
        String url = CardArt.cached(ctx, kind, card, lang);
        if (url == null) {
            if (lastListener != null && artAsked.add(card.label)) lastListener.cardArt(kind, card, lang, u -> {
                if (u != null && lastListener != null) lastListener.redraw();
            });
            return null;
        }
        return ImageCache.get(url, () -> {
            if (lastListener != null) lastListener.redraw();
        }, null, width);
    }

    /** The first time a listener is known (the first key), pictures not yet known are fetched. */
    private void startArt(Listener l) {
        if (lastListener != null) return;
        lastListener = l;
    }

    void attach(Listener l) {
        startArt(l);
    }

    /** Shown again after Back from what was played: the same results, on the same card. */
    void reopen() {
        open = true;
        launched = false;
        t = 0;
    }

    /** Back: from the results or Customize to the cards (true); from the cards, false (the pop-up closes). */
    boolean back() {
        if (mode == CARDS || kind == Picks.SEARCH) return false;
        if (mode == TOPICS) {
            mode = CARDS;
            if (tileFocus != null) focus = tileFocus;
            return true;
        }
        if (mode == RESULTS && topicTile != null) {   // picks from a tile that asks: back to its choices
            generation++;
            mode = TOPICS;
            return true;
        }
        if (mode == CUSTOM) {
            if (cRight) {
                cRight = false;
                return true;
            }
            reload();
            mode = CARDS;
            focus = itemOf(CUSTOMIZE);
            return true;
        }
        mode = CARDS;
        generation++;
        if (tileFocus != null) focus = tileFocus;
        return true;
    }

    private Item itemOf(int group) {
        for (Item it : items) if (it.group == group) return it;
        return items.get(0);
    }

    private boolean titlesKind() {
        return kind == Picks.MOVIE || kind == Picks.SERIES;
    }

    /** "🔪 Thriller & Crime  ·  Hindi + English": what the results are for. */
    private String what() {
        if (kind == Picks.SEARCH) return "" + query;
        String s = chosen.emoji + " " + chosen.label;
        return hasLanguages() && chosen.followsLanguage() ? s + "   ·   " + Picks.languageSummary(ticked) : s;
    }

    // ---- keys ------------------------------------------------------------------------------------

    /** D-pad inside the pop-up. True when handled. */
    boolean key(int keyCode) {
        if (mode == RESULTS) return resultKey(keyCode);
        if (!isDpad(keyCode)) return false;
        if (mode == TOPICS) {
            Item best = nearest(tItems, tFocus, keyCode, k);
            if (best != null) tFocus = best;
            return true;
        }
        if (mode == CUSTOM) return customKey(keyCode);
        boolean side = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (focus != null && focus.group == TAB && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus.index == 3) {
            focus = itemOf(CUSTOMIZE);   // the last tab: on to Customize beside it (for the tab that is showing)
            return true;
        }
        if (focus != null && focus.group == TAB && side) {   // along the tabs: the focus moves; OK or Down shows one
            int to = Math.floorMod(focus.index + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), 4);
            for (Item it : items) if (it.group == TAB && it.index == to) focus = it;
            return true;
        }
        if (focus != null && focus.group == TAB && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focus.index != kind && lastListener != null) {
            lastListener.switchKind(focus.index);
            return true;
        }
        if (focus != null && focus.group == TILE && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int to = focus.index + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? COLS : -COLS);
            if (to >= 0 && to < cards.size()) {   // the next row of moods, scrolled into view
                int r = to / COLS;
                if (r < tileTop) tileTop = r;
                if (r >= tileTop + ROWS_SHOWN) tileTop = r - ROWS_SHOWN + 1;
                layout();
                for (Item it : items) if (it.group == TILE && it.index == to) focus = it;
                aheadSoon();
                return true;
            }
        }
        Item best = nearest(items, focus, keyCode, k);
        if (best == null) best = wrap(keyCode);   // past the edge: round to the other side
        if (best != null) focus = best;
        aheadSoon();
        return true;
    }

    /** When the focus rests on a card for a moment, its results are fetched, so OK shows them at once. */
    private void aheadSoon() {
        later.removeCallbacksAndMessages(null);
        if (focus == null || focus.group != TILE || lastListener == null) return;
        final Picks.Entry card = cards.get(focus.index);
        if (card.myMix || card.asks) return;   // a tile that asks first: nothing to fetch until you choose
        later.postDelayed(() -> fetch(card, null), 500);
    }

    private String readyKey(Picks.Entry card) {
        return card.key() + "|" + playlists + "|" + String.join("+", ticked);
    }

    /** Results for a card: kept ones at once (then a next round is fetched ahead), else fetched; done may be null. */
    private void fetch(Picks.Entry card, Consumer<List<Object>> done) {
        String key = readyKey(card);
        List<Object> have = ready.remove(key);
        if (have != null) {
            if (done != null) {
                done.accept(have);
                fetch(card, null);   // the next Shuffle is instant too
            } else {
                ready.put(key, have);
            }
            return;
        }
        List<Consumer<List<Object>>> waiting = coming.get(key);
        if (waiting != null) {
            if (done != null) waiting.add(done);
            return;
        }
        waiting = new ArrayList<>();
        if (done != null) waiting.add(done);
        coming.put(key, waiting);
        Consumer<List<Object>> arrived = got -> {
            List<Consumer<List<Object>>> w = coming.remove(key);
            if (w == null || w.isEmpty()) {
                ready.put(key, got);
                return;
            }
            for (Consumer<List<Object>> c : w) c.accept(got);
            fetch(card, null);
        };
        if (titlesKind()) lastListener.pickTitles(kind, card, ticked, TITLES_PER_ROUND, got -> arrived.accept(new ArrayList<>(got)));
        else lastListener.findMusic(kind, card, ticked, shown, playlists, got -> arrived.accept(new ArrayList<>(got)));
    }

    /** Past an edge: the item furthest the other way that lines up with the focus (its row, or its column). */
    @androidx.annotation.Nullable
    private Item wrap(int keyCode) {
        if (focus == null) return null;
        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        RectF f = focus.rect;
        Item best = null;
        float bestPos = 0;
        for (Item it : items) {
            if (it == focus || it.rect.top < -1000 * k) continue;   // out of view
            RectF r = it.rect;
            boolean lined = horizontal ? r.bottom > f.top && r.top < f.bottom : r.right > f.left && r.left < f.right;
            if (!lined) continue;
            float pos = horizontal ? r.centerX() : r.centerY();
            boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            if (best == null || (forward ? pos < bestPos : pos > bestPos)) {   // right/down wraps to the start, left/up to the end
                best = it;
                bestPos = pos;
            }
        }
        return best;
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** OK. */
    void select(Listener listener) {
        startArt(listener);
        lastListener = listener;
        if (mode == RESULTS) {
            resultSelect(listener);
            return;
        }
        if (mode == TOPICS) {
            topicSelect(listener);
            return;
        }
        if (mode == CUSTOM) {
            customSelect(listener);
            return;
        }
        switch (focus.group) {
            case TAB:
                if (focus.index != kind) listener.switchKind(focus.index);
                break;
            case TILE:
                if (cards.get(focus.index).asks) openTopics(cards.get(focus.index));
                else start(listener, cards.get(focus.index));
                break;
            case SEG:   // English or Hindi on or off; the last language can't go
            case LANG: {
                boolean turnOff = ticked.contains(focus.label);
                if (focus.group == SEG) Picks.setShown(ctx, kind, focus.label, true);
                int grp = focus.group, at = focus.index;
                Picks.setTicked(ctx, kind, focus.label, !turnOff);
                reload();
                for (Item it : items) if (it.group == grp && it.index == at) focus = it;
                break;
            }
            case SKIP:
                Library.setSkipWatched(ctx, !Library.skipWatched(ctx));
                layout();
                focus = itemOf(SKIP);
                break;
            case FORMAT: {
                playlists = focus.index == 1;
                int at = focus.index;
                layout();
                for (Item it : items) if (it.group == FORMAT && it.index == at) focus = it;
                break;
            }
            case PLAY: {   // any card but My Mix
                List<Picks.Entry> pool = new ArrayList<>();
                for (Picks.Entry c : cards) if (!c.myMix) pool.add(c);
                start(listener, pool.get(random.nextInt(pool.size())));
                break;
            }
            default: openCustomize();   // CUSTOMIZE
        }
    }

    /** Screenshots: a mood's picks, as if OK was pressed on card i. */
    void startMood(int i, Listener listener) {
        if (cards == null || cards.isEmpty()) return;
        lastListener = listener;
        start(listener, cards.get(Math.max(0, Math.min(i, cards.size() - 1))));
    }

    private void start(Listener listener, Picks.Entry card) {
        if (card.myMix) {
            open = false;
            launched = true;
            listener.myMix();
            return;
        }
        if (mode != TOPICS) topicTile = null;
        chosen = card;
        if (mode != TOPICS) tileFocus = focus;
        mode = RESULTS;
        mbtn = 0;
        bigFrom = 0;
        load(listener);
    }

    /**
     * The item a direction key should move to. Items in line with the current one (overlapping it across the
     * direction of travel) come first, nearest edge to edge, then the one most nearly in line; only when nothing is in
     * line does the nearest item off to the side get the focus.
     */
    private static Item nearest(List<Item> items, Item from, int keyCode, float k) {
        RectF f = from.rect;
        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        Item best = null;
        float bestScore = Float.MAX_VALUE;
        for (Item it : items) {
            if (it == from) continue;
            RectF r = it.rect;
            float dx = r.centerX() - f.centerX(), dy = r.centerY() - f.centerY(), main, gap, side;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: main = dx; gap = r.left - f.right; side = Math.abs(dy); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: main = -dx; gap = f.left - r.right; side = Math.abs(dy); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: main = dy; gap = r.top - f.bottom; side = Math.abs(dx); break;
                default: main = -dy; gap = f.top - r.bottom; side = Math.abs(dx);
            }
            if (main <= 4 * k || gap < -8 * k) continue;   // must lie beyond this item in that direction
            float overlap = horizontal
                    ? Math.min(r.bottom, f.bottom) - Math.max(r.top, f.top)
                    : Math.min(r.right, f.right) - Math.max(r.left, f.left);
            boolean inLine = overlap > -24 * k;
            float score = inLine ? Math.max(0, gap) + side * 0.25f : 100000 + main + side * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }

    // ---- results ---------------------------------------------------------------------------------

    private int resultCount() {
        return results.size();
    }

    /** The button under the focused card: Trailer for a film or series found by Search; none otherwise. */
    private boolean underCard() {
        return kind == Picks.SEARCH && mcol < results.size() && results.get(mcol) instanceof Tmdb.Title;
    }

    /** A new round for the chosen card (or the search): about twelve songs or videos, or eight films or series. */
    private void load(Listener listener) {
        final int gen = ++generation;
        loading = true;
        results.clear();
        mcol = 0;
        mrow = 0;
        mFrom = mTo = 0;
        Consumer<List<Object>> show = got -> {
            if (gen != generation || mode != RESULTS) return;
            loading = false;
            results.addAll(got);
            if (results.isEmpty()) mrow = 2;
            peekAround(listener);
        };
        if (kind == Picks.SEARCH) listener.search(query, show);
        else fetch(chosen, show);
    }

    /** A playlist's first songs, for the cards in view (each fetched once). */
    private void peekAround(Listener listener) {
        if (!playlists) return;
        for (int i = mcol; i < Math.min(results.size(), mcol + 3); i++) {
            if (!(results.get(i) instanceof Songs.Pick)) continue;
            Songs.Pick p = (Songs.Pick) results.get(i);
            if (p.playlist && peeked.add(p.id)) listener.peek(p, () -> { });
        }
    }

    private int buttonCount() {
        return kind == Picks.SEARCH ? 2 : 3;
    }

    private boolean resultKey(int keyCode) {
        if (big()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (mrow == 0) bigMove(-1);
                else mbtn = Math.max(0, mbtn - 1);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (mrow == 0) bigMove(1);
                else mbtn = Math.min(4, mbtn + 1);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) mrow = 0;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) mrow = 2;
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mrow == 2) mbtn = Math.max(0, mbtn - 1);
                else if (mrow == 0) setCol(mcol - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mrow == 2) mbtn = Math.min(buttonCount() - 1, mbtn + 1);
                else if (mrow == 0) setCol(mcol + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mrow == 2) mrow = underCard() ? 1 : 0;
                else mrow = 0;
                if (resultCount() == 0) mrow = 2;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                mrow = mrow == 0 && underCard() ? 1 : 2;
                return true;
            default:
                return false;
        }
    }

    /**
     * Moves along the row as on Home: the focus moves across the cards in view, and the row slides (briefly) only when
     * the focus would leave it.
     */
    private void setCol(int c) {
        int n = resultCount();
        if (n == 0) return;
        c = Math.max(0, Math.min(n - 1, c));
        long now = SystemClock.uptimeMillis();
        float to = mTo;
        if (c < to) to = c;
        int view = inView();
        if (c > to + view - 1) to = c - view + 1;
        to = Math.max(0, Math.min(to, Math.max(0, n - view)));
        if (to != mTo) {
            mFrom = shift(now);
            mTo = to;
            mStart = now;
        }
        mcol = c;
        if (lastListener != null) peekAround(lastListener);
    }

    private float shift(long now) {
        return mFrom + (mTo - mFrom) * Theme.progress(now, mStart, SLIDE_MS);
    }

    private void play(Listener listener, Object o) {
        open = false;
        launched = true;
        if (o instanceof Tmdb.Title) listener.open((Tmdb.Title) o);
        else listener.playMusic((Songs.Pick) o);
    }

    private void resultSelect(Listener listener) {
        if (big()) {
            bigSelect(listener);
            return;
        }
        int n = resultCount();
        if (mrow == 0 && mcol < n) {
            play(listener, results.get(mcol));
        } else if (mrow == 1 && underCard()) {
            listener.trailer((Tmdb.Title) results.get(mcol));
        } else if (mrow == 2) {
            if (kind == Picks.SEARCH) {
                if (mbtn == 0) {
                    open = false;
                    listener.searchAgain();
                } else {
                    open = false;
                }
            } else if (mbtn == 0 && n > 0) {   // Just play: any one of them
                play(listener, results.get(random.nextInt(n)));
            } else if (mbtn == 1) {
                load(listener);
            } else if (mbtn == 2) {
                back();
            }
        }
    }

    // ---- Customize -------------------------------------------------------------------------------

    private void openCustomize() {
        mode = CUSTOM;
        cSel = 0;
        cRight = false;
        cListScroll = 0;
        buildCustomList();
        buildChips();
    }

    /** The cards that have something to change (hidden ones faded), ➕ New card, then Languages (not for Extras). */
    private void buildCustomList() {
        cList.clear();
        float x = panel.left + PAD * k, y = panel.top + TOP * k;
        for (int i = 0; i < allCards.size(); i++) {
            Picks.Entry c = allCards.get(i);
            if (c.myMix) continue;
            Item it = new Item(C_CARD, i, c.emoji, c.label, c.color);
            it.on = !c.hidden;
            it.rect.set(x, y, x + C_LIST_W * k, y + (C_ROW - 6) * k);
            cList.add(it);
            y += C_ROW * k;
        }
        if (Picks.takesOwnCards(kind)) {
            Item it = new Item(C_CARD, NEW_CARD, "➕", ctx.getString(R.string.new_card), accent);
            it.on = true;
            it.rect.set(x, y, x + C_LIST_W * k, y + (C_ROW - 6) * k);
            cList.add(it);
            y += C_ROW * k;
        }
        if (hasLanguages()) {
            Item it = new Item(C_CARD, LANGUAGES_PAGE, "", ctx.getString(R.string.languages), accent);
            it.on = true;
            it.rect.set(x, y, x + C_LIST_W * k, y + (C_ROW - 6) * k);
            cList.add(it);
        }
        cSel = Math.min(cSel, cList.size() - 1);
    }

    private boolean languagesPage() {
        return cList.get(cSel).index == LANGUAGES_PAGE;
    }

    private boolean newCardPage() {
        return cList.get(cSel).index == NEW_CARD;
    }

    private Picks.Entry customCard() {
        return allCards.get(cList.get(cSel).index);
    }

    /** What the chosen card plays (ticked when on; ✕ on names you added), and Add; or every language. */
    private void buildChips() {
        cChips.clear();
        float left = panel.left + PAD * k + C_LIST_W * k + 48 * k, right = panel.right - PAD * k;
        float x = left, y = panel.top + TOP * k + 70 * k, gap = 12 * k;
        List<Item> list = new ArrayList<>();
        if (newCardPage()) {
            list.add(new Item(C_ADD, 0, "➕", ctx.getString(R.string.new_card_make), accent));
        } else if (languagesPage()) {
            List<String> all = Picks.allLanguages(kind);
            for (int i = 0; i < all.size(); i++) {
                Item it = new Item(C_CHIP, i, null, all.get(i), accent);
                it.on = shown.contains(all.get(i));
                list.add(it);
            }
        } else {
            Picks.Entry c = customCard();
            for (int i = 0; i < c.parts.size(); i++) {
                Picks.Entry p = c.parts.get(i);
                Item it = new Item(C_CHIP, i, null, p.label, c.color);
                it.on = !p.off;
                it.added = p.added;
                list.add(it);
            }
            if (c.adder != null) list.add(new Item(C_ADD, 0, "➕", ctx.getString(R.string.add_more), accent));
            list.add(new Item(C_HIDE, 0, c.hidden ? "" : "", ctx.getString(c.hidden ? R.string.show_card : R.string.hide_card), accent));
            if (c.own) list.add(new Item(C_DELETE, 0, "", ctx.getString(R.string.delete_card), accent));
        }
        for (Item it : list) {
            float w = Math.min(right - left, langText.measureText(L10n.t(it.label)) + (it.emoji != null ? 96 : 84) * k);
            if (x + w > right) {
                x = left;
                y += C_CHIP_H * k + gap;
            }
            it.rect.set(x, y, x + w, y + C_CHIP_H * k);
            x += w + gap;
            cChips.add(it);
        }
        if (cFocus == null || !cChips.contains(cFocus)) cFocus = cChips.isEmpty() ? null : cChips.get(0);
    }

    private boolean customKey(int keyCode) {
        if (!cRight) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: setCustomCard(cSel - 1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: setCustomCard(cSel + 1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!cChips.isEmpty()) {
                        cRight = true;
                        cFocus = cChips.get(0);
                    }
                    return true;
                default: return true;
            }
        }
        Item best = nearest(cChips, cFocus, keyCode, k);
        if (best != null) cFocus = best;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) cRight = false;
        return true;
    }

    private void setCustomCard(int i) {
        setCustomCard(i, false);
    }

    private void setCustomCard(int i, boolean force) {
        i = Math.max(0, Math.min(cList.size() - 1, i));
        if (i == cSel && !force) return;
        cSel = i;
        cFocus = null;
        cScroll = 0;
        buildChips();
    }

    private void customSelect(Listener listener) {
        if (!cRight && newCardPage()) {   // OK on ➕ New card asks for its name straight away
            askNewCard(listener);
            return;
        }
        if (!cRight) {
            if (!cChips.isEmpty()) {
                cRight = true;
                cFocus = cChips.get(0);
            }
            return;
        }
        if (cFocus == null) return;
        int at = cChips.indexOf(cFocus);
        if (newCardPage()) {
            askNewCard(listener);
            return;
        } else if (languagesPage()) {
            Picks.setShown(ctx, kind, cFocus.label, !cFocus.on);
            shown = Picks.shownLanguages(ctx, kind);
        } else {
            Picks.Entry c = customCard();
            if (cFocus.group == C_HIDE) {
                if (!Picks.setHidden(ctx, kind, c, !c.hidden)) {
                    note(ctx.getString(c.hidden ? R.string.cards_full : R.string.last_card));
                    return;
                }
                cardsChanged();
                return;
            }
            if (cFocus.group == C_DELETE) {
                Picks.deleteCard(ctx, kind, c);
                cardsChanged();
                setCustomCard(Math.max(0, cSel - 1), true);
                cRight = false;
                return;
            }
            if (cFocus.group == C_ADD) {
                final int card = cSel;
                listener.askText(ctx.getString(R.string.add_to, c.label), c.addHint, typed -> {
                    if (typed == null || typed.trim().isEmpty() || mode != CUSTOM || cSel != card) return;
                    Picks.addName(ctx, kind, customCard(), typed);
                    cards = Picks.cards(ctx, kind);
                    allCards = Picks.allCards(ctx, kind);
                    cFocus = null;
                    buildChips();
                    cFocus = cChips.get(cChips.size() - 1);   // Add again, right after the new name
                });
                return;
            }
            Picks.Entry p = c.parts.get(cFocus.index);
            if (p.added) Picks.removeName(ctx, kind, c, p.label);   // a name you added goes away
            else Picks.setOff(ctx, kind, c, p, !p.off);
            cards = Picks.cards(ctx, kind);
            allCards = Picks.allCards(ctx, kind);
        }
        cFocus = null;
        buildChips();
        cFocus = cChips.get(Math.max(0, Math.min(at, cChips.size() - 1)));
    }

    /** Asks for a new card's name; the new card is then chosen, ready for more searches. */
    private void askNewCard(Listener listener) {
        listener.askText(ctx.getString(R.string.new_card), ctx.getString(R.string.new_card_hint), typed -> {
            if (typed == null || typed.trim().isEmpty() || mode != CUSTOM) return;
            if (!Picks.addCard(ctx, kind, typed)) {
                note(ctx.getString(R.string.new_card_failed));
                return;
            }
            cardsChanged();
            for (int i = 0; i < cList.size(); i++) if (cList.get(i).label.equalsIgnoreCase(typed.trim().replaceAll("\\s+", " "))) {
                setCustomCard(i, true);
            }
            cRight = true;
            cFocus = cChips.isEmpty() ? null : cChips.get(0);
        });
    }

    /** Cards added, deleted, hidden or shown: the lists again, keeping the chosen row. */
    private void cardsChanged() {
        int group = cFocus != null ? cFocus.group : -1;
        cards = Picks.cards(ctx, kind);
        allCards = Picks.allCards(ctx, kind);
        buildCustomList();
        cFocus = null;
        buildChips();
        for (Item it : cChips) if (it.group == group) cFocus = it;   // stay on the same button
    }

    /** A short message in Customize, in place of the line about the chosen card. */
    private void note(String text) {
        cNote = text;
        cNoteUntil = System.currentTimeMillis() + 4000;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    /** The moods need the height for all twelve; Picks only what its cards need. */
    private void sizePanel(float designH) {
        float h = designH * k, mid = screen.centerY();
        panel.top = mid - h / 2;
        panel.bottom = mid + h / 2;
    }

    void draw(Canvas c) {
        float e = Theme.ease(t);
        if (big()) {
            drawBig(c);
            return;
        }
        sizePanel(mode == RESULTS ? 900 : PANEL_H);
        if (mode == CARDS && kind != Picks.SEARCH) {   // the moods: full screen, like Home
            moodBackdrop(c, e);
            drawCards(c, e);
            return;
        }
        if (mode == TOPICS) {
            moodBackdrop(c, e);
            drawTopics(c, e);
            return;
        }
        if (mode == RESULTS && kind != Picks.SEARCH) {   // the picks: full screen too, tall covers, no stories
            resultsBackdrop(c, e);
            drawResultsFull(c, e);
            return;
        }
        neonPanel(c, e);
        if (mode == RESULTS) drawResults(c, e);
        else if (mode == CUSTOM) drawCustomize(c, e);
        else drawCards(c, e);
    }

    /**
     * Every page is glass only as large as it needs, over Home's picture, blurred.
     */
    private void neonPanel(Canvas c, float e) {
        Frost.scrim(c, screen, e, bmp);
        Frost.panel(c, panel, 40 * k, e, k);
    }

    private void header(Canvas c, float e, String head, String line) {
        int a = Math.round(255 * e);
        String h = head.isEmpty() ? head : head.charAt(0) + head.substring(1).toLowerCase(Locale.ROOT);
        nTitle.setAlpha(a);
        c.drawText(h, panel.left + 56 * k, panel.top + 76 * k, nTitle);
        nEyebrow.setColor(Frost.MUTE);
        nEyebrow.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
        c.drawText(line.replaceFirst("^[^\\p{L}\\p{N}]+", ""), panel.left + 56 * k, panel.top + 120 * k, nEyebrow);
    }

    // ---- one big suggestion: films and series, over the whole screen -------------------------------------

    private ArtStage stage;
    private android.view.View stageHost;
    private String stageArt;
    private Paint bName, bMeta, bBody, bBtn;
    private TextPaint bStory;

    /** Films and series get the whole screen; needs the view to draw on. */
    RandomSheet host(android.view.View v) {
        stageHost = v;
        stage = new ArtStage(v);
        NoirKit n = new NoirKit(ctx, k);
        bName = n.inter(64, 700, 0, Color.WHITE);
        bMeta = n.inter(24, 500, 0, Color.WHITE);
        bBody = n.inter(30, 400, 0, Color.WHITE);
        bBtn = n.inter(24, 500, 0, Color.WHITE);
        bStory = new TextPaint(bBody);
        return this;
    }

    private boolean big() {
        return false;   // every result is the glass pop-up's row, sliding sideways (the full-screen look is kept for later)
    }

    private static final int BIG_SHOWN = 5;
    private int bigFrom;
    private long bigMovedAt;

    private String bigButton(int b, @androidx.annotation.Nullable Tmdb.Title f) {
        switch (b) {
            case 0:
                return f != null && f.service != null ? L10n.t("Play on ") + f.service.name : L10n.t("Where to watch");
            case 1:
                return L10n.t("More info");
            case 2:
                return L10n.t("Trailer");
            case 3:
                return L10n.t("New picks");
            default:
                return L10n.t("Other moods");
        }
    }

    private void bigMove(int dir) {
        int n = results.size();
        if (n == 0) return;
        mcol = Math.max(0, Math.min(n - 1, mcol + dir));
        if (mcol < bigFrom) bigFrom = mcol;
        if (mcol >= bigFrom + BIG_SHOWN) bigFrom = mcol - BIG_SHOWN + 1;
        bigMovedAt = SystemClock.uptimeMillis();
    }

    private void bigOpen(Listener listener, @androidx.annotation.Nullable Tmdb.Title f) {
        if (f == null) return;
        if (f.service != null) play(listener, f);
        else TitleActivity.open(ctx, f);
    }

    private void bigSelect(Listener listener) {
        Tmdb.Title f = mcol < results.size() ? (Tmdb.Title) results.get(mcol) : null;
        if (mrow == 0) {
            bigOpen(listener, f);
            return;
        }
        switch (mbtn) {
            case 0:
                bigOpen(listener, f);
                break;
            case 1:
                if (f != null) TitleActivity.open(ctx, f);
                break;
            case 2:
                if (f != null) listener.trailer(f);
                break;
            case 3:
                if (loading) return;
                bigFrom = 0;
                load(listener);
                mrow = 2;   // stays on the button, to ask again
                break;
            default:
                back();
        }
    }

    private void bigLogo(Tmdb.Title f) {
        if (f.logoUrl != null) f.logoDone = true;
        if (f.logoDone || f.logoAsked) return;
        f.logoAsked = true;
        f.logoAskedAt = SystemClock.uptimeMillis();
        new Thread(() -> {
            f.logoUrl = Catalog.logo(ctx, f);
            f.logoDone = true;
            stageHost.postInvalidate();
        }, "surprise-logo").start();
    }

    private final RectF bigWindow = new RectF();

    /** The chosen film big at the top, with its picture in a window; the others in a row under it; the buttons last. */
    private void drawBig(Canvas c) {
        long now = SystemClock.uptimeMillis();
        Tmdb.Title f = !loading && mcol < results.size() ? (Tmdb.Title) results.get(mcol) : null;
        if (f != null) {
            String art = f.backdropLarge() != null ? f.backdropLarge() : f.poster();
            if (art != null && !art.equals(stageArt)) {
                if (stageArt == null || now - bigMovedAt > 300) {   // not for every film passed on the way
                    stageArt = art;
                    stage.show(art);
                } else stageHost.postDelayed(stageHost::invalidate, 320);
            }
            bigLogo(f);
        }
        bigWindow.set(1000 * k, 50 * k, 1840 * k, 522 * k);
        stage.drawSoft(c, now, bigWindow, 28 * k);
        float x = 80 * k, room = 860 * k;
        String where = what().replaceFirst("^[^\\p{L}\\p{N}]+", "");
        bMeta.setColor(Frost.MUTE);
        c.drawText(ellipsize(where, bMeta, room), x, 78 * k, bMeta);
        bMeta.setColor(Color.WHITE);
        if (f != null) {
            float nameB = 262 * k;
            boolean waiting = !f.logoDone && now - f.logoAskedAt < 2500;
            Bitmap logo = f.logoUrl != null ? ImageCache.get(f.logoUrl, stageHost::postInvalidate, null, 500) : null;
            if (f.logoUrl != null && logo == null && now - f.logoAskedAt < 2500) waiting = true;
            if (logo != null && logo.getWidth() > 0 && !Frost.darkLogo(logo)) {
                float sc = Math.min(620 * k / logo.getWidth(), 150 * k / logo.getHeight());
                tmp2.set(x, nameB - logo.getHeight() * sc, x + logo.getWidth() * sc, nameB);
                c.drawBitmap(logo, null, tmp2, stage.bits());
            } else if (!waiting) {
                float size = 64;
                bName.setTextSize(size * k);
                while (size > 40 && bName.measureText(f.title) > room) bName.setTextSize((size -= 4) * k);
                c.drawText(ellipsize(f.title, bName, room), x, nameB - 8 * k, bName);
            }
            if (waiting) stageHost.postInvalidateOnAnimation();
            String line = (f.isSeries() ? L10n.t("Series") : L10n.t("Film")) + (f.year > 0 ? " · " + f.year : "")
                    + (f.genres != null && !f.genres.isEmpty() ? " · " + f.genres.replace(",", " ·") : "")
                    + (f.runtime > 0 ? " · " + (f.runtime >= 60 ? f.runtime / 60 + " h " + f.runtime % 60 + L10n.t(" min") : f.runtime + L10n.t(" min")) : "")
                    + (f.rating > 0 ? " · ★ " + String.format(Locale.US, "%.1f", f.rating) : "");
            c.drawText(ellipsize(line, bMeta, room), x, 312 * k, bMeta);
            if (f.overview != null && !f.overview.isEmpty()) {
                StaticLayout story = layout("B" + f.key(), f.overview, bStory, room, 4);
                c.save();
                c.translate(x, 334 * k);
                story.draw(c);
                c.restore();
            }
        } else {
            String say = loading ? "Finding something for you" : ctx.getString(R.string.nothing_for_choice);
            c.drawText(say, x, 200 * k, bBody);
        }
        // The row: clean pictures, the chosen one framed.
        float gap = 20 * k, w = (1760 * k - (BIG_SHOWN - 1) * gap) / BIG_SHOWN, h = w * 9 / 16, top = 566 * k;
        for (int v = 0; v < BIG_SHOWN; v++) {
            float cx = x + v * (w + gap);
            tmp.set(cx, top, cx + w, top + h);
            int i = bigFrom + v;
            if (loading || i >= results.size()) {
                if (loading) {
                    fill.setShader(null);
                    fill.setColor(0x1FFFFFFF);
                    c.drawRoundRect(tmp, 20 * k, 20 * k, fill);
                }
                continue;
            }
            Tmdb.Title t = (Tmdb.Title) results.get(i);
            boolean on = i == mcol && mrow == 0;
            if (on) {
                tmp2.set(tmp.left - 8 * k, tmp.top - 8 * k, tmp.right + 8 * k, tmp.bottom + 8 * k);
                Frost.plate(c, tmp2, 26 * k, k);
            }
            String url = t.backdropCard() != null ? t.backdropCard() : t.poster();
            Bitmap pic = ImageCache.get(url, stageHost::postInvalidate, null, 400);
            if (pic != null) drawImage(c, pic, tmp, 20 * k, 255);
            else {
                fill.setShader(null);
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(tmp, 20 * k, 20 * k, fill);
            }
            bMeta.setColor(i == mcol ? Color.WHITE : Frost.MUTE);
            c.drawText(ellipsize(t.title, bMeta, w - 8 * k), cx + 4 * k, top + h + 38 * k, bMeta);
            bMeta.setColor(Frost.MUTE);
            if (t.service != null) c.drawText(ellipsize(t.service.name, bMeta, w - 8 * k), cx + 4 * k, top + h + 72 * k, bMeta);
            bMeta.setColor(Color.WHITE);
        }
        if (!loading && results.size() > BIG_SHOWN) {
            String count = (mcol + 1) + L10n.t(" of ") + results.size();
            bMeta.setColor(Frost.MUTE);
            c.drawText(count, 1840 * k - bMeta.measureText(count), 548 * k, bMeta);
            bMeta.setColor(Color.WHITE);
        }
        float bx = x;
        for (int b = 0; b < 5; b++) {
            String label = bigButton(b, f);
            float bw = bBtn.measureText(label) + 64 * k;
            tmp.set(bx, 932 * k, bx + bw, 1000 * k);
            if (mrow != 0 && b == mbtn) Frost.plate(c, tmp, tmp.height() / 2, k);
            else Frost.ghost(c, tmp, tmp.height() / 2, false, k);
            c.drawText(label, tmp.left + 32 * k, tmp.centerY() + 9 * k, bBtn);
            bx += bw + 12 * k;
        }
        if (stage.moving(now) || loading) stageHost.postInvalidateOnAnimation();
    }

    /** Behind the moods: Home blurred, then the chosen mood's own picture, soft and large, under a deep shade. */
    private void moodBackdrop(Canvas c, float e) {
        Frost.scrim(c, screen, e, bmp);
        Item f = focus != null && focus.group == TILE ? focus : tileFocus;
        Bitmap pic = f != null && f.index < cards.size() ? coverOf(cards.get(f.index), Math.round(f.rect.width())) : null;   // the tile's own copy
        if (pic != null) {   // a small picture stretched over the screen is soft by itself: the blur for free
            float sc = Math.max(screen.width() / pic.getWidth(), screen.height() / pic.getHeight());
            float w = pic.getWidth() * sc, h = pic.getHeight() * sc;
            tmp2.set(screen.centerX() - w / 2, screen.centerY() - h / 2, screen.centerX() + w / 2, screen.centerY() + h / 2);
            bmp.setAlpha(Math.round((mode == TOPICS ? 45 : 110) * e));   // under the chips: just a hint of it
            c.drawBitmap(pic, null, tmp2, bmp);
            bmp.setAlpha(255);
        }
        fill.setShader(new LinearGradient(0, 0, 0, screen.height(), new int[]{0xC7050507, 0x8C050507, 0xE6050507},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        fill.setAlpha(Math.round(255 * e));
        c.drawRect(screen, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawCards(Canvas c, float e) {
        c.drawText(L10n.t("What's the mood?"), 96 * k, 106 * k, nTitle);
        // the tabs' capsule: Home's smoked glass, round all four
        Item first = null;
        for (Item it : items) if (it.group == TAB && first == null) first = it;
        if (first != null) Frost.smoked(c, new RectF(first.rect.left - 6 * k, first.rect.top - 6 * k, tabsRight + 6 * k, first.rect.bottom + 6 * k), 40 * k, k);
        c.save();
        c.clipRect(tilesClip);
        for (Item it : items) if (it.group == TILE && it != focus) drawNoirItem(c, it, false);
        if (focus != null && focus.group == TILE) drawNoirItem(c, focus, true);
        c.restore();
        for (Item it : items) if (it.group != TILE) drawNoirItem(c, it, it == focus);
        if (tileRows > ROWS_SHOWN) {   // more moods above or below: a small marker on the right of the cards
            float h = tilesClip.height() - 28 * k, bh = h * ROWS_SHOWN / tileRows, by = tilesClip.top + 14 * k + (h - bh) * tileTop / (tileRows - ROWS_SHOWN);
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(tilesClip.right - 8 * k, tilesClip.top + 14 * k, tilesClip.right - 4 * k, tilesClip.top + 14 * k + h, 2 * k, 2 * k, fill);
            fill.setColor(0x99FFFFFF);
            c.drawRoundRect(tilesClip.right - 8 * k, by, tilesClip.right - 4 * k, by + bh, 2 * k, 2 * k, fill);
        }
    }

    private void drawNoirItem(Canvas c, Item it, boolean focused) {
        RectF r = it.rect;
        switch (it.group) {
            case TAB: {   // the one shown on a faint plate, the selection on the frosted one; a small neon dot of its colour
                boolean here = it.index == kind;
                float rad = r.height() / 2;
                if (focused) Frost.plate(c, r, rad, k);
                else if (here) {
                    fill.setShader(null);
                    fill.setColor(0x1CFFFFFF);
                    c.drawRoundRect(r, rad, rad, fill);
                }
                fill.setColor(REMOTE[it.index]);
                fill.setShadowLayer(5 * k, 0, 0, REMOTE[it.index]);
                c.drawCircle(r.left + 36 * k, r.centerY(), 7 * k, fill);
                fill.clearShadowLayer();
                nTab.setAlpha(here || focused ? 255 : 215);
                c.drawText(L10n.t(it.label), r.left + 58 * k, r.centerY() + 12 * k, nTab);
                return;
            }
            case TILE: {   // the picture fills the tile, its name inside on a dark fade; chosen: it grows, a white edge hugs it
                float rad = 22 * k;
                boolean inTiles = focus != null && focus.group == TILE;
                RectF pic = focused ? Frost.posterGrown(r, 1.05f) : new RectF(r);
                if (focused) Frost.posterShadow(c, pic, rad, k);
                Picks.Entry card = cards.get(it.index);
                Bitmap cover = coverOf(card, Math.round(r.width()));
                c.save();
                cardClip.reset();
                cardClip.addRoundRect(pic, rad, rad, android.graphics.Path.Direction.CW);
                c.clipPath(cardClip);
                if (cover != null) {
                    float sc = Math.max(pic.width() / cover.getWidth(), pic.height() / cover.getHeight());
                    float cw = cover.getWidth() * sc, ch = cover.getHeight() * sc;
                    tmp2.set(pic.centerX() - cw / 2, pic.centerY() - ch / 2, pic.centerX() + cw / 2, pic.centerY() + ch / 2);
                    c.drawBitmap(cover, null, tmp2, bmp);
                } else {   // until its picture comes: its colour and mark
                    MoodArt.Look look = MoodArt.of(it.label, card.color);
                    fill.setColor(Color.WHITE);
                    fill.setShader(new LinearGradient(pic.left, pic.top, pic.right, pic.bottom, look.top, look.bottom, Shader.TileMode.CLAMP));
                    c.drawRect(pic, fill);
                    fill.setShader(null);
                    Bitmap mark = LineIcon.icon(look.icon, 72 * k);
                    c.drawBitmap(mark, pic.centerX() - mark.getWidth() / 2f, pic.centerY() - mark.getHeight() * 0.7f, bmp);
                }
                c.restore();
                if (focused) Frost.posterRing(c, pic, rad, k);
                nameStrip(c, pic, L10n.t(it.label), focused);
                return;
            }
            case PLAY: {   // a glass bar with a big die; chosen, the die rolls (its face changing as it wobbles)
                float rad = 30 * k;
                c.save();
                if (focused) {
                    c.translate(0, -3 * k);
                    c.scale(1.03f, 1.03f, r.centerX(), r.centerY());
                }
                Frost.heroDeep(c, r, rad, focused, k);
                long now = SystemClock.uptimeMillis();
                boolean rolling = focused && !Lite.on;
                float angle = rolling ? (float) (8 * Math.sin(now / 180.0)) : 0;
                int face = rolling ? (int) ((now / 260) % 6) + 1 : 5;
                String sub = kind == Picks.SONG ? L10n.t("A random song") : kind == Picks.EXTRAS ? L10n.t("Something random") : L10n.t("Picked at random");
                TextPaint sp = new TextPaint(nPill);
                sp.setTextSize(23 * k);
                sp.setColor(0xCCFFFFFF);
                if (r.height() > 220 * k) {   // a tall tile: the die large in the middle, the words under it
                    float d = Math.min(120 * k, r.height() * 0.36f), dx = r.centerX() - d / 2, dy = r.top + (r.height() - d - 96 * k) / 2;
                    drawDie(c, new RectF(dx, dy, dx + d, dy + d), angle, face, TAB_COLOURS[kind]);
                    float ly = dy + d + 52 * k;
                    c.drawText(L10n.t(it.label), r.centerX() - nSurprise.measureText(L10n.t(it.label)) / 2, ly, nSurprise);
                    c.drawText(sub, r.centerX() - sp.measureText(sub) / 2, ly + 34 * k, sp);
                } else {
                    float d = r.height() - 48 * k, dx = r.left + 26 * k, dy = r.centerY() - d / 2;
                    drawDie(c, new RectF(dx, dy, dx + d, dy + d), angle, face, TAB_COLOURS[kind]);
                    float tx = dx + d + 22 * k;
                    c.drawText(L10n.t(it.label), tx, r.centerY() - 2 * k, nSurprise);
                    c.drawText(TextUtils.ellipsize(sub, sp, r.right - tx - 16 * k, TextUtils.TruncateAt.END).toString(), tx, r.centerY() + 30 * k, sp);
                }
                c.restore();
                if (rolling && lastListener != null) lastListener.redraw();   // keeps rolling while chosen
                return;
            }
            default: {   // a chip along the foot (a language, Skip watched, Songs / Playlists), or Customize
                float rad = r.height() / 2;
                Paint p = it.on ? nPillBold : nPill;
                p.setAlpha(255);
                if (it.group == SEG || it.group == LANG || it.group == SKIP || it.group == FORMAT) {
                    if (focused) Frost.plate(c, r, rad, k);
                    else Frost.smoked(c, r, rad, k);
                    float d = 26 * k, bx = r.left + 22 * k, by = r.centerY() - d / 2;
                    if (it.on) {   // on: a green tick, as every tick in TV Town
                        fill.setShader(null);
                        fill.setColor(Frost.GREEN);
                        c.drawCircle(bx + d / 2, by + d / 2, d / 2, fill);
                        Frost.tick(c, bx + d / 2, by + d / 2, 9 * k, k);
                    } else {
                        edge.setStrokeWidth(Math.max(1.5f, 2 * k));
                        edge.setColor(Color.WHITE);
                        edge.setAlpha(140);
                        c.drawCircle(bx + d / 2, by + d / 2, d / 2 - k, edge);
                        edge.setAlpha(255);
                    }
                    TextPaint q = new TextPaint(chipText);
                    q.setColor(Color.WHITE);
                    q.setAlpha(it.on || focused ? 255 : 200);
                    q.setFakeBoldText(it.on);
                    c.drawText(L10n.t(it.label), bx + d + 14 * k, r.centerY() + q.getTextSize() * 0.36f, q);
                    return;
                }
                if (focused) Frost.plate(c, r, rad, k);
                else if (it.group == CUSTOMIZE) Frost.smoked(c, r, rad, k);
                if (false) {
                } else if (it.group == CUSTOMIZE) {
                    kit.drawGlyph(c, kit.glyph(WheelArt.Glyph.PENCIL, 30), r.left + 40 * k, r.centerY(), 255);
                    c.drawText(L10n.t(it.label), r.left + 66 * k, r.centerY() + 10 * k, p);
                } else c.drawText(L10n.t(it.label), r.centerX() - p.measureText(L10n.t(it.label)) / 2, r.centerY() + 10 * k, p);
            }
        }
    }

    private final Map<String, StaticLayout> centredLayouts = new HashMap<>();

    /** A chip: a language, Song / Playlist, Customize, or (in Customize) a part, a language or Add. */
    private void drawChip(Canvas c, Item it, RectF r, boolean focused, boolean selected, float alpha) {
        float rad = r.height() / 2;
        boolean tickable = it.group == LANG || it.group == FORMAT || it.group == C_CHIP;
        if (focused) glow(c, r, rad, Color.WHITE, alpha * 0.8f);
        if (selected && tickable) {
            fill.setColor(Color.WHITE);   // a shader is scaled by the paint's alpha
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, withAlpha(Color.WHITE, Math.round(46 * alpha)),
                    withAlpha(Color.WHITE, Math.round(30 * alpha)), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
        } else {
            fill.setColor(Color.argb(Math.round((focused ? 46 : 22) * alpha), 255, 255, 255));
            c.drawRoundRect(r, rad, rad, fill);
        }
        edge.setStrokeWidth(focused ? (GlassKit.neon(ctx) ? 1.8f * k : Math.max(2f, 3 * k)) : Math.max(1f, 1.5f * k));
        edge.setColor(focused ? (GlassKit.neon(ctx) ? 0x00FFFFFF : Color.WHITE) : Color.WHITE);
        edge.setAlpha(Math.round((focused ? 0 : 36) * alpha));
        c.drawRoundRect(r, rad, rad, edge);
        Paint p = selected ? langBold : langText;
        float a = it.group == C_CHIP && !selected ? 0.55f : 1f;   // parts switched off look faded
        p.setAlpha(Math.round(255 * alpha * a));
        float tx = r.left + 24 * k;
        if (it.emoji != null && !(selected && it.group == FORMAT)) {
            chipEmoji.setAlpha(Math.round(255 * alpha));
            c.drawText(it.emoji, tx, r.centerY() + 10 * k, chipEmoji);
            tx += 44 * k;
        }
        float room = r.right - tx - (it.added || (selected && tickable) ? 52 : 16) * k;   // room for the tick or ✕
        String text = TextUtils.ellipsize(L10n.t(it.label), new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
        c.drawText(text, tx, r.centerY() + 9 * k, p);
        if (it.added) {   // a name you added: OK removes it
            p.setAlpha(Math.round(200 * alpha));
            c.drawText("✕", r.right - 22 * k - p.measureText("✕"), r.centerY() + 9 * k, p);
        } else if (selected && tickable) {
            Frost.tick(c, r.right - 30 * k, r.centerY(), 14 * k, k);
        }
    }

    /** Customize: the cards on the left, what the chosen one plays on the right. */
    private void drawCustomize(Canvas c, float e) {
        String which = ctx.getString(new int[]{R.string.random_movie, R.string.random_series, R.string.random_song, R.string.extras}[kind]);
        header(c, e, ctx.getString(R.string.customize), which + "  ·  " + ctx.getString(R.string.customize_hint));
        float listTop = panel.top + TOP * k, listBottom = panel.bottom - 40 * k;
        // Keep the chosen card in view in the list.
        Item sel = cList.get(cSel);
        if (sel.rect.bottom - cListScroll > listBottom) cListScroll = sel.rect.bottom - listBottom;
        if (sel.rect.top - cListScroll < listTop) cListScroll = sel.rect.top - listTop;
        c.save();
        c.clipRect(panel.left, listTop - 14 * k, panel.left + PAD * k + C_LIST_W * k + 20 * k, listBottom);
        for (int i = 0; i < cList.size(); i++) {
            Item it = cList.get(i);
            tmp.set(it.rect);
            tmp.offset(0, -cListScroll);
            boolean current = i == cSel, focused = current && !cRight;
            float rad = tmp.height() / 2;
            if (current) {
                fill.setColor(withAlpha(it.color, Math.round((focused ? 200 : 120) * e)));
                c.drawRoundRect(tmp, rad, rad, fill);
            }
            if (focused) {
                edge.setStrokeWidth(Math.max(2f, 3 * k));
                edge.setColor(Color.WHITE);
                edge.setAlpha(Math.round(255 * e));
                c.drawRoundRect(tmp, rad, rad, edge);
            }
            chipEmoji.setAlpha(Math.round(255 * e));
            c.drawText(it.emoji, tmp.left + 20 * k, tmp.centerY() + 10 * k, chipEmoji);
            Paint p = current ? langBold : langText;
            p.setAlpha(Math.round(255 * e * (it.on ? 1f : 0.45f)));   // hidden cards look faded
            c.drawText(it.on ? L10n.t(it.label) : L10n.t(it.label) + "  ·  " + ctx.getString(R.string.hidden), tmp.left + 66 * k, tmp.centerY() + 9 * k, p);
        }
        c.restore();
        // Right: what it plays.
        float left = panel.left + PAD * k + C_LIST_W * k + 48 * k, top = panel.top + TOP * k;
        String what;
        if (cNote != null && System.currentTimeMillis() < cNoteUntil) what = cNote;
        else if (newCardPage()) what = ctx.getString(R.string.new_card_about);
        else if (languagesPage()) what = ctx.getString(R.string.customize_languages);
        else {
            Picks.Entry card = customCard();
            what = card.adder != null ? ctx.getString(R.string.customize_plays_add, card.addHint) : ctx.getString(R.string.customize_plays);
        }
        cardMeta.setAlpha(Math.round(220 * e));
        c.drawText(TextUtils.ellipsize(what, new TextPaint(cardMeta), panel.right - PAD * k - left, TextUtils.TruncateAt.END).toString(),
                left, top + 30 * k, cardMeta);
        float chipsTop = top + 60 * k, chipsBottom = panel.bottom - 40 * k;
        if (cRight && cFocus != null) {   // keep the focused chip in view
            if (cFocus.rect.bottom - cScroll > chipsBottom) cScroll = cFocus.rect.bottom - chipsBottom;
            if (cFocus.rect.top - cScroll < chipsTop + 10 * k) cScroll = Math.max(0, cFocus.rect.top - chipsTop - 10 * k);
        }
        c.save();
        c.clipRect(left - 20 * k, chipsTop, panel.right - 20 * k, chipsBottom);
        c.translate(0, -cScroll);
        for (Item it : cChips) {
            boolean f = cRight && it == cFocus;
            c.save();
            tmp.set(it.rect);
            if (f) c.scale(1.04f, 1.04f, tmp.centerX(), tmp.centerY());
            drawChip(c, it, tmp, f, it.on, e);
            c.restore();
        }
        c.restore();
    }

    // ---- results drawing -------------------------------------------------------------------------

    // ---- a tile that asks first (Recipes, Podcasts & Talks, Spirituality, Documentaries) ----------------------

    private static final int T_OPT = 30, T_ADD = 31, T_SHOW = 32;
    private Picks.Entry topicTile;
    private Topics.Asks asks;
    private final List<Item> tItems = new ArrayList<>();
    private Item tFocus;
    private final List<float[]> tHeads = new ArrayList<>();   // each row's name: x, y (with tHeadNames)
    private final List<String> tHeadNames = new ArrayList<>();

    private void openTopics(Picks.Entry tile) {
        topicTile = tile;
        tileFocus = focus;
        asks = Topics.withOwn(ctx, tile.label);
        mode = TOPICS;
        layoutTopics(null);
    }

    /** The rows of chips, wrapping across the screen; then Show me. keep: the chip to stay on. */
    private void layoutTopics(String keep) {
        tItems.clear();
        tHeads.clear();
        tHeadNames.clear();
        java.util.Set<String> on = Topics.ticked(ctx, topicTile.label);
        float x0 = 96 * k, right = screen.width() - 96 * k, y = 230 * k, chipH = 64 * k, g = 14 * k;
        chipText.set(nPill);
        chipText.setTextSize(25 * k);
        asks = Topics.withOwn(ctx, topicTile.label);   // each row with what you added to it
        List<Topics.Group> rows = new ArrayList<>(asks.groups);
        Topics.Group mine = new Topics.Group(Topics.OWN);   // what was added before rows had their own (kept)
        for (String s : Topics.own(ctx, topicTile.label)) mine.add(s, s);
        if (!mine.options.isEmpty()) rows.add(mine);
        int idx = 0;
        for (Topics.Group gr : rows) {
            tHeads.add(new float[]{x0, y});
            tHeadNames.add(L10n.t(gr.name));
            y += 22 * k;
            float x = x0;
            List<Item> row = new ArrayList<>();
            for (String[] o : gr.options) {
                Item it = new Item(T_OPT, idx++, null, gr.name + "|" + o[0], topicTile.color);
                it.on = on.contains(it.label);
                row.add(it);
            }
            if (gr != mine) row.add(new Item(T_ADD, idx++, null, gr.name + "|" + L10n.t("+ Add"), topicTile.color));   // every row takes your own
            for (Item it : row) {
                float w = chipText.measureText(L10n.t(shown(it))) + 92 * k;
                if (x + w > right && x > x0) {
                    x = x0;
                    y += chipH + g;
                }
                it.rect.set(x, y, x + w, y + chipH);
                tItems.add(it);
                x += w + g;
            }
            y += chipH + 46 * k;
        }
        Item show = new Item(T_SHOW, idx, null, "|" + L10n.t("Show me"), topicTile.color);
        show.rect.set(right - 360 * k, 930 * k, right, 1026 * k);
        tItems.add(show);
        tFocus = null;
        for (Item it : tItems) if (keep != null && keep.equals(it.label)) tFocus = it;
        if (tFocus == null) tFocus = show;
    }

    /** What was ticked, in short: "   ·   Veg · Italian" (nothing when nothing is). */
    private String topicSummary() {
        List<String> l = new ArrayList<>();
        for (String on : Topics.ticked(ctx, topicTile.label)) l.add(L10n.t(on.substring(on.indexOf('|') + 1)));
        return l.isEmpty() ? "" : "   ·   " + TextUtils.join(" · ", l);
    }

    private static String shown(Item it) {
        int bar = it.label.indexOf('|');
        return bar >= 0 ? it.label.substring(bar + 1) : it.label;
    }

    private void topicSelect(Listener listener) {
        if (tFocus == null) return;
        if (tFocus.group == T_OPT) {
            java.util.Set<String> on = Topics.ticked(ctx, topicTile.label);
            if (!on.remove(tFocus.label)) on.add(tFocus.label);
            Topics.setTicked(ctx, topicTile.label, on);
            layoutTopics(tFocus.label);
            return;
        }
        if (tFocus.group == T_ADD) {   // into this row (Diet, Cuisine…), kept there and ticked
            final String tile = topicTile.label, group = tFocus.label.substring(0, tFocus.label.indexOf('|'));
            listener.askText(L10n.t("+ Add to ") + L10n.t(group), L10n.t(asks.ownHint), typed -> {
                if (typed == null || typed.trim().isEmpty() || mode != TOPICS || !tile.equals(topicTile.label)) return;
                Topics.addOwnIn(ctx, tile, group, typed.trim());
                java.util.Set<String> on = Topics.ticked(ctx, tile);
                on.add(group + "|" + typed.trim());
                Topics.setTicked(ctx, tile, on);
                layoutTopics(group + "|" + typed.trim());
            });
            return;
        }
        start(listener, Topics.card(ctx, topicTile));   // Show me
    }

    private void drawTopics(Canvas c, float e) {
        c.drawText(L10n.t(topicTile.label), 96 * k, 106 * k, nTitle);
        sub.setAlpha(Math.round(210 * e));
        c.drawText(L10n.t("Tick what you like, then Show me. Nothing ticked: a bit of everything."), 96 * k, 156 * k, sub);
        TextPaint hp = new TextPaint(nPill);
        hp.setTextSize(21 * k);
        hp.setLetterSpacing(0.08f);
        hp.setColor(Frost.MUTE);
        for (int i = 0; i < tHeads.size(); i++) c.drawText(tHeadNames.get(i).toUpperCase(Locale.getDefault()), tHeads.get(i)[0], tHeads.get(i)[1], hp);
        for (Item it : tItems) {
            boolean f = it == tFocus;
            RectF r = it.rect;
            if (it.group == T_SHOW) {
                Frost.heroDeep(c, r, r.height() / 2, f, k);
                kit.drawGlyph(c, kit.glyph(WheelArt.Glyph.PLAY, 30), r.left + 48 * k, r.centerY(), 255);
                c.drawText(shown(it), r.left + 84 * k, r.centerY() + 11 * k, nSurprise);
                continue;
            }
            if (f) Frost.plate(c, r, r.height() / 2, k);
            else Frost.smoked(c, r, r.height() / 2, k);
            float d = 26 * k, bx = r.left + 22 * k, by = r.centerY() - d / 2;
            if (it.group == T_ADD) {
                TextPaint q = new TextPaint(chipText);
                q.setColor(Color.WHITE);
                c.drawText(shown(it), r.left + 30 * k, r.centerY() + q.getTextSize() * 0.36f, q);
                continue;
            }
            if (it.on) {
                fill.setShader(null);
                fill.setColor(Frost.GREEN);
                c.drawCircle(bx + d / 2, by + d / 2, d / 2, fill);
                Frost.tick(c, bx + d / 2, by + d / 2, 9 * k, k);
            } else {
                edge.setStrokeWidth(Math.max(1.5f, 2 * k));
                edge.setColor(Color.WHITE);
                edge.setAlpha(140);
                c.drawCircle(bx + d / 2, by + d / 2, d / 2 - k, edge);
                edge.setAlpha(255);
            }
            TextPaint q = new TextPaint(chipText);
            q.setColor(Color.WHITE);
            q.setAlpha(it.on || f ? 255 : 210);
            q.setFakeBoldText(it.on);
            c.drawText(L10n.t(shown(it)), bx + d + 14 * k, r.centerY() + q.getTextSize() * 0.36f, q);
        }
    }

    /** How many results are in view at once: films and series as tall covers (five), videos as wide pictures (three). */
    private int inView() {
        return kind != Picks.SEARCH && titlesKind() ? 5 : 3;
    }

    /** Behind the picks: Home blurred, the chosen one's picture soft and large, a deep shade. */
    private void resultsBackdrop(Canvas c, float e) {
        Frost.scrim(c, screen, e, bmp);
        Bitmap pic = null;
        if (!loading && mcol < resultCount()) {
            Object o = results.get(mcol);
            pic = o instanceof Tmdb.Title ? ImageCache.get(((Tmdb.Title) o).backdropCard(), null) : ImageCache.get(((Songs.Pick) o).thumb, null);
        }
        if (pic != null) {
            float sc = Math.max(screen.width() / pic.getWidth(), screen.height() / pic.getHeight());
            float w = pic.getWidth() * sc, h = pic.getHeight() * sc;
            tmp2.set(screen.centerX() - w / 2, screen.centerY() - h / 2, screen.centerX() + w / 2, screen.centerY() + h / 2);
            bmp.setAlpha(Math.round(80 * e));   // soft: it sets the mood, it doesn't compete with the picks
            c.drawBitmap(pic, null, tmp2, bmp);
            bmp.setAlpha(255);
        }
        fill.setShader(new LinearGradient(0, 0, 0, screen.height(), new int[]{0xD9050507, 0xA6050507, 0xEB050507},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        fill.setAlpha(Math.round(255 * e));
        c.drawRect(screen, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawResultsFull(Canvas c, float e) {
        int head = titlesKind() ? R.string.picks_for_tonight : kind == Picks.EXTRAS ? R.string.videos_for_you
                : playlists ? R.string.playlists_for_you : R.string.songs_for_you;
        float x0 = 96 * k, right = screen.width() - 96 * k;
        c.drawText(ctx.getString(head), x0, 106 * k, nTitle);
        String w0 = L10n.t(chosen.label) + (topicTile != null ? topicSummary()
                : hasLanguages() && chosen.followsLanguage() ? "   ·   " + Picks.languageSummary(ticked) : "");
        sub.setAlpha(Math.round(210 * e));
        c.drawText(ellipsize(w0, sub, 1100 * k), x0, 152 * k, sub);
        int n = resultCount();
        if (n > 0) {
            String count = (mcol + 1) + " / " + n;
            c.drawText(count, right - sub.measureText(count), 106 * k, sub);
        }
        boolean tall = titlesKind();
        int view = inView();
        float gap = 34 * k, top = 210 * k, w = (right - x0 - (view - 1) * gap) / view, h = tall ? w * 1.5f : w * 9 / 16f;
        long now = SystemClock.uptimeMillis();
        float s = shift(now);
        c.save();
        c.clipRect(0, top - 40 * k, screen.width(), top + h + 150 * k);
        if (loading || n == 0) {
            for (int i = 0; i < view; i++) {
                tmp.set(x0 + i * (w + gap), top, x0 + i * (w + gap) + w, top + h);
                if (loading) placeholder(c, tmp, e, now, i);
            }
            if (!loading) {
                String none = ctx.getString(R.string.nothing_for_choice);
                c.drawText(none, x0, top + h / 2, sub);
            }
        } else {
            boolean inRow = mrow == 0;
            int first = Math.max(0, (int) Math.floor(s) - 1), last = Math.min(n - 1, first + view + 1);
            for (int i = first; i <= last; i++) {
                if (i == mcol && inRow) continue;
                tmp.set(x0 + (i - s) * (w + gap), top, x0 + (i - s) * (w + gap) + w, top + h);
                drawPick(c, i, tmp, false, inRow, e);
            }
            if (inRow) {
                tmp.set(x0 + (mcol - s) * (w + gap), top, x0 + (mcol - s) * (w + gap) + w, top + h);
                drawPick(c, mcol, tmp, true, true, e);
            }
        }
        c.restore();
        // Just play, Shuffle, Categories along the foot
        float bh = 80 * k, y = 930 * k, g = 16 * k;
        String[] labels = {ctx.getString(R.string.just_play), ctx.getString(R.string.shuffle), ctx.getString(R.string.back_to_moods)};
        float x = x0;
        for (int i = 0; i < labels.length; i++) {
            float wd = nPill.measureText(labels[i]) + 96 * k;
            tmp.set(x, y, x + wd, y + bh);
            boolean f = mrow == 2 && mbtn == i;
            if (i == 0) {
                Frost.heroDeep(c, tmp, bh / 2, f, k);
                c.drawText(labels[i], tmp.centerX() - nPillBold.measureText(labels[i]) / 2, tmp.centerY() + 10 * k, nPillBold);
            } else {
                if (f) Frost.plate(c, tmp, bh / 2, k);
                else Frost.smoked(c, tmp, bh / 2, k);
                c.drawText(labels[i], tmp.centerX() - nPill.measureText(labels[i]) / 2, tmp.centerY() + 10 * k, nPill);
            }
            x += wd + g;
        }
    }

    /** One pick: its cover (tall for films and series), the name under it, then where and when. No story. */
    private void drawPick(Canvas c, int i, RectF r, boolean focused, boolean rowOn, float e) {
        float rad = 22 * k;
        RectF pic = focused ? Frost.posterGrown(r, Frost.POSTER_GROW) : new RectF(r);
        if (focused) Frost.posterShadow(c, pic, rad, k);
        Object o = results.get(i);
        String name, line;
        Bitmap img;
        if (o instanceof Tmdb.Title) {
            Tmdb.Title t = (Tmdb.Title) o;
            String url = titlesKind() && t.poster() != null ? t.poster() : t.backdropCard();
            img = url != null ? ImageCache.get(url, () -> { if (lastListener != null) lastListener.redraw(); }, null, Math.round(r.width())) : null;
            name = t.title;
            line = (t.service != null ? t.service.name + " · " : "") + (t.year > 0 ? t.year : "") + (t.rating > 0 ? " · ★ " + String.format(Locale.US, "%.1f", t.rating) : "");
        } else {
            Songs.Pick p = (Songs.Pick) o;
            img = ImageCache.get(p.thumb, null);
            name = p.title;
            line = p.by + "  ·  " + (p.playlist ? ctx.getString(R.string.n_songs, p.videos) : p.seconds == 0 ? ctx.getString(R.string.live) : MyMovies.clock(p.seconds * 1000L));
        }
        if (img != null) drawImage(c, img, pic, rad, Math.round(255 * e));
        else Frost.surface(c, pic, rad, k);
        if (focused) Frost.posterRing(c, pic, rad, k);
        nameStrip(c, pic, name, focused);
        cardMeta.setColor(Frost.MUTE);
        cardMeta.setAlpha(Math.round((focused ? 255 : 200) * e));
        String ln = ellipsize(line, cardMeta, r.width());
        c.drawText(ln, r.centerX() - cardMeta.measureText(ln) / 2, pic.bottom + 36 * k, cardMeta);
    }

    /** A cover's name on the cover itself: centred near its foot on a small frosted strip, bold and clear. */
    private void nameStrip(Canvas c, RectF pic, String name, boolean focused) {
        TextPaint np = new TextPaint(cardTitle);
        np.setTextSize((focused ? 27 : 25) * k);
        np.setFakeBoldText(false);
        np.setTypeface(android.graphics.Typeface.create(cardTitle.getTypeface(), android.graphics.Typeface.BOLD));
        String t = ellipsize(name, np, pic.width() - 44 * k);
        float tw = np.measureText(t), h = 52 * k, w = Math.min(pic.width() - 20 * k, tw + 40 * k);
        RectF strip = new RectF(pic.centerX() - w / 2, pic.bottom - h - 14 * k, pic.centerX() + w / 2, pic.bottom - 14 * k);
        fill.setShader(null);
        fill.setColor(focused ? 0x99101114 : 0x80101114);
        c.drawRoundRect(strip, h / 2, h / 2, fill);
        fill.setColor(0x33FFFFFF);   // light along its top edge: glass
        c.drawRoundRect(strip.left, strip.top, strip.right, strip.top + 1.6f * k, h / 2, h / 2, fill);
        np.setColor(Color.WHITE);
        c.drawText(t, strip.centerX() - tw / 2, strip.centerY() + 9 * k, np);
    }

    private void drawResults(Canvas c, float e) {
        int head = kind == Picks.SEARCH ? R.string.search : titlesKind() ? R.string.picks_for_tonight
                : kind == Picks.EXTRAS ? R.string.videos_for_you : playlists ? R.string.playlists_for_you : R.string.songs_for_you;
        header(c, e, ctx.getString(head), what());
        int n = resultCount();
        if (n > 0) {
            String count = (mcol + 1) + " / " + n;
            sub.setAlpha(Math.round(200 * e));
            c.drawText(count, panel.right - PAD * k - sub.measureText(count), panel.top + 130 * k, sub);
        }
        long now = SystemClock.uptimeMillis();
        float gap = 30 * k, w = (panel.width() - 2 * PAD * k - 2 * gap) / 3, top = panel.top + 156 * k, h = 590 * k;
        float left = panel.left + PAD * k, s = shift(now);
        c.save();
        c.clipRect(panel.left + 8 * k, top - 30 * k, panel.right - 8 * k, top + h + 90 * k);
        if (loading || n == 0) {
            for (int i = 0; i < 3; i++) {
                tmp.set(left + i * (w + gap), top, left + i * (w + gap) + w, top + h);
                if (loading) placeholder(c, tmp, e, now, i);
            }
            if (!loading) {
                sub.setAlpha(Math.round(220 * e));
                String none = ctx.getString(R.string.nothing_for_choice);
                c.drawText(none, panel.centerX() - sub.measureText(none) / 2, top + h / 2, sub);
            }
        } else {
            int first = Math.max(0, (int) Math.floor(s) - 1), last = Math.min(n - 1, first + 5);
            for (int i = first; i <= last; i++) {
                if (i == mcol && mrow == 0) continue;
                float x = left + (i - s) * (w + gap);
                tmp.set(x, top, x + w, top + h);
                drawResult(c, i, tmp, false, e);
            }
            float fx = left + (mcol - s) * (w + gap);
            if (mrow == 0) {
                tmp.set(fx, top, fx + w, top + h);
                drawResult(c, mcol, tmp, true, e);
            }
            if (underCard()) {   // Trailer, under the focused film or series (Search)
                float nw = 230 * k;
                tmp.set(fx + (w - nw) / 2, top + h + 18 * k, fx + (w + nw) / 2, top + h + 72 * k);
                drawPill(c, tmp, "" + ctx.getString(R.string.trailer), mrow == 1, accent, nopeText, e);
            }
        }
        c.restore();
        // Buttons: Just play, Shuffle, Categories (Search: New search, Close).
        float bh = 76 * k, y = panel.bottom - 36 * k - bh, g = 16 * k;
        String[] labels = kind == Picks.SEARCH
                ? new String[]{ctx.getString(R.string.new_search), ctx.getString(R.string.close)}
                : new String[]{ctx.getString(R.string.just_play), ctx.getString(R.string.shuffle), ctx.getString(R.string.back_to_moods)};
        float[] widths = new float[labels.length];
        for (int i = 0; i < labels.length; i++) widths[i] = nPill.measureText(labels[i]) + 88 * k;
        float total = (labels.length - 1) * g;
        for (float wd : widths) total += wd;
        float x = panel.left + PAD * k;
        for (int i = 0; i < labels.length; i++) {
            tmp.set(x, y, x + widths[i], y + bh);
            boolean f = mrow == 2 && mbtn == i;
            c.save();
            if (f) {
                c.translate(0, -3 * k);
                c.scale(1.04f, 1.04f, tmp.centerX(), tmp.centerY());
            }
            if (i == 0) {
                Frost.hero(c, tmp, bh / 2, f, k);   // Just play: the main button's jewel
                nPillBold.setAlpha(Math.round(255 * e));
                c.drawText(labels[i], tmp.centerX() - nPillBold.measureText(labels[i]) / 2, tmp.centerY() + 10 * k, nPillBold);
            } else {
                drawPill(c, tmp, labels[i], f, Color.WHITE, nPill, e);
            }
            c.restore();
            x += widths[i] + g;
        }
    }

    private void drawPill(Canvas c, RectF r, String text, boolean focused, int glowColor, Paint p, float e) {
        Frost.ghost(c, r, r.height() / 2, focused, k);
        p.setAlpha(Math.round((focused ? 255 : 225) * e));
        c.drawText(text, r.centerX() - p.measureText(text) / 2, r.centerY() + 10 * k, p);
    }

    /** Shimmering stand-ins while results load. */
    private void placeholder(Canvas c, RectF r, float e, long now, int i) {
        float rad = 26 * k;
        fill.setColor(Color.argb(Math.round(22 * e), 255, 255, 255));
        c.drawRoundRect(r, rad, rad, fill);
        float iw = r.width() - 24 * k, ih = iw * 9 / 16;
        tmp2.set(r.left + 12 * k, r.top + 12 * k, r.left + 12 * k + iw, r.top + 12 * k + ih);
        float s = 0.5f + 0.5f * (float) Math.sin(now / 300.0 + i);
        fill.setColor(Color.argb(Math.round((30 + 26 * s) * e), 255, 255, 255));
        c.drawRoundRect(tmp2, 18 * k, 18 * k, fill);
        for (int l = 0; l < 3; l++) {
            float y = tmp2.bottom + (34 + l * 44) * k;
            c.drawRoundRect(r.left + 26 * k, y, r.left + 26 * k + (l == 0 ? 0.7f : 0.5f) * iw, y + 24 * k, 12 * k, 12 * k, fill);
        }
    }

    /**
     * A result card: a big picture, then for a film or series its title, type · year · length, rating and genres, the
     * app and a short summary; for a song, playlist or video its name, channel, length (or LIVE, or how many songs)
     * and a playlist's first songs.
     */
    private void drawResult(Canvas c, int i, RectF r, boolean focused, float e) {
        float rad = 20 * k;
        boolean isTitle = results.get(i) instanceof Tmdb.Title;
        if (focused) {   // the frosted plate round the whole card, as on Home
            tmp2.set(r.left - 14 * k, r.top - 14 * k, r.right + 14 * k, r.bottom + 6 * k);
            Frost.plate(c, tmp2, 30 * k, k);
        }
        tmp2.set(r.left, r.top, r.right, r.top + r.width() * 9 / 16);
        if (!focused) {
            fill.setShader(null);
            fill.setColor(Color.argb(Math.round(30 * e), 255, 255, 255));
            c.drawRoundRect(tmp2, rad, rad, fill);
        }
        float x = r.left + 4 * k, w = r.width() - 8 * k, y = tmp2.bottom + 18 * k;
        int textA = Math.round((focused ? 255 : 225) * e);
        if (isTitle) {
            Tmdb.Title t = (Tmdb.Title) results.get(i);
            Bitmap img = ImageCache.get(t.backdrop() != null ? t.backdrop() : t.backdropCard(), null);
            if (img == null) img = ImageCache.get(t.backdropCard(), null);
            if (img != null) drawImage(c, img, tmp2, rad, Math.round((focused ? 255 : 220) * e));
            if (t.service != null) appBadge(c, t.service.pkg, t.service.name, tmp2, e);
            else if (kind == Picks.SEARCH) appBadge(c, "", ctx.getString(R.string.not_streaming_short), tmp2, e);
            StaticLayout tl = layout("t" + t.key(), t.title, cardTitle, w, 2);
            cardTitle.setAlpha(textA);
            c.save();
            c.translate(x, y);
            tl.draw(c);
            c.restore();
            y += tl.getHeight() + 34 * k;
            String line1 = (t.isSeries() ? L10n.t("Series") : L10n.t("Movie")) + (t.year > 0 ? " · " + t.year : "")
                    + (t.runtime > 0 ? " · " + (t.runtime >= 60 ? t.runtime / 60 + " h " + t.runtime % 60 + L10n.t(" min") : t.runtime + L10n.t(" min")) : "")
                    + (t.rating > 0 ? " · ★ " + String.format(Locale.US, "%.1f", t.rating) : "");
            cardMeta.setColor(Frost.MUTE);
            cardMeta.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
            c.drawText(ellipsize(line1, cardMeta, w), x, y, cardMeta);
            y += 16 * k;
            int lines = (int) Math.min(4, (r.bottom - 8 * k - y) / cardBody.getFontSpacing());
            if (!t.overview.isEmpty() && lines >= 1) {
                StaticLayout bl = layout("b" + lines + t.key(), t.overview, cardBody, w, lines);
                cardBody.setAlpha(Math.round(215 * e));
                c.save();
                c.translate(x, y);
                bl.draw(c);
                c.restore();
            }
            return;
        }
        Songs.Pick p = (Songs.Pick) results.get(i);
        Bitmap img = ImageCache.get(p.thumb, null);
        if (img != null) drawImage(c, img, tmp2, rad, Math.round((focused ? 255 : 220) * e));
        if (kind == Picks.SEARCH) appBadge(c, Services.YOUTUBE_PKG, L10n.t("YouTube"), tmp2, e);   // where it plays
        StaticLayout tl = layout("m" + p.id, p.title, cardTitle, w, 2);
        cardTitle.setAlpha(textA);
        c.save();
        c.translate(x, y);
        tl.draw(c);
        c.restore();
        y += tl.getHeight() + 34 * k;
        String info = p.by + " · " + (p.playlist ? ctx.getString(R.string.n_songs, p.videos)
                : p.seconds == 0 ? ctx.getString(R.string.live) : MyMovies.clock(p.seconds * 1000L));
        cardMeta.setColor(Frost.MUTE);
        cardMeta.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
        c.drawText(ellipsize(info, cardMeta, w), x, y, cardMeta);
        y += 16 * k;
        String inc = p.includes;
        if (p.playlist && inc != null && !inc.isEmpty() && y + 50 * k < r.bottom) {
            StaticLayout bl = layout("i" + p.id + inc.length(), ctx.getString(R.string.includes, inc), cardBody, w, 3);
            cardBody.setAlpha(Math.round(215 * e));
            c.save();
            c.translate(x, y);
            bl.draw(c);
            c.restore();
        }
    }

    /** The app a title is on, as a dark pill with its icon and name in the picture's top-left corner. */
    private void appBadge(Canvas c, String pkg, String name, RectF pic, float e) {
        Bitmap icon = appIcon(pkg);
        float h = 44 * k, w = cardMeta.measureText(name) + (icon != null ? 70 : 32) * k;
        RectF b = new RectF(pic.left + 12 * k, pic.top + 12 * k, pic.left + 12 * k + w, pic.top + 12 * k + h);
        fill.setColor(Color.argb(Math.round(215 * e), 14, 14, 16));
        c.drawRoundRect(b, h / 2, h / 2, fill);
        float tx = b.left + 16 * k;
        if (icon != null) {
            bmp.setAlpha(Math.round(255 * e));
            c.drawBitmap(icon, null, new RectF(tx, b.centerY() - 15 * k, tx + 30 * k, b.centerY() + 15 * k), bmp);
            tx += 40 * k;
        }
        cardMeta.setAlpha(Math.round(255 * e));
        c.drawText(name, tx, b.centerY() + 8 * k, cardMeta);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A soft halo around the focused item, in its colour. */
    private void glow(Canvas c, RectF r, float rad, int color, float alpha) {
        if (GlassKit.neon(ctx)) {   // the frosted plate, as on Home
            Frost.plate(c, r, rad, k);
            return;
        }
        for (int i = 3; i >= 1; i--) {
            float g = i * 7 * k;
            tmp2.set(r.left - g, r.top - g, r.right + g, r.bottom + g);
            fill.setColor(withAlpha(color, Math.round(34 * alpha / i)));
            c.drawRoundRect(tmp2, rad + g, rad + g, fill);
        }
    }

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
            l = StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.round(w)).setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(2 * k, 1f).build();
            if (layouts.size() > 60) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    private Bitmap appIcon(String pkg) {
        if (appIcons.containsKey(pkg)) return appIcons.get(pkg);
        Bitmap b = null;
        try {
            android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            int sz = Math.round(34 * k);
            b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sz, sz);
            d.draw(new Canvas(b));
        } catch (Exception ignored) {
            // no icon: the name alone
        }
        appIcons.put(pkg, b);
        return b;
    }

    /** A die: a rounded square in the tab's colour, lit from the top, with white pips for the face (1 to 6). */
    /** The die as one of TV Town's line icons: clear glass with a lilac edge and lilac pips, like the icon on Play and Trailer. */
    private void drawDie(Canvas c, RectF r, float angle, int face, int colour) {
        c.save();
        c.rotate(angle, r.centerX(), r.centerY());
        float rad = r.width() * 0.24f;
        fill.setShader(null);
        fill.clearShadowLayer();
        fill.setColor(0x24FFFFFF);
        c.drawRoundRect(r, rad, rad, fill);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(2f, r.width() * 0.045f));
        edge.setColor(Frost.HERO_ICON);
        float in = edge.getStrokeWidth() / 2;
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, rad - in, rad - in, edge);
        fill.setColor(Frost.HERO_ICON);
        float p = r.width() * 0.075f, a = r.width() * 0.25f, cx = r.centerX(), cy = r.centerY();
        int[][] pips = {{}, {0, 0}, {-1, -1, 1, 1}, {-1, -1, 0, 0, 1, 1}, {-1, -1, 1, -1, -1, 1, 1, 1},
                {-1, -1, 1, -1, 0, 0, -1, 1, 1, 1}, {-1, -1, 1, -1, -1, 0, 1, 0, -1, 1, 1, 1}};
        int[] f = pips[Math.max(1, Math.min(6, face))];
        for (int i = 0; i + 1 < f.length; i += 2) c.drawCircle(cx + f[i] * a, cy + f[i + 1] * a, p, fill);
        c.restore();
    }

    /** A colour a little lighter, for the bright end of a switch. */
    private static int lighten(int color) {
        return Color.rgb(Math.min(255, Color.red(color) + 60), Math.min(255, Color.green(color) + 60), Math.min(255, Color.blue(color) + 60));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

}
