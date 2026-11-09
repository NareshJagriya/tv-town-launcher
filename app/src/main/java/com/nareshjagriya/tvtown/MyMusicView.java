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
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * My Music. Browsing: Search and Browse at the top (Browse's menu: Folders, Artists, All songs, Favourites,
 * Downloaded; and Now playing while a song is on), folders and artists as a grid of cards with their covers, songs as a list (OK
 * plays from that song; ❤ and ✏ at the end of each line). Playing: a full-screen player over a moving background
 * (videos from "TV Town Scenes" on the drive, chosen to suit the folder, changed with each song; drawn scenes when
 * there are none), karaoke lyrics that light up as each line is sung, music bars that move with the song (a calm
 * wave, classic bars, a ring round the cover, or off: ▼ changes them), a soft glow on strong beats in the cover's
 * colour, and the controls (▲). Everything but the lyrics and bars fades after ten seconds without a key.
 */
public class MyMusicView extends View implements Choreographer.FrameCallback, MusicPlayer.Watcher {

    interface Listener {
        /** Ask for a line of text (TV keyboard, or typed on a phone); `done` gets it, null when cancelled. */
        void onType(String title, String initial, Consumer<String> done);

        /** The player came up or went away: the background videos run only while it shows. */
        void onPlayerShown(boolean shown);

        /** A new song in this folder: the background moves on to a video that suits it. */
        void onScene(String folder);

        /** The music bars need the microphone permission (Android's rule for reading the sound being played). */
        void onAskBarsPermission();

        /** True when the drive has background videos (else drawn scenes are used). */
        boolean hasVideos();

        /** The search line: `typed` gets every change, `closed` runs when it goes away. */
        void onSearch(String initial, Consumer<String> typed, java.util.function.Function<String, List<String>> suggest, Runnable closed);
    }

    // Tabs, then buttons, in the top bar.
    private static final int T_FOLDERS = 0, T_ARTISTS = 1, T_SONGS = 2, T_FAVS = 3, T_SEARCH = 4, B_DOWNLOADED = 5, B_SHUFFLE = 6, B_SURPRISE = 7, B_NOW = 8;
    private static final String[] TAB_TEXT = {"Folders", "Artists", "All songs", "❤Favourites", "", "⬇  Downloaded", "", ""};

    // Music bar styles.
    static final int VIS_WAVE = 0, VIS_AURORA = 1, VIS_BARS = 2, VIS_HALO = 3, VIS_TRANCE = 4, VIS_OFF = 5;
    private static final String[] VIS_NAMES = {"Glow line", "Glow line", "Glow line", "Glow line", "Glow line", "Effects off"};

    // Layout, in design pixels.
    private static final float PAD = 80, GRID_TOP = 136, COL_GAP = 24, TEXT_H = 0, ROW_GAP = 24, ROW_H = 68;
    /** How many cards to a row: artists are round portraits, more of them. */
    private int cols() {
        return tab == T_ARTISTS ? 6 : 3;
    }

    // The sidebar (the views), as in the reference picture; the content to its right; a song's details on the far right.
    private static final float SIDE_L = 32, SIDE_W = 360, MAIN_L = 80, MAIN_R = 1840, DETAIL_W = 420;
    private static final long IDLE_MS = 10_000;
    private static final ExecutorService net = Work.pool(2);

    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MusicPlayer player;
    private final ArtBackdrop bg;
    private GlassKit kit;
    private float k = 1;
    private boolean running, scheduled, permission = true, scanned, drive = true;
    private int generation;

    private final List<MyMusic.Song> all = new ArrayList<>();
    private List<MyMusic.Group> folders = new ArrayList<>(), artists = new ArrayList<>();

    // Browsing focus.
    private int tab = T_FOLDERS, barSel, gridSel, rowSel, col;
    private boolean inBar = true, barWas;
    private long barAt = -10_000;
    private MyMusic.Group group;          // a folder or artist opened from the grid
    private float gridFrom, gridTo, listFrom, listTo;
    private long gridStart, listStart;

    // Edit a song.
    private MyMusic.Song editing;
    private int editSel;
    private boolean finding, findLoading;
    private final List<SongInfo.Found> found = new ArrayList<>();

    // The player.
    private boolean playerShown, controls, moreOpen, screenOff;
    private int ctrlSel = 1, moreSel, vis;
    private long lastKey = SystemClock.uptimeMillis();
    private String toast;
    private long toastAt;

    // Lyrics.
    private List<Lyrics.Line> lyrics = new ArrayList<>();
    private String lyricsKey;
    private boolean lyricsLoading, lyricsOn, lyricsApprox;
    private String script;

    // Music bars.
    private Visualizer viz;
    private int vizSession;
    private boolean vizBroken;
    private byte[] fft, wave;
    private final float[] bands = new float[48], peaks = new float[48];
    private float energy, energyAvg = 0.1f, beat;
    private long silentSince;

    // Drawn scenes (no videos on the drive).
    private Bitmap scene, oldScene;
    private int sceneIndex = -1;
    private long sceneAt;
    private final float[][] motes = new float[36][4];

    // Drawing.
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF tmp = new RectF();
    private final Path path = new Path();
    private Paint title, sub, chip, chipBold, cardMeta, help, big, rowTitle, rowMeta, icon, songTitle, songArtist, lyricNow,
            lyricNear, lyricFar, clockP;
    private Bitmap disc;
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm", Locale.getDefault());
    private int vsyncs;

