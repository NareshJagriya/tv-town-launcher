package com.nareshjagriya.tvtown;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The TV Town home helper: keeps TV Town the default home without turning anything off. While TV Town is the chosen
 * home, it opens TV Town whenever the TV's own home screen would appear: Home on the remote, backing out of an app,
 * and start-up. When Google TV (or the TV's own launcher) is chosen instead, it does nothing.
 *
 * It looks only at the Home key and at which app's window came to the front, never at what is on screen. The
 * choice is switched in TV Town's Settings, and switched back by opening TV Town from the TV's app list.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String PREF_MODE = "home_mode";
    static final String TV_TOWN = "tvtown", STOCK = "stock";

    /**
     * Red, green, yellow and blue open the Random pop-ups from any app (not live TV). Off unless switched on in
     * Settings: those buttons belong to the TV's own features (teletext, apps) on many TVs.
     */
    static boolean colourKeys(Context ctx) {
        return false;   // removed: watching the buttons for them stopped held volume repeating on this TV
    }

    static void setColourKeys(Context ctx, boolean on) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("colour_keys", on).apply();
        if (instance != null) instance.filterKeys();
    }

    private static HomeKeyService instance;

    /** The app on the screen now (its package), or "" when not known: the phone's search goes into it. */
    static String frontApp() {
        HomeKeyService me = instance;
        return me == null || me.front == null ? "" : me.front;
    }

    /**
     * Watching the remote's buttons makes this TV stop repeating a held one (holding volume changes it only once),
     * so the helper watches them only when the coloured buttons are switched on. Home doesn't need it: the TV's
     * own home coming to the front is noticed (onAccessibilityEvent) and replaced with TV Town.
     */
    private void filterKeys() {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int f = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        boolean on = colourKeys(this) || saveWindow || (saveNote != null && saveNote.wantsKeys());
        if (on == filtering) return;
        filtering = on;
        info.flags = on ? info.flags | f : info.flags & ~f;
        setServiceInfo(info);
    }

    // ---- the red button: save what's playing -----------------------------------------------------------

    private static final long HOLD_MS = 700, QR_MS = 180_000, PAUSE_SETTLE_MS = 600, SAVE_WINDOW_MS = 6000;
    private boolean filtering, redHeld, redDown, saveWindow;
    private SaveNote saveNote;
    private final Runnable redHold = () -> {
        redHeld = true;
        pasteLink();
    };
    private final Runnable saveWindowEnd = () -> endSaveWindow(true);
    private final List<android.media.session.MediaController> watched = new java.util.ArrayList<>();
    private final android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> watchSessions(list);
    private final android.media.session.MediaController.Callback playback = new android.media.session.MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(android.media.session.PlaybackState p) {
            if (p == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (p.getState() == android.media.session.PlaybackState.STATE_PAUSED) {
                pausedAt = now;
                main.removeCallbacks(offerSave);
                main.postDelayed(offerSave, PAUSE_SETTLE_MS);   // not for the moment a seek or an advert pauses it
            } else {
                main.removeCallbacks(offerSave);
                if (p.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    endSaveWindow(true);
                    // OK pressed twice: paused and playing again within a moment. Save it, on any remote, with no
                    // button watched (watching them would stop held volume repeating).
                    if (!Features.on(HomeKeyService.this, Features.YT_SAVE)) pausedAt = 0;   // switched off in Settings → Features
                    if (pausedAt > 0 && now - pausedAt < QUICK_MS && now - lastQuickSave > 8000 && youTube(front)) {
                        lastQuickSave = now;
                        if (TvTools.seesPlaying(HomeKeyService.this)) {
                            usedQuickSave();
                            startSaveNow(HomeKeyService.this, false);
                        } else note("To save, TV Town needs Notification access in the TV's settings", 6000);
                    } else if (pausedAt == 0 || now - pausedAt > 60_000) tipOnce();
                    pausedAt = 0;
                }
            }
        }
    };
    private final Runnable offerSave = this::offerSave;
    private static final long QUICK_MS = 1300;
    private long pausedAt, lastQuickSave;

    /** The first video of the day shows how to save with OK pressed twice, until it has been used a few times. */
    private void tipOnce() {
        android.content.SharedPreferences sp = getSharedPreferences("save", MODE_PRIVATE);
        long day = System.currentTimeMillis() / 86_400_000L;
        if (!youTube(front) || !Features.on(this, Features.YT_SAVE) || sp.getInt("quick_used", 0) >= 3 || sp.getLong("tip_day", -1) == day) return;
        sp.edit().putLong("tip_day", day).apply();
        main.postDelayed(() -> note("Tip: press OK twice to save this song", 4500), 2500);
    }

    private void usedQuickSave() {
        android.content.SharedPreferences sp = getSharedPreferences("save", MODE_PRIVATE);
        sp.edit().putInt("quick_used", sp.getInt("quick_used", 0) + 1).apply();
    }

    /**
     * Watching the remote's buttons stops a held one repeating everywhere (Android hands each press back as a single
     * one), and it can't be limited to one button. So they're watched only for a few seconds after a pause in YouTube,
     * while a small note offers the save; the rest of the time holding volume and the arrows work as normal.
     */
    private void offerSave() {
        if (!youTube(front) || !Features.on(this, Features.YT_SAVE) || !YtSaver.offerOnPause(this) || (saveNote != null && saveNote.wantsKeys())) return;
        saveWindow = true;
        filterKeys();
        card().note("↓  Press " + YtSaver.BUTTON_NAMES[YtSaver.button(this)] + " to save", SAVE_WINDOW_MS);
        main.removeCallbacks(saveWindowEnd);
        main.postDelayed(saveWindowEnd, SAVE_WINDOW_MS);
    }

    private void endSaveWindow(boolean hideNote) {
        main.removeCallbacks(saveWindowEnd);
        if (!saveWindow) return;
        saveWindow = false;
        filterKeys();
        if (hideNote && saveNote != null && !saveNote.wantsKeys()) saveNote.close();
    }

    /** Notes what every app plays, for Continue watching (Watched). */
    private final class Noter extends android.media.session.MediaController.Callback {
        final android.media.session.MediaController mc;

        Noter(android.media.session.MediaController mc) {
            this.mc = mc;
        }

        @Override
        public void onMetadataChanged(android.media.MediaMetadata m) {
            Watched.record(HomeKeyService.this, mc);
        }

        @Override
        public void onPlaybackStateChanged(android.media.session.PlaybackState p) {
            Watched.record(HomeKeyService.this, mc);
        }
    }

    private final List<Noter> noters = new java.util.ArrayList<>();

    private void watchSessions(@androidx.annotation.Nullable List<android.media.session.MediaController> list) {
        for (android.media.session.MediaController c : watched) c.unregisterCallback(playback);
        watched.clear();
        for (Noter n : noters) n.mc.unregisterCallback(n);
        noters.clear();
        if (list == null) return;
        for (android.media.session.MediaController c : list) {
            Noter n = new Noter(c);
            c.registerCallback(n, main);
            noters.add(n);
            Watched.record(this, c);
        }
        for (android.media.session.MediaController c : list) {
            if (!youTube(c.getPackageName())) continue;
            c.registerCallback(playback, main);
            watched.add(c);
        }
    }

    // False until notification access lets us watch; tried again when an app comes to the front, so access switched
    // on later (or lost at a restart and given back) starts working without restarting the helper.
    private boolean watchingSessions;

    private void startWatchingSessions() {
        try {
            android.media.session.MediaSessionManager m = getSystemService(android.media.session.MediaSessionManager.class);
            ComponentName me = new ComponentName(this, MediaWatch.class);
            m.addOnActiveSessionsChangedListener(sessions, me, main);
            watchSessions(m.getActiveSessions(me));
            watchingSessions = true;
        } catch (SecurityException e) {
            android.util.Log.w("YtSaver", "no notification access", e);
        }
    }

    private final Runnable phoneTimeout = () -> {
        if (saveNote != null) saveNote.close();
    };

    /** A real app screen: not a corner note (this card's own window counts as a change too), a menu or a toast. */
    private boolean isScreen(String pkg, CharSequence name) {
        if (name == null) return false;
        try {
            return getPackageManager().getActivityInfo(new ComponentName(pkg, name.toString()), 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Only here are the buttons watched, so held volume keeps working everywhere else (see filterKeys). */
    static boolean youTube(String pkg) {
        return pkg.equals("com.google.android.youtube.tv") || pkg.equals("com.google.android.youtube.tvmusic");
    }

    private SaveNote card() {
        if (saveNote == null) saveNote = new SaveNote(this, getSystemService(android.view.WindowManager.class));
        return saveNote;
    }

    /** A small note in the corner, from anywhere. */
    static void note(String text, long ms) {
        HomeKeyService s = instance;
        if (s != null) s.main.post(() -> s.card().note(text, ms));
    }

    /** The card opened or closed: it needs the remote's buttons only while open. */
    static void keysChanged() {
        HomeKeyService s = instance;
        if (s != null) s.filterKeys();
    }

    /** Red in YouTube: what's playing. With nothing there, the link from the phone instead. */
    private void redPressed() {
        if (!TvTools.seesPlaying(this)) {
            note("Switch on TV Town in Settings → Apps → Special app access → Notification access", 7000);
            return;
        }
        android.media.session.MediaController m = TvTools.nowPlaying(this);
        String title = m == null ? "" : TvTools.title(m);
        if (title.isEmpty()) {
            pasteLink();
            return;
        }
        android.media.MediaMetadata md = m.getMetadata();
        CharSequence by = md == null ? null : md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        String channel = by == null ? "" : by.toString();
        long seconds = md == null ? 0 : md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) / 1000;
        card().choice(title, channel, video -> {
            note("Finding: " + title, 3000);
            YtSaver.find(title, channel, seconds, f -> {
                if (f == null) note("Couldn't find this exact video", 5000);
                else save(f, video);
            });
        }, null);
    }

    /** Held red: a QR code for the phone; the link it sends goes straight to the Audio / Video choice. */
    static void pasteLink() {
        HomeKeyService s = instance;
        if (s != null) s.openPhone();
    }

    private boolean qrOpen;

    /** The phone opened the page or pressed something: it's connected, so the QR code has done its job. */
    static void phoneConnected() {
        HomeKeyService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (!s.qrOpen) return;
            s.qrOpen = false;
            s.main.removeCallbacks(s.phoneTimeout);
            s.card().close();
        });
    }

    private void openPhone() {
        if (!Features.on(this, Features.PHONE)) {
            note("The phone remote is off: turn it on in Settings \u2192 Features", 6000);
            return;
        }
        String url = PhoneLink.url(this);
        android.graphics.Bitmap code = url == null ? null : PhoneKeyboard.qrFor(url, 480);
        if (code == null) {
            note("The TV isn't connected to the network", 5000);
            return;
        }
        qrOpen = true;
        card().qr(code, () -> {
            qrOpen = false;
            main.removeCallbacks(phoneTimeout);
        });
        main.removeCallbacks(phoneTimeout);
        main.postDelayed(phoneTimeout, QR_MS);
    }

    /** From the page on your phone: it has already chosen Audio or Video, so straight to saving. */
    static void saveFromPhone(String link, boolean video) {
        HomeKeyService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            note("Reading the link", 3000);
            YtSaver.read(link, f -> {
                if (f == null || f.urls.isEmpty()) note("That link didn't work", 5000);
                else s.save(f, video);
            });
        });
    }

    /** Puts the TV to sleep; false when the helper is not running. */
    static boolean sleepNow() {
        HomeKeyService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    static void globalAction(int action) {
        HomeKeyService s = instance;
        if (s != null) s.performGlobalAction(action);
    }

    /** "Save what's playing" on the phone; false when nothing is. */
    /** ok, noaccess (TV Town can't see other apps' playback) or nothing. */
    static String saveNowFromPhone(boolean video) {
        HomeKeyService s = instance;
        if (s == null) return "nothing";
        if (!TvTools.seesPlaying(s)) return "noaccess";
        return startSaveNow(s, video) ? "ok" : "nothing";
    }

    private static boolean startSaveNow(HomeKeyService s, boolean video) {
        android.media.session.MediaController m = TvTools.nowPlaying(s);
        String title = m == null ? "" : TvTools.title(m);
        if (title.isEmpty()) return false;
        android.media.MediaMetadata md = m.getMetadata();
        CharSequence by = md == null ? null : md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        long seconds = md == null ? 0 : md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) / 1000;
        s.main.post(() -> {
            note("Finding: " + title, 3000);
            YtSaver.find(title, by == null ? "" : by.toString(), seconds, f -> {
                if (f == null) note("Couldn't find this exact video", 5000);
                else s.save(f, video);
            });
        });
        return true;
    }

    /** What was chosen in the keyboard: the Audio / Video card, then saving. */
    static void offer(String title, String by, String url) {
        HomeKeyService s = instance;
        if (s == null) return;
        YtSaver.Found f = new YtSaver.Found();
        f.title = title;
        f.by = by;
        f.urls.add(url);
        s.main.post(() -> s.card().choice(title, by, video -> s.save(f, video), null));
    }

    /** Search pressed in the keyboard: a link, or the first thing YouTube lists for the words. */
    static void offerTyped(String text) {
        HomeKeyService s = instance;
        if (s == null || text == null || text.trim().isEmpty()) return;
        s.main.post(() -> {
            note("Looking on YouTube", 3000);
            java.util.function.Consumer<YtSaver.Found> done = f -> {
                if (f == null || f.urls.isEmpty()) note("Nothing found", 5000);
                else s.card().choice(f.title, f.by, video -> s.save(f, video), null);
            };
            if (text.matches("(?i).*(youtube\\.com|youtu\\.be)/.*")) YtSaver.read(text, done);
            else new Thread(() -> {
                YtSaver.Found out = null;
                try {
                    List<YtSaver.Result> r = YtSaver.search(text);
                    if (!r.isEmpty()) {
                        out = new YtSaver.Found();
                        out.title = r.get(0).title;
                        out.by = r.get(0).by;
                        out.urls.add(r.get(0).url);
                    }
                } catch (Exception e) {
                    android.util.Log.w("YtSaver", "search", e);
                }
                final YtSaver.Found got = out;
                s.main.post(() -> done.accept(got));
            }, "save-search").start();
        });
    }

    private void save(YtSaver.Found f, boolean video) {
        java.io.File folder = YtSaver.folder(this, video);
        if (folder == null) note("Plug in a drive to save to", 5000);
        else YtSaver.add(this, f, video, folder);
    }

    /** True when the press was the saver's. */
    private boolean saverKey(KeyEvent event, int k) {
        if (saveNote != null && saveNote.wantsKeys()) {
            if (saveNote.key(event)) return true;
        }
        if (!youTube(front)) return false;
        if (k == YtSaver.BUTTONS[YtSaver.button(this)]) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    redHeld = false;
                    redDown = true;
                    main.postDelayed(redHold, HOLD_MS);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                main.removeCallbacks(redHold);
                if (redDown && !redHeld && !event.isCanceled()) {
                    endSaveWindow(false);
                    redPressed();
                }
                redDown = false;
            }
            return true;
        }
        return false;
    }

    /** Which home the viewer chose: TV Town (the default) or the TV's own. */
    static String mode(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString(PREF_MODE, TV_TOWN);
    }

    static boolean tvTownIsHome(Context ctx) {
        return TV_TOWN.equals(mode(ctx));
    }

    static void setMode(Context ctx, String mode) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString(PREF_MODE, mode).apply();
    }

    /** True when the helper is switched on in the TV's accessibility settings. */
    static boolean enabled(Context ctx) {
        String on = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(on)) return false;
        String me = new ComponentName(ctx, HomeKeyService.class).flattenToString();
        String shortName = ctx.getPackageName() + "/." + HomeKeyService.class.getSimpleName();
        for (String s : on.split(":")) if (me.equalsIgnoreCase(s) || shortName.equalsIgnoreCase(s)) return true;
        return false;
    }

    /**
     * The TV's own launchers: everything else that answers Home, except the system's safety nets (FallbackHome,
     * Settings, Google TV's setup/recovery screen).
     */
    static Set<String> stockLaunchers(Context ctx) {
        Set<String> out = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName, name = ri.activityInfo.name;
            if (pkg.equals(ctx.getPackageName()) || isSafetyNet(pkg, name)) continue;
            out.add(pkg);
        }
        return out;
    }

    static boolean isSafetyNet(String pkg, String activity) {
        return activity.endsWith("FallbackHome") || activity.endsWith("RecoveryActivity") || pkg.contains("setupwraith")
                || pkg.contains("setupwizard") || pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings");
    }

    private Set<String> launchers = new HashSet<>();
    private long lastOpen;
    private String front = "";   // the app in front, to leave the coloured buttons to live TV

    /** Live TV apps: their coloured buttons (teletext, guide) are left alone. */
    static boolean liveTv(String pkg) {
        return pkg.startsWith("org.droidtv") || pkg.equals("com.mediatek.wwtv.tvcenter") || pkg.equals("com.google.android.tv")
                || pkg.equals("com.sony.dtv.tvx") || pkg.equals("com.android.tv");
    }

    /**
     * At power-on: TV Town every time the TV wakes from standby (on by default), or only after a restart (the TV then
     * brings back the last HDMI input or app). Pressing a button as it wakes keeps what's on screen.
     */
    static boolean wakeHome(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("wake_home", true);
    }

    static void setWakeHome(Context ctx, boolean on) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("wake_home", on).apply();
    }

    private static final long WAKE_DELAY_MS = 800;   // after the TV has put its last input or app back
    private long keyAt;
    private BroadcastReceiver screenOn;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        launchers = stockLaunchers(this);
        instance = this;
        filterKeys();
        startWatchingSessions();
        if (Features.on(this, Features.PHONE)) PhoneLink.start(this);
        ServiceRepair.connected(this);
        // Connected at start-up (or when switched on): show TV Town straight away if it is the chosen home.
        if (tvTownIsHome(this)) openTvTown();
        if (screenOn == null) {
            screenOn = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (!wakeHome(c) || !tvTownIsHome(c)) return;
                    long on = SystemClock.elapsedRealtime();
                    main.postDelayed(() -> {
                        if (keyAt < on) openTvTown();   // not if a button was pressed meanwhile
                    }, WAKE_DELAY_MS);
                }
            };
            Perms.listen(this, screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        PhoneLink.stop();
        android.media.session.MediaSessionManager sm = getSystemService(android.media.session.MediaSessionManager.class);
        if (sm != null) sm.removeOnActiveSessionsChangedListener(sessions);
        watchSessions(null);
        main.removeCallbacks(offerSave);
        main.removeCallbacks(guard);
        ServiceRepair.disconnected(this);   // switched off on purpose: not put back
        if (screenOn != null) {
            unregisterReceiver(screenOn);
            screenOn = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        keyAt = SystemClock.elapsedRealtime();
        int k = event.getKeyCode();
        if (saverKey(event, k)) return true;
        boolean mic = k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST;
        if (mic && getPackageName().equals(front) && tvTownIsHome(this)) {   // on TV Town the mic is TV Town's voice
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(MainActivity.EXTRA_FROM_HELPER, true).putExtra(MainActivity.EXTRA_VOICE, true));
            }
            return true;
        }
        int random = HomeView.colourKind(k);
        if (random >= 0 && colourKeys(this) && tvTownIsHome(this) && !liveTv(front)) {   // red, green, yellow, blue: a Random pop-up
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) open(true, random);
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME || !tvTownIsHome(this)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) open(true, -1);
        return true;   // both press and release, so the TV's own home does not open as well
    }

    /** The TV's own home came to the front (backing out of an app, a Home press the key filter missed): replace it. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!watchingSessions && youTube(pkg)) startWatchingSessions();
        if (!pkg.equals("com.android.systemui") && !pkg.equals(front) && isScreen(pkg, event.getClassName())) {
            front = pkg;
            filterKeys();
        }
        if (!launchers.contains(pkg) || !tvTownIsHome(this)) return;
        open(false, -1);   // Back out of an app landed on the TV's own home
        guardTries = 0;
        main.removeCallbacks(guard);
        main.postDelayed(guard, GUARD_MS);
    }

    // Two things can leave the TV's own launcher on screen after Home. It can open itself a second time, on top of
    // TV Town, inside open()'s 700 ms window. And Android holds back other apps for 5 seconds after Home, unless the
    // launcher lifts that by opening itself. So keep taking the screen back for longer than those 5 seconds.
    private static final long GUARD_MS = 700;
    private static final int GUARD_MAX = 12;
    private int guardTries;
    private final Runnable guard = new Runnable() {
        @Override
        public void run() {
            if (front == null || !launchers.contains(front) || !tvTownIsHome(HomeKeyService.this) || guardTries++ >= GUARD_MAX) return;
            lastOpen = 0;
            open(false, -1);
            main.postDelayed(this, GUARD_MS);
        }
    };

    private void openTvTown() {
        open(false, -1);
    }

    /** Opens TV Town: after Home (homeKey), after Back out of an app, or with a Random pop-up (random 0 to 3). */
    private void open(boolean homeKey, int random) {
        long now = SystemClock.uptimeMillis();
        if (random < 0 && now - lastOpen < 700) return;   // one Home press can produce several events
        lastOpen = now;
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_FROM_HELPER, true)
                .putExtra(MainActivity.EXTRA_HOME_KEY, homeKey)
                .putExtra(MainActivity.EXTRA_RANDOM, random);
        try {   // sent this way, it isn't held back for the 5 seconds after Home
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (android.os.Build.VERSION.SDK_INT >= 34) {   // Android 14: the sender says it may start an activity from the background
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, o.toBundle());
            } else pi.send();
        } catch (android.app.PendingIntent.CanceledException e) {
            startActivity(i);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
