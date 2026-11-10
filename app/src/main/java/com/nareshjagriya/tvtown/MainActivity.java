package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Home (HomeView). This activity launches what Home asks for: apps, titles on their service (deep link,
 * else the app, else the Play Store), trailers in the YouTube app, and the Random picks.
 */
public class MainActivity extends Activity implements HomeView.Listener {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    /** Set when the home helper opened TV Town (so it is not taken as the viewer choosing TV Town again). */
    static final String EXTRA_FROM_HELPER = "from_helper";
    /** From the helper: Home was pressed (rather than Back out of an app). */
    static final String EXTRA_HOME_KEY = "home_key";
    /** From the helper: the mic button was pressed on TV Town. */
    static final String EXTRA_VOICE = "voice";
    private static final int ASK_VOICE = 7;
    /** From the helper: a coloured button, the Random pop-up to open (0 to 3). */
    static final String EXTRA_RANDOM = "random";
    /** From the phone remote: a place to open. */
    static final String EXTRA_PHONE = "phone", EXTRA_PHONE_WORDS = "phone_words";
    /** Screenshots (scripts/screenshots.sh): the screen to open, e.g. "surprise-me". */
    private boolean newIntent;   // onNewIntent came just before this onResume
    private static final long MINUTE_MS = 60_000L;
    private static final int ICON_DP = 84;

