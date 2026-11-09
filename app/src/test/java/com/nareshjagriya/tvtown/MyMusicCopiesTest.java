package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MyMusicCopiesTest {

    private static MyMusic.Song song(String folder, String title, String artist, long ms) {
        MyMusic.Song s = new MyMusic.Song();
        s.folder = folder;
        s.title = title;
        s.artist = artist;
        s.durationMs = ms;
        return s;
    }

    @Test
    public void aCopiedFileShowsOnce() {
        List<MyMusic.Song> l = MyMusic.withoutCopies(Arrays.asList(
                song("English", "'Till I Collapse (feat. Nate Dogg)", "Eminem", 297_000),
                song("English", "'Till I Collapse (feat. Nate Dogg)", "Eminem", 297_900)));
        assertEquals(1, l.size());
    }

    @Test
    public void differentVersionsStay() {
        List<MyMusic.Song> l = MyMusic.withoutCopies(Arrays.asList(
                song("English", "Bad Romance", "Lady Gaga", 294_000),
                song("English", "Bad Romance", "Vitamin String Quartet", 263_000),   // another singer
                song("Unplugged", "Bad Romance", "Lady Gaga", 294_000),               // another folder
                song("English", "Bad Romance", "Lady Gaga", 330_000)));               // another cut
        assertEquals(4, l.size());
    }
}
