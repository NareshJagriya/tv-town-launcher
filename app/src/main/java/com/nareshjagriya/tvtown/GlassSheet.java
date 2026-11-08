package com.nareshjagriya.tvtown;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A pop-up page of rows (Settings, Customize, Random Song, Switch to default home): actions, values changed with
 * Left/Right, check boxes, section headers and plain notes. It is only as tall as its rows, over Home's picture, blurred. The title, margins and row sizes are
 * the same on every page; the frosted plate glides to
 * the chosen row. It scrolls when there are more rows than fit. Its owner supplies values and handles choices.
 */
final class GlassSheet {

    static final int ACTION = 0, VALUE = 1, CHECK = 2, HEADER = 3, NOTE = 4;

    interface Host {
        @Nullable
        String value(Row r);

        void change(GlassSheet sheet, Row r, int dir, boolean wrap);

        void activate(GlassSheet sheet, Row r);
    }

    /** A category drawn its own way on the right instead of as rows (Support TV Town). */
    interface Pane {
        void draw(Canvas c, RectF area, float e, boolean focused);

        /** A key while the pane has the focus; false hands it back (Left at its edge goes to the categories). */
        boolean key(int keyCode);
    }

    static final class Row {
        final int kind;
        final String label;
        final Bitmap icon;
        final Object tag;
        boolean checked, more;   // more: opens further options

        Row(int kind, String label, @Nullable Bitmap icon, @Nullable Object tag) {
            this.kind = kind;
            this.label = label;
            this.icon = icon;
            this.tag = tag;
        }

        boolean selectable() {
            return kind != HEADER && kind != NOTE;
        }
    }

    private static final float ROW_H = 84;
    private static final int MAX_ROWS = 9;

    /** The frosted picture behind every page; Home keeps it up to date. */
    static Bitmap frost;
    private static final float CELL_W = 236, CELL_H = 176;
    private static final int GRID_ROWS = 4;
    /** How wide the page is; a short list of choices asks for less. */
    float popupW = 1180;
    /** More than 0: the rows are tiles in this many columns, with their marks above their names. */
    int gridCols;

    final String title;
    final WheelArt.Glyph glyph;
    final Object id;
    final List<Row> rows = new ArrayList<>();
    final RectF panel = new RectF();
    GlassSheet parent;          // a sub-page returns here on Back
    int sel, scroll, visible;
    float t;                    // 0 closed .. 1 open
    boolean open = true;
    private String note;
    private long noteUntil;

