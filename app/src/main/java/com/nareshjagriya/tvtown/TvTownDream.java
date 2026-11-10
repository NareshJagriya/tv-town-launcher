package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.view.Choreographer;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * TV Town's screensaver. With videos in a "TV Town Screensaver" folder on the drive (in folders by kind: Aquarium,
 * Coral Reef, Koi Pond…), the whole screen is one of them, silent, each played once and the next (shuffled across
 * every kind) fading in just before it ends, so nothing visibly repeats; the time and the kind's name sit small in a
 * corner and move to another corner every few minutes. Without them, the illustrated scenes change behind a glass panel with the time and date,
 * the panel drifting slowly round the screen. Either way nothing stays in one place for long.
 */
public class TvTownDream extends DreamService {

    private static final long FADE_MS = 2500;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.view.TextureView[] layers = new android.view.TextureView[2];
    private final android.media.MediaPlayer[] players = new android.media.MediaPlayer[2];
    private final android.view.Surface[] surfaces = new android.view.Surface[2];
    private final java.util.List<android.net.Uri> clips = new java.util.ArrayList<>();
    private final java.util.List<String> kinds = new java.util.ArrayList<>();
    private int next, front = -1;
    private ClockView clock;

    private static android.content.SharedPreferences store(Context c) {
        return c.getSharedPreferences("screensaver", Context.MODE_PRIVATE);
    }

    /** What TV Town's own screensaver shows: 0 your videos (else film pictures), 1 film pictures, 2 the scenes. */
    static int kind(Context c) {
        return store(c).getInt("kind", store(c).getBoolean("pictures", false) ? 1 : 0);
    }

    static void setKind(Context c, int k) {
        store(c).edit().putInt("kind", Math.floorMod(k, 3)).apply();
    }

    /** Whether any drive has videos in a "TV Town Screensaver" folder. */
    static boolean hasVideos(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 30 || !Perms.media(c)) return false;
        for (String vol : android.provider.MediaStore.getExternalVolumeNames(c)) {
            if (android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;
            try (android.database.Cursor q = c.getContentResolver().query(android.provider.MediaStore.Video.Media.getContentUri(vol),
                    new String[]{android.provider.MediaStore.Video.Media._ID}, android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{"%TV Town Screensaver%"}, null)) {
                if (q != null && q.moveToFirst()) return true;
            } catch (Exception ignored) {
                // not readable
            }
        }
        return false;
    }