    public MyMusicView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        ImageCache.init(context);
        bg = new ArtBackdrop(context);
        player = MusicPlayer.get(context);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        SharedPreferences p = MyMusic.settings(context);
        tab = p.getInt("tab", T_FOLDERS);
        if (tab < 0 || tab > T_FAVS) tab = T_FOLDERS;
        barSel = T_SEARCH;
        vis = Math.max(0, Math.min(VIS_OFF, p.getInt("vis2", VIS_WAVE)));
        lyricsOn = p.getBoolean("lyrics", true);
        script = p.getString("script", Lyrics.SCRIPT_HINDI);
        Random r = new Random(7);
        for (float[] m : motes) {
            m[0] = r.nextFloat();
            m[1] = r.nextFloat();
            m[2] = 0.3f + r.nextFloat();
            m[3] = r.nextFloat() * 6.28f;
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setRunning(boolean run) {
        running = run;
        if (run) {
            player.watch(this);
            if (!scheduled) {
                scheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
            changed();
            if (permission && scanned) rescan();   // songs added or the drive changed while away
        } else {
            Context app = getContext().getApplicationContext();   // song details still waiting: written to the drive in the
            new Thread(() -> SongMatch.flush(app), "song-flush").start();   // background (a slow drive froze Back)
            player.unwatch(this);
            releaseViz();
            bg.release();
        }
    }

    void setPermission(boolean granted) {
        permission = granted;
        if (granted) rescan();
        invalidate();
    }

    /** The microphone permission for the bars was answered. */
    void barsPermission(boolean granted) {
        vizBroken = !granted;
        vizSession = 0;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        scheduled = false;
        if (!running) return;
        if (++vsyncs % 2 == 0) {   // 30 fps
            if (playerShown) readBars();
            else bg.step(SystemClock.uptimeMillis());
            invalidate();
        }
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        live = this;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (live == this) live = null;
        running = false;
        generation++;
        player.unwatch(this);
        releaseViz();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        bg.setSize(w, h);
        kit = new GlassKit(getContext(), bg, k);
        title = kit.font(44, 700);
        title.setLetterSpacing(0.12f);
        sub = kit.font(24, 400);
        chip = kit.font(24, 500);
        chipBold = kit.font(24, 700);
        cardMeta = kit.font(25, 500);
        help = kit.font(24, 500);
        big = kit.font(30, 700);
        rowTitle = kit.font(28, 600);
        rowMeta = kit.font(23, 400);
        icon = kit.font(26, 400);
        icon.clearShadowLayer();
        songTitle = kit.font(52, 700);
        songArtist = kit.font(30, 500);
        lyricNow = kit.font(62, 700);
        lyricNear = kit.font(38, 600);
        lyricFar = kit.font(32, 500);
        clockP = kit.font(34, 600);
        disc = WheelArt.glyphDisc(WheelArt.Glyph.NOTE, Math.round(72 * k), Theme.VIOLET_A, Theme.VIOLET_B);
        NoirKit nk = new NoirKit(getContext(), k);   // the neon glass look's font
        title = nk.inter(46, 800, -0.01f, 0xFFFFFFFF);
        for (Paint p : new Paint[]{sub, chip, help, rowMeta, cardMeta}) p.setTypeface(nk.inter(10, 500, 0, 0).getTypeface());
        for (Paint p : new Paint[]{chipBold, big, rowTitle, clockP}) p.setTypeface(nk.inter(10, 700, 0, 0).getTypeface());
        title.setShadowLayer(8 * k, 0, 1 * k, 0x80000000);
        sub.setColor(0xFFC3CAE6);
        rowMeta.setColor(0xFFD8D8D6);
    }

    // ---- the songs ---------------------------------------------------------------------------------

    /**
     * Shows the songs read last time at once (kept in memory, read in the background when the drive came in), then
     * reads the drive again behind them and updates the lists if anything changed.
     */
    void rescan() {
        final int gen = ++generation;
        List<MyMusic.Song> quick = MyMusic.lastRead();
        if (quick != null && !scanned) show(gen, true, quick, false);
        net.execute(() -> {
            Context ctx = getContext();
            long t0 = SystemClock.uptimeMillis();
            boolean mounted = MyMovies.driveMounted(ctx);
            List<MyMusic.Song> songs = mounted ? MyMusic.read(ctx) : new ArrayList<>();
            android.util.Log.d("TVTownTime", "music scan " + songs.size() + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
            SongMatch.flush(ctx);   // details moved from the TV to the drive while reading are written now
            main.post(() -> show(gen, mounted, songs, true));
        });
    }

    private String pendingKey, pendingFind;

    /** Plays this song, with the rest of its folder after it, once the drive has been read. */
    void playKey(String key) {
        pendingKey = key;
        pendingFind = null;
        if (scanned) fromKeyboard();
    }

    /** Lists the songs found for these words, once the drive has been read. */
    void findNamed(String words) {
        pendingFind = words;
        pendingKey = null;
        if (scanned) fromKeyboard();
    }

    private void fromKeyboard() {
        String key = pendingKey, words = pendingFind;
        pendingKey = pendingFind = null;
        if (words != null) {
            setTab(T_SEARCH);
            query = words;
            rowSel = 0;
            listFrom = listTo = 0;
            inBar = found().isEmpty();
            requestFocus();
            invalidate();
            return;
        }
        if (key == null) return;
        for (MyMusic.Song s : all) {
            if (!key.equals(s.key())) continue;
            List<MyMusic.Song> queue = new ArrayList<>();
            for (MyMusic.Song o : all) if (java.util.Objects.equals(o.folder, s.folder)) queue.add(o);
            play(queue, Math.max(0, queue.indexOf(s)), false, s.folder);
            return;
        }
    }

    private void show(int gen, boolean mounted, List<MyMusic.Song> songs, boolean fresh) {
        if (gen != generation) return;
        if (fresh && scanned && sameSongs(songs)) {   // nothing changed on the drive: keep what is on screen
            drive = mounted;
            tidyNames(gen);
            return;
        }
        List<MyMusic.Group> f = MyMusic.folders(songs), a = MyMusic.artists(songs);
        {
            {
                drive = mounted;
                scanned = true;
                all.clear();
                all.addAll(songs);
                main.post(this::fromKeyboard);   // what the keyboard chose before the songs were read
                folders = sortFolders(f);
                artists = a;
                if (assigned == null || assigned.size() != folders.size()) {
                    assigned = null;
                    photos.clear();
                    photoAsked.clear();
                }
                if (group != null) group = find(tab == T_ARTISTS ? artists : folders, group.name);
                clampFocus();
                invalidate();
                if (fresh) tidyNames(gen);
            }
        }
    }

    /** The same songs (same files) as on screen. */
    private boolean sameSongs(List<MyMusic.Song> songs) {
        if (songs.size() != all.size()) return false;
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (MyMusic.Song s : all) keys.add(s.key());
        for (MyMusic.Song s : songs) if (!keys.contains(s.key())) return false;
        return true;
    }

    private int tidying;   // songs still to look up in the catalogue

    /**
     * Looks up the songs not tidied yet (SongMatch), one every three seconds in the background; the lists update
     * every few songs. Stops when the screen closes or the songs are read again.
     */
    private void tidyNames(int gen) {
        List<MyMusic.Song> todo = new ArrayList<>();
        for (MyMusic.Song s : all) if (!s.matched && !MyMusic.edited(getContext(), s) && SongMatch.pending(getContext(), s)) todo.add(s);
        tidying = todo.size();
        if (todo.isEmpty()) return;
        Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            int done = 0;
            for (MyMusic.Song s : todo) {
                if (gen != generation || !running) break;
                boolean ok = SongMatch.match(ctx, s);
                done++;
                final boolean refresh = ok && done % 5 == 0 || done == todo.size();
                final int left = todo.size() - done;
                main.post(() -> {
                    if (gen != generation) return;
                    tidying = left;
                    if (refresh) regroup();
                    invalidate();
                });
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            SongMatch.flush(ctx);
        }, "tidy").start();
    }

    /** Favourite folders first, then A–Z. */
    private List<MyMusic.Group> sortFolders(List<MyMusic.Group> f) {
        List<MyMusic.Group> out = new ArrayList<>(f);
        java.util.Collections.sort(out, (x, y) -> {
            boolean fx = MyMusic.favouriteFolder(getContext(), x.name), fy = MyMusic.favouriteFolder(getContext(), y.name);
            return fx != fy ? (fx ? -1 : 1) : x.name.compareToIgnoreCase(y.name);
        });
        return out;
    }

    @Nullable
    private static MyMusic.Group find(List<MyMusic.Group> list, String name) {
        for (MyMusic.Group g : list) if (g.name.equals(name)) return g;
        return null;
    }

    private List<MyMusic.Song> favourites() {
        List<MyMusic.Song> out = new ArrayList<>();
        for (MyMusic.Song s : all) if (MyMusic.favourite(getContext(), s)) out.add(s);
        return out;
    }

    /** What the YouTube downloader saved, in its folder. */
    private List<MyMusic.Song> downloaded() {
        java.io.File to = YtSaver.folder(getContext(), false);
        List<MyMusic.Song> out = new ArrayList<>();
        for (MyMusic.Song s : all) if (YtSaver.inside(to, s.path)) out.add(s);
        return out;
    }

    /** The songs listed now: an opened folder or artist, All songs, or Favourites; null on the grid. */
    @Nullable
    private List<MyMusic.Song> list() {
        if (group != null) return group.songs;
        if (tab == T_SONGS) return all;
        if (tab == T_FAVS) return favourites();
        if (tab == B_DOWNLOADED) return downloaded();
        if (tab == T_SEARCH) return found();
        return null;
    }

    private String query = "";

    /**
     * Songs for the search words: every word must appear in the title, film or album, singers or folder; names that
     * start with the words come first, then titles that have them, then the rest.
     */
    private List<MyMusic.Song> found() {
        List<MyMusic.Song> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        String[] words = q.split("\\s+");
        List<int[]> rank = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            MyMusic.Song s = all.get(i);
            String title = s.title.toLowerCase(Locale.ROOT), hay = (title + " " + s.album + " " + s.artist + " " + s.folder + " " + s.genre).toLowerCase(Locale.ROOT);
            boolean every = true;
            for (String w : words) if (!hay.contains(w)) {
                every = false;
                break;
            }
            if (!every) continue;
            int score = title.startsWith(q) ? 0 : title.contains(q) ? 1 : (s.artist.toLowerCase(Locale.ROOT).contains(q) ? 2 : 3);
            rank.add(new int[]{score, i});
        }
        java.util.Collections.sort(rank, (x, y) -> x[0] != y[0] ? x[0] - y[0] : x[1] - y[1]);
        for (int[] r : rank) out.add(all.get(r[1]));
        return out;
    }

    private void openSearch() {
        final int before = tab != T_SEARCH ? tab : MyMusic.settings(getContext()).getInt("tab", tab);
        setTab(T_SEARCH);
        if (listener == null) return;
        listener.onSearch(query, t -> {
            query = t;
            rowSel = 0;
            listFrom = listTo = 0;
            invalidate();
        }, typed -> {   // names to offer: songs, films and albums, singers, folders
            List<String> names = new ArrayList<>();
            for (MyMusic.Song s : all) {
                names.add(s.title);
                names.add(s.album);
                names.add(MyMusic.mainArtist(s.artist));
                names.add(s.folder);
            }
            return SearchBox.suggest(typed, names);
        }, () -> {
            if (query.trim().isEmpty() && tab == T_SEARCH && before != T_SEARCH) {   // nothing searched for: back where it was
                setTab(before);
                inBar = true;
            } else if (!found().isEmpty()) inBar = false;
            requestFocus();
            invalidate();
        });
    }

    private List<MyMusic.Group> groups() {
        return tab == T_ARTISTS ? artists : folders;
    }

    private void clampFocus() {
        List<MyMusic.Song> l = list();
        if (l != null) rowSel = Math.max(group != null ? -1 : 0, Math.min(l.size() - 1, rowSel));
        else gridSel = Math.max(0, Math.min(groups().size() - 1, gridSel));
    }

    // ---- playing ------------------------------------------------------------------------------------

    private void play(List<MyMusic.Song> songs, int from, boolean shuffled, String name) {
        if (songs.isEmpty()) return;
        player.play(new ArrayList<>(songs), from, shuffled, name);
        showPlayer(true);
    }

    /** Surprise me: a random folder (favourite folders twice as likely), shuffled. */
    private void surprise() {
        if (folders.isEmpty()) return;
        List<MyMusic.Group> pool = new ArrayList<>();
        for (MyMusic.Group g : folders) {
            pool.add(g);
            if (MyMusic.favouriteFolder(getContext(), g.name)) pool.add(g);
        }
        MyMusic.Group g = pool.get(new Random().nextInt(pool.size()));
        play(g.songs, -1, true, g.name);
        say("" + g.name);
    }

    void showPlayer(boolean show) {
        if (show && !player.active()) return;
        if (show == playerShown) return;
        playerShown = show;
        controls = false;
        moreOpen = false;
        screenOff = false;
        lastKey = SystemClock.uptimeMillis();
        if (listener != null) listener.onPlayerShown(show);
        if (show) {
            MyMusic.Song s = player.current();
            if (s != null && listener != null) listener.onScene(sceneWords(s.folder));
            nextScene();
        } else releaseViz();
        invalidate();
    }

    private String lastSongKey;

    /** From MusicPlayer: a new song, play or pause. */
    @Override
    public void changed() {
        MyMusic.Song s = player.current();
        String key = s != null ? s.key() : null;
        if (key != null && !key.equals(lastSongKey)) {
            lastSongKey = key;
            loadLyrics(s);
            if (playerShown) {
                if (listener != null) listener.onScene(sceneWords(s.folder));
                nextScene();
            }
        }
        if (s == null && playerShown) showPlayer(false);
        if (player.sessionId() != vizSession) releaseViz();
        invalidate();
    }

    private void loadLyrics(MyMusic.Song s) {
        lyrics = new ArrayList<>();
        lyricsKey = s.key();
        lyricsLoading = true;
        lyricsApprox = false;
        findLyrics(s, 0);
    }

    private List<Lyrics.Line> lyricsSource = new ArrayList<>();   // as found or picked, before any conversion

    /** Shows these lyrics in the chosen script: English letters from Hindi here on the TV, Hindi from English letters online. */
    private void showLyrics(List<Lyrics.Line> source, MyMusic.Song s) {
        lyricsSource = source;
        lyrics = Lyrics.inScript(source, script);
        if (!Lyrics.SCRIPT_HINDI.equals(script) || !Lyrics.convertible(source)) return;
        final String key = s.key();
        Context ctx = getContext().getApplicationContext();
        net.execute(() -> {
            List<Lyrics.Line> hindi = Lyrics.toHindi(ctx, source);
            main.post(() -> {
                if (lyricsSource != source || !key.equals(lyricsKey) || !Lyrics.SCRIPT_HINDI.equals(script)) return;
                if (hindi != null) lyrics = hindi;
                else say("Can't reach the converter: showing English letters");
                invalidate();
            });
        });
    }

    /** Looks the lyrics up; when LRCLIB cannot be reached, tries again 20 and 60 seconds later while the song plays. */
    private void findLyrics(MyMusic.Song s, int round) {
        String want = script;
        net.execute(() -> {
            List<Lyrics.Line> got = Lyrics.find(getContext(), s, want);
            main.post(() -> {
                if (!s.key().equals(lyricsKey)) return;
                if (got == null && round < 2) {
                    main.postDelayed(() -> {
                        if (s.key().equals(lyricsKey)) findLyrics(s, round + 1);
                    }, round == 0 ? 20_000 : 60_000);
                    return;
                }
                showLyrics(got != null ? got : new ArrayList<>(), s);
                lyricsApprox = got instanceof Lyrics.Rough;
                lyricsLoading = false;
                invalidate();
            });
        });
    }

    // ---- music bars ---------------------------------------------------------------------------------

    /** Reads the sound being played (the Visualizer); a gentle made-up motion when that is not allowed. */
    private void readBars() {
        int session = player.sessionId();
        if (!vizBroken && !Lite.on && (vis != VIS_OFF || effectNow() >= 0) && session != 0 && (viz == null || session != vizSession)) {
            releaseViz();
            try {
                viz = new Visualizer(session);
                int[] range = Visualizer.getCaptureSizeRange();
                viz.setCaptureSize(Math.max(range[0], Math.min(range[1], 512)));
                viz.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                viz.setEnabled(true);
                vizSession = session;
                fft = new byte[viz.getCaptureSize()];
                wave = new byte[viz.getCaptureSize()];
                silentSince = 0;
            } catch (SecurityException e) {
                vizBroken = true;
                if (listener != null) listener.onAskBarsPermission();
            } catch (Throwable t) {
                vizBroken = true;   // this TV does not allow it: made-up motion
            }
        }
        long now = SystemClock.uptimeMillis();
        boolean real = false;
        if (viz != null && player.playing()) {
            try {
                if (viz.getFft(fft) == Visualizer.SUCCESS) {
                    viz.getWaveForm(wave);
                    real = true;
                    float sum = 0;
                    int n = fft.length / 2;
                    for (int b = 0; b < bands.length; b++) {
                        // Log-spaced bands: more of them for the low and middle notes, where the music is.
                        int lo = (int) Math.max(1, Math.pow(n, b / (float) bands.length)), hi = (int) Math.max(lo + 1, Math.pow(n, (b + 1) / (float) bands.length));
                        float m = 0;
                        for (int i = lo; i < hi && i < n; i++) {
                            float re = fft[2 * i], im = fft[2 * i + 1];
                            m = Math.max(m, (float) Math.hypot(re, im));
                        }
                        float v = (float) Math.min(1, Math.log10(1 + m) / 2.1);
                        bands[b] += (v - bands[b]) * (v > bands[b] ? 0.55f : 0.18f);
                        sum += v;
                    }
                    if (sum < 0.01f) {   // all zeros: this TV gives no data (treated as not allowed after 3 s)
                        if (silentSince == 0) silentSince = now;
                        else if (now - silentSince > 3000 && player.position() > 5000) {
                            vizBroken = true;
                            releaseViz();
                        }
                    } else silentSince = 0;
                }
            } catch (Throwable t) {
                vizBroken = true;
                releaseViz();
            }
        }
        if (!real) {   // made-up: slow waves, quiet when paused
            float t = now / 1000f, amp = player.playing() ? 0.55f : 0.08f;
            for (int b = 0; b < bands.length; b++) {
                float v = amp * (0.35f + 0.35f * (float) Math.sin(t * 1.7 + b * 0.45) * (float) Math.sin(t * 0.9 + b * 0.13)
                        + 0.25f * (float) Math.sin(t * 3.1 + b * 0.8)) * (1 - b / (float) bands.length * 0.5f);
                v = Math.max(0.02f, v);
                bands[b] += (v - bands[b]) * 0.2f;
            }
        }
        float low = 0;
        for (int b = 0; b < 10; b++) low += bands[b];
        energy = low / 10;
        energyAvg += (energy - energyAvg) * 0.04f;
        if (energy > energyAvg * 1.25f + 0.04f) beat = Math.min(1, beat + 0.5f);
        beat *= 0.9f;
        for (int b = 0; b < bands.length; b++) peaks[b] = Math.max(bands[b], peaks[b] - 0.012f);
    }

    private void releaseViz() {
        if (viz != null) {
            try {
                viz.setEnabled(false);
                viz.release();
            } catch (Throwable ignored) {
            }
            viz = null;
        }
        vizSession = 0;
    }

    // ---- keys ---------------------------------------------------------------------------------------

    /** Back: closes what is open, the player last (the music carries on); false when the screen should close. */
    boolean back() {
        if (lyricChoices != null) lyricChoices = null;
        else if (screenOff) screenOff = false;
        else if (moreOpen) moreOpen = false;
        else if (viewOpen) viewOpen = false;
        else if (playerShown && controls) controls = false;
        else if (playerShown) showPlayer(false);
        else if (finding) finding = false;
        else if (editing != null) editing = null;
        else if (group != null) {
            group = null;
            rowSel = 0;
        } else if (!inBar) inBar = true;
        else return false;
        invalidate();
        return true;
    }

    private long lastRepeat;

    private final Runnable snapBehind = () -> {
        if (this.editing == null && !this.moreOpen && this.lyricChoices == null && getContext() instanceof android.app.Activity) Frost.capture((android.app.Activity) getContext());
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
        // A held (or repeating) arrow moves at a steady pace, not as fast as the remote repeats.
        if (event.getRepeatCount() > 0 && keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            long t = SystemClock.uptimeMillis();
            if (t - lastRepeat < 160) return true;
            lastRepeat = t;
        }
        lastKey = SystemClock.uptimeMillis();
        if (screenOff) {   // any key wakes the screen
            screenOff = false;
            invalidate();
            return true;
        }
        if (player.mediaKey(keyCode)) {
            invalidate();
            return true;
        }
        int pressed = musicKey(event);
        if (playerShown && pressed != 0 && lyricChoices == null) {   // the buttons chosen in Customize
            int forScript = musicKeyFor(getContext(), KEY_SCRIPT), forFind = musicKeyFor(getContext(), KEY_FIND);
            if (pressed == forScript || pressed == forFind) {
                if (event.getRepeatCount() > 0) return true;
                if (pressed == forScript) switchScript();
                else {
                    MyMusic.Song s = player.current();
                    if (s != null) {
                        moreOpen = false;
                        askLyrics(s);
                    }
                }
                invalidate();
                return true;
            }
        }
        if (subtitleKey(event)) return true;
        if (textKey(event)) {   // TEXT while browsing: search
            if (event.getRepeatCount() > 0) return true;
            if (!playerShown && editing == null) {
                inBar = true;
                barSel = T_SEARCH;
                openSearch();
            }
            invalidate();
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_BACK && !isDpadOrOk(keyCode))   // which buttons this remote sends (for mapping)
            android.util.Log.d("TVTownKeys", "key " + keyCode + " scan " + event.getScanCode());
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok && event.getRepeatCount() > 0) return true;
        boolean handled;
        if (playerShown) handled = playerKey(keyCode, ok);
        else if (finding) handled = findKey(keyCode, ok);
        else if (editing != null) handled = editKey(keyCode, ok);
        else if (inBar) handled = barKey(keyCode, ok);
        else if (list() != null) handled = listKey(keyCode, ok);
        else handled = gridKey(keyCode, ok);
        if (handled) invalidate();
        return handled || super.onKeyDown(keyCode, event);
    }

    private static boolean isDpadOrOk(int k) {
        return k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
    }

    private int lastTab() {
        return player.active() ? B_NOW : 0;
    }

    /** The views in Browse's menu, top to bottom: Downloaded is one of them. */
    private static final int[] VIEWS = {T_FOLDERS, T_ARTISTS, T_SONGS, T_FAVS, B_DOWNLOADED};

    private static int viewIndex(int t) {
        for (int i = 0; i < VIEWS.length; i++) if (VIEWS[i] == t) return i;
        return 0;
    }

