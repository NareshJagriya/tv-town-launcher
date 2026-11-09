package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class DiscoverKindsTest {

    @Test
    public void everyKindButNewHasACategory() {
        for (DiscoverKinds.Kind k : DiscoverKinds.ALL) {
            if (k.id.equals(DiscoverKinds.NEW)) assertNull(DiscoverKinds.category(k));
            else assertNotNull(k.name, DiscoverKinds.category(k));
        }
    }

    @Test
    public void idsAreUniqueAndLinkFriendly() {
        Set<String> seen = new HashSet<>();
        for (DiscoverKinds.Kind k : DiscoverKinds.ALL) {
            assertTrue(k.id, k.id.matches("[a-z0-9]+(-[a-z0-9]+)*"));
            assertTrue("twice: " + k.id, seen.add(k.id));
        }
    }

    @Test
    public void findsKindsFromLooseWords() {
        assertEquals("anime", DiscoverKinds.find("Anime").id);
        assertEquals("k-drama", DiscoverKinds.find("kdrama").id);
        assertEquals("k-drama", DiscoverKinds.find("K Drama").id);
        assertEquals("top-rated", DiscoverKinds.find("top rated").id);
        assertEquals("trending", DiscoverKinds.find("popular").id);
        assertEquals("action-comedy", DiscoverKinds.find("Action Comedy").id);
        assertNull(DiscoverKinds.find("interstellar"));
        assertNull(DiscoverKinds.find(""));
    }

    @Test
    public void ownLanguageKinds() {
        assertEquals("ja", DiscoverKinds.category(DiscoverKinds.find("anime")).language);
        assertEquals("ko", DiscoverKinds.category(DiscoverKinds.find("k-drama")).language);
    }
}
