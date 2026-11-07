package com.nareshjagriya.tvtown;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The TV's launchable apps, alphabetical, each with a rounded-square icon. */
final class Apps {

    static final class App {
        final String label;
        final ComponentName component;
        final String pkg;
        final Bitmap icon;

        App(String label, ComponentName component, Bitmap icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.icon = icon;
        }

        String key() {
            return component.flattenToShortString();
        }
    }

    /** Never listed: the Live TV app (reached through Inputs) and the TV's own gallery. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("com.mediatek.wwtv.tvcenter", "com.mitv.gallery"));

    /** Icon corner radius relative to its size. */
    static final float CORNER = 0.22f;

    /** Icons are rendered once per app and size. */
    private static final Map<String, Bitmap> iconCache = new HashMap<>();

    private Apps() {
    }

    static List<App> load(Context ctx, int iconPx) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || HIDDEN.contains(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            String cacheKey = cn.flattenToShortString() + "@" + iconPx;
            Bitmap icon = iconCache.get(cacheKey);
            if (icon == null) {
                icon = shapedIcon(highDensity(pm, ri.activityInfo), iconPx);
                iconCache.put(cacheKey, icon);
            }
            apps.add(new App(String.valueOf(ri.loadLabel(pm)), cn, icon));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /** An activity's launcher icon at a high density, for large renderings. */
    static Drawable hiResIcon(Context ctx, ComponentName cn) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return highDensity(pm, pm.getActivityInfo(cn, 0));
        } catch (Exception e) {
            return pm.getDefaultActivityIcon();
        }
    }

    private static Drawable highDensity(PackageManager pm, ActivityInfo ai) {
        int res = ai.getIconResource();
        if (res != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (Exception ignored) {
                // fall back to the default density below
            }
        }
        return ai.loadIcon(pm);
    }

    /**
     * An icon as a full-bleed rounded square. Adaptive icons are drawn edge to edge; legacy icons that carry
     * their own shape (a circle, a rounded square) are enlarged until that shape fills the square.
     */
    static Bitmap shapedIcon(Drawable d, int size) {
        Bitmap full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        if (d != null && d.getConstantState() != null) d = d.getConstantState().newDrawable().mutate();
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int extra = size / 4;   // adaptive layers are 108 units with the middle 72 visible
            Rect b = new Rect(-extra, -extra, size + extra, size + extra);
            if (a.getBackground() != null) {
                a.getBackground().setBounds(b);
                a.getBackground().draw(c);
            }
            if (a.getForeground() != null) {
                a.getForeground().setBounds(b);
                a.getForeground().draw(c);
            }
        } else if (d != null) {
            Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(raw));
            int[] px = new int[size * size];
            raw.getPixels(px, 0, size, 0, 0, size, size);
            Rect box = opaqueBounds(px, size);
            c.drawColor(edgeColor(px, size, box));
            if (box != null) {
                int inset = Math.round(box.width() * 0.04f);   // trims the icon's own rounded corners
                box.inset(inset, inset);
                c.drawBitmap(raw, box, new Rect(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            raw.recycle();
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = size * CORNER;
        new Canvas(out).drawRoundRect(0, 0, size, size, r, r, p);
        full.recycle();
        return out;
    }

    /** The square around the icon's opaque pixels, centred on them; null when the icon is empty. */
    private static Rect opaqueBounds(int[] px, int s) {
        int l = s, t = s, r = -1, b = -1;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (Color.alpha(px[y * s + x]) > 200) {
                    l = Math.min(l, x);
                    r = Math.max(r, x);
                    t = Math.min(t, y);
                    b = Math.max(b, y);
                }
            }
        }
        if (r < 0) return null;
        int side = Math.max(r - l, b - t) + 1, cx = (l + r) / 2, cy = (t + b) / 2;
        return new Rect(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side);
    }

    /** The colour just inside the icon's left edge, used behind any leftover transparency. */
    private static int edgeColor(int[] px, int s, Rect box) {
        if (box == null) return 0xFF2C2C2E;
        int x = Math.min(s - 1, Math.max(0, box.left + box.width() / 8));
        int y = Math.min(s - 1, Math.max(0, box.centerY()));
        int p = px[y * s + x];
        return Color.alpha(p) > 200 ? (p | 0xFF000000) : 0xFF2C2C2E;
    }
}
