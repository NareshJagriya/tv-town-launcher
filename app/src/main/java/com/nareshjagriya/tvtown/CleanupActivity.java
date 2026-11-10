package com.nareshjagriya.tvtown;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Speed up your TV: how much memory is free; Close apps (apps left running behind, never a home screen); and the apps that can be switched off for good (Cleanup's two lists, only those on this TV), each with what it
 * does, how much memory it takes now and, for the ones people do use, what stops working. Switching off is
 * "pm disable-user" through the TV's debugging connection: nothing is uninstalled, and Turn all back on undoes every one
 * TV Town switched off. Before each, the TV is asked again what it needs (its inputs, keyboard, Home, screensaver).
 */
public class CleanupActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Customize
    }

    private Page page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new Page(this);
        setContentView(page);
        page.setFocusable(true);
        page.requestFocus();
        page.load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        page.resumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        page.resumed = false;
    }

    @Override
    public void onBackPressed() {
        if (!page.back()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        page.work.shutdownNow();
        new Thread(TvShell::close, "shell-close").start();
    }

    private static final class Row {
        final Cleanup.App app;
        final String pkg;
        boolean on, needed, busy;
        long kb;

        Row(Cleanup.App app, String pkg, boolean on) {
            this.app = app;
            this.pkg = pkg;
            this.on = on;
        }
    }

    private static final class Page extends View {

        private static final int B_CLOSE = 0, B_SAFE = 1, B_RESTORE = 2, BUTTONS = 3;
        private static final String[] BUTTON = {L10n.t("Close background apps"), L10n.t("Switch off the safe ones"), L10n.t("Turn all back on")};
        private static final float LIST_L = 80, LIST_R = 1080, LIST_TOP = 352, ROW_H = 74, HEAD_H = 56, LIST_BOTTOM = 1040;

        final ExecutorService work = Work.pool(1);
        private final Activity act;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final float k;
        private final GlassKit kit;
        private final Paint title, big, text, small, muted, chip, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final List<Row> rows = new ArrayList<>();
        private int focus = -1, button;   // the app list: focus -1 is its buttons, else a row
        private boolean menu = true;      // the card with the choices (else the app list)
        private int menuSel;              // 0 the big button, then the four rows
        private int top;                  // the first row in view
        private String note = "";
        private boolean loading = true, canSwitch;
        private long freeMb, totalMb;

        Page(Activity act) {
            super(act);
            this.act = act;
            k = act.getResources().getDisplayMetrics().widthPixels / 1920f;
            kit = new GlassKit(act, null, k);
            NoirKit nk = new NoirKit(act, k);
            title = nk.inter(44, 600, -0.02f, Color.WHITE);
            big = nk.inter(30, 600, 0, Color.WHITE);
            text = nk.inter(27, 500, 0, Color.WHITE);
            small = nk.inter(24, 500, 0, Color.WHITE);
            muted = nk.inter(24, 500, 0, 0xB4FFFFFF);
            chip = nk.inter(22, 600, 0, Color.WHITE);
            prefs = act.getSharedPreferences("cleanup", Context.MODE_PRIVATE);
            memory();
        }

        private void memory() {
            android.app.ActivityManager am = getContext().getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            freeMb = mi.availMem / (1024 * 1024);
            totalMb = mi.totalMem / (1024 * 1024);
        }

        private Set<String> switchedByUs() {
            return new HashSet<>(prefs.getStringSet("off", new HashSet<>()));
        }

        // ---- reading the TV --------------------------------------------------------------------------------

        /** Which listed apps are here and on, what each uses now, and what the TV needs: one command, its parts split. */
        void load() {
            loading = true;
            work.execute(() -> {
                String out = TvShell.run(getContext(), L10n.t("pm list packages -e; echo '=====';")
                        + L10n.t(" pm list packages -d; echo '=====';")
                        + " pm query-services -a android.media.tv.TvInputService; echo '=====';"
                        + L10n.t(" settings get secure default_input_method; echo '=====';")
                        + " cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; echo '=====';"
                        + L10n.t(" settings get secure screensaver_components; echo '=====';")
                        + L10n.t(" dumpsys meminfo"), 60_000);
                main.post(() -> {
                    loading = false;
                    memory();
                    if (out == null) {
                        canSwitch = false;
                        note = TvShell.last == TvShell.DEBUGGING_OFF
                                ? L10n.t("To switch apps off, turn on USB debugging once: Settings → Device Preferences → About → press OK on Build 7 times, then Developer options → USB debugging. Close background apps works anyway.")
                                : L10n.t("The TV asked whether to allow TV Town: choose Allow (tick Always), then open this again. Close background apps works anyway.");
                        invalidate();
                        return;
                    }
                    canSwitch = true;
                    read(out);
                    if (rows.isEmpty()) note = L10n.t("Nothing on TV Town's list is on this TV: it's clean already.");
                    invalidate();
                });
            });
        }

        private void read(String out) {
            String[] part = (out + "\n").replace("\r", "").split("(?m)^=====$", -1);
            if (part.length < 7) return;
            Set<String> enabled = new HashSet<>(Cleanup.packages(part[0])), disabled = new HashSet<>(Cleanup.packages(part[1]));
            String needs = part[2] + "\n" + part[3] + "\n" + part[4] + "\n" + part[5];
            Map<String, Long> mem = Cleanup.memoryByApp(part[6]);
            rows.clear();
            Set<String> all = new HashSet<>(enabled);
            all.addAll(disabled);
            for (List<Cleanup.App> l : java.util.Arrays.asList(Cleanup.SAFE, Cleanup.MAYBE)) {
                for (Cleanup.App a : l) {
                    for (String p : sorted(all)) {
                        if (Cleanup.find(p) != a) continue;
                        Row r = new Row(a, p, enabled.contains(p));
                        r.needed = mentions(needs, p);
                        Long kb = mem.get(p);
                        r.kb = kb == null ? 0 : kb;
                        rows.add(r);
                    }
                }
            }
            focus = Math.min(focus, rows.size() - 1);
        }

        /** Whether the TV's answers name this package as a whole word (an input, the keyboard, Home, the screensaver). */
        private static boolean mentions(String text, String pkg) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|[\\s=:{])" + java.util.regex.Pattern.quote(pkg) + "($|[\\s/}])",
                    java.util.regex.Pattern.MULTILINE).matcher(text);
            return m.find();
        }

                /** An app's name for people: its own label, else a known one, else the last part of its package. */
        private static String label(Context c, String pkg) {
            if (pkg.equals("com.google.android.tvlauncher") || pkg.equals("com.google.android.apps.tv.launcherx")) return "Google's Home";
            if (pkg.equals("com.android.vending")) return "Play Store";
            try {
                return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) {
                Cleanup.App a = Cleanup.find(pkg);
                if (a != null) return a.name;
                String last = pkg.substring(pkg.lastIndexOf('.') + 1);
                return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
            }
        }

        private static List<String> sorted(Set<String> s) {
            List<String> l = new ArrayList<>(s);
            java.util.Collections.sort(l);
            return l;
        }

        // ---- doing things ------------------------------------------------------------------------------------

        /** What Close apps did: how many it closed, what they were using, and some of their names. */
        private static final class Closed {
            int n;
            long kb;
            final List<String> names = new ArrayList<>();
        }

        /**
         * Apps left running behind, closed (system ones only if they're waiting in the background). Never the TV's home
         * screens: the TV starts its own again at once, and TV Town's helper then brings TV Town's Home up over this page.
         * Off the main thread.
         */
        private static Closed closeNow(Context c) {
            Closed out = new Closed();
            String meminfo = TvShell.run(c, L10n.t("pm list packages -s; echo '====='; dumpsys meminfo; echo '====='; ")
                    + "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 60_000);
            if (meminfo == null) {
                out.kb = TvTools.freeUpMemory(c) * 1024;   // without the debugging connection: what Android lets an app close
                return out;
            }
            String[] part = meminfo.replace("\r", "").split("(?m)^=====$", -1);
            Set<String> system = new HashSet<>(Cleanup.packages(part[0]));
            String homes = part.length > 2 ? part[2] : "";
            String playing = null;
            android.media.session.MediaController mc = TvTools.nowPlaying(c);
            if (mc != null) playing = mc.getPackageName();
            StringBuilder cmd = new StringBuilder();
            for (Map.Entry<String, Long> en : Cleanup.memoryByApp(part.length > 1 ? part[1] : "").entrySet()) {
                String p = en.getKey();
                if (!Cleanup.closable(p) || p.equals(playing) || homes.contains(p + "/")) continue;
                cmd.append(system.contains(p) ? "am kill " : "am force-stop ").append(p).append("; ");
                out.n++;
                out.kb += en.getValue();
                if (out.names.size() < 3) out.names.add(label(c, p));
            }
            if (cmd.length() > 0) TvShell.run(c, cmd.toString(), 10_000);
            return out;
        }

        /** Close apps on its own: what it did, in a line. */
        private void closeBackground() {
            note = L10n.t("Closing apps running in the background…");
            invalidate();
            Context c = getContext();
            work.execute(() -> {
                Closed cl = closeNow(c);
                String said = cl.n > 0 ? L10n.t("Closed ") + cl.n + (cl.n == 1 ? L10n.t(" app") : L10n.t(" apps")) + L10n.t(" that were using ") + Cleanup.mb(cl.kb) + " ("
                        + TextUtils.join(", ", cl.names) + (cl.n > cl.names.size() ? "…" : "") + L10n.t("). A few, like the Play Store, start again by themselves.")
                        : cl.kb > 0 ? Cleanup.mb(cl.kb) + L10n.t(" freed") : L10n.t("Nothing was running in the background");
                main.post(() -> {
                    memory();
                    note = said;
                    if (canSwitch) load();
                    invalidate();
                });
            });
        }

        /** One app off or back on; the TV is asked again first what it needs. */
        private void toggle(Row r) {
            if (!canSwitch || r.busy) return;
            if (r.needed) {
                note = r.app.name + L10n.t(" is in use by your TV, so it stays on");
                invalidate();
                return;
            }
            r.busy = true;
            boolean off = r.on;
            long was = r.kb;
            Context c = getContext();
            invalidate();
            work.execute(() -> {
                String res = Cleanup.cleanName(r.pkg)
                        ? TvShell.run(c, (off ? L10n.t("pm disable-user --user 0 ") : L10n.t("pm enable --user 0 ")) + r.pkg, 30_000) : null;
                boolean done = res != null && res.contains(off ? "disabled" : "enabled");
                main.post(() -> {
                    r.busy = false;
                    if (done) {
                        r.on = !off;
                        if (off) r.kb = 0;
                        Set<String> s = switchedByUs();
                        if (off) s.add(r.pkg);
                        else s.remove(r.pkg);
                        prefs.edit().putStringSet("off", s).apply();
                        note = off ? r.app.name + L10n.t(" is off") + (was > 0 ? L10n.t(": the ") + Cleanup.mb(was) + L10n.t(" it was using is free, and it won't start again.")
                                : L10n.t(". It wasn't running just now, so nothing frees today, but it can't start later and take memory."))
                                : r.app.name + L10n.t(" is back on. It may start again and use memory.");
                    } else note = L10n.t("Couldn't change ") + r.app.name;
                    memory();
                    invalidate();
                });
            });
        }

        /** The safe ones that are on: off, one after another. How many were asked. */
        private int switchOffSafe() {
            if (!canSwitch) {
                if (loading) note = L10n.t("Still looking at this TV\u2026 try again in a moment");
                invalidate();
                return 0;
            }
            int n = 0;
            for (Row r : rows) if (r.app.safe && r.on && !r.needed) {
                toggle(r);
                n++;
            }
            if (n == 0) note = L10n.t("The apps the TV doesn't need are already off");
            return n;
        }

        /** Speed it all up: what the TV doesn't need off, apps left running closed, and Lite mode on. */
        // Speed it all up, step by step on this page: each step a spinner, then a tick and what it did.
        private static final int STEPS = 3;
        private final String[] stepText = new String[STEPS];
        private final int[] stepState = new int[STEPS];   // 0 waiting, 1 working, 2 done
        private boolean steps, allRunning;
        private long freedMb = -1;
        private boolean resumed = true;

        private void step(int i, int state, String text) {
            main.post(() -> {
                stepState[i] = state;
                stepText[i] = text;
                invalidate();
            });
        }

        private void doAll() {
            if (allRunning) return;
            allRunning = true;
            steps = true;
            freedMb = -1;
            for (int i = 0; i < STEPS; i++) stepState[i] = 0;
            stepText[0] = L10n.t("Lite mode");
            stepText[1] = L10n.t("Switching off extras");
            stepText[2] = L10n.t("Closing apps left running");
            note = "";
            Context c = getContext();
            long before = TvTools.freeMemoryMb(c);
            Lite.set(c, true);
            stepState[0] = 2;
            stepText[0] = L10n.t("Lite mode is on");
            stepState[1] = 1;   // working from the start: the TV may first be asked (the permission question)
            stepText[1] = L10n.t("Getting ready\u2026");
            invalidate();
            work.execute(() -> {   // after the first look at the TV (queued before this), so the list of apps is known
                List<Row> safe = new ArrayList<>();
                java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
                main.post(() -> {
                    if (canSwitch) for (Row r : rows) if (r.app.safe && r.on && !r.needed && Cleanup.cleanName(r.pkg)) safe.add(r);
                    got.countDown();
                });
                try {
                    got.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                step(1, 1, L10n.t("Switching off extras\u2026"));
                int off = 0;
                Set<String> mine = switchedByUs();
                for (Row r : safe) {
                    String res = TvShell.run(c, L10n.t("pm disable-user --user 0 ") + r.pkg, 30_000);
                    if (res != null && res.contains("disabled")) {
                        off++;
                        mine.add(r.pkg);
                        main.post(() -> {
                            r.on = false;
                            r.kb = 0;
                        });
                    }
                }
                prefs.edit().putStringSet("off", mine).apply();
                step(1, 2, !canSwitch ? L10n.t("Switching off needs USB debugging once") : off > 0 ? off + (off == 1 ? L10n.t(" app") : L10n.t(" apps")) + L10n.t(" the TV doesn't need are off")
                        : L10n.t("Extras were already off"));
                step(2, 1, L10n.t("Closing apps left running\u2026"));
                Closed cl = closeNow(c);
                try {
                    Thread.sleep(1500);   // the memory shows up free a moment later
                } catch (InterruptedException e) {
                    return;
                }
                long after = TvTools.freeMemoryMb(c);
                step(2, 2, cl.n > 0 ? L10n.t("Closed ") + cl.n + (cl.n == 1 ? L10n.t(" app") : L10n.t(" apps")) + " (" + Cleanup.mb(cl.kb) + ")" : L10n.t("Nothing was running in the background"));
                main.post(() -> {
                    allRunning = false;
                    freedMb = Math.max(0, after - before);
                    memory();
                    invalidate();
                    if (!resumed) act.startActivity(new android.content.Intent(act, CleanupActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));   // back here to show what was done
                });
            });
        }

        private void lite() {
            Lite.set(getContext(), !Lite.on);
            note = Lite.on ? L10n.t("Lite mode is on: plain glass, no animations, smaller pictures. Home shows the change when you go back.")
                    : L10n.t("Lite mode is off: everything looks as before.");
        }

        /** Everything as it was: the apps TV Town switched off back on, and Lite mode off. */
        private void undoAll() {
            boolean wasLite = Lite.on;
            Lite.set(getContext(), false);
            if (switchedByUs().isEmpty()) {
                note = wasLite ? L10n.t("Lite mode is off. TV Town hadn't switched any apps off.") : L10n.t("Nothing to undo: TV Town hasn't changed anything");
                invalidate();
                return;
            }
            restoreAll();
        }

        private void restoreAll() {
            Set<String> s = switchedByUs();
            if (s.isEmpty()) {
                note = L10n.t("TV Town hasn't switched anything off");
                invalidate();
                return;
            }
            Context c = getContext();
            note = L10n.t("Turning everything back on…");
            invalidate();
            work.execute(() -> {
                StringBuilder cmd = new StringBuilder();
                for (String p : s) if (Cleanup.cleanName(p)) cmd.append(L10n.t("pm enable --user 0 ")).append(p).append("; ");
                String res = TvShell.run(c, cmd.toString(), 30_000);
                main.post(() -> {
                    if (res != null) {
                        prefs.edit().remove("off").apply();
                        note = L10n.t("Everything TV Town switched off is back on");
                        load();
                    } else note = L10n.t("Couldn't reach the TV's settings");
                    invalidate();
                });
            });
        }

        // ---- drawing -------------------------------------------------------------------------------------------

        private RectF gr(float x, float y, float w, float h) {
            return new RectF(x * k, y * k, (x + w) * k, (y + h) * k);
        }

        private static String fit(String s, Paint p, float room) {
            return TextUtils.ellipsize(s, new TextPaint(p), room, TextUtils.TruncateAt.END).toString();
        }

        private static final String[][] CHOICE = {
                {L10n.t("Close apps"), L10n.t("Apps left running"), "stop"},
                {L10n.t("Switch off extras"), L10n.t("Only the safe ones"), "power"},
                {L10n.t("Lite mode"), L10n.t("Lighter look"), "leaf"},
                {L10n.t("Undo everything"), L10n.t("Back as it was"), "redo"}};
        private static final int M_BIG = 0, M_LIST = 5;   // menuSel: 0 the big button, 1-4 the tiles (two by two), 5 the list link

        // The ring's sweep: from the last value to the new one, eased, whenever the memory is read again.
        private float ringFrom, ringTo;
        private long ringAt = -1, openedAt = android.os.SystemClock.uptimeMillis();
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float ring(long now) {
            float want = totalMb > 0 ? freeMb / (float) totalMb : 0;
            if (want != ringTo) {   // a new value: from where the ring is shown now (worked out, not asked again)
                ringFrom = ringAt < 0 ? 0 : ringShown(now);
                ringTo = want;
                ringAt = now;
            }
            return ringShown(now);
        }

        private float ringShown(long now) {
            float f = Lite.on || ringAt < 0 ? 1 : Math.min(1, (now - ringAt) / 900f);
            f = 1 - (1 - f) * (1 - f) * (1 - f);
            return ringFrom + (ringTo - ringFrom) * f;
        }

        /**
         * The card: on the left a ring of how much memory is available (it sweeps round when it changes); on the right
         * the big button, the four choices two by two with their icons and how they stand, and the list link; under them
         * what was just done.
         */
        private void drawMenu(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            float W = 1360, H = 760, L0 = (1920 - W) / 2, T0 = (1080 - H) / 2;
            int accent = Frost.VIOLET;
            // A soft glow of the accent behind the card.
            fill.setShader(new android.graphics.RadialGradient(960 * k, 540 * k, 900 * k, (accent & 0xFFFFFF) | 0x40000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setShader(null);
            RectF p = gr(L0, T0, W, H);
            Frost.panel(c, p, 44 * k, 1, k);
            // ---- left: the ring
            float cx = (L0 + 230) * k, cy = (T0 + 330) * k, R = 150 * k, v = ring(now);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setStrokeWidth(22 * k);
            arc.setShader(null);
            arc.setColor(0x1FFFFFFF);
            RectF ov = new RectF(cx - R, cy - R, cx + R, cy + R);
            c.drawArc(ov, 0, 360, false, arc);
            int good = v < 0.2f ? 0xFFFF7A6B : Frost.CYAN;
            arc.setColor(Color.WHITE);   // the gradient takes its alpha from the paint
            arc.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{good, accent | 0xFF000000, good}, null));
            c.save();
            c.rotate(-90, cx, cy);
            c.drawArc(ov, 0, Math.max(2, 360 * v), false, arc);
            c.restore();
            arc.setShader(null);
            String mb = Math.round(totalMb * v) + "";
            c.drawText(mb, cx - title.measureText(mb) / 2, cy + 8 * k, title);
            String l1 = L10n.t("MB available"), l2 = "of " + totalMb + L10n.t(" MB");
            c.drawText(l1, cx - muted.measureText(l1) / 2, cy + 48 * k, muted);
            c.drawText(l2, (L0 + 230) * k - muted.measureText(l2) / 2, cy + R + 64 * k, muted);
            String l3 = freedMb > 0 ? "+" + freedMb + L10n.t(" MB freed") : L10n.t("It changes on its own");
            Paint tiny = new Paint(chip);
            tiny.setColor(freedMb > 0 ? 0xFF22C55E : 0x99FFFFFF);
            c.drawText(l3, (L0 + 230) * k - tiny.measureText(l3) / 2, cy + R + 100 * k, tiny);
            // ---- right: title, big button, tiles, link
            float x = L0 + 470, rw = W - 470 - 56;
            c.drawText(L10n.t("Speed up your TV"), x * k, (T0 + 92) * k, title);
            c.drawText(L10n.t("Make TV Town and your TV feel quicker"), x * k, (T0 + 134) * k, muted);
            RectF bigB = gr(x, T0 + 168, rw, 120);
            boolean bf = menuSel == M_BIG;
            c.save();
            if (bf) c.scale(1.02f, 1.02f, bigB.centerX(), bigB.centerY());
            Frost.hero(c, bigB, 36 * k, bf, k);
            if (bf && !Lite.on) {   // a band of light sliding across it
                float t = ((now - openedAt) % 2400) / 2400f, sx = bigB.left - 200 * k + (bigB.width() + 400 * k) * t;
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(bigB, 36 * k, 36 * k, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                fill.setShader(new LinearGradient(sx - 120 * k, 0, sx + 120 * k, 0, new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.drawRect(bigB, fill);
                fill.setShader(null);
                c.restore();
            }
            Bitmap bolt = LineIcon.icon("sparkle", 46 * k);
            c.drawBitmap(bolt, bigB.left + 36 * k, bigB.centerY() - bolt.getHeight() / 2f, null);
            c.drawText(L10n.t("Speed it all up"), bigB.left + 104 * k, bigB.top + 54 * k, big);
            c.drawText(fit(L10n.t("Closes apps, switches off extras and turns on Lite mode"), muted, bigB.width() - 140 * k), bigB.left + 104 * k, bigB.top + 90 * k, muted);
            c.restore();
            float tw = (rw - 20) / 2, th = 132;
            if (steps) {   // Speed it all up, step by step: a spinner while working, then a tick and what it did
                RectF box = gr(x, T0 + 312, rw, 2 * th + 18);
                fill.setColor(0x14FFFFFF);
                c.drawRoundRect(box, 28 * k, 28 * k, fill);
                for (int i = 0; i < STEPS; i++) {
                    float sy = (T0 + 312 + 56 + i * 92) * k, sx = (x + 56) * k;
                    if (stepState[i] == 2) {
                        fill.setColor(0xFF22C55E);
                        c.drawCircle(sx, sy, 22 * k, fill);
                        Frost.tick(c, sx, sy, 12 * k, k);
                    } else if (stepState[i] == 1) {
                        arc.setStyle(Paint.Style.STROKE);
                        arc.setStrokeWidth(5 * k);
                        arc.setShader(null);
                        arc.setColor(0x33FFFFFF);
                        c.drawCircle(sx, sy, 20 * k, arc);
                        arc.setColor(Frost.CYAN);
                        float start = (now % 1000) / 1000f * 360;
                        c.drawArc(new RectF(sx - 20 * k, sy - 20 * k, sx + 20 * k, sy + 20 * k), start, 100, false, arc);
                    } else {
                        arc.setStyle(Paint.Style.STROKE);
                        arc.setStrokeWidth(3 * k);
                        arc.setShader(null);
                        arc.setColor(0x33FFFFFF);
                        c.drawCircle(sx, sy, 20 * k, arc);
                    }
                    Paint tp = stepState[i] == 0 ? muted : text;
                    c.drawText(fit(stepText[i] == null ? "" : stepText[i], tp, (rw - 140) * k), sx + 48 * k, sy + 10 * k, tp);
                }
                if (!allRunning) {
                    String hint = L10n.t("Press any button to go on");
                    c.drawText(hint, (x + 56) * k + 48 * k, (T0 + 312 + 2 * th + 18 - 24) * k, chip);
                }
                if (allRunning && !Lite.on) postInvalidateOnAnimation();
                else if (allRunning) postInvalidateDelayed(250);
            }
            for (int i = 0; i < CHOICE.length && !steps; i++) {
                RectF r = gr(x + (i % 2) * (tw + 20), T0 + 312 + (i / 2) * (th + 18), tw, th);
                boolean f = menuSel == i + 1;
                c.save();
                if (f) {
                    c.scale(1.03f, 1.03f, r.centerX(), r.centerY());
                    Frost.plate(c, r, 28 * k, k);
                } else {
                    fill.setColor(0x14FFFFFF);
                    c.drawRoundRect(r, 28 * k, 28 * k, fill);
                }
                // the icon in a soft disc of the accent
                float ix = r.left + 52 * k, iy = r.centerY();
                fill.setColor((accent & 0xFFFFFF) | (f ? 0x66000000 : 0x40000000));
                c.drawCircle(ix, iy, 32 * k, fill);
                Bitmap g = LineIcon.icon(CHOICE[i][2], 32 * k);
                c.drawBitmap(g, ix - g.getWidth() / 2f, iy - g.getHeight() / 2f, null);
                String right = i == 2 ? (Lite.on ? L10n.t("On") : L10n.t("Off")) : "";
                float room = r.width() - 124 * k - (right.isEmpty() ? 20 * k : chip.measureText(right) + 84 * k);
                c.drawText(fit(CHOICE[i][0], text, room), r.left + 100 * k, r.centerY() - 6 * k, text);
                String sub = i != 1 ? CHOICE[i][1] : canSwitch ? (safeOn() == 0 ? L10n.t("All switched off") : safeOn() + L10n.t(" safe apps still on"))
                        : loading ? L10n.t("Checking\u2026") : CHOICE[i][1];
                c.drawText(fit(sub, muted, room), r.left + 100 * k, r.centerY() + 30 * k, muted);
                if (!right.isEmpty()) {
                    RectF pill = new RectF(r.right - 24 * k - chip.measureText(right) - 40 * k, r.centerY() - 21 * k, r.right - 24 * k, r.centerY() + 21 * k);
                    fill.setColor(i == 2 && Lite.on ? 0xFF22C55E : 0x33FFFFFF);
                    c.drawRoundRect(pill, 21 * k, 21 * k, fill);
                    c.drawText(right, pill.centerX() - chip.measureText(right) / 2, pill.centerY() + 8 * k, chip);
                }
                c.restore();
            }
            // the link to the list
            String link = L10n.t("Choose apps one by one  ›");
            RectF lr = gr(x, T0 + 612, small.measureText(link) / k + 48, 54);
            if (menuSel == M_LIST) Frost.plate(c, lr, 27 * k, k);
            c.drawText(link, lr.left + 24 * k, lr.centerY() + 8 * k, small);
            if (!note.isEmpty()) {   // what was just done
                Bitmap ok = LineIcon.icon("check", 26 * k);
                c.drawBitmap(ok, x * k, (T0 + 686) * k, null);
                StaticText.draw(c, note, muted, x * k + 40 * k, (T0 + 712) * k, (rw - 40) * k, 2);
            }
            boolean moving = !Lite.on && (bf || now - ringAt < 950);
            if (moving) postInvalidateOnAnimation();
        }

        /** The safe ones still on. */
        private int safeOn() {
            int n = 0;
            for (Row r : rows) if (r.app.safe && r.on && !r.needed) n++;
            return n;
        }

        /** Back: from the app list to the card; from the card, out. */
        boolean back() {
            if (menu) return false;
            menu = true;
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            Frost.frame(this);   // selections glide (Frost.plate)
            fill.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), 0xFF101418, 0xFF06070A, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setShader(null);
            if (menu) {
                drawMenu(c);
                return;
            }
            c.drawText(L10n.t("Apps you can switch off"), 80 * k, 100 * k, title);
            // Memory: a bar with what is free.
            String m = freeMb + L10n.t(" MB available of ") + totalMb + L10n.t(" MB");
            c.drawText(m, 80 * k, 160 * k, text);
            c.drawText(L10n.t("It moves on its own as the TV starts and stops apps"), 80 * k + text.measureText(m) + 28 * k, 160 * k, muted);
            RectF bar = gr(80, 180, 760, 14);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(bar, 7 * k, 7 * k, fill);
            float used = totalMb > 0 ? 1 - freeMb / (float) totalMb : 0;
            fill.setColor(used > 0.8f ? 0xFFFF7A6B : Frost.CYAN);
            c.drawRoundRect(new RectF(bar.left, bar.top, bar.left + bar.width() * used, bar.bottom), 7 * k, 7 * k, fill);
            // The buttons.
            float x = 80;
            for (int i = 0; i < BUTTONS; i++) {
                float w = small.measureText(BUTTON[i]) / k + 64;
                RectF b = gr(x, 230, w, 68);
                boolean f = focus == -1 && button == i;
                if (f) Frost.plate(c, b, 34 * k, k);
                else Frost.ghost(c, b, 34 * k, false, k);
                c.drawText(BUTTON[i], b.left + 32 * k, b.centerY() + 9 * k, small);
                x += w + 16;
            }
            if (!note.isEmpty()) {   // the latest word, under the buttons, across the page
                StaticText.draw(c, note, muted, 80 * k, 330 * k, 1760 * k, 2);
            }
            drawList(c);
            drawDetail(c);
        }

        private float noteH() {
            return note.isEmpty() ? 0 : note.length() > 110 ? 70 : 40;
        }

        /** The two groups, a heading over each; rows scroll to keep the chosen one in view. */
        private void drawList(Canvas c) {
            float y0 = LIST_TOP + noteH();
            if (loading) {
                c.drawText(L10n.t("Checking…"), LIST_L * k, (y0 + 40) * k, muted);
                return;
            }
            int fitRows = (int) ((LIST_BOTTOM - y0 - 2 * HEAD_H) / ROW_H);
            if (focus >= 0) {
                if (focus < top) top = focus;
                if (focus >= top + fitRows) top = focus - fitRows + 1;
            }
            c.save();
            c.clipRect(0, y0 * k, LIST_R * k + 20 * k, getHeight());
            float y = y0;
            Boolean lastSafe = null;
            for (int i = top; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (lastSafe == null || lastSafe != r.app.safe) {
                    c.drawText(r.app.safe ? L10n.t("SAFE TO SWITCH OFF") : L10n.t("ONLY IF YOU DON'T USE IT"), (LIST_L + 20) * k, (y + 38) * k, chip);
                    y += HEAD_H;
                    lastSafe = r.app.safe;
                }
                if (y > 1080) break;
                RectF rr = gr(LIST_L, y, LIST_R - LIST_L, ROW_H - 8);
                if (focus == i) Frost.plate(c, rr, 22 * k, k);
                text.setAlpha(r.on ? 255 : 150);
                c.drawText(fit(r.app.name, text, 520 * k), rr.left + 24 * k, rr.centerY() + 10 * k, text);
                text.setAlpha(255);
                String state = r.busy ? "…" : r.needed ? L10n.t("Needed") : r.on ? L10n.t("On") : switchedByUs().contains(r.pkg) ? L10n.t("Off") : L10n.t("Off by TV");
                RectF pill = new RectF(rr.right - (chip.measureText(state) + 56 * k) - 20 * k, rr.centerY() - 22 * k, rr.right - 20 * k, rr.centerY() + 22 * k);
                fill.setColor(r.on ? 0x33FFFFFF : 0x22FF7A6B);
                c.drawRoundRect(pill, 22 * k, 22 * k, fill);
                c.drawText(state, pill.centerX() - chip.measureText(state) / 2, pill.centerY() + 8 * k, chip);
                if (r.kb > 0) {
                    String mb = Cleanup.mb(r.kb);
                    c.drawText(mb, pill.left - 24 * k - muted.measureText(mb), rr.centerY() + 9 * k, muted);
                }
                y += ROW_H;
            }
            c.restore();
        }

        /** The chosen app: what it is, what you'd lose, and what OK does. */
        private void drawDetail(Canvas c) {
            if (focus < 0 || focus >= rows.size()) {
                RectF p = gr(1140, LIST_TOP + noteH(), 700, 330);
                Frost.panel(c, p, 32 * k, 1, k);
                String[] lines = {L10n.t("Close apps frees memory now:"), L10n.t("apps you left running behind."), L10n.t("Home screens are never closed."),
                        "", L10n.t("Switching off lasts until you turn it back on."), L10n.t("Nothing is ever uninstalled.")};
                for (int i = 0; i < lines.length; i++) c.drawText(lines[i], p.left + 36 * k, p.top + (64 + i * 42) * k, i == 0 ? big : muted);
                return;
            }
            Row r = rows.get(focus);
            RectF p = gr(1140, LIST_TOP + noteH(), 700, 400);
            Frost.panel(c, p, 32 * k, 1, k);
            float tx = p.left + 36 * k, room = p.width() - 72 * k;
            c.drawText(fit(r.app.name, big, room), tx, p.top + 64 * k, big);
            StaticText.draw(c, r.app.what, muted, tx, p.top + 108 * k, room, 2);
            float y = p.top + 196 * k;
            if (!r.app.ifOff.isEmpty()) {
                StaticText.draw(c, L10n.t("Off: ") + r.app.ifOff, small, tx, y, room, 2);
                y += 90 * k;
            }
            if (r.kb > 0) {
                c.drawText(L10n.t("Using ") + Cleanup.mb(r.kb) + L10n.t(" now"), tx, y, small);
                y += 50 * k;
            }
            String ok = r.needed ? L10n.t("Your TV is using it: it stays on") : !canSwitch ? L10n.t("Allow TV Town first (see above)")
                    : r.on ? L10n.t("OK switches it off") : L10n.t("OK turns it back on");
            c.drawText(ok, tx, p.bottom - 36 * k, chip);
        }

        // ---- the remote --------------------------------------------------------------------------------------------

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (menu && steps) {   // the steps stay while working; after, any button goes back to the choices
                if (!allRunning) {
                    steps = false;
                    invalidate();
                }
                return true;
            }
            if (menu) {   // the big button; tiles 1 2 / 3 4; the list link under them
                int m = menuSel;
                if (code == KeyEvent.KEYCODE_DPAD_UP) m = m == M_LIST ? 3 : m >= 3 ? m - 2 : M_BIG;
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) m = m == M_BIG ? 1 : m <= 2 ? m + 2 : M_LIST;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) m = m == 1 || m == 3 ? m + 1 : m;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) m = m == 2 || m == 4 ? m - 1 : m;
                else if (ok) {
                    switch (menuSel) {
                        case M_BIG: doAll(); break;
                        case 1: closeBackground(); break;
                        case 2: switchOffSafe(); break;
                        case 3: lite(); break;
                        case 4: undoAll(); break;
                        default:   // the list of apps, to choose one by one
                            menu = false;
                            focus = rows.isEmpty() ? -1 : 0;
                    }
                } else return super.onKeyDown(code, e);
                menuSel = m;
                invalidate();
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (focus >= 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (focus < rows.size() - 1) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus == -1) button = Math.max(0, button - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus == -1) button = Math.min(BUTTONS - 1, button + 1);
                    break;
                default:
                    if (!ok) return super.onKeyDown(code, e);
                    if (focus >= 0) toggle(rows.get(focus));
                    else if (button == B_CLOSE) closeBackground();
                    else if (button == B_SAFE) switchOffSafe();
                    else restoreAll();
            }
            invalidate();
            return true;
        }
    }

    /** A few lines of wrapped text. */
    private static final class StaticText {
        static void draw(Canvas c, String s, Paint p, float x, float y, float width, int maxLines) {
            android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(s, 0, s.length(), new TextPaint(p), Math.max(1, (int) width))
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
            c.save();
            c.translate(x, y - p.getTextSize());
            l.draw(c);
            c.restore();
        }
    }
}
