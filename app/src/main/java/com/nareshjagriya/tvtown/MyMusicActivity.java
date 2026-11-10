package com.nareshjagriya.tvtown;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * My Music (MyMusicView), over two video layers for the player's moving background. Background videos are any
 * videos in a "TV Town Scenes" folder on the drive, sorted into mood folders (Night, Candle, Rain, Lake, Mist,
 * Sunset, Fire, Beach, Forest, Ocean, Sky, City); each music folder has the moods that suit it (Gazals: Night,
 * Candle, Rain…), and a scene folder with a music folder's own name is used for that folder alone. A new video fades
 * in over three seconds with each song, and every four minutes in a long one. Silent, looping, cropped to fill.
 */
public class MyMusicActivity extends Activity implements MyMusicView.Listener {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    static final String EXTRA_PLAYER = "player", EXTRA_SONG = "song_key", EXTRA_FIND = "find";

    /** The keyboard elsewhere chose a song (it plays) or a singer, album or folder (its songs are listed). */
    private void fromKeyboard(android.content.Intent i) {
        String song = i.getStringExtra(EXTRA_SONG), find = i.getStringExtra(EXTRA_FIND);
        if (song != null) view.playKey(song);
        else if (find != null) view.findNamed(find);
    }
    private static final int ASK_STORAGE = 1, ASK_MIC = 2;
    private static final long SCENE_EVERY_MS = 45_000;

    private MyMusicView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Layer[] layers = new Layer[2];
    private int front = -1;
    private final List<Scene> scenes = new ArrayList<>();
    private boolean scenesRead, playerShown;
    private String sceneFolder;
    private Uri lastScene;

    /** A background video: where it is and its mood (its folder under TV Town Scenes). */
    private static final class Scene {
        Uri uri;
        String mood;
    }

    /** One video layer: a TextureView and its silent looping player. */
    private final class Layer {
        final TextureView tv;
        MediaPlayer mp;
        Surface surface;
        int vw, vh;

        Layer() {
            tv = new TextureView(MyMusicActivity.this);
            tv.setAlpha(0);
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surface = new Surface(st);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    crop();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    stop();
                    if (surface != null) surface.release();
                    surface = null;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
        }

        void play(Uri uri, Runnable shown) {
            stop();
            if (surface == null) return;
            MediaPlayer p = new MediaPlayer();
            mp = p;
            try {
                p.setSurface(surface);
                p.setDataSource(MyMusicActivity.this, uri);
                p.setLooping(true);
                p.setVolume(0, 0);
                p.setOnVideoSizeChangedListener((m, w, h) -> {
                    vw = w;
                    vh = h;
                    crop();
                });
                p.setOnInfoListener((m, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && m == mp) shown.run();
                    return false;
                });
                p.setOnErrorListener((m, what, extra) -> true);
                p.setOnPreparedListener(MediaPlayer::start);
                p.prepareAsync();
            } catch (Exception e) {
                stop();
            }
        }

        /** Fills the screen, cutting off what does not fit (no black bars). */
        void crop() {
            float w = tv.getWidth(), h = tv.getHeight();
            if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
            float s = Math.max(w / vw, h / vh);
            Matrix m = new Matrix();
            m.setScale(vw * s / w, vh * s / h, w / 2, h / 2);
            tv.setTransform(m);
        }