    private boolean barKey(int key, boolean ok) {
        if (viewOpen) {
            if (ok) {
                viewOpen = false;
                setTab(viewSel);
                if (hasContent()) inBar = false;
                return true;
            }
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (viewIndex(viewSel) > 0) viewSel = VIEWS[viewIndex(viewSel) - 1];
                    else viewOpen = false;
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    viewSel = VIEWS[Math.min(VIEWS.length - 1, viewIndex(viewSel) + 1)];
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    viewOpen = false;
                    return true;
                default: return false;
            }
        }
        if (ok) {
            if (barSel == 0) {
                viewOpen = true;
                viewSel = VIEWS[viewIndex(tab)];
            } else if (barSel == T_SEARCH) openSearch();
            else showPlayer(true);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:   // Search, Browse, then Now playing while a song is on
                barSel = barSel == B_NOW ? 0 : T_SEARCH;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                barSel = barSel == T_SEARCH ? 0 : lastTab();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:   // on Browse: into its menu; elsewhere: down to the songs
                if (barSel == 0) {
                    viewOpen = true;
                    viewSel = VIEWS[viewIndex(tab)];
                } else if (hasContent()) inBar = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: return true;
            default: return false;
        }
    }

    private void setTab(int t) {
        if (t == tab && group == null) return;
        tab = t;
        group = null;
        rowSel = 0;
        gridSel = 0;
        col = 0;
        listFrom = listTo = gridFrom = gridTo = 0;
        if (t != T_SEARCH && t != B_DOWNLOADED) MyMusic.settings(getContext()).edit().putInt("tab", t).apply();
    }

    private boolean hasContent() {
        List<MyMusic.Song> l = list();
        return l != null ? !l.isEmpty() || group != null : !groups().isEmpty();
    }

    private boolean gridKey(int key, boolean ok) {
        List<MyMusic.Group> g = groups();
        if (g.isEmpty()) return false;
        if (ok) {
            group = g.get(gridSel);
            rowSel = -1;   // on Play all
            col = 0;
            listFrom = listTo = 0;
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (gridSel % cols() > 0) gridMove(gridSel - 1);
                else {
                    inBar = true;
                    barSel = T_SEARCH;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (gridSel % cols() < cols() - 1 && gridSel + 1 < g.size()) gridMove(gridSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridSel >= cols()) gridMove(gridSel - cols());
                else {
                    inBar = true;
                    barSel = 0;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (gridSel / cols() < (g.size() - 1) / cols()) gridMove(Math.min(g.size() - 1, gridSel + cols()));
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                play(g.get(gridSel).songs, 0, player.shuffle(), g.get(gridSel).name);
                return true;
            default: return false;
        }
    }

    private void gridMove(int i) {
        gridSel = i;
        float want = rowInView(i / cols(), gridTo, gridRows());
        if (want != gridTo) {
            long now = SystemClock.uptimeMillis();
            gridFrom = glide(gridFrom, gridTo, gridStart, now);
            gridTo = want;
            gridStart = now;
        }
    }

    /** A song list: Up / Down along the songs, Left / Right between the song, ❤ and ✏; OK plays from a song. */
    private boolean listKey(int key, boolean ok) {
        List<MyMusic.Song> l = list();
        if (l == null) return false;
        int first = group != null ? -1 : 0;
        if (ok) {
            if (rowSel < 0) {   // the folder's buttons
                if (col == 0) play(l, 0, false, group.name);
                else if (col == 1) play(l, -1, true, group.name);
                else if (tab == T_FOLDERS) {
                    boolean on = !MyMusic.favouriteFolder(getContext(), group.name);
                    MyMusic.setFavouriteFolder(getContext(), group.name, on);
                    folders = sortFolders(folders);
                    say(on ? "❤" + group.name : "" + group.name);
                }
                return true;
            }
            MyMusic.Song s = l.get(rowSel);
            if (col == 0) play(l, rowSel, false, group != null ? group.name : tab == T_FAVS ? "Favourites" : tab == B_DOWNLOADED ? "Downloaded" : "All songs");
            else if (col == 1) {
                boolean on = !MyMusic.favourite(getContext(), s);
                MyMusic.setFavourite(getContext(), s, on);
                if (tab == T_FAVS && group == null) clampFocus();
            } else openEdit(s);
            return true;
        }
        int cols = rowSel < 0 ? (tab == T_FOLDERS ? 3 : 2) : 3;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (rowSel > first) listMove(rowSel - 1);
                else {   // the top of the list: up to the bar
                    inBar = true;
                    barSel = 0;
                    return true;
                }
                col = Math.min(col, rowSel < 0 ? (tab == T_FOLDERS ? 2 : 1) : 2);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (rowSel < l.size() - 1) listMove(rowSel + 1);
                if (rowSel >= 0 && col > 2) col = 2;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 0) {
                    inBar = true;
                    barSel = T_SEARCH;
                } else col--;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: col = Math.min(cols - 1, col + 1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                listMove(Math.min(l.size() - 1, rowSel + listRows()));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                listMove(Math.max(first, rowSel - listRows()));
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                play(l, Math.max(0, rowSel), false, group != null ? group.name : "All songs");
                return true;
            default: return false;
        }
    }

    private void listMove(int i) {
        rowSel = i;
        float want = rowInView(Math.max(0, i), listTo, listRows());
        if (want != listTo) {
            long now = SystemClock.uptimeMillis();
            listFrom = glide(listFrom, listTo, listStart, now);
            listTo = want;
            listStart = now;
        }
    }

    private static float rowInView(int row, float first, int rows) {
        if (row < first) return row;
        if (row > first + rows - 1) return row - rows + 1;
        return first;
    }

    private static float glide(float from, float to, long start, long now) {
        return from + (to - from) * Theme.progress(now, start, Theme.MOVE_MS);
    }

    // ---- editing a song -----------------------------------------------------------------------------

    private void openEdit(MyMusic.Song s) {
        editing = s;
        editSel = 0;
        finding = false;
    }

    private List<String> editItems() {
        List<String> out = new ArrayList<>();
        MyMusic.Song s = editing;
        out.add("✏Name:  " + s.title);
        out.add("Artist:  " + (s.artist.isEmpty() ? "—" : s.artist));
        out.add("Genre:  " + (s.genre.isEmpty() ? "—" : s.genre));
        out.add("Find the right details and cover online");
        if (MyMusic.edited(getContext(), s)) out.add("↺  Undo my changes");
        return out;
    }

    private boolean editKey(int key, boolean ok) {
        int n = editItems().size();
        if (ok) {
            MyMusic.Song s = editing;
            switch (editSel) {
                case 0: ask("Song name", s.title, t -> { if (!t.isEmpty()) MyMusic.edit(getContext(), s, t, null, null, null); }); break;
                case 1: ask("Artist", s.artist, t -> MyMusic.edit(getContext(), s, null, t, null, null)); break;
                case 2: ask("Genre (for example Ghazal, Sufi, Retro)", s.genre, t -> MyMusic.edit(getContext(), s, null, null, t, null)); break;
                case 3: findOnline(s.title + " " + MyMusic.mainArtist(s.artist)); break;
                default:
                    MyMusic.clearEdits(getContext(), s);
                    editing = null;
                    say("↺  Back to the file's own details");
                    rescan();
            }
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: editSel = Math.max(0, editSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: editSel = Math.min(n - 1, editSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
            default: return false;
        }
    }

    private void ask(String what, String initial, Consumer<String> then) {
        if (listener == null) return;
        listener.onType(what, initial, t -> {
            if (t == null) return;
            then.accept(t.trim());
            regroup();
            invalidate();
        });
    }

    /** After an edit: artists and lists sorted again (names may have changed). */
    private void regroup() {
        java.util.Collections.sort(all, (a, b) -> a.title.compareToIgnoreCase(b.title));
        folders = sortFolders(MyMusic.folders(all));
        artists = MyMusic.artists(all);
        if (group != null) {
            MyMusic.Group g = find(tab == T_ARTISTS ? artists : folders, group.name);
            if (g != null) group = g;
        }
        clampFocus();
    }

    private void findOnline(String words) {
        finding = true;
        findLoading = true;
        found.clear();
        editSel = 0;
        final MyMusic.Song s = editing;
        net.execute(() -> {
            List<SongInfo.Found> got = SongInfo.search(getContext(), words);
            main.post(() -> {
                if (editing != s) return;
                found.clear();
                // Same length first: most likely the same recording.
                java.util.Collections.sort(got, (a, b) -> Long.compare(Math.abs(a.durationMs - s.durationMs), Math.abs(b.durationMs - s.durationMs)));
                found.addAll(got);
                findLoading = false;
                invalidate();
            });
        });
    }

    private boolean findKey(int key, boolean ok) {
        int n = found.size() + 1;   // the last line: search with other words
        if (ok) {
            if (editSel >= found.size()) {
                MyMusic.Song s = editing;
                if (listener != null) listener.onType("Search for the song", s.title, t -> {
                    if (t != null && !t.trim().isEmpty()) findOnline(t.trim());
                });
                return true;
            }
            SongInfo.Found f = found.get(editSel);
            MyMusic.edit(getContext(), editing, f.title, f.artist, f.genre, f.cover != null ? f.cover : "");
            say("" + f.title + "  ·  " + f.artist);
            finding = false;
            editing = null;
            regroup();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: editSel = Math.max(0, editSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: editSel = Math.min(n - 1, editSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
            default: return false;
        }
    }

    // ---- the player's keys ----------------------------------------------------------------------------

    // Control buttons, left to right.
    // The row holds the everyday ones (⏮ ⏯ ⏭ 🔀 🔁 ❤ ⋯); lyrics, effects, sleep and screen off are in ⋯ More, and on
    // the remote's SUBTITLE and TEXT buttons.
    private static final int C_PREV = 0, C_PLAY = 1, C_NEXT = 2, C_SHUFFLE = 3, C_REPEAT = 4, C_FAV = 5, C_MORE = 6,
            C_LYRICS = 7, C_VIS = 8, C_SLEEP = 9, C_OFF = 10;
    private static final int[] SLEEPS = {15, 30, 45, 60, 90, -1, 0};

    private boolean playerKey(int key, boolean ok) {
        if (lyricChoices != null) return lyricPickKey(key, ok);
        if (moreOpen) return moreKey(key, ok);
        if (!controls) {
            if (ok) {
                player.toggle();
                say(player.playing() ? "▶" : "");
                return true;
            }
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: player.previous(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: player.next(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    controls = true;
                    ctrlSel = C_PLAY;
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:   // straight to the settings panel
                    openPanel(pref("music_show") == SHOW_VIDEOS ? 1 : pref("music_show") == SHOW_EFFECTS ? 0 : 2);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: player.seekTo(player.position() + 15_000); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND: player.seekTo(player.position() - 15_000); return true;
                default: return false;
            }
        }
        if (ok) {
            control(ctrlSel);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {   // along the buttons as drawn
                int at = 0;
                for (int i = 0; i < CTRL_ORDER.length; i++) if (CTRL_ORDER[i] == ctrlSel) at = i;
                at = Math.max(0, Math.min(CTRL_ORDER.length - 1, at + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                ctrlSel = CTRL_ORDER[at];
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: return true;   // stays on the buttons: Left and Right must keep moving along them
            case KeyEvent.KEYCODE_DPAD_UP:
                controls = false;
                return true;
            default: return false;
        }
    }

    private void control(int c) {
        MyMusic.Song s = player.current();
        switch (c) {
            case C_PREV: player.previous(); break;
            case C_PLAY: player.toggle(); break;
            case C_NEXT: player.next(); break;
            case C_SHUFFLE:
                player.setShuffle(!player.shuffle());
                say(player.shuffle() ? "Shuffle on" : "Shuffle off");
                break;
            case C_REPEAT:
                player.cycleRepeat();
                say(player.repeat() == MusicPlayer.REPEAT_ONE ? "Repeat this song" : player.repeat() == MusicPlayer.REPEAT_ALL
                        ? "Repeat all" : "Repeat off");
                break;
            case C_FAV:
                if (s != null) {
                    boolean on = !MyMusic.favourite(getContext(), s);
                    MyMusic.setFavourite(getContext(), s, on);
                    say(on ? "❤Added to Favourites" : "Taken out of Favourites");
                }
                break;
            case C_LYRICS:
                lyricsOn = !lyricsOn;
                MyMusic.settings(getContext()).edit().putBoolean("lyrics", lyricsOn).apply();
                say(lyricsOn ? "Lyrics on" : "Lyrics off");
                break;
            case C_VIS: break;
            case C_SLEEP: {
                int now = player.sleepLeft(), next = 0;
                // The next step up from what is set (off → 15 → 30 … → end of song → off).
                int idx = -1;
                if (now == -1) idx = 5;
                else if (now > 0) for (int i = 0; i < 5; i++) if (SLEEPS[i] >= now) {
                    idx = i;
                    break;
                }
                next = SLEEPS[(idx + 1) % SLEEPS.length];
                player.sleepIn(next);
                say(next > 0 ? "Music stops in " + next + " min" : next < 0 ? "Stops after this song" : "Sleep timer off");
                break;
            }
            case C_OFF:
                screenOff = true;
                controls = false;
                break;
            default:
                openPanel(0);
        }
    }

    // What's behind the player, and everything else you can set, in one side panel with four tabs.
    private static final int SHOW_VIDEOS = 0, SHOW_RECORD = 1, SHOW_EFFECTS = 2;
    private static final String[] TABS = {"Effects", "Videos", "Cover", "Controls"};
    private static final String[] CHANGE_NAMES = {"Stay on this one", "Every 30 seconds", "With each song"};
    private final MusicEffects effects = new MusicEffects();
    private int moreTab;

    private int pref(String name) {
        return MyMusic.settings(getContext()).getInt(name, 0);
    }

    private void setPref(String name, int v) {
        MyMusic.settings(getContext()).edit().putInt(name, v).apply();
    }

    private int effectNow() {
        if (pref("music_show") != SHOW_EFFECTS) return -1;
        int n = MusicEffects.NAMES.length, base = Math.floorMod(pref("music_fx"), n);
        switch (pref("music_fx_change")) {
            case 1:
                return (int) ((base + SystemClock.uptimeMillis() / 30_000) % n);
            case 2: {
                MyMusic.Song s = player.current();
                return s == null ? base : Math.floorMod(base + s.key().hashCode(), n);
            }
            default:
                return base;
        }
    }

    /** Rows of the tab showing: what doesn't apply isn't there. */
    private String[] panelRows() {
        boolean fx = pref("music_show") == SHOW_EFFECTS, vid = pref("music_show") == SHOW_VIDEOS;
        switch (moreTab) {
            case 0: return fx ? new String[]{"fx", "effect", "change"} : new String[]{"fx", "line"};
            case 1: return vid ? new String[]{"videos", "scene"} : new String[]{"videos"};
            case 2: return pref("music_style") == 1 ? new String[]{"style"} : new String[]{"style", "spin", "arm"};
            default: return new String[]{"lyrics", "timing", "script", "find", "sleep", "off", "fix", "stop"};
        }
    }

    private void openPanel(int tab) {
        moreOpen = true;
        moreTab = tab;
        moreSel = -1;   // on the tabs: Left and Right move between them
    }

    private String rowLabel(String id) {
        switch (id) {
            case "fx": return "Effects";
            case "effect": return "Effect";
            case "change": return "Changes";
            case "line": return "Music line";
            case "videos": return "Music videos";
            case "scene": return "Next video";
            case "style": return "Show as";
            case "spin": return "Spinning";
            case "arm": return "Tone arm";
            case "lyrics": return "Show lyrics";
            case "timing": return "Lyrics timing";
            case "script": return "Lyrics written in";
            case "find": return lyrics.isEmpty() || lyricsApprox ? "Find lyrics" : "Find other lyrics";
            case "sleep": return "Sleep timer";
            case "off": return "Turn screen off";
            case "fix": return "Fix song details";
            default: return "Stop music";
        }
    }

    /** "On" and "Off" draw as a switch; others as words, with arrows when Left and Right change them. */
    @Nullable
    private String rowValue(String id) {
        MyMusic.Song s = player.current();
        int z = player.sleepLeft();
        switch (id) {
            case "fx": return pref("music_show") == SHOW_EFFECTS ? "On" : "Off";
            case "videos": return pref("music_show") == SHOW_VIDEOS ? "On" : "Off";
            case "line": return vis != VIS_OFF ? "On" : "Off";
            case "spin": return pref("music_nospin") == 1 ? "Off" : "On";
            case "arm": return pref("music_noarm") == 1 ? "Off" : "On";
            case "lyrics": return lyricsOn ? "On" : "Off";
            case "effect": return MusicEffects.NAMES[Math.floorMod(pref("music_fx"), MusicEffects.NAMES.length)];
            case "change": return CHANGE_NAMES[pref("music_fx_change")];
            case "style": return pref("music_style") == 1 ? "Album cover" : "Vinyl record";
            case "timing": return String.format(Locale.US, "%+.1f s", (s != null ? MyMusic.lyricsOffset(getContext(), s) : 0) / 1000f);
            case "script": return Lyrics.SCRIPT_HINDI.equals(script) ? "Hindi" : "English";
            case "sleep": return z == 0 ? "Off" : z < 0 ? "End of song" : z + " min";
            default: return null;
        }
    }

    /** OK on a row. Left and Right (dir) also step the rows that have several choices. */
    private void rowStep(String id, int dir) {
        MyMusic.Song s = player.current();
        switch (id) {
            case "fx": case "videos": {   // one press; both off leaves the big record
                int mine = id.equals("videos") ? SHOW_VIDEOS : SHOW_EFFECTS;
                setPref("music_show", pref("music_show") == mine ? SHOW_RECORD : mine);
                if (listener != null) listener.onScene(sceneWords(s == null ? null : s.folder));
                break;
            }
            case "effect": setPref("music_fx", Math.floorMod(pref("music_fx") + dir, MusicEffects.NAMES.length)); break;
            case "change": setPref("music_fx_change", Math.floorMod(pref("music_fx_change") + dir, CHANGE_NAMES.length)); break;
            case "style": setPref("music_style", 1 - pref("music_style")); moreSel = 0; break;
            case "spin": setPref("music_nospin", 1 - pref("music_nospin")); break;
            case "arm": setPref("music_noarm", 1 - pref("music_noarm")); break;
            case "line":
                vis = vis == VIS_OFF ? VIS_WAVE : VIS_OFF;
                MyMusic.settings(getContext()).edit().putInt("vis2", vis).apply();
                break;
            case "lyrics": control(C_LYRICS); break;
            case "script": switchScript(); break;
            case "sleep": control(C_SLEEP); break;
            case "timing":
                if (s != null) MyMusic.setLyricsOffset(getContext(), s, MyMusic.lyricsOffset(getContext(), s) + dir * 500L);
                break;
            default:
        }
    }

    private boolean moreKey(int key, boolean ok) {
        String[] rows = panelRows();
        if (moreSel >= rows.length) moreSel = rows.length - 1;
        if (ok) {
            if (moreSel < 0) {
                moreSel = 0;
                return true;
            }
            MyMusic.Song s = player.current();
            String id = rows[moreSel];
            switch (id) {
                case "scene":
                    if (s != null && listener != null) listener.onScene(sceneWords(s.folder));
                    return true;
                case "find":
                    moreOpen = false;
                    if (s != null) askLyrics(s);
                    return true;
                case "off":
                    moreOpen = false;
                    control(C_OFF);
                    return true;
                case "fix":
                    if (s != null) {
                        moreOpen = false;
                        showPlayer(false);
                        openEdit(s);
                    }
                    return true;
                case "stop":
                    moreOpen = false;
                    player.stop();
                    return true;
                default:
                    rowStep(id, 1);
                    return true;
            }
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: moreSel = Math.max(-1, moreSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moreSel = Math.min(rows.length - 1, moreSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (moreSel < 0) moreTab = Math.floorMod(moreTab + dir, TABS.length);
                else if (java.util.Arrays.asList("effect", "change", "style", "timing").contains(rows[moreSel])) rowStep(rows[moreSel], dir);
                return true;
            }
            default: return false;
        }
    }

    /** Lyrics in Hindi script or English letters (the TEXT button in the player). */
    private static MyMusicView live;

    /** The phone remote's music buttons. The answer is what the phone says back. */
    static String fromPhone(String what) {
        MyMusicView v = live;
        if (v == null || !v.playerShown) return "noplayer";
        v.post(() -> {
            switch (what) {
                case "lyrics":
                    v.control(C_LYRICS);
                    break;
                case "script":
                    v.switchScript();
                    break;
                case "find": {
                    MyMusic.Song s = v.player.current();
                    if (s != null) {
                        v.moreOpen = false;
                        v.askLyrics(s);
                    }
                    break;
                }
                case "effect": {
                    v.setPref("music_show", SHOW_EFFECTS);
                    v.setPref("music_fx", Math.floorMod(v.pref("music_fx") + 1, MusicEffects.NAMES.length));
                    v.say(MusicEffects.NAMES[Math.max(0, v.effectNow())]);
                    break;
                }
                default:
                    break;
            }
            v.invalidate();
        });
        return "ok";
    }

    private void switchScript() {
        script = Lyrics.SCRIPT_HINDI.equals(script) ? Lyrics.SCRIPT_LATIN : Lyrics.SCRIPT_HINDI;
        MyMusic.settings(getContext()).edit().putString("script", script).apply();
        say(Lyrics.SCRIPT_HINDI.equals(script) ? "Lyrics in Hindi script" : "Lyrics in English letters");
        MyMusic.Song s = player.current();
        // The same lyrics, only written differently; a search runs only if there are none yet.
        if (s != null && !lyricsSource.isEmpty() && s.key().equals(lyricsKey)) showLyrics(lyricsSource, s);
        else if (s != null) loadLyrics(s);
    }

    // ---- the remote's own buttons ------------------------------------------------------------------------

    // The remote's raw button numbers (Linux KEY_SUBTITLE and the TEXT button), for TVs whose button table leaves
    // these two unnamed (the Philips sends them as KEYCODE_UNKNOWN with only this number).
    private static final int SCAN_SUBTITLE = 370, SCAN_TEXT = 468;

    /** The SUBTITLE button: CAPTIONS, or the unnamed button 370. */
    static boolean subtitleKey(KeyEvent e) {
        return e.getKeyCode() == KeyEvent.KEYCODE_CAPTIONS || e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && e.getScanCode() == SCAN_SUBTITLE;
    }

    /** The TEXT (teletext) button: TV_TELETEXT or F3, or the unnamed button 468. */
    static boolean textKey(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_TV_TELETEXT || c == KeyEvent.KEYCODE_F3 || c == KeyEvent.KEYCODE_UNKNOWN && e.getScanCode() == SCAN_TEXT;
    }

    // Which remote button does what in the player, chosen in Customize.
    static final String KEY_SCRIPT = "key_lyrics_script", KEY_FIND = "key_lyrics_find";
    static final String[] MUSIC_KEYS = {"Off", "Text", "Subtitle", "Red", "Green", "Yellow", "Blue"};

    /** The button chosen for a job (an index of MUSIC_KEYS): Text changes the writing and Subtitle finds lyrics unless changed. */
    static int musicKeyFor(Context c, String job) {
        return MyMusic.settings(c).getInt(job, job.equals(KEY_SCRIPT) ? 1 : 2);
    }

    static void setMusicKey(Context c, String job, int key) {
        MyMusic.settings(c).edit().putInt(job, Math.floorMod(key, MUSIC_KEYS.length)).apply();
    }

    private static int musicKey(KeyEvent e) {
        if (textKey(e)) return 1;
        if (subtitleKey(e)) return 2;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_PROG_RED: return 3;
            case KeyEvent.KEYCODE_PROG_GREEN: return 4;
            case KeyEvent.KEYCODE_PROG_YELLOW: return 5;
            case KeyEvent.KEYCODE_PROG_BLUE: return 6;
            default: return 0;
        }
    }

    // Find lyrics: the versions found for the words typed, to choose from.
    private List<Lyrics.Choice> lyricChoices;
    private boolean lyricLooking;
    private int lyricSel;

    /** Asks for the words (song name and singer filled in), then lists every version found. */
    private void askLyrics(MyMusic.Song s) {
        if (listener == null) return;
        String first = MyMusic.mainArtist(s.artist);
        listener.onType("Find lyrics: song name and singer", (s.title + " " + first).trim(), words -> {
            if (words == null || words.trim().isEmpty()) return;
            lyricChoices = new ArrayList<>();
            lyricLooking = true;
            lyricSel = 0;
            long secs = s.durationMs / 1000;
            net.execute(() -> {
                List<Lyrics.Choice> got = Lyrics.choices(words.trim(), secs);
                main.post(() -> {
                    lyricLooking = false;
                    if (got == null) {
                        lyricChoices = null;
                        say("The lyrics site is busy. Try again in a minute.");
                    } else lyricChoices = got;
                    invalidate();
                });
            });
        });
    }

    private boolean lyricPickKey(int key, boolean ok) {
        int n = lyricChoices.size();
        if (ok) {
            MyMusic.Song s = player.current();
            if (lyricSel < n && s != null) {
                Lyrics.Choice c = lyricChoices.get(lyricSel);
                List<Lyrics.Line> lines = Lyrics.keep(s, c, script);
                showLyrics(lines, s);
                lyricsApprox = lines instanceof Lyrics.Rough;
                lyricsLoading = false;
                MyMusic.setLyricsOffset(getContext(), s, 0);
                say(c.timed ? "Lyrics saved with the song" : "Lyrics saved (rough timing)");
            } else if (s != null) askLyrics(s);   // the last line: other words
            lyricChoices = null;
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: lyricSel = Math.max(0, lyricSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: lyricSel = Math.min(n, lyricSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
            default: return false;
        }
    }

    /** The versions found, over the player: name · singer, length, timed or plain; the last line searches again. */
    private void drawLyricChoices(Canvas c) {
        Frost.blurBehind(c, getWidth(), getHeight(), bmp);
        float w = 1300 * k, h = 760 * k;
        RectF p = new RectF((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        kit.panel(c, p, 30 * k, 1, Theme.TINT_HIGH);
        fill.setColor(0xF20D0D0F);   // Home's dark glass
        c.drawRoundRect(p, 30 * k, 30 * k, fill);
        big.setAlpha(255);
        c.drawText("Find lyrics", p.left + 44 * k, p.top + 70 * k, big);
        MyMusic.Song s = player.current();
        rowMeta.setAlpha(180);
        if (s != null) c.drawText("Your song is " + MyMovies.clock(s.durationMs) + " long: the closest length keeps best time.",
                p.left + 44 * k, p.top + 112 * k, rowMeta);
        float y = p.top + 140 * k;
        if (lyricLooking) {
            sub.setAlpha(230);
            c.drawText("Looking…", p.left + 44 * k, y + 60 * k, sub);
            return;
        }
        int n = lyricChoices.size() + 1, visible = 6, first = Math.max(0, Math.min(n - visible, lyricSel - 2));
        for (int v = 0; v < visible && first + v < n; v++) {
            int i = first + v;
            RectF r = new RectF(p.left + 30 * k, y + v * 98 * k, p.right - 30 * k, y + v * 98 * k + 88 * k);
            if (i == lyricSel) {
                fill.setColor(0x38FFFFFF);   // chosen: a cyan wash, as everywhere
                c.drawRoundRect(r, 16 * k, 16 * k, fill);
            }
            if (i == lyricChoices.size()) {
                chipBold.setAlpha(255);
                c.drawText(lyricChoices.isEmpty() ? "Nothing found.  ✏Try other words…" : "✏Try other words…", r.left + 24 * k, r.centerY() + 9 * k, chipBold);
                continue;
            }
            Lyrics.Choice ch = lyricChoices.get(i);
            rowTitle.setAlpha(255);
            c.drawText(ellipsize(ch.title + "  ·  " + ch.artist, rowTitle, r.width() - 48 * k), r.left + 24 * k, r.top + 38 * k, rowTitle);
            rowMeta.setAlpha(200);
            String first4 = ch.text.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\s+", " ").trim();
            String meta = (ch.timed ? "timed" : "≈ plain") + "  ·  " + MyMovies.clock(ch.seconds * 1000) + "  ·  " + first4;
            c.drawText(ellipsize(meta, rowMeta, r.width() - 48 * k), r.left + 24 * k, r.top + 72 * k, rowMeta);
        }
    }

    private void say(String s) {
        toast = s;
        toastAt = SystemClock.uptimeMillis();
    }

    // ---- drawing ------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        Frost.frame(this);   // selections glide (Frost.plate)
        if (kit == null) return;
        long now = SystemClock.uptimeMillis();
        if (playerShown) {
            drawPlayer(c, now);
            drawToast(c, now);
            return;
        }
        calmPage(c, now, focusTint());
        // With the sidebar open the page makes room for it: it slides right and shrinks a little, so the heading and
        // the first column stay in full view instead of under the sidebar, and dims only slightly.
        boolean open = false;   // the bar is across the top now; nothing makes room for it
        if (open != barWas) {
            barWas = open;
            barAt = now;
        }
        float t = Theme.progress(now, barAt, 260), o = open ? t : 1 - t;
        if (t < 1) postInvalidateOnAnimation();
        float room = (SIDE_L + SIDE_W + 32 - MAIN_L) * k, sc = 1 - o * room / ((MAIN_R - MAIN_L) * k);
        Paint head = songRow();
        float hs = head.getTextSize();
        head.setTextSize(40 * k);
        c.drawText(getContext().getString(R.string.my_music), MAIN_L * k, 92 * k, head);
        head.setTextSize(hs);
        if (!permission || !drive || !scanned || all.isEmpty()) {
            String msg = getContext().getString(!permission ? R.string.my_movies_permission : !scanned ? R.string.my_music_reading
                    : !drive ? R.string.my_music_no_drive : R.string.my_music_empty);
            TextPaint tp = new TextPaint(big);
            android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(msg, 0, msg.length(), tp, Math.round(1100 * k)).build();
            c.save();
            c.translate(getWidth() / 2f - 550 * k, getHeight() / 2f - l.getHeight() / 2f);
            l.draw(c);
            c.restore();
            return;
        }
        List<MyMusic.Song> l = list();
        c.save();
        c.translate(o * room, 0);
        c.scale(sc, sc, MAIN_L * k, 172 * k);
        if (l != null) drawList(c, l, now);
        else drawGrid(c, now);
        c.restore();
        drawBar(c);   // over the content while you're in it; else a slim strip on the left
        if (editing != null) drawEdit(c);
        drawToast(c, now);
    }

    private int pageFrom = 0xFF5146C8, pageTo = 0xFF5146C8;
    private long pageAt;

    /** The colour of the folder or song you're on. */
    private int focusTint() {
        List<MyMusic.Song> l = list();
        String name = group != null ? group.name : null;
        if (name == null && l != null && !l.isEmpty()) name = l.get(Math.max(0, Math.min(l.size() - 1, rowSel))).folder;
        if (name == null && l == null && !groups().isEmpty()) name = groups().get(Math.max(0, Math.min(groups().size() - 1, gridSel))).name;
        return name == null ? pageTo : NEON[Math.abs(name.hashCode()) % NEON.length];
    }

    /** An aurora: three slow pools of colour drifting over black, in the colour of what you're on and its neighbours. */
    private void calmPage(Canvas c, long now, int want) {
        float t = Math.min(1, (now - pageAt) / 700f);
        if (want != pageTo) {
            pageFrom = (int) new android.animation.ArgbEvaluator().evaluate(t, pageFrom, pageTo);
            pageTo = want;
            pageAt = now;
            t = 0;
            Frost.accentNow = deep(want);
        }
        int col = (int) new android.animation.ArgbEvaluator().evaluate(t, pageFrom, pageTo);
        c.drawColor(0xFF08080B);
        fill.setAlpha(255);
        fill.setShader(new RadialGradient(getWidth() * 0.12f, 0, 1200 * k, withAlpha(col, 46), withAlpha(col, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(null);
    }

    private final RectF auroraTo = new RectF();
    private final Paint auroraPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint blobPaint = new Paint();

    private final java.util.Map<String, Bitmap> discs = new java.util.HashMap<>();

    /** A record at rest, painted once: nine of them cost no more than one picture each. */
    private void restingDisc(Canvas c, String name, float cx, float cy, float rad, @Nullable Bitmap label, int col) {
        String key = name + (label != null) + Math.round(rad);
        Bitmap d = discs.get(key);
        if (d == null) {
            int px = Math.round(rad * 2) + 2;
            d = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            vinyl(new Canvas(d), px / 2f, px / 2f, rad, 0, label, col);
            discs.put(key, d);
        }
        c.drawBitmap(d, cx - d.getWidth() / 2f, cy - d.getHeight() / 2f, bmp);
    }

    /** Rich and deep enough to carry white words. */
    private static int deep(int col) {
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        hsv[1] = Math.max(0.6f, hsv[1]);
        hsv[2] = 0.72f;
        return Color.HSVToColor(hsv);
    }

    // ---- the record ---------------------------------------------------------------------------------------

    private final Path labelClip = new Path();
    private final Paint vinylFill = new Paint(Paint.ANTI_ALIAS_FLAG), vinylLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** A vinyl record: grooves, light catching it as it turns, and a picture (or colour) for its label. */
    private void vinyl(Canvas c, float cx, float cy, float rad, float angle, @Nullable Bitmap label, int col) {
        vinylFill.setShader(null);
        vinylFill.setColor(0xFF0A0A0E);
        c.drawCircle(cx, cy, rad, vinylFill);
        vinylLine.setStyle(Paint.Style.STROKE);
        vinylLine.setStrokeWidth(Math.max(1, k));
        vinylLine.setColor(0x14FFFFFF);
        for (float f = 0.5f; f < 0.98f; f += 0.07f) c.drawCircle(cx, cy, rad * f, vinylLine);
        c.save();
        c.rotate(angle, cx, cy);
        vinylFill.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x2AFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0, 0.07f, 0.16f, 0.5f, 0.58f, 0.66f, 1}));
        c.drawCircle(cx, cy, rad, vinylFill);
        vinylFill.setShader(null);
        float lr = rad * 0.46f;
        labelClip.reset();
        labelClip.addCircle(cx, cy, lr, Path.Direction.CW);
        c.save();
        c.clipPath(labelClip);
        vinylFill.setColor(col);
        c.drawCircle(cx, cy, lr, vinylFill);
        if (label != null) drawImage(c, label, new RectF(cx - lr, cy - lr, cx + lr, cy + lr), 0, 255);
        else {
            Bitmap note = kit.glyph(WheelArt.Glyph.NOTE, lr / k * 0.9f);
            c.drawBitmap(note, cx - note.getWidth() / 2f, cy - note.getHeight() / 2f, bmp);
        }
        c.restore();
        c.restore();
        vinylFill.setColor(0xFF0A0A0E);
        c.drawCircle(cx, cy, Math.max(3 * k, rad * 0.045f), vinylFill);
        vinylLine.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, rad - k, vinylLine);
    }

    /** A real album cover from a folder, for its record's label. */
    @Nullable
    private Bitmap labelOf(MyMusic.Group g) {
        int n = g.songs.size();
        if (n == 0) return null;
        int start = Math.abs(g.name.hashCode()) % n;
        for (int i = 0; i < n && i < 30; i++) {
            MyMusic.Song x = g.songs.get((start + i) % n);
            if (MyMusic.hasCover(x)) {
                Bitmap b = MyMusic.cover(getContext(), x, this::invalidate);
                if (b != null) return b;
            }
        }
        return null;
    }

    // The bar: the views as tabs on the left; on the right round buttons for Search, Shuffle and Surprise me (the
    // focused one's name under it), then what is playing.
    private static final String[] ACTION_NAMES = {"Search", "Shuffle all", "Surprise me"};

    private static final String[] SIDE_NAMES = {"Folders", "Artists", "All songs", "Favourites", "Search", "Downloaded", "Shuffle all", "Surprise me", "Now playing"};
    private static final WheelArt.Glyph[] SIDE_GLYPHS = {WheelArt.Glyph.GRID, WheelArt.Glyph.TELESCOPE, WheelArt.Glyph.NOTE, WheelArt.Glyph.CHECK,
            WheelArt.Glyph.SEARCH, WheelArt.Glyph.DRIVE, WheelArt.Glyph.SHUFFLE, WheelArt.Glyph.PLAY, WheelArt.Glyph.NOTE};

    private String tabWord(int t) {
        if (t == 0) return tab == T_SEARCH ? "Folders" : SIDE_NAMES[tab];
        if (t == T_SEARCH) return query.isEmpty() ? "Search songs" : query;
        String label = SIDE_NAMES[t];
        if (t == B_NOW) {
            MyMusic.Song cur = player.current();
            label = cur != null ? cur.title : label;
            if (label.length() > 18) label = label.substring(0, 17) + "…";
        }
        return label;
    }

    /** From the right: Now playing (while a song is on), Show, then the search bar, with a small gap between. */
    private RectF tabRect(int t) {
        float x = 1840 * k + 16 * k;
        if (lastTab() == B_NOW) {
            x -= capWidth(B_NOW) + 16 * k;
            if (t == B_NOW) return new RectF(x, 40 * k, x + capWidth(B_NOW), 112 * k);
        }
        x -= capWidth(0) + 16 * k;
        if (t == 0) return new RectF(x, 40 * k, x + capWidth(0), 112 * k);
        x -= capWidth(T_SEARCH) + 16 * k;
        return new RectF(x, 40 * k, x + capWidth(T_SEARCH), 112 * k);
    }

    private static final String SHOW = "Show  ";

    private float capWidth(int t) {
        if (t == T_SEARCH) return 360 * k;   // a search bar, not a button
        if (t == 0) return chip.measureText(SHOW) + chipBold.measureText(tabWord(0) + "  \u25BE") + 60 * k;
        return Math.min(260 * k, chipBold.measureText(tabWord(t))) + 56 * k;
    }

    /** The count beside a view in the sidebar. */
    private String tabCount(int t) {
        switch (t) {
            case T_FOLDERS: return String.valueOf(folders.size());
            case T_ARTISTS: return String.valueOf(artists.size());
            case T_SONGS: return String.valueOf(all.size());
            case T_FAVS: return String.valueOf(favourites().size());
            case B_DOWNLOADED: return String.valueOf(downloaded().size());
            default: return "";
        }
    }

    /** The sidebar: the views with their counts (the open one lit), the actions, what's playing. */
    private void drawBar(Canvas c) {
        if (gTitle == null) neonPaints();
        // Left to right: the search bar, Show (the view, as a drop-down), and Now playing while a song is on.
        for (int t : lastTab() == B_NOW ? new int[]{T_SEARCH, 0, B_NOW} : new int[]{T_SEARCH, 0}) {
            RectF r = tabRect(t);
            boolean focused = inBar && editing == null && (barSel == t && (!viewOpen || t != 0));
            if (t == T_SEARCH) {   // the search bar, the same on every page
                Frost.searchBar(c, r, k, focused, tabWord(t), query.isEmpty(), query.isEmpty() ? chip : chipBold);
                continue;
            }
            if (focused) Frost.plate(c, r, r.height() / 2, k);
            else Frost.ghost(c, r, r.height() / 2, false, k);
            chipBold.setColor(Color.WHITE);
            if (t == 0) {   // Show  Folders ▾
                int was = chip.getColor();
                chip.setColor(0xB4FFFFFF);
                c.drawText(SHOW, r.left + 30 * k, r.centerY() + 9 * k, chip);
                chip.setColor(was);
                c.drawText(tabWord(0) + "  \u25BE", r.left + 30 * k + chip.measureText(SHOW), r.centerY() + 9 * k, chipBold);
            } else c.drawText(ellipsize(tabWord(t), chipBold, r.width() - 40 * k), r.left + 28 * k, r.centerY() + 9 * k, chipBold);
        }
        if (inBar && editing == null && barSel == 0 && viewOpen) {   // Show's menu, open
            float rowH = 76 * k;
            RectF box = new RectF(tabRect(0).left, 124 * k, tabRect(0).left + 480 * k, 124 * k + VIEWS.length * rowH + 24 * k);
            if (box.right > 1880 * k) box.offset(1880 * k - box.right, 0);
            RectF screen = new RectF(0, 0, getWidth(), getHeight());
            Frost.dropdown(c, box, 30 * k, screen, bmp, k);
            for (int vi = 0; vi < VIEWS.length; vi++) {
                int t = VIEWS[vi];
                RectF r = new RectF(box.left + 12 * k, box.top + 12 * k + vi * rowH, box.right - 12 * k, box.top + 12 * k + (vi + 1) * rowH);
                boolean focused = viewOpen && viewSel == t;
                if (focused) Frost.plate(c, r, 20 * k, k);
                Bitmap g = kit.glyph(SIDE_GLYPHS[t], 32);
                c.drawBitmap(g, r.left + 38 * k - g.getWidth() / 2f, r.centerY() - g.getHeight() / 2f, bmp);
                chipBold.setColor(focused || t == tab ? 0xFFFFFFFF : 0xDEF4F4F1);
                c.drawText(SIDE_NAMES[t], r.left + 70 * k, r.centerY() + 10 * k, chipBold);
                chipBold.setColor(Color.WHITE);
                float right = r.right - 20 * k;
                if (t == tab) {
                    Frost.tick(c, right - 14 * k, r.centerY(), 14 * k, k);
                    right -= 44 * k;
                }
                String n = tabCount(t);
                rowMeta.setColor(Frost.MUTE);
                c.drawText(n, right - rowMeta.measureText(n), r.centerY() + 9 * k, rowMeta);
                rowMeta.setColor(0xFFD8D8D6);
            }
        }
    }
    private boolean viewOpen;
    private int viewSel;

    private float cardW() {
        return (MAIN_R - MAIN_L - (cols() - 1) * COL_GAP) / cols();
    }

    private float cardH() {
        return tab == T_ARTISTS ? cardW() + 84 : (1040 - GRID_TOP - 2 * ROW_GAP) / 3;   // three rows fill the screen
    }

    private float rowStep() {
        return cardH() + TEXT_H + ROW_GAP;
    }

    private int gridRows() {
        return Math.max(1, (int) ((1040 - GRID_TOP + ROW_GAP) / rowStep()));
    }

    /** Folders or artists: a card each, its singers' photos side by side, its name and size on glass at the bottom. */
    private void drawGrid(Canvas c, long now) {
        List<MyMusic.Group> g = groups();
        float first = glide(gridFrom, gridTo, gridStart, now), w = cardW() * k, ph = cardH() * k, step = rowStep() * k, top = GRID_TOP * k;
        c.save();
        c.clipRect((MAIN_L - 24) * k, top - 30 * k, getWidth(), getHeight());
        int fromRow = Math.max(0, (int) Math.floor(first)), toRow = (int) Math.ceil(first + gridRows() + 1);
        for (int i = fromRow * cols(); i < g.size() && i < toRow * cols(); i++) {
            MyMusic.Group grp = g.get(i);
            float x = MAIN_L * k + (i % cols()) * (w + COL_GAP * k), y = top + (i / cols() - first) * step;
            boolean focused = i == gridSel && !inBar;
            RectF r = new RectF(x, y, x + w, y + ph);
            c.save();
            if (focused && tab == T_ARTISTS) Frost.plate(c, r, 24 * k, k);
            cardFocused = focused;
            drawGroupArt(c, grp, r, focused, true);
            if (!focused && !GlassKit.neon(getContext())) {
                fill.setColor(0x330B0B0D);
                c.drawRoundRect(r, 20 * k, 20 * k, fill);
            }
            c.restore();
        }
        c.restore();
    }

    // Singers' photos for the cards (by card), looked up once in the background; folders each get their own singers.
    private final java.util.Map<String, List<String>> photos = new java.util.HashMap<>();
    private final java.util.Set<String> photoAsked = new java.util.HashSet<>();
    private java.util.Map<String, List<String>> assigned;
    private static final ExecutorService photoNet = Work.pool(1);

    private java.io.File driveDir;
    private long driveAt;

    /** The plugged-in drive (asked at most every few seconds, not on every frame). */
    @Nullable
    private java.io.File drive() {
        long now = SystemClock.uptimeMillis();
        if (now - driveAt > 5000) {
            driveAt = now;
            driveDir = DriveStore.anyDrive(getContext());
        }
        return driveDir;
    }

    private java.io.File photoStore;
    private boolean photoStoreLooked;

    /** Singers' photos are kept in ".tvtown/photos" on the drive (the TV's memory when there is none). */
    @Nullable
    private java.io.File photoStore() {
        if (!photoStoreLooked) {
            photoStoreLooked = true;
            photoStore = DriveStore.dir(drive(), "photos");
        }
        return photoStore;
    }

    private List<String> photosFor(MyMusic.Group g) {
        boolean folder = tab != T_ARTISTS;
        String key = (folder ? "f:" : "a:") + g.name;
        List<String> got = photos.get(key);
        if (got != null) return got;
        if (photoAsked.add(key)) {
            if (folder && assigned == null) assigned = ArtistPhotos.assign(folders);
            List<String> names = folder ? (assigned.containsKey(g.name) ? assigned.get(g.name) : new ArrayList<>())
                    : java.util.Collections.singletonList(g.name);
            Context ctx = getContext().getApplicationContext();
            photoNet.execute(() -> {
                List<String> urls = new ArrayList<>();
                for (String n : names) {
                    if (urls.size() >= 3) break;
                    String u = ArtistPhotos.photo(ctx, n);
                    if (u != null) urls.add(u);
                    try {
                        Thread.sleep(250);   // gently: Wikipedia limits quick requests
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                main.post(() -> {
                    photos.put(key, urls);   // none is an answer too (kept per singer on the drive): covers make up the card
                    invalidate();
                });
            });
        }
        return java.util.Collections.emptyList();
    }

    // Each card's pictures, chosen once and kept on the drive (".tvtown/cards.json"), so a card looks the same every
    // time: "photo:<url>" (a singer), "cover:<url>" (a song's cover from the catalogue), "song:<key>" (the picture
    // inside a song's file).
    private final java.util.Map<String, MyMusic.Song> byKey = new java.util.HashMap<>();

    @Nullable
    private MyMusic.Song songByKey(String key) {
        if (byKey.size() != all.size()) {
            byKey.clear();
            for (MyMusic.Song s : all) byKey.put(s.key(), s);
        }
        return byKey.get(key);
    }

    private java.io.File coverStoreDir;
    private boolean coverStoreLooked;

    @Nullable
    private java.io.File coverStore() {
        if (!coverStoreLooked) {
            coverStoreLooked = true;
            coverStoreDir = DriveStore.dir(drive(), "covers");
        }
        return coverStoreDir;
    }

    /**
     * The card's pictures: the saved choice; else, once its singers have been looked up, up to three of their photos
     * and then catalogue covers of its songs (in name order), saved as soon as there are three (or, with fewer, when
     * all names are tidied). Until then only the photos, in their places.
     */
    private List<String> cardChoice(MyMusic.Group g) {
        String key = (tab != T_ARTISTS ? "f:" : "a:") + g.name;
        java.io.File drive = drive();
        org.json.JSONObject cards = DriveStore.map(drive, "cards");
        org.json.JSONArray saved = cards.optJSONArray(key);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (int i = 0; i < saved.length(); i++) out.add(saved.optString(i));
            return out;
        }
        List<String> urls = photosFor(g);
        boolean looked = photos.containsKey(key);
        for (String u : urls) if (out.size() < 3) out.add("photo:" + u);
        if (!looked) return out;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (MyMusic.Song s : g.songs) {
            if (out.size() >= 3) break;
            if (s.cover != null && used.add(s.cover)) out.add("cover:" + s.cover);
        }
        if (out.size() < 3 && tidying == 0) {   // all tidied: the pictures inside the files make up the rest
            for (MyMusic.Song s : g.songs) {
                if (out.size() >= 3) break;
                if (s.cover == null && MyMusic.hasCover(s) && MyMusic.cover(getContext(), s, this::invalidate) != null) out.add("song:" + s.key());
            }
        }
        if (out.size() >= 3 || tidying == 0) {
            try {
                cards.put(key, new org.json.JSONArray(out));
                DriveStore.save(drive, "cards");
            } catch (Exception ignored) {
            }
        } else {
            out.removeIf(x -> !x.startsWith("photo:"));   // not settled yet: only the singers, nothing that may change
        }
        return out;
    }

    /** The card's colour, then its pictures in their places (one large left, two stacked right; or two; or one). */
    private void paintPictures(Canvas c, RectF r, int col, List<Bitmap> pics, List<Boolean> face) {
        fill.setAlpha(255);
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, withAlpha(col, 255), withAlpha(darker(col), 255), Shader.TileMode.CLAMP));
        c.drawRect(r, fill);
        fill.setShader(null);
        float gap = 3 * k;
        RectF[] slots;
        if (pics.size() >= 3) {
            float split = r.left + r.width() * 0.56f;
            slots = new RectF[]{new RectF(r.left, r.top, split - gap / 2, r.bottom),
                    new RectF(split + gap / 2, r.top, r.right, r.centerY() - gap / 2),
                    new RectF(split + gap / 2, r.centerY() + gap / 2, r.right, r.bottom)};
        } else if (pics.size() == 2) {
            slots = new RectF[]{new RectF(r.left, r.top, r.centerX() - gap / 2, r.bottom), new RectF(r.centerX() + gap / 2, r.top, r.right, r.bottom)};
        } else if (pics.size() == 1) {
            slots = new RectF[]{new RectF(r)};
        } else slots = new RectF[0];
        for (int i = 0; i < slots.length; i++) {
            if (pics.get(i) == null) continue;
            if (face.get(i)) drawFace(c, pics.get(i), slots[i]);
            else drawImage(c, pics.get(i), slots[i], 0, 255);
        }
    }

    // Finished card pictures: made once from the card's photos and covers, kept on the drive (".tvtown/cards") and
    // in memory, and drawn as a single picture from then on (no loading, no flicker, the same every time).
    private final java.util.Map<String, Bitmap> composites = new java.util.HashMap<>();
    private final java.util.Set<String> compositeAsked = new java.util.HashSet<>();

    private boolean settled(String ckey) {
        return DriveStore.map(drive(), "cards").has(ckey);
    }

    @Nullable
    private java.io.File compositeFile(String ckey) {
        java.io.File d = DriveStore.dir(drive(), "cards");
        return d == null ? null : new java.io.File(d, Net.sha1(ckey) + ".jpg");
    }

    /** The card's finished picture, or null; read from the drive (once, in the background) when it was made before. */
    @Nullable
    private Bitmap composite(String ckey) {
        Bitmap b = composites.get(ckey);
        if (b != null || !compositeAsked.add(ckey)) return b;
        java.io.File f = compositeFile(ckey);
        if (f == null) return null;
        photoNet.execute(() -> {
            if (!f.isFile()) return;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap got = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            if (got != null) main.post(() -> {
                composites.put(ckey, got);
                invalidate();
            });
        });
        return null;
    }

    /** Draws the card's pictures into one picture at the card's size, keeps it, and saves it on the drive. */
    private void makeComposite(String ckey, int col, List<Bitmap> pics, List<Boolean> face) {
        if (composites.containsKey(ckey)) return;
        int w = Math.round(cardW() * k), h = Math.round(cardW() * 9 / 16 * k);
        Bitmap out = Bitmap.createBitmap(w * 3 / 2, h * 3 / 2, Bitmap.Config.RGB_565);   // a little larger: also the folder's header
        paintPictures(new Canvas(out), new RectF(0, 0, out.getWidth(), out.getHeight()), col, pics, face);
        composites.put(ckey, out);
        java.io.File f = compositeFile(ckey);
        if (f != null) photoNet.execute(() -> {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, o);
            } catch (Exception ignored) {
                // made again next time
            }
        });
    }

    /**
     * A card: a poster of three singers (one large on the left, two stacked on the right), or the covers of its
     * songs where photos are missing; a soft dark fade at the bottom with the name in large letters and its size.
     */
    private static final int[] NEON = {0xFF7546F5, 0xFF1685FF, 0xFFF03CBF, 0xFF00D8E8, 0xFF5146C8};

    /**
     * The neon glass look's card: tinted glass with a fine edge. A folder: a big white note in a disc of its colour,
     * the name and the count. A singer: a small cover with a record peeking out behind it, the name and the count.
     */
    private void neonGroup(Canvas c, MyMusic.Group g, RectF r) {
        fill.setShader(null);
        TextPaint np = new TextPaint(big);
        np.clearShadowLayer();
        if (tab == T_ARTISTS) {   // a round portrait, the name under it
            float d = r.width() - 44 * k, cx = r.centerX(), top = r.top + 16 * k;
            RectF cov = new RectF(cx - d / 2, top, cx + d / 2, top + d);
            Bitmap b = null;
            for (String c0 : cardChoice(g)) {
                if (c0.startsWith("photo:")) b = ImageCache.get(c0.substring(6), this::invalidate, photoStore(), 330);
                else if (c0.startsWith("cover:")) b = ImageCache.get(c0.substring(6), this::invalidate, coverStore(), 330);
                if (b != null) break;
            }
            if (b == null && !g.songs.isEmpty()) b = MyMusic.cover(getContext(), g.songs.get(0), this::invalidate);
            if (b != null) drawImage(c, b, cov, d / 2, 255);
            else {
                fill.setColor(0x1FFFFFFF);
                c.drawOval(cov, fill);
                Bitmap gl = kit.glyph(WheelArt.Glyph.NOTE, 64);
                c.drawBitmap(gl, cx - gl.getWidth() / 2f, cov.centerY() - gl.getHeight() / 2f, bmp);
            }
            np.setTextSize(28 * k);
            String nm = ellipsize(g.name, np, r.width() - 12 * k);
            c.drawText(nm, cx - np.measureText(nm) / 2, cov.bottom + 44 * k, np);
            cardMeta.setColor(Frost.MUTE);
            String m = g.songs.size() + " songs";
            c.drawText(m, cx - cardMeta.measureText(m) / 2, cov.bottom + 78 * k, cardMeta);
            return;
        }
        int col = NEON[Math.abs(g.name.hashCode()) % NEON.length];
        if (cardFocused) Frost.plate(c, r, 28 * k, k, deep(col));
        else {
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, (col & 0x00FFFFFF) | 0x52000000, (col & 0x00FFFFFF) | 0x14000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            fill.setShader(null);
            Frost.topEdge(c, r, 28 * k, k, 0x33FFFFFF);
        }
        // The record alone, its cover as the round label; the one you're on turns.
        float size = Math.min(r.height() - 48 * k, 200 * k), cy = r.centerY(), rad = size / 2, cx = r.left + 28 * k + rad;
        long now = SystemClock.uptimeMillis();
        Bitmap label = labelOf(g);
        if (cardFocused && !Lite.on) vinyl(c, cx, cy, rad, (now % 6000) / 6000f * 360, label, col);
        else restingDisc(c, g.name, cx, cy, rad, label, col);
        float tx = cx + rad + 28 * k, tw = r.right - tx - 14 * k;
        String name = g.name.isEmpty() ? "" : g.name.substring(0, 1).toUpperCase(Locale.ROOT) + g.name.substring(1);
        float ns = 34 * k;
        np.setTextSize(ns);
        while (ns > 24 * k && np.measureText(name) > tw) np.setTextSize(ns -= 1 * k);
        c.drawText(ellipsize(name, np, tw), tx, cy - 12 * k, np);
        cardMeta.setColor(cardFocused ? Color.WHITE : 0xD9FFFFFF);
        cardMeta.setTextSize(24 * k);
        cardMeta.clearShadowLayer();
        c.drawText(g.songs.size() + " songs", tx, cy + 26 * k, cardMeta);
        c.drawText(ellipsize(MyMusic.length(g.durationMs()), cardMeta, tw), tx, cy + 58 * k, cardMeta);
        if (MyMusic.favouriteFolder(getContext(), g.name)) {
            Bitmap h = kit.glyph(WheelArt.Glyph.HEART, 26);
            c.drawBitmap(h, r.right - 22 * k - h.getWidth(), r.top + 18 * k, bmp);
        }
    }

    private boolean cardFocused;
    private float spin;
    private long spinAt;

    private void drawGroupArt(Canvas c, MyMusic.Group g, RectF r, boolean focused, boolean label) {
        if (label && GlassKit.neon(getContext())) {
            neonGroup(c, g, r);
            return;
        }
        int h = Math.abs(g.name.hashCode());
        int[] cols = {Theme.VIOLET_B, Theme.ROSE_B, Theme.AMBER_B, Theme.MINT_B, 0xFF3B6FD8, 0xFF8A3FD1};
        int col = cols[h % cols.length];
        float rad = 20 * k;
        c.save();
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(path);
        String ckey = (tab != T_ARTISTS ? "f:" : "a:") + g.name;
        Bitmap comp = composite(ckey);
        if (comp != null) drawImage(c, comp, r, 0, 255);   // the card's finished picture: drawn as one, never changes
        else {
            // Pictures: the card's own choice, made once and kept on the drive, each in its fixed place.
            List<String> choice = cardChoice(g);
            List<Bitmap> pics = new ArrayList<>();
            List<Boolean> face = new ArrayList<>();
            boolean all = !choice.isEmpty();
            for (String c0 : choice) {
                Bitmap b = null;
                if (c0.startsWith("photo:")) b = ImageCache.get(c0.substring(6), this::invalidate, photoStore(), 330);
                else if (c0.startsWith("cover:")) b = ImageCache.get(c0.substring(6), this::invalidate, coverStore(), MyMusic.COVER_PX);
                else {
                    MyMusic.Song song = songByKey(c0.substring(5));
                    if (song != null) b = MyMusic.cover(getContext(), song, this::invalidate);
                }
                pics.add(b);   // may be null while loading: its place stays empty, nothing moves
                face.add(c0.startsWith("photo:"));
                if (b == null) all = false;
            }
            paintPictures(c, r, col, pics, face);
            if (all && settled(ckey)) makeComposite(ckey, col, pics, face);
        }
        if (label) {
            // A fade to dark at the bottom for the name, with a little of the folder's own colour in it.
            fill.setAlpha(255);
            fill.setShader(new LinearGradient(0, r.top + r.height() * 0.35f, 0, r.bottom, 0x000B0B0D, 0xF00B0B0D, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(new LinearGradient(r.left, r.bottom, r.left + r.width() * 0.7f, r.bottom - r.height() * 0.5f,
                    withAlpha(col, 120), withAlpha(col, 0), Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(null);
        }
        c.restore();
        if (!label) return;
        TextPaint np = new TextPaint(big);
        np.setTextSize((focused ? 38 : 36) * k);
        np.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        float tx = r.left + 24 * k, tw = r.width() - 48 * k;
        String name = g.name.isEmpty() ? "" : g.name.substring(0, 1).toUpperCase(Locale.ROOT) + g.name.substring(1);
        c.drawText(ellipsize(name, np, tw), tx, r.bottom - 52 * k, np);
        String meta = g.songs.size() + " songs  ·  " + MyMusic.length(g.durationMs());
        if (tab == T_FOLDERS && MyMusic.favouriteFolder(getContext(), g.name)) meta = "❤" + meta;
        cardMeta.setAlpha(225);
        c.drawText(ellipsize(meta, cardMeta, tw), tx, r.bottom - 20 * k, cardMeta);
    }

    /** A portrait in a slice of the card: filled, kept to the upper part where faces are. */
    private void drawFace(Canvas c, Bitmap img, RectF r) {
        float sc = Math.max(r.width() / img.getWidth(), r.height() / img.getHeight());
        matrix.setScale(sc, sc);
        float extraH = img.getHeight() * sc - r.height();
        matrix.postTranslate(r.centerX() - img.getWidth() * sc / 2, r.top - extraH * 0.18f);
        BitmapShader sh = new BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(matrix);
        image.setShader(sh);
        image.setAlpha(255);
        c.drawRect(r, image);
        image.setShader(null);
    }

    private int listRows() {
        float top = group != null ? GRID_TOP + 112 : GRID_TOP;
        return Math.max(1, (int) ((1080 - top - 70) / ROW_H));
    }

    /** A list of songs, as a clean table: a small picture, the song, its singer, its film or album, its length. */
    private void drawList(Canvas c, List<MyMusic.Song> l, long now) {
        if (gTitle == null) neonPaints();
        float top = GRID_TOP * k, L = MAIN_L * k, R = (MAIN_R - DETAIL_W - 24) * k;
        if (group != null) {   // the folder's name on the left, its buttons on the right
            TextPaint tp = new TextPaint(big);
            tp.clearShadowLayer();
            tp.setTextSize(40 * k);
            float maxName = 900 * k;
            vinyl(c, L + 46 * k, top + 48 * k, 46 * k, Lite.on ? 0 : (now % 6000) / 6000f * 360, labelOf(group), NEON[Math.abs(group.name.hashCode()) % NEON.length]);
            c.drawText(ellipsize(group.name, tp, maxName), L + 116 * k, top + 44 * k, tp);
            mutedRow().setColor(0xD9FFFFFF);
            c.drawText(group.songs.size() + " songs  ·  " + MyMusic.length(group.durationMs()), L + 116 * k, top + 82 * k, mutedRow());
            String[] b = tab == T_FOLDERS ? new String[]{"Play all", "Shuffle",
                    MyMusic.favouriteFolder(getContext(), group.name) ? "♥ Favourite" : "Favourite"} : new String[]{"Play all", "Shuffle"};
            float bx = R;
            for (int i = b.length - 1; i >= 0; i--) {
                float w = chipBold.measureText(b[i]) + 56 * k;
                RectF r = new RectF(bx - w, top + 14 * k, bx, top + 82 * k);
                boolean sel = !inBar && rowSel < 0 && col == i && editing == null;
                if (i == 0) Frost.hero(c, r, r.height() / 2, sel, k);   // Play all: the main button's jewel
                else Frost.ghost(c, r, r.height() / 2, sel, k);
                c.drawText(b[i], r.left + 28 * k, r.centerY() + 9 * k, chipBold);
                bx -= w + 12 * k;
            }
            top += 112 * k;
        }
        if (l.isEmpty()) {
            String msg = tab == B_DOWNLOADED ? "Nothing downloaded yet. Pause a video on YouTube, then press the download button." : tab == T_FAVS ? "No favourites yet. Press → on a song and OK on the heart."
                    : tab == T_SEARCH ? (query.isEmpty() ? "Press OK on Search and type a song, film, singer or folder." : "Nothing matches “" + query + "”.")
                    : "No songs here.";
            sub.setAlpha(230);
            c.drawText(msg, getWidth() / 2f - sub.measureText(msg) / 2, top + 120 * k, sub);
            return;
        }
        float first = glide(listFrom, listTo, listStart, now), rh = ROW_H * k;
        c.save();
        c.clipRect(L - 12 * k, top - 6 * k, getWidth(), getHeight());
        MyMusic.Song cur = player.current();
        Paint song = songRow(), muted = mutedRow();
        float artistX = L + 760 * k, lenX = R - 170 * k;
        int from = Math.max(0, (int) Math.floor(first)), to = Math.min(l.size(), (int) Math.ceil(first + listRows() + 1));
        for (int i = from; i < to; i++) {
            MyMusic.Song x = l.get(i);
            float y = top + (i - first) * rh;
            RectF r = new RectF(L, y, R, y + rh - 4 * k);
            boolean focused = !inBar && editing == null && i == rowSel, playing = cur != null && cur.key().equals(x.key());
            if (focused && col == 0) Frost.plate(c, r, 14 * k, k);
            else if (focused) {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(r, 14 * k, 14 * k, fill);
            } else {
                fill.setColor(0x1FFFFFFF);
                c.drawRect(r.left, r.bottom + 1 * k, r.right, r.bottom + 2 * k, fill);
            }
            RectF cov = new RectF(r.left + 10 * k, r.top + 8 * k, r.left + 10 * k + r.height() - 16 * k, r.bottom - 8 * k);
            drawCover(c, x, cov, 8 * k);
            float base = r.centerY() + 10 * k, tx = cov.right + 20 * k;
            if (playing) {   // bars moving on its picture
                fill.setColor(0x99000000);
                c.drawRoundRect(cov, 8 * k, 8 * k, fill);
                fill.setColor(Color.WHITE);
                for (int q = 0; q < 3; q++) {
                    float bh = player.playing() ? (6 + 12 * (0.5f + 0.5f * (float) Math.sin(now / 170.0 + q * 1.7))) * k : 6 * k;
                    float bx0 = cov.centerX() + (q - 1) * 10 * k;
                    c.drawRoundRect(bx0 - 3 * k, cov.centerY() - bh, bx0 + 3 * k, cov.centerY() + bh, 2 * k, 2 * k, fill);
                }
            }
            c.drawText(ellipsize(x.title, song, artistX - tx - 24 * k), tx, base, song);
            muted.setColor(focused && col == 0 ? Color.WHITE : 0xD9FFFFFF);
            String len = MyMovies.clock(x.durationMs);
            float lenLeft = lenX - muted.measureText(len), albumEnd = lenLeft - 24 * k;
            if (hasLyrics(x)) {   // a green tick: lyrics are ready for this song
                float wx = lenLeft - 28 * k - muted.measureText("Lyrics");
                c.drawText("Lyrics", wx, base, muted);
                Frost.tick(c, wx - 22 * k, r.centerY(), 13 * k, k);
                albumEnd = wx - 48 * k;
            }
            c.drawText(ellipsize(MyMusic.mainArtist(x.artist), muted, albumEnd - artistX), artistX, base, muted);
            c.drawText(len, lenLeft, base, muted);
            boolean fav = MyMusic.favourite(getContext(), x);
            for (int b = 1; b <= 2; b++) {
                float cx = r.right - (b == 1 ? 110 : 44) * k, cy = r.centerY(), rad = 26 * k;
                if (focused && col == b) {
                    tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    Frost.plate(c, tmp, rad, k);
                }
                if (!focused && !(b == 1 && fav)) continue;
                Bitmap g = kit.glyph(b == 1 ? WheelArt.Glyph.HEART : WheelArt.Glyph.PENCIL, 28);
                c.drawBitmap(g, cx - g.getWidth() / 2f, cy - g.getHeight() / 2f, bmp);
            }
        }
        c.restore();
        drawSongDetail(c, l.get(Math.max(0, Math.min(l.size() - 1, rowSel))));
    }

    private Paint songRowP, mutedRowP;

    private Paint songRow() {
        if (songRowP == null) songRowP = new NoirKit(getContext(), k).inter(30, 500, 0, Color.WHITE);
        return songRowP;
    }

    private Paint mutedRow() {
        if (mutedRowP == null) mutedRowP = new NoirKit(getContext(), k).inter(24, 400, 0, 0xB3FFFFFF);
        return mutedRowP;
    }

    /** The details column (as in the reference picture): a big cover, the title, the singers, lyrics, film, genre, length. */
    private void drawSongDetail(Canvas c, MyMusic.Song s) {
        RectF p = new RectF((MAIN_R - DETAIL_W) * k, GRID_TOP * k, MAIN_R * k, 1040 * k);
        int col = NEON[Math.abs(s.folder.hashCode()) % NEON.length];
        fill.setShader(new LinearGradient(p.left, p.top, p.right, p.bottom, (col & 0x00FFFFFF) | 0x52000000, (col & 0x00FFFFFF) | 0x14000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(p, 28 * k, 28 * k, fill);
        fill.setShader(null);
        Frost.topEdge(c, p, 28 * k, k, 0x33FFFFFF);
        float pad = 26 * k;
        RectF cov = new RectF(p.left + pad, p.top + pad, p.right - pad, p.top + pad + (p.width() - 2 * pad));
        drawCover(c, s, cov, 18 * k);
        float y = cov.bottom + 52 * k, w = p.width() - 2 * pad;
        rowTitle.setTextSize(30 * k);
        c.drawText(ellipsize(s.title, rowTitle, w), p.left + pad, y, rowTitle);
        rowTitle.setTextSize(28 * k);
        c.drawText(ellipsize(s.artist.isEmpty() ? s.folder : s.artist, rowMeta, w), p.left + pad, y + 34 * k, rowMeta);
        y += 78 * k;
        if (hasLyrics(s)) {
            Frost.tick(c, p.left + pad + 13 * k, y - 8 * k, 13 * k, k);
            rowMeta.setColor(Frost.MUTE);
            c.drawText("Lyrics available", p.left + pad + 36 * k, y, rowMeta);
            rowMeta.setColor(0xFFD8D8D6);
            y += 44 * k;
        }
        String[][] facts = {{"Film / album", s.album}, {"Genre", s.genre}, {"Length", MyMovies.clock(s.durationMs)}, {"Folder", s.folder}};
        for (String[] f : facts) {
            if (f[1] == null || f[1].isEmpty() || y > p.bottom - 40 * k) continue;
            fill.setColor(0x14FFFFFF);
            c.drawRect(p.left + pad, y - 30 * k, p.right - pad, y - 29 * k, fill);
            help.setAlpha(170);
            c.drawText(f[0], p.left + pad, y, help);
            c.drawText(ellipsize(f[1], chipBold, w - 170 * k), p.left + pad + 170 * k, y, chipBold);
            y += 54 * k;
        }
    }

    private final java.util.Map<String, Boolean> lyricsKnown = new java.util.HashMap<>();
    private final java.util.Set<String> lyricsAsked = new java.util.HashSet<>();

    /** Whether a song already has lyrics (looked up once per song in the background; false until known). */
    private boolean hasLyrics(MyMusic.Song s) {
        Boolean b = lyricsKnown.get(s.key());
        if (b != null) return b;
        if (lyricsAsked.add(s.key())) photoNet.execute(() -> {
            boolean known = Lyrics.known(s);
            main.post(() -> {
                lyricsKnown.put(s.key(), known);
                if (known) invalidate();
            });
        });
        return false;
    }

    /** A song's cover, or a soft square with a note. */
    private void drawCover(Canvas c, MyMusic.Song s, RectF r, float rad) {
        Bitmap b = MyMusic.hasCover(s) ? MyMusic.cover(getContext(), s, this::invalidate) : null;
        if (b != null) {
            drawImage(c, b, r, rad, 255);
            return;
        }
        fill.setShader(null);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(r, rad, rad, fill);
        Bitmap note = kit.glyph(WheelArt.Glyph.NOTE, r.width() / k * 0.5f);
        c.drawBitmap(note, r.centerX() - note.getWidth() / 2f, r.centerY() - note.getHeight() / 2f, bmp);
    }

    /** Edit a song: its details to change, or the songs found online to choose from. */
    private void drawEdit(Canvas c) {
        Frost.blurBehind(c, getWidth(), getHeight(), bmp);
        float w = 1240 * k, h = 720 * k;
        RectF p = new RectF((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        kit.panel(c, p, 30 * k, 1, Theme.TINT_HIGH);
        fill.setColor(0xF20D0D0F);   // Home's dark glass
        c.drawRoundRect(p, 30 * k, 30 * k, fill);
        RectF cov = new RectF(p.left + 44 * k, p.top + 44 * k, p.left + 164 * k, p.top + 164 * k);
        drawCover(c, editing, cov, 14 * k);
        big.setAlpha(255);
        c.drawText(ellipsize(editing.title, big, p.right - cov.right - 80 * k), cov.right + 30 * k, cov.top + 50 * k, big);
        rowMeta.setAlpha(170);
        c.drawText(ellipsize("" + editing.name, rowMeta, p.right - cov.right - 80 * k), cov.right + 30 * k, cov.top + 92 * k, rowMeta);
        c.drawText(finding ? "Choose the right one: name, artist, genre and cover are all taken from it."
                : "Your changes are kept on the TV; the file stays as it is.", cov.right + 30 * k, cov.top + 126 * k, rowMeta);
        float y = cov.bottom + 30 * k;
        if (finding) {
            if (findLoading) {
                sub.setAlpha(230);
                c.drawText("Looking…", p.left + 44 * k, y + 50 * k, sub);
                return;
            }
            int n = found.size() + 1, visible = 5, firstRow = Math.max(0, Math.min(n - visible, editSel - 2));
            for (int v = 0; v < visible && firstRow + v < n; v++) {
                int i = firstRow + v;
                RectF r = new RectF(p.left + 30 * k, y + v * 98 * k, p.right - 30 * k, y + v * 98 * k + 88 * k);
                if (i == editSel) {
                    fill.setColor(0x38FFFFFF);   // chosen: a cyan wash, as everywhere
                    c.drawRoundRect(r, 16 * k, 16 * k, fill);
                }
                if (i == found.size()) {
                    chipBold.setAlpha(255);
                    c.drawText(found.isEmpty() ? "Nothing found. Search with other words…" : "Search with other words…",
                            r.left + 24 * k, r.centerY() + 9 * k, chipBold);
                    continue;
                }
                SongInfo.Found f = found.get(i);
                RectF fc = new RectF(r.left + 12 * k, r.top + 8 * k, r.left + 12 * k + 72 * k, r.top + 80 * k);
                Bitmap b = f.cover != null ? ImageCache.get(f.cover.replace("600x600bb", "200x200bb"), this::invalidate) : null;
                if (b != null) drawImage(c, b, fc, 8 * k, 255);
                else {
                    fill.setColor(0x33FFFFFF);
                    c.drawRoundRect(fc, 8 * k, 8 * k, fill);
                }
                rowTitle.setAlpha(255);
                c.drawText(ellipsize(f.title + "  ·  " + f.artist, rowTitle, r.width() - 140 * k), fc.right + 20 * k, r.top + 38 * k, rowTitle);
                rowMeta.setAlpha(190);
                c.drawText(ellipsize(f.album + "  ·  " + f.genre + "  ·  " + MyMovies.clock(f.durationMs), rowMeta, r.width() - 140 * k),
                        fc.right + 20 * k, r.top + 70 * k, rowMeta);
            }
            return;
        }
        List<String> items = editItems();
        for (int i = 0; i < items.size(); i++) {
            RectF r = new RectF(p.left + 30 * k, y + i * 80 * k, p.right - 30 * k, y + i * 80 * k + 68 * k);
            if (i == editSel) {
                fill.setColor(0x38FFFFFF);   // chosen: a cyan wash, as everywhere
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            }
            Paint tp = i == editSel ? chipBold : chip;
            tp.setAlpha(255);
            c.drawText(ellipsize(items.get(i), tp, r.width() - 48 * k), r.left + 28 * k, r.centerY() + 9 * k, tp);
        }
    }

    private void drawToast(Canvas c, long now) {
        if (toast == null || now - toastAt > 2200) return;
        float a = Math.min(1, (2200 - (now - toastAt)) / 400f);
        float w = chipBold.measureText(toast) + 70 * k, h = 70 * k;
        RectF r = new RectF((getWidth() - w) / 2, 60 * k, (getWidth() + w) / 2, 60 * k + h);
        kit.pill(c, r, h / 2, a);
        fill.setColor(Color.argb(Math.round(235 * a), 14, 14, 16));
        c.drawRoundRect(r, h / 2, h / 2, fill);
        chipBold.setAlpha(Math.round(255 * a));
        c.drawText(toast, r.left + 35 * k, r.centerY() + 9 * k, chipBold);
        chipBold.setAlpha(255);
    }

    // ---- the player ---------------------------------------------------------------------------------

    private Bitmap tintFrom;
    private String tintKey;
    private int tintColour = Theme.VIOLET_A;
    private float tintHue = 265;

    /**
     * The player: the background (videos, or drawn scenes), the effect, the lyrics in the middle, and on glass the song
     * (top left, always readable: title, film or album with the singers, progress, what is next) and the clock.
     */
    private void drawPlayer(Canvas c, long now) {
        if (gTitle == null) neonPaints();   // before anything on this screen uses them
        MyMusic.Song s = player.current();
        if (s == null) return;
        if (screenOff) {   // nearly black: a tiny note that moves now and then, so nothing stays on the screen
            c.drawColor(Color.BLACK);
            long slot = now / 30_000;
            Random r = new Random(slot);
            String t = "♪  " + s.title + "   ·   press any button";
            sub.setAlpha(55);
            c.drawText(t, PAD * k + r.nextFloat() * (getWidth() - 2 * PAD * k - sub.measureText(t)), 120 * k + r.nextFloat() * (getHeight() - 240 * k), sub);
            return;
        }
        Bitmap cov = MyMusic.hasCover(s) ? MyMusic.cover(getContext(), s, this::invalidate) : null;
        if (cov != tintFrom || tintKey == null || !tintKey.equals(s.key())) {   // worked out once per cover
            tintFrom = cov;
            tintKey = s.key();
            tintColour = MyMusic.colour(cov);
            float[] hsv = new float[3];
            Color.colorToHSV(tintColour, hsv);
            tintHue = hsv[0];
        }
        int tint = tintColour;
        boolean videos = listener != null && listener.hasVideos();
        boolean neonLook = GlassKit.neon(getContext());
        int fx = Lite.on ? -1 : effectNow();   // Lite: no effects
        if (fx >= 0) {
            effects.draw(c, fx, getWidth(), getHeight(), k, now, bands, energy, beat);
            // Side effects leave the middle clear; the others are dimmed so the words stay readable.
            if (fx >= MusicEffects.SIDE && lyricsOn && !lyrics.isEmpty()) c.drawColor(0x80000000);
        } else if (!videos) calmPage(c, now, cov != null ? tint : NEON[Math.abs(s.folder.hashCode()) % NEON.length]);
        else if (lyricsOn && !lyrics.isEmpty()) c.drawColor(0x59000000);
        if (vis != VIS_OFF && !Lite.on && fx < 0 && !videos) drawGlowLine(c);
        if (lyricsOn) drawLyrics(c, s, tint, now);
        drawPlayerUi(c, s, now);
        if (moreOpen) drawMoreNeon(c);
        if (lyricChoices != null) drawLyricChoices(c);
    }

    /** 1 while keys are being pressed, fading to 0 from ten seconds after the last one (the controls keep it at 1). */
    private float uiAlpha(long now) {
        if (controls || moreOpen || !player.playing()) return 1;
        long idle = now - lastKey;
        if (idle < IDLE_MS) return 1;
        return Math.max(0, 1 - (idle - IDLE_MS) / 1500f);
    }

    /**
     * Karaoke lyrics: the line before (faint), the line being sung (big, filling with colour from left to right as
     * it is sung), and the two after. Lyrics with only rough timing (no timed version found) scroll without the fill.
     */
    private void drawLyrics(Canvas c, MyMusic.Song s, int tint, long now) {
        boolean small = miniPlayer();
        float stageY = small ? 460 : 470;
        if (lyrics.isEmpty()) {
            if (!small) {   // no words beside the record: the song's name takes their place
                TextPaint h = new TextPaint(gTitle);
                float size = 72 * k;
                h.setTextSize(size);
                while (size > 40 * k && h.measureText(s.title) > 1020 * k) h.setTextSize(size -= 4 * k);
                c.drawText(ellipsize(s.title, h, 1020 * k), 820 * k, 450 * k, h);
                sub.setAlpha(210);
                c.drawText(lyricsLoading ? "Finding the lyrics…" : "No lyrics yet", 820 * k, 504 * k, sub);
            }
            return;
        }
        long pos = player.position() - MyMusic.lyricsOffset(getContext(), s) + 250;   // a quarter second early reads better
        int i = Lyrics.lineAt(lyrics, pos);
        lyricCentre = small;
        int fxNow = effectNow();
        float cxm = small ? getWidth() / 2f : 820 * k, maxW = fxNow == 2 || fxNow == 3 ? 930 * k : small ? 1500 * k : 1020 * k, y = stageY * k;
        if (i >= 1) drawLyric(c, lyrics.get(i - 1).text, lyricFar, 130, cxm, y - 104 * k, maxW, 0, 0);
        if (i >= 0) {
            Lyrics.Line ln = lyrics.get(i);
            long nextAt = i + 1 < lyrics.size() ? lyrics.get(i + 1).at : ln.at + 6000;
            long sungFor = Math.min(nextAt - ln.at, Math.max(1500, ln.text.length() * 190L));
            float fillT = Math.max(0, Math.min(1, (pos - ln.at) / (float) sungFor));   // rough timing fills too, as a good guess
            String t = ln.text.isEmpty() ? "♪   ♪   ♪" : ln.text;
            drawLyric(c, t, lyricNow, 255, cxm, y, maxW, fillT, tint);
        } else {
            drawLyric(c, "♪   ♪   ♪", lyricNow, 150, cxm, y, maxW, 0, 0);
        }
        for (int j = 1; j <= 1 && i + j < lyrics.size(); j++) {
            String t = lyrics.get(i + j).text;
            drawLyric(c, t.isEmpty() ? "♪" : t, j == 1 ? lyricNear : lyricFar, 200, cxm, y + 100 * k, maxW, 0, 0);
        }
    }

    /** One lyric line, centred, made smaller to fit; with a colour fill sweeping across it (karaoke) when fillT > 0. */
    private void drawLyric(Canvas c, String text, Paint p, int alpha, float cx, float y, float maxW, float fillT, int tint) {
        float size = p.getTextSize(), w = p.measureText(text);
        if (w > maxW) {
            p.setTextSize(Math.max(size * 0.6f, size * maxW / w));
            w = Math.min(maxW, p.measureText(text));
        }
        String t = ellipsize(text, p, maxW);
        float x = lyricCentre ? cx - w / 2 : cx;   // centred, or starting beside the record
        p.setShadowLayer(10 * k, 0, 3 * k, 0xE6000000);
        p.setColor(Color.WHITE);
        p.setAlpha(fillT > 0 ? 200 : alpha);
        c.drawText(t, x, y, p);
        if (fillT > 0) {
            c.save();
            c.clipRect(x - 4 * k, y - p.getTextSize() * 1.2f, x + w * fillT, y + p.getTextSize() * 0.5f);
            p.setColor(0xFFFFD24A);   // always the same warm gold: it must show on any video
            p.setAlpha(255);
            c.drawText(t, x, y, p);
            c.restore();
        }
        p.setColor(Color.WHITE);
        p.setTextSize(size);
    }

    /** A soft light from the edges, stronger on the beat, in the cover's colour. */
    /** The words that choose a song's videos: its folder, then its genre and title. */
    private String sceneWords(@Nullable String folder) {
        MyMusic.Song cur = player.current();
        return (folder == null ? "" : folder) + (cur == null ? "" : " " + cur.genre + " " + cur.title);
    }

    private final Path linePath = new Path();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The one effect: a thin line of light along the foot of the screen, moved by the music. The video stays clear. */
    private void drawGlowLine(Canvas c) {
        float w = getWidth(), base = (uiAlpha(SystemClock.uptimeMillis()) > 0.01f ? DOCK_T - 8 : 1060) * k, step = w / (bands.length * 2 - 1);
        linePath.reset();
        linePath.moveTo(0, base);
        for (int i = 1; i < bands.length * 2; i++) {
            int b = i < bands.length ? bands.length - 1 - i : i - bands.length;   // the bass in the middle
            float x = i * step, y = base - Math.min(1, bands[b]) * 64 * k;
            float px = (i - 1) * step;
            int pb = i - 1 < bands.length ? bands.length - 1 - (i - 1) : i - 1 - bands.length;
            float py = base - Math.min(1, bands[pb]) * 64 * k;
            linePath.quadTo(px, py, (px + x) / 2, (py + y) / 2);
        }
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0x33FFFFFF);
        linePaint.setStrokeWidth(8 * k);
        c.drawPath(linePath, linePaint);
        linePaint.setColor(0xE6FFFFFF);
        linePaint.setStrokeWidth(Math.max(1.5f, 2.4f * k));
        c.drawPath(linePath, linePaint);
    }

    /** Rainbow bars along the bottom, mirrored from the middle, each with a small falling cap. */
    private final Path[] flow = {new Path(), new Path()};

    private boolean controlOn(int i) {
        switch (i) {
            case C_SHUFFLE: return player.shuffle();
            case C_REPEAT: return player.repeat() != MusicPlayer.REPEAT_OFF;
            case C_LYRICS: return lyricsOn;
            case C_SLEEP: return player.sleepLeft() != 0;
            default: return false;
        }
    }

    // ---- the player, in the neon glass look --------------------------------------------------------------
    //
    // The cover on the left; beside it the title, the singers and film, what's next, a glowing progress line and
    // round glass buttons (🔀 ⏮ ⏯ ⏭ 🔁 ❤ ⋯); the lyrics under them; the time top right. ⋯ More opens a frosted
    // menu on the right. Arrows move along the buttons in the order they are drawn (CTRL_ORDER).

    private static final int[] CTRL_ORDER = {C_SHUFFLE, C_PREV, C_PLAY, C_NEXT, C_REPEAT, C_FAV, C_MORE};
    private static final String[] CTRL_NAMES = {"Shuffle", "Previous", "Play or pause", "Next", "Repeat", "Favourite", "Options"};
    private Paint gTitle, gLine, gSmall, gMenu, gMenuOn, gTime;
    private final Paint gFill = new Paint(Paint.ANTI_ALIAS_FLAG), gEdge = new Paint(Paint.ANTI_ALIAS_FLAG), gIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path gPath = new Path();

    private void neonPaints() {
        NoirKit nk = new NoirKit(getContext(), k);
        gTitle = nk.inter(64, 800, -0.015f, 0xFFFFFFFF);
        gLine = nk.inter(29, 500, 0, 0xFFE2E6F6);
        gSmall = nk.inter(21, 500, 0, 0xFFB4BCD8);
        gMenu = nk.inter(23, 400, 0, 0xFFB5B9C6);
        gMenuOn = nk.inter(23, 600, 0, 0xFFFFFFFF);
        gTime = nk.inter(34, 700, 0, 0xFFFFFFFF);
        for (Paint p : new Paint[]{gTitle, gLine, gTime}) p.setShadowLayer(8 * k, 0, 1 * k, 0x80000000);
        gEdge.setStyle(Paint.Style.STROKE);
        gIcon.setColor(Color.WHITE);
        // Poppins for the words: rounder and warmer than the rest of the screen, with matching Hindi and English letters.
        android.graphics.Typeface sung = gTitle.getTypeface(), plain = gLine.getTypeface();
        try {
            sung = android.graphics.Typeface.createFromAsset(getContext().getAssets(), "fonts/Poppins-SemiBold.ttf");
            plain = android.graphics.Typeface.createFromAsset(getContext().getAssets(), "fonts/Poppins-Medium.ttf");
        } catch (RuntimeException e) {
            android.util.Log.w("TVTown", "lyrics font", e);
        }
        lyricNow.setTypeface(sung);
        lyricNear.setTypeface(plain);
        lyricFar.setTypeface(plain);
        lyricNow.setTextSize(68 * k);
        lyricNear.setTextSize(40 * k);
        lyricFar.setTextSize(36 * k);
    }

    /** Video or effect behind: the record lives in the dock. Neither: it takes the stage. */
    private boolean miniPlayer() {
        return effectNow() >= 0 || listener != null && listener.hasVideos();
    }

    private boolean lyricCentre;
    private static final float DOCK_T = 904, DOCK_B = 1040;

    /** The song's picture, as a turning record or a plain cover, as chosen. */
    private void drawDisc(Canvas c, MyMusic.Song s, float cx, float cy, float rad) {
        if (pref("music_style") == 1) {
            drawCover(c, s, new RectF(cx - rad, cy - rad, cx + rad, cy + rad), rad * 0.16f);
            return;
        }
        vinyl(c, cx, cy, rad, pref("music_nospin") == 1 ? 0 : spin % 360, MyMusic.hasCover(s) ? MyMusic.cover(getContext(), s, this::invalidate) : null, NEON[Math.abs(s.folder.hashCode()) % NEON.length]);
    }

    private void drawPlayerUi(Canvas c, MyMusic.Song s, long now) {
        if (gTitle == null) neonPaints();
        long frame = SystemClock.uptimeMillis();
        if (player.playing() && spinAt > 0) spin += (frame - spinAt) * 360f / 7000f;
        spinAt = frame;
        float ui = moreOpen ? 1 : uiAlpha(now);
        long pos = player.position(), dur = Math.max(1, player.duration());
        float f = Math.min(1, pos / (float) dur);
        boolean mini = miniPlayer();
        if (!mini) {   // the record on the stage, left of the words
            float rcx = 420 * k, rcy = 470 * k, rrad = 350 * k;
            drawDisc(c, s, rcx, rcy, rrad);
            if (pref("music_style") == 0 && pref("music_noarm") == 0) {
                gEdge.setStrokeCap(Paint.Cap.ROUND);
                gEdge.setStrokeWidth(9 * k);
                gEdge.setColor(0xFFD9D9DE);
                c.save();
                c.rotate(player.playing() ? 0 : -9, 772 * k, 110 * k);
                c.drawLine(772 * k, 110 * k, 690 * k, 450 * k, gEdge);
                gFill.setColor(0xFFD9D9DE);
                c.drawRoundRect(670 * k, 442 * k, 712 * k, 506 * k, 8 * k, 8 * k, gFill);
                c.restore();
                gFill.setColor(0xFFEDEDF0);
                c.drawCircle(772 * k, 110 * k, 22 * k, gFill);
            }
        }
        if (ui <= 0.01f) {   // at rest: only a hairline of progress along the foot
            gFill.setColor(0x4DFFFFFF);
            c.drawRect(0, getHeight() - 4 * k, getWidth(), getHeight(), gFill);
            gFill.setColor(Color.WHITE);
            c.drawRect(0, getHeight() - 4 * k, getWidth() * f, getHeight(), gFill);
            return;
        }
        int layer = ui < 1 ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * ui)) : -1;
        String clock = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date()).toLowerCase(Locale.ROOT);
        if (!moreOpen) c.drawText(clock, 1840 * k - gTime.measureText(clock), 84 * k, gTime);
        int z = player.sleepLeft();
        if (z != 0 && !moreOpen) {
            String zz = z < 0 ? "Sleep after this song" : "Sleep in " + z + " min";
            c.drawText(zz, 1840 * k - gSmall.measureText(zz), 122 * k, gSmall);
        }
        // The dock: glass, the same in every mode.
        RectF dock = new RectF(80 * k, DOCK_T * k, 1840 * k, DOCK_B * k);
        gFill.setColor(0xB80C0C10);
        c.drawRoundRect(dock, 40 * k, 40 * k, gFill);
        Frost.ghost(c, dock, 40 * k, false, k);
        // Progress along its top.
        float px = dock.left + 40 * k, pw = dock.width() - 80 * k, py = dock.top + 18 * k;
        gFill.setColor(0x3DFFFFFF);
        c.drawRoundRect(px, py - 2.5f * k, px + pw, py + 2.5f * k, 3 * k, 3 * k, gFill);
        gFill.setColor(Color.WHITE);
        c.drawRoundRect(px, py - 2.5f * k, px + pw * f, py + 2.5f * k, 3 * k, 3 * k, gFill);
        c.drawCircle(px + pw * f, py, 7 * k, gFill);
        float mid = dock.top + 76 * k;
        // Left: the record and the song.
        drawDisc(c, s, dock.left + 80 * k, mid, 46 * k);
        float tx = dock.left + 146 * k, tw = 440 * k;
        gTitle.setTextSize(30 * k);
        String name = s.title + (MyMusic.favourite(getContext(), s) ? "  ♥" : "");
        c.drawText(ellipsize(name, gTitle, tw), tx, mid - 4 * k, gTitle);
        gLine.setTextSize(24 * k);
        String who = MyMusic.mainArtist(s.artist);
        c.drawText(ellipsize(who.isEmpty() ? s.folder : who, gLine, tw), tx, mid + 30 * k, gLine);
        // Right: what's next and the time.
        float right = dock.right - 40 * k;
        String time = MyMovies.clock(pos) + " / " + MyMovies.clock(dur);
        c.drawText(time, right - gSmall.measureText(time), mid + 30 * k, gSmall);
        List<MyMusic.Song> next = player.upNext(1);
        if (!next.isEmpty()) {
            String nx = ellipsize("Next: " + next.get(0).title, gSmall, 420 * k);
            c.drawText(nx, right - gSmall.measureText(nx), mid - 4 * k, gSmall);
        }
        // Middle: the buttons.
        float small = 60 * k, big = 84 * k, gap = 14 * k, rowW = (CTRL_ORDER.length - 1) * small + big + (CTRL_ORDER.length - 1) * gap;
        float x = 960 * k - rowW / 2;
        for (int id : CTRL_ORDER) {
            float d2 = id == C_PLAY ? big : small;
            boolean focused = controls && !moreOpen && ctrlSel == id;
            RectF r = new RectF(x, mid - d2 / 2, x + d2, mid + d2 / 2);
            if (focused) Frost.plate(c, r, d2 / 2, k);
            boolean on = controlOn(id) || id == C_FAV && MyMusic.favourite(getContext(), s);
            gIcon.setColor(on && !focused ? 0xFF5EE69A : Color.WHITE);
            controlIcon(c, id, r.centerX(), r.centerY(), id == C_PLAY ? 40 * k : 28 * k);
            x += d2 + gap;
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** A button's icon, drawn (not a font character), in gIcon's colour. */
    private void controlIcon(Canvas c, int id, float cx, float cy, float s) {
        gIcon.setStyle(Paint.Style.FILL);
        gPath.reset();
        switch (id) {
            case C_PLAY:
                if (player.playing()) {
                    c.drawRoundRect(cx - s * 0.36f, cy - s * 0.42f, cx - s * 0.1f, cy + s * 0.42f, s * 0.06f, s * 0.06f, gIcon);
                    c.drawRoundRect(cx + s * 0.1f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.42f, s * 0.06f, s * 0.06f, gIcon);
                } else {
                    gPath.moveTo(cx - s * 0.3f, cy - s * 0.45f);
                    gPath.lineTo(cx + s * 0.45f, cy);
                    gPath.lineTo(cx - s * 0.3f, cy + s * 0.45f);
                    gPath.close();
                    c.drawPath(gPath, gIcon);
                }
                return;
            case C_PREV: case C_NEXT: {
                float dir = id == C_NEXT ? 1 : -1;
                gPath.moveTo(cx - dir * s * 0.35f, cy - s * 0.4f);
                gPath.lineTo(cx + dir * s * 0.25f, cy);
                gPath.lineTo(cx - dir * s * 0.35f, cy + s * 0.4f);
                gPath.close();
                c.drawPath(gPath, gIcon);
                c.drawRect(cx + dir * s * 0.28f - s * 0.06f, cy - s * 0.4f, cx + dir * s * 0.28f + s * 0.06f, cy + s * 0.4f, gIcon);
                return;
            }
            case C_MORE:
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.36f, cy, s * 0.1f, gIcon);
                return;
            case C_VIS: {   // the effect: three bars of a little equaliser
                for (int i = -1; i <= 1; i++) {
                    float h = s * (i == 0 ? 0.42f : 0.26f);
                    c.drawRoundRect(cx + i * s * 0.3f - s * 0.08f, cy - h, cx + i * s * 0.3f + s * 0.08f, cy + h, s * 0.06f, s * 0.06f, gIcon);
                }
                return;
            }
            case C_FAV:
                gPath.moveTo(cx, cy + s * 0.42f);
                gPath.cubicTo(cx - s * 0.75f, cy - s * 0.05f, cx - s * 0.35f, cy - s * 0.65f, cx, cy - s * 0.22f);
                gPath.cubicTo(cx + s * 0.35f, cy - s * 0.65f, cx + s * 0.75f, cy - s * 0.05f, cx, cy + s * 0.42f);
                gIcon.setStyle(MyMusic.favourite(getContext(), player.current()) ? Paint.Style.FILL : Paint.Style.STROKE);
                gIcon.setStrokeWidth(2.4f * k);
                c.drawPath(gPath, gIcon);
                return;
            case C_SHUFFLE: {
                gIcon.setStyle(Paint.Style.STROKE);
                gIcon.setStrokeWidth(2.4f * k);
                gIcon.setStrokeCap(Paint.Cap.ROUND);
                float a = s * 0.42f;
                c.drawLine(cx - a, cy + a * 0.6f, cx + a, cy - a * 0.6f, gIcon);
                c.drawLine(cx - a, cy - a * 0.6f, cx - a * 0.15f, cy - a * 0.1f, gIcon);
                c.drawLine(cx + a * 0.15f, cy + a * 0.1f, cx + a, cy + a * 0.6f, gIcon);
                c.drawLine(cx + a, cy - a * 0.6f, cx + a * 0.55f, cy - a * 0.6f, gIcon);
                c.drawLine(cx + a, cy - a * 0.6f, cx + a, cy - a * 0.15f, gIcon);
                c.drawLine(cx + a, cy + a * 0.6f, cx + a * 0.55f, cy + a * 0.6f, gIcon);
                c.drawLine(cx + a, cy + a * 0.6f, cx + a, cy + a * 0.15f, gIcon);
                return;
            }
            case C_REPEAT: {
                gIcon.setStyle(Paint.Style.STROKE);
                gIcon.setStrokeWidth(2.4f * k);
                gIcon.setStrokeCap(Paint.Cap.ROUND);
                float a = s * 0.42f;
                c.drawArc(cx - a, cy - a * 0.75f, cx + a, cy + a * 0.75f, 200, 290, false, gIcon);
                float ax = cx - a * 0.94f, ay = cy - a * 0.26f;
                c.drawLine(ax, ay, ax - a * 0.25f, ay - a * 0.3f, gIcon);
                c.drawLine(ax, ay, ax + a * 0.3f, ay - a * 0.2f, gIcon);
                if (player.repeat() == MusicPlayer.REPEAT_ONE) {
                    gIcon.setStyle(Paint.Style.FILL);
                    gIcon.setTextSize(s * 0.5f);
                    c.drawText("1", cx - gIcon.measureText("1") / 2, cy + s * 0.18f, gIcon);
                }
                return;
            }
            default:
        }
    }

    /** ⋯ More: a frosted menu on the right (as in the picture: grey rows, the chosen one lifted frosted glass, hairlines). */
    private void drawMoreNeon(Canvas c) {
        if (gTitle == null) neonPaints();
        float rowH = 72 * k;
        RectF p = new RectF(1240 * k, 60 * k, 1840 * k, 884 * k);   // beside the picture: every change shows live
        gFill.setColor(0xE60C0C10);
        c.drawRoundRect(p, 36 * k, 36 * k, gFill);
        Frost.ghost(c, p, 36 * k, false, k);
        Paint tab = new Paint(gMenuOn);
        float x = p.left + 18 * k, ty = p.top + 18 * k;
        for (int i = 0; i < TABS.length; i++) {
            float w = tab.measureText(TABS[i]) + 44 * k;
            RectF r = new RectF(x, ty, x + w, ty + 64 * k);
            if (moreSel < 0 && moreTab == i) Frost.plate(c, r, 32 * k, k);
            else if (moreTab == i) {
                gFill.setColor(0x2EFFFFFF);
                c.drawRoundRect(r, 32 * k, 32 * k, gFill);
            }
            tab.setColor(moreTab == i ? Color.WHITE : 0x99FFFFFF);
            c.drawText(TABS[i], r.left + 22 * k, r.centerY() + 9 * k, tab);
            x += w + 6 * k;
        }
        String[] rows = panelRows();
        float y = ty + 84 * k;
        for (int i = 0; i < rows.length; i++) {
            RectF r = new RectF(p.left + 14 * k, y, p.right - 14 * k, y + rowH - 6 * k);
            boolean on = moreSel == i;
            if (on) Frost.plate(c, r, 20 * k, k);
            Paint lp = on ? gMenuOn : gMenu;
            c.drawText(rowLabel(rows[i]), r.left + 24 * k, r.centerY() + 9 * k, lp);
            String val = rowValue(rows[i]);
            float right = r.right - 24 * k;
            if ("On".equals(val) || "Off".equals(val)) {   // a switch
                boolean yes = "On".equals(val);
                RectF sw = new RectF(right - 68 * k, r.centerY() - 19 * k, right, r.centerY() + 19 * k);
                gFill.setColor(yes ? 0xFF2BC56F : 0x4DFFFFFF);
                c.drawRoundRect(sw, 19 * k, 19 * k, gFill);
                gFill.setColor(Color.WHITE);
                c.drawCircle(yes ? sw.right - 19 * k : sw.left + 19 * k, sw.centerY(), 15 * k, gFill);
            } else if (val != null) {
                boolean steps = java.util.Arrays.asList("effect", "change", "style", "timing").contains(rows[i]);
                String shown = steps && on ? "‹  " + val + "  ›" : val;
                gMenu.setColor(on ? Color.WHITE : 0xB3FFFFFF);
                c.drawText(shown, right - gMenu.measureText(shown), r.centerY() + 9 * k, gMenu);
                gMenu.setColor(0xFFE6E6E6);
            }
            y += rowH;
        }
    }

    // ---- drawn scenes (when the drive has no background videos) --------------------------------------

    /** Another of the drawn scenes, faded in over the old one. */
    private void nextScene() {
        if (listener != null && listener.hasVideos()) return;
        int i;
        do i = new Random().nextInt(Scenes.COUNT); while (i == sceneIndex && Scenes.COUNT > 1);
        sceneIndex = i;
        final int pick = i;
        net.execute(() -> {
            Bitmap b = Scenes.render(pick);
            main.post(() -> {
                if (pick != sceneIndex) return;
                oldScene = scene;
                scene = b;
                sceneAt = SystemClock.uptimeMillis();
                invalidate();
            });
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------

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
        return TextUtils.ellipsize(s, new TextPaint(p), Math.max(1, w), TextUtils.TruncateAt.END).toString();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static int darker(int color) {
        return Color.rgb(Color.red(color) * 2 / 5, Color.green(color) * 2 / 5, Color.blue(color) * 2 / 5);
    }

}