    /** Home's newest titles, for the film pictures. */
    static void keepPictures(Context c, java.util.List<Tmdb.Title> titles) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Tmdb.Title t : titles) {
            String art = t.backdropLarge();
            if (art == null) continue;
            try {
                a.put(new org.json.JSONObject().put("u", art).put("n", t.title));
            } catch (org.json.JSONException ignored) {
                // left out
            }
            if (a.length() >= 20) break;
        }
        if (a.length() > 0) store(c).edit().putString("art", a.toString()).apply();
    }

    private static java.util.List<String[]> keptPictures(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(store(c).getString("art", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(new String[]{a.getJSONObject(i).getString("u"), a.getJSONObject(i).getString("n")});
        } catch (org.json.JSONException ignored) {
            // none kept
        }
        java.util.Collections.shuffle(out);
        return out;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        readClips();
        clock = new ClockView(this);
        java.util.List<String[]> art = keptPictures(this);
        int kind = kind(this);
        if (kind == 2) clips.clear();
        if (!art.isEmpty() && (kind == 1 || kind == 0 && clips.isEmpty())) {
            clips.clear();
            clock.art = art;
            setContentView(clock);
            return;
        }
        clock.videos = !clips.isEmpty();
        if (clips.isEmpty()) {
            setContentView(clock);
            return;
        }
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        for (int i = 0; i < 2; i++) {
            final int n = i;
            android.view.TextureView tv = new android.view.TextureView(this);
            tv.setAlpha(0);
            tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    surfaces[n] = new android.view.Surface(st);
                    if (n == 0) playNext();
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    release(n);
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
                }
            });
            layers[i] = tv;
            root.addView(tv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(clock, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
        release(0);
        release(1);
    }

    /**
     * The videos under "TV Town Screensaver" on any drive, all shuffled together (never the same kind twice in a row
     * when there is a choice).
     */
    private void readClips() {
        if (android.os.Build.VERSION.SDK_INT < 30
                || !Perms.media(this)) return;
        java.util.Map<String, java.util.List<android.net.Uri>> byKind = new java.util.LinkedHashMap<>();
        for (String vol : android.provider.MediaStore.getExternalVolumeNames(this)) {
            if (android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol)) continue;
            android.net.Uri base = android.provider.MediaStore.Video.Media.getContentUri(vol);
            String[] cols = {android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME};
            try (android.database.Cursor c = getContentResolver().query(base, cols,
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"%TV Town Screensaver%"}, null)) {
                while (c != null && c.moveToNext()) {
                    String name = c.getString(2);
                    if (name == null || name.startsWith("._")) continue;
                    String rel = c.getString(1).replaceAll("/+$", "");
                    String kind = rel.substring(rel.lastIndexOf('/') + 1);
                    if (kind.equalsIgnoreCase("TV Town Screensaver")) kind = "";
                    byKind.computeIfAbsent(kind, x -> new java.util.ArrayList<>())
                            .add(android.content.ContentUris.withAppendedId(base, c.getLong(0)));
                }
            } catch (Exception ignored) {
                // no drive, or not readable: the illustrated scenes
            }
        }
        for (java.util.List<android.net.Uri> l : byKind.values()) java.util.Collections.shuffle(l);
        String last = null;
        while (true) {   // deal from the kinds in turn, the fullest first, skipping the one just used
            String pick = null;
            for (java.util.Map.Entry<String, java.util.List<android.net.Uri>> e : byKind.entrySet()) {
                if (e.getValue().isEmpty() || e.getKey().equals(last) && byKind.size() > 1 && othersLeft(byKind, last)) continue;
                if (pick == null || e.getValue().size() > byKind.get(pick).size()) pick = e.getKey();
            }
            if (pick == null) break;
            clips.add(byKind.get(pick).remove(0));
            kinds.add(pick);
            last = pick;
        }
    }

    private static boolean othersLeft(java.util.Map<String, java.util.List<android.net.Uri>> m, String but) {
        for (java.util.Map.Entry<String, java.util.List<android.net.Uri>> e : m.entrySet()) if (!e.getKey().equals(but) && !e.getValue().isEmpty()) return true;
        return false;
    }

    /** The next video, faded in over the one showing; the one before is let go when the fade ends. */
    private void playNext() {
        handler.removeCallbacks(advance);
        if (clips.isEmpty()) return;
        int back = front < 0 ? 0 : 1 - front;
        if (surfaces[back] == null) {   // its surface is not ready yet
            handler.postDelayed(this::playNext, 300);
            return;
        }
        int i = next % clips.size();
        next++;
        release(back);
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        players[back] = mp;
        android.view.TextureView tv = layers[back];
        try {
            mp.setSurface(surfaces[back]);
            mp.setDataSource(this, clips.get(i));
            mp.setLooping(false);   // once each: the next fades in before the end
            mp.setVolume(0, 0);
            mp.setOnVideoSizeChangedListener((m, vw, vh) -> crop(tv, vw, vh));
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    tv.bringToFront();
                    clock.bringToFront();
                    tv.animate().alpha(1).setDuration(FADE_MS).withEndAction(() -> {
                        int old = 1 - back;
                        release(old);
                        layers[old].setAlpha(0);
                    }).start();
                    clock.kind = kinds.get(i);
                }
                return false;
            });
            mp.setOnErrorListener((m, what, extra) -> {
                handler.postDelayed(this::playNext, 500);
                return true;
            });
            mp.setOnPreparedListener(m -> {
                m.start();
                long d = m.getDuration();   // the next one fades in just before this one ends
                handler.removeCallbacks(advance);
                handler.postDelayed(advance, d > 0 ? Math.max(4000, d - FADE_MS - 300) : 20_000);
            });
            mp.prepareAsync();
            front = back;
        } catch (Exception e) {
            handler.postDelayed(this::playNext, 500);
            return;
        }
    }

    private final Runnable advance = this::playNext;

    /** Fills the screen, cropping what does not fit. */
    private static void crop(android.view.TextureView tv, int vw, int vh) {
        float w = tv.getWidth(), h = tv.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float s = Math.max(w / vw, h / vh);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(vw * s / w, vh * s / h, w / 2, h / 2);
        tv.setTransform(m);
    }

    private void release(int n) {
        if (players[n] != null) {
            try {
                players[n].release();
            } catch (Exception ignored) {
            }
            players[n] = null;
        }
    }

    private static final class ClockView extends View implements Choreographer.FrameCallback {
        boolean videos;      // over the videos: a small clock in a corner, no scenes
        java.util.List<String[]> art;   // film pictures, each with its name; else the videos or the scenes
        private ArtStage stage;
        private int artAt = -1;
        private long artSince;
        private static final long ART_MS = 30_000;
        String kind = "";    // the kind of video showing (L10n.t("Swiss Alps"))
        private Paint smallTime, smallKind;
        private final GlassBackdrop bg;
        private GlassKit kit;
        private Paint time, date;
        private final RectF panel = new RectF();
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());
        private SimpleDateFormat dateFmt = new SimpleDateFormat(Lang.datePattern(), Lang.dates());

        /** The date format, following the streaming country (it can change in Customize). */
        private SimpleDateFormat dateFormat() {
            if (!dateFmt.toPattern().equals(Lang.datePattern())) dateFmt = new SimpleDateFormat(Lang.datePattern(), Lang.dates());
            return dateFmt;
        }
        private final long start = SystemClock.uptimeMillis();
        private int vsyncs;

        ClockView(Context ctx) {
            super(ctx);
            bg = new GlassBackdrop(ctx);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            boolean fading = stage != null && stage.moving(SystemClock.uptimeMillis());
            if (++vsyncs % (fading ? 2 : art != null || videos ? 60 : 3) == 0) {   // 20 fps for the drift; else the clock once a second
                if (art == null) bg.step(SystemClock.uptimeMillis());
                invalidate();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bg.setSize(w, h);
            kit = new GlassKit(getContext(), bg, w / 1920f);
            NoirKit nk = new NoirKit(getContext(), w / 1920f);
            smallTime = nk.inter(88, 700, 0, 0xFFFFFFFF);
            smallTime.setShadowLayer(4 * w / 1920f, 0, 1 * w / 1920f, 0x8C000000);
            smallKind = nk.inter(24, 500, 0, 0xFFFFFFFF);
            smallKind.setShadowLayer(4 * w / 1920f, 0, 1 * w / 1920f, 0x8C000000);
            time = kit.font(150, 400);
            date = kit.font(30, 500);
            date.setLetterSpacing(0.2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            Frost.frame(this);   // selections glide (Frost.plate)
            long now = SystemClock.uptimeMillis();
            if (art != null) {
                if (stage == null) stage = new ArtStage(this);
                if (artAt < 0 || now - artSince > ART_MS) {
                    artAt = (artAt + 1) % art.size();
                    artSince = now;
                    stage.show(art.get(artAt)[0]);
                }
                stage.draw(c, now);
                kind = art.get(artAt)[1];
            }
            if (videos || art != null) {   // the time and the name, in a corner that changes every four minutes
                if (smallTime == null) return;
                float k = getWidth() / 1920f, m = 80 * k;
                int corner = (int) ((now - start) / 240_000) % 4;
                String t = timeFmt.format(new Date()), kd = kind;
                float tw = Math.max(smallTime.measureText(t), smallKind.measureText(kd));
                boolean right = corner == 1 || corner == 2, bottom = corner >= 2;
                float x = right ? getWidth() - m - tw : m, y = bottom ? getHeight() - m - 30 * k : m + 60 * k;
                c.drawText(t, x, y, smallTime);
                if (!kd.isEmpty()) c.drawText(kd, x + 4 * k, y + 40 * k, smallKind);
                nightDim(c);
                return;
            }
            bg.draw(c, now);
            if (kit == null) return;
            float k = kit.k, secs = (now - start) / 1000f;
            // A slow Lissajous drift over most of the screen (about 3 and 4 minutes per sweep).
            float pw = 720 * k, ph = 300 * k;
            float x = (getWidth() - pw) / 2f + (getWidth() - pw - 160 * k) / 2f * (float) Math.sin(secs * 2 * Math.PI / 190);
            float y = (getHeight() - ph) / 2f + (getHeight() - ph - 160 * k) / 2f * (float) Math.sin(secs * 2 * Math.PI / 250);
            panel.set(x, y, x + pw, y + ph);
            kit.panel(c, panel, 40 * k, 1, 0.05f);
            Date d = new Date();
            String t = timeFmt.format(d), ds = dateFormat().format(d).toUpperCase(Locale.ROOT);
            c.drawText(t, panel.centerX() - time.measureText(t) / 2, panel.top + 180 * k, time);
            c.drawText(ds, panel.centerX() - date.measureText(ds) / 2, panel.top + 245 * k, date);
            nightDim(c);
        }

        /** Softer late at night. */
        private void nightDim(Canvas c) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            if (hour >= 23 || hour < 6) c.drawColor(0x99000000);
        }
    }
}
