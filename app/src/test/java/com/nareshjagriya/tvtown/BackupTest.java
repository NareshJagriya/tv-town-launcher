package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

public class BackupTest {

    @Test
    public void everyKindOfSettingIsWrittenWithItsType() throws Exception {
        assertEquals("s", Backup.value("x").getString("t"));
        assertEquals(true, Backup.value(true).getBoolean("v"));
        assertEquals("i", Backup.value(3).getString("t"));
        assertEquals("l", Backup.value(3L).getString("t"));
        assertEquals(0.5, Backup.value(0.5f).getDouble("v"), 1e-6);
        JSONObject set = Backup.value(new HashSet<>(Arrays.asList("a", "b")));
        assertEquals("set", set.getString("t"));
        assertEquals(2, ((JSONArray) set.get("v")).length());
        assertNull(Backup.value(new Object()));
    }

    @Test
    public void onlyOurBackupsAreRestored() throws Exception {
        assertEquals(-1, Backup.fromJson(null, new JSONObject("{\"app\":\"Other\",\"version\":1,\"files\":{}}")));
        assertEquals(-1, Backup.fromJson(null, new JSONObject("{\"app\":\"Other\",\"version\":1,\"files\":{}}")));
        assertEquals(-1, Backup.fromJson(null, new JSONObject("{\"app\":\"TV Town\",\"version\":2,\"files\":{}}")));
        assertEquals(-1, Backup.fromJson(null, new JSONObject("{\"app\":\"TV Town\",\"version\":1}")));
    }
}
