package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class LibraryTest {

    @Test
    public void sameTitleFromTmdbOrJustWatchHasOneKey() {
        Tmdb.Title tmdb = new Tmdb.Title(155, "movie", "The Dark Knight", "", "2008-07-16", 0, 8.5, null, null);
        Tmdb.Title jw = new Tmdb.Title(155, "movie", "The Dark Knight", "", "2008-07-16", 0, 9.1, null, null);
        jw.jwId = "tm12345";
        assertEquals(Library.keyOf(tmdb), Library.keyOf(jw));
        Tmdb.Title noTmdb = new Tmdb.Title(0, "tv", "Something", "", "", 0, 0, null, null);
        noTmdb.jwId = "ts99";
        assertEquals("jw:ts99", Library.keyOf(noTmdb));
    }

    @Test
    public void entriesSurviveARoundTripAndBadOnesAreRefused() throws Exception {
        JSONObject o = new JSONObject().put("key", "movie:155").put("type", "movie").put("title", "The Dark Knight")
                .put("poster", "https://images.justwatch.com/poster/1/s332/x.jpg").put("year", 2008).put("status", 2)
                .put("rating", 9).put("tomato", 94).put("tmdb", 155).put("imdb", 9.1).put("time", 5L);
        Library.Entry e = Library.Entry.from(o);
        assertTrue(e.valid());
        Library.Entry back = Library.Entry.from(e.json());
        assertEquals(9, back.rating);
        assertEquals(Library.WATCHED, back.status);
        assertEquals("The Dark Knight", back.title);
        // Out-of-range values are clamped; odd keys and non-https posters are refused.
        Library.Entry odd = Library.Entry.from(new JSONObject().put("key", "../etc").put("title", "x").put("rating", 99)
                .put("status", 7).put("poster", "http://evil/x.jpg"));
        assertFalse(odd.valid());
        assertEquals(10, odd.rating);
        assertEquals(Library.WATCHED, odd.status);
        assertEquals(null, odd.poster);
    }

    @Test
    public void readsAwardWinnersFromWikidata() throws Exception {
        String json = "{\"results\":{\"bindings\":[{\"tmdb\":{\"type\":\"literal\",\"value\":\"20453\"}},"
                + "{\"tmdb\":{\"type\":\"literal\",\"value\":\"not-a-number\"}},{\"other\":{}}]}}";
        Set<String> out = new HashSet<>();
        Awards.parse(json, "movie", out);
        assertEquals(1, out.size());
        assertTrue(out.contains("movie:20453"));
    }

    @Test
    public void imagesAreDecodedNoBiggerThanNeeded() {
        // maxWidth is 1920 until init() (the screen width).
        assertEquals(2, ImageCache.sampleFor(1920, "https://i.ytimg.com/vi/x/maxres.jpg", 0));   // thumbnails: half the screen
        assertEquals(1, ImageCache.sampleFor(780, "https://image.tmdb.org/t/p/w780/x.jpg", 0));
        assertEquals(1, ImageCache.sampleFor(320, "https://i.ytimg.com/vi/x/mqdefault.jpg", 0));
        assertEquals(4, ImageCache.sampleFor(1920, "https://image.tmdb.org/t/p/w1280/x.jpg", 400));   // a card's own width
    }
}
