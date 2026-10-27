package com.example.touristguide;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Local persistence (SharedPreferences). Everything here stays on the device; cloud backup is a
 * separate, explicit action in {@link CloudSync}.
 *
 * <p>Favourites and the itinerary are stored by stable place id together with a small snapshot,
 * so they keep working offline and for live places that are no longer in the current results.</p>
 */
public final class Store {
    private static final String PREFS = "tourist_guide";

    // Keys used by version 2.x of the app (migrated on first launch of this version).
    private static final String LEGACY_FAVORITES = "favorites";
    private static final String LEGACY_PLAN = "plan";
    private static final String LEGACY_SEP = "\u001C";

    private static final String FAVORITES = "favorites_v3";
    private static final String ITINERARY = "itinerary_v3";
    private static final String BUDGET = "budget_v1";
    private static final String QUIZ = "quiz_v2";
    private static final String LOCATION = "location_v1";
    private static final String QUERY = "explore_query_v1";
    private static final String PLACES_CACHE = "places_cache_v1";
    private static final String BACKEND_URL = "backend_url";
    private static final String THEME = "theme_mode";
    private static final String LIVE_ENABLED = "live_places";
    private static final String GOOGLE_MAPS = "use_google_maps";
    private static final String LOCATION_ASKED = "location_rationale_seen";

    /** Live results are only reused for 24 hours, and always labelled as cached. */
    public static final long CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int CACHE_ENTRIES = 6;

    private static SharedPreferences p;

    private Store() {
    }

