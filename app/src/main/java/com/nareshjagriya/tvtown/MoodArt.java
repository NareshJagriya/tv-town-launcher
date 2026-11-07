package com.nareshjagriya.tvtown;

import java.util.Locale;

/** A mood's own graphic: a symbol and two colours, chosen by its name; moods you add take their own colour. */
final class MoodArt {

    private MoodArt() {
    }

    final static class Look {
        final String icon;
        final int top, bottom;

        Look(String icon, int top, int bottom) {
            this.icon = icon;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final Object[][] LOOKS = {
            {"my mix", "shuffle", 0xFF8B5CF6, 0xFF4C1D95},
            {"sad", "rain", 0xFF4F7CAC, 0xFF1E3A5F}, {"heartbreak", "rain", 0xFF4F7CAC, 0xFF1E3A5F},
            {"comedy", "smile", 0xFFF59E0B, 0xFFC2410C}, {"happy", "smile", 0xFFF59E0B, 0xFFC2410C}, {"feel-good", "smile", 0xFFF59E0B, 0xFFC2410C},
            {"sitcom", "smile", 0xFFF59E0B, 0xFFC2410C},
            {"roman", "heart", 0xFFEC4899, 0xFFBE123C}, {"love", "heart", 0xFFEC4899, 0xFFBE123C},
            {"action", "flame", 0xFFEF4444, 0xFF9A3412},
            {"thriller", "target", 0xFF475569, 0xFF1E293B}, {"crime", "target", 0xFF475569, 0xFF1E293B},
            {"horror", "ghost", 0xFF6D28D9, 0xFF2E1065},
            {"sci-fi", "planet", 0xFF06B6D4, 0xFF1D4ED8},
            {"drama", "mask", 0xFFB91C4B, 0xFF5F0F2E},
            {"family", "people", 0xFF22C55E, 0xFF0F766E},
            {"gem", "gem", 0xFF8B5CF6, 0xFF4338CA},
            {"award", "trophy", 0xFFEAB308, 0xFFA16207}, {"sports", "trophy", 0xFF16A34A, 0xFF14532D},
            {"classic", "reel", 0xFFB08050, 0xFF5C3A1E}, {"old is gold", "reel", 0xFFB08050, 0xFF5C3A1E}, {"throwback", "reel", 0xFFB08050, 0xFF5C3A1E},
            {"binge", "tv", 0xFFF43F5E, 0xFF7C3AED},
            {"anime", "sparkle", 0xFFF472B6, 0xFF7C3AED}, {"cartoon", "sparkle", 0xFFFB923C, 0xFFDB2777}, {"bheem", "sparkle", 0xFFFB923C, 0xFFDB2777},
            {"party", "sparkle", 0xFFD946EF, 0xFF6D28D9}, {"new release", "sparkle", 0xFF14B8A6, 0xFF1D4ED8},
            {"chill", "leaf", 0xFF34D399, 0xFF0F766E}, {"ambient", "leaf", 0xFF34D399, 0xFF0F766E},
            {"workout", "dumbbell", 0xFFF97316, 0xFFB91C1C},
            {"singer", "mic", 0xFF3B82F6, 0xFF3730A3}, {"artist", "mic", 0xFF3B82F6, 0xFF3730A3}, {"hip-hop", "mic", 0xFF6366F1, 0xFF312E81},
            {"stand-up", "mic", 0xFFF59E0B, 0xFFB91C1C}, {"late night", "moon", 0xFF4338CA, 0xFF1E1B4B},
            {"indie", "music", 0xFF0EA5E9, 0xFF4338CA}, {"rock", "music", 0xFFDC2626, 0xFF450A0A}, {"alternate", "music", 0xFF0EA5E9, 0xFF4338CA},
            {"trailer", "play", 0xFFEF4444, 0xFF7F1D1D},
            {"news", "broadcast", 0xFFF97316, 0xFF9A3412},
            {"documentar", "globe", 0xFF0D9488, 0xFF134E4A},
            {"free", "film", 0xFF2563EB, 0xFF1E3A8A}, {"classic tv", "tv", 0xFFB08050, 0xFF5C3A1E},
    };

    static Look of(String label, int ownColour) {
        String l = label.toLowerCase(Locale.ROOT);
        for (Object[] o : LOOKS) if (l.contains((String) o[0])) return new Look((String) o[1], (Integer) o[2], (Integer) o[3]);
        int r = (ownColour >> 16) & 255, g = (ownColour >> 8) & 255, b = ownColour & 255;
        return new Look("star", ownColour | 0xFF000000, 0xFF000000 | (r * 45 / 100) << 16 | (g * 45 / 100) << 8 | (b * 45 / 100));
    }
}
