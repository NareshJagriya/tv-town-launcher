package com.nareshjagriya.tvtown;

/**
 * Hindi (and Marathi) script in English letters, the way songs are usually written that way: "तेरे नैना, तेरे नैना रे"
 * gives "tere naina, tere naina re". The silent "a" is dropped at the end of a word and between a sounded vowel and a
 * consonant with its own vowel ("makhmali", "tujhse", "karta"); long vowels are "aa", "ee",
 * "oo" inside a word and "a", "i", "u" at its end, and ं is "n" (or "m" before p, b, m). For the lyrics when only a
 * Hindi-script version exists and English letters are chosen.
 */
final class Roman {

    private Roman() {
    }

    /** Consonants (without their vowel), from क (0x0915) to ह (0x0939). */
    private static final String[] CONS = {
            "k", "kh", "g", "gh", "n",        // क ख ग घ ङ
            "ch", "chh", "j", "jh", "n",      // च छ ज झ ञ
            "t", "th", "d", "dh", "n",        // ट ठ ड ढ ण
            "t", "th", "d", "dh", "n", "n",   // त थ द ध न ऩ
            "p", "ph", "b", "bh", "m",        // प फ ब भ म
            "y", "r", "r", "l", "l", "l",     // य र ऱ ल ळ ऴ
            "v", "sh", "sh", "s", "h"};       // व श ष स ह

    static String of(String raw) {
        // Letters like ज़ can come as one character; split them into ज and its dot, which is what the code below reads.
        String text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        int n = text.length();
        boolean voweled = false;   // the last sound written in this word was a vowel
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (!isLetter(ch)) voweled = false;
            if (ch >= 0x0915 && ch <= 0x0939) {
                String c = CONS[ch - 0x0915];
                int j = i + 1;
                if (j < n && text.charAt(j) == '़') {   // nukta: क़ ख़ ग़ ज़ ड़ ढ़ फ़
                    switch (ch) {
                        case 'क': c = "q"; break;
                        case 'ख': c = "kh"; break;
                        case 'ग': c = "gh"; break;
                        case 'ज': c = "z"; break;
                        case 'ड': c = "r"; break;
                        case 'ढ': c = "rh"; break;
                        case 'फ': c = "f"; break;
                        default: break;
                    }
                    j++;
                }
                if (ch == 'व' && j < n && text.charAt(j) == '्') c = "w";   // व् as in "swar"
                out.append(c);
                char next = j < n ? text.charAt(j) : ' ';
                if (next == '्') {   // virama: no vowel
                    voweled = false;
                    i = j;
                } else if (next >= 0x093E && next <= 0x094C) {   // a vowel sign
                    out.append(sign(next, endOfWord(text, j + 1)));
                    voweled = true;
                    i = j;
                } else {   // the inherent "a": silent at the end of a word (kept after a lone letter: "na", "ka"),
                    // and between a sounded vowel and a consonant with its own vowel ("makhmali", not "makhamali")
                    boolean end = endOfWord(text, j);
                    boolean lone = i == 0 || !isLetter(text.charAt(i - 1));
                    boolean say = lone || !end && !(voweled && nextHasVowel(text, j));
                    if (say) out.append('a');
                    voweled = say;
                    i = j - 1;
                }
                continue;
            }
            if (ch >= 0x0905 && ch <= 0x0914) voweled = true;   // a vowel letter (अ आ इ …)
            switch (ch) {
                case 'अ': out.append('a'); break;                 // अ
                case 'आ': out.append(endOfWord(text, i + 1) ? "a" : "aa"); break;   // आ
                case 'इ': out.append('i'); break;                 // इ
                case 'ई': out.append(endOfWord(text, i + 1) ? "i" : "ee"); break;   // ई
                case 'उ': out.append('u'); break;                 // उ
                case 'ऊ': out.append(endOfWord(text, i + 1) ? "u" : "oo"); break;   // ऊ
                case 'ऋ': out.append("ri"); break;                // ऋ
                case 'ए': out.append('e'); break;                 // ए
                case 'ऐ': out.append("ai"); break;                // ऐ
                case 'ओ': out.append('o'); break;                 // ओ
                case 'औ': out.append("au"); break;                // औ
                case 'ं': {                                       // ं: n, or m before p b m
                    char nx = i + 1 < n ? text.charAt(i + 1) : ' ';
                    out.append(nx == 'प' || nx == 'ब' || nx == 'भ' || nx == 'म' ? 'm' : 'n');
                    break;
                }
                case 'ँ': out.append('n'); break;                 // ँ
                case 'ः': out.append('h'); break;                 // ः
                case '़': case '्': case '‌': case '‍': break;
                case '।': case '॥': out.append('.'); break;  // ।
                default:
                    if (ch >= 0x0966 && ch <= 0x096F) out.append((char) ('0' + ch - 0x0966));   // Hindi digits
                    else out.append(ch);
            }
        }
        // A capital letter to start the line, as lyrics are written.
        if (out.length() > 0 && Character.isLowerCase(out.charAt(0))) out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
        return out.toString();
    }

    /** True when the letter at p is a consonant followed by its own vowel (a vowel sign, or a sounded "a"). */
    private static boolean nextHasVowel(String t, int p) {
        if (p >= t.length()) return false;
        char c = t.charAt(p);
        if (c < 0x0915 || c > 0x0939) return false;
        int q = p + 1;
        if (q < t.length() && t.charAt(q) == 0x093C) q++;   // nukta
        if (q >= t.length()) return false;
        char after = t.charAt(q);
        if (after == 0x094D) return false;                   // virama
        if (after >= 0x093E && after <= 0x094C) return true;
        return !endOfWord(t, q);
    }

    /** A vowel sign: long ones are doubled inside a word ("naaraaz") and single at its end ("naina", "teri"). */
    private static String sign(char s, boolean end) {
        switch (s) {
            case 'ा': return end ? "a" : "aa";   // ा
            case 'ि': return "i";                 // ि
            case 'ी': return end ? "i" : "ee";   // ी
            case 'ु': return "u";                 // ु
            case 'ू': return end ? "u" : "oo";   // ू
            case 'ृ': return "ri";                // ृ
            case 'े': return "e";                 // े
            case 'ै': return "ai";                // ै
            case 'ो': return "o";                 // ो
            case 'ौ': return "au";                // ौ
            default: return "";
        }
    }

    /** True when nothing more of the word follows position p (only nasal marks or nukta may come first). */
    private static boolean endOfWord(String t, int p) {
        while (p < t.length()) {
            char ch = t.charAt(p);
            if (ch == 'ं' || ch == 'ँ' || ch == '़') {
                p++;
                continue;
            }
            return !isLetter(ch);
        }
        return true;
    }

    private static boolean isLetter(char ch) {
        return ch >= 0x0900 && ch <= 0x097F && ch != 0x0964 && ch != 0x0965 || Character.isLetter(ch);
    }
}
