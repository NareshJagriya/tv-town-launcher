package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class CatalogTest {

    private static final String NODE = "{\"id\":\"tm1626286\",\"objectType\":\"MOVIE\",\"content\":{\"title\":\"Mardaani 3\","
            + "\"originalTitle\":\"मर्दानी 3\",\"originalReleaseYear\":2026,\"originalReleaseDate\":\"2026-01-30\",\"runtime\":150,"
            + "\"shortDescription\":\"A cop takes on a trafficking ring.\",\"externalIds\":{\"tmdbId\":\"1339876\"},"
            + "\"genres\":[{\"shortName\":\"act\"},{\"shortName\":\"crm\"},{\"shortName\":\"drm\"}],"
            + "\"clips\":[{\"externalId\":\"abcdefghijk\",\"provider\":\"YOUTUBE\"}],"
            + "\"backdrops\":[{\"backdropUrl\":\"/backdrop/340777357/{profile}/mardaani-3.{format}\"}],"
            + "\"posterUrl\":\"/poster/1/{profile}/x.{format}\",\"scoring\":{\"imdbScore\":7.1,\"imdbVotes\":1200}},"
            + "\"offers\":[{\"monetizationType\":\"FLATRATE\",\"standardWebURL\":\"https://www.netflix.com/title/82023351\",\"package\":{\"shortName\":\"nfx\"}},"
            + "{\"monetizationType\":\"RENT\",\"standardWebURL\":\"https://app.primevideo.com/detail?gti=x\",\"package\":{\"shortName\":\"prv\"}},"
            + "{\"monetizationType\":\"FLATRATE\",\"standardWebURL\":\"http://insecure.example\",\"package\":{\"shortName\":\"jhs\"}}]}";

    @Test
    public void justWatchTitlesBecomeSharedTitles() throws Exception {
        Tmdb.Title t = Catalog.fromJw(new JustWatch.Title(new JSONObject(NODE)), "hi");
        assertNotNull(t);
        assertEquals("movie", t.type);
        assertEquals(1339876, t.id);   // TMDB id kept, so Wikidata can still be asked
        assertEquals("jw:tm1626286", t.key());
        assertEquals(2026, t.year);
        assertEquals(150, t.runtime);
        assertEquals("Action, Crime", t.genres);
        assertEquals("abcdefghijk", t.trailer);
        assertEquals("hi", t.language);
        assertEquals("https://images.justwatch.com/backdrop/340777357/s1440/mardaani-3.jpg", t.backdrop());
        assertEquals("https://images.justwatch.com/backdrop/340777357/s640/mardaani-3.jpg", t.backdropCard());
        // Only subscription / free offers with https links count.
        assertEquals(Arrays.asList(Services.NETFLIX), t.streams);
        assertEquals("https://www.netflix.com/title/82023351", t.links.get("netflix"));
        assertTrue(t.detailed);
    }

    @Test
    public void imagePathsAreValidated() {
        assertNull(JustWatch.imagePath("https://evil.example/x.jpg"));
        assertNull(JustWatch.imagePath("/backdrop/1/{profile}/../../x.{format}"));
        assertEquals("/poster/12/{profile}/a-b.{format}", JustWatch.imagePath("/poster/12/{profile}/a-b.{format}"));
    }

    @Test
    public void directLinksOnlyGoToTheAppsOwnSites() {
        assertTrue(DeepLinks.directLink(Services.NETFLIX, "https://www.netflix.com/title/82023351"));
        assertTrue(DeepLinks.directLink(Services.PRIME, "https://app.primevideo.com/detail?gti=amzn1.dv.gti.x"));
        assertFalse(DeepLinks.directLink(Services.NETFLIX, "https://www.netflix.com.evil.example/title/1"));
        assertFalse(DeepLinks.directLink(Services.NETFLIX, "http://www.netflix.com/title/1"));
        assertFalse(DeepLinks.directLink(Services.HOTSTAR, "https://www.netflix.com/title/1"));
        assertFalse(DeepLinks.directLink(Services.APPLE, null));
    }

    @Test
    public void categoriesMapToJustWatchFilters() {
        Tmdb.Category mix = null;
        for (Tmdb.Category c : Tmdb.categories()) if (c.name.equals("Crime thriller")) mix = c;
        assertNotNull(mix);
        JustWatch.Category j = Catalog.category(mix, mix.movieGenres, "ko");
        assertEquals("crm", j.genres[0]);
        assertEquals("trl", j.requireGenre);   // both genres, not either
        assertEquals("KR", j.countries[0]);
        JustWatch.Category hindi = Catalog.category(Tmdb.categories().get(0), "", "hi");
        assertEquals(JustWatch.Script.DEVANAGARI, hindi.script);
        assertNull(hindi.genres);
        assertEquals("POPULAR", hindi.sort);
        assertEquals("RANDOM", Catalog.category(Tmdb.randomPicks(new java.util.Random(1)), "", "").sort);
    }

    @Test
    public void packageCodesFollowTheApps() {
        assertEquals(Arrays.asList("nfa", "nfk", "nfx"), Catalog.codes(Arrays.asList(Services.NETFLIX)));
        assertEquals(Arrays.asList("amp", "jhs", "prv"), Catalog.codes(Arrays.asList(Services.PRIME, Services.HOTSTAR)));
        assertEquals(Arrays.asList("dnp", "hlu", "mxx"), Catalog.codes(Arrays.asList(Services.DISNEY, Services.MAX, Services.HULU)));
    }
}
