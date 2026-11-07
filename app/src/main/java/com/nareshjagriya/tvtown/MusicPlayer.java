package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TV Town's own music player: one queue for the whole app, so a song keeps playing on the home screen. Shuffle
 * plays the queue in a random order (each song once), repeat is off, all or one, and the sleep timer stops after
 * some minutes or at the end of the song. Pauses when another app takes the sound (a film, YouTube) and when the
 * drive is taken out. MusicService keeps it alive while it plays.
 */
final class MusicPlayer {

    interface Watcher {
        /** Song, play state or queue changed. */
        void changed();
    }

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    private static MusicPlayer instance;

    static synchronized MusicPlayer get(Context ctx) {
        if (instance == null) instance = new MusicPlayer(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final List<Watcher> watchers = new CopyOnWriteArrayList<>();
    private MediaPlayer mp;
    private final List<MyMusic.Song> queue = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();   // play order: positions in the queue
    private int at = -1;                                     // index into order
    private boolean shuffle, prepared, playing, focusLost;
    private int repeat = REPEAT_ALL;
    private String queueName = "";
    private long sleepAt;               // elapsedRealtime to stop at, 0 off
    private boolean sleepAfterSong;
    private int sessionId;
    private AudioFocusRequest focus;

    private MusicPlayer(Context app) {
        ctx = app;
        audio = app.getSystemService(AudioManager.class);
        shuffle = MyMusic.settings(app).getBoolean("shuffle", false);
        repeat = MyMusic.settings(app).getInt("repeat", REPEAT_ALL);
    }

    void watch(Watcher w) {
        if (!watchers.contains(w)) watchers.add(w);
    }

    void unwatch(Watcher w) {
        watchers.remove(w);
    }

    private void tell() {
        for (Watcher w : watchers) w.changed();
    }

    // ---- what is playing ------------------------------------------------------------------------------

    @Nullable
    MyMusic.Song current() {
        return at >= 0 && at < order.size() ? queue.get(order.get(at)) : null;
    }

    /** The next songs in play order (for "Up next"), at most n. */
    List<MyMusic.Song> upNext(int n) {
        List<MyMusic.Song> out = new ArrayList<>();
        for (int i = at + 1; i < order.size() && out.size() < n; i++) out.add(queue.get(order.get(i)));
        return out;
    }

    boolean playing() {
        return playing;
    }

    boolean active() {
        return current() != null;
    }

    String queueName() {
        return queueName;
    }

    long position() {
        try {
            return prepared && mp != null ? mp.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    long duration() {
        try {
            if (prepared && mp != null && mp.getDuration() > 0) return mp.getDuration();
        } catch (IllegalStateException ignored) {
        }
        MyMusic.Song s = current();
        return s != null ? s.durationMs : 0;
    }

    /** The sound's session, for the music bars; 0 when nothing is loaded. */
    int sessionId() {
        return prepared ? sessionId : 0;
    }

    boolean shuffle() {
        return shuffle;
    }

    int repeat() {
        return repeat;
    }

    // ---- controls -------------------------------------------------------------------------------------

    /** Plays a list from one song (or, with shuffle, from a random one when start is -1). */
    void play(List<MyMusic.Song> songs, int start, boolean shuffled, String name) {
        if (songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        queueName = name;
        if (shuffled != shuffle) setShuffle(shuffled, false);
        int first = start >= 0 ? start : new Random().nextInt(songs.size());
        buildOrder(first);
        at = 0;
        load(true);
    }

    /** Play order: the first song, then the rest in order or shuffled. */
    private void buildOrder(int first) {
        order.clear();
        for (int i = 0; i < queue.size(); i++) if (i != first) order.add(i);
        if (shuffle) Collections.shuffle(order);
        else {
            List<Integer> wrapped = new ArrayList<>();   // in order from the first song, wrapping round
            for (int i = first + 1; i < queue.size(); i++) wrapped.add(i);
            for (int i = 0; i < first; i++) wrapped.add(i);
            order.clear();
            order.addAll(wrapped);
        }
        order.add(0, first);
    }

    void toggle() {
        if (mp == null || !prepared) {
            if (current() != null) load(true);
            return;
        }
        if (playing) pause();
        else resume();
    }

    void pause() {
        if (mp != null && prepared && playing) {
            mp.pause();
            playing = false;
            MusicService.update(ctx, false);
            tell();
        }
    }

    private void resume() {
        if (!requestFocus()) return;
        mp.start();
        playing = true;
        MusicService.update(ctx, true);
        tell();
    }

    void next() {
        if (order.isEmpty()) return;
        if (at + 1 >= order.size()) {
            if (shuffle) {   // a new round in a new order
                int last = order.get(at);
                Collections.shuffle(order);
                if (order.size() > 1 && order.get(0) == last) Collections.swap(order, 0, order.size() - 1);
            }
            at = 0;
        } else at++;
        load(true);
    }

    /** Back to the start of the song when more than 4 seconds in, else the song before. */
    void previous() {
        if (order.isEmpty()) return;
        if (position() > 4000) {
            seekTo(0);
            return;
        }
        at = at > 0 ? at - 1 : order.size() - 1;
        load(true);
    }

    void seekTo(long ms) {
        if (mp != null && prepared) mp.seekTo((int) Math.max(0, Math.min(duration() - 500, ms)));
        tell();
    }

    void setShuffle(boolean on) {
        setShuffle(on, true);
    }

    private void setShuffle(boolean on, boolean keepCurrent) {
        shuffle = on;
        MyMusic.settings(ctx).edit().putBoolean("shuffle", on).apply();
        if (keepCurrent && current() != null) {   // the song playing stays; what comes after is re-ordered
            buildOrder(order.get(at));
            at = 0;
        }
        tell();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        MyMusic.settings(ctx).edit().putInt("repeat", repeat).apply();
        tell();
    }

    /** Stops everything and lets the sound go (the drive was taken out, or Stop). */
    void stop() {
        release();
        queue.clear();
        order.clear();
        at = -1;
        sleepAt = 0;
        sleepAfterSong = false;
        MusicService.update(ctx, false);
        tell();
    }

    // ---- sleep timer ----------------------------------------------------------------------------------

    /** Minutes from now (0 turns it off), or -1 for the end of this song. */
    void sleepIn(int minutes) {
        main.removeCallbacks(sleepCheck);
        sleepAfterSong = minutes < 0;
        sleepAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        if (sleepAt > 0) main.postDelayed(sleepCheck, minutes * 60_000L);
        tell();
    }

    /** Minutes left on the sleep timer, -1 for "after this song", 0 when off. */
    int sleepLeft() {
        if (sleepAfterSong) return -1;
        if (sleepAt <= 0) return 0;
        return (int) Math.max(1, Math.ceil((sleepAt - SystemClock.elapsedRealtime()) / 60_000.0));
    }

    private final Runnable sleepCheck = () -> {
        sleepAt = 0;
        fadeOutAndPause();
    };

    /** A gentle stop: the sound fades over four seconds. */
    private void fadeOutAndPause() {
        if (mp == null || !playing) return;
        final long start = SystemClock.uptimeMillis();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (mp == null) return;
                float t = (SystemClock.uptimeMillis() - start) / 4000f;
                if (t >= 1) {
                    pause();
                    mp.setVolume(1, 1);
                    return;
                }
                mp.setVolume(1 - t, 1 - t);
                main.postDelayed(this, 100);
            }
        });
    }

    // ---- the media player -----------------------------------------------------------------------------

    private void load(boolean start) {
        release();
        MyMusic.Song s = current();
        if (s == null) return;
        remember(s);
        MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(ctx, s.uri);
            p.setOnPreparedListener(m -> {
                if (m != mp) return;
                prepared = true;
                sessionId = m.getAudioSessionId();
                if (start && requestFocus()) {
                    m.start();
                    playing = true;
                }
                MusicService.update(ctx, playing);
                tell();
            });
            p.setOnCompletionListener(m -> {
                if (m != mp) return;
                if (sleepAfterSong) {
                    sleepAfterSong = false;
                    playing = false;
                    MusicService.update(ctx, false);
                    tell();
                    return;
                }
                if (repeat == REPEAT_ONE) {
                    m.seekTo(0);
                    m.start();
                    return;
                }
                if (repeat == REPEAT_OFF && at + 1 >= order.size()) {
                    playing = false;
                    MusicService.update(ctx, false);
                    tell();
                    return;
                }
                next();
            });
            p.setOnErrorListener((m, what, extra) -> {   // a broken file: on to the next, unless all of them fail
                if (m != mp) return true;
                skipped++;
                if (skipped < Math.min(5, order.size())) main.postDelayed(this::next, 300);
                else stop();
                return true;
            });
            p.prepareAsync();
            skippedReset();
        } catch (Exception e) {
            main.postDelayed(this::next, 300);
        }
        tell();
    }

    private int skipped;

    private void skippedReset() {
        main.removeCallbacks(clearSkipped);
        main.postDelayed(clearSkipped, 10_000);
    }

    private final Runnable clearSkipped = () -> skipped = 0;

    private void release() {
        prepared = false;
        playing = false;
        sessionId = 0;
        if (mp != null) {
            MediaPlayer old = mp;
            mp = null;
            try {
                old.reset();
                old.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- sharing the sound with other apps ------------------------------------------------------------

    private boolean requestFocus() {
        if (audio == null) return true;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(this::focusChanged, main).build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Another app plays: pause (and carry on after a short interruption, like a notification sound). */
    private void focusChanged(int change) {
        if (mp == null || !prepared) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focusLost = false;
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusLost = playing;
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && focusLost) {
            focusLost = false;
            resume();
        }
    }

    /** The drive with the songs went away. */
    void driveRemoved() {
        if (active()) stop();
    }

    /** For the TV's media keys on any TV Town screen: true when the key was used. */
    boolean mediaKey(int keyCode) {
        if (!active()) return false;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY: if (!playing) toggle(); return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE: pause(); return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT: next(); return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS: previous(); return true;
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP: stop(); return true;
            default: return false;
        }
    }

    /** The songs played last, newest first (by key), for Home's Continue Listening. */
    static List<String> recent(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String k : MyMusic.settings(ctx).getString("recent", "").split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void remember(MyMusic.Song s) {
        List<String> r = recent(ctx);
        r.remove(s.key());
        r.add(0, s.key());
        while (r.size() > 10) r.remove(r.size() - 1);
        MyMusic.settings(ctx).edit().putString("recent", String.join("\n", r)).apply();
    }

    static Intent openIntent(Context ctx) {
        return new Intent(ctx, MyMusicActivity.class).putExtra(MyMusicActivity.EXTRA_PLAYER, true);
    }
}
