package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class DeepLinksTest {

    @Test
    public void onlyNumericTmdbIdsAreAccepted() {
        assertTrue(DeepLinks.validTmdbId("157336"));
        assertFalse(DeepLinks.validTmdbId("0"));
        assertFalse(DeepLinks.validTmdbId("12a"));
        assertFalse(DeepLinks.validTmdbId("1\" } ?x ?y ?z . #"));
        assertFalse(DeepLinks.validTmdbId(""));
        assertFalse(DeepLinks.validTmdbId(null));
        assertFalse(DeepLinks.validTmdbId("1234567890"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sparqlRefusesInjectedIds() {
        DeepLinks.sparql("movie", "1\" . ?s ?p ?o");
    }

    @Test
    public void sparqlUsesTheRightTmdbProperty() {
        assertTrue(DeepLinks.sparql("movie", "157336").contains("wdt:P4947 \"157336\""));
        assertTrue(DeepLinks.sparql("tv", "66732").contains("wdt:P4983 \"66732\""));
        String q = DeepLinks.sparql("tv", "66732");
        for (String p : new String[]{"P1874", "P11049", "P8055", "P9586", "P9751"}) assertTrue(p, q.contains(p));
    }

    @Test
    public void parsesSparqlResults() throws Exception {
        JSONObject r = new JSONObject("{\"results\":{\"bindings\":[{"
                + "\"nf\":{\"type\":\"literal\",\"value\":\"70305903\"},"
                + "\"pv\":{\"type\":\"literal\",\"value\":\"B00TU9UFTS\"},"
                + "\"am\":{\"type\":\"literal\",\"value\":\"umc.cmc.1vrwat5k1ucm5k42q97ioqyq3\"},"
                + "\"hs\":{\"type\":\"literal\",\"value\":\"javascript:alert(1)\"}}]}}");
        DeepLinks.Ids ids = DeepLinks.parseSparql(r);
        assertNotNull(ids);
        assertEquals("70305903", ids.netflix);
        assertEquals("B00TU9UFTS", ids.prime);
        assertEquals("umc.cmc.1vrwat5k1ucm5k42q97ioqyq3", ids.appleMovie);
        assertNull("invalid ids are dropped", ids.hotstar);
    }

    @Test
    public void noSparqlRowMeansNoItem() throws Exception {
        assertNull(DeepLinks.parseSparql(new JSONObject("{\"results\":{\"bindings\":[]}}")));
        assertNull(DeepLinks.parseSparql(new JSONObject("{}")));
    }

    @Test
    public void parsesWikidataClaims() throws Exception {
        JSONObject claims = new JSONObject("{\"P1874\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"80057281\"}}}],"
                + "\"P11049\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"1260023113\"}}}],"
                + "\"P8055\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"not\":\"a string\"}}}}]}");
        DeepLinks.Ids ids = DeepLinks.parseClaims(claims);
        assertEquals("80057281", ids.netflix);
        assertEquals("1260023113", ids.hotstar);
        assertNull(ids.prime);
        assertTrue(ids.any());
    }

    @Test
    public void idsSurviveTheCacheRoundTrip() throws Exception {
        DeepLinks.Ids ids = new DeepLinks.Ids();
        ids.netflix = "80057281";
        ids.appleShow = "umc.cmc.3ib2d0zyxnhiy0j8jpg6qb6kv";
        DeepLinks.Ids back = DeepLinks.Ids.fromJson(new JSONObject(ids.toJson().toString()));
        assertEquals(ids.netflix, back.netflix);
        assertEquals(ids.appleShow, back.appleShow);
        assertNull(back.prime);
        assertFalse(new DeepLinks.Ids().any());
    }

    @Test
    public void buildsServiceUrls() {
        DeepLinks.Ids ids = new DeepLinks.Ids();
        ids.netflix = "80057281";
        ids.hotstar = "1260023113";
        ids.prime = "B00TU9UFTS";
        ids.appleMovie = "umc.cmc.1vrwat5k1ucm5k42q97ioqyq3";
        assertEquals("https://www.netflix.com/title/80057281", DeepLinks.titleUrl(Services.NETFLIX, true, ids));
        assertEquals("https://www.hotstar.com/in/1260023113", DeepLinks.titleUrl(Services.HOTSTAR, true, ids));
        assertEquals("https://app.primevideo.com/detail?asin=B00TU9UFTS", DeepLinks.titleUrl(Services.PRIME, false, ids));
        assertEquals("https://tv.apple.com/in/movie/umc.cmc.1vrwat5k1ucm5k42q97ioqyq3", DeepLinks.titleUrl(Services.APPLE, false, ids));
        assertNull("no show id", DeepLinks.titleUrl(Services.APPLE, true, ids));
        assertNull("no Wikidata id for Zee5", DeepLinks.titleUrl(Services.ZEE5, false, ids));
    }
}