    private final GlassKit kit;
    private final float k;
    private final Paint title_, row, value, header, notePaint, status;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), check = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tmp = new RectF(), icon = new RectF(), screen = new RectF();

    private float plateFrom, plateTo;
    private long plateAt;
    private boolean plateSet;

    GlassSheet(GlassKit kit, String title, WheelArt.Glyph glyph, Object id, String scrollHint) {
        this.kit = kit;
        this.k = kit.k;
        this.title = title;
        this.glyph = glyph;
        this.id = id;
        NoirKit nk = new NoirKit(kit.context(), k);
        title_ = nk.inter(40, 600, 0, Color.WHITE);
        row = nk.inter(30, 500, 0, Color.WHITE);
        value = nk.inter(24, 500, 0, Frost.MUTE);
        header = nk.inter(24, 600, 0, Frost.FAINT);
        notePaint = nk.inter(24, 500, 0, Frost.MUTE);
        status = nk.inter(24, 500, 0, Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Color.WHITE);
        check.setColorFilter(new PorterDuffColorFilter(0xFF07202B, PorterDuff.Mode.SRC_IN));
    }

    GlassSheet add(int kind, String label, @Nullable Bitmap icon, @Nullable Object tag) {
        if (sectionRows != null) sectionRows.get(sectionRows.size() - 1).add(new Row(kind, label, icon, tag));
        else rows.add(new Row(kind, label, icon, tag));
        return this;
    }

    // Settings: a compact glass page, categories on its left and the chosen one's rows on its right.
    @Nullable
    List<List<Row>> sectionRows;
    private final List<String> sectionNames = new ArrayList<>();
    private final List<Bitmap> sectionIcons = new ArrayList<>();
    private final List<Object> sectionActions = new ArrayList<>();
    private final List<Pane> sectionPanes = new ArrayList<>();
    private final RectF outer = new RectF();
    int section;
    boolean inRail;
    private static final int PAGE_ROWS = 7;
    private static final float RAIL_ROW = 78;
    private float railRow = RAIL_ROW;   // a little closer when there are many categories

    /** Starts a category; rows added next belong to it. */
    GlassSheet addSection(String name, Bitmap icon) {
        return addSection(name, icon, null);
    }

    /** A category that is itself a button: OK on it does `action`; its rows only explain. */
    GlassSheet addSection(String name, Bitmap icon, @Nullable Object action) {
        if (sectionRows == null) sectionRows = new ArrayList<>();
        sectionRows.add(new ArrayList<>());
        sectionNames.add(name);
        sectionIcons.add(icon);
        sectionActions.add(action);
        sectionPanes.add(null);
        return this;
    }

    /** The category just added shows this pane on the right instead of rows. */
    GlassSheet pane(Pane p) {
        sectionPanes.set(sectionPanes.size() - 1, p);
        return this;
    }

    boolean hasPane() {
        return pane() != null;
    }

    @Nullable
    private Pane pane() {
        return sectionRows != null && section < sectionPanes.size() ? sectionPanes.get(section) : null;
    }

    /** Marks these rows as leading to more options: they show an arrow. */
    void markMore(String... tags) {
        for (List<Row> list : sectionRows) for (Row r : list) for (String t : tags) if (t.equals(r.tag)) r.more = true;
    }

    /** Lays the page out in the middle of the screen, with the focus on category `first`. */
    void layoutPage(float screenW, float screenH, int first) {
        screen.set(0, 0, screenW, screenH);
        int n = sectionNames == null ? 0 : sectionNames.size();
        railRow = n > 9 ? 72 : RAIL_ROW;
        float h = Math.max(740, 104 + n * railRow + 24), top = (1080 - h) / 2;   // tall enough for every category
        outer.set(240 * k, top * k, 1680 * k, (top + h) * k);
        panel.set(outer.left + 470 * k, outer.top, outer.right - 8 * k, outer.bottom);
        inRail = true;
        show(first);
    }

    /** OK or Right on a category. */
    void railOk(Host host) {
        if (pane() != null) {
            inRail = false;
            return;
        }
        Object action = sectionActions.get(section);
        if (action != null) host.activate(this, new Row(ACTION, sectionNames.get(section), null, action));
        else inRail = false;
    }

    private void show(int i) {
        section = i;
        rows.clear();
        rows.addAll(sectionRows.get(i));
        visible = Math.min(rows.size(), PAGE_ROWS);
        sel = 0;
        sel = next(0, 1);
        scroll = 0;
        plateSet = false;
    }

    /** Fills the screen with up to ten rows showing; width and maxVisible are kept for the callers' sake. */
    void layout(float screenW, float screenH, float width, int maxVisible) {
        visible = Math.min(rows.size(), MAX_ROWS);
        if (gridCols > 0) {
            int all = (rows.size() + gridCols - 1) / gridCols;
            visible = Math.min(all, GRID_ROWS);
            float gw = (gridCols * CELL_W + 56) * k, gh = (116 + visible * CELL_H + 24) * k;
            panel.set((screenW - gw) / 2, (screenH - gh) / 2, (screenW + gw) / 2, (screenH + gh) / 2);
            screen.set(0, 0, screenW, screenH);
            sel = 0;
            scroll = 0;
            return;
        }
        float w = popupW * k, h = (116 + visible * ROW_H + 28) * k;
        panel.set((screenW - w) / 2, (screenH - h) / 2, (screenW + w) / 2, (screenH + h) / 2);
        screen.set(0, 0, screenW, screenH);
        sel = next(0, 1);
        scroll = 0;
        plateSet = false;
    }

    private int next(int from, int dir) {
        for (int i = from; i >= 0 && i < rows.size(); i += dir) if (rows.get(i).selectable()) return i;
        return sel;
    }

    void say(String message) {
        note = message;
        noteUntil = SystemClock.uptimeMillis() + 2600;
    }

    /** True while the plate is still gliding, so the owner keeps drawing. */
    boolean moving(long now) {
        return plateSet && now - plateAt < Frost.GLIDE_MS;
    }

    /** D-pad inside the page. True when handled. */
    boolean key(int keyCode, Host host) {
        if (sectionRows != null) {
            if (inRail) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (section > 0) show(section - 1);
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (section < sectionRows.size() - 1) show(section + 1);
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) railOk(host);
                else return keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
                return true;
            }
            Pane p = pane();
            if (p != null) {
                if (p.key(keyCode)) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) inRail = true;
                return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {   // always back to the categories, on any row
                inRail = true;
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                Row r = rows.get(sel);
                if (r.more) host.activate(this, r);
                else if (r.kind == VALUE) host.change(this, r, 1, true);
                return true;
            }
        }
        if (gridCols > 0) {
            int n = rows.size();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && sel % gridCols > 0) sel--;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && sel % gridCols < gridCols - 1 && sel + 1 < n) sel++;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && sel >= gridCols) sel -= gridCols;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && sel / gridCols < (n - 1) / gridCols) sel = Math.min(n - 1, sel + gridCols);
            else if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            int row = sel / gridCols;
            if (row < scroll) scroll = row;
            if (row >= scroll + visible) scroll = row - visible + 1;
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                sel = next(sel + dir, dir);
                if (sel < scroll) scroll = Math.max(0, sel > 0 && !rows.get(sel - 1).selectable() ? sel - 1 : sel);
                if (sel >= scroll + visible) scroll = sel - visible + 1;
                // Keep notes at the top of a page visible when moving back up to the first row.
                if (sel == next(0, 1)) scroll = 0;
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Row r = rows.get(sel);
                if (r.kind == VALUE || r.kind == CHECK) host.change(this, r, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, false);
                return true;
            }
            default:
                return false;
        }
    }

    private void drawTiles(Canvas c, Host host, float e) {
        float cw = CELL_W * k, ch = CELL_H * k, x0 = panel.left + 28 * k, y0 = panel.top + 112 * k;
        for (int i = scroll * gridCols; i < rows.size() && i < (scroll + visible) * gridCols; i++) {
            Row x = rows.get(i);
            float cx = x0 + (i % gridCols) * cw, cy = y0 + (i / gridCols - scroll) * ch;
            tmp.set(cx + 6 * k, cy + 6 * k, cx + cw - 6 * k, cy + ch - 6 * k);
            if (i == sel) Frost.plate(c, tmp, 28 * k, k);
            if (x.icon != null) {
                bmp.setAlpha(Math.round((i == sel ? 255 : x.checked ? 235 : 130) * e));
                c.drawBitmap(x.icon, tmp.centerX() - x.icon.getWidth() / 2f, tmp.top + 56 * k - x.icon.getHeight() / 2f, bmp);
                bmp.setAlpha(255);
            }
            value.setColor(i == sel || x.checked ? Frost.INK : Frost.MUTE);
            value.setTextSize(26 * k);
            String label = android.text.TextUtils.ellipsize(x.label, new android.text.TextPaint(value), tmp.width() - 20 * k, android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(label, tmp.centerX() - value.measureText(label) / 2, tmp.bottom - 24 * k, value);
            value.setTextSize(24 * k);
            if (x.checked) {
                Frost.tick(c, tmp.right - 26 * k, tmp.top + 26 * k, 14 * k, k);
                String order = host.value(x);
                if (order != null) {
                    status.setColor(Frost.MUTE);
                    status.setTextSize(26 * k);
                    c.drawText(order, tmp.left + 16 * k, tmp.top + 36 * k, status);
                    status.setTextSize(24 * k);
                }
            }
        }
    }

    /** A row's sign that there is more inside. */
    private void drawArrow(Canvas c, float x, float y) {
        edge.setStrokeWidth(3 * k);
        edge.setStrokeCap(Paint.Cap.ROUND);
        edge.setColor(Color.WHITE);
        float s = 9 * k;
        c.drawLine(x - s / 2, y - s, x + s / 2, y, edge);
        c.drawLine(x + s / 2, y, x - s / 2, y + s, edge);
    }

    private void drawSwitch(Canvas c, float x, float y, float w, boolean on) {
        float h = 38 * k, d = h - 8 * k;
        tmp.set(x, y - h / 2, x + w, y + h / 2);
        fill.setColor(on ? 0xFF34C759 : 0x4DFFFFFF);
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        fill.setColor(Color.WHITE);
        c.drawCircle(on ? tmp.right - 4 * k - d / 2 : tmp.left + 4 * k + d / 2, y, d / 2, fill);
    }

    void draw(Canvas c, Host host, long now) {
        float e = Theme.ease(t);
        int a = Math.round(255 * e);
        Frost.scrim(c, screen, e, bmp);
        Frost.panel(c, sectionRows != null ? outer : panel, 40 * k, e, k);

        title_.setAlpha(a);
        boolean page = sectionRows != null;
        if (page) {
            c.drawText(title, outer.left + 44 * k, outer.top + 72 * k, title_);
            float rh = railRow * k, ry = outer.top + 104 * k;
            for (int i = 0; i < sectionNames.size(); i++) {
                tmp.set(outer.left + 20 * k, ry + i * rh, outer.left + 450 * k, ry + i * rh + rh - 6 * k);
                if (i == section) {
                    if (inRail) Frost.plate(c, tmp, 22 * k, k);
                    else {
                        fill.setColor(0x2EFFFFFF);
                        c.drawRoundRect(tmp, 22 * k, 22 * k, fill);
                    }
                }
                kit.drawGlyph(c, sectionIcons.get(i), tmp.left + 42 * k, tmp.centerY(), i == section ? a : Math.round(190 * e));
                row.setAlpha(i == section ? a : Math.round(205 * e));
                c.drawText(sectionNames.get(i), tmp.left + 84 * k, tmp.centerY() + 11 * k, row);
                if (sectionActions.get(i) != null) drawArrow(c, tmp.right - 24 * k, tmp.centerY());
            }
            fill.setColor(0x1FFFFFFF);
            c.drawRect(panel.left - 8 * k, outer.top + 28 * k, panel.left - 6 * k, outer.bottom - 28 * k, fill);
            if (note != null && now < noteUntil) {
                status.setColor(Color.WHITE);
                c.drawText(android.text.TextUtils.ellipsize(note, new android.text.TextPaint(status), panel.width() - 96 * k, android.text.TextUtils.TruncateAt.END).toString(), panel.left + 48 * k, outer.bottom - 28 * k, status);
            }
        } else c.drawText(title, panel.left + 48 * k, panel.top + 76 * k, title_);
        String corner = page ? null : note != null && now < noteUntil ? note : rows.size() > visible ? (sel + 1) + " of " + rows.size() : null;
        if (corner != null) {
            boolean said = note != null && now < noteUntil;
            status.setColor(said ? Color.WHITE : Frost.MUTE);
            status.setAlpha(Math.round(Color.alpha(status.getColor()) * e));
            if (said && status.measureText(corner) > panel.width() * 0.45f) {   // a long hint: along the foot, clear of the title
                String fit = android.text.TextUtils.ellipsize(corner, new android.text.TextPaint(status), panel.width() - 96 * k, android.text.TextUtils.TruncateAt.END).toString();
                c.drawText(fit, panel.left + 48 * k, panel.bottom - 24 * k, status);
            } else c.drawText(corner, panel.right - 48 * k - status.measureText(corner), panel.top + 74 * k, status);
        }

        if (pane() != null) {
            pane().draw(c, panel, e, !inRail);
            return;
        }
        if (gridCols > 0) {
            drawTiles(c, host, e);
            return;
        }
        Paint.FontMetrics fm = row.getFontMetrics();
        float rowH = ROW_H * k, top0 = panel.top + (page ? 28 : 112) * k;
        int at = Math.max(0, sel - scroll);
        float target = top0 + at * rowH;
        if (!plateSet) {
            plateSet = true;
            plateFrom = plateTo = target;
            plateAt = 0;
        } else if (target != plateTo) {
            plateFrom = Frost.glide(plateFrom, plateTo, now, plateAt);
            plateTo = target;
            plateAt = now;
        }
        if (sel >= 0 && sel < rows.size() && rows.get(sel).selectable() && !(page && inRail)) {
            float y = Frost.glide(plateFrom, plateTo, now, plateAt);
            tmp.set(panel.left + 20 * k, y + 4 * k, panel.right - 20 * k, y + rowH - 4 * k);
            Frost.plate(c, tmp, 28 * k, k);
        }
        for (int v = 0; v < visible; v++) {
            int i = scroll + v;
            if (i >= rows.size()) break;
            Row x = rows.get(i);
            float top = top0 + v * rowH, mid = top + rowH / 2, base = mid - (fm.ascent + fm.descent) / 2;
            if (x.kind == HEADER) {
                header.setAlpha(Math.round(Color.alpha(Frost.FAINT) * e));
                c.drawText(x.label, panel.left + 48 * k, mid + 20 * k, header);
                continue;
            }
            if (x.kind == NOTE) {
                notePaint.setAlpha(Math.round(Color.alpha(Frost.MUTE) * e));
                c.drawText(x.label, panel.left + 48 * k, base, notePaint);
                continue;
            }
            float textX = panel.left + 48 * k;
            if (x.icon != null) {
                if (x.kind == CHECK) {
                    float is = 48 * k;
                    bmp.setAlpha(a);
                    icon.set(textX, mid - is / 2, textX + is, mid + is / 2);
                    c.drawBitmap(x.icon, null, icon, bmp);
                    bmp.setAlpha(255);
                } else {
                    kit.drawGlyph(c, x.icon, textX + 20 * k, mid, Math.round((i == sel ? 255 : 215) * e));
                }
                textX += 68 * k;
            }
            row.setAlpha(Math.round((i == sel ? 255 : 220) * e));
            float right = panel.right - 48 * k;
            String val = host.value(x);
            if (page && x.kind == VALUE) {   // a switch for on or off, else the choice in a small capsule
                boolean onOff = "On".equals(val) || "Off".equals(val);
                float w = onOff ? 72 * k : value.measureText(String.valueOf(val)) + 44 * k;
                c.drawText(android.text.TextUtils.ellipsize(x.label, new android.text.TextPaint(row), Math.max(80 * k, right - textX - w - 28 * k),
                        android.text.TextUtils.TruncateAt.END).toString(), textX, base, row);
                if (onOff) drawSwitch(c, right - w, mid, w, "On".equals(val));
                else {
                    tmp.set(right - w, mid - 25 * k, right, mid + 25 * k);
                    fill.setColor(0x26FFFFFF);
                    c.drawRoundRect(tmp, 25 * k, 25 * k, fill);
                    value.setColor(Color.WHITE);
                    c.drawText(String.valueOf(val), tmp.left + 22 * k, base, value);
                }
                continue;
            }
            if (x.more) right -= 34 * k;
            String shown = val == null ? null : x.kind == VALUE ? "‹  " + val + "  ›" : val;
            float valW = shown == null ? 0 : value.measureText(shown) + (x.kind == CHECK ? 56 * k : 0);
            float room = right - textX - (valW > 0 ? valW + 28 * k : 0);   // the name never runs into the value
            c.drawText(android.text.TextUtils.ellipsize(x.label, new android.text.TextPaint(row), Math.max(80 * k, room),
                    android.text.TextUtils.TruncateAt.END).toString(), textX, base, row);
            if (shown != null && (x.kind == VALUE || x.kind == CHECK || x.kind == ACTION)) {
                value.setColor(i == sel ? Frost.INK : Frost.MUTE);
                value.setAlpha(Math.round(Color.alpha(value.getColor()) * e));
                c.drawText(shown, right - value.measureText(shown) - (x.kind == CHECK ? 56 * k : 0), base, value);
            }
            if (x.more) drawArrow(c, panel.right - 48 * k - 6 * k, mid);
            if (x.kind == CHECK) {
                float cr = 17 * k, ccx = right - cr;
                if (x.checked) Frost.tick(c, ccx, mid, cr, k);
                else Frost.untick(c, ccx, mid, cr, k);
            }
        }
    }
}
