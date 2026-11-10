package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Extras tiles where you choose first, then get picks: Recipes (diet, cuisine, meal, quick), Podcasts & Talks,
 * Spirituality and Documentaries (a topic each). India and the rest of the world get their own choices (the content
 * style). What you tick is kept per tile, and you can add your own ("Kerala fish curry", "Huberman Lab").
 */
final class Topics {

    private Topics() {
    }

    /** One row of choices: Diet, Cuisine… Each choice is its name and the words searched for. */
    static final class Group {
        final String name;
        final List<String[]> options = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }

        Group add(String label, String words) {
            options.add(new String[]{label, words});
            return this;
        }
    }

    /** A tile that asks first: its rows of choices, and how a set of words becomes a search. */
    static final class Asks {
        final List<Group> groups = new ArrayList<>();
        final String ownHint;
        final java.util.function.Function<String, Picks.Tune> tune;

        Asks(String ownHint, java.util.function.Function<String, Picks.Tune> tune) {
            this.ownHint = ownHint;
            this.tune = tune;
        }
    }

    static final String RECIPES = "Recipes", PODCASTS = "Podcasts & Talks", SPIRITUALITY = "Spirituality", DOCS = "Documentaries";

    private static final String NOT_SHORT = "#shorts|shorts|reel|status";

    static Picks.Tune recipeTune(String words) {
        return new Picks.Tune(words + " recipe|how to make " + words + "|" + words + " recipe step by step")
                .length(180, 2400).noTail().never(NOT_SHORT + "|asmr|mukbang|eating challenge");
    }

    static Picks.Tune podcastTune(String words) {
        return new Picks.Tune(words + " podcast full episode|" + words + " podcast|" + words + " interview")
                .length(1500, 14400).noTail().never(NOT_SHORT + "|clip|highlights|trailer");
    }

    static Picks.Tune spiritTune(String words) {
        return new Picks.Tune(words).length(300, 7200).noTail().never(NOT_SHORT).allow("instrumental");
    }

    static Picks.Tune docTune(String words) {
        return new Picks.Tune(words + " full documentary|" + words + " documentary").length(1200, 10800).noTail()
                .never(NOT_SHORT + "|trailer|preview");
    }

    /** The choices for a tile, for India or the rest of the world; null for a tile that doesn't ask. */
    static Asks asks(String label) {
        boolean india = !Style.global();
        switch (label) {
            case RECIPES: {
                Asks a = new Asks("A dish or cuisine, like \"Kerala fish curry\"", Topics::recipeTune);
                Group diet = new Group("Diet");
                if (india) diet.add("Veg", "veg").add("Egg", "egg").add("Non-veg", "chicken mutton").add("Vegan", "vegan").add("Jain", "jain no onion no garlic");
                else diet.add("Vegetarian", "vegetarian").add("Vegan", "vegan").add("Chicken", "chicken").add("Meat", "beef pork").add("Seafood", "seafood").add("Gluten-free", "gluten free");
                Group cuisine = new Group("Cuisine");
                if (india) cuisine.add("North Indian", "north indian").add("South Indian", "south indian").add("Maharashtrian", "maharashtrian")
                        .add("Gujarati", "gujarati").add("Punjabi", "punjabi").add("Bengali", "bengali").add("Indo-Chinese", "indo chinese")
                        .add("Italian", "italian").add("Baking", "baking");
                else cuisine.add("American", "american").add("Italian", "italian").add("Mexican", "mexican").add("Chinese", "chinese")
                        .add("Japanese", "japanese").add("Thai", "thai").add("Indian", "indian").add("Mediterranean", "mediterranean").add("Baking", "baking");
                Group meal = new Group("Meal").add("Breakfast", "breakfast").add("Lunch", "lunch").add("Dinner", "dinner")
                        .add("Snacks", "snacks").add("Desserts", "dessert");
                Group time = new Group("Time").add("Quick (under 20 min)", "quick 15 minute");
                Collections.addAll(a.groups, diet, cuisine, meal, time);
                return a;
            }
            case PODCASTS: {
                Asks a = new Asks("A show or a guest, like \"Huberman Lab\"", Topics::podcastTune);
                Group g = new Group("Topic");
                if (india) g.add("Business", "business startup hindi").add("Money", "personal finance investing hindi").add("Tech", "technology")
                        .add("Health", "health fitness").add("Cricket", "cricket").add("Bollywood", "bollywood").add("Self-growth", "self improvement motivation")
                        .add("History", "indian history").add("Science", "science space").add("Comedy", "comedy");
                else g.add("Business", "business").add("Money", "personal finance").add("Tech", "technology AI").add("Health", "health science")
                        .add("Sports", "sports").add("Film & TV", "movies tv").add("Self-growth", "self improvement").add("History", "history")
                        .add("Science", "science").add("Comedy", "comedy").add("True crime", "true crime");
                a.groups.add(g);
                return a;
            }
            case SPIRITUALITY: {
                Asks a = new Asks("A teacher, place or prayer, like \"Hanuman Chalisa\"", Topics::spiritTune);
                Group g = new Group("Topic");
                if (india) g.add("Meditation", "guided meditation").add("Bhagavad Gita", "bhagavad gita explained").add("Osho", "osho talks")
                        .add("Sadhguru", "sadhguru").add("Bhajans & Aarti", "bhajan aarti").add("Gurbani", "gurbani kirtan").add("Sufi", "sufi qawwali")
                        .add("Buddhist talks", "buddhist dharma talk").add("Satsang", "satsang pravachan");
                else g.add("Meditation", "guided meditation").add("Mindfulness", "mindfulness talk").add("Buddhism", "buddhist dharma talk")
                        .add("Christian", "sermon worship").add("Sufi & Rumi", "rumi sufi").add("Yoga philosophy", "yoga philosophy")
                        .add("Stoicism", "stoic philosophy").add("Sound healing", "sound healing singing bowls");
                a.groups.add(g);
                return a;
            }
            case DOCS: {
                Asks a = new Asks("A subject or channel, like \"Ancient Egypt\"", Topics::docTune);
                Group g = new Group("Topic").add("Wildlife", "wildlife nature").add("Space", "space universe").add("History", "history")
                        .add("True crime", "true crime").add("Science", "science").add("Travel", "travel").add("Food", "food")
                        .add("Engineering", "engineering mega structures").add(india ? "India" : "Biography", india ? "india" : "biography");
                a.groups.add(g);
                return a;
            }
            default:
                return null;
        }
    }

    // ---- what you ticked, and what you added -------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("topics", Context.MODE_PRIVATE);
    }

    /** Ticked choices, as "Group|Label", for this tile in this content style. */
    static Set<String> ticked(Context c, String label) {
        return new LinkedHashSet<>(prefs(c).getStringSet(Style.key("on_" + label), new HashSet<>()));
    }

    static void setTicked(Context c, String label, Set<String> on) {
        prefs(c).edit().putStringSet(Style.key("on_" + label), new HashSet<>(on)).apply();
    }

    /** Your own choices for a tile (they search as typed). */
    static List<String> own(Context c, String label) {
        String v = prefs(c).getString(Style.key("own_" + label), "");
        List<String> out = new ArrayList<>();
        for (String s : v.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static void addOwn(Context c, String label, String text) {
        List<String> l = own(c, label);
        if (l.contains(text.trim())) return;
        l.add(text.trim());
        prefs(c).edit().putString(Style.key("own_" + label), String.join("\n", l)).apply();
    }

    static void removeOwn(Context c, String label, String text) {
        List<String> l = own(c, label);
        l.remove(text);
        prefs(c).edit().putString(Style.key("own_" + label), String.join("\n", l)).apply();
    }

    static final String OWN = "Your own";

    /**
     * The tile as a card to search with: one part per mix of the ticked choices (a diet, a cuisine and a meal
     * together for Recipes; each topic for the others), plus your own. Nothing ticked: everything, mixed.
     */
    static Picks.Entry card(Context c, Picks.Entry tile) {
        Asks a = asks(tile.label);
        Set<String> on = ticked(c, tile.label);
        Picks.Entry out = new Picks.Entry(tile.emoji, tile.label, tile.color);
        out.topicSig = String.join(",", new java.util.TreeSet<>(on));
        List<List<String>> chosen = new ArrayList<>();
        for (Group g : a.groups) {
            List<String> words = new ArrayList<>();
            for (String[] o : g.options) if (on.contains(g.name + "|" + o[0])) words.add(o[1]);
            chosen.add(words);
        }
        List<String> mine = new ArrayList<>();
        for (String s : own(c, tile.label)) if (on.contains(OWN + "|" + s)) mine.add(s);
        Random r = new Random();
        if (a.groups.size() == 1) {   // a topic each
            List<String> w = chosen.get(0);
            if (w.isEmpty() && mine.isEmpty()) for (String[] o : a.groups.get(0).options) w.add(o[1]);
            for (String x : w) part(out, a, x);
        } else {   // Recipes: mixes of one of each ticked row (empty rows left out), up to twelve
            for (int i = 0; i < 12; i++) {
                StringBuilder q = new StringBuilder();
                for (int g = 0; g < chosen.size(); g++) {
                    List<String> w = chosen.get(g);
                    if (!w.isEmpty()) q.append(w.get(r.nextInt(w.size()))).append(' ');
                }
                String words = q.toString().trim();
                if (words.isEmpty() && !mine.isEmpty()) break;
                if (words.isEmpty()) words = a.groups.get(1).options.get(r.nextInt(a.groups.get(1).options.size()))[1];
                part(out, a, words);
            }
        }
        for (String s : mine) part(out, a, s);
        return out;
    }

    private static void part(Picks.Entry card, Asks a, String words) {
        Picks.Entry p = new Picks.Entry(card.emoji, words, card.color);
        p.tune = a.tune.apply(words);
        card.parts.add(p);
    }
}
