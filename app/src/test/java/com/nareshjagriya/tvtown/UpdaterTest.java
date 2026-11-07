package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class UpdaterTest {

    @Test
    public void versionsCompareByNumber() {
        assertTrue(Updater.newer("1.10", "1.9"));
        assertTrue(Updater.newer("1.2.1", "1.2"));
        assertTrue(Updater.newer("2.0", "1.9.9"));
        assertFalse(Updater.newer("1.2", "1.2"));
        assertFalse(Updater.newer("1.2", "1.2.0"));
        assertFalse(Updater.newer("1.1", "1.2"));
        assertTrue(Updater.newer("1.3-beta", "1.2"));
    }

    @Test
    public void aNewerReleaseGivesItsApk() throws Exception {
        JSONObject o = new JSONObject("{\"tag_name\":\"v1.3\",\"body\":\"New things\",\"assets\":["
                + "{\"name\":\"tv-town-launcher-1.3-debug.apk\",\"browser_download_url\":\"https://github.com/x/debug.apk\"},"
                + "{\"name\":\"tv-town-launcher-1.3-release.apk\",\"browser_download_url\":\"https://github.com/x/release.apk\"}]}");
        Updater.Release r = Updater.parse(o, "1.2");
        assertEquals("1.3", r.version);
        assertEquals("https://github.com/x/release.apk", r.apkUrl);
        assertEquals("New things", r.notes);
    }

    @Test
    public void oldDraftOrApklessReleasesAreIgnored() throws Exception {
        String apk = ",\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"https://github.com/a.apk\"}]}";
        assertNull(Updater.parse(new JSONObject("{\"tag_name\":\"v1.2\"" + apk), "1.2"));
        assertNull(Updater.parse(new JSONObject("{\"tag_name\":\"v1.3\",\"draft\":true" + apk), "1.2"));
        assertNull(Updater.parse(new JSONObject("{\"tag_name\":\"v1.3\",\"prerelease\":true" + apk), "1.2"));
        assertNull(Updater.parse(new JSONObject("{\"tag_name\":\"v1.3\",\"assets\":[]}"), "1.2"));
    }
}
