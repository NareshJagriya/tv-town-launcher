package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Icon packs (the ADW / Nova kind, which Projectivy-style TV packs follow too): the packs installed, and for an app
 * the picture its chosen pack has for it (from the pack's appfilter list). A wide picture is used as the app's banner.
 */
final class IconPacks {

    private static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME"};

    private IconPacks() {
    }

    /** Installed packs: package → name. */
    static Map<String, String> installed(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        for (String a : ACTIONS) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(a), PackageManager.GET_META_DATA)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || pkg.equals("com.spocky.projengmenu") || out.containsKey(pkg)) continue;
                out.put(pkg, String.valueOf(r.loadLabel(pm)));
            }
        }
        return out;
    }

    static String chosen(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("icon_pack", "");
    }

    static void choose(Context c, String pkg) {
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("icon_pack", pkg).apply();
        synchronized (IconPacks.class) {
            filterFor = null;
            pictures.clear();
        }
    }

    private static String filterFor;
    private static Map<String, String> filter = new HashMap<>();   // "pkg/activity" and "pkg" → drawable name
    private static final Map<String, Bitmap> pictures = new HashMap<>();

    /** The chosen pack's picture for an app, or null (none chosen, or the pack has none). Slow the first time: off the main thread. */
    @Nullable
    static synchronized Bitmap picture(Context c, String pkg, String activity) {
        String pack = chosen(c);
        if (pack.isEmpty()) return null;
        String key = pkg + "/" + activity;
        if (pictures.containsKey(key)) return pictures.get(key);
        Bitmap b = null;
        try {
            if (!pack.equals(filterFor)) {
                filter = readFilter(c, pack);
                filterFor = pack;
            }
            String name = filter.get(key);
            if (name == null) name = filter.get(pkg);
            if (name != null) {
                Resources res = c.getPackageManager().getResourcesForApplication(pack);
                int id = res.getIdentifier(name, "drawable", pack);
                if (id == 0) id = res.getIdentifier(name, "mipmap", pack);
                if (id != 0) b = render(res.getDrawable(id, null));
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // the app's own look
        }
        pictures.put(key, b);
        return b;
    }

    private static Bitmap render(Drawable d) {
        int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
        float scale = Math.min(1f, 320f / Math.max(w, h));   // banners up to 320 wide, icons up to 320 tall
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(b));
        return b;
    }

    /** The pack's appfilter: res/xml/appfilter, else assets/appfilter.xml. */
    private static Map<String, String> readFilter(Context c, String pack) throws Exception {
        Map<String, String> out = new HashMap<>();
        Resources res = c.getPackageManager().getResourcesForApplication(pack);
        int id = res.getIdentifier("appfilter", "xml", pack);
        XmlPullParser x;
        InputStream in = null;
        if (id != 0) x = res.getXml(id);
        else {
            in = c.createPackageContext(pack, 0).getAssets().open("appfilter.xml");
            x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(in, "utf-8");
        }
        try {
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e != XmlPullParser.START_TAG || !"item".equals(x.getName())) continue;
                String comp = x.getAttributeValue(null, "component"), draw = x.getAttributeValue(null, "drawable");
                if (comp == null || draw == null || !comp.startsWith("ComponentInfo{")) continue;
                String cn = comp.substring(14, comp.length() - 1);   // pkg/activity
                int slash = cn.indexOf('/');
                if (slash <= 0) continue;
                String p = cn.substring(0, slash), a = cn.substring(slash + 1);
                if (a.startsWith(".")) a = p + a;
                out.put(p + "/" + a, draw);
                if (!out.containsKey(p)) out.put(p, draw);
            }
        } finally {
            if (in != null) in.close();
        }
        return out;
    }

    /** For the picker: ids (package, "" for none) and names. */
    static List<String[]> choices(Context c) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"", L10n.t("None")});
        for (Map.Entry<String, String> e : installed(c).entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
        out.add(new String[]{"get", L10n.t("Get icon packs…")});
        return out;
    }

    /** The Play Store, at icon packs (any ADW / Nova kind works; TV ones have wide banners). */
    static void getMore(Context c) {
        String q = "icon pack android tv";
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=" + android.net.Uri.encode(q) + "&c=apps"));
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q="
                    + android.net.Uri.encode(q) + "&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
