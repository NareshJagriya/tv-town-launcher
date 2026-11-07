package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeTest {

    @Test
    public void easeStartsAndEndsExactly() {
        assertEquals(0f, Theme.ease(0f), 0f);
        assertEquals(1f, Theme.ease(1f), 0f);
        assertEquals(0f, Theme.ease(-1f), 0f);
        assertEquals(1f, Theme.ease(2f), 0f);
    }

    @Test
    public void easeRisesWithoutOvershoot() {
        float last = 0;
        for (int i = 1; i <= 100; i++) {
            float v = Theme.ease(i / 100f);
            assertTrue("monotonic at " + i, v >= last - 1e-4f);
            assertTrue("no overshoot at " + i, v <= 1.0001f);
            last = v;
        }
    }

    @Test
    public void easeIsAQuickStart() {
        // cubic-bezier(.22, 1, .36, 1) is well past half way a quarter of the way in.
        assertTrue(Theme.ease(0.25f) > 0.6f);
    }

    @Test
    public void progressClampsAndHandlesZeroDuration() {
        assertEquals(0f, Theme.progress(100, 200, 500), 0f);
        assertEquals(1f, Theme.progress(1000, 200, 500), 0f);
        assertEquals(1f, Theme.progress(0, 0, 0), 0f);
    }

    @Test
    public void motionTimesAreWithinTheSpec() {
        assertTrue(Theme.MOVE_MS >= 450 && Theme.MOVE_MS <= 650);
        assertTrue(Theme.FADE_MS >= 150 && Theme.FADE_MS <= 650);
    }
}
