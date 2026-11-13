package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A title opened the way each app opens its own home-screen tiles, which is the way it reliably answers.
 * Netflix: only a link shaped like its own (the title's address plus extras it adds to the rows it puts on the TV's
 * home screen: source_type, and a deeplink string with a device token and an expiry); a plain netflix.com/title
 * link is ignored. So one of Netflix's own links is kept whenever its rows are read, and a title's link is that one
 * with the title's number swapped in. Prime Video: its player screen with the title's detail address as an extra.
 * JioHotstar: its own hotstar:// address. Anything else: the plain link, as before.
 */
final class AppLinks {

    static final String PKG = "com.netflix.ninja";
    static final String PRIME = "com.amazon.amazonvideo.livingroom", HOTSTAR = "in.startv.hotstar";
    private static final Pattern TITLE = Pattern.compile("/title/(\\d{6,9})");

    private AppLinks() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("netflix_link", Context.MODE_PRIVATE);
    }

    /** One of Netflix's own home-screen links (from its rows); kept as the pattern for the others. */
    static void remember(Context c, @Nullable String uri) {
        if (uri == null || !uri.contains("#Intent;") || !uri.contains("S.deeplink=")) return;
        Matcher m = TITLE.matcher(uri);
        if (!m.find()) return;
        SharedPreferences p = prefs(c);
        if (uri.equals(p.getString("uri", null))) return;
        p.edit().putString("uri", uri).putString("id", m.group(1)).apply();
    }

    /** The title's number in a netflix.com/title link, or null. */
    @Nullable
    static String idIn(@Nullable String url) {
        if (url == null) return null;
        Matcher m = TITLE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Netflix's own kind of link to this title, or null before Netflix's rows have been seen. */
    @Nullable
    static Intent forTitle(Context c, String netflixId) {
        SharedPreferences p = prefs(c);
        String uri = p.getString("uri", null), id = p.getString("id", null);
        if (uri == null || id == null || netflixId == null || !netflixId.matches("\\d{6,9}")) return null;
        try {
            Intent i = Intent.parseUri(uri.replace(id, netflixId), Intent.URI_INTENT_SCHEME);
            i.setPackage(PKG);
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The app's own way in to the title at this web address, or null to use the address itself. */
    @Nullable
    static Intent own(Context c, String pkg, android.net.Uri u) {
        String url = u.toString();
        if (PKG.equals(pkg)) return forTitle(c, idIn(url));
        if (PRIME.equals(pkg)) {   // "https://app.primevideo.com/detail?gti=…" → its player screen, told "detail?gti=…"
            String path = u.getEncodedPath() == null ? "" : u.getEncodedPath().replaceFirst("^/", "");
            if (!path.startsWith("detail") || u.getEncodedQuery() == null) return null;
            return new Intent().setClassName(PRIME, "com.amazon.ignition.IgnitionActivity")   // as its own tiles: no action
                    .putExtra("com.amazon.ignition.DeepLinkIntent.DEEP_LINK", path + "?" + u.getEncodedQuery())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        if (HOTSTAR.equals(pkg) && "www.hotstar.com".equals(u.getHost()) && u.getEncodedPath() != null && u.getEncodedPath().length() > 1) {
            String q = u.getEncodedQuery();
            return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("hotstar:/" + u.getEncodedPath() + (q == null ? "" : "?" + q)))
                    .setPackage(HOTSTAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        return null;
    }
}
