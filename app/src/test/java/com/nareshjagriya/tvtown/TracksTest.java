package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;

public class TracksTest {

    private static final List<String> UNDERSTOOD = Arrays.asList("hi", "en", "mr");

    private static Tracks.Info info(String[] audio, String... subs) {
        Tracks.Info i = new Tracks.Info();
        i.audio.addAll(Arrays.asList(audio));
        i.subs.addAll(Arrays.asList(subs));
        i.hasSubs = subs.length > 0;
        return i;
    }

    @Test
    public void languageCodes() {
        assertEquals("hi", Tracks.code("hin"));
        assertEquals("hi", Tracks.code("Hindi"));
        assertEquals("en", Tracks.code("en-US"));
        assertEquals("fr", Tracks.code("fre"));
        assertNull(Tracks.code("und"));
        assertNull(Tracks.code("xx"));
        assertEquals("Hindi", Tracks.name("hi"));
    }

    @Test
    public void fileNames() {
        assertEquals(Arrays.asList("hi", "en"),
                Tracks.nameLanguages("Mortal Kombat II (2026) [LiNE Hindi AAC 2.0 + English AAC 2.0] ESub.mkv"));
        assertTrue(Tracks.nameSaysSubs("Film 2026 720p ESub.mkv"));
        assertFalse(Tracks.nameSaysSubs("Subway Surfers 2026.mkv"));
        assertEquals("en", Tracks.subtitleLanguage("The Pianist (2002).en.srt"));
        assertEquals("hi", Tracks.subtitleLanguage("The Pianist (2002).Hindi.srt"));
        assertNull(Tracks.subtitleLanguage("The Pianist (2002).srt"));
    }

    @Test
    public void labels() {
        assertEquals("Hollywood  ·  Audio: English, Hindi  ·  Subs: English",
                Tracks.label("en", info(new String[]{"en", "hi"}, "en"), UNDERSTOOD).text);
        assertEquals("Bollywood  ·  Audio: Hindi  ·  No subs", Tracks.label("hi", info(new String[]{"hi"}), UNDERSTOOD).text);
        Tracks.Label korean = Tracks.label("ko", info(new String[]{}), UNDERSTOOD);
        assertTrue(korean.foreignNoSubs);
        assertEquals("Korean  ·  Audio: Korean  ·  No subs", korean.text);
        Tracks.Label dubbed = Tracks.label("ko", info(new String[]{"ko", "hi"}), UNDERSTOOD);
        assertFalse(dubbed.foreignNoSubs);   // Hindi audio: no subtitles needed
        assertEquals("Hollywood  ·  Audio: English  ·  Subs: English, German +2",
                Tracks.label("en", info(new String[]{"en"}, "en", "de", "es", "fr"), UNDERSTOOD).text);
        assertEquals("South Indian", Tracks.origin("te"));
        assertEquals("Chinese", Tracks.origin("cn"));
        assertEquals("European", Tracks.origin("fr"));
        assertEquals("Other", Tracks.origin("fa"));
    }

    @Test
    public void openSubtitlesHash() throws Exception {
        File f = File.createTempFile("hash", ".bin");
        f.deleteOnExit();
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] b = new byte[200_000];
            for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 7);
            r.write(b);
        }
        // the same sum worked out directly: size + first 64 KB + last 64 KB, as little-endian 64-bit numbers
        long expect = f.length();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            for (long start : new long[]{0, f.length() - 65536}) {
                byte[] b = new byte[65536];
                r.seek(start);
                r.readFully(b);
                for (int i = 0; i < b.length; i += 8) {
                    long v = 0;
                    for (int j = 7; j >= 0; j--) v = (v << 8) | (b[i + j] & 0xFF);
                    expect += v;
                }
            }
            assertEquals(String.format("%016x", expect), OpenSubtitles.hash(r.getChannel(), f.length()));
        }
    }

    @Test
    public void subtitleFileNames() {
        assertEquals("The Pianist (2002).en.srt", Subtitles.fileName("The Pianist (2002).mkv", "en"));
        assertEquals("A_B.und.srt", Subtitles.fileName("A:B.mp4", ""));
    }
}
