package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * TV Town's own keyboard, drawn in the app so it opens at once: a wide QWERTY panel over the screen, with one list of
 * suggestions beside it, from the films, series, songs, singers and apps on this TV and then from streaming. Space,
 * Delete and Done sit on top; holding OK on any letter gives a space. Up from the top row enters the typed text, where
 * left and right move the cursor, so one letter can be changed like on a phone. Keys never move, and every key can always
 * be pressed: letters that continue no name you own are only dimmed, since a streaming title may still need them.
 */
final class TvKeyboard extends View {

    static final int FILM = 0, SERIES = 1, SONG = 2, SINGER = 3, ALBUM = 4, FOLDER = 5, APP = 6, STREAM = 7, YOUTUBE = 8;

    /** One suggestion. */
    static final class Hit {
        final int kind;
        final String title, label;
        final String plain;
        final String[] words, sounds;
        String key;                  // SONG: the song's key; APP: the package
        Tmdb.Title online;           // STREAM
        String url, picture;         // YOUTUBE; `picture` too for films
        String backdrop;             // Search: the picture behind the page
        MyMusic.Song song;           // SONG
        int score;

        Hit(int kind, String title, String label) {
            this.kind = kind;
            this.title = title;
            this.label = label;
            plain = SearchMatch.plain(title);
            words = SearchMatch.words(plain);
            sounds = SearchMatch.sounds(words);
        }
    }

    interface Listener {
        /** The Search key: `text` as typed, perhaps empty. */
        void onSearch(String text);

        /** A suggestion was chosen; false leaves it to the usual place for its kind. */
        boolean onPick(Hit hit);

        /** The keyboard has gone. */
        void onClosed();
    }

    private static final int COLS = 10, ROWS = 7, MAX_LOCAL = 30, MAX_ONLINE = 6;
    private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl'", "zxcvbnm,."};
    private static final String[] SYMBOL_ROWS = {"!@#$%^&*()", "_=+[]{};:\"", "/?\\|<>~-\u20B9"};
    private static final int K_SPACE = 0x10000, K_DELETE = 0x10001, K_PHONE = 0x10003, K_SEARCH = 0x10004, K_SHIFT = 0x10005, K_SYMBOLS = 0x10006, K_CLEAR = 0x10007;   // past every character's code
    private static final long ONLINE_DELAY_MS = 400;

    private static TvKeyboard current;

    private final Listener listener;
    private final String hint;
    private final boolean withOnline;
    private final float k;
    private final Paint field, keyText, small, rowText, rowLabel, note;
    private final int[][] grid = new int[ROWS][COLS];
    private final boolean textOnly, capWords;   // a plain line of text: no suggestions, more signs, capitals
    private final String title, okLabel;
    private boolean shift, symbols, inBox, okDown, okHeld;
    private int caret;   // where the next letter goes in the text
    @Nullable
    private View before;   // had the focus when this opened
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final StringBuilder text = new StringBuilder();
    private final Set<Character> live = new HashSet<>();
    private final RectF r = new RectF();

    private volatile List<Hit> index;
    private List<Hit> local = new ArrayList<>(), online = new ArrayList<>(), hits = new ArrayList<>();
    private int row, col, list = -1, listFrom, generation;
    private boolean closing;
    private PhoneKeyboard phone;
    private Bitmap qr, mini;   // the big QR code (asked for), and the small one always shown beside the keyboard
    private final Runnable lookOnline = this::searchOnline;

    /** A search of YouTube only, to save something: nothing from this TV in the list. */
    static void youtube(Activity a, @Nullable String initial, Listener listener) {
        TvKeyboard v = new TvKeyboard(a, "YouTube song, video or link", true, false, false, null, null, listener);
        v.youtube = true;
        v.index = new ArrayList<>();
        show(a, v, initial);
    }

    private boolean youtube;

    /** Opens over the whole screen, at once; the names come in behind it. */
    static void open(Activity a, String hint, @Nullable String initial, boolean withOnline, Listener listener) {
        TvKeyboard v = show(a, new TvKeyboard(a, hint, withOnline, false, false, null, null, listener), initial);
        v.work.execute(() -> {
            List<Hit> built = names(a.getApplicationContext());
            v.index = built;
            v.main.post(v::changed);
        });
    }

    // ---- Search: the whole screen ------------------------------------------------------------------------

