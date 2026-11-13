package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Where saved songs or videos go: a small glass list. Save here, New folder, then the folders inside this one. */
public class SaveFolderActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    static final String EXTRA_VIDEO = "video";
    private static final int ROWS = 8;

    private boolean video;
    private File root, here;
    private final List<String> rows = new ArrayList<>();
    private int sel, top;
    private ListView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        root = drive(this);
        if (root == null) {
            HomeKeyService.note(L10n.t("Plug in a drive to save to"), 5000);
            finish();
            return;
        }
        File had = YtSaver.folder(this, video);
        if (had != null) //noinspection ResultOfMethodCallIgnored
            had.mkdirs();
        here = had != null && had.isDirectory() ? had : root;
        view = new ListView(this);
        setContentView(view);
        view.setFocusable(true);
        view.requestFocus();
        load();
    }

    /** The biggest removable drive, or null. */
    static File drive(Context c) {
        StorageManager sm = c.getSystemService(StorageManager.class);
        File best = null;
        if (sm != null && android.os.Build.VERSION.SDK_INT >= 30) {   // drives by folder: Android 11 and newer
            for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null && v.isRemovable() && (best == null || d.getTotalSpace() > best.getTotalSpace())) best = d;
            }
        }
        return best;
    }

    private void load() {
        rows.clear();
        rows.add(L10n.t("Save here"));
        rows.add(L10n.t("New folder"));
        File[] in = here.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (in != null) {
            Arrays.sort(in, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : in) rows.add(f.getName());
        }
        sel = 0;
        top = 0;
        view.invalidate();
    }

    private void choose() {
        if (sel == 0) {
            YtSaver.setFolder(this, video, here);
            finish();
        } else if (sel == 1) {
            TvKeyboard.ask(this, L10n.t("New folder"), L10n.t("Folder name"), null, L10n.t("Create"), true, name -> {
                String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
                if (n.isEmpty() || n.startsWith(".")) return;
                File f = new File(here, n);
                if (f.isDirectory() || f.mkdirs()) {
                    here = f;
                    load();
                } else {
                    HomeKeyService.note(L10n.t("Couldn't make that folder"), 4000);
                }
            });
        } else {
            here = new File(here, rows.get(sel));
            load();
        }
    }

    @Override
    public void onBackPressed() {
        if (TvKeyboard.isOpen()) {
            super.onBackPressed();
        } else if (!here.equals(root)) {
            here = here.getParentFile();
            load();
        } else {
            finish();
        }
    }

    private final class ListView extends View {
        private final float k;
        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG), big, row, rowOn, small;

        ListView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            plate.setColor(0xE6141416);
            white.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, 1.5f * k));
            edge.setColor(0x38FFFFFF);
            big = Theme.font(c, 34 * k, 600);
            row = Theme.font(c, 28 * k, 500);
            rowOn = Theme.font(c, 28 * k, 600);
            rowOn.clearShadowLayer();
            rowOn.setColor(0xFF111114);
            small = Theme.font(c, 22 * k, 400);
            small.setColor(0xB3FFFFFF);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                sel = Math.max(0, Math.min(rows.size() - 1, sel + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                if (sel < top) top = sel;
                if (sel >= top + ROWS) top = sel - ROWS + 1;
                invalidate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() == 0) choose();
                return true;
            }
            return super.onKeyDown(code, e);
        }

        @Override
        protected void onDraw(Canvas c) {
            Frost.frame(this);   // selections glide (Frost.plate)
            c.drawColor(0x8C000000);
            float pw = 720 * k, rh = 64 * k, pad = 30 * k;
            int n = Math.min(ROWS, rows.size());
            float ph = 128 * k + n * rh + pad;
            RectF box = new RectF((getWidth() - pw) / 2, (getHeight() - ph) / 2, (getWidth() + pw) / 2, (getHeight() + ph) / 2);
            c.drawRoundRect(box, 34 * k, 34 * k, plate);
            c.drawRoundRect(box, 34 * k, 34 * k, edge);
            c.drawText(video ? L10n.t("Save videos to") : L10n.t("Save songs to"), box.left + pad, box.top + 56 * k, big);
            String path = here.equals(root) ? "Drive" : "Drive" + here.getPath().substring(root.getPath().length()).replace("/", "  ›  ");
            c.drawText(TextUtils.ellipsize(path, new TextPaint(small), pw - 2 * pad, TextUtils.TruncateAt.START).toString(), box.left + pad, box.top + 96 * k, small);
            float y = box.top + 120 * k;
            for (int i = top; i < top + n && i < rows.size(); i++, y += rh) {
                RectF r = new RectF(box.left + 14 * k, y, box.right - 14 * k, y + rh - 6 * k);
                boolean on = i == sel;
                if (on) c.drawRoundRect(r, r.height() / 2, r.height() / 2, white);
                Paint p = on ? rowOn : row;
                String t = TextUtils.ellipsize(rows.get(i), new TextPaint(p), r.width() - 2 * pad, TextUtils.TruncateAt.END).toString();
                c.drawText(t, r.left + pad, r.centerY() - (p.ascent() + p.descent()) / 2, p);
            }
        }
    }
}
