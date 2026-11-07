package com.nareshjagriya.tvtown;

import android.content.Context;
import android.media.MediaScannerConnection;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The phone remote's Files tab: songs, films and subtitles on the plugged-in drive, to and from the phone. Only those
 * kinds of file, only on the drive, and never in its hidden or system folders.
 */
final class PhoneFiles {

    private static final Set<String> MEDIA = new HashSet<>(Arrays.asList("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma",
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "wmv", "srt", "ass", "vtt", "lrc"));
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("android", "lost.dir"));

    private PhoneFiles() {
    }

    static boolean isMedia(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && MEDIA.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** A place on the drive from the path the phone sent, or null when it isn't one we show. Blank: the top of the drive. */
    @Nullable
    static File place(File drive, String rel) throws IOException {
        File root = drive.getCanonicalFile();
        if (rel == null || rel.isEmpty()) return root;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.startsWith(".") || HIDDEN.contains(part.toLowerCase(Locale.ROOT))) return null;
        }
        File f = new File(root, rel).getCanonicalFile();
        return f.getPath().startsWith(root.getPath() + "/") ? f : null;
    }

    /** A plain name for a new file or folder, or null. */
    @Nullable
    static String cleanName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "").trim();
        return n.isEmpty() || n.startsWith(".") || n.length() > 200 ? null : n;
    }

    private static String relative(File drive, File f) throws IOException {
        String root = drive.getCanonicalPath(), p = f.getCanonicalPath();
        return p.equals(root) ? "" : p.startsWith(root + "/") ? p.substring(root.length() + 1) : "";
    }

    /** What's in a folder, and how much room the drive has. */
    static String list(Context c, String rel) throws Exception {
        File drive = DriveStore.anyDrive(c);
        JSONObject o = new JSONObject();
        if (drive == null) return o.put("none", true).toString();
        File dir = place(drive, rel);
        if (dir == null || !dir.isDirectory()) return o.put("gone", true).toString();
        JSONArray folders = new JSONArray(), files = new JSONArray();
        File[] all = dir.listFiles();
        if (all != null) {
            Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : all) {
                String n = f.getName();
                if (n.startsWith(".") || n.startsWith("._")) continue;
                if (f.isDirectory()) {
                    if (!HIDDEN.contains(n.toLowerCase(Locale.ROOT))) folders.put(n);
                } else if (isMedia(n)) files.put(new JSONObject().put("name", n).put("size", f.length()));
            }
        }
        return o.put("dir", relative(drive, dir)).put("free", drive.getUsableSpace()).put("total", drive.getTotalSpace())
                .put("folders", folders).put("files", files).toString();
    }

    /** Where downloads go now. */
    static String saveFolders(Context c) throws Exception {
        File drive = DriveStore.anyDrive(c);
        JSONObject o = new JSONObject();
        if (drive == null) return o.put("none", true).toString();
        File a = YtSaver.folder(c, false), v = YtSaver.folder(c, true);
        return o.put("audio", a == null ? "" : relative(drive, a)).put("video", v == null ? "" : relative(drive, v)).toString();
    }

    @Nullable
    static String setSaveFolder(Context c, String rel, boolean video) throws Exception {
        File drive = DriveStore.anyDrive(c);
        File dir = drive == null ? null : place(drive, rel);
        if (dir == null || !dir.isDirectory()) return null;
        YtSaver.setFolder(c, video, dir);
        return "ok";
    }

    @Nullable
    static String makeFolder(Context c, String rel, String name) throws Exception {
        File drive = DriveStore.anyDrive(c);
        File dir = drive == null ? null : place(drive, rel);
        String clean = cleanName(name);
        if (dir == null || clean == null) return null;
        File f = new File(dir, clean);
        return f.isDirectory() || f.mkdir() ? "ok" : null;
    }

    /** From the drive to the phone. */
    static boolean send(Context c, String rel, OutputStream out) throws Exception {
        File drive = DriveStore.anyDrive(c);
        File f = drive == null ? null : place(drive, rel);
        if (f == null || !f.isFile() || !isMedia(f.getName())) return false;
        String ascii = f.getName().replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20")
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        out.flush();
        return true;
    }

    /**
     * From the phone to the drive. It lands under a hidden name and takes its real one only when all of it has
     * arrived, so a dropped connection never leaves half a film. An existing file is never replaced.
     * The answer is ok, room (not enough space) or a reason the phone can show.
     */
    static String receive(Context c, String rel, String name, long length, InputStream in) throws Exception {
        File drive = DriveStore.anyDrive(c);
        if (drive == null) return "nodrive";
        File dir = place(drive, rel);
        String clean = cleanName(name);
        if (dir == null || !dir.isDirectory() || clean == null) return "badplace";
        if (!isMedia(clean)) return "badtype";
        if (length <= 0) return "empty";
        if (drive.getUsableSpace() < length + 16 * 1024 * 1024) return "room";
        File part = new File(dir, ".tvtown-" + System.currentTimeMillis() + ".part");
        long got = 0;
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            while (got < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - got));
                if (n < 0) break;
                out.write(buf, 0, n);
                got += n;
            }
        } finally {
            if (got < length) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
        if (got < length) return "cut";
        File target = free(dir, clean);
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return "failed";
        }
        MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{target.getPath()}, null, null);
        return "ok";
    }

    /** "Song.mp3", or "Song (2).mp3" when that's taken. */
    private static File free(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
