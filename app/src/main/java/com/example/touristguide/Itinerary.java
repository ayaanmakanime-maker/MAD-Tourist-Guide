package com.example.touristguide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A trip: an ordered list of stops grouped into days. Pure Java (unit tested); persistence is
 * handled by {@link PlanRepository}. Stops are identified by stable place ids, so the same place
 * can never be added twice.
 */
public final class Itinerary {
    public static final int MAX_DAYS = 14;
    public static final int MAX_STOPS = 60;
    public static final int MAX_NOTE_LENGTH = 300;

    /** One stop. Holds a snapshot of the place so the plan still shows offline or if a listing disappears. */
    public static final class Stop {
        public final Place place;
        public int day;
        public String note;
        /** Estimated cost entered by the user, in rupees; null = not entered (unknown). */
        public Long estimatedCost;

        Stop(Place place, int day) {
            this.place = place;
            this.day = day;
            this.note = "";
        }

        public String id() {
            return place.id;
        }
    }

    public enum AddResult { ADDED, DUPLICATE, FULL }

    private final List<Stop> stops = new ArrayList<>();
    private int days = 1;
    private String tripName = "";
    /** ISO date (yyyy-MM-dd) of day 1, or empty when not set. */
    private String startDate = "";

    public int days() { return days; }
    public String tripName() { return tripName; }
    public String startDate() { return startDate; }
    public int size() { return stops.size(); }
    public boolean isEmpty() { return stops.isEmpty(); }

    public void setTripName(String name) {
        tripName = name == null ? "" : name.trim();
        if (tripName.length() > 60) tripName = tripName.substring(0, 60);
    }

    public void setStartDate(String isoDate) {
        startDate = isoDate == null ? "" : isoDate.trim();
    }

    /** Changes the number of days; stops on removed days move to the new last day. */
    public void setDays(int n) {
        days = Math.max(1, Math.min(MAX_DAYS, n));
        for (Stop s : stops) if (s.day > days) s.day = days;
    }

    public boolean contains(String placeId) {
        return find(placeId) != null;
    }

    public Stop find(String placeId) {
        for (Stop s : stops) if (s.id().equals(placeId)) return s;
        return null;
    }

    /** All stops in display order (day 1 first, then insertion order within a day). */
    public List<Stop> stops() {
        List<Stop> out = new ArrayList<>();
        for (int d = 1; d <= days; d++) out.addAll(stopsForDay(d));
        return Collections.unmodifiableList(out);
    }

