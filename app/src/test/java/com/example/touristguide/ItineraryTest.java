package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ItineraryTest {

    private static Place place(String name, double lat, double lng) {
        return new Place(name, "Test", "Area", "", "📍", 30, 0, lat, lng, "Historic");
    }

    private static List<String> names(List<Itinerary.Stop> stops) {
        List<String> out = new ArrayList<>();
        for (Itinerary.Stop s : stops) out.add(s.place.name);
        return out;
    }

    @Test
    public void preventsDuplicatesById() {
        Itinerary it = new Itinerary();
        Place a = place("A", 0, 0);
        assertEquals(Itinerary.AddResult.ADDED, it.add(a, 1));
        assertEquals(Itinerary.AddResult.DUPLICATE, it.add(place("A", 5, 5), 1));
        assertEquals(1, it.size());
    }

    @Test
    public void organisesByDayAndClampsDays() {
        Itinerary it = new Itinerary();
        it.setDays(2);
        it.add(place("A", 0, 0), 2);
        it.add(place("B", 0, 0), 1);
        it.add(place("C", 0, 0), 9); // clamped to the last day
        assertEquals(Arrays.asList("B", "A", "C"), names(it.stops()));
        it.setDays(1); // removing day 2 moves its stops to day 1
        assertEquals(3, it.stopsForDay(1).size());
        it.setDays(0);
        assertEquals(1, it.days());
        it.setDays(99);
        assertEquals(Itinerary.MAX_DAYS, it.days());
    }

    @Test
    public void reordersWithinADayOnly() {
        Itinerary it = new Itinerary();
        it.setDays(2);
        it.add(place("A", 0, 0), 1);
        it.add(place("X", 0, 0), 2);
        it.add(place("B", 0, 0), 1);
        assertTrue(it.moveUp("curated:b"));
        assertEquals(Arrays.asList("B", "A"), names(it.stopsForDay(1)));
        assertFalse(it.moveUp("curated:b")); // already first in its day
        assertTrue(it.moveDown("curated:b"));
        assertFalse(it.moveDown("curated:b")); // last stop of day 1 cannot jump into day 2
        assertEquals(Arrays.asList("A", "B"), names(it.stopsForDay(1)));
        assertTrue(it.moveToDay("curated:a", 2));
        assertEquals(Arrays.asList("X", "A"), names(it.stopsForDay(2)));
        assertFalse(it.moveUp("missing"));
    }

    @Test
    public void removeAndUndoRestorePosition() {
        Itinerary it = new Itinerary();
        it.add(place("A", 0, 0), 1);
        it.add(place("B", 0, 0), 1);
        it.add(place("C", 0, 0), 1);
        Itinerary.Stop b = it.find("curated:b");
        int index = it.indexOf("curated:b");
        assertTrue(it.remove("curated:b"));
        assertFalse(it.remove("curated:b"));
        it.restore(b, index);
        assertEquals(Arrays.asList("A", "B", "C"), names(it.stops()));
    }

    @Test
    public void costsOnlyCountWhenEntered() {
        Itinerary it = new Itinerary();
        it.add(place("A", 0, 0), 1);
        it.add(place("B", 0, 0), 1);
        it.add(place("C", 0, 0), 1);
        it.setEstimatedCost("curated:a", 400L);
        it.setEstimatedCost("curated:b", 0L);
        assertEquals(400, it.enteredCostTotal());
        assertEquals(2, it.stopsWithCost());
        it.setEstimatedCost("curated:a", null);
        assertEquals(1, it.stopsWithCost());
        it.setEstimatedCost("curated:c", Long.MAX_VALUE);
        assertEquals(BudgetCalculator.MAX_AMOUNT, it.enteredCostTotal());
        it.setEstimatedCost("curated:c", -5L);
        assertEquals(0, it.enteredCostTotal());
    }

    @Test
    public void notesAreTrimmedAndLimited() {
        Itinerary it = new Itinerary();
        it.add(place("A", 0, 0), 1);
        it.setNote("curated:a", "  buy ferry tickets  ");
        assertEquals("buy ferry tickets", it.find("curated:a").note);
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 500; i++) longNote.append('x');
        it.setNote("curated:a", longNote.toString());
        assertEquals(Itinerary.MAX_NOTE_LENGTH, it.find("curated:a").note.length());
    }

    @Test
    public void proximityOrderingIsNearestNeighbourPerDay() {
        Itinerary it = new Itinerary();
        it.add(place("Start", 18.90, 72.80), 1);
        it.add(place("Far", 19.20, 72.80), 1);
        it.add(place("Near", 18.91, 72.80), 1);
        it.add(place("Mid", 19.00, 72.80), 1);
        it.orderEachDayByProximity();
        assertEquals(Arrays.asList("Start", "Near", "Mid", "Far"), names(it.stops()));
    }

    @Test
    public void jsonRoundTripKeepsEverything() throws Exception {
        Itinerary it = new Itinerary();
        it.setDays(3);
        it.setTripName("Weekend in Mumbai");
        it.setStartDate("2026-11-14");
        it.add(Catalog.PLACES.get(0), 2);
        Place live = new Place.Builder("google:abc123", Place.Source.LIVE, "Cafe X", 18.9, 72.8)
                .food(true).rating(4.4).ratingCount(120).priceLevel("PRICE_LEVEL_MODERATE").openNow(true)
                .photo("places/abc123/photos/xyz", "Someone").build();
        it.add(live, 3);
        it.setNote("google:abc123", "lunch");
        it.setEstimatedCost("google:abc123", 600L);

        Itinerary copy = Itinerary.fromJson(new JSONObject(it.toJson().toString()));
        assertEquals(3, copy.days());
        assertEquals("Weekend in Mumbai", copy.tripName());
        assertEquals("2026-11-14", copy.startDate());
        Itinerary.Stop stop = copy.find("google:abc123");
        assertEquals(3, stop.day);
        assertEquals("lunch", stop.note);
        assertEquals(Long.valueOf(600), stop.estimatedCost);
        assertEquals(4.4, stop.place.rating, 0.001);
        assertTrue(stop.place.isLive());
        assertNull("photo names must not be persisted", stop.place.photoName);
        assertEquals(2, copy.find(Catalog.PLACES.get(0).id).day);
    }

    @Test
    public void corruptJsonIsIgnored() throws Exception {
        Itinerary it = Itinerary.fromJson(new JSONObject("{\"days\":2,\"stops\":[{\"place\":{\"id\":\"\"}},5,{\"day\":1}]}"));
        assertEquals(0, it.size());
        assertEquals(2, it.days());
        assertEquals(0, Itinerary.fromJson(null).size());
    }
}
