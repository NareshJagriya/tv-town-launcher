package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TagsTest {

    private static Tags.Facts facts(String... keywords) {
        Tags.Facts f = new Tags.Facts();
        f.keywords.addAll(Arrays.asList(keywords));
        return f;
    }

    @Test
    public void atMostThreeSpecificThemesFirst() {
        Tags.Facts f = facts("feel-good", "time travel", "heist", "revenge", "based on true story");
        f.award = true;
        List<String> t = Tags.auto(f);
        assertEquals(Tags.MAX_AUTO, t.size());
        assertEquals(Arrays.asList("Time Travel & Loops", "True Story", "Revenge"), t);
    }

    @Test
    public void darkComedyIsNotDarkAndIntense() {
        List<String> t = Tags.auto(facts("black comedy film", "dark comedy"));
        assertTrue(t.contains("Dark Comedy & Satire"));
        assertFalse(t.contains("Dark & Intense"));
        assertTrue(Tags.auto(facts("dark")).contains("Dark & Intense"));
    }

    @Test
    public void awardsAndCertificates() {
        Tags.Facts f = facts();
        f.award = true;
        f.certificate = "U";
        assertEquals(Arrays.asList("Award Winner", "Family-Friendly"), Tags.auto(f));
        f.genres.add(27);   // a horror film is never family-friendly
        assertEquals(Arrays.asList("Award Winner"), Tags.auto(f));
        assertTrue(Tags.familyCertificate("UA 7+"));
        assertFalse(Tags.familyCertificate("UA 16+"));
        assertFalse(Tags.familyCertificate("A"));
    }

    @Test
    public void genreGuessOnlyWithoutEvidence() {
        Tags.Facts comedy = facts();
        comedy.genres.add(35);
        assertEquals(Arrays.asList(Tags.FEEL_GOOD), Tags.auto(comedy));
        comedy.genres.add(80);   // a crime comedy: no guess
        assertTrue(Tags.auto(comedy).isEmpty());
        Tags.Facts war = facts("revenge");
        war.genres.add(10752);
        assertEquals(Arrays.asList("Revenge"), Tags.auto(war));
    }

    @Test
    public void manualTagsHaveNoKeywords() {
        assertEquals(20, Tags.ALL.length);
        for (String n : new String[]{"Slow Burn", "One Location", "Hidden Gem"}) assertEquals(null, Tags.find(n).keywords);
        assertEquals("🏷", Tags.emoji("My own tag"));
    }
}
