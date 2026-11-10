package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContinueRowTest {

    @Test
    public void rowsAboutCarryingOn() {
        for (String n : new String[]{"Continue Watching", "Continue watching for Naresh", "Resume", "Keep Watching",
                "Recently watched", "Jump back in", "देखना जारी रखें"}) {
            assertTrue(n, TvTools.continueRow(n));
        }
    }

    @Test
    public void notRowsOfPicks() {
        for (String n : new String[]{"Popular on Netflix", "Top 10 in India", "Recommended for you", "New releases", "Trending"}) {
            assertFalse(n, TvTools.continueRow(n));
        }
    }

    @Test
    public void netflixMarksWhatYouAreWatching() {
        assertTrue(TvTools.markedToContinue("Continue Watching Episode 1"));
        assertTrue(TvTools.markedToContinue("Resume S2:E4"));
        assertFalse(TvTools.markedToContinue("A spy must continue the mission after her husband is taken."));
        assertFalse(TvTools.markedToContinue(""));
    }
}
