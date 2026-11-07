package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class TmdbTest {

    @Test
    public void parsesMixedResultsAndDropsBadOnes() throws Exception {
        JSONArray a = new JSONArray("["
                + "{\"media_type\":\"movie\",\"id\":157336,\"title\":\"Interstellar\",\"release_date\":\"2014-11-05\","
                + "\"overview\":\"Space.\",\"popularity\":99.5,\"vote_average\":8.4,\"backdrop_path\":\"/abc.jpg\",\"poster_path\":\"/p.jpg\"},"
                + "{\"media_type\":\"tv\",\"id\":66732,\"name\":\"Stranger Things\",\"first_air_date\":\"2016-07-15\"},"
                + "{\"media_type\":\"person\",\"id\":1,\"name\":\"Someone\"},"
                + "{\"media_type\":\"movie\",\"id\":0,\"title\":\"No id\"},"
                + "{\"media_type\":\"movie\",\"id\":5,\"title\":\"Bad path\",\"backdrop_path\":\"https://evil.example/x.jpg\",\"release_date\":\"soon\"}"
                + "]");
        List<Tmdb.Title> l = Tmdb.parse(a, null);
        assertEquals(3, l.size());
        Tmdb.Title t = l.get(0);
        assertEquals("Interstellar", t.title);
        assertEquals(2014, t.year);
        assertEquals("movie:157336", t.key());
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", t.backdrop());
        assertEquals("https://image.tmdb.org/t/p/w300/abc.jpg", t.backdropCard());
        assertTrue(l.get(1).isSeries());
        assertNull("only TMDB image paths are used", l.get(2).backdrop());
        assertEquals("", l.get(2).date);
    }

    @Test
    public void metaListsWhatIsKnown() throws Exception {
        Tmdb.Title t = Tmdb.parse(new JSONArray("[{\"id\":1,\"title\":\"A\",\"release_date\":\"2020-01-01\"}]"), "movie").get(0);
        assertEquals("Movie  ·  2020", t.meta());
        t.genres = "Drama";
        t.runtime = 130;
        assertEquals("Movie  ·  Drama  ·  2020  ·  2 h 10 min", t.meta());
    }

    @Test
    public void readsTheOriginalLanguage() throws Exception {
        List<Tmdb.Title> l = Tmdb.parse(new JSONArray("["
                + "{\"id\":1,\"title\":\"A\",\"original_language\":\"hi\",\"release_date\":\"2024-01-01\"},"
                + "{\"id\":2,\"title\":\"B\",\"original_language\":\"<b>\"}]"), "movie");
        assertEquals("hi", l.get(0).language);
        assertEquals("Movie  ·  Hindi  ·  2024", l.get(0).meta());
        assertEquals("invalid codes are dropped", "", l.get(1).language);
    }

    @Test
    public void languageListStartsWithAll() {
        assertEquals("All", Tmdb.LANGUAGES[0][0]);
        assertEquals("", Tmdb.LANGUAGES[0][1]);
        assertEquals("Korean", Tmdb.languageName("ko"));
        assertEquals("Chinese", Tmdb.languageName("zh"));
        assertEquals("", Tmdb.languageName("xx"));
    }

    @Test
    public void randomPicksAreShuffledAndVaried() {
        java.util.Random r = new java.util.Random(7);
        Tmdb.Category c = Tmdb.randomPicks(r);
        assertTrue(c.random);
        assertTrue(c.pageOffset >= 0 && c.pageOffset < 8);
        assertTrue(c.minVotes > 0);
    }

    @Test
    public void picksTheOfficialYouTubeTrailer() throws Exception {
        JSONObject v = new JSONObject("{\"results\":["
                + "{\"site\":\"Vimeo\",\"key\":\"zzzzzzzzzzz\",\"type\":\"Trailer\",\"official\":true},"
                + "{\"site\":\"YouTube\",\"key\":\"aaaaaaaaaaa\",\"type\":\"Featurette\",\"official\":true},"
                + "{\"site\":\"YouTube\",\"key\":\"bbbbbbbbbbb\",\"type\":\"Trailer\",\"official\":false},"
                + "{\"site\":\"YouTube\",\"key\":\"ccccccccccc\",\"type\":\"Trailer\",\"official\":true},"
                + "{\"site\":\"YouTube\",\"key\":\"<script>xx\",\"type\":\"Trailer\",\"official\":true}]}");
        assertEquals("ccccccccccc", Tmdb.trailerKey(v));
    }

    @Test
    public void noTrailerWithoutATrailerOrTeaser() throws Exception {
        assertNull(Tmdb.trailerKey(new JSONObject("{\"results\":[{\"site\":\"YouTube\",\"key\":\"aaaaaaaaaaa\",\"type\":\"Clip\"}]}")));
        assertNull(Tmdb.trailerKey(null));
    }
}
