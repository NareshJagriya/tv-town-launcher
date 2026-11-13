package com.nareshjagriya.tvtown;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * My Movies (MyMoviesView). Asks once for permission to read the drive, and plays files in VLC when it is installed
 * (it starts at the resume point and reports where you stopped), else in the TV's own player.
 */
public class MyMoviesActivity extends Activity implements MyMoviesView.Listener {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    static final String VLC = "org.videolan.vlc";
    private static final int ASK_STORAGE = 1, PLAY = 2;

    private MyMoviesView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MyMovies.Video playing;
    private long playFrom, playStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_movies);
        view = findViewById(R.id.my_movies);
        view.setListener(this);
        view.requestFocus();
        boolean granted = Perms.media(this);
        view.setPermission(granted);
        if (!granted) requestPermissions(Perms.media(), ASK_STORAGE);
        String resume = getIntent().getStringExtra(EXTRA_RESUME);
        if (granted && resume != null) resume(resume, getIntent().getLongExtra(EXTRA_FROM, -1), getIntent().getStringExtra(EXTRA_TITLE));
        String named = getIntent().getStringExtra(EXTRA_OPEN);
        if (granted && named != null) view.openNamed(named);
    }

    /** From the keyboard elsewhere: this film's page. */
    static final String EXTRA_OPEN = "open_named";

    static final String EXTRA_RESUME = "resume", EXTRA_FROM = "from", EXTRA_TITLE = "title";
    private boolean closeAfter;   // opened from Continue watching: back to the home screen when VLC closes

    /** Continue watching on the home screen: finds the file (by its key) and plays it on in VLC. */
    private void resume(String key, long fromMs, String title) {
        closeAfter = true;
        new Thread(() -> {
            MyMovies.Video found = null;
            for (MyMovies.Video v : MyMovies.scan(this)) if (v.key().equals(key)) found = v;
            MyMovies.Video f = found;
            handler.post(() -> {
                if (f != null) onPlay(f, fromMs, title);
                else closeAfter = false;
            });
        }, "resume").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == ASK_STORAGE) view.setPermission(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.setRunning(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (TvKeyboard.back()) return;
        if (!view.back()) super.onBackPressed();
    }

    /** VLC with the resume point (it reports back where you stopped); else whatever plays videos. */
    @Override
    public void onPlay(MyMovies.Video video, long fromMs, String title) {
        playing = video;
        playFrom = Math.max(0, fromMs);
        playStarted = SystemClock.elapsedRealtime();
        Intent vlc = new Intent(Intent.ACTION_VIEW).setDataAndType(video.uri, "video/*")
                .setComponent(new ComponentName(VLC, "org.videolan.vlc.gui.video.VideoPlayerActivity"))
                .putExtra("title", title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // A known place: from there. -1: started before but the place never came back: VLC's own memory of it.
        if (fromMs >= 0) vlc.putExtra("position", fromMs).putExtra("from_start", fromMs == 0);
        else vlc.putExtra("from_start", false);
        String subs = Tracks.downloaded(this, video);
        if (subs != null) vlc.putExtra("subtitles_location", subs);
        if (Services.installed(this, VLC)) {
            try {
                Toast.makeText(this, getString(R.string.opening_in, "VLC"), Toast.LENGTH_SHORT).show();
                startActivityForResult(vlc, PLAY);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall through to the TV's player
            }
        }
        Intent any = new Intent(Intent.ACTION_VIEW).setDataAndType(video.uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(any, PLAY);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * VLC's answer: extra_position and extra_duration in ms. When it comes back at 0 after playing about as long as
     * what was left, the video ran to the end.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PLAY || playing == null) return;
        long pos = data != null ? data.getLongExtra("extra_position", -1) : -1;
        long dur = data != null ? data.getLongExtra("extra_duration", -1) : -1;
        if (dur <= 0) dur = playing.durationMs;
        long watchedFor = SystemClock.elapsedRealtime() - playStarted;
        if (pos <= 0 && dur > 0 && watchedFor > (dur - playFrom) * 0.85) pos = dur;   // ran to the end
        else if (pos <= 0) {   // VLC gave no place: never lose the one we had; after a real watch, about where it stopped
            pos = watchedFor > 60_000 ? playFrom + watchedFor - 15_000 : -1;
            if (pos > 0 && dur > 0) pos = Math.min(pos, dur - 1000);
        }
        if (pos >= 0) view.played(playing, pos, dur);
        else view.refresh();
        playing = null;
        if (closeAfter) finish();
    }

    @Override
    public void onTrailer(Tmdb.Title title) {
        new Thread(() -> {
            Intent i = DeepLinks.trailer(this, title);
            handler.post(() -> {
                if (i == null) return;
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
        }, "trailer").start();
    }

    /** Fix match by name: a one-line box with the TV's keyboard, and a QR code to type on a phone. */
    @Override
    public void onTypeName(String current) {
        TextBox.askName(this, getString(R.string.type_a_name_title), "", current, getString(R.string.search), text -> {
            if (text != null) view.fixSearch(text);
        });
    }

    @Override
    public void onAskName(String title, String current, java.util.function.Consumer<String> done) {
        TextBox.askName(this, title, "", current, getString(R.string.done), done);
    }

    @Override
    public void onTypeTag(java.util.function.Consumer<String> done) {
        TextBox.askName(this, getString(R.string.new_tag_title), "", "", getString(R.string.add), done);
    }

    @Override
    public void onSearch(String initial, java.util.function.Consumer<String> typed,
                         java.util.function.Function<String, java.util.List<String>> suggest, Runnable closed) {
        TvKeyboard.open(this, L10n.t("Film, series, song, singer or app"), initial, true, new TvKeyboard.Listener() {
            @Override
            public void onSearch(String text) {
                typed.accept(text);
            }

            @Override
            public boolean onPick(TvKeyboard.Hit hit) {
                if (hit.kind != TvKeyboard.FILM && hit.kind != TvKeyboard.SERIES) return false;
                view.openNamed(hit.title);
                return true;
            }

            @Override
            public void onClosed() {
                closed.run();
            }
        });
    }

}
