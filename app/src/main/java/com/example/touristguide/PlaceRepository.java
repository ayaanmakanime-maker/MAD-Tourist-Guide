package com.example.touristguide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of place data for Explore, Map, Plan and Chat.
 *
 * <p>It loads live results from Google Places (via the backend). When that fails it shows the
 * last cached live results (under 24 h old, labelled "cached") or, failing that, the curated
 * guide (labelled "curated"). It never presents old or static data as live.</p>
 */
public final class PlaceRepository {

    public enum Status {
        /** First load in progress (nothing to show yet). */
        LOADING,
        /** Fresh results from Google Places. */
        LIVE,
        /** Live results saved earlier, shown because the live request failed. */
        CACHED,
        /** Curated guide shown because live data is unavailable. */
        CURATED_FALLBACK,
        /** Curated guide because the user turned live places off. */
        CURATED_ONLY
    }

    public static final class State {
        public final Status status;
        /** Filtered, sorted places ready to display. */
        public final List<Place> places;
        public final PlaceQuery query;
        public final double lat;
        public final double lng;
        /** Why live data is not shown (null for LIVE / CURATED_ONLY). */
        public final BackendClient.ApiError error;
        /** When the shown data was fetched from the provider (0 for curated). */
        public final long fetchedAt;
        public final boolean refreshing;
        public final boolean canLoadMore;
        public final boolean loadingMore;

        State(Status status, List<Place> places, PlaceQuery query, double lat, double lng,
              BackendClient.ApiError error, long fetchedAt, boolean refreshing, boolean canLoadMore,
              boolean loadingMore) {
            this.status = status;
            this.places = Collections.unmodifiableList(places);
            this.query = query;
            this.lat = lat;
            this.lng = lng;
            this.error = error;
            this.fetchedAt = fetchedAt;
            this.refreshing = refreshing;
            this.canLoadMore = canLoadMore;
            this.loadingMore = loadingMore;
        }
    }

    public interface Listener {
        void onPlacesChanged(State state);
    }

    /** Re-use fresh live results for identical requests within this window. */
    private static final long REUSE_MS = 2 * 60 * 1000;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** Every place seen in this session, by id, so any screen can resolve an id. */
    private static final Map<String, Place> KNOWN = new HashMap<>();

    private static State state;
    /** Unfiltered provider results for the current request (sort/open-now are applied on top). */
    private static List<Place> raw = new ArrayList<>();
    private static String rawKey;
    private static String nextPageToken;
    private static int generation;
    private static String lastViewedId;
    private static String mapFocusId;

    private PlaceRepository() {
    }

    // ------------------------------------------------------------------ listeners

    public static void addListener(Listener l) {
        LISTENERS.add(l);
        if (state != null) l.onPlacesChanged(state);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static State state() {
        return state;
    }

    private static void publish(State s) {
        state = s;
        for (Listener l : LISTENERS) l.onPlacesChanged(s);
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads places for the query around the selected location.
     *
     * @param force true for pull-to-refresh / retry (skips the short reuse window)
     */
    public static void load(PlaceQuery q, boolean force) {
        LocationRepository.Selected loc = LocationRepository.selected();
        String key = q.cacheKey(loc.lat, loc.lng);
        int gen = ++generation;

        if (!Store.liveEnabled()) {
            publish(curatedState(Status.CURATED_ONLY, q, loc, null));
            return;
        }
        // Only sort/open-now changed, or the same request was made moments ago: no network call.
        if (!force && key.equals(rawKey) && state != null && state.status == Status.LIVE
                && System.currentTimeMillis() - state.fetchedAt < REUSE_MS) {
            publish(new State(Status.LIVE, PlaceFilter.finish(raw, q, loc.lat, loc.lng), q, loc.lat, loc.lng,
                    null, state.fetchedAt, false, nextPageToken != null, false));
            return;
        }

        if (state == null || state.places.isEmpty()) {
            publish(new State(Status.LOADING, new ArrayList<>(), q, loc.lat, loc.lng, null, 0, true, false, false));
        } else {
            publish(new State(state.status, state.places, q, loc.lat, loc.lng, state.error, state.fetchedAt,
                    true, false, false));
        }

        BackendClient.Callback<JSONObject> cb = new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (gen != generation) return; // a newer request superseded this one
                List<Place> places = parse(value);
                raw = places;
                rawKey = key;
                nextPageToken = q.isTextSearch() ? emptyToNull(value.optString("next_page_token", "")) : null;
                Store.cachePlaces(key, places);
                publish(new State(Status.LIVE, PlaceFilter.finish(places, q, loc.lat, loc.lng), q, loc.lat, loc.lng,
                        null, System.currentTimeMillis(), false, nextPageToken != null, false));
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                if (gen != generation) return;
                raw = new ArrayList<>();
                rawKey = null;
                nextPageToken = null;
                Store.CachedPlaces cached = Store.cachedPlaces(key);
                if (cached != null && !cached.places.isEmpty()) {
                    remember(cached.places);
                    publish(new State(Status.CACHED, PlaceFilter.finish(cached.places, q, loc.lat, loc.lng), q,
                            loc.lat, loc.lng, error, cached.savedAt, false, false, false));
                } else {
                    publish(curatedState(Status.CURATED_FALLBACK, q, loc, error));
                }
            }
        };
        if (q.isTextSearch()) {
            BackendClient.search(q.text, loc.lat, loc.lng, q.radiusMeters, q.category.key, q.openNow, null, cb);
        } else {
            BackendClient.nearby(loc.lat, loc.lng, q.radiusMeters, q.category.key,
                    q.sort == PlaceQuery.Sort.DISTANCE ? "distance" : "popularity", cb);
        }
    }

