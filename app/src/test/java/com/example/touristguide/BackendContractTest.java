package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * End-to-end contract test: the real app data layer against the real FastAPI backend, with only
 * Google/Gemini faked (backend/tests/fake_provider_server.py). Skipped unless started with
 * -PcontractBackendUrl=http://127.0.0.1:8765
 */
@RunWith(RobolectricTestRunner.class)
public class BackendContractTest {

    private String url;

    @Before
    public void setUp() {
        url = System.getProperty("contractBackendUrl");
        assumeTrue("contract backend not configured", url != null && !url.isEmpty());
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("tourist_guide", Context.MODE_PRIVATE).edit().clear().commit();
        Store.init(c);
        Store.setBackendUrl(url);
        LocationRepository.select(LocationRepository.PRESETS.get(0));
        PlaceRepository.clearCache();
        PlanRepository.reload();
    }

    private static void waitFor(BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
        }
        throw new AssertionError("timed out");
    }

    @Test
    public void liveNearbyResultsParseIntoSharedModel() {
        PlaceRepository.load(PlaceQuery.defaults(), true);
        waitFor(() -> PlaceRepository.state().status == PlaceRepository.Status.LIVE);
        List<Place> places = PlaceRepository.state().places;
        assertEquals(2, places.size());
        Place gateway = PlaceRepository.get("google:ChIJ-gateway-0001");
        assertNotNull(gateway);
        assertTrue(gateway.isLive());
        assertEquals(4.6, gateway.rating, 1e-9);
        assertEquals(Integer.valueOf(250000), gateway.ratingCount);
        assertEquals(Boolean.TRUE, gateway.openNow);
        assertNotNull(gateway.photoName);
        Place food = PlaceRepository.get("google:ChIJ-food-000002");
        assertTrue(food.food);
        assertNull("missing rating must stay missing", food.rating);
        assertEquals(Integer.valueOf(200), food.priceStart);

        // The same place can go straight into the plan, and is cached (without photo names).
        assertEquals(Itinerary.AddResult.ADDED, PlanRepository.add(gateway, 1));
        assertNull(Store.cachedPlaces(PlaceQuery.defaults().cacheKey(
                LocationRepository.selected().lat, LocationRepository.selected().lng)).places.get(0).photoName);
    }

    @Test
    public void textSearchAndDetailsWork() {
        PlaceRepository.load(PlaceQuery.defaults().withText("veg thali"), true);
        waitFor(() -> PlaceRepository.state().status == PlaceRepository.Status.LIVE);
        AtomicReference<Place> fresh = new AtomicReference<>();
        PlaceRepository.refreshDetails(PlaceRepository.get("google:ChIJ-gateway-0001"), new BackendClient.Callback<Place>() {
            @Override
            public void onSuccess(Place value) {
                fresh.set(value);
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                throw new AssertionError(error.kind.name());
            }
        });
        waitFor(() -> fresh.get() != null);
        assertEquals("Gateway of India", fresh.get().name);
    }

    @Test
    public void chatReplyIsGroundedAndActionable() {
        AtomicReference<AiClient.Reply> reply = new AtomicReference<>();
        JSONObject req = AiClient.buildRequest("Find vegetarian restaurants near me under ₹500",
                LocationRepository.selected(), new ArrayList<>(Catalog.PLACES), null, PlanRepository.get(),
                null, Collections.emptyList());
        AiClient.chat(req, new AiClient.Listener() {
            @Override
            public void onReply(AiClient.Reply r) {
                reply.set(r);
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                throw new AssertionError(error.kind.name());
            }
        });
        waitFor(() -> reply.get() != null);
        assertFalse(reply.get().text.isEmpty());
        assertEquals(1, reply.get().places.size()); // the invented id was dropped by the backend
        Place p = reply.get().places.get(0);
        assertEquals("google:ChIJ-food-000002", p.id);
        assertEquals(Itinerary.AddResult.ADDED, PlanRepository.add(p, 1));
        assertNotNull(PlaceRepository.get(p.id));
    }

    @Test
    public void areaSearchReturnsGoogleMatchesThatCanBeSelected() {
        AtomicReference<List<LocationRepository.AreaMatch>> found = new AtomicReference<>();
        LocationRepository.findAreas(ApplicationProvider.getApplicationContext(), "Gateway of India",
                new LocationRepository.AreasCallback() {
                    @Override
                    public void onAreas(List<LocationRepository.AreaMatch> matches) {
                        found.set(matches);
                    }

                    @Override
                    public void onFailure(LocationRepository.Failure failure) {
                        throw new AssertionError(failure.name());
                    }
                });
        waitFor(() -> found.get() != null);
        LocationRepository.AreaMatch first = found.get().get(0);
        assertTrue(first.fromGoogle);
        assertEquals("Gateway of India", first.location.label);
        assertEquals(18.9220, first.location.lat, 1e-6);
        LocationRepository.select(first.location);
        assertEquals("Gateway of India", LocationRepository.selected().label);
    }
}
