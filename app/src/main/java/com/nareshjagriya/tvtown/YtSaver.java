package com.nareshjagriya.tvtown;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Saves YouTube songs and videos to the drive in the background, one at a time: Audio as an MP3 with its name, singer
 * and picture inside, Video as an MP4 of up to 1080p. Personal build only: NewPipeExtractor is GPL-3.
 */
final class YtSaver {

    private static final int MP3_KBPS = 192, MAX_HEIGHT = 1080, PLAYLIST_MAX = 300;
    private static final long PIECE = 5_000_000L;
    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /** What was found: one video, or a playlist's videos. */
    static final class Found {
        String title = "", by = "";
        final List<String> urls = new ArrayList<>();
    }

    private static final class Job {
        String url;
        boolean video;
        File folder;
    }

    private static final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean started, ready;
    private static int batch, finished;

    private YtSaver() {
    }

    // ---- the folders ---------------------------------------------------------------------------------

    @Nullable
    static File folder(Context c, boolean video) {
        String p = c.getSharedPreferences("save", Context.MODE_PRIVATE).getString(video ? "video_folder" : "audio_folder", null);
        if (p != null && new File(p).isDirectory()) return new File(p);   // a chosen folder since removed: back to the usual one
        File drive = SaveFolderActivity.drive(c);   // never asks: these unless changed in Customize
        return drive == null ? null : new File(drive, video ? "Movies/Downloaded Videos" : "Music/Downloaded Songs");
    }

    static void setFolder(Context c, boolean video, File f) {
        c.getSharedPreferences("save", Context.MODE_PRIVATE).edit().putString(video ? "video_folder" : "audio_folder", f.getPath()).apply();
    }

    static final int[] BUTTONS = {android.view.KeyEvent.KEYCODE_PROG_RED, android.view.KeyEvent.KEYCODE_PROG_GREEN,
            android.view.KeyEvent.KEYCODE_PROG_YELLOW, android.view.KeyEvent.KEYCODE_PROG_BLUE};
    static final String[] BUTTON_NAMES = {"Red", "Green", "Yellow", "Blue"};

    /** Which of the coloured buttons saves: 0 red (the usual), 1 green, 2 yellow, 3 blue. */
    static int button(Context c) {
        return c.getSharedPreferences("save", Context.MODE_PRIVATE).getInt("button", 0);
    }

    static void setButton(Context c, int i) {
        c.getSharedPreferences("save", Context.MODE_PRIVATE).edit().putInt("button", i).apply();
    }

    /** The small "press to save" note when a YouTube video is paused. */
    static boolean offerOnPause(Context c) {
        return false;   // removed: the note on every pause got in the way; OK twice and the phone save instead
    }

    static void setOfferOnPause(Context c, boolean on) {
        c.getSharedPreferences("save", Context.MODE_PRIVATE).edit().putBoolean("offer_on_pause", on).apply();
    }

    /** Inside this folder? For My Music's Downloaded and My Movies' Videos. */
    static boolean inside(@Nullable File folder, @Nullable String path) {
        return folder != null && path != null && path.toLowerCase(java.util.Locale.ROOT).startsWith(folder.getPath().toLowerCase(java.util.Locale.ROOT) + "/");
    }

    /** A saved video's picture, for its card in My Movies. */
    @Nullable
    static String picture(Context c, String fileName) {
        return c.getSharedPreferences("save_pictures", Context.MODE_PRIVATE).getString(fileName, null);
    }

    // ---- finding -------------------------------------------------------------------------------------

    private static synchronized void init() {
        if (ready) return;
        NewPipe.init(new Web(), new Localization("en", "IN"), new ContentCountry("IN"));
        ready = true;
    }

    private static StreamingService yt() {
        init();
        return ServiceList.YouTube;
    }

    /** A pasted link: a video or a playlist. done runs on the main thread, with null when it can't be read. */
    static void read(String link, Consumer<Found> done) {
        background(() -> {
            String url = link.trim();
            StreamingService s = yt();
            if (s.getLinkTypeByUrl(url) == StreamingService.LinkType.PLAYLIST) return playlist(s, url);
            StreamInfo i = StreamInfo.getInfo(s, url);
            Found f = new Found();
            f.title = i.getName();
            f.by = person(i.getUploaderName());
            f.urls.add(url);
            return f;
        }, done);
    }