    private HomeView wheel;
    private float density;
    private final Random random = new Random();

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** At boot Wi-Fi often connects after Home is shown: refresh the Wi-Fi symbol then. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifi);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifi);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifi();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The drive's songs read a little after start-up (not during it), so My Music opens at once.
        handler.postDelayed(() -> MyMusic.warmUp(this), 20_000);
        density = getResources().getDisplayMetrics().density;
        wheel = findViewById(R.id.home);
        wheel.setListener(this);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        chosenFromAppList(getIntent());
        // A pen drive plugged in or taken out: the My Movies tile, and a note once the TV has read the drive.
        IntentFilter drives = new IntentFilter();
        drives.addAction(Intent.ACTION_MEDIA_MOUNTED);
        drives.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        drives.addAction(Intent.ACTION_MEDIA_REMOVED);
        drives.addAction(Intent.ACTION_MEDIA_EJECT);
        drives.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        drives.addDataScheme("file");
        Perms.listen(this, driveReceiver, drives);
        // Bing photos from the previous home screen are no longer used.
        new Thread(() -> deleteTree(new File(getFilesDir(), "walls")), "cleanup").start();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back out of an app straight into TV Town (it is the only home): the results it was played from, if any.
        if (!newIntent) wheel.returnToResults();
        newIntent = false;
        wheel.setApps(Apps.load(this, Math.round(ICON_DP * density)));
        wheel.requestFocus();
        wheel.setRunning(true);
        updateWifi();
        wheel.setDrive(MyMovies.driveMounted(this));
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (getSharedPreferences("home", MODE_PRIVATE).getBoolean("welcomed", false)) {
            Perms.askOnce(this);
            Perms.askTvListings(this, false);   // without it Continue Watching can't see your apps
        }
        handler.postDelayed(() -> {
            if (!isFinishing() && hasWindowFocus()) Treat.maybeNudge(this);
        }, 5000);
        Updater.check(this, false, r -> {   // once a day; a new version is mentioned once
            if (r == null) return;
            android.content.SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
            if (r.version.equals(p.getString("update_announced", ""))) return;
            p.edit().putString("update_announced", r.version).apply();
            handler.post(() -> Toast.makeText(this, getString(R.string.update_announce, r.version), Toast.LENGTH_LONG).show());
        });
    }

    private final BroadcastReceiver driveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean mounted = MyMovies.driveMounted(MainActivity.this);
            wheel.setDrive(mounted);
            if (!mounted) MusicPlayer.get(MainActivity.this).driveRemoved();
            if (!mounted || !Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(intent.getAction())) return;
            MyMusic.warmUp(MainActivity.this);   // the drive's songs, ready before My Music is opened
            if (!Perms.media(MainActivity.this)) {
                Toast.makeText(MainActivity.this, R.string.drive_found_plain, Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                int n = MyMovies.scan(MainActivity.this).size();
                handler.post(() -> {
                    if (n > 0) Toast.makeText(MainActivity.this, getString(R.string.drive_found, n), Toast.LENGTH_LONG).show();
                });
            }, "drive").start();
        }
    };

    @Override
    public void onMyMovies() {
        open(new Intent(this, MyMoviesActivity.class), null);
    }

    @Override
    public void onResumeFilm(MyMovies.Video video, long fromMs, String title) {
        open(new Intent(this, MyMoviesActivity.class).putExtra(MyMoviesActivity.EXTRA_RESUME, video.key())
                .putExtra(MyMoviesActivity.EXTRA_FROM, fromMs).putExtra(MyMoviesActivity.EXTRA_TITLE, title), null);
    }

    @Override
    public void onMyMusic() {
        open(new Intent(this, MyMusicActivity.class), null);
    }

    @Override
    public void onMusicPlayer() {
        open(new Intent(this, MyMusicActivity.class).putExtra(MyMusicActivity.EXTRA_PLAYER, true), null);
    }

    @Override
    public void onMustWatch() {
        open(new Intent(this, MustWatchActivity.class), null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        wheel.setRunning(false);   // nothing moves while an app is open
    }

    @Override
    protected void onStop() {
        super.onStop();
        wheel.releaseImages();     // another app is in front: give its memory back
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) wheel.releaseImages();
        else if (level >= TRIM_MEMORY_RUNNING_LOW) ImageCache.trim();   // short of memory while TV Town is in front
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        newIntent = true;
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            onVoice();
            return;
        }
        String phone = intent.getStringExtra(EXTRA_PHONE);
        if (phone != null) {
            String words = intent.getStringExtra(EXTRA_PHONE_WORDS);
            if (phone.equals("search") && words != null && !words.trim().isEmpty()) {
                wheel.reset();
                TvKeyboard.page(this, words.trim(), wheel::requestFocus);
            } else wheel.fromPhone(phone);
            return;
        }
        int random = intent.getIntExtra(EXTRA_RANDOM, -1);
        boolean helper = intent.getBooleanExtra(EXTRA_FROM_HELPER, false);
        if (random >= 0) wheel.colourButton(random);                                     // a coloured button
        else if (helper && !intent.getBooleanExtra(EXTRA_HOME_KEY, false)) wheel.returnToResults();   // Back out of an app
        else {   // Home: close whatever is open, Search too
            TvKeyboard.closeIfOpen();
            wheel.reset();
        }
        chosenFromAppList(intent);
    }

    /** Opened from the TV's app list (e.g. the TV Town tile on Google TV): TV Town becomes the default home again. */
    private void chosenFromAppList(Intent intent) {
        if (intent == null || intent.getBooleanExtra(EXTRA_FROM_HELPER, false) || intent.getCategories() == null) return;
        boolean fromList = intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER);
        if (!fromList || HomeKeyService.tvTownIsHome(this)) return;
        HomeKeyService.setMode(this, HomeKeyService.TV_TOWN);
        Toast.makeText(this, HomeKeyService.enabled(this) ? R.string.tvtown_is_home_again : R.string.tvtown_home_needs_helper,
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(driveReceiver);
    }

    @Override
    public void onBackPressed() {
        wheel.back();   // Home screen: Back only closes things, never leaves
    }

    // ---- home ------------------------------------------------------------------------------------

    @Override
    public void onDiscover() {
        open(new Intent(this, DiscoverActivity.class), null);
    }

    /** Google's voice input (no microphone permission needed here); the words come back to onActivityResult. */
    @Override
    public void onVoice() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Lang.speech())
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, ASK_VOICE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ASK_VOICE || resultCode != RESULT_OK || data == null) return;
        List<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty() || said.get(0).trim().isEmpty()) return;
        List<Apps.App> apps = Apps.load(this, Math.round(ICON_DP * density));
        List<String> labels = new ArrayList<>();
        for (Apps.App a : apps) labels.add(a.label);
        Voice.Command c = Voice.parse(said.get(0), labels);
        Toast.makeText(this, "🎙  " + said.get(0), Toast.LENGTH_SHORT).show();
        switch (c.kind) {
            case Voice.APP:
                for (Apps.App a : apps) if (a.label.equals(c.text)) onOpenApp(a, null);
                break;
            case Voice.RANDOM:
                wheel.openRandom(Integer.parseInt(c.text));
                break;
            case Voice.YOUTUBE:
                openYouTube("https://www.youtube.com/results?search_query=" + Uri.encode(c.text));
                break;
            default:
                TvKeyboard.page(this, c.text, wheel::requestFocus);
        }
    }

    @Override
    public void onOpenApp(Apps.App app, Rect from) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!open(i, from)) Toast.makeText(this, getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAppInfo(Apps.App app) {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)), null);
    }

    @Override
    public void onInput(Inputs.Source source) {
        open(new Intent(source.intent), null);
    }

    @Override
    public void onMessage(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilitySettings() {
        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null);
    }

    /** Opens the TV's own home screen (Google TV), never its recovery or fallback screens; false if it is turned off. */
    @Override
    public boolean onOpenStockHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            if (HomeKeyService.isSafetyNet(ri.activityInfo.packageName, ri.activityInfo.name)) continue;
            Intent i = new Intent(home).setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            if (open(i, null)) return true;
        }
        return false;
    }

    /** A title's trailer in the YouTube app: the first one that still plays, else a YouTube search for it. */
    @Override
    public void onTrailer(Tmdb.Title t) {
        new Thread(() -> {
            Intent i = DeepLinks.trailer(this, t);
            handler.post(() -> open(i, null));
        }, "trailer").start();
    }

    /**
     * A title on its service: on the service it was found on, else the first of your apps that has it (TMDB's
     * where-to-watch for India), else the first service that has it. The deep link comes from Wikidata.
     */
    @Override
    public void onPlay(Tmdb.Title t) {
        Toast.makeText(this, getString(R.string.opening, t.title), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String in = OpenIn.chosen(this);
            if (!OpenIn.APPS.equals(in)) {   // Customize says Stremio or Nuvio: straight to the title's page there
                Intent i = OpenIn.intent(this, t, in);
                if (i != null) {
                    handler.post(() -> open(i, null));
                    return;
                }
            }
            Services.Service s = t.service;
            if (s == null) {
                try {
                    List<Services.Service> where = Catalog.whereToWatch(this, t);
                    for (Services.Service w : where) if (Services.installed(this, w.pkg)) { s = w; break; }
                    if (s == null && !where.isEmpty()) s = where.get(0);
                } catch (Exception ignored) {
                    // offline: nothing to go on
                }
            }
            final Services.Service service = s;
            DeepLinks.Plan plan = service != null ? DeepLinks.plan(this, t, service) : null;
            handler.post(() -> {
                if (plan == null || plan.intent == null) {
                    String alt = OpenIn.fallback(this);   // on no streaming app: Stremio or Nuvio, when installed
                    if (alt != null) {
                        new Thread(() -> {
                            Intent i = OpenIn.intent(this, t, alt);
                            handler.post(() -> {
                                if (i != null) open(i, null);
                                else Toast.makeText(this, getString(R.string.not_streaming, t.title), Toast.LENGTH_LONG).show();
                            });
                        }, "open-in").start();
                        return;
                    }
                    Toast.makeText(this, getString(R.string.not_streaming, t.title), Toast.LENGTH_LONG).show();
                    return;
                }
                if (plan.outcome == DeepLinks.Outcome.APP) {
                    Toast.makeText(this, getString(R.string.opened_app_search, service.name, t.title), Toast.LENGTH_LONG).show();
                } else if (plan.outcome == DeepLinks.Outcome.STORE) {
                    Toast.makeText(this, getString(R.string.get_app_first, service.name), Toast.LENGTH_LONG).show();
                }
                open(plan.intent, null);
            });
        }, "play-title").start();
    }

    @Override
    public void onWifi() {
        open(new Intent(Settings.ACTION_WIFI_SETTINGS), null);
    }

    @Override
    public void onSystemSettings() {
        open(new Intent(Settings.ACTION_SETTINGS), null);
    }

    /** A picture for a Random card, found off the main thread. */
    @Override
    public void onCardArt(int kind, Picks.Entry card, String language, java.util.function.Consumer<String> done) {
        artPool.execute(() -> {
            String url = CardArt.find(this, kind, card, language);
            handler.post(() -> done.accept(url));
        });
    }

    private final java.util.concurrent.ExecutorService artPool = Work.pool(3);

    @Override
    public void onSaveSearch() {
        saveSearch(null);
    }

    private void saveSearch(@androidx.annotation.Nullable String initial) {
        TvKeyboard.youtube(this, initial, new TvKeyboard.Listener() {
            @Override
            public void onSearch(String text) {
                HomeKeyService.offerTyped(text);
            }

            @Override
            public boolean onPick(TvKeyboard.Hit hit) {
                HomeKeyService.offer(hit.title, hit.label, hit.url);
                return true;
            }

            @Override
            public void onClosed() {
                wheel.requestFocus();
            }
        });
    }

    @Override
    public void onSearchKeys() {
        TvKeyboard.page(this, null, wheel::requestFocus);
    }

    /** A one-line box with the TV's keyboard, and a QR code to type on a phone. */
    @Override
    public void onAskText(String title, String hint, java.util.function.Consumer<String> done) {
        TextBox.ask(this, title, hint, null, getString(R.string.add), done);
    }

    @Override
    public void onAskText(String title, String hint, String ok, java.util.function.Consumer<String> done) {
        TextBox.ask(this, title, hint, null, ok, done);
    }

    /**
     * Search: films and series (TMDB, else JustWatch) with the app each streams on, yours first, and their details;
     * then YouTube videos for the same words.
     */
    @Override
    public void onSearch(String query, java.util.function.Consumer<List<Object>> done) {
        new Thread(() -> {
            java.util.concurrent.ExecutorService pool = Work.pool(6);
            java.util.concurrent.Future<List<Songs.Pick>> videos = pool.submit(() -> Songs.searchVideos(query, 8));
            List<Tmdb.Title> titles = new ArrayList<>();
            try {
                for (Tmdb.Title t : Catalog.search(this, query, 1).titles) if (titles.size() < 10) titles.add(t);
            } catch (Exception ignored) {
                // offline: videos only
            }
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (Tmdb.Title t : titles) jobs.add(pool.submit(() -> {
                try {
                    List<Services.Service> where = Catalog.whereToWatch(this, t);
                    for (Services.Service w : where) if (Services.installed(this, w.pkg)) { t.service = w; break; }
                    if (t.service == null && !where.isEmpty()) t.service = where.get(0);
                } catch (Exception ignored) {
                    // shown without an app
                }
                Catalog.details(this, t);
                ImageCache.prefetch(t.backdropCard());
            }));
            for (java.util.concurrent.Future<?> f : jobs) {
                try {
                    f.get(6, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // shown with what it has
                }
            }
            List<Object> out = new ArrayList<>();
            // On your apps first, then elsewhere, then not streaming in India.
            for (Tmdb.Title t : titles) if (t.service != null && Services.installed(this, t.service.pkg)) out.add(t);
            for (Tmdb.Title t : titles) if (!out.contains(t) && t.service != null) out.add(t);
            for (Tmdb.Title t : titles) if (!out.contains(t)) out.add(t);
            try {
                out.addAll(videos.get(10, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception ignored) {
                // no videos
            }
            pool.shutdown();
            handler.post(() -> done.accept(out));
        }, "search").start();
    }

    /** My Mix, from a random song. */
    @Override
    public void onMyMix() {
        openYouTube(Picks.myMixUrl(random));
    }

    /** Random Music and Extras: about twelve songs, playlists or videos for a card, none shown before for it. */
    @Override
    public void onFindMusic(int kind, Picks.Entry card, List<String> ticked, List<String> shown, boolean playlists,
                            java.util.function.Consumer<List<Songs.Pick>> done) {
        new Thread(() -> {
            List<Songs.Pick> got = Songs.find(this, kind, card, ticked, shown, playlists, 12, random);
            for (int i = 0; i < Math.min(4, got.size()); i++) ImageCache.prefetch(got.get(i).thumb);
            handler.post(() -> done.accept(got));
        }, "random-music").start();
    }

    @Override
    public void onPlayMusic(Songs.Pick pick) {
        openYouTube(pick.url(random));
    }

    @Override
    public void onPeek(Songs.Pick pick, Runnable done) {
        new Thread(() -> {
            pick.includes = Songs.includes(pick.id);
            handler.post(done);
        }, "peek").start();
    }

    private void openYouTube(String url) {
        String yt = Services.YOUTUBE_PKG;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(yt);
        if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
        open(i, null);
    }

    /**
     * Tonight's three: random titles for a card of the picker from all your apps. Apps are weighted by how many
     * matching titles they have, pages are random across the whole list (up to 500 titles per app), and titles
     * already given for this exact choice, or hidden with Not for me, are skipped until the pool is used up. Titles
     * that open on their own page are preferred over ones that would only open the app.
     */
    @Override
    public void onPickTitles(int kind, Picks.Entry card, List<String> ticked, int count,
                             java.util.function.Consumer<List<Tmdb.Title>> done) {
        List<Services.Service> mine = Services.installedOrPopular(this);
        if (mine.isEmpty()) {
            Toast.makeText(this, R.string.no_streaming_apps, Toast.LENGTH_LONG).show();
            done.accept(new ArrayList<>());
            return;
        }
        String bag = Picks.bag(kind, card, ticked);
        new Thread(() -> {
            String type = kind == Picks.MOVIE ? "movie" : "tv";
            List<String> codes = Picks.titleLanguages(ticked);
            List<Tmdb.Title> picks = new ArrayList<>();
            // A few titles from each of three of the card's parts (Thriller & Crime: a heist, a courtroom drama…),
            // chosen at random and fetched side by side.
            int parts = Math.min(3, Math.max(1, count / 2));
            java.util.concurrent.ExecutorService pool = Work.pool(parts);
            List<java.util.concurrent.Future<List<Tmdb.Title>>> rounds = new ArrayList<>();
            for (int i = 0; i < parts; i++) {
                final Tmdb.Category c = Picks.titlePart(card, codes, random).category;
                final List<String> lang = c.language == null ? codes : java.util.Collections.singletonList("");
                final int want = (count + parts - 1) / parts;
                rounds.add(pool.submit(() -> {
                    List<Tmdb.Title> got = randomTitles(c, type, lang, mine, bag, want);
                    if (got.size() < want && !Picks.seen(this, bag).isEmpty()) {   // the pool is used up: a new round
                        Picks.newRound(this, bag);
                        got.addAll(randomTitles(c, type, lang, mine, bag, want - got.size()));
                    }
                    return got;
                }));
            }
            for (java.util.concurrent.Future<List<Tmdb.Title>> r : rounds) {
                try {
                    for (Tmdb.Title t : r.get(25, java.util.concurrent.TimeUnit.SECONDS)) {
                        if (picks.size() < count && !containsTitle(picks, t)) picks.add(t);
                    }
                } catch (Exception ignored) {
                    // that part found nothing in time
                }
            }
            java.util.Collections.shuffle(picks, random);
            // Runtime, genres and trailer for the cards, and their big pictures, side by side: 3 s at most in all.
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            java.util.concurrent.ExecutorService more = Work.pool(4);
            for (Tmdb.Title t : picks) jobs.add(more.submit(() -> {
                Catalog.details(this, t);
                ImageCache.prefetch(t.backdrop());
            }));
            long until = android.os.SystemClock.uptimeMillis() + 3000;
            for (java.util.concurrent.Future<?> f : jobs) {
                try {
                    f.get(Math.max(1, until - android.os.SystemClock.uptimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // shown with what it has
                }
            }
            pool.shutdown();
            more.shutdown();
            handler.post(() -> {
                if (picks.isEmpty()) Toast.makeText(this, R.string.nothing_for_choice, Toast.LENGTH_LONG).show();
                done.accept(picks);
            });
        }, "random-titles").start();
    }

    private static boolean containsTitle(List<Tmdb.Title> l, Tmdb.Title t) {
        for (Tmdb.Title x : l) if (x.key().equals(t.key())) return true;
        return false;
    }

    /** Page 1 of every app for one language, side by side: what each has for the choice, and how much. */
    private Map<Services.Service, Tmdb.Page> firstPages(Tmdb.Category c, String type, String language, List<Services.Service> mine) {
        Map<Services.Service, Tmdb.Page> firsts = new java.util.LinkedHashMap<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, mine.size()));
        Map<Services.Service, java.util.concurrent.Future<Tmdb.Page>> jobs = new HashMap<>();
        for (Services.Service s : mine) jobs.put(s, pool.submit(() -> Catalog.discover(this, c, s, type, 1, language)));
        for (Services.Service s : mine) {
            try {
                Tmdb.Page p = jobs.get(s).get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (p != null && !p.titles.isEmpty()) firsts.put(s, p);
            } catch (Exception ignored) {
                // that app is left out of this draw
            }
        }
        pool.shutdownNow();
        return firsts;
    }

    /**
     * Up to `count` draws from the choice's pool, each marked as given. With several languages, each draw is in one
     * of them at random (so a small language is not drowned out by a big one), from an app weighted by how many
     * matching titles it has in that language.
     */
    private List<Tmdb.Title> randomTitles(Tmdb.Category c, String type, List<String> languages, List<Services.Service> mine,
                                          String bag, int count) {
        List<Tmdb.Title> out = new ArrayList<>();
        java.util.Set<String> skip = Picks.seen(this, bag);
        skip.addAll(Picks.hidden(this));
        // Your watched list (Must-Watch, My Movies) is skipped too, unless switched off in Settings.
        java.util.Set<String> watched = Library.skipWatched(this) ? Library.watched(this) : java.util.Collections.emptySet();
        Map<String, Map<Services.Service, Tmdb.Page>> firstsBy = new HashMap<>();
        Map<String, Map<Services.Service, Integer>> weightsBy = new HashMap<>();
        List<String> open = new ArrayList<>(languages);
        // Candidates: a few more than needed, from random languages, weighted apps and random pages.
        List<Tmdb.Title> candidates = new ArrayList<>();
        for (int attempt = 0; attempt < count * 4 + languages.size() && candidates.size() < count * 2 && !open.isEmpty(); attempt++) {
            String language = open.get(random.nextInt(open.size()));
            Map<Services.Service, Tmdb.Page> firsts = firstsBy.get(language);
            if (firsts == null) {
                firsts = firstPages(c, type, language, mine);
                firstsBy.put(language, firsts);
                Map<Services.Service, Integer> w = new HashMap<>();
                for (Map.Entry<Services.Service, Tmdb.Page> e : firsts.entrySet()) {
                    Tmdb.Page p = e.getValue();
                    w.put(e.getKey(), p.totalResults > 0 ? Math.min(500, p.totalResults) : p.more ? 100 : p.titles.size());
                }
                weightsBy.put(language, w);
            }
            Map<Services.Service, Integer> weight = weightsBy.get(language);
            if (weight.isEmpty()) {   // nothing in this language for the choice
                open.remove(language);
                continue;
            }
            Services.Service s = weighted(weight);
            int pages = Math.max(1, Math.min(25, (weight.get(s) + 19) / 20));
            int page = 1 + random.nextInt(pages);
            Tmdb.Page p = firsts.get(s);
            if (page > 1) {
                try {
                    Tmdb.Page got = Catalog.discover(this, c, s, type, page, language);
                    if (got != null && !got.titles.isEmpty()) p = got;
                } catch (Exception ignored) {
                    // use page 1
                }
            }
            List<Tmdb.Title> fresh = new ArrayList<>();
            for (Tmdb.Title t : p.titles) {
                if (!skip.contains(t.key()) && !watched.contains(Library.keyOf(t)) && !containsTitle(candidates, t)) fresh.add(t);
            }
            if (fresh.isEmpty()) {
                if (pages == 1) weight.remove(s);   // this app has nothing new for the choice in this language
                continue;
            }
            Tmdb.Title t = fresh.get(random.nextInt(fresh.size()));
            t.service = s;
            candidates.add(t);
        }
        // Prefer titles that open on their own page (checked side by side; the lookups are cached).
        java.util.concurrent.ExecutorService checks = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, candidates.size()));
        List<java.util.concurrent.Future<Boolean>> linked = new ArrayList<>();
        for (Tmdb.Title t : candidates) linked.add(checks.submit(() -> DeepLinks.plan(this, t, t.service).outcome == DeepLinks.Outcome.TITLE));
        List<Tmdb.Title> withLink = new ArrayList<>(), without = new ArrayList<>();
        long checkUntil = android.os.SystemClock.uptimeMillis() + 3000;   // links checked for 3 s at most in all
        for (int i = 0; i < candidates.size(); i++) {
            boolean ok;
            try {
                ok = linked.get(i).get(Math.max(1, checkUntil - android.os.SystemClock.uptimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                ok = false;
            }
            (ok ? withLink : without).add(candidates.get(i));
        }
        checks.shutdownNow();
        for (Tmdb.Title t : withLink) if (out.size() < count) out.add(t);
        for (Tmdb.Title t : without) if (out.size() < count) out.add(t);
        for (Tmdb.Title t : out) Picks.markSeen(this, bag, t.key());
        return out;
    }

    /** An app chosen with probability in proportion to its weight. */
    private Services.Service weighted(Map<Services.Service, Integer> weight) {
        int total = 0;
        for (int w : weight.values()) total += Math.max(1, w);
        int r = random.nextInt(total);
        for (Map.Entry<Services.Service, Integer> e : weight.entrySet()) {
            r -= Math.max(1, e.getValue());
            if (r < 0) return e.getKey();
        }
        return weight.keySet().iterator().next();
    }

    private boolean open(Intent i, Rect from) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (from != null) {
                View root = getWindow().getDecorView();
                startActivity(i, ActivityOptions.makeScaleUpAnimation(root, from.left, from.top,
                        from.width(), from.height()).toBundle());
            } else {
                startActivity(i);
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (from == null) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** The Wi-Fi symbol dims when the TV is offline. */
    private void updateWifi() {
        boolean online = false;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            online = cm != null && cm.getActiveNetwork() != null;
        } catch (Exception ignored) {
        }
        wheel.setOnline(online);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == Perms.ASK_TV_LISTINGS && wheel != null) wheel.findContinue();   // allowed: look again at once
    }
}
