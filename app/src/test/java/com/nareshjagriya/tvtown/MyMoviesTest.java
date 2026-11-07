package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MyMoviesTest {

    private static MyMovies.Parsed p(String name) {
        return MyMovies.parse(name, "", "");
    }

    @Test
    public void cleansMovieFileNames() {
        MyMovies.Parsed a = p("The.Punisher.One.Last.Kill.2026.1080p.WEBRip.x265-KONTRAST.mkv");
        assertEquals("The Punisher One Last Kill", a.title);
        assertEquals(2026, a.year);
        assertFalse(a.series);
        MyMovies.Parsed b = p("Mortal Kombat II (2026) 720p 10bit iNTERNAL HDRip x265 HEVC [LiNE Hindi AAC 2.0 + English AAC 2.0] ESub - BUTCHER.mkv");
        assertEquals("Mortal Kombat II", b.title);
        assertEquals(2026, b.year);
        assertEquals("Hoppers", p("Hoppers.2026.1080p.WEBRip.x264.AAC5.1-[YTS.BZ].mp4").title);
        MyMovies.Parsed g = p("Ghatak 1996 Hindi 720p DvDrip x264 AC3 5.1...Hon3y.mkv");
        assertEquals("Ghatak", g.title);
        assertEquals(1996, g.year);
        assertEquals("Spider-Man Brand New Day", p("Spider-Man.Brand.New.Day.2026.1080p.WEBRip.x264.AAC5.1-[YTS.GG - YTS.BZ].mp4").title);
        assertEquals("Its A Wonderful Life", p("Its.A.Wonderful.Life.1946.1080p.BluRay.x264.AAC-[YTS.MX].mp4").title);
        assertEquals("Dangal", p("Dangal 1080p BluRay x264 Hindi.mkv").title);
        MyMovies.Parsed y = p("1917.2019.1080p.mkv");
        assertEquals("1917", y.title);
        assertEquals(2019, y.year);
    }

    @Test
    public void findsEpisodes() {
        MyMovies.Parsed e = p("Panchayat.S02E05.1080p.AMZN.WEB-DL.mkv");
        assertTrue(e.series);
        assertEquals("Panchayat", e.title);
        assertEquals(2, e.season);
        assertEquals(5, e.episode);
        MyMovies.Parsed x = p("The Office - 3x07 - Branch Closing.avi");
        assertEquals("The Office", x.title);
        assertEquals(3, x.season);
        assertEquals(7, x.episode);
        MyMovies.Parsed f = MyMovies.parse("S01E02.mkv", "Season 1", "Kota Factory");
        assertEquals("Kota Factory", f.title);
        assertEquals(1, f.season);
    }

    @Test
    public void usesTheFolderWhenTheFileSaysTooLittle() {
        MyMovies.Parsed a = MyMovies.parse("The.Pianist.1080p.BrRip.x264.YIFY.mp4", "The Pianist (2002)", "");
        assertEquals("The Pianist", a.title);
        assertEquals(2002, a.year);
        assertEquals("The Pianist (2002)", MyMovies.folderName("Movies/The Pianist (2002)/"));
        assertEquals("Kota Factory", MyMovies.parentFolder("TV/Kota Factory/Season 1/"));
    }

    @Test
    public void formatsTimes() {
        assertEquals("1:02:03", MyMovies.clock(3723_000));
        assertEquals("45:10", MyMovies.clock(2710_000));
    }

    @Test
    public void bestMatchPrefersSameTitleAndYear() {
        MyMovies.Parsed want = p("Ghatak.1996.mkv");
        java.util.List<Tmdb.Title> found = java.util.Arrays.asList(
                new Tmdb.Title(1, "movie", "Ghatak 2", "", "2024-01-01", 0, 5, null, null),
                new Tmdb.Title(2, "movie", "Ghatak", "", "2010-01-01", 0, 5, null, null),
                new Tmdb.Title(3, "movie", "Ghatak", "", "1996-11-08", 0, 7, null, null));
        assertEquals(3, MyMovies.best(found, want).id);
    }

    @Test
    public void endCreditsCountAsWatched() {
        long min = 60_000L;
        assertTrue(MyMovies.nearEnd(140 * min, 150 * min));    // a film, 10 minutes of credits left
        assertTrue(MyMovies.nearEnd(91 * min, 100 * min));     // the last 10%
        assertFalse(MyMovies.nearEnd(120 * min, 150 * min));   // half an hour to go
        assertTrue(MyMovies.nearEnd(41 * min, 44 * min));      // an episode, 3 minutes left
        assertFalse(MyMovies.nearEnd(30 * min, 44 * min));
        assertFalse(MyMovies.nearEnd(2 * min, 5 * min));       // not even half way
        assertFalse(MyMovies.nearEnd(10 * min, 0));            // length unknown
    }
}