    private static Found playlist(StreamingService s, String url) throws Exception {
        PlaylistInfo p = PlaylistInfo.getInfo(s, url);
        Found f = new Found();
        f.title = p.getName();
        for (StreamInfoItem i : p.getRelatedItems()) f.urls.add(i.getUrl());
        Page next = p.getNextPage();
        while (Page.isValid(next) && f.urls.size() < PLAYLIST_MAX) {
            ListExtractor.InfoItemsPage<StreamInfoItem> more = PlaylistInfo.getMoreItems(s, url, next);
            for (StreamInfoItem i : more.getItems()) f.urls.add(i.getUrl());
            next = more.getNextPage();
        }
        f.by = f.urls.size() + " videos";
        return f;
    }

    /**
     * What's playing: YouTube shares only its title, channel and length, so the video is found by those. Exact names
     * aren't always first (often 4th to 13th, under re-uploads), so every result is scored, then wider searches are
     * tried: title and channel, title, the short title with the channel, the short title, a second page of each.
     * Nothing that's merely similar is taken: a wrong song is worse than saying it wasn't found.
     */
    static void find(String title, String channel, long seconds, Consumer<Found> done) {
        background(() -> {
            String by = person(channel), brief = Songs.shortTitle(title).replace("…", "").trim();
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            queries.add((title + " " + channel).trim());
            queries.add(title);
            queries.add((brief + " " + by).trim());
            queries.add(brief);
            StreamInfoItem best = null;
            int bestScore = -1;
            StreamingService yt = yt();
            for (String q : queries) {
                try {
                    SearchExtractor e = yt.getSearchExtractor(q, java.util.Collections.singletonList("videos"), "");
                    e.fetchPage();
                    ListExtractor.InfoItemsPage<InfoItem> page = e.getInitialPage();
                    for (int p = 0; p < 2 && page != null; p++) {
                        for (InfoItem it : page.getItems()) {
                            if (!(it instanceof StreamInfoItem)) continue;
                            StreamInfoItem s = (StreamInfoItem) it;
                            int score = score(s, title, by, seconds);
                            if (score > bestScore) {
                                bestScore = score;
                                best = s;
                            }
                        }
                        if (bestScore >= SURE) break;
                        page = p == 0 && page.hasNextPage() ? e.getPage(page.getNextPage()) : null;
                    }
                } catch (Exception e) {
                    android.util.Log.w("YtSaver", "search: " + q, e);   // the next, wider search may still work
                }
                if (bestScore >= SURE) break;
            }
            if (best == null || bestScore < ENOUGH) return null;
            Found f = new Found();
            f.title = best.getName();
            f.by = person(best.getUploaderName());
            f.urls.add(best.getUrl());
            return f;
        }, done);
    }

    private static final int SURE = 8, ENOUGH = 4;

    /** The same name 4, a close name 2; the same length 4; the same channel 2. */
    private static int score(StreamInfoItem s, String title, String by, long seconds) {
        String name = SearchMatch.plain(s.getName()), want = SearchMatch.plain(title);
        int score = name.equals(want) ? 4 : !name.isEmpty() && (name.contains(want) || want.contains(name)) ? 2 : 0;
        if (seconds > 0 && Math.abs(s.getDuration() - seconds) <= 3) score += 4;
        if (!by.isEmpty() && SearchMatch.plain(person(s.getUploaderName())).equals(SearchMatch.plain(by))) score += 2;
        return score;
    }

    /** One search result, for the keyboard and the phone. */
    static final class Result {
        String title, by, url, picture;
        long seconds;
    }

