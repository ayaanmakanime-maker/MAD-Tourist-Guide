package com.example.touristguide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The one place model shared by Explore, Map, Plan and Chat.
 *
 * <p>Curated places come from {@link Catalog}; live places come from Google Places via the
 * backend. Every place has a stable {@link #id}: "curated:&lt;slug&gt;" or "google:&lt;placeId&gt;".
 * Provider fields are nullable and are only shown when the provider supplied them.</p>
 */
public class Place {
    public enum Source { CURATED, LIVE }

    public static final String CURATED_PREFIX = "curated:";
    public static final String GOOGLE_PREFIX = "google:";
    /** Unknown entry fee / visit time. */
    public static final int UNKNOWN = -1;

    public final String id;
    public final Source source;
    public final String name;
    public final String category;
    public final String area;
    public final String description;
    public final String emoji;
    /** Suggested visit length in minutes (curated only), or {@link #UNKNOWN}. */
    public final int minutes;
    /** Indicative entry fee in rupees (curated only, 0 = free), or {@link #UNKNOWN}. */
    public final int budget;
    public final double lat;
    public final double lng;
    public final List<String> tags;
    public final boolean food;

    // Optional provider details (null when not supplied).
    public final Double rating;
    public final Integer ratingCount;
    public final String priceLevel;
    public final Integer priceStart;
    public final Integer priceEnd;
    public final String priceCurrency;
    public final Boolean openNow;
    public final List<String> hours;
    public final String mapsUri;
    public final String businessStatus;
    /** Google photo resource name. Never persisted (Google photo names expire). */
    public final String photoName;
    public final String photoAttribution;

    /** Curated constructor, kept compatible with the original catalogue entries. */
    public Place(String name, String category, String area, String description, String emoji,
                 int minutes, int budget, double lat, double lng, String... tags) {
        this(new Builder(CURATED_PREFIX + slug(name), Source.CURATED, name, lat, lng)
                .category(category).area(area).description(description).emoji(emoji)
                .minutes(minutes).budget(budget).tags(Arrays.asList(tags))
                .food(Arrays.asList(tags).contains("Food") || Arrays.asList(tags).contains("Cafe")));
    }

    private Place(Builder b) {
        id = b.id;
        source = b.source;
        name = b.name;
        category = b.category == null ? "" : b.category;
        area = b.area == null ? "" : b.area;
        description = b.description == null ? "" : b.description;
        emoji = b.emoji == null ? (b.food ? "🍽️" : "📍") : b.emoji;
        minutes = b.minutes;
        budget = b.budget;
        lat = b.lat;
        lng = b.lng;
        tags = Collections.unmodifiableList(new ArrayList<>(b.tags));
        food = b.food;
        rating = b.rating;
        ratingCount = b.ratingCount;
        priceLevel = b.priceLevel;
        priceStart = b.priceStart;
        priceEnd = b.priceEnd;
        priceCurrency = b.priceCurrency;
        openNow = b.openNow;
        hours = Collections.unmodifiableList(new ArrayList<>(b.hours));
        mapsUri = b.mapsUri;
        businessStatus = b.businessStatus;
        photoName = b.photoName;
        photoAttribution = b.photoAttribution;
    }

    public boolean isLive() {
        return source == Source.LIVE;
    }

    /** The raw Google place id for live places, otherwise null. */
    public String googleId() {
        return id.startsWith(GOOGLE_PREFIX) ? id.substring(GOOGLE_PREFIX.length()) : null;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** Converts a name into a stable lowercase id fragment, e.g. "Gateway of India" -> "gateway-of-india". */
    public static String slug(String value) {
        String s = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    // ------------------------------------------------------------------ JSON

    /** Parses one place from the backend's normalised JSON. Returns null if unusable. */
    public static Place fromBackendJson(JSONObject o) {
        String rawId = o.optString("id", "");
        String name = o.optString("name", "");
        if (rawId.isEmpty() || name.isEmpty() || !o.has("lat") || !o.has("lng")) return null;
        boolean live = !"curated".equals(o.optString("source", "live"));
        String id = live && !rawId.startsWith(GOOGLE_PREFIX) ? GOOGLE_PREFIX + rawId : rawId;
        Builder b = new Builder(id, live ? Source.LIVE : Source.CURATED, name,
                o.optDouble("lat"), o.optDouble("lng"))
                .category(optString(o, "category_label"))
                .area(optString(o, "address"))
                .food(o.optBoolean("is_food", false))
                .rating(optDouble(o, "rating"))
                .ratingCount(optInt(o, "rating_count"))
                .priceLevel(optString(o, "price_level"))
                .openNow(o.has("open_now") && !o.isNull("open_now") ? o.optBoolean("open_now") : null)
                .mapsUri(optString(o, "maps_uri"))
                .businessStatus(optString(o, "business_status"));
        JSONObject range = o.optJSONObject("price_range");
        if (range != null) {
            b.priceRange(optInt(range, "start"), optInt(range, "end"), optString(range, "currency"));
        }
        JSONArray hours = o.optJSONArray("hours");
        if (hours != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < hours.length(); i++) list.add(hours.optString(i));
            b.hours(list);
        }
        JSONObject photo = o.optJSONObject("photo");
        if (photo != null) {
            JSONArray attributions = photo.optJSONArray("attributions");
            String by = attributions == null || attributions.length() == 0 ? null : attributions.optString(0);
            b.photo(optString(photo, "name"), by);
        }
        if (Double.isNaN(b.lat) || Double.isNaN(b.lng)) return null;
        return b.build();
    }

    /**
     * Snapshot used for favourites, itinerary and the short-lived results cache.
     * Photo names are deliberately left out because Google says they must not be cached.
     */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("source", source.name());
            o.put("name", name);
            o.put("category", category);
            o.put("area", area);
            o.put("description", description);
            o.put("emoji", emoji);
            o.put("minutes", minutes);
            o.put("budget", budget);
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("food", food);
            o.put("tags", new JSONArray(tags));
            if (rating != null) o.put("rating", rating);
            if (ratingCount != null) o.put("ratingCount", ratingCount);
            if (priceLevel != null) o.put("priceLevel", priceLevel);
            if (priceStart != null) o.put("priceStart", priceStart);
            if (priceEnd != null) o.put("priceEnd", priceEnd);
            if (priceCurrency != null) o.put("priceCurrency", priceCurrency);
            if (openNow != null) o.put("openNow", openNow);
            if (!hours.isEmpty()) o.put("hours", new JSONArray(hours));
            if (mapsUri != null) o.put("mapsUri", mapsUri);
            if (businessStatus != null) o.put("businessStatus", businessStatus);
        } catch (JSONException ignored) {
            // Values are plain strings/numbers; put() only throws for NaN, which we never store.
        }
        return o;
    }

    /** Reads a snapshot written by {@link #toJson()}. Returns null if it is unusable. */
    public static Place fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String name = o.optString("name", "");
        if (id.isEmpty() || name.isEmpty()) return null;
        Source source = "LIVE".equals(o.optString("source")) ? Source.LIVE : Source.CURATED;
        Builder b = new Builder(id, source, name, o.optDouble("lat", Double.NaN), o.optDouble("lng", Double.NaN))
                .category(o.optString("category", ""))
                .area(o.optString("area", ""))
                .description(o.optString("description", ""))
                .emoji(optString(o, "emoji"))
                .minutes(o.optInt("minutes", UNKNOWN))
                .budget(o.optInt("budget", UNKNOWN))
                .food(o.optBoolean("food", false))
                .rating(optDouble(o, "rating"))
                .ratingCount(optInt(o, "ratingCount"))
                .priceLevel(optString(o, "priceLevel"))
                .priceRange(optInt(o, "priceStart"), optInt(o, "priceEnd"), optString(o, "priceCurrency"))
                .openNow(o.has("openNow") ? o.optBoolean("openNow") : null)
                .mapsUri(optString(o, "mapsUri"))
                .businessStatus(optString(o, "businessStatus"));
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) list.add(tags.optString(i));
            b.tags(list);
        }
        JSONArray hours = o.optJSONArray("hours");
        if (hours != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < hours.length(); i++) list.add(hours.optString(i));
            b.hours(list);
        }
        if (Double.isNaN(b.lat) || Double.isNaN(b.lng)) return null;
        return b.build();
    }

    private static String optString(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String value = o.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    private static Double optDouble(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        double value = o.optDouble(key, Double.NaN);
        return Double.isNaN(value) ? null : value;
    }

    private static Integer optInt(JSONObject o, String key) {
        Double value = optDouble(o, key);
        return value == null ? null : (int) Math.round(value);
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private final String id;
        private final Source source;
        private final String name;
        private final double lat;
        private final double lng;
        private String category;
        private String area;
        private String description;
        private String emoji;
        private int minutes = UNKNOWN;
        private int budget = UNKNOWN;
        private List<String> tags = new ArrayList<>();
        private boolean food;
        private Double rating;
        private Integer ratingCount;
        private String priceLevel;
        private Integer priceStart;
        private Integer priceEnd;
        private String priceCurrency;
        private Boolean openNow;
        private List<String> hours = new ArrayList<>();
        private String mapsUri;
        private String businessStatus;
        private String photoName;
        private String photoAttribution;

        public Builder(String id, Source source, String name, double lat, double lng) {
            this.id = id;
            this.source = source;
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }

        public Builder category(String v) { category = v; return this; }
        public Builder area(String v) { area = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder emoji(String v) { emoji = v; return this; }
        public Builder minutes(int v) { minutes = v; return this; }
        public Builder budget(int v) { budget = v; return this; }
        public Builder tags(List<String> v) { tags = v == null ? new ArrayList<>() : v; return this; }
        public Builder food(boolean v) { food = v; return this; }
        public Builder rating(Double v) { rating = v; return this; }
        public Builder ratingCount(Integer v) { ratingCount = v; return this; }
        public Builder priceLevel(String v) { priceLevel = v; return this; }
        public Builder openNow(Boolean v) { openNow = v; return this; }
        public Builder hours(List<String> v) { hours = v == null ? new ArrayList<>() : v; return this; }
        public Builder mapsUri(String v) { mapsUri = v; return this; }
        public Builder businessStatus(String v) { businessStatus = v; return this; }

        public Builder priceRange(Integer start, Integer end, String currency) {
            priceStart = start;
            priceEnd = end;
            priceCurrency = currency;
            return this;
        }

        public Builder photo(String name, String attribution) {
            photoName = name;
            photoAttribution = attribution;
            return this;
        }

        public Place build() {
            return new Place(this);
        }
    }
}