    public static void init(Context context) {
        // Android returns the same instance for the same app, so repeated calls are cheap no-ops.
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs == p) return;
        p = prefs;
        migrateLegacy();
    }

    // ------------------------------------------------------------------ favourites

    public static boolean isSaved(String placeId) {
        return favourites().has(placeId);
    }

    /** Toggles a favourite. Returns true if the place is now saved. */
    public static boolean toggleSaved(Place place) {
        JSONObject fav = favourites();
        boolean added;
        if (fav.has(place.id)) {
            fav.remove(place.id);
            added = false;
        } else {
            put(fav, place.id, place.toJson());
            added = true;
        }
        p.edit().putString(FAVORITES, fav.toString()).apply();
        return added;
    }

    public static int savedCount() {
        return favourites().length();
    }

    /** Saved places, oldest first. */
    public static List<Place> savedPlaces() {
        JSONObject fav = favourites();
        List<Place> out = new ArrayList<>();
        Iterator<String> keys = fav.keys();
        while (keys.hasNext()) {
            Place place = Place.fromJson(fav.optJSONObject(keys.next()));
            if (place != null) out.add(place);
        }
        return out;
    }

    public static Place savedSnapshot(String placeId) {
        return Place.fromJson(favourites().optJSONObject(placeId));
    }

    private static JSONObject favourites() {
        return parseObject(p.getString(FAVORITES, null));
    }

    // ------------------------------------------------------------------ itinerary, budget, quiz

    public static JSONObject itineraryJson() {
        return parseObject(p.getString(ITINERARY, null));
    }

    public static void saveItinerary(JSONObject json) {
        p.edit().putString(ITINERARY, json.toString()).apply();
    }

    public static JSONObject budgetJson() {
        return parseObject(p.getString(BUDGET, null));
    }

    public static void saveBudget(JSONObject json) {
        p.edit().putString(BUDGET, json.toString()).apply();
    }

    /** Best score for a quiz mode as "score/total", or null if never played. */
    public static int[] quizBest(String mode) {
        JSONObject best = quizJson().optJSONObject("best");
        JSONArray entry = best == null ? null : best.optJSONArray(mode);
        return entry == null || entry.length() != 2 ? null : new int[]{entry.optInt(0), entry.optInt(1)};
    }

    /** Records a finished quiz. Returns true if it is a new best for that mode. */
    public static boolean recordQuiz(String mode, int score, int total) {
        JSONObject quiz = quizJson();
        JSONObject best = quiz.optJSONObject("best");
        if (best == null) best = new JSONObject();
        int[] previous = quizBest(mode);
        boolean isBest = previous == null || score * previous[1] > previous[0] * total;
        if (isBest) put(best, mode, new JSONArray(Arrays.asList(score, total)));
        put(quiz, "best", best);
        put(quiz, "played", quiz.optInt("played", 0) + 1);
        put(quiz, "correct", quiz.optInt("correct", 0) + score);
        put(quiz, "answered", quiz.optInt("answered", 0) + total);
        p.edit().putString(QUIZ, quiz.toString()).apply();
        return isBest;
    }

    public static int quizzesPlayed() {
        return quizJson().optInt("played", 0);
    }

    /** Overall percentage of correct answers across all games, or -1 if none. */
    public static int quizAccuracy() {
        JSONObject quiz = quizJson();
        int answered = quiz.optInt("answered", 0);
        return answered == 0 ? -1 : quiz.optInt("correct", 0) * 100 / answered;
    }

    public static void resetQuiz() {
        p.edit().remove(QUIZ).apply();
    }

    private static JSONObject quizJson() {
        return parseObject(p.getString(QUIZ, null));
    }

    // ------------------------------------------------------------------ location & explore preferences

    public static JSONObject locationJson() {
        return parseObject(p.getString(LOCATION, null));
    }

    public static void saveLocation(JSONObject json) {
        p.edit().putString(LOCATION, json.toString()).apply();
    }

    public static boolean locationRationaleSeen() {
        return p.getBoolean(LOCATION_ASKED, false);
    }

    public static void setLocationRationaleSeen() {
        p.edit().putBoolean(LOCATION_ASKED, true).apply();
    }

    public static PlaceQuery savedQuery() {
        JSONObject o = parseObject(p.getString(QUERY, null));
        PlaceQuery.Sort sort;
        try {
            sort = PlaceQuery.Sort.valueOf(o.optString("sort", "RELEVANCE"));
        } catch (IllegalArgumentException e) {
            sort = PlaceQuery.Sort.RELEVANCE;
        }
        return new PlaceQuery("", PlaceQuery.Category.fromKey(o.optString("category", "all")),
                o.optInt("radius", PlaceQuery.DEFAULT_RADIUS_M), sort, o.optBoolean("openNow", false));
    }

    public static void saveQuery(PlaceQuery q) {
        JSONObject o = new JSONObject();
        put(o, "category", q.category.key);
        put(o, "radius", q.radiusMeters);
        put(o, "sort", q.sort.name());
        put(o, "openNow", q.openNow);
        p.edit().putString(QUERY, o.toString()).apply();
    }

    // ------------------------------------------------------------------ live results cache

    /** Stores the latest live results for a query (photo names are never stored). */
    public static void cachePlaces(String key, List<Place> places) {
        JSONObject cache = parseObject(p.getString(PLACES_CACHE, null));
        JSONArray arr = new JSONArray();
        for (Place place : places) arr.put(place.toJson());
        JSONObject entry = new JSONObject();
        put(entry, "savedAt", System.currentTimeMillis());
        put(entry, "places", arr);
        cache.remove(key);
        put(cache, key, entry);
        // Keep only the most recent entries.
        while (cache.length() > CACHE_ENTRIES) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            Iterator<String> keys = cache.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long t = cache.optJSONObject(k) == null ? 0 : cache.optJSONObject(k).optLong("savedAt");
                if (t < oldestTime) {
                    oldestTime = t;
                    oldest = k;
                }
            }
            cache.remove(oldest);
        }
        p.edit().putString(PLACES_CACHE, cache.toString()).apply();
    }

    /** Cached results for a query if younger than 24 hours, else null. */
    public static CachedPlaces cachedPlaces(String key) {
        JSONObject entry = parseObject(p.getString(PLACES_CACHE, null)).optJSONObject(key);
        if (entry == null) return null;
        long savedAt = entry.optLong("savedAt");
        if (System.currentTimeMillis() - savedAt > CACHE_MAX_AGE_MS) return null;
        JSONArray arr = entry.optJSONArray("places");
        List<Place> places = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Place place = Place.fromJson(arr.optJSONObject(i));
            if (place != null) places.add(place);
        }
        return new CachedPlaces(places, savedAt);
    }

    /** Clears cached live results only. Saved places, trips, budget and quiz progress are kept. */
    public static void clearPlacesCache() {
        p.edit().remove(PLACES_CACHE).apply();
    }

    public static final class CachedPlaces {
        public final List<Place> places;
        public final long savedAt;

        CachedPlaces(List<Place> places, long savedAt) {
            this.places = places;
            this.savedAt = savedAt;
        }
    }

    // ------------------------------------------------------------------ settings

    public static String backendUrl() {
        String url = p.getString(BACKEND_URL, "");
        return url == null || url.trim().isEmpty() ? BuildConfig.BACKEND_URL : url.trim();
    }

    /** Saves a custom backend URL; an empty value restores the build default. */
    public static void setBackendUrl(String url) {
        p.edit().putString(BACKEND_URL, url == null ? "" : url.trim()).apply();
    }

    public static int themeMode() {
        return p.getInt(THEME, -1); // AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    public static void setThemeMode(int mode) {
        p.edit().putInt(THEME, mode).apply();
    }

    public static boolean liveEnabled() {
        return p.getBoolean(LIVE_ENABLED, true);
    }

    public static void setLiveEnabled(boolean enabled) {
        p.edit().putBoolean(LIVE_ENABLED, enabled).apply();
    }

    /** Prefer Google Maps when a key is built in; false = always OpenStreetMap. */
    public static boolean useGoogleMaps() {
        return p.getBoolean(GOOGLE_MAPS, true);
    }

    public static void setUseGoogleMaps(boolean use) {
        p.edit().putBoolean(GOOGLE_MAPS, use).apply();
    }

    // ------------------------------------------------------------------ cloud backup snapshot

    /** User data for an explicit cloud backup (no settings, no caches, no location). */
    public static JSONObject exportUserData() {
        JSONObject o = new JSONObject();
        put(o, "favourites", favourites().toString());
        put(o, "itinerary", itineraryJson().toString());
        put(o, "budget", budgetJson().toString());
        put(o, "quiz", quizJson().toString());
        return o;
    }

    /** Replaces local user data with a backup. Unknown or corrupt sections are ignored. */
    public static void importUserData(String favourites, String itinerary, String budget, String quiz) {
        SharedPreferences.Editor e = p.edit();
        if (isJsonObject(favourites)) e.putString(FAVORITES, favourites);
        if (isJsonObject(itinerary)) e.putString(ITINERARY, itinerary);
        if (isJsonObject(budget)) e.putString(BUDGET, budget);
        if (isJsonObject(quiz)) e.putString(QUIZ, quiz);
        e.apply();
    }

    // ------------------------------------------------------------------ helpers

    /** Moves favourites and plan saved by older versions (by name) to the id-based format. */
    private static void migrateLegacy() {
        SharedPreferences.Editor e = p.edit();
        boolean changed = false;
        if (p.contains(LEGACY_FAVORITES) && !p.contains(FAVORITES)) {
            JSONObject fav = new JSONObject();
            Set<String> names = p.getStringSet(LEGACY_FAVORITES, new HashSet<>());
            for (String name : names) {
                Place place = Catalog.byName(name);
                if (place != null) put(fav, place.id, place.toJson());
            }
            e.putString(FAVORITES, fav.toString()).remove(LEGACY_FAVORITES);
            changed = true;
        }
        if (p.contains(LEGACY_PLAN) && !p.contains(ITINERARY)) {
            String raw = p.getString(LEGACY_PLAN, "");
            Itinerary it = new Itinerary();
            if (raw != null && !raw.isEmpty()) {
                for (String name : raw.split(LEGACY_SEP)) {
                    Place place = Catalog.byName(name);
                    if (place != null) it.add(place, 1);
                }
            }
            e.putString(ITINERARY, it.toJson().toString()).remove(LEGACY_PLAN);
            changed = true;
        }
        if (p.contains("quiz") || p.contains("quiz_answer")) {
            e.remove("quiz").remove("quiz_answer");
            changed = true;
        }
        if (changed) e.apply();
    }

    private static JSONObject parseObject(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject(); // Corrupt data must never crash the app.
        }
    }

    private static boolean isJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            new JSONObject(raw);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
            // Keys are never null and values are never NaN.
        }
    }
}
