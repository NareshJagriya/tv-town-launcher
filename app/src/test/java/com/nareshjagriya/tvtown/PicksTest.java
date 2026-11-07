package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class PicksTest {

    private static Picks.Entry card(List<Picks.Entry> cards, String label) {
        for (Picks.Entry c : cards) if (c.label.equals(label)) return c;
        throw new AssertionError("no card " + label);
    }

    private static Picks.Entry part(Picks.Entry card, String label) {
        for (Picks.Entry p : card.parts) if (p.label.equals(label)) return p;
        throw new AssertionError("no part " + label);
    }

    @Test
    public void atMostTwelveCardsEach() {
        for (int kind : new int[]{Picks.MOVIE, Picks.SERIES, Picks.SONG, Picks.EXTRAS}) {
            List<Picks.Entry> cards = Picks.cards(kind);
            assertTrue("kind " + kind, cards.size() >= 6 && cards.size() <= 12);
            Set<String> names = new HashSet<>();
            for (Picks.Entry c : cards) {
                assertTrue(c.label, names.add(c.label));
                assertTrue(c.label, c.myMix || !c.parts.isEmpty());
            }
        }
        assertEquals(12, Picks.cards(Picks.MOVIE).size());
        assertEquals(12, Picks.cards(Picks.SERIES).size());
        assertEquals(12, Picks.cards(Picks.SONG).size());
    }

    @Test
    public void classicsForMoviesAndBingeWorthyForSeries() {
        List<Picks.Entry> movies = Picks.cards(Picks.MOVIE), series = Picks.cards(Picks.SERIES);
        card(movies, "Classics");
        card(series, "Binge-Worthy");
        for (Picks.Entry c : movies) assertTrue(!c.label.equals("Binge-Worthy"));
        assertEquals("3", part(card(series, "Binge-Worthy"), "Finished Series").category.status);
        for (Picks.Entry p : card(series, "Anime").parts) assertTrue(!p.label.equals("Anime Movies"));
    }

    @Test
    public void cardsCoverSeveralKinds() {
        Picks.Entry thriller = card(Picks.cards(Picks.MOVIE), "Thriller & Crime");
        assertEquals("10051", part(thriller, "Heist").category.keywords);
        assertEquals("9840", part(card(Picks.cards(Picks.SERIES), "Romance"), "Romance").category.tvKeywords);
        Tmdb.Category gems = part(card(Picks.cards(Picks.MOVIE), "Hidden Gems"), "Hidden Gems").category;
        assertTrue(gems.maxVotes > gems.minVotes);
        Random r = new Random(4);
        Set<String> got = new HashSet<>();
        for (int i = 0; i < 40; i++) got.add(Picks.titlePart(thriller, java.util.Arrays.asList("hi"), r).label);
        assertTrue(got.size() >= 5);
    }

    @Test
    public void partsInAnotherLanguageWaitForThatLanguage() {
        Picks.Entry classics = card(Picks.cards(Picks.MOVIE), "Classics");
        Random r = new Random(2);
        for (int i = 0; i < 30; i++) {
            assertTrue(!Picks.titlePart(classics, java.util.Arrays.asList("en"), r).label.equals("90s Bollywood"));
        }
        Picks.Entry singers = card(Picks.cards(Picks.SONG), "Top Singers");
        for (int i = 0; i < 30; i++) {
            Picks.Tune t = Picks.tunePart(singers, java.util.Arrays.asList("Marathi"), false, r).tune;
            assertEquals("Marathi", t.language);
        }
        Picks.Entry indie = card(Picks.cards(Picks.SONG), "Alternate Artists");
        Set<String> artists = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            Picks.Tune t = Picks.tunePart(indie, java.util.Arrays.asList("Hindi"), false, r).tune;
            if (t.artist != null) artists.add(t.artist);
        }
        assertTrue("indie artists are inside one card", artists.size() >= 5);
    }

    @Test
    public void ownLanguageCardsIgnoreTheLanguageList() {
        assertTrue(card(Picks.cards(Picks.MOVIE), "Comedy").followsLanguage());
        assertTrue(!card(Picks.cards(Picks.MOVIE), "Anime").followsLanguage());
        assertTrue(card(Picks.cards(Picks.SONG), "Romantic").followsLanguage());
        assertTrue(!card(Picks.cards(Picks.SONG), "Top Singers").followsLanguage());
    }

    @Test
    public void languagesComeFromTheShownAndTickedLists() {
        java.util.List<String> none = new java.util.ArrayList<>(), hindiGujarati = java.util.Arrays.asList("Hindi", "Gujarati");
        assertEquals("Hindi + Gujarati", Picks.languageSummary(hindiGujarati));
        assertEquals("Any", Picks.languageSummary(none));
        assertEquals(java.util.Arrays.asList("hi", "gu"), Picks.titleLanguages(hindiGujarati));
        assertEquals(java.util.Arrays.asList(""), Picks.titleLanguages(none));
        java.util.List<String> shown = java.util.Arrays.asList("Hindi", "English", "Marathi", "Punjabi");
        assertEquals(shown, Picks.songLanguages(none, shown));   // Any: every language on the list
        assertEquals(java.util.Arrays.asList("Marathi"), Picks.songLanguages(java.util.Arrays.asList("Marathi"), shown));
        assertTrue(Picks.allLanguages(Picks.MOVIE).containsAll(java.util.Arrays.asList("Tamil", "Telugu", "Korean")));
        assertTrue(Picks.allLanguages(Picks.SONG).containsAll(shown));
    }

    @Test
    public void extrasAndMusicCardsAreTheChosenOnes() {
        List<String> extras = new java.util.ArrayList<>();
        for (Picks.Entry c : Picks.cards(Picks.EXTRAS)) extras.add(c.label);
        assertTrue(extras.containsAll(java.util.Arrays.asList("Classic TV Shows", "Cartoons", "Live News",
                "Sports Highlights", "Free Full Movies", "Ambient & Relax")));
        for (String gone : new String[]{"South Indian Trailers", "Sketch Comedy", "Crowd Work", "Osho Talks"}) assertTrue(!extras.contains(gone));
        List<String> music = new java.util.ArrayList<>();
        for (Picks.Entry c : Picks.cards(Picks.SONG)) music.add(c.label);
        assertTrue(music.contains("Alternate Artists") && !music.contains("Devotional & Sufi"));
        Picks.Entry shows = card(Picks.cards(Picks.EXTRAS), "Classic TV Shows");
        assertEquals("Taarak Mehta Ka Ooltah Chashmah", part(shows, "Taarak Mehta Ka Ooltah Chashmah").tune.artist);
        assertTrue(shows.adder != null);
        assertTrue(card(Picks.cards(Picks.EXTRAS), "Live News").parts.get(0).tune.live);
    }

    @Test
    public void songSearchesVaryBetweenPresses() {
        Picks.Tune romantic = card(Picks.cards(Picks.SONG), "Romantic").parts.get(0).tune;
        Random r = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) seen.add(Picks.songQuery(romantic, "Hindi", false, r));
        assertTrue("several different searches", seen.size() >= 4);
        for (String q : seen) assertTrue(q, (q.contains("romantic") || q.contains("love")) && (q.contains("hindi") || q.contains("bollywood")));
        assertTrue(Picks.songQuery(romantic, "Marathi", false, r).contains("marathi"));
        assertEquals("arrahman", Songs.squash("A. R. Rahman"));
        assertEquals("beyonce", Songs.squash("Beyoncé"));
    }

    @Test
    public void cardsAboutRemixesLetRemixesThrough() {
        String[] none = {};
        assertTrue(Songs.blocked("Bollywood Party Remix Nonstop 2025", none));
        assertTrue(!Songs.blocked("Bollywood Party Remix Nonstop 2025", "dj,remix,nonstop".split(",")));
        assertTrue(Songs.blocked("Tum Hi Ho (Slowed + Reverb)", none));
        assertTrue(!Songs.blocked("Tum Hi Ho (Slowed + Reverb)", "lofi,lo-fi,slowed,reverb".split(",")));
        assertTrue(!Songs.blocked("Kesariya | Brahmastra | Arijit Singh", none));
    }

    @Test
    public void trailerCardsAreRecentTrailersOnly() {
        Picks.Tune hindi = card(Picks.cards(Picks.EXTRAS), "New Trailers").parts.get(0).tune;
        assertEquals("EgQIAxAB", hindi.filter);
        assertTrue(hindi.must.matcher("Akka | Official Trailer | Netflix India").find());
        assertTrue(hindi.never.matcher("Ramayana Trailer 2 Review | Yogi Bolta Hai").find());
        Picks.Tune zakir = card(Picks.cards(Picks.EXTRAS), "Hindi Stand-up").parts.get(0).tune;
        assertTrue(zakir.verified && zakir.maxSeconds >= 3600 && "Zakir Khan".equals(zakir.artist));
    }

    @Test
    public void readsViewCounts() {
        assertEquals(139223, Songs.views("1,39,223 views"));   // Indian grouping
        assertEquals(1234567, Songs.views("1,234,567 views"));
        assertEquals(1, Songs.views("1 view"));
        assertEquals(0, Songs.views("No views"));
    }

    @Test
    public void myMixStartsAtARandomSong() {
        Random r = new Random(3);
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            String u = Picks.myMixUrl(r);
            assertTrue(u, u.matches("https://www\\.youtube\\.com/watch\\?list=RDMM&index=([1-9]|[12]\\d|30)"));
            urls.add(u);
        }
        assertTrue(urls.size() >= 5);
    }

    @Test
    public void playlistsStartNearTheirBeginning() {
        Random r = new Random(5);
        for (int i = 0; i < 20; i++) {
            String u = Picks.playlistUrl("PLabcdefghijklmnop", 40, r);
            assertTrue(u, u.matches("https://www\\.youtube\\.com/watch\\?list=PLabcdefghijklmnop&index=([1-9]|1[0-5])"));
        }
        assertTrue(Picks.playlistUrl("PLabcdefghijklmnop", 3, r).matches(".*index=[1-3]"));
    }

    @Test
    public void playlistSearchesVary() {
        Picks.Tune sad = card(Picks.cards(Picks.SONG), "Sad & Heartbreak").parts.get(0).tune;
        Random r = new Random(9);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String q = Picks.songQuery(sad, "Hindi", true, r);
            assertTrue(q, !q.contains("official") && (q.contains("playlist") || q.contains("collection")));
            seen.add(q);
        }
        assertTrue(seen.size() >= 5);
    }

    @Test
    public void readsPlaylistVideoCounts() {
        assertEquals(24, Songs.videoCount("{\"text\":\"24 videos\"}"));
        assertEquals(1200, Songs.videoCount("{\"text\":\"1.2K videos\"}"));
        assertEquals(1, Songs.videoCount("{\"text\":\"1 video\"}"));
        assertEquals(0, Songs.videoCount("{\"text\":\"Play all\"}"));
    }

    @Test
    public void musicTitlesAreTidied() {
        assertEquals("Bekhayali", Songs.shortTitle("Bekhayali Full Song | Kabir Singh | Shahid K, Kiara A"));
        assertEquals("Tum Hi Ho Aashiqui 2", Songs.shortTitle("\"Tum Hi Ho Aashiqui 2\" Full Video Song HD | Aditya Roy Kapur"));
        assertEquals("Sad Songs", Songs.shortTitle("Sad Songs | Hindi | Loneliness | Bollywood Break-up Songs"));
        assertEquals("Hosanna", Songs.shortTitle("Hosanna (Lyric Video) | A. R. Rahman"));
        assertTrue(Songs.shortTitle("A very long title that goes on and on and on without any separator at all").length() <= 48);
    }

    @Test
    public void readsTheLargestThumbnail() {
        String json = "{\"thumbnails\":[{\"url\":\"https://i.ytimg.com/vi/x/hq720.jpg?sqp=small\"},"
                + "{\"url\":\"https://i.ytimg.com/vi/x/hq720.jpg?sqp=big\\u0026rs=1\"}]}";
        assertEquals("https://i.ytimg.com/vi/x/hq720.jpg?sqp=big&rs=1", Songs.thumbnail(json));
        assertNull(Songs.thumbnail("{\"url\":\"https://evil.example/a.jpg\"}"));
        assertEquals("https://i.ytimg.com/vi/y/hq720.jpg", Songs.thumbnail("{\"url\":\"https:\\/\\/i.ytimg.com\\/vi\\/y\\/hq720.jpg\"}"));
    }

    @Test
    public void bagsAreSeparatePerChoice() {
        List<Picks.Entry> movies = Picks.cards(Picks.MOVIE);
        Picks.Entry comedy = card(movies, "Comedy"), horror = card(movies, "Horror"), anime = card(movies, "Anime");
        List<String> hi = java.util.Arrays.asList("Hindi"), mr = java.util.Arrays.asList("Marathi");
        assertTrue(!Picks.bag(0, comedy, hi).equals(Picks.bag(0, comedy, mr)));
        assertTrue(!Picks.bag(0, comedy, hi).equals(Picks.bag(1, comedy, hi)));
        assertTrue(!Picks.bag(0, comedy, hi).equals(Picks.bag(0, horror, hi)));
        assertEquals(Picks.bag(0, anime, hi), Picks.bag(0, anime, mr));   // its own language: the list does not matter
    }

    @Test
    public void trailersAreRankedOfficialFirst() throws Exception {
        JSONObject v = new JSONObject("{\"results\":["
                + "{\"site\":\"YouTube\",\"key\":\"teaser00001\",\"type\":\"Teaser\",\"official\":true},"
                + "{\"site\":\"YouTube\",\"key\":\"trailer0002\",\"type\":\"Trailer\",\"official\":false},"
                + "{\"site\":\"YouTube\",\"key\":\"trailer0003\",\"type\":\"Trailer\",\"official\":true},"
                + "{\"site\":\"YouTube\",\"key\":\"clip0000004\",\"type\":\"Clip\",\"official\":true}]}");
        List<String> keys = Tmdb.trailerKeys(v);
        assertEquals("trailer0003", keys.get(0));
        assertEquals("trailer0002", keys.get(1));
        assertEquals("teaser00001", keys.get(2));
        assertEquals(3, keys.size());
        assertNull(Tmdb.trailerKey(new JSONObject("{\"results\":[]}")));
    }

    @Test
    public void everyStyleHasItsOwnCards() {
        try {
            for (String style : Style.STYLES) {
                Style.use(style, Style.INDIAN.equals(style) ? "IN" : "US");
                for (int kind : new int[]{Picks.MOVIE, Picks.SERIES, Picks.SONG, Picks.EXTRAS}) {
                    List<Picks.Entry> cards = Picks.cards(kind);
                    assertTrue(style + " " + kind, cards.size() >= 6 && cards.size() <= 12);
                    Set<String> names = new HashSet<>();
                    for (Picks.Entry c : cards) {
                        assertTrue(c.label, names.add(c.label));
                        assertTrue(c.label, c.myMix || !c.parts.isEmpty());
                        for (Picks.Entry p : c.parts) assertTrue(p.label, !p.label.toLowerCase(java.util.Locale.ROOT).contains("osho"));
                    }
                }
            }
            Style.use(Style.GLOBAL, "US");
            List<String> extras = new java.util.ArrayList<>();
            for (Picks.Entry c : Picks.cards(Picks.EXTRAS)) extras.add(c.label);
            assertTrue(extras.containsAll(java.util.Arrays.asList("Late Night", "Classic Sitcoms", "Sports Highlights")));
            assertTrue(!extras.contains("Classic TV Shows"));
            assertEquals(java.util.Arrays.asList("English", "Hindi", "Korean"), Picks.allLanguages(Picks.MOVIE).subList(0, 3));
            Picks.Entry classics = card(Picks.cards(Picks.MOVIE), "Classics");
            part(classics, "80s & 90s Hollywood");
            for (Picks.Entry p : classics.parts) assertTrue(!p.label.equals("90s Bollywood"));
            Style.use(Style.MIXED, "IN");
            classics = card(Picks.cards(Picks.MOVIE), "Classics");
            part(classics, "80s & 90s Hollywood");
            part(classics, "90s Bollywood");
        } finally {
            Style.use(Style.INDIAN, "IN");
        }
    }
}