    /** What YouTube lists for these words. Network: not on the main thread. */
    static List<Result> search(String words) throws Exception {
        SearchExtractor e = yt().getSearchExtractor(words, java.util.Collections.singletonList("videos"), "");
        e.fetchPage();
        List<Result> out = new ArrayList<>();
        for (InfoItem it : e.getInitialPage().getItems()) {
            if (!(it instanceof StreamInfoItem) || out.size() >= 15) continue;
            StreamInfoItem s = (StreamInfoItem) it;
            Result r = new Result();
            r.title = s.getName();
            r.by = person(s.getUploaderName());
            r.url = s.getUrl();
            r.seconds = s.getDuration();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]v=([\\w-]{11})").matcher(r.url);
            if (m.find()) r.picture = "https://i.ytimg.com/vi/" + m.group(1) + "/mqdefault.jpg";
            out.add(r);
        }
        return out;
    }

    static String clock(long seconds) {
        if (seconds <= 0) return "";
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private interface Work {
        Found run() throws Exception;
    }

    private static void background(Work w, Consumer<Found> done) {
        new Thread(() -> {
            Found f = null;
            try {
                f = w.run();
            } catch (Throwable e) {
                android.util.Log.w("YtSaver", "find", e);
            }
            final Found out = f;
            main.post(() -> done.accept(out));
        }, "save-find").start();
    }

    // ---- saving --------------------------------------------------------------------------------------

    static synchronized void add(Context c, Found f, boolean video, File folder) {
        Context app = c.getApplicationContext();
        batch += f.urls.size();
        for (String u : f.urls) {
            Job j = new Job();
            j.url = u;
            j.video = video;
            j.folder = folder;
            queue.add(j);
        }
        if (started) return;
        started = true;
        Thread t = new Thread(() -> worker(app), "save");
        t.setDaemon(true);
        t.start();
    }

    /** Half-finished files from a save that was cut off (the TV switched off, or an update). */
    private static void clearLeftovers(File folder) {
        File[] old = folder.listFiles((d, n) -> n.startsWith(".") && n.contains(".part."));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static void worker(Context c) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        File cleared = null;
        while (true) {
            Job j;
            try {
                j = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (!j.folder.equals(cleared)) {   // only this saver writes here, one at a time
                clearLeftovers(j.folder);
                cleared = j.folder;
            }
            String shown = null;
            boolean ok = false;
            for (int attempt = 0; attempt < 2 && !ok; attempt++) {   // web addresses go stale: a second try gets fresh ones
                try {
                    StreamInfo i = StreamInfo.getInfo(yt(), j.url);
                    shown = i.getName();
                    if (attempt == 0) HomeKeyService.note("Saving: " + shown + count(), 3500);
                    long at = android.os.SystemClock.elapsedRealtime();
                    File done = j.video ? saveVideo(j, i) : saveAudio(j, i);
                    android.util.Log.i("YtSaver", "saved " + done + " " + done.length() + " bytes in " + (android.os.SystemClock.elapsedRealtime() - at) + "ms");
                    if (j.video) c.getSharedPreferences("save_pictures", Context.MODE_PRIVATE).edit()
                            .putString(done.getName(), "https://i.ytimg.com/vi/" + i.getId() + "/hqdefault.jpg").apply();
                    MediaScannerConnection.scanFile(c, new String[]{done.getPath()}, null, null);
                    ok = true;
                } catch (Throwable e) {
                    android.util.Log.w("YtSaver", "save " + j.url, e);
                }
            }
            synchronized (YtSaver.class) {
                finished++;
                boolean last = queue.isEmpty();
                String label = shown == null ? "this video" : shown;
                HomeKeyService.note((ok ? "Saved: " : "Couldn't save: ") + label + (last ? "" : count()), ok ? 3500 : 6000);
                if (last) batch = finished = 0;
            }
        }
    }

    private static synchronized String count() {
        return batch > 1 ? "  ·  " + Math.min(finished + 1, batch) + " of " + batch : "";
    }

    private static String fileName(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 100) s = s.substring(0, 100).trim();
        s = s.replaceAll("^\\.+|\\.+$", "");
        return s.isEmpty() ? "YouTube" : s;
    }

    private static String person(@Nullable String channel) {
        return channel == null ? "" : channel.replaceFirst(" - Topic$", "").trim();
    }

    private static AudioStream bestAudio(StreamInfo i) throws IOException {
        AudioStream best = null;
        for (AudioStream s : i.getAudioStreams()) {
            if (s.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A && s.isUrl() && (best == null || s.getAverageBitrate() > best.getAverageBitrate())) best = s;
        }
        if (best == null) throw new IOException("no audio");
        return best;
    }

    private static File saveAudio(Job j, StreamInfo i) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        j.folder.mkdirs();
        String name = fileName(i.getName());
        File out = new File(j.folder, name + ".mp3");
        if (out.exists()) return out;
        File raw = new File(j.folder, "." + name + ".part.m4a"), part = new File(j.folder, "." + name + ".part.mp3");
        try {
            fetch(bestAudio(i).getContent(), raw);
            try (FileOutputStream mp3 = new FileOutputStream(part)) {
                mp3.write(id3(i));
                toMp3(raw, mp3);
            }
            if (!part.renameTo(out)) throw new IOException("rename");
            return out;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static File saveVideo(Job j, StreamInfo i) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        j.folder.mkdirs();
        String name = fileName(i.getName());
        File out = new File(j.folder, name + ".mp4");
        if (out.exists()) return out;
        VideoStream best = null;
        int bestH = 0;
        for (VideoStream s : i.getVideoOnlyStreams()) {
            String codec = s.getCodec();
            int h = height(s.getResolution());
            if (s.getFormat() == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 && s.isUrl() && codec != null && codec.startsWith("avc1") && h <= MAX_HEIGHT && h > bestH) {
                best = s;
                bestH = h;
            }
        }
        if (best == null) throw new IOException("no mp4 video");
        File v = new File(j.folder, "." + name + ".part.v"), a = new File(j.folder, "." + name + ".part.a"), part = new File(j.folder, "." + name + ".part.mp4");
        try {
            fetch(best.getContent(), v);
            fetch(bestAudio(i).getContent(), a);
            join(v, a, part);
            if (!part.renameTo(out)) throw new IOException("rename");
            return out;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            v.delete();
            //noinspection ResultOfMethodCallIgnored
            a.delete();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static int height(@Nullable String resolution) {
        if (resolution == null) return 0;
        int p = resolution.indexOf('p');
        try {
            return p > 0 ? Integer.parseInt(resolution.substring(0, p)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** In pieces, because YouTube slows down one long download. */
    private static void fetch(String url, File to) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (FileOutputStream f = new FileOutputStream(to)) {
            long at = 0;
            while (true) {
                long end = at + PIECE - 1;
                HttpURLConnection c = (HttpURLConnection) new URL(url + "&range=" + at + "-" + end).openConnection();
                c.setConnectTimeout(20_000);
                c.setReadTimeout(40_000);
                c.setRequestProperty("User-Agent", AGENT);
                int code = c.getResponseCode();
                if (code == 416 && at > 0) return;
                if (code >= 400) throw new IOException("http " + code);
                long got = 0;
                try (InputStream in = c.getInputStream()) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        f.write(buf, 0, n);
                        got += n;
                    }
                }
                at += got;
                if (got < PIECE) return;
            }
        }
    }

    /** Picture and sound come separately above 360p; this puts them in one MP4, without changing either. */
    private static void join(File video, File audio, File to) throws Exception {
        MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
        MediaMuxer m = null;
        try {
            v.setDataSource(video.getPath());
            a.setDataSource(audio.getPath());
            v.selectTrack(0);
            a.selectTrack(0);
            m = new MediaMuxer(to.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = m.addTrack(v.getTrackFormat(0)), at = m.addTrack(a.getTrackFormat(0));
            m.start();
            ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long tv = v.getSampleTime(), ta = a.getSampleTime();
                if (tv < 0 && ta < 0) break;
                boolean useV = ta < 0 || (tv >= 0 && tv <= ta);
                MediaExtractor e = useV ? v : a;
                buf.clear();
                int n = e.readSampleData(buf, 0);
                if (n < 0) break;
                info.set(0, n, e.getSampleTime(), (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                m.writeSampleData(useV ? vt : at, buf, info);
                e.advance();
            }
            m.stop();
        } finally {
            v.release();
            a.release();
            if (m != null) m.release();
        }
    }

    /** Sound to MP3: the TV's software decoder (it can't take the hardware from what is playing), then LAME. */
    private static void toMp3(File in, FileOutputStream out) throws Exception {
        MediaExtractor x = new MediaExtractor();
        MediaCodec d = null;
        long lame = 0;
        try {
            x.setDataSource(in.getPath());
            x.selectTrack(0);
            android.media.MediaFormat f = x.getTrackFormat(0);
            try {
                d = MediaCodec.createByCodecName("c2.android.aac.decoder");
            } catch (Exception e) {
                d = MediaCodec.createDecoderByType(f.getString(android.media.MediaFormat.KEY_MIME));
            }
            d.configure(f, null, null, 0);
            d.start();
            int rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[8192];
            byte[] mp3 = new byte[16384];
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    int i = d.dequeueInputBuffer(10_000);
                    if (i >= 0) {
                        int n = x.readSampleData(d.getInputBuffer(i), 0);
                        if (n < 0) {
                            d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            d.queueInputBuffer(i, 0, n, x.getSampleTime(), 0);
                            x.advance();
                        }
                    }
                }
                int o = d.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = d.getOutputFormat();
                    rate = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                } else if (o >= 0) {
                    if (info.size > 0) {
                        if (lame == 0) {
                            lame = Mp3Lame.open(rate, ch, MP3_KBPS);
                            if (lame == 0) throw new IOException("lame");
                        }
                        ByteBuffer b = d.getOutputBuffer(o);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        int samples = info.size / 2;
                        if (samples > pcm.length) pcm = new short[samples];
                        b.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, samples);
                        int frames = samples / ch, need = frames * 5 / 4 + 7200;
                        if (need > mp3.length) mp3 = new byte[need];
                        int w = Mp3Lame.encode(lame, pcm, frames, ch, mp3);
                        if (w < 0) throw new IOException("lame " + w);
                        out.write(mp3, 0, w);
                    }
                    d.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (lame == 0) throw new IOException("no sound");
            int w = Mp3Lame.flush(lame, mp3);
            if (w > 0) out.write(mp3, 0, w);
        } finally {
            if (d != null) d.release();
            x.release();
            if (lame != 0) Mp3Lame.close(lame);
        }
    }

    // ---- the name, singer and picture inside the MP3 -----------------------------------------------------

    private static byte[] id3(StreamInfo i) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frame(frames, "TIT2", text(Songs.shortTitle(i.getName())));
        frame(frames, "TPE1", text(person(i.getUploaderName())));
        byte[] art = picture(i);
        if (art != null) {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            p.write(0);
            p.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
            p.write(0);
            p.write(3);
            p.write(0);
            p.write(art);
            frame(frames, "APIC", p.toByteArray());
        }
        int n = frames.size();
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write("ID3".getBytes(StandardCharsets.ISO_8859_1));
        t.write(new byte[]{3, 0, 0, (byte) (n >> 21 & 0x7f), (byte) (n >> 14 & 0x7f), (byte) (n >> 7 & 0x7f), (byte) (n & 0x7f)});
        frames.writeTo(t);
        return t.toByteArray();
    }

    private static byte[] text(String s) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(1);
        b.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
        b.write(s.getBytes(StandardCharsets.UTF_16LE));
        return b.toByteArray();
    }

    private static void frame(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        int n = body.length;
        out.write(id.getBytes(StandardCharsets.ISO_8859_1));
        out.write(new byte[]{(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n, 0, 0});
        out.write(body);
    }

    @Nullable
    private static byte[] picture(StreamInfo i) {
        try {
            String id = i.getId();
            HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] t = new byte[16384];
                for (int n; (n = in.read(t)) > 0; ) b.write(t, 0, n);
            }
            return b.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** How the library reaches YouTube. */
    private static final class Web extends Downloader {
        @Override
        public Response execute(Request r) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(r.url()).openConnection();
            c.setRequestMethod(r.httpMethod());
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", AGENT);
            for (Map.Entry<String, List<String>> e : r.headers().entrySet()) {
                for (String v : e.getValue()) c.addRequestProperty(e.getKey(), v);
            }
            if (r.dataToSend() != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(r.dataToSend());
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] t = new byte[16384];
                for (int n; (n = in.read(t)) > 0; ) b.write(t, 0, n);
            }
            return new Response(code, c.getResponseMessage(), c.getHeaderFields(), b.toString("UTF-8"), c.getURL().toString());
        }
    }
}
