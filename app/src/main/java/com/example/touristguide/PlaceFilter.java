package com.example.touristguide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure filtering/sorting logic shared by live and curated results (unit tested). */
public final class PlaceFilter {
    private PlaceFilter() {
    }

    /** Whether a curated place matches the query's text and category. */
    public static boolean matchesCurated(Place p, PlaceQuery q) {
        if (!matchesCategory(p, q.category)) return false;
        if (q.text.isEmpty()) return true;
        String needle = q.text.toLowerCase(Locale.ROOT);
        return p.name.toLowerCase(Locale.ROOT).contains(needle)
                || p.category.toLowerCase(Locale.ROOT).contains(needle)
                || p.area.toLowerCase(Locale.ROOT).contains(needle)
                || p.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    public static boolean matchesCategory(Place p, PlaceQuery.Category c) {
        switch (c) {
            case ALL:
                return true;
            case ATTRACTIONS:
                return !p.food;
            case RESTAURANTS:
                return p.food;
            default:
                return c.curatedTag != null && p.hasTag(c.curatedTag);
        }
    }

    /** Curated places matching the query, sorted and with "open now" applied. */
    public static List<Place> curated(List<Place> catalog, PlaceQuery q, double lat, double lng) {
        List<Place> out = new ArrayList<>();
        for (Place p : catalog) if (matchesCurated(p, q)) out.add(p);
        return finish(out, q, lat, lng);
    }

    /**
     * Removes duplicate ids (first wins), drops places without a known "open" status when
     * {@code openNow} is requested, then sorts.
     */
    public static List<Place> finish(List<Place> places, PlaceQuery q, double lat, double lng) {
        Map<String, Place> unique = new LinkedHashMap<>();
        for (Place p : places) {
            if (p == null || unique.containsKey(p.id)) continue;
            if (q.openNow && !Boolean.TRUE.equals(p.openNow)) continue;
            unique.put(p.id, p);
        }
        List<Place> out = new ArrayList<>(unique.values());
        sort(out, q.sort, lat, lng);
        return out;
    }

    public static void sort(List<Place> list, PlaceQuery.Sort sort, double lat, double lng) {
        if (sort == PlaceQuery.Sort.DISTANCE) {
            list.sort(Comparator.comparingDouble(p -> GeoUtils.distanceMeters(lat, lng, p.lat, p.lng)));
        } else if (sort == PlaceQuery.Sort.RATING) {
            // Unrated places go last; more reviews break ties. Stable sort keeps relevance order otherwise.
            list.sort((a, b) -> {
                double ra = a.rating == null ? -1 : a.rating;
                double rb = b.rating == null ? -1 : b.rating;
                if (ra != rb) return Double.compare(rb, ra);
                int ca = a.ratingCount == null ? 0 : a.ratingCount;
                int cb = b.ratingCount == null ? 0 : b.ratingCount;
                return Integer.compare(cb, ca);
            });
        }
    }

    /** Appends {@code more} to {@code base} without duplicating ids (used for "load more"). */
    public static List<Place> merge(List<Place> base, List<Place> more) {
        Map<String, Place> unique = new LinkedHashMap<>();
        for (Place p : base) unique.put(p.id, p);
        for (Place p : more) if (!unique.containsKey(p.id)) unique.put(p.id, p);
        return new ArrayList<>(unique.values());
    }
}
