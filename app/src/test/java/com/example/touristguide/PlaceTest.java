package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaceTest {

    private static final double LAT = 18.9220;
    private static final double LNG = 72.8347;

    private static Place live(String id, double rating, int count, Boolean open, double lat) {
        return new Place.Builder("google:" + id, Place.Source.LIVE, id, lat, LNG)
                .rating(rating).ratingCount(count).openNow(open).build();
    }

    @Test
    public void catalogIdsAreStableAndUnique() {
        Set<String> ids = new HashSet<>();
        for (Place p : Catalog.PLACES) {
            assertTrue(p.id.startsWith(Place.CURATED_PREFIX));
            assertTrue("duplicate " + p.id, ids.add(p.id));
            assertTrue(GeoUtils.isValid(p.lat, p.lng));
            assertNull("curated data must not invent ratings", p.rating);
        }
        assertEquals("curated:gateway-of-india", Catalog.byName("Gateway of India").id);
        assertNotNull(Catalog.byId("curated:marine-drive"));
    }

    @Test
    public void parsesBackendJsonAndKeepsMissingFieldsMissing() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", "ChIJ123").put("name", "Veg Place").put("lat", 18.9).put("lng", 72.8)
                .put("is_food", true).put("rating", JSONObject.NULL).put("open_now", false)
                .put("price_level", "PRICE_LEVEL_INEXPENSIVE")
                .put("price_range", new JSONObject().put("start", 200).put("end", 400).put("currency", "INR"))
                .put("hours", new JSONArray(Arrays.asList("Monday: 9 AM – 5 PM")))
                .put("photo", new JSONObject().put("name", "places/ChIJ123/photos/p1")
                        .put("attributions", new JSONArray(Arrays.asList("Ann"))));
        Place p = Place.fromBackendJson(o);
        assertEquals("google:ChIJ123", p.id);
        assertEquals("ChIJ123", p.googleId());
        assertTrue(p.isLive() && p.food);
        assertNull(p.rating);
        assertNull(p.ratingCount);
        assertEquals(Boolean.FALSE, p.openNow);
        assertEquals(Integer.valueOf(200), p.priceStart);
        assertEquals("Ann", p.photoAttribution);
        assertEquals(1, p.hours.size());
    }

    @Test
    public void rejectsBackendJsonWithoutEssentials() throws Exception {
        assertNull(Place.fromBackendJson(new JSONObject().put("id", "x").put("name", "n")));
        assertNull(Place.fromBackendJson(new JSONObject().put("name", "n").put("lat", 1).put("lng", 1)));
    }

    @Test
    public void curatedSourceIsPreservedFromChatEcho() throws Exception {
        JSONObject o = new JSONObject().put("id", "curated:gateway-of-india").put("name", "Gateway of India")
                .put("lat", LAT).put("lng", LNG).put("source", "curated");
        Place p = Place.fromBackendJson(o);
        assertFalse(p.isLive());
        assertEquals("curated:gateway-of-india", p.id);
    }

    @Test
    public void filterDedupesSortsAndAppliesOpenNow() {
        List<Place> in = new ArrayList<>(Arrays.asList(
                live("a", 4.0, 10, true, 18.95),
                live("b", 4.8, 5, null, 18.93),
                live("a", 1.0, 1, false, 18.95),
                live("c", 4.8, 500, true, 19.10)));
        PlaceQuery rating = PlaceQuery.defaults().withSort(PlaceQuery.Sort.RATING);
        List<String> ids = new ArrayList<>();
        for (Place p : PlaceFilter.finish(in, rating, LAT, LNG)) ids.add(p.name);
        assertEquals(Arrays.asList("c", "b", "a"), ids);

        PlaceQuery distance = PlaceQuery.defaults().withSort(PlaceQuery.Sort.DISTANCE);
        ids.clear();
        for (Place p : PlaceFilter.finish(in, distance, LAT, LNG)) ids.add(p.name);
        assertEquals(Arrays.asList("b", "a", "c"), ids);

        // Open now hides closed AND unknown-hours places rather than guessing.
        PlaceQuery open = PlaceQuery.defaults().withOpenNow(true);
        ids.clear();
        for (Place p : PlaceFilter.finish(in, open, LAT, LNG)) ids.add(p.name);
        assertEquals(Arrays.asList("a", "c"), ids);
    }

    @Test
    public void curatedFilterByCategoryAndText() {
        LocationRepository.Selected loc = LocationRepository.DEFAULT;
        List<Place> food = PlaceFilter.curated(Catalog.PLACES,
                PlaceQuery.defaults().withCategory(PlaceQuery.Category.RESTAURANTS), loc.lat, loc.lng);
        assertFalse(food.isEmpty());
        for (Place p : food) assertTrue(p.food);

        List<Place> attractions = PlaceFilter.curated(Catalog.PLACES,
                PlaceQuery.defaults().withCategory(PlaceQuery.Category.ATTRACTIONS), loc.lat, loc.lng);
        for (Place p : attractions) assertFalse(p.food);
        assertEquals(Catalog.PLACES.size(), food.size() + attractions.size());

        List<Place> beach = PlaceFilter.curated(Catalog.PLACES,
                PlaceQuery.defaults().withText("juhu"), loc.lat, loc.lng);
        assertEquals("Juhu Beach", beach.get(0).name);
        assertTrue(PlaceFilter.curated(Catalog.PLACES, PlaceQuery.defaults().withText("zzz"), loc.lat, loc.lng).isEmpty());
    }

    @Test
    public void mergeDoesNotDuplicate() {
        List<Place> merged = PlaceFilter.merge(
                Arrays.asList(live("a", 4, 1, null, LAT), live("b", 4, 1, null, LAT)),
                Arrays.asList(live("b", 4, 1, null, LAT), live("c", 4, 1, null, LAT)));
        assertEquals(3, merged.size());
    }

    @Test
    public void queryNormalisesInput() {
        PlaceQuery q = new PlaceQuery("  veg   thali ", null, 999_999, null, false);
        assertEquals("veg thali", q.text);
        assertEquals(PlaceQuery.Category.ALL, q.category);
        assertEquals(50_000, q.radiusMeters);
        assertTrue(q.isTextSearch());
        assertFalse(PlaceQuery.defaults().withText("a").isTextSearch());
        assertEquals(PlaceQuery.Category.CAFES, PlaceQuery.Category.fromKey("cafes"));
        assertEquals(PlaceQuery.Category.ALL, PlaceQuery.Category.fromKey("unknown"));
    }

    @Test
    public void distanceAndFormatting() {
        // Gateway of India to CSMT is roughly 2 km in a straight line.
        double d = GeoUtils.distanceMeters(18.9220, 72.8347, 18.9398, 72.8355);
        assertTrue(d > 1900 && d < 2100);
        assertEquals(0, GeoUtils.distanceMeters(LAT, LNG, LAT, LNG), 0.001);
        assertEquals("350 m", GeoUtils.formatDistance(347));
        assertEquals("1.2 km", GeoUtils.formatDistance(1234));
        assertEquals("14 km", GeoUtils.formatDistance(14_400));
        assertEquals("5 km", GeoUtils.formatDistance(5000));
        assertFalse(GeoUtils.isValid(91, 0));
        assertFalse(GeoUtils.isValid(Double.NaN, 0));
    }
}