    private static final float PAGE_T = 190, PAGE_B = 1024;
    private static final int RECENT = 9, MAX_RECENT = 6, IN_APP = 10;   // IN_APP: "Search in Nuvio": the words, in that app
    private boolean page, searching;
    private final ArtStage stage = new ArtStage(this);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), slot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path round = new android.graphics.Path();
    private final RectF empty = new RectF(), to = new RectF();
    private final android.graphics.Rect from = new android.graphics.Rect();
    private final java.util.Map<String, Bitmap> icons = new java.util.HashMap<>(), appPics = new java.util.HashMap<>();
    private List<Hit> videos = new ArrayList<>();
    private int accentBefore;
    @Nullable
    private Bitmap frostBefore;
    private final Runnable artNow = this::showArt;

    /** Search everywhere at once: keyboard on the left, what it finds on the right, and it stays while you open things. */
    static void page(Activity a, @Nullable String initial, Runnable closed) {
        TvKeyboard v = new TvKeyboard(a, "Films, songs, apps, YouTube", true, false, false, null, "Results", new Listener() {
            @Override
            public void onSearch(String text) {
            }

            @Override
            public boolean onPick(Hit hit) {
                return true;
            }

            @Override
            public void onClosed() {
                closed.run();
            }
        });
        v.page = true;
        v.keyW = 96;   // a little wider than the small keyboard
        v.keyH = 96;
        v.rowH = 118;
        v.rowLabel.setTextSize(24 * v.k);
        v.heading.setTextSize(40 * v.k);
        v.accentBefore = Frost.accentNow;
        v.frostBefore = GlassSheet.frost;
        v.place();
        show(a, v, initial);
        v.merge();   // recent searches at once; the names take longer
        v.work.execute(() -> {
            List<Hit> built = names(a.getApplicationContext());
            v.index = built;
            v.main.post(v::changed);
        });
    }

    private android.content.SharedPreferences recents() {
        return getContext().getSharedPreferences("tvtown_search", Context.MODE_PRIVATE);
    }

    private List<Hit> recentHits() {
        List<Hit> out = new ArrayList<>();
        for (String w : recents().getString("recent", "").split("\n")) if (!w.trim().isEmpty()) out.add(new Hit(RECENT, w, "Recent search"));
        return out;
    }

    private void remember(String typed) {
        typed = typed.trim();
        if (typed.isEmpty()) return;
        StringBuilder b = new StringBuilder(typed);
        int n = 1;
        for (String w : recents().getString("recent", "").split("\n")) {
            if (n < MAX_RECENT && !w.trim().isEmpty() && !w.equalsIgnoreCase(typed)) {
                b.append('\n').append(w);
                n++;
            }
        }
        recents().edit().putString("recent", b.toString()).apply();
    }

    /** The background follows the highlighted result, or the best one while typing. */
    private void showArt() {
        if (closing || hits.isEmpty()) return;
        Hit h = hits.get(Math.max(0, Math.min(hits.size() - 1, list)));
        if (h.kind == SONG && h.song != null) {
            Bitmap b = MyMusic.cover(getContext(), h.song, this::invalidate);
            if (b != null) stage.show(h.key, b);
        } else if (h.backdrop != null) stage.show(h.backdrop);
    }

    private void artSoon() {
        main.removeCallbacks(artNow);
        main.postDelayed(artNow, 400);
    }

    private Bitmap icon(String name) {
        Bitmap b = icons.get(name);
        if (b == null) {
            b = LineIcon.icon(name, 44 * k);
            icons.put(name, b);
        }
        return b;
    }

    @Nullable
    private Bitmap appPicture(String pkg) {
        if (appPics.containsKey(pkg)) return appPics.get(pkg);
        Bitmap out = null;
        try {
            PackageManager pm = getContext().getPackageManager();
            android.graphics.drawable.Drawable d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            float sc = Math.min(1f, 200f / w);
            out = Bitmap.createBitmap(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, out.getWidth(), out.getHeight());
            d.draw(new Canvas(out));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            android.util.Log.d("TVTown", "search app picture: " + e.getMessage());
        }
        appPics.put(pkg, out);
        return out;
    }

    @Nullable
    private Bitmap pictureOf(Hit h) {
        switch (h.kind) {
            case SONG:
                return h.song == null ? null : MyMusic.cover(getContext(), h.song, this::invalidate);
            case APP:
            case IN_APP:
                return appPicture(h.key);
            default:
                return h.picture == null ? null : ImageCache.get(h.picture, this::invalidate, null, 200);
        }
    }

    private String iconOf(Hit h) {
        switch (h.kind) {
            case FILM:
            case STREAM:
                return "film";
            case SERIES:
                return "tv";
            case APP:
                return "apps";
            case YOUTUBE:
                return "play";
            case RECENT:
            case IN_APP:
                return "search";
            default:
                return "music";
        }
    }

    private void drawPage(Canvas c) {
        long now = android.os.SystemClock.uptimeMillis();
        Frost.page(c, new RectF(0, 0, getWidth(), getHeight()));   // the shiny black page, as My Movies and Discover
        heading.setColor(Color.WHITE);
        c.drawText("Search", 64 * k, 170 * k, heading);
        r.set(panelL * k, panelT * k, panelR * k, panelB * k);
        Frost.panel(c, r, 44 * k, 1, k);
        r.set((panelR + 24) * k, panelT * k, 1856 * k, panelB * k);
        Frost.panel(c, r, 44 * k, 1, k);
        drawField(c);
        drawKeys(c);
        if (qr != null) {
            drawPhone(c);
            return;
        }
        // (no QR code under the keys: "Type on phone" shows it when wanted)
        if (list >= 0 && hits.size() > rowsShown) {
            note.setColor(Frost.MUTE);
            note.setTextAlign(Paint.Align.RIGHT);
            c.drawText((list + 1) + " of " + hits.size(), 1856 * k, 170 * k, note);
            note.setTextAlign(Paint.Align.LEFT);
        }
        boolean places = whereShown();
        if (places) drawWhere(c);
        float listT = panelT + 18 + (places ? 76 : 0);
        if (hits.isEmpty()) {
            note.setColor(Frost.MUTE);
            String typed = text.toString().trim();
            String say = typed.isEmpty() ? "Films, songs, apps and YouTube appear here as you type"
                    : where != 0 && !allHits.isEmpty() ? "Nothing in " + WHERE[where] + " for \u201C" + typed + "\u201D"
                    : index == null ? "Reading your drive" : searching ? "Searching" : "Nothing found for \u201C" + typed + "\u201D";
            c.drawText(String.valueOf(TextUtils.ellipsize(say, tp(note), (listR - listX - 44) * k, TextUtils.TruncateAt.END)), (listX + 22) * k, (listT + 60) * k, note);
            return;
        }
        float sz = (rowH - 18) * k;
        for (int i = 0; i < rowsShown && listFrom + i < hits.size(); i++) {
            Hit h = hits.get(listFrom + i);
            boolean on = listFrom + i == list;
            float rowT = (listT + i * rowH) * k;
            r.set(listX * k, rowT, listR * k, rowT + (rowH - 6) * k);
            if (on) Frost.plate(c, r, 20 * k, k);
            float sx = r.left + 10 * k, sy = r.centerY() - sz / 2;
            drawSlot(c, h, sx, sy, sz);
            float x = sx + sz + 26 * k, room = r.right - 22 * k - x;
            rowText.setColor(Frost.INK);
            c.drawText(String.valueOf(TextUtils.ellipsize(h.title, tp(rowText), room, TextUtils.TruncateAt.END)), x, r.centerY() - 5 * k, rowText);
            rowLabel.setColor(on ? Frost.INK : Frost.MUTE);
            c.drawText(String.valueOf(TextUtils.ellipsize(h.label, tp(rowLabel), room, TextUtils.TruncateAt.END)), x, r.centerY() + 30 * k, rowLabel);
        }
    }

    /** A result's picture in a square, whole unless it is a video frame; a symbol until there is one. */
    private void drawSlot(Canvas c, Hit h, float x, float y, float sz) {
        Bitmap b = pictureOf(h);
        if (b == null) {
            slot.setColor(0x24FFFFFF);
            to.set(x, y, x + sz, y + sz);
            c.drawRoundRect(to, 14 * k, 14 * k, slot);
            Bitmap g = icon(iconOf(h));
            sprite.setAlpha(220);
            c.drawBitmap(g, to.centerX() - g.getWidth() / 2f, to.centerY() - g.getHeight() / 2f, sprite);
            return;
        }
        int bw = b.getWidth(), bh = b.getHeight();
        if (h.kind == YOUTUBE) {
            int side = Math.min(bw, bh);
            from.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            to.set(x, y, x + sz, y + sz);
        } else {
            from.set(0, 0, bw, bh);
            float sc = Math.min(sz / bw, sz / bh), dw = bw * sc, dh = bh * sc;
            to.set(x + (sz - dw) / 2, y + (sz - dh) / 2, x + (sz + dw) / 2, y + (sz + dh) / 2);
        }
        c.save();
        round.reset();
        round.addRoundRect(to, 14 * k, 14 * k, android.graphics.Path.Direction.CW);
        c.clipPath(round);
        sprite.setAlpha(255);
        c.drawBitmap(b, from, to, sprite);
        c.restore();
    }

    /**
     * A plain line of text, for anything that is not a search: `done` gets it, or null on Back. capWords starts each
     * word with a capital, for names; leave it off for keys and codes, where every letter counts.
     */
    static void ask(Activity a, @Nullable String title, @Nullable String hint, @Nullable String initial, String okLabel, boolean capWords, Consumer<String> done) {
        boolean[] answered = {false};
        show(a, new TvKeyboard(a, hint == null ? "" : hint, false, true, capWords, title, okLabel, new Listener() {
            @Override
            public void onSearch(String text) {
                answered[0] = true;
                done.accept(text);
            }

            @Override
            public boolean onPick(Hit hit) {
                return true;
            }

            @Override
            public void onClosed() {
                if (!answered[0]) done.accept(null);
            }
        }), initial);
    }

    private static TvKeyboard show(Activity a, TvKeyboard v, @Nullable String initial) {
        if (current != null) current.close();
        if (initial != null) v.text.append(initial);
        v.caret = v.text.length();
        v.before = a.getCurrentFocus();
        Frost.capture(a, () -> {   // of the screen as it is, before the keyboard covers it
            v.blurReady = true;
            v.invalidate();
        });
        a.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        current = v;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.requestFocus();
        v.post(() -> {
            if (current == v) v.requestFocus();
        });
        return v;
    }

    static boolean isOpen() {
        return current != null;
    }

    /** Back with the keyboard open (should its focus ever be lost): closes it. */
    static boolean back() {
        if (current == null) return false;
        current.close();
        return true;
    }

    private TvKeyboard(Context ctx, String hint, boolean withOnline, boolean textOnly, boolean capWords, @Nullable String title, @Nullable String okLabel, Listener listener) {
        super(ctx);
        this.textOnly = textOnly;
        this.capWords = capWords;
        this.title = title;
        this.okLabel = "Search";   // the same key everywhere: Search, with the magnifier
        this.hint = hint;
        this.withOnline = withOnline;
        this.listener = listener;
        k = ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
        field = plain(ctx, 36, 500);
        keyText = plain(ctx, 34, 500);
        keyText.setTextAlign(Paint.Align.CENTER);
        small = plain(ctx, 26, 500);
        small.setTextAlign(Paint.Align.CENTER);
        rowText = plain(ctx, 30, 500);
        rowLabel = plain(ctx, 22, 400);
        note = plain(ctx, 26, 400);
        heading = plain(ctx, 34, 700);
        help = plain(ctx, 22, 400);
        for (int c = 0; c < COLS; c++) {
            // Space, Delete and Search on top; the letters; the numbers; then Clear all, Space, Type on phone and Search
            // again at the foot, so the common keys are near wherever you are.
            grid[0][c] = c < 3 ? K_DELETE : c < 7 ? K_SPACE : K_SEARCH;
            grid[4][c] = "1234567890".charAt(c);
            grid[5][c] = c < 2 ? K_CLEAR : c == 2 ? K_SHIFT : c < 7 ? K_SPACE : K_SEARCH;   // Aa (capitals) on every keyboard
            grid[PHONE_ROW][c] = K_PHONE;   // a low, wide button across the foot
        }
        fillLetters();
        row = 1;   // start on the letters
        place();
    }

    /** The three lower rows: letters, or all the symbols. */
    private void fillLetters() {
        String[] from = symbols ? SYMBOL_ROWS : LETTER_ROWS;
        for (int y = 0; y < from.length; y++) for (int c = 0; c < from[y].length(); c++) grid[y + 1][c] = from[y].charAt(c);
        grid[3][COLS - 1] = K_SYMBOLS;   // hardly used: out of the way, bottom corner
    }

    private Paint plain(Context ctx, float size, int weight) {
        Paint p = Theme.font(ctx, size * k, weight);
        p.clearShadowLayer();
        p.setColor(Frost.INK);
        return p;
    }

    // ---- the names -----------------------------------------------------------------------------------

    /** Everything on this TV that can be searched for. */
    private static List<Hit> names(Context ctx) {
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            if (MyMovies.driveMounted(ctx)) {
                for (MyMovies.Video v : MyMovies.scan(ctx)) {
                    MyMovies.Parsed p = MyMovies.parse(v.name, v.folder, "");
                    MyMovies.Match m = MyMovies.saved(ctx, MyMovies.matchKey(v, p));
                    String name = m != null ? m.title : p.title.isEmpty() ? v.name : p.title;
                    int year = m != null && m.year > 0 ? m.year : p.year;
                    if (seen.add("f" + name)) {
                        Hit h = new Hit(p.series ? SERIES : FILM, name,
                                (p.series ? "Series" : "Film") + (year > 0 ? " · " + year : "") + " · on your drive");
                        if (m != null) {
                            h.picture = m.poster;
                            h.backdrop = m.backdrop != null ? m.backdrop : m.poster;
                        }
                        out.add(h);
                    }
                }
                for (MyMusic.Song s : MyMusic.read(ctx)) {
                    if (s.title != null && !s.title.trim().isEmpty()) {
                        Hit h = new Hit(SONG, s.title.trim(), "Song" + (s.artist != null && !s.artist.isEmpty() ? " · " + MyMusic.mainArtist(s.artist) : ""));
                        h.key = s.key();
                        h.song = s;
                        out.add(h);
                    }
                    String singer = MyMusic.mainArtist(s.artist);
                    if (singer != null && singer.trim().length() > 1 && seen.add("s" + singer)) out.add(new Hit(SINGER, singer.trim(), "Singer"));
                    if (s.album != null && s.album.trim().length() > 1 && seen.add("a" + s.album)) out.add(new Hit(ALBUM, s.album.trim(), "Album or film songs"));
                    if (s.folder != null && s.folder.trim().length() > 1 && seen.add("o" + s.folder)) out.add(new Hit(FOLDER, s.folder.trim(), "Music folder"));
                }
            }
            PackageManager pm = ctx.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo i : pm.queryIntentActivities(launcher, 0)) {
                Hit h = new Hit(APP, String.valueOf(i.loadLabel(pm)), "App");
                h.key = i.activityInfo.packageName;
                if (!ctx.getPackageName().equals(h.key)) out.add(h);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("TVTown", "keyboard names", e);
        }
        return out;
    }

    /** What was typed changed, or the names arrived. */
    private void changed() {
        if (closing) return;
        if (textOnly) {
            invalidate();
            return;
        }
        final int gen = ++generation;
        final String typed = text.toString();
        final List<Hit> from = index;
        final boolean atEnd = caret == typed.length();
        main.removeCallbacks(lookOnline);
        online = new ArrayList<>();
        videos = new ArrayList<>();
        searching = page && SearchMatch.plain(typed).length() >= 2;
        if (typed.trim().isEmpty() || from == null) {
            local = new ArrayList<>();
            live.clear();
            merge();
            return;
        }
        merge();
        work.execute(() -> {
            List<Hit> found = match(from, typed);
            Set<Character> next = atEnd ? nextLetters(found, typed) : new HashSet<>();
            main.post(() -> {
                if (gen != generation) return;
                local = found;
                live.clear();
                live.addAll(next);
                merge();
            });
        });
        if (withOnline && SearchMatch.plain(typed).length() >= 2) main.postDelayed(lookOnline, ONLINE_DELAY_MS);
    }

    static List<Hit> match(List<Hit> from, String typed) {
        String q = SearchMatch.plain(typed);
        String[] w = SearchMatch.words(q), s = SearchMatch.sounds(w);
        List<Hit> found = new ArrayList<>();
        if (w.length == 0) return found;   // only symbols so far: nothing to match on
        for (Hit h : from) {
            int sc = SearchMatch.score(q, w, s, h.plain, h.words, h.sounds);
            if (sc == SearchMatch.NONE) continue;
            h.score = sc;
            found.add(h);
        }
        Collections.sort(found, (a, b) -> {
            if (a.score != b.score) return Integer.compare(a.score, b.score);
            if (a.title.length() != b.title.length()) return Integer.compare(a.title.length(), b.title.length());   // the closest to what was typed
            return Integer.compare(a.kind, b.kind);
        });
        return found.size() > MAX_LOCAL ? new ArrayList<>(found.subList(0, MAX_LOCAL)) : found;
    }

    /** The letters that would carry on the last word typed, in the names found. */
    private static Set<Character> nextLetters(List<Hit> found, String typed) {
        Set<Character> next = new HashSet<>();
        if (typed.endsWith(" ")) return next;
        String[] w = SearchMatch.words(SearchMatch.plain(typed));
        if (w.length == 0) return next;
        String last = w[w.length - 1];
        for (Hit h : found) {
            for (String word : h.words) {
                if (word.startsWith(last) && word.length() > last.length()) next.add(word.charAt(last.length()));
            }
        }
        return next;
    }

    private void searchOnline() {
        final int gen = generation;
        final String typed = text.toString().trim();
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            List<Hit> found = new ArrayList<>();
            if (youtube) {
                try {
                    if (!typed.matches("(?i).*(youtube\\.com|youtu\\.be)/.*")) for (YtSaver.Result r : YtSaver.search(typed)) {
                        Hit h = new Hit(YOUTUBE, r.title, r.by + (r.seconds > 0 ? " · " + YtSaver.clock(r.seconds) : ""));
                        h.url = r.url;
                        h.picture = r.picture;
                        found.add(h);
                    }
                } catch (Exception e) {
                    android.util.Log.w("YtSaver", "keyboard", e);
                }
                main.post(() -> {
                    if (gen != generation) return;
                    online = found;
                    merge();
                });
                return;
            }
            try {
                String q = SearchMatch.plain(typed);
                String[] w = SearchMatch.words(q), s = SearchMatch.sounds(w);
                for (Tmdb.Title t : (page ? Catalog.search(ctx, typed, 1) : Tmdb.search(ctx, typed, 1)).titles) {
                    if (found.size() >= (page ? 8 : MAX_ONLINE)) break;
                    String name = SearchMatch.plain(t.title);
                    String[] nw = SearchMatch.words(name);
                    if (SearchMatch.score(q, w, s, name, nw, SearchMatch.sounds(nw)) == SearchMatch.NONE) continue;   // its own idea of a match: not shown
                    Hit h = new Hit(STREAM, t.title, ("tv".equals(t.type) ? "Series" : "Movie") + (t.year > 0 ? " · " + t.year : "") + " · streaming");
                    h.online = t;
                    h.picture = t.poster();
                    h.backdrop = t.backdrop() != null ? t.backdrop() : t.poster();
                    found.add(h);
                }
            } catch (java.io.IOException | RuntimeException e) {
                android.util.Log.d("TVTown", "keyboard online: " + e.getMessage());
            }
            main.post(() -> {
                if (gen != generation) return;
                online = found;
                merge();
            });
            if (!page) return;
            List<Hit> clips = new ArrayList<>();
            try {
                for (Songs.Pick p : Songs.searchVideos(typed, 8)) {
                    if (p.playlist) continue;
                    Hit h = new Hit(YOUTUBE, p.title, "YouTube" + (p.by != null && !p.by.isEmpty() ? " · " + p.by : "") + (p.seconds > 0 ? " · " + YtSaver.clock(p.seconds) : ""));
                    h.key = p.id;
                    h.picture = p.thumb;
                    h.backdrop = p.thumb;
                    clips.add(h);
                }
            } catch (RuntimeException e) {
                android.util.Log.d("TVTown", "search videos: " + e.getMessage());
            }
            main.post(() -> {
                if (gen != generation) return;
                videos = clips;
                searching = false;
                merge();
            });
        }, "keyboard-online").start();
    }

    /** The list: what is on this TV first, then streaming titles not already listed. */
    private void merge() {
        // The best few of this TV's own, then streaming, then the rest: so streaming titles show without scrolling.
        int head = Math.min(3, local.size());
        List<Hit> all = new ArrayList<>(local.subList(0, head));
        Set<String> have = new HashSet<>();
        for (Hit h : local) if (h.kind == FILM || h.kind == SERIES) have.add(h.plain);
        for (Hit h : online) if (!have.contains(h.plain)) all.add(h);
        all.addAll(local.subList(head, local.size()));
        all.addAll(videos);
        String q = text.toString().trim();
        if (page && q.length() >= 2) {   // then every app that takes a search: Netflix, Prime Video, Nuvio, Stremio…
            for (String[] app : PhoneRemote.searchableApps(getContext())) {
                Hit h = new Hit(IN_APP, "Search in " + app[1], "\u201C" + q + "\u201D in the app");
                h.key = app[0];
                all.add(h);
            }
        }
        if (page && q.isEmpty()) all = recentHits();
        allHits = all;
        hits = page && !q.isEmpty() && where != 0 ? only(all, where) : all;
        rowsShown = page ? (q.isEmpty() ? 6 : 5) : rowsShown;   // the places' row takes the room of one result
        if (page) artSoon();
        if (list >= hits.size()) list = hits.isEmpty() ? -1 : hits.size() - 1;
        keepInView();
        invalidate();
    }

    // ---- where to look: All, Your drive, Streaming, Music, Apps (the full search page) ----
    private static final String[] WHERE = {"All", "Your drive", "Streaming", "Music", "Apps"};
    private int where;               // the place chosen
    private boolean inWhere;         // the remote is on the places' row
    private List<Hit> allHits = new ArrayList<>();

    private static int placeOf(Hit h) {
        switch (h.kind) {
            case FILM: case SERIES: case FOLDER: return 1;
            case STREAM: case YOUTUBE: return 2;
            case SONG: case SINGER: case ALBUM: return 3;
            default: return 4;   // apps, and Search in an app
        }
    }

    private static List<Hit> only(List<Hit> all, int place) {
        List<Hit> out = new ArrayList<>();
        for (Hit h : all) if (placeOf(h) == place) out.add(h);
        return out;
    }

    private void chooseWhere(int w) {
        where = w;
        hits = w == 0 ? allHits : only(allHits, w);
        list = -1;
        listFrom = 0;
        if (page) artSoon();
        invalidate();
    }

    private boolean whereShown() {
        return page && qr == null && !text.toString().trim().isEmpty();
    }

    /** The places' row along the top of the results: the chosen one filled light, the remote's one in clear glass. */
    private void drawWhere(Canvas c) {
        float x = listX, y = panelT + 22, h = 56, gap = 10;
        Paint p = rowLabel;
        float was = p.getTextSize();
        int[] n = new int[WHERE.length];
        for (int i = 0; i < WHERE.length; i++) n[i] = i == 0 ? allHits.size() : only(allHits, i).size();
        float size = 24;   // as large as fits across the panel
        for (; size > 16; size -= 1) {
            p.setTextSize(size * k);
            float total = 0;
            for (int i = 0; i < WHERE.length; i++) total += (p.measureText(WHERE[i]) + p.measureText(String.valueOf(n[i]))) / k + size * 2f + gap;
            if (total - gap <= listR - listX) break;
        }
        float pad = size * 0.8f;
        for (int i = 0; i < WHERE.length; i++) {
            String label = WHERE[i], count = String.valueOf(n[i]);
            float w = (p.measureText(label) + p.measureText(count)) / k + size * 2f;
            r.set(x * k, y * k, (x + w) * k, (y + h) * k);
            boolean chosen = i == where, focused = inWhere && chosen;
            if (chosen) {
                tile.setColor(0xE6FFFFFF);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, tile);
                if (focused) {
                    RectF ring = new RectF(r.left - 4 * k, r.top - 4 * k, r.right + 4 * k, r.bottom + 4 * k);
                    Frost.plate(c, ring, ring.height() / 2, k);
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, tile);
                }
            } else {
                tile.setColor(0x14FFFFFF);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, tile);
            }
            int ink = chosen ? 0xFF0B0B0D : n[i] == 0 ? 0x66F4F4F1 : 0xD9F4F4F1;
            float ty = r.centerY() + size * 0.34f * k;
            p.setColor(ink);
            c.drawText(label, r.left + pad * k, ty, p);
            p.setColor(chosen ? 0x990B0B0D : 0x73F4F4F1);
            c.drawText(count, r.left + pad * k + p.measureText(label) + size * 0.4f * k, ty, p);
            x += w + gap;
        }
        p.setTextSize(was);
        p.setColor(Frost.INK);
    }

    private void keepInView() {
        if (list < 0) {
            listFrom = 0;
            return;
        }
        if (list < listFrom) listFrom = list;
        if (list >= listFrom + rowsShown) listFrom = list - rowsShown + 1;
    }

    // ---- the keys ------------------------------------------------------------------------------------

    /** Keeps the keys while open: Home can take the focus back as it starts, or on returning from another screen. */
    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && !closing && current == this) requestFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        boolean first = e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                return true;   // acted on when it is let go, so nothing behind sees it
            case KeyEvent.KEYCODE_DPAD_UP:
                move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {   // letters go in when OK is let go, since holding it means a space
                    okDown = true;
                    okHeld = false;
                    if (list < 0 && !inBox && !inWhere && grid[row][col] == K_DELETE) press(K_DELETE);
                } else if (list < 0 && !inBox && !inWhere) {
                    int key = grid[row][col];
                    if (key == K_DELETE) press(K_DELETE);
                    else if (key < K_SPACE && !okHeld) {
                        okHeld = true;
                        press(K_SPACE);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) press(K_SPACE);
                return true;
            case KeyEvent.KEYCODE_DEL:
                press(K_DELETE);
                return true;
            default:
                int u = e.getUnicodeChar();   // a real keyboard, or the number keys on the remote
                if (u == ' ') {
                    press(K_SPACE);
                    return true;
                }
                if (u > ' ' && u < K_SPACE) {
                    insert((char) u);
                    return true;
                }
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (qr != null) hidePhone();
            else if (page && (list >= 0 || inWhere)) {   // back to the keys first
                list = -1;
                inWhere = false;
                invalidate();
            } else close();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!okDown) return true;   // the OK that opened this keyboard, being let go
            okDown = false;
            if (inWhere) {   // OK on a place: into its results
                if (!hits.isEmpty()) {
                    inWhere = false;
                    list = listFrom = 0;
                    invalidate();
                }
            } else if (list >= 0) {
                if (list < hits.size()) pick(hits.get(list));
            } else if (inBox) leaveBox();
            else if (!okHeld && grid[row][col] != K_DELETE) press(grid[row][col]);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void leaveBox() {
        inBox = false;
        changed();
    }

    private void move(int dx, int dy) {
        if (inBox) {
            if (dy > 0) {
                leaveBox();
                return;
            }
            if (dx != 0) {
                caret = Math.max(0, Math.min(text.length(), caret + dx));
                live.clear();
                invalidate();
            }
            return;
        }
        if (inWhere) {   // along the places: the list follows at once
            if (dx != 0) {
                int w = where + dx;
                if (w < 0) {
                    inWhere = false;   // past the first: back to the keys
                    invalidate();
                } else if (w < WHERE.length) chooseWhere(w);
            } else if (dy > 0 && !hits.isEmpty()) {
                inWhere = false;
                list = listFrom = 0;
                keepInView();
                invalidate();
            }
            return;
        }
        if (list >= 0) {
            if (dx < 0) list = -1;
            else if (dy < 0 && list == 0 && whereShown()) {   // up from the first result: the places
                list = -1;
                inWhere = true;
            } else if (dy != 0) list = Math.max(0, Math.min(hits.size() - 1, list + dy));
            keepInView();
            if (page) artSoon();
            invalidate();
            return;
        }
        if (dx > 0 && lastCol()) {
            if (!hits.isEmpty()) {
                list = listFrom = 0;
                invalidate();
            } else if (whereShown()) {   // no results here: onto the places, to look somewhere else
                inWhere = true;
                invalidate();
            }
            return;
        }
        int key = grid[row][col], c = col, y = row;
        if (dx != 0) {   // over a wide key in one press
            c += dx;
            while (c >= 0 && c < COLS && grid[y][c] == key) c += dx;
            if (c < 0 || c >= COLS) return;
        } else {
            y += dy;
            if (y < 0) {   // up from the top row: into the typed text
                inBox = true;
                live.clear();
                invalidate();
                return;
            }
            if (y >= ROWS) return;
        }
        row = y;
        col = c;
        invalidate();
    }

    /** Nothing further right in this row: the key is the last one. */
    private boolean lastCol() {
        for (int c = col + 1; c < COLS; c++) if (grid[row][c] != grid[row][col]) return false;
        return true;
    }

    private void press(int key) {
        switch (key) {
            case K_SPACE:
                if (caret > 0 && text.charAt(caret - 1) != ' ') {
                    text.insert(caret, ' ');
                    caret++;
                }
                break;
            case K_DELETE:
                if (caret > 0) {
                    text.deleteCharAt(caret - 1);
                    caret--;
                }
                break;
            case K_CLEAR:   // everything typed, gone at once
                text.setLength(0);
                caret = 0;
                break;
            case K_SYMBOLS:
                symbols = !symbols;
                fillLetters();
                invalidate();
                return;
            case K_PHONE:
                if (qr != null) hidePhone();
                else showPhone();
                return;
            case K_SHIFT:
                shift = !shift;
                invalidate();
                return;
            case K_SEARCH:
                if (page) {
                    if (!hits.isEmpty()) {
                        list = listFrom = 0;
                        invalidate();
                    }
                    return;
                }
                listener.onSearch(text.toString().trim());
                close();
                return;
            default:
                insert((char) key);
                return;
        }
        changed();
    }

    private void insert(char ch) {
        if (text.length() >= 100) return;
        text.insert(caret, capitalNext() ? Character.toUpperCase(ch) : ch);
        caret++;
        changed();
    }

    /** The next letter is a capital. */
    private boolean capitalNext() {
        return shift || capWords && (caret == 0 || text.charAt(caret - 1) == ' ');
    }

    private void pick(Hit h) {
        if (page) {   // stays open under what's opened, so Back returns to these results
            Activity a = (Activity) getContext();
            if (h.kind == RECENT) {
                text.setLength(0);
                text.append(h.title);
                caret = text.length();
                list = -1;
                changed();
                return;
            }
            remember(text.toString());
            try {
                if (h.kind == STREAM && h.online != null) TitleActivity.open(a, h.online);
                else if (h.kind == YOUTUBE) a.startActivity(DeepLinks.youtube(a, h.key));
                else if (h.kind == IN_APP) {
                    if (!PhoneRemote.searchApp(a, h.key, text.toString().trim())) route(a, new Hit(APP, h.title, ""), h.key);
                }
                else route(a, h);
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                android.widget.Toast.makeText(a, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        close();
        if (!listener.onPick(h)) route((Activity) getContext(), h);
    }

    /** Takes the chosen thing to its own section. */
    /** An app opened by its package, for a search it couldn't take straight in. */
    private static void route(Activity a, Hit h, String pkg) {
        h.key = pkg;
        route(a, h);
    }

    static void route(Activity a, Hit h) {
        Intent i;
        switch (h.kind) {
            case FILM:
            case SERIES:
                i = new Intent(a, MyMoviesActivity.class).putExtra(MyMoviesActivity.EXTRA_OPEN, h.title);
                break;
            case SONG:
                i = new Intent(a, MyMusicActivity.class).putExtra(MyMusicActivity.EXTRA_SONG, h.key);
                break;
            case SINGER:
            case ALBUM:
            case FOLDER:
                i = new Intent(a, MyMusicActivity.class).putExtra(MyMusicActivity.EXTRA_FIND, h.title);
                break;
            case APP:
                i = a.getPackageManager().getLeanbackLaunchIntentForPackage(h.key);
                break;
            default:
                i = new Intent(a, DiscoverActivity.class).putExtra(DiscoverActivity.EXTRA_QUERY, h.title);
                break;
        }
        if (i == null) return;
        try {
            a.startActivity(i);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            android.widget.Toast.makeText(a, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        if (page) {
            Frost.accentNow = accentBefore;
            GlassSheet.frost = frostBefore;
        }
        if (current == this) current = null;
        stopPhone();
        generation++;
        main.removeCallbacksAndMessages(null);
        work.shutdown();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        listener.onClosed();
        if (before != null && before.isAttachedToWindow()) before.requestFocus();
    }

    // ---- the phone -----------------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Typing on the phone is always on offer: a small QR code beside the keyboard (a plain line shows the big one).
        post(() -> {
            if (closing) return;
            if (textOnly) showPhone();
            else startPhone();
        });
    }

    /** The phone's typing page, started once; its small QR code shows beside the keyboard. */
    private boolean startPhone() {
        if (phone != null) return true;
        phone = new PhoneKeyboard(typed -> {
            text.setLength(0);
            text.append(typed);
            caret = text.length();
            changed();
        }, typed -> {
            text.setLength(0);
            text.append(typed);
            caret = text.length();
            if (page) {
                hidePhone();
                changed();
                return;
            }
            listener.onSearch(text.toString().trim());
            close();
        });
        if (phone.start() == null) {
            phone.stop();
            phone = null;
            return false;
        }
        mini = phone.qr(Math.round((page ? 112 : 150) * k));
        invalidate();
        return true;
    }

    /** The big QR code, when asked for. */
    private void showPhone() {
        if (startPhone()) qr = phone.qr(Math.round(textOnly ? 340 * k : 380 * k));
        if (qr == null) android.widget.Toast.makeText(getContext(), R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
        place();
        invalidate();
    }

    /** Back to the small QR code. */
    private void hidePhone() {
        qr = null;
        place();
        invalidate();
    }

    private void stopPhone() {
        if (phone != null) phone.stop();
        phone = null;
        qr = mini = null;
    }

    /** The small QR code below the keyboard, with a word on what it is for. */
    private void drawMini(Canvas c) {
        if (mini == null || qr != null) return;
        float size = mini.getWidth(), y = (page ? PAGE_B + 12 : panelB + 18) * k;
        float x = page ? 64 * k : panelR * k - size;
        c.drawBitmap(mini, x, y, null);
        note.setColor(Frost.MUTE);
        note.setTextAlign(page ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float tx = page ? x + size + 24 * k : x - 24 * k;
        c.drawText("Scan to type on your phone", tx, y + size / 2 + 10 * k, note);
        note.setTextAlign(Paint.Align.LEFT);
        note.setColor(Color.WHITE);
    }

    // ---- drawing -------------------------------------------------------------------------------------

    // On the 1920 x 1080 design: a wide panel over the screen, the box across its top, the keys on the left and the
    // suggestions beside them at the same height. A plain line has no suggestions, so it is only as wide as the keys.
    private static final float PAD = 32, GAP = 6, BOX_H = 84, TITLE_H = 60, BESIDE = 440, LIST_W = 520, HELP_H = 46;
    private static final int PHONE_ROW = 6;   // Type on your phone: its own low row, where the hints used to be
    private float panelL, panelR, panelT, panelB, boxT, left, top, listX, listR, boxR;
    private float keyW = 96, keyH = 84, rowH = 70;
    private int rowsShown = 6;

    private void place() {
        if (page) {   // the whole screen: keyboard left, results right, the same height, centred
            panelL = 64;
            panelR = 64 + PAD * 2 + COLS * keyW - GAP;
            panelT = PAGE_T;
            panelB = PAGE_B;
            left = panelL + PAD;
            boxT = panelT + PAD;
            boxR = panelR - PAD;
            top = boxT + BOX_H + 20;
            listX = panelR + 24 + 18;
            listR = 1856 - 18;
            return;
        }
        float keys = COLS * keyW - GAP;
        float width = PAD * 2 + keys + (textOnly ? (qr != null ? BESIDE : 0) : 26 + LIST_W);
        float height = PAD * 2 + (title != null ? TITLE_H : 0) + BOX_H + 20 + ROWS * keyH - GAP;
        panelL = (1920 - width) / 2;
        panelR = panelL + width;
        panelT = (1080 - height) / 2;
        panelB = panelT + height;
        left = panelL + PAD;
        boxT = panelT + PAD + (title != null ? TITLE_H : 0);
        top = boxT + BOX_H + 20;
        listX = left + COLS * keyW + 20;
        listR = panelR - PAD;
        boxR = listR;
    }

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        if (page) {
            drawPage(c);
            return;
        }
        if (blurReady) Frost.blurBehind(c, getWidth(), getHeight(), picture);
        else c.drawColor(0x73000000);   // for the moment before the blurred screen is ready
        r.set(panelL * k, panelT * k, panelR * k, panelB * k);
        Frost.panel(c, r, 44 * k, 1, k);
        drawField(c);
        drawKeys(c);
        if (qr != null) drawPhone(c);
        else if (!textOnly) drawList(c);
        // (no QR code under the keys: "Type on phone" shows it when wanted)
    }

    private void drawField(Canvas c) {
        if (title != null) c.drawText(title, left * k, (panelT + PAD + 36) * k, heading);
        r.set(left * k, boxT * k, boxR * k, (boxT + BOX_H) * k);
        if (inBox) Frost.plate(c, r, 42 * k, k);
        else Frost.ghost(c, r, 42 * k, false, k);
        float base = r.centerY() + 13 * k, x = r.left + 38 * k, room = r.width() - 76 * k;
        if (text.length() == 0) {
            field.setColor(Frost.FAINT);
            c.drawText(hint, x, base, field);
            field.setColor(Frost.INK);
        } else {
            String t = text.toString();
            float upTo = field.measureText(t, 0, caret);
            float shift = Math.max(0, upTo - room);   // long text slides so the cursor stays in view
            c.save();
            c.clipRect(x, r.top, x + room + 4 * k, r.bottom);
            c.drawText(t, x - shift, base, field);
            float at = x - shift + upTo + 2 * k;
            c.drawRect(at, r.centerY() - 24 * k, at + 3 * k, r.centerY() + 24 * k, field);
            c.restore();
            return;
        }
        c.drawRect(x, r.centerY() - 24 * k, x + 3 * k, r.centerY() + 24 * k, field);
    }

    private void drawKeys(Canvas c) {
        boolean dimming = !live.isEmpty();
        for (int y = 0; y < ROWS; y++) {
            int x = 0;
            while (x < COLS) {
                int key = grid[y][x], end = x;
                while (end + 1 < COLS && grid[y][end + 1] == key) end++;
                float keyT = top + y * keyH, kh = keyH;
                r.set((left + x * keyW) * k, keyT * k, (left + (end + 1) * keyW - GAP) * k, (keyT + kh - GAP) * k);
                boolean on = list < 0 && !inBox && !inWhere && y == row && x <= col && col <= end;
                if (key == K_SEARCH) Frost.hero(c, r, 16 * k, on, k);   // the main key: the jewel colour
                else if (key == K_SYMBOLS && !on) {   // the symbols key: a soft sky tint, so it is found at once
                    tile.setColor(0x2E7CC8FF);
                    c.drawRoundRect(r, 16 * k, 16 * k, tile);
                } else if (key == K_PHONE) {   // a key like the rest, along the foot, with a faint teal wash so it reads as its own thing
                    if (on) Frost.plate(c, r, 16 * k, k);
                    else {
                        tile.setColor(Color.WHITE);   // a shader takes the paint's alpha
                        tile.setShader(new android.graphics.LinearGradient(r.left, 0, r.right, 0, 0x2E6EE7D2, 0x146EE7D2, android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r, 16 * k, 16 * k, tile);
                        tile.setShader(null);
                    }
                }
                else if (on) Frost.plate(c, r, 16 * k, k);
                else {
                    tile.setColor(key == K_SHIFT && shift ? 0x59FFFFFF : 0x1FFFFFFF);
                    c.drawRoundRect(r, 16 * k, 16 * k, tile);
                }
                float cy = r.centerY();
                if (key < K_SPACE) {
                    boolean bright = on || !dimming || live.contains((char) key);
                    keyText.setColor(bright ? Frost.INK : 0x59F4F4F1);
                    c.drawText(String.valueOf(capitalNext() ? Character.toUpperCase((char) key) : (char) key), r.centerX(), cy + 12 * k, keyText);
                } else {
                    small.setColor(key == K_SEARCH || on ? Frost.INK : key == K_SYMBOLS ? 0xFFA8DCFF : 0xE6F4F4F1);
                    String iconName = keyIcon(key);
                    if (iconName != null) {   // a coloured icon, then the word, centred together
                        String w = word(key);
                        Bitmap g = LineIcon.icon(iconName, 32 * k);
                        float was = small.getTextSize();
                        while (g.getWidth() + 10 * k + small.measureText(w) > r.width() - 20 * k && small.getTextSize() > 16 * k)
                            small.setTextSize(small.getTextSize() - k);
                        float total = g.getWidth() + 10 * k + small.measureText(w), gx = r.centerX() - total / 2;
                        keyIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(keyColour(key), android.graphics.PorterDuff.Mode.SRC_IN));
                        c.drawBitmap(g, gx, cy - g.getHeight() / 2f, keyIcon);
                        Paint.Align al = small.getTextAlign();
                        small.setTextAlign(Paint.Align.LEFT);
                        c.drawText(w, gx + g.getWidth() + 10 * k, cy + 9 * k, small);
                        small.setTextAlign(al);
                        small.setTextSize(was);
                    } else {
                        c.drawText(word(key), r.centerX(), cy + 9 * k, small);
                        if (key == K_SPACE) {   // a space bar's line under the word
                            float half = small.measureText(word(key)) / 2 + 6 * k;
                            c.drawRect(r.centerX() - half, cy + 18 * k, r.centerX() + half, cy + 20.5f * k, small);
                        }
                    }
                }
                x = end + 1;
            }
        }
    }

    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyIcon = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    /** The icon on a key that does something, or null (Aa, #+=). */
    @Nullable
    private static String keyIcon(int key) {
        switch (key) {
            case K_SEARCH: return "search";
            case K_DELETE: return "backspace";
            case K_CLEAR: return "clearall";
            case K_PHONE: return "phone";
            default: return null;   // Space has its line; Type on your phone is words only
        }
    }

    /** Each key's own colour, so it is found at a glance: soft enough to sit on the dark keys. */
    private static int keyColour(int key) {
        switch (key) {
            case K_DELETE: return 0xFFFF8A80;         // coral
            case K_CLEAR: return 0xFFFFC266;          // amber
            case K_PHONE: return 0xFF6EE7D2;          // teal
            default: return Color.WHITE;              // Search: the white magnifier it always had
        }
    }
    private Paint heading, help;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean blurReady;

    private String word(int key) {
        switch (key) {
            case K_SPACE:
                return "Space";
            case K_DELETE:
                return "Delete";
            case K_PHONE:
                return qr != null ? "Hide the phone code" : "Type on your phone";
            case K_SYMBOLS:
                return symbols ? "abc" : "#+=";
            case K_SHIFT:
                return "Aa";
            case K_CLEAR:
                return "Clear all";
            default:
                return okLabel;
        }
    }

    private void drawList(Canvas c) {
        float w = (listR - listX) * k;
        if (hits.isEmpty()) {
            note.setColor(Frost.FAINT);
            String say = youtube ? (text.toString().trim().length() < 2 ? "Results appear as you type" : "Searching YouTube")
                    : text.toString().trim().isEmpty() ? "Suggestions appear as you type"
                    : index == null ? "Reading your drive" : "No matches yet";
            c.drawText(say, (listX + 22) * k, (top + 44) * k, note);
            return;
        }
        for (int i = 0; i < rowsShown && listFrom + i < hits.size(); i++) {
            Hit h = hits.get(listFrom + i);
            boolean on = listFrom + i == list;
            float rowT = (top + i * rowH) * k;
            r.set(listX * k, rowT, listR * k, rowT + (rowH - GAP) * k);
            if (on) Frost.plate(c, r, 16 * k, k);
            float x = r.left + 22 * k, room = w - 44 * k;
            if (h.kind == YOUTUBE && h.picture != null) {
                float ph = (rowH - GAP - 12) * k, pw = ph * 16 / 9;
                Bitmap pic = ImageCache.get(h.picture, this::invalidate, null, Math.round(pw));
                if (pic != null) c.drawBitmap(pic, null, new RectF(r.left + 10 * k, r.top + 6 * k, r.left + 10 * k + pw, r.top + 6 * k + ph), null);
                x = r.left + 10 * k + pw + 14 * k;
                room = r.right - 16 * k - x;
            }
            rowText.setColor(Frost.INK);
            c.drawText(String.valueOf(TextUtils.ellipsize(h.title, tp(rowText), room, TextUtils.TruncateAt.END)), x, rowT + 30 * k, rowText);
            rowLabel.setColor(on ? Frost.MUTE : Frost.FAINT);
            c.drawText(String.valueOf(TextUtils.ellipsize(h.label, tp(rowLabel), room, TextUtils.TruncateAt.END)), x, rowT + 55 * k, rowLabel);
        }
    }

    private final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private TextPaint tp(Paint p) {
        measure.set(p);
        return measure;
    }

    private void drawPhone(Canvas c) {
        float mid = (listX + listR) / 2 * k, y = (top + 40) * k;
        c.drawBitmap(qr, mid - qr.getWidth() / 2f, y, null);
        note.setColor(Frost.MUTE);
        note.setTextAlign(Paint.Align.CENTER);
        c.drawText("Scan to type on your phone", mid, y + qr.getHeight() + 50 * k, note);
        note.setTextAlign(Paint.Align.LEFT);
        note.setColor(Color.WHITE);
    }
}
