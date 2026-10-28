package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class LocationTest {

    private static final LocationRepository.Selected HERE = new LocationRepository.Selected(
            18.9220, 72.8347, "Colaba", LocationRepository.Kind.DEVICE, 15f);
    private static final long NOW = 10_000_000L;
    private static final long LONG_AGO = NOW - 5 * 60_000;

    @Test
    public void followsOnlyRealMovesWithPreciseFixes() {
        // ~330 m north: a real move.
        assertTrue(LocationRepository.shouldFollow(HERE, 18.9250, 72.8347, 12f, LONG_AGO, NOW));
        // ~55 m: GPS jitter, ignore.
        assertFalse(LocationRepository.shouldFollow(HERE, 18.9225, 72.8347, 12f, LONG_AGO, NOW));
        // Vague fix (±500 m) is never acted on.
        assertFalse(LocationRepository.shouldFollow(HERE, 18.9300, 72.8347, 500f, LONG_AGO, NOW));
        // Rate limited: a move 20 s after the last one waits.
        assertFalse(LocationRepository.shouldFollow(HERE, 18.9300, 72.8347, 12f, NOW - 20_000, NOW));
        // A chosen area is never overridden by the device position.
        LocationRepository.Selected area = new LocationRepository.Selected(
                19.0596, 72.8295, "Bandra West", LocationRepository.Kind.MANUAL);
        assertFalse(LocationRepository.shouldFollow(area, 18.9220, 72.8347, 5f, LONG_AGO, NOW));
    }

    @Test
    public void coarseFirstFixIsRefinedByAMuchBetterOne() {
        LocationRepository.Selected coarse = new LocationRepository.Selected(
                18.9220, 72.8347, "Colaba", LocationRepository.Kind.DEVICE, 900f);
        // 150 m away (less than the follow distance) but ±10 m instead of ±900 m.
        assertTrue(LocationRepository.shouldFollow(coarse, 18.92335, 72.8347, 10f, LONG_AGO, NOW));
        // Unknown accuracy on the old fix: only real moves count.
        LocationRepository.Selected unknown = new LocationRepository.Selected(
                18.9220, 72.8347, "Colaba", LocationRepository.Kind.DEVICE);
        assertFalse(LocationRepository.shouldFollow(unknown, 18.92335, 72.8347, 10f, LONG_AGO, NOW));
    }

    @Test
    public void parsesAreaMatchesAndSkipsBrokenOnes() throws Exception {
        JSONObject body = new JSONObject("{\"results\":["
                + "{\"name\":\"Phoenix Palladium\",\"address\":\"Lower Parel, Mumbai\",\"lat\":18.9945,\"lng\":72.8251},"
                + "{\"name\":\"\",\"lat\":19.0,\"lng\":72.8},"
                + "{\"name\":\"No coordinates\"},"
                + "{\"name\":\"Bandra Bandstand\",\"lat\":19.0469,\"lng\":72.8197}]}");
        List<LocationRepository.AreaMatch> matches = LocationRepository.parseAreas(body);
        assertEquals(2, matches.size());
        assertEquals("Phoenix Palladium", matches.get(0).location.label);
        assertEquals("Lower Parel, Mumbai", matches.get(0).address);
        assertEquals(LocationRepository.Kind.MANUAL, matches.get(0).location.kind);
        assertTrue(matches.get(0).fromGoogle);
        assertEquals("", matches.get(1).address);
        assertTrue(LocationRepository.parseAreas(new JSONObject("{}")).isEmpty());
        assertTrue(LocationRepository.parseAreas(null).isEmpty());
    }
}