    public List<Stop> stopsForDay(int day) {
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops) if (s.day == day) out.add(s);
        return out;
    }

    public AddResult add(Place place, int day) {
        if (place == null) throw new IllegalArgumentException("place");
        if (contains(place.id)) return AddResult.DUPLICATE;
        if (stops.size() >= MAX_STOPS) return AddResult.FULL;
        stops.add(new Stop(place, clampDay(day)));
        return AddResult.ADDED;
    }

    public boolean remove(String placeId) {
        Stop s = find(placeId);
        return s != null && stops.remove(s);
    }

    /** Re-inserts a removed stop at its old position (used by "Undo"). */
    public void restore(Stop stop, int index) {
        if (stop == null || contains(stop.id())) return;
        stop.day = clampDay(stop.day);
        stops.add(Math.max(0, Math.min(index, stops.size())), stop);
    }

    public int indexOf(String placeId) {
        for (int i = 0; i < stops.size(); i++) if (stops.get(i).id().equals(placeId)) return i;
        return -1;
    }

    public void clear() {
        stops.clear();
    }

    /** Moves a stop one place earlier within its day. Returns false if it is already first. */
    public boolean moveUp(String placeId) {
        return swapWithinDay(placeId, -1);
    }

    /** Moves a stop one place later within its day. Returns false if it is already last. */
    public boolean moveDown(String placeId) {
        return swapWithinDay(placeId, +1);
    }

    private boolean swapWithinDay(String placeId, int direction) {
        int i = indexOf(placeId);
        if (i < 0) return false;
        int day = stops.get(i).day;
        for (int j = i + direction; j >= 0 && j < stops.size(); j += direction) {
            if (stops.get(j).day == day) {
                Collections.swap(stops, i, j);
                return true;
            }
        }
        return false;
    }

    /** Moves a stop to another day (it becomes the last stop of that day). */
    public boolean moveToDay(String placeId, int day) {
        Stop s = find(placeId);
        if (s == null) return false;
        stops.remove(s);
        s.day = clampDay(day);
        stops.add(s);
        return true;
    }

    public void setNote(String placeId, String note) {
        Stop s = find(placeId);
        if (s == null) return;
        String n = note == null ? "" : note.trim();
        s.note = n.length() > MAX_NOTE_LENGTH ? n.substring(0, MAX_NOTE_LENGTH) : n;
    }

    /** Sets (or clears with null) the user's own cost estimate, limited to the budget maximum. */
    public void setEstimatedCost(String placeId, Long rupees) {
        Stop s = find(placeId);
        if (s == null) return;
        s.estimatedCost = rupees == null ? null : Math.max(0, Math.min(BudgetCalculator.MAX_AMOUNT, rupees));
    }

    /** Sum of the costs the user entered. Stops without an estimate are not counted. */
    public long enteredCostTotal() {
        long sum = 0;
        for (Stop s : stops) if (s.estimatedCost != null) sum += s.estimatedCost;
        return sum;
    }

    public int stopsWithCost() {
        int n = 0;
        for (Stop s : stops) if (s.estimatedCost != null) n++;
        return n;
    }

    /**
     * Reorders each day by a simple nearest-neighbour walk from that day's first stop, using
     * straight-line distance. A convenience heuristic only: not an optimal or traffic-aware route.
     */
    public void orderEachDayByProximity() {
        List<Stop> reordered = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            List<Stop> remaining = stopsForDay(d);
            if (remaining.isEmpty()) continue;
            Stop current = remaining.remove(0);
            reordered.add(current);
            while (!remaining.isEmpty()) {
                Stop nearest = remaining.get(0);
                double best = Double.MAX_VALUE;
                for (Stop candidate : remaining) {
                    double dist = GeoUtils.distanceMeters(current.place.lat, current.place.lng,
                            candidate.place.lat, candidate.place.lng);
                    if (dist < best) {
                        best = dist;
                        nearest = candidate;
                    }
                }
                remaining.remove(nearest);
                reordered.add(nearest);
                current = nearest;
            }
        }
        stops.clear();
        stops.addAll(reordered);
    }

    /** Replaces the trip with the given places, all on day 1, skipping duplicates. */
    public void replaceWith(List<Place> places) {
        stops.clear();
        for (Place p : places) if (p != null) add(p, 1);
    }

    private int clampDay(int day) {
        return Math.max(1, Math.min(days, day));
    }

    // ------------------------------------------------------------------ JSON

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 2);
            o.put("days", days);
            o.put("tripName", tripName);
            o.put("startDate", startDate);
            JSONArray arr = new JSONArray();
            for (Stop s : stops) {
                JSONObject item = new JSONObject();
                item.put("place", s.place.toJson());
                item.put("day", s.day);
                item.put("note", s.note);
                if (s.estimatedCost != null) item.put("cost", s.estimatedCost);
                arr.put(item);
            }
            o.put("stops", arr);
        } catch (JSONException ignored) {
            // Only plain values are stored.
        }
        return o;
    }

    /** Reads a saved trip; corrupt entries are skipped instead of crashing. */
    public static Itinerary fromJson(JSONObject o) {
        Itinerary it = new Itinerary();
        if (o == null) return it;
        it.setDays(o.optInt("days", 1));
        it.setTripName(o.optString("tripName", ""));
        it.setStartDate(o.optString("startDate", ""));
        JSONArray arr = o.optJSONArray("stops");
        if (arr == null) return it;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            Place p = Place.fromJson(item.optJSONObject("place"));
            if (p == null || it.add(p, item.optInt("day", 1)) != AddResult.ADDED) continue;
            it.setNote(p.id, item.optString("note", ""));
            if (item.has("cost") && !item.isNull("cost")) it.setEstimatedCost(p.id, item.optLong("cost"));
        }
        return it;
    }
}
