package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServicesTest {

    @Test
    public void namesMatchTmdbProviderNames() {
        assertTrue(Services.matches(Services.APPLE, "Apple TV Plus"));
        assertTrue(Services.matches(Services.APPLE, "Apple TV+"));
        assertTrue(Services.matches(Services.HOTSTAR, "JioHotstar"));
        assertTrue(Services.matches(Services.HOTSTAR, "Hotstar"));
        assertTrue(Services.matches(Services.NETFLIX, "Netflix"));
        assertFalse(Services.matches(Services.NETFLIX, "Netflix Kids"));
        assertFalse(Services.matches(Services.PRIME, "Netflix"));
    }

    @Test
    public void normalisesPlusAndPunctuation() {
        assertEquals(Services.norm("Apple TV Plus"), Services.norm("apple tv+"));
    }

    @Test
    public void findsServicesByPackage() {
        assertSame(Services.NETFLIX, Services.byPackage("com.netflix.ninja"));
        assertNull(Services.byPackage("com.example.nothing"));
    }
}
