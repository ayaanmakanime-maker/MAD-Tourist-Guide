package com.example.touristguide;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Loads/saves the {@link Itinerary} and notifies screens when it changes. */
public final class PlanRepository {

    public interface Listener {
        void onPlanChanged();
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static Itinerary itinerary;

    private PlanRepository() {
    }

    /** The current trip. Callers that change it must call {@link #save()}. */
    public static Itinerary get() {
        if (itinerary == null) itinerary = Itinerary.fromJson(Store.itineraryJson());
        return itinerary;
    }

    public static Itinerary.AddResult add(Place place, int day) {
        Itinerary.AddResult r = get().add(place, day);
        if (r == Itinerary.AddResult.ADDED) save();
        return r;
    }

    public static boolean contains(String placeId) {
        return get().contains(placeId);
    }

    public static void save() {
        Store.saveItinerary(get().toJson());
        for (Listener l : LISTENERS) l.onPlanChanged();
    }

    /** Re-reads the trip from storage (after a cloud restore). */
    public static void reload() {
        itinerary = null;
        for (Listener l : LISTENERS) l.onPlanChanged();
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}
