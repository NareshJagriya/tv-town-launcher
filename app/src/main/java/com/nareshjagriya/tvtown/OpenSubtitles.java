package com.nareshjagriya.tvtown;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Subtitles from OpenSubtitles.com (its official REST API), only when asked for. Searches first by the file's own
 * fingerprint (the OpenSubtitles hash: a match is made for this exact file, so its timing is right), then by the
 * film's TMDB id. English only. The API key is built in from ~/.tvtown/api.properties
 * (opensubtitles.apiKey); a username and password there too, when given, raise the daily download limit.
 */
final class OpenSubtitles {

    private OpenSubtitles() {
    }

    private static final String API = "https://api.opensubtitles.com/api/v1";
    private static final String AGENT = "TVTown v" + BuildConfig.VERSION_NAME;
    private static String token, base = API;

    static boolean available() {
        return !Keys.openSubtitles().isEmpty();
    }

    /** One subtitle to choose from. */
    static final class Result {
        int fileId, downloads;
        String language = "", release = "";
        boolean exact;   // made for this exact file
    }

    // ---- the file's fingerprint -------------------------------------------------------------------

    /**
     * The OpenSubtitles hash: the file's size plus the first and last 64 KB read as little-endian 64-bit numbers,
     * as 16 hex digits.
     */
    static String hash(Context ctx, Uri uri, long size) throws IOException {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("no file");
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel ch = in.getChannel();
                long length = size > 0 ? size : ch.size();
                return hash(ch, length);
            }
        }
    }

    static String hash(FileChannel ch, long length) throws IOException {
        int chunk = (int) Math.min(65536, length);
        long h = length;
        h += sum(ch, 0, chunk);
        h += sum(ch, Math.max(0, length - chunk), chunk);
        return String.format(Locale.US, "%016x", h);
    }

    private static long sum(FileChannel ch, long at, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        ch.position(at);
        while (b.hasRemaining() && ch.read(b) > 0) {
            // keep reading
        }
        b.flip();
        long s = 0;
        while (b.remaining() >= 8) s += b.getLong();
        return s;
    }

    // ---- searching -------------------------------------------------------------------------------

    /**
     * English subtitles for a film (or an episode: season and episode above 0), exact matches first, then by
     * downloads. Network; off the main thread.
     */
    static List<Result> search(@Nullable String hash, int tmdbId, boolean episode, int season, int number) throws Exception {
        List<Result> out = page(hash, tmdbId, episode, season, number, "en");
        out.sort((x, y) -> x.exact != y.exact ? (x.exact ? -1 : 1) : Integer.compare(y.downloads, x.downloads));
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    /** One page of results, in one language or (null) any. */
    private static List<Result> page(@Nullable String hash, int tmdbId, boolean episode, int season, int number,
                                     @Nullable String language) throws Exception {
        // The API answers only parameters in alphabetical order (anything else is redirected).
        java.util.TreeMap<String, String> p = new java.util.TreeMap<>();
        if (language != null) p.put("languages", language);
        if (hash != null) p.put("moviehash", hash);
        if (tmdbId > 0) {
            if (episode) {
                p.put("parent_tmdb_id", String.valueOf(tmdbId));
                if (season > 0) p.put("season_number", String.valueOf(season));
                if (number > 0) p.put("episode_number", String.valueOf(number));
            } else {
                p.put("tmdb_id", String.valueOf(tmdbId));
            }
        }
        StringBuilder q = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : p.entrySet()) q.append(q.length() == 0 ? "?" : "&").append(e.getKey()).append('=').append(e.getValue());
        JSONObject o = new JSONObject(request("GET", base + "/subtitles" + q, null));
        List<Result> out = new ArrayList<>();
        JSONArray data = o.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i) != null ? data.optJSONObject(i).optJSONObject("attributes") : null;
            JSONArray files = a != null ? a.optJSONArray("files") : null;
            if (files == null || files.length() == 0) continue;
            Result r = new Result();
            r.fileId = files.optJSONObject(0).optInt("file_id");
            String code = Tracks.code(a.optString("language"));
            r.language = code != null ? code : a.optString("language");
            r.release = a.optString("release", files.optJSONObject(0).optString("file_name", ""));
            r.downloads = a.optInt("download_count");
            r.exact = a.optBoolean("moviehash_match");
            if (r.fileId > 0) out.add(r);
        }
        return out;
    }

    /** The subtitle file itself (SubRip text). Logs in first when a username and password are set. */
    static byte[] download(int fileId) throws Exception {
        if (token == null && !BuildConfig.OPENSUBTITLES_USERNAME.isEmpty()) login();
        JSONObject o = new JSONObject(request("POST", base + "/download", new JSONObject().put("file_id", fileId).toString()));
        String link = o.optString("link", "");
        if (!link.startsWith("https://")) {
            throw new IOException(o.optString("message", "No download link"));
        }
        return Net.get(link, null, 20_000, 1);
    }

    private static void login() {
        try {
            JSONObject body = new JSONObject().put("username", BuildConfig.OPENSUBTITLES_USERNAME)
                    .put("password", BuildConfig.OPENSUBTITLES_PASSWORD);
            JSONObject o = new JSONObject(request("POST", API + "/login", body.toString()));
            token = o.optString("token", null);
            String host = o.optString("base_url", "");
            if (host.matches("[a-z0-9.-]+\\.opensubtitles\\.com")) base = "https://" + host + "/api/v1";
        } catch (Exception ignored) {
            // without logging in: the smaller daily limit
        }
    }

    private static String request(String method, String url, @Nullable String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Api-Key", Keys.openSubtitles());
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; in != null && (n = in.read(buf)) > 0 && out.size() < 2_000_000; ) out.write(buf, 0, n);
            String text = out.toString("UTF-8");
            if (status >= 400) {
                String msg = "";
                try {
                    msg = new JSONObject(text).optString("message", "");
                } catch (Exception ignored) {
                    // not JSON
                }
                throw new IOException(status == 406 ? "Daily download limit reached"
                        : status == 429 || status == 403 ? "OpenSubtitles is busy, try again in a few minutes"
                        : msg.isEmpty() ? "Error " + status : msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }
}
