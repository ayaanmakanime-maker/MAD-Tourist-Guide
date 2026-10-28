package com.example.touristguide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rule-based suggestions used when the AI backend is unreachable. It never invents anything:
 * it only picks from places the app already has (live, cached or curated) and the chat UI labels
 * the answer as an offline suggestion.
 */
public final class OfflineAssistant {

    public enum Intent { FOOD, CAFE, DAY_PLAN, SIGHTSEEING, BUDGET, MY_PLAN }

    public static final class Suggestion {
        public final Intent intent;
        public final List<Place> places;
        /** True when the user asked for places that are open now. */
        public final boolean wantedOpenNow;

        Suggestion(Intent intent, List<Place> places, boolean wantedOpenNow) {
            this.intent = intent;
            this.places = places;
            this.wantedOpenNow = wantedOpenNow;
        }
    }

    private static final Pattern CAFE = Pattern.compile("\\b(cafes?|café|cafés|coffee|chai|tea)\\b");
    private static final Pattern FOOD = Pattern.compile(
            "\\b(restaurants?|food|eat|lunch|dinner|breakfast|veg|vegetarian|vegan|snacks?|meal)\\b");
    private static final Pattern PLAN = Pattern.compile("\\b(plan|itinerary|day trip|half-day|half day|route)\\b");
    private static final Pattern OPEN = Pattern.compile("\\b(open now|currently open|still open|open right now)\\b");
    private static final Pattern MY_PLAN = Pattern.compile(
            "\\b(my (plan|trip|itinerary|stops)|what('s| is) (in|on) my|planned so far)\\b");
    private static final Pattern BUDGET = Pattern.compile(
            "\\b(budget|money|spend|spent|spending|afford|remaining|left to spend|how much (do|can|have|is left))\\b");

    /** Sightseeing topics: words in the question -> what to look for in a place's tags/category. */
    private static final String[][] TOPICS = {
            {"\\b(beach|beaches|seaside|sea face)\\b", "beach"},
            {"\\b(museums?|gallery|galleries|art)\\b", "museum|gallery"},
            {"\\b(histor\\w*|heritage|monuments?|forts?|caves?|landmarks?)\\b", "histor|monument|heritage|fort|cave"},
            {"\\b(shopping|shops?|markets?|malls?|bazaar)\\b", "shop|market|mall"},
            {"\\b(nature|parks?|gardens?|green|wildlife)\\b", "nature|park|garden"},
    };

    private OfflineAssistant() {
    }

    public static Intent detect(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (MY_PLAN.matcher(m).find()) return Intent.MY_PLAN;
        if (PLAN.matcher(m).find()) return Intent.DAY_PLAN;
        if (CAFE.matcher(m).find()) return Intent.CAFE;
        if (FOOD.matcher(m).find()) return Intent.FOOD;
        if (BUDGET.matcher(m).find()) return Intent.BUDGET;
        return Intent.SIGHTSEEING;
    }

    /** What to look for in a place for the sightseeing topic asked about, or null. */
    static String topic(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        for (String[] t : TOPICS) if (Pattern.compile(t[0]).matcher(m).find()) return t[1];
        return null;
    }

    private static boolean matchesTopic(Place p, String topic) {
        StringBuilder text = new StringBuilder(p.category == null ? "" : p.category); // String.join needs API 26
        for (String tag : p.tags) text.append(' ').append(tag);
        return Pattern.compile(topic).matcher(text.toString().toLowerCase(Locale.ROOT)).find();
    }

    public static Suggestion suggest(String message, List<Place> available, double lat, double lng) {
        Intent intent = detect(message);
        boolean openNow = OPEN.matcher(message == null ? "" : message.toLowerCase(Locale.ROOT)).find();
        List<Place> food = new ArrayList<>();
        List<Place> sights = new ArrayList<>();
        for (Place p : available) {
            if (openNow && !Boolean.TRUE.equals(p.openNow)) continue;
            if (p.food) food.add(p);
            else sights.add(p);
        }
        Comparator<Place> nearest = Comparator.comparingDouble(p -> GeoUtils.distanceMeters(lat, lng, p.lat, p.lng));
        food.sort(nearest);
        sights.sort(nearest);

        List<Place> picks = new ArrayList<>();
        switch (intent) {
            case CAFE:
                for (Place p : food) if (p.hasTag("Cafe") || p.category.toLowerCase(Locale.ROOT).contains("caf")) picks.add(p);
                if (picks.isEmpty()) picks.addAll(food);
                break;
            case FOOD:
                picks.addAll(food);
                break;
            case DAY_PLAN:
                // Up to three nearby sights plus one food stop for lunch.
                picks.addAll(sights.subList(0, Math.min(3, sights.size())));
                if (!food.isEmpty()) picks.add(Math.min(2, picks.size()), food.get(0));
                break;
            case BUDGET:
            case MY_PLAN:
                break; // answered from the user's own budget / plan, not from places
            default:
                String topic = topic(message);
                if (topic != null) for (Place p : sights) if (matchesTopic(p, topic)) picks.add(p);
                if (picks.isEmpty()) picks.addAll(sights);
        }
        return new Suggestion(intent, new ArrayList<>(picks.subList(0, Math.min(4, picks.size()))), openNow);
    }
}
