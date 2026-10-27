package com.example.touristguide;

import java.util.Locale;

/** What the user asked Explore for. Immutable; the same query drives live search and the curated fallback. */
public final class PlaceQuery {

    /** Explore categories. {@code key} is the backend category key; {@code curatedTag} filters the offline guide. */
    public enum Category {
        ALL("all", null, R.string.cat_all),
        ATTRACTIONS("attractions", null, R.string.cat_attractions),
        RESTAURANTS("restaurants", "Food", R.string.cat_restaurants),
        CAFES("cafes", "Cafe", R.string.cat_cafes),
        HISTORIC("historic", "Historic", R.string.cat_historic),
        BEACH("beach", "Beach", R.string.cat_beach),
        MUSEUM("museum", "Museum", R.string.cat_museum),
        SHOPPING("shopping", "Shopping", R.string.cat_shopping),
        NATURE("nature", "Nature", R.string.cat_nature);

        public final String key;
        public final String curatedTag;
        public final int label;

        Category(String key, String curatedTag, int label) {
            this.key = key;
            this.curatedTag = curatedTag;
            this.label = label;
        }

        public boolean isFood() {
            return this == RESTAURANTS || this == CAFES;
        }

        public static Category fromKey(String key) {
            for (Category c : values()) if (c.key.equals(key)) return c;
            return ALL;
        }
    }

    public enum Sort { RELEVANCE, DISTANCE, RATING }

    public static final int[] RADIUS_OPTIONS_M = {1000, 2000, 5000, 10000, 25000};
    public static final int DEFAULT_RADIUS_M = 5000;

    public final String text;
    public final Category category;
    public final int radiusMeters;
    public final Sort sort;
    public final boolean openNow;

    public PlaceQuery(String text, Category category, int radiusMeters, Sort sort, boolean openNow) {
        this.text = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        this.category = category == null ? Category.ALL : category;
        this.radiusMeters = Math.max(100, Math.min(50_000, radiusMeters));
        this.sort = sort == null ? Sort.RELEVANCE : sort;
        this.openNow = openNow;
    }

    public static PlaceQuery defaults() {
        return new PlaceQuery("", Category.ALL, DEFAULT_RADIUS_M, Sort.RELEVANCE, false);
    }

    public PlaceQuery withText(String t) { return new PlaceQuery(t, category, radiusMeters, sort, openNow); }
    public PlaceQuery withCategory(Category c) { return new PlaceQuery(text, c, radiusMeters, sort, openNow); }
    public PlaceQuery withRadius(int r) { return new PlaceQuery(text, category, r, sort, openNow); }
    public PlaceQuery withSort(Sort s) { return new PlaceQuery(text, category, radiusMeters, s, openNow); }
    public PlaceQuery withOpenNow(boolean o) { return new PlaceQuery(text, category, radiusMeters, sort, o); }

    /** Live search only runs for 2+ characters; shorter text falls back to a nearby search. */
    public boolean isTextSearch() {
        return text.length() >= 2;
    }

    /** Identifies the provider request (sort and open-now are applied on the device). */
    public String cacheKey(double lat, double lng) {
        return String.format(Locale.US, "%s|%s|%d|%.3f|%.3f",
                isTextSearch() ? text.toLowerCase(Locale.ROOT) : "", category.key, radiusMeters, lat, lng);
    }
}
