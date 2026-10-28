package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AssistantTest {

    private static final LocationRepository.Selected COLABA =
            new LocationRepository.Selected(18.906712, 72.814734, "Colaba", LocationRepository.Kind.DEVICE);

    @Test
    public void requestContainsGroundingContextButNoPreciseLocation() throws Exception {
        Itinerary plan = new Itinerary();
        plan.add(Catalog.PLACES.get(0), 1);
        BudgetCalculator.Inputs budget = new BudgetCalculator.Inputs(5000, 2, 1500, 500, 300, 0, 0);
        List<AiClient.Turn> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(new AiClient.Turn(i % 2 == 0, "turn " + i));

        JSONObject req = AiClient.buildRequest("Find restaurants near me under ₹500", COLABA,
                Catalog.PLACES, Catalog.PLACES.get(1), plan, budget, history);

        JSONObject loc = req.getJSONObject("location");
        assertEquals(18.907, loc.getDouble("lat"), 1e-9);
        assertEquals(72.815, loc.getDouble("lng"), 1e-9);
        assertEquals("Colaba", loc.getString("label"));

        JSONArray places = req.getJSONArray("places");
        assertEquals(Math.min(AiClient.MAX_CONTEXT_PLACES, Catalog.PLACES.size()), places.length());
        JSONObject first = places.getJSONObject(0);
        assertEquals("curated", first.getString("source"));
        assertEquals(0, first.getLong("distance_m") % 100); // rounded so it can't pinpoint the user
        assertFalse("curated places carry no invented rating", first.has("rating"));

        assertEquals(1, req.getJSONArray("plan").length());
        assertEquals(Catalog.PLACES.get(1).id, req.getJSONObject("selected_place").getString("id"));
        assertEquals(AiClient.MAX_HISTORY, req.getJSONArray("history").length());
    }

    @Test
    public void budgetSummaryIsSent() throws Exception {
        BudgetCalculator.Inputs budget = new BudgetCalculator.Inputs(5000, 2, 1500, 500, 300, 0, 0);
        JSONObject req = AiClient.buildRequest("hi", COLABA, Collections.emptyList(), null, null,
                budget, Collections.emptyList());
        JSONObject b = req.getJSONObject("budget");
        assertEquals(5000, b.getLong("total"));
        assertEquals(2300, b.getLong("planned"));
        assertEquals(2700, b.getLong("remaining"));
        assertEquals(1500, b.getLong("food"));
    }

    @Test
    public void historyIsCappedAndDefaultLocationIsNotSent() throws Exception {
        List<AiClient.Turn> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) history.add(new AiClient.Turn(true, "q" + i));
        JSONObject req = AiClient.buildRequest("hi", LocationRepository.DEFAULT, Collections.emptyList(),
                null, null, null, history);
        assertFalse(req.has("location"));
        assertFalse(req.has("budget"));
        JSONArray turns = req.getJSONArray("history");
        assertEquals(AiClient.MAX_HISTORY, turns.length());
        assertEquals("q9", turns.getJSONObject(turns.length() - 1).getString("text"));
    }

    @Test
    public void offlineAssistantPicksMatchingLoadedPlacesOnly() {
        OfflineAssistant.Suggestion food = OfflineAssistant.suggest("Find restaurants near me", Catalog.PLACES,
                COLABA.lat, COLABA.lng);
        assertEquals(OfflineAssistant.Intent.FOOD, food.intent);
        assertFalse(food.places.isEmpty());
        for (Place p : food.places) assertTrue(p.food);

        OfflineAssistant.Suggestion cafes = OfflineAssistant.suggest("Suggest cafés close to my selected attraction",
                Catalog.PLACES, COLABA.lat, COLABA.lng);
        assertEquals(OfflineAssistant.Intent.CAFE, cafes.intent);
        for (Place p : cafes.places) assertTrue(p.hasTag("Cafe"));

        OfflineAssistant.Suggestion plan = OfflineAssistant.suggest("Plan a Mumbai day trip with a lunch stop",
                Catalog.PLACES, COLABA.lat, COLABA.lng);
        assertEquals(OfflineAssistant.Intent.DAY_PLAN, plan.intent);
        int foodStops = 0;
        for (Place p : plan.places) if (p.food) foodStops++;
        assertEquals(1, foodStops);

        // Curated places have no opening hours, so "open now" yields nothing rather than a guess.
        OfflineAssistant.Suggestion open = OfflineAssistant.suggest("restaurants currently open", Catalog.PLACES,
                COLABA.lat, COLABA.lng);
        assertTrue(open.wantedOpenNow);
        assertTrue(open.places.isEmpty());

        OfflineAssistant.Suggestion none = OfflineAssistant.suggest("museums", Arrays.asList(), 0, 0);
        assertTrue(none.places.isEmpty());
    }

    @Test
    public void offlineAssistantUnderstandsBudgetPlanAndTopics() {
        assertEquals(OfflineAssistant.Intent.BUDGET, OfflineAssistant.detect("How much of my budget is left?"));
        assertEquals(OfflineAssistant.Intent.MY_PLAN, OfflineAssistant.detect("What's in my plan?"));
        assertEquals(OfflineAssistant.Intent.DAY_PLAN, OfflineAssistant.detect("Plan a day near Colaba"));
        // Food questions that mention money are still food questions.
        assertEquals(OfflineAssistant.Intent.FOOD, OfflineAssistant.detect("Find restaurants under my budget"));
        assertTrue(OfflineAssistant.suggest("How much budget is left?", Catalog.PLACES, COLABA.lat, COLABA.lng)
                .places.isEmpty());

        OfflineAssistant.Suggestion beaches = OfflineAssistant.suggest("Any beaches nearby?", Catalog.PLACES,
                COLABA.lat, COLABA.lng);
        assertEquals(OfflineAssistant.Intent.SIGHTSEEING, beaches.intent);
        assertFalse(beaches.places.isEmpty());
        for (Place p : beaches.places) assertTrue(p.name, p.hasTag("Beach"));

        OfflineAssistant.Suggestion history = OfflineAssistant.suggest("historic places to visit", Catalog.PLACES,
                COLABA.lat, COLABA.lng);
        for (Place p : history.places) assertTrue(p.name, p.hasTag("Historic"));
    }
}
