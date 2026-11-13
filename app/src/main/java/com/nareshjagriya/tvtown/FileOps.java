package com.nareshjagriya.tvtown;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rename, move, copy and delete for My Movies and My Music: a file and the files that belong to it (subtitles
 * named after a film, lyrics named after a song). Everything stays on the same drive's folders; the TV's media list
 * is told at once, so My Movies and My Music show the change.
 */
final class FileOps {

    private FileOps() {
    }

    static final List<String> SIDECARS = Arrays.asList("srt", "ass", "ssa", "sub", "idx", "vtt", "lrc", "txt", "nfo");

    static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    /** The files that go with this one: same name before the extension (or "name.en.srt"), of a sidecar kind. */
    static List<File> sidecars(File file) {
        List<File> out = new ArrayList<>();
        File dir = file.getParentFile();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return out;
        String b = base(file.getName()).toLowerCase(Locale.ROOT);
        for (File f : all) {
            if (f.equals(file) || !f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            String e = n.substring(n.lastIndexOf('.') + 1);
            if (SIDECARS.contains(e) && (n.startsWith(b + ".") || base(n).equals(b))) out.add(f);
        }
        return out;
    }

    /** Subtitle files only. */
    static List<File> subtitles(File file) {
        List<File> out = new ArrayList<>();
        for (File f : sidecars(file)) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".sub") || n.endsWith(".idx") || n.endsWith(".vtt")) out.add(f);
        }
        return out;
    }

    /** The drive a media-index volume name is on ("1234-abcd"), or null. */
    @Nullable
    static File driveOf(Context c, String volume) {
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm == null || volume == null || android.os.Build.VERSION.SDK_INT < 30) return null;   // drives by folder: Android 11+
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (volume.equalsIgnoreCase(v.getMediaStoreVolumeName()) && v.getDirectory() != null) return v.getDirectory();
        }
        return null;
    }

    /** The drive's folders to move or copy into: the top ones and one level below, hidden and system ones left out. */
    static List<File> folders(File drive) {
        List<File> out = new ArrayList<>();
        out.add(drive);
        File[] top = drive.listFiles(File::isDirectory);
        if (top == null) return out;
        Arrays.sort(top, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : top) {
            if (skip(d)) continue;
            out.add(d);
            File[] sub = d.listFiles(File::isDirectory);
            if (sub == null) continue;
            Arrays.sort(sub, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File s : sub) if (!skip(s) && out.size() < 60) out.add(s);
        }
        return out;
    }

    private static boolean skip(File d) {
        String n = d.getName();
        return n.startsWith(".") || n.equalsIgnoreCase("LOST.DIR") || n.equalsIgnoreCase("Android") || n.startsWith("$") || n.equalsIgnoreCase("System Volume Information");
    }

    /** A folder as shown: its path on the drive ("Movies/Hindi"), the drive itself as "Top of the drive". */
    static String shown(File drive, File dir) {
        String p = dir.getPath().equals(drive.getPath()) ? "" : dir.getPath().substring(drive.getPath().length() + 1);
        return p.isEmpty() ? L10n.t("Top of the drive") : p;
    }

    /** A new name (the extension kept); its own files renamed with it. The new file, or null with `why` set. */
    @Nullable
    static File rename(Context c, File file, String newBase, String[] why) {
        String clean = newBase.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (clean.isEmpty()) {
            why[0] = L10n.t("That name can't be used");
            return null;
        }
        File to = new File(file.getParentFile(), clean + ext(file.getName()));
        if (to.exists()) {
            why[0] = L10n.t("A file with that name is already there");
            return null;
        }
        List<File> side = sidecars(file);
        String oldBase = base(file.getName());
        if (!file.renameTo(to)) {
            why[0] = L10n.t("The drive didn't allow it");
            return null;
        }
        List<String> paths = new ArrayList<>(Arrays.asList(file.getPath(), to.getPath()));
        for (File s : side) {
            File t = new File(s.getParentFile(), clean + s.getName().substring(oldBase.length()));
            if (s.renameTo(t)) {
                paths.add(s.getPath());
                paths.add(t.getPath());
            }
        }
        scan(c, paths);
        return to;
    }

    /** Into another folder (copy = keep the original too), its own files with it. False with `why` set. */
    static boolean place(Context c, File file, File dir, boolean copy, String[] why) {
        if (dir.equals(file.getParentFile())) {
            why[0] = L10n.t("It is already in that folder");
            return false;
        }
        List<File> all = new ArrayList<>();
        all.add(file);
        all.addAll(sidecars(file));
        if (new File(dir, file.getName()).exists()) {
            why[0] = L10n.t("A file with that name is already there");
            return false;
        }
        long need = 0;
        for (File f : all) need += f.length();
        if (copy && dir.getUsableSpace() < need + 16 * 1024 * 1024) {
            why[0] = L10n.t("Not enough room on the drive");
            return false;
        }
        List<String> paths = new ArrayList<>();
        for (File f : all) {
            File to = new File(dir, f.getName());
            boolean ok = !copy && f.renameTo(to);
            if (!ok) {
                ok = copyFile(f, to);
                if (ok && !copy) ok = f.delete();
            }
            if (!ok) {
                why[0] = L10n.t("The drive didn't allow it");
                return false;
            }
            paths.add(to.getPath());
            if (!copy) paths.add(f.getPath());
        }
        scan(c, paths);
        return true;
    }

    /** Deletes these files (the drive's own, not a copy anywhere else). */
    static int delete(Context c, List<File> files) {
        int n = 0;
        List<String> paths = new ArrayList<>();
        for (File f : files) {
            if (f.delete()) {
                n++;
                paths.add(f.getPath());
            }
        }
        scan(c, paths);
        return n;
    }

    private static boolean copyFile(File from, File to) {
        File part = new File(to.getParentFile(), ".tvtown-" + System.currentTimeMillis() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        return part.renameTo(to);
    }

    private static void scan(Context c, List<String> paths) {
        if (!paths.isEmpty()) MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null);
    }
}
