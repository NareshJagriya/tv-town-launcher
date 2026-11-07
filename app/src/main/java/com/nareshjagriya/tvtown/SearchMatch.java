package com.nareshjagriya.tvtown;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Does a name match what was typed, forgiving the ways Hindi is spelled in English letters ("dil chahata", "zindagi" and
 * "jindagi", "pyaar" and "pyar") and one wrong letter in a longer word. Each typed word must begin a word of the name,
 * in any order. Plain Java, so it can be tested off the TV.
 */
final class SearchMatch {

    private SearchMatch() {
    }

    static final int NONE = Integer.MAX_VALUE;

    /** Lower case, accents gone, punctuation as spaces. */
    static String plain(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return d.replaceAll("[^\\p{L}\\p{N} ]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** One spelling for the sounds that are written several ways. */
    static String sound(String word) {
        String w = word;
        w = w.replace("chh", "c").replace("ch", "c").replace("sh", "s").replace("ph", "f");
        w = w.replace("bh", "b").replace("dh", "d").replace("gh", "g").replace("kh", "k").replace("th", "t");
        w = w.replace('w', 'v').replace('z', 'j').replace('q', 'k');
        w = w.replace("ee", "i").replace("oo", "u").replace("ou", "u").replace("ai", "e").replace("ay", "e");
        w = w.replace('y', 'i');
        StringBuilder b = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (i == 0 || c != w.charAt(i - 1)) b.append(c);   // doubled letters: pyaar, pyar; ishq, isshq
        }
        return b.toString();
    }

    /** Changes needed to turn a into b, stopping once it passes 1. */
    private static boolean withinOne(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0, j = 0;
        boolean used = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (used) return false;
            used = true;
            if (la > lb) i++;
            else if (lb > la) j++;
            else {
                i++;
                j++;
            }
        }
        return !used || (i == la && j == lb);
    }

    /** One typed word against one word of a name: 0 as typed, 1 sounds the same, 2 one letter off, else NONE. */
    private static int word(String typed, String typedSound, String name, String nameSound) {
        if (name.startsWith(typed)) return 0;
        if (nameSound.startsWith(typedSound)) return 1;
        int n = typedSound.length();
        if (n < 4) return NONE;   // too short to guess: "dil" must not turn into "dal"
        for (int len = n - 1; len <= n + 1; len++) {
            if (len <= nameSound.length() && withinOne(typedSound, nameSound.substring(0, len))) return 2;
        }
        return NONE;
    }

    /** How well the name fits (lower is better), or NONE. */
    static int score(String typedPlain, String[] typedWords, String[] typedSounds, String namePlain, String[] nameWords, String[] nameSounds) {
        int worst = 0;
        for (int i = 0; i < typedWords.length; i++) {
            int best = NONE;
            for (int j = 0; j < nameWords.length && best > 0; j++) {
                best = Math.min(best, word(typedWords[i], typedSounds[i], nameWords[j], nameSounds[j]));
            }
            if (best == NONE) {   // "indino" for "In Dino": a last try, without the spaces
                return typedWords.length == 1 && typedWords[0].length() >= 4 && namePlain.replace(" ", "").startsWith(typedWords[0]) ? 150 : NONE;
            }
            worst = Math.max(worst, best);
        }
        int s = worst * 100;
        if (namePlain.equals(typedPlain)) s -= 60;
        else if (namePlain.startsWith(typedPlain)) s -= 40;
        else if (nameWords.length > 0 && nameWords[0].startsWith(typedWords[0])) s -= 20;
        return s;
    }

    static String[] words(String plain) {
        return plain.isEmpty() ? new String[0] : plain.split(" ");
    }

    static String[] sounds(String[] words) {
        String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) out[i] = sound(words[i]);
        return out;
    }
}