        void stop() {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Exception ignored) {
                }
                mp = null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.INK);
        for (int i = 0; i < 2; i++) {
            layers[i] = new Layer();
            root.addView(layers[i].tv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        view = new MyMusicView(this, null);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        view.setListener(this);
        view.requestFocus();
        boolean granted = Perms.media(this);
        view.setPermission(granted);
        if (!granted) requestPermissions(Perms.media(), ASK_STORAGE);
        else readScenes();
        if (getIntent().getBooleanExtra(EXTRA_PLAYER, false)) handler.post(() -> view.showPlayer(true));
        fromKeyboard(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_PLAYER, false)) view.showPlayer(true);
        fromKeyboard(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == ASK_STORAGE) {
            view.setPermission(ok);
            if (ok) readScenes();
        } else if (requestCode == ASK_MIC) view.barsPermission(ok);
    }

    /** The TV finished reading a drive (or one came or went): the songs and videos are read again. */
    private final android.content.BroadcastReceiver drives = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (!Perms.media(MyMusicActivity.this)) return;
            view.rescan();
            readScenes();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.content.Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(android.content.Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(android.content.Intent.ACTION_MEDIA_EJECT);
        f.addDataScheme("file");
        Perms.listen(this, drives, f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(drives);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setRunning(true);
        if (playerShown) startScene(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.setRunning(false);
        stopScenes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopScenes();
    }

    @Override
    public void onBackPressed() {
        if (TvKeyboard.back()) return;
        if (!view.back()) super.onBackPressed();
    }

    // ---- MyMusicView.Listener ---------------------------------------------------------------------------

    @Override
    public void onType(String title, String initial, Consumer<String> done) {
        TextBox.askName(this, title, "", initial, getString(R.string.done), done);
    }

    @Override
    public void onPlayerShown(boolean shown) {
        playerShown = shown;
        // The screen stays on while the player shows (the music is the point), and the TV's screensaver waits.
        if (shown) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (shown && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && !MyMusic.settings(this).getBoolean("asked_mic", false)) onAskBarsPermission();
        if (!shown) stopScenes();
    }

    @Override
    public void onScene(String folder) {
        sceneFolder = folder;
        if (!playerShown) return;
        if (hasVideos()) startScene(true);
        else stopScenes();
    }

    @Override
    public void onAskBarsPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            view.barsPermission(true);
            return;
        }
        if (MyMusic.settings(this).getBoolean("asked_mic", false)) return;
        MyMusic.settings(this).edit().putBoolean("asked_mic", true).apply();
        Toast.makeText(this, R.string.music_bars_permission, Toast.LENGTH_LONG).show();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC);
    }

    @Override
    public void onSearch(String initial, Consumer<String> typed, java.util.function.Function<String, java.util.List<String>> suggest,
                         Runnable closed) {
        TvKeyboard.open(this, L10n.t("Song, singer, film, folder or app"), initial, true, new TvKeyboard.Listener() {
            @Override
            public void onSearch(String text) {
                typed.accept(text);
            }

            @Override
            public boolean onPick(TvKeyboard.Hit hit) {
                if (hit.kind == TvKeyboard.SONG) view.playKey(hit.key);
                else if (hit.kind == TvKeyboard.SINGER || hit.kind == TvKeyboard.ALBUM || hit.kind == TvKeyboard.FOLDER) view.findNamed(hit.title);
                else return false;
                return true;
            }

            @Override
            public void onClosed() {
                closed.run();
            }
        });
    }

    @Override
    public boolean hasVideos() {
        return !scenes.isEmpty() && MyMusic.settings(this).getInt("music_show", 0) == 0;
    }

    // ---- background videos --------------------------------------------------------------------------

    /** Finds the videos under "TV Town Scenes" on the drive (in the background). */
    private void readScenes() {
        new Thread(() -> {
            List<Scene> found = new ArrayList<>();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                for (String vol : MediaStore.getExternalVolumeNames(this)) {
                    if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;
                    Uri base = MediaStore.Video.Media.getContentUri(vol);
                    String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DISPLAY_NAME};
                    try (Cursor c = getContentResolver().query(base, cols, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                            new String[]{"%TV Town Scenes%"}, null)) {
                        while (c != null && c.moveToNext()) {
                            String name = c.getString(2);
                            if (name == null || name.startsWith("._")) continue;
                            Scene s = new Scene();
                            s.uri = ContentUris.withAppendedId(base, c.getLong(0));
                            s.mood = moodOf(c.getString(1));
                            found.add(s);
                        }
                    } catch (Exception ignored) {
                        // no drive, or no permission
                    }
                }
            }
            handler.post(() -> {
                scenes.clear();
                scenes.addAll(found);
                scenesRead = true;
                if (playerShown) startScene(true);
            });
        }, "scenes").start();
    }

    /** "TV Town Scenes/Rain/" gives "rain"; videos straight in TV Town Scenes have no mood (they suit anything). */
    private static String moodOf(String rel) {
        if (rel == null) return "";
        String r = rel.replaceAll("/+$", "");
        int i = r.toLowerCase(Locale.ROOT).indexOf("tv town scenes");
        if (i < 0) return "";
        String rest = r.substring(i + "tv town scenes".length()).replaceAll("^/+", "");
        int slash = rest.indexOf('/');
        return (slash >= 0 ? rest.substring(0, slash) : rest).toLowerCase(Locale.ROOT);
    }

    /** The moods that suit a music folder, by the words in its name; empty means any. */
    static List<String> moodsFor(String folder) {
        String n = folder == null ? "" : folder.toLowerCase(Locale.ROOT);
        if (n.matches(".*(gazal|ghazal|sufi|qawwal).*")) return java.util.Arrays.asList("sufi", "night", "rain", "candle", "zen");
        if (n.matches(".*(bhajan|devotion|osho|medit|mantra|spiritual).*")) return java.util.Arrays.asList("zen", "sufi", "nature", "birds", "candle");
        if (n.matches(".*(hip-hop|hip hop|rap|rock|metal|electronic|edm|party|dance|remix|workout|club).*")) return java.util.Arrays.asList("party", "trance", "city");
        if (n.matches(".*(sad|heartbreak).*")) return java.util.Arrays.asList("rain", "night", "mist");
        if (n.matches(".*(romantic|love).*")) return java.util.Arrays.asList("flowers", "japan", "ocean", "sunset", "rain");
        if (n.matches(".*(silent|calm|soft|sleep|peace|slow).*")) return java.util.Arrays.asList("rain", "nature", "zen", "lake", "mist", "flowers");
        if (n.matches(".*(old|retro|classic|purane|oldies).*")) return java.util.Arrays.asList("alps train", "landscapes", "birds", "china", "sunset");
        if (n.matches(".*(unplug|acoustic).*")) return java.util.Arrays.asList("nature", "landscapes", "birds", "beach", "fire");
        if (n.matches(".*(new|latest|hits).*")) return java.util.Arrays.asList("party", "trance", "city", "ocean", "night");
        if (n.matches(".*(english|western|pop).*")) return java.util.Arrays.asList("ocean", "night", "landscapes", "city", "animals");
        return Collections.emptyList();
    }

    /** The videos for the folder playing: its own scene folder, else its moods, else all of them. */
    private List<Scene> poolFor(String folder) {
        List<Scene> own = new ArrayList<>(), suits = new ArrayList<>();
        String name = folder == null ? "" : folder.toLowerCase(Locale.ROOT);
        List<String> moods = moodsFor(folder);
        for (Scene s : scenes) {
            if (s.mood.equals(name)) own.add(s);
            else if (moods.contains(s.mood) || s.mood.isEmpty()) suits.add(s);
        }
        if (!own.isEmpty()) return own;
        if (!suits.isEmpty()) return suits;
        return scenes;
    }

    private final Runnable sceneTimer = () -> startScene(true);

    /** Fades in another video for the folder (or the same one again when there is only one). */
    private void startScene(boolean change) {
        if (!hasVideos()) return;
        handler.removeCallbacks(sceneTimer);
        if (!scenesRead || scenes.isEmpty() || !playerShown) return;
        if (!change && front >= 0 && layers[front].mp != null) return;
        List<Scene> pool = poolFor(sceneFolder);
        Scene pick = pool.get(new Random().nextInt(pool.size()));
        if (pool.size() > 1) while (pick.uri.equals(lastScene)) pick = pool.get(new Random().nextInt(pool.size()));
        lastScene = pick.uri;
        final int back = front < 0 ? 0 : 1 - front;
        Layer l = layers[back];
        l.tv.animate().cancel();
        l.tv.setAlpha(0);
        l.tv.bringToFront();
        view.bringToFront();
        Runnable shown = () -> l.tv.animate().alpha(1).setDuration(3000).withEndAction(() -> {
            int old = 1 - back;
            if (front == back) {
                layers[old].stop();
                layers[old].tv.setAlpha(0);
            }
        }).start();
        if (l.surface == null) {   // the surface is not ready yet: try again in a moment
            handler.postDelayed(() -> startScene(true), 300);
            return;
        }
        front = back;
        l.play(pick.uri, shown);
        handler.postDelayed(sceneTimer, SCENE_EVERY_MS);
    }

    private void stopScenes() {
        handler.removeCallbacks(sceneTimer);
        for (Layer l : layers) {
            if (l == null) continue;
            l.tv.animate().cancel();
            l.tv.setAlpha(0);
            l.stop();
        }
        front = -1;
    }
}
