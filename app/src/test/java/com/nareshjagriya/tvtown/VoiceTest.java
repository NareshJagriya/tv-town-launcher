package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class VoiceTest {

    private static final List<String> APPS = Arrays.asList("Netflix", "Prime Video", "YouTube", "JioHotstar", "VLC");

    private static void is(String said, int kind, String text) {
        Voice.Command c = Voice.parse(said, APPS);
        assertEquals(said, kind, c.kind);
        assertEquals(said, text, c.text);
    }

    @Test
    public void understandsWhatIsSaid() {
        is("Open Netflix", Voice.APP, "Netflix");
        is("open prime video app", Voice.APP, "Prime Video");
        is("YouTube", Voice.APP, "YouTube");
        is("random movie", Voice.RANDOM, "0");
        is("Surprise me", Voice.RANDOM, "0");
        is("random series", Voice.RANDOM, "1");
        is("random music", Voice.RANDOM, "2");
        is("live news", Voice.RANDOM, "3");
        is("play Arijit Singh", Voice.YOUTUBE, "arijit singh");
        is("kesariya song", Voice.YOUTUBE, "kesariya song");
        is("Lagaan", Voice.SEARCH, "Lagaan");
        is("movies with Shah Rukh Khan", Voice.SEARCH, "movies with Shah Rukh Khan");
    }
}
