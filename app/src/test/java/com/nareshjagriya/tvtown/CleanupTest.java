package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CleanupTest {

    /** What a TV can't do without must never be on either list. */
    private static final List<String> VITAL = Arrays.asList("com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.android.tv.settings", "com.google.android.tungsten.setupwraith", "com.google.android.tv.remote.service",
            "com.google.android.inputmethod.latin", "com.mediatek.tvinput", "com.mediatek.wwtv.tvcenter", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.android.systemui", "com.android.providers.tv", "com.nareshjagriya.tvtown",
            "com.google.android.packageinstaller", "com.android.bluetooth");

    @Test
    public void nothingVitalCanBeSwitchedOff() {
        for (String p : VITAL) assertNull(p, Cleanup.find(p));
    }

    @Test
    public void listedNamesAreCleanAndOnce() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<Cleanup.App> l : Arrays.asList(Cleanup.SAFE, Cleanup.MAYBE)) {
            for (Cleanup.App a : l) {
                assertTrue(a.pkg, Cleanup.cleanName(a.pkg.replace(".*", ".x")));
                assertTrue("twice: " + a.pkg, seen.add(a.pkg));
                assertFalse(a.name.isEmpty() || a.what.isEmpty());
                assertTrue("a MAYBE app says what you lose: " + a.pkg, a.safe || !a.ifOff.isEmpty());
            }
        }
    }

    @Test
    public void wildcardMatchesTheMakersAutoInstaller() {
        assertNotNull(Cleanup.find("android.autoinstalls.config.tpv.device"));
        assertNull(Cleanup.find("android.autoinstalls"));
    }

    @Test
    public void onlyPackageNamesGoIntoCommands() {
        assertTrue(Cleanup.cleanName("com.google.android.feedback"));
        assertFalse(Cleanup.cleanName("com.x; reboot"));
        assertFalse(Cleanup.cleanName("com.x && rm"));
        assertFalse(Cleanup.cleanName("nodots"));
        assertFalse(Cleanup.cleanName(null));
    }

    @Test
    public void keepsTheTvRunning() {
        assertFalse(Cleanup.closable("com.google.android.gms"));
        assertFalse(Cleanup.closable("com.google.android.gms:persistent"));
        assertFalse(Cleanup.closable("com.mediatek.tv.service"));
        assertFalse(Cleanup.closable("com.android.systemui"));
        assertFalse(Cleanup.closable("com.nareshjagriya.tvtown"));
        assertFalse(Cleanup.closable("com.google.android.tvlauncher"));   // the TV's own home: it would start again at once
        assertFalse(Cleanup.closable("com.google.android.apps.tv.launcherx"));
        assertTrue(Cleanup.closable("com.android.vending"));
        assertTrue(Cleanup.closable("com.apple.atve.androidtv.appletv"));
    }

    @Test
    public void readsMeminfo() {
        String m = "Applications Memory Usage (in Kilobytes):\n\nTotal PSS by process:\n"
                + "     90,966K: com.nareshjagriya.tvtown (pid 25827 / activities)\n"
                + "     50,518K: com.android.vending (pid 26328)\n"
                + "     19,760K: com.android.vending:background (pid 26230)\n"
                + "     15,793K:  --native-window-size=1280x720  (pid 8201)\n"
                + "     13,262K: dtv_svc (pid 3186)\n"
                + "\nTotal PSS by OOM adjustment:\n    1,000K: Native\n";
        Map<String, Long> by = Cleanup.memoryByApp(m);
        assertEquals(Long.valueOf(70278), by.get("com.android.vending"));
        assertEquals(Long.valueOf(90966), by.get("com.nareshjagriya.tvtown"));
        assertNull(by.get("dtv_svc"));
        assertEquals(2, by.size());
        assertEquals("69 MB", Cleanup.mb(70278));
    }
}