    /** Loads the next page of a live text search. */
    public static void loadMore() {
        State s = state;
        if (s == null || s.status != Status.LIVE || nextPageToken == null || s.loadingMore) return;
        PlaceQuery q = s.query;
        int gen = generation;
        String token = nextPageToken;
        publish(new State(s.status, s.places, q, s.lat, s.lng, null, s.fetchedAt, false, true, true));
        BackendClient.search(q.text, s.lat, s.lng, q.radiusMeters, q.category.key, q.openNow, token,
                new BackendClient.Callback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject value) {
                        if (gen != generation) return;
                        raw = PlaceFilter.merge(raw, parse(value));
                        nextPageToken = emptyToNull(value.optString("next_page_token", ""));
                        publish(new State(Status.LIVE, PlaceFilter.finish(raw, q, s.lat, s.lng), q, s.lat, s.lng,
                                null, s.fetchedAt, false, nextPageToken != null, false));
                    }

                    @Override
                    public void onError(BackendClient.ApiError error) {
                        if (gen != generation) return;
                        publish(new State(Status.LIVE, s.places, q, s.lat, s.lng, error, s.fetchedAt,
                                false, true, false));
                    }
                });
    }

    private static State curatedState(Status status, PlaceQuery q, LocationRepository.Selected loc,
                                      BackendClient.ApiError error) {
        return new State(status, PlaceFilter.curated(Catalog.PLACES, q, loc.lat, loc.lng), q, loc.lat, loc.lng,
                error, 0, false, false, false);
    }

    private static List<Place> parse(JSONObject body) {
        List<Place> out = new ArrayList<>();
        JSONArray arr = body.optJSONArray("places");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            Place p = o == null ? null : Place.fromBackendJson(o);
            if (p != null) out.add(p);
        }
        remember(out);
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    // ------------------------------------------------------------------ lookup

    /** Makes places resolvable by id from any screen (e.g. places recommended in Chat). */
    public static void remember(List<Place> places) {
        for (Place p : places) remember(p);
    }

    public static void remember(Place p) {
        Place existing = KNOWN.get(p.id);
        // Keep the richer copy (one with a photo) if we already have it.
        if (existing == null || existing.photoName == null || p.photoName != null) KNOWN.put(p.id, p);
    }

    /** Resolves a place id from session results, the curated guide, the plan or favourites. */
    public static Place get(String id) {
        if (id == null) return null;
        Place p = KNOWN.get(id);
        if (p != null) return p;
        p = Catalog.byId(id);
        if (p != null) return p;
        Itinerary.Stop stop = PlanRepository.get().find(id);
        if (stop != null) return stop.place;
        return Store.savedSnapshot(id);
    }

    /**
     * Fetches fresh details for a live place (photo, hours, rating). Curated places are returned
     * as they are. On failure the snapshot is still usable, so callers only need the error to
     * show a note (e.g. "no longer listed").
     */
    public static void refreshDetails(Place place, BackendClient.Callback<Place> cb) {
        String googleId = place.googleId();
        if (googleId == null || !Store.liveEnabled()) {
            cb.onSuccess(place);
            return;
        }
        BackendClient.details(googleId, new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                JSONObject o = value.optJSONObject("place");
                Place fresh = o == null ? null : Place.fromBackendJson(o);
                if (fresh == null) {
                    cb.onError(new BackendClient.ApiError(BackendClient.ErrorKind.SERVER, 0, null));
                    return;
                }
                remember(fresh);
                cb.onSuccess(fresh);
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                cb.onError(error);
            }
        });
    }

    /** Places currently loaded (for Chat context and the Map). */
    public static List<Place> current() {
        return state == null ? new ArrayList<>() : new ArrayList<>(state.places);
    }

    public static void setLastViewed(String id) {
        lastViewedId = id;
    }

    public static Place lastViewed() {
        return get(lastViewedId);
    }

    /** Asks the Map tab to centre on a place the next time it is shown. */
    public static void requestMapFocus(String id) {
        mapFocusId = id;
    }

    public static String consumeMapFocus() {
        String id = mapFocusId;
        mapFocusId = null;
        return id;
    }

    /** Forgets in-memory and stored live results (saved places and trips are untouched). */
    public static void clearCache() {
        Store.clearPlacesCache();
        KNOWN.clear();
        raw = new ArrayList<>();
        rawKey = null;
        nextPageToken = null;
        generation++;
        state = null;
    }
}
