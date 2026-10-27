package com.example.touristguide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the backend's grounded travel assistant (POST /chat).
 *
 * <p>The Gemini key lives only on the server. The request carries just what the assistant needs:
 * the selected area (coordinates rounded to ~100 m), the places currently loaded, the trip and the
 * user's own budget numbers. No account details or device identifiers are sent.</p>
 */
public final class AiClient {

    public static final int MAX_CONTEXT_PLACES = 25;
    public static final int MAX_HISTORY = 6;

    public interface Listener {
        void onReply(Reply reply);

        void onError(BackendClient.ApiError error);
    }

    /** A previous chat turn (sent so follow-up questions make sense). */
    public static final class Turn {
        public final boolean user;
        public final String text;

        public Turn(boolean user, String text) {
            this.user = user;
            this.text = text;
        }
    }

    public static final class Reply {
        public final String text;
        public final boolean needsMoreInfo;
        /** Places the assistant recommended; all come from the context the app sent or a live search. */
        public final List<Place> places;

        Reply(String text, boolean needsMoreInfo, List<Place> places) {
            this.text = text;
            this.needsMoreInfo = needsMoreInfo;
            this.places = places;
        }
    }

    private AiClient() {
    }

    public static void chat(JSONObject request, Listener listener) {
        BackendClient.chat(request, new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                Reply reply = parseReply(value);
                PlaceRepository.remember(reply.places);
                listener.onReply(reply);
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                listener.onError(error);
            }
        });
    }

    /** Builds the /chat request body. Pure (no Android APIs) so it is unit tested. */
    public static JSONObject buildRequest(String message, LocationRepository.Selected location,
                                          List<Place> places, Place selectedPlace, Itinerary plan,
                                          BudgetCalculator.Inputs budget, List<Turn> history) {
        JSONObject o = new JSONObject();
        try {
            o.put("message", message.length() > 1500 ? message.substring(0, 1500) : message);
            o.put("city", "Mumbai");
            if (location != null && location.kind != LocationRepository.Kind.DEFAULT) {
                JSONObject loc = new JSONObject();
                loc.put("lat", round3(location.lat));
                loc.put("lng", round3(location.lng));
                loc.put("label", location.label);
                o.put("location", loc);
            }
            double lat = location == null ? Double.NaN : location.lat;
            double lng = location == null ? Double.NaN : location.lng;
            if (selectedPlace != null) o.put("selected_place", contextPlace(selectedPlace, lat, lng));

            JSONArray arr = new JSONArray();
            for (Place p : places) {
                if (arr.length() >= MAX_CONTEXT_PLACES) break;
                arr.put(contextPlace(p, lat, lng));
            }
            o.put("places", arr);

            JSONArray stops = new JSONArray();
            if (plan != null) {
                for (Itinerary.Stop s : plan.stops()) {
                    if (stops.length() >= 40) break;
                    stops.put(new JSONObject().put("id", s.id()).put("name", s.place.name).put("day", s.day));
                }
            }
            o.put("plan", stops);

            if (budget != null) {
                BudgetCalculator.Summary sum = BudgetCalculator.calculate(budget);
                o.put("budget", new JSONObject()
                        .put("total", budget.total)
                        .put("days", budget.days)
                        .put("planned", sum.planned)
                        .put("remaining", sum.remaining)
                        .put("food", budget.food));
            }

            JSONArray turns = new JSONArray();
            int start = Math.max(0, history.size() - MAX_HISTORY);
            for (int i = start; i < history.size(); i++) {
                Turn t = history.get(i);
                String text = t.text.length() > 2000 ? t.text.substring(0, 2000) : t.text;
                if (!text.trim().isEmpty()) {
                    turns.put(new JSONObject().put("role", t.user ? "user" : "assistant").put("text", text));
                }
            }
            o.put("history", turns);
        } catch (JSONException ignored) {
            // Only plain values are added.
        }
        return o;
    }

    private static JSONObject contextPlace(Place p, double lat, double lng) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id);
        o.put("name", p.name);
        if (!p.category.isEmpty()) o.put("category_label", p.category);
        if (!p.area.isEmpty()) o.put("address", p.area);
        o.put("lat", round3(p.lat));
        o.put("lng", round3(p.lng));
        if (p.rating != null) o.put("rating", p.rating);
        if (p.ratingCount != null) o.put("rating_count", p.ratingCount);
        if (p.priceLevel != null) o.put("price_level", p.priceLevel);
        if (p.priceStart != null || p.priceEnd != null) {
            JSONObject range = new JSONObject();
            if (p.priceStart != null) range.put("start", p.priceStart);
            if (p.priceEnd != null) range.put("end", p.priceEnd);
            if (p.priceCurrency != null) range.put("currency", p.priceCurrency);
            o.put("price_range", range);
        }
        if (p.openNow != null) o.put("open_now", p.openNow);
        // Rounded to 100 m: exact distances to many places would reveal the precise position.
        if (!Double.isNaN(lat)) o.put("distance_m", Math.round(GeoUtils.distanceMeters(lat, lng, p.lat, p.lng) / 100d) * 100);
        o.put("is_food", p.food);
        o.put("source", p.isLive() ? "live" : "curated");
        return o;
    }

    static Reply parseReply(JSONObject o) {
        List<Place> places = new ArrayList<>();
        JSONArray arr = o.optJSONArray("places");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            Place p = Place.fromBackendJson(item);
            // Curated/known places echo back by id: prefer our richer local copy.
            if (p != null) {
                Place known = PlaceRepository.get(p.id);
                places.add(known != null ? known : p);
            }
        }
        return new Reply(o.optString("reply", "").trim(), o.optBoolean("needs_more_info", false), places);
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
