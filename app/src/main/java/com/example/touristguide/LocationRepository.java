package com.example.touristguide;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The "selected location" that Explore, Map and Chat search around. It is either the device's
 * position, an area the user picked, or Mumbai as the default.
 *
 * <p>The device position comes from a high-accuracy fix. While the app is on screen and the user
 * chose "Use my location", it keeps following them (foreground only, never in the background) and
 * moves the search centre when they have moved far enough to matter.</p>
 */
public final class LocationRepository {

    public enum Kind { DEVICE, MANUAL, DEFAULT }

    public static final class Selected {
        public final double lat;
        public final double lng;
        public final String label;
        public final Kind kind;
        /** Accuracy radius of a device fix in metres; NaN when unknown or not a device fix. */
        public final float accuracyM;

        public Selected(double lat, double lng, String label, Kind kind) {
            this(lat, lng, label, kind, Float.NaN);
        }

        public Selected(double lat, double lng, String label, Kind kind, float accuracyM) {
            this.lat = lat;
            this.lng = lng;
            this.label = label;
            this.kind = kind;
            this.accuracyM = accuracyM;
        }
    }

    /** One candidate for an area or landmark the user typed. */
    public static final class AreaMatch {
        public final Selected location;
        public final String address;
        /** True when it came from Google Places (accurate); false for the phone's own Geocoder. */
        public final boolean fromGoogle;

        AreaMatch(Selected location, String address, boolean fromGoogle) {
            this.location = location;
            this.address = address;
            this.fromGoogle = fromGoogle;
        }
    }

    public interface Callback {
        void onLocation(Selected selected);

        void onFailure(Failure failure);
    }

    public interface AreasCallback {
        void onAreas(List<AreaMatch> matches);

        void onFailure(Failure failure);
    }

    public enum Failure { NO_PERMISSION, UNAVAILABLE, NOT_FOUND, NO_GEOCODER }

    /** Handy areas for users who prefer not to share location or want to explore elsewhere. */
    public static final List<Selected> PRESETS = Arrays.asList(
            new Selected(18.9067, 72.8147, "Colaba", Kind.MANUAL),
            new Selected(18.9322, 72.8347, "Fort", Kind.MANUAL),
            new Selected(18.9440, 72.8230, "Marine Drive", Kind.MANUAL),
            new Selected(19.0178, 72.8478, "Dadar", Kind.MANUAL),
            new Selected(19.0596, 72.8295, "Bandra West", Kind.MANUAL),
            new Selected(19.1075, 72.8263, "Juhu", Kind.MANUAL),
            new Selected(19.1197, 72.8468, "Andheri", Kind.MANUAL),
            new Selected(19.1176, 72.9060, "Powai", Kind.MANUAL));

    public static final Selected DEFAULT = new Selected(18.9398, 72.8355, "Mumbai", Kind.DEFAULT);

    /** Following: move the search centre after this distance… */
    static final double FOLLOW_DISTANCE_M = 250;
    /** …but not more often than this, so a moving car doesn't trigger a search every few seconds. */
    static final long FOLLOW_MIN_INTERVAL_MS = 60_000;
    /** Fixes vaguer than this are ignored while following. */
    static final float FOLLOW_MAX_ACCURACY_M = 200;

    // Mumbai Metropolitan Region, used to keep offline Geocoder matches local.
    private static final double MMR_SOUTH = 18.85, MMR_WEST = 72.75, MMR_NORTH = 19.35, MMR_EAST = 73.15;

    private static Selected selected;

    // Following state (foreground only). Application context, never an Activity.
    @SuppressLint("StaticFieldLeak")
    private static Context appContext;
    private static boolean foreground;
    private static Runnable onMoved;
    private static LocationCallback tracking;
    private static long lastMoveAt;

    private LocationRepository() {
    }

    public static Selected selected() {
        if (selected == null) {
            JSONObject o = Store.locationJson();
            double lat = o.optDouble("lat", Double.NaN);
            double lng = o.optDouble("lng", Double.NaN);
            Kind kind;
            try {
                kind = Kind.valueOf(o.optString("kind", "DEFAULT"));
            } catch (IllegalArgumentException e) {
                kind = Kind.DEFAULT;
            }
            selected = GeoUtils.isValid(lat, lng)
                    ? new Selected(lat, lng, o.optString("label", DEFAULT.label), kind,
                    (float) o.optDouble("accuracy", Double.NaN))
                    : DEFAULT;
        }
        return selected;
    }

    public static void select(Selected s) {
        selected = s;
        JSONObject o = new JSONObject();
        try {
            o.put("lat", s.lat);
            o.put("lng", s.lng);
            o.put("label", s.label);
            o.put("kind", s.kind.name());
            if (!Float.isNaN(s.accuracyM)) o.put("accuracy", (double) s.accuracyM);
        } catch (JSONException ignored) {
            // Coordinates are validated before they get here.
        }
        Store.saveLocation(o);
        updateFollowing();
    }

    public static boolean hasPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ device position

    /**
     * Gets a fresh high-accuracy fix (GPS when available, up to 20 s) and selects it. Falls back to
     * the last known position if it is recent. With "approximate" permission Android returns a
     * coarse fix, which is still used.
     */
    public static void useDeviceLocation(Context context, String fallbackLabel, Callback cb) {
        Context app = context.getApplicationContext();
        if (!hasPermission(app)) {
            cb.onFailure(Failure.NO_PERMISSION);
            return;
        }
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(15_000)
                .setDurationMillis(20_000)
                .build();
        try {
            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(app);
            client.getCurrentLocation(request, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) resolveAndSelect(app, location, fallbackLabel, cb);
                        else useLastKnown(app, client, fallbackLabel, cb);
                    })
                    .addOnFailureListener(e -> useLastKnown(app, client, fallbackLabel, cb));
        } catch (SecurityException e) {
            cb.onFailure(Failure.NO_PERMISSION);
        }
    }

    @SuppressLint("MissingPermission") // only reached after hasPermission()
    private static void useLastKnown(Context app, FusedLocationProviderClient client, String fallbackLabel,
                                     Callback cb) {
        try {
            client.getLastLocation()
                    .addOnSuccessListener(last -> {
                        if (last != null && System.currentTimeMillis() - last.getTime() < 10 * 60_000) {
                            resolveAndSelect(app, last, fallbackLabel, cb);
                        } else {
                            cb.onFailure(Failure.UNAVAILABLE);
                        }
                    })
                    .addOnFailureListener(e -> cb.onFailure(Failure.UNAVAILABLE));
        } catch (SecurityException e) {
            cb.onFailure(Failure.NO_PERMISSION);
        }
    }

    /** Names the fix (reverse geocoding off the main thread) and selects it. */
    private static void resolveAndSelect(Context app, Location location, String fallbackLabel, Callback cb) {
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        BackendClient.runInBackground(() -> {
            String label = reverseGeocode(app, lat, lng);
            Selected s = new Selected(lat, lng, label == null ? fallbackLabel : label, Kind.DEVICE, accuracy);
            BackendClient.postToMain(() -> {
                lastMoveAt = System.currentTimeMillis();
                select(s);
                if (cb != null) cb.onLocation(s);
            });
        });
    }

    // ------------------------------------------------------------------ following (foreground only)

    /**
     * Called by MainActivity from onStart/onStop. While visible, and only if the user chose their
     * device location, the position is followed; {@code moved} runs after the search centre moved.
     */
    public static void setForeground(Context context, boolean visible, Runnable moved) {
        if (visible) {
            appContext = context.getApplicationContext();
            onMoved = moved;
        }
        foreground = visible;
        updateFollowing();
    }

    public static boolean isFollowing() {
        return tracking != null;
    }

    /**
     * Pure rule (unit tested): follow a new fix if it is precise enough and either the user moved at
     * least {@link #FOLLOW_DISTANCE_M}, or the fix is much more precise than the one in use.
     */
    static boolean shouldFollow(Selected current, double lat, double lng, float accuracyM,
                                long lastMoveAt, long now) {
        if (current == null || current.kind != Kind.DEVICE || !GeoUtils.isValid(lat, lng)) return false;
        if (!Float.isNaN(accuracyM) && accuracyM > FOLLOW_MAX_ACCURACY_M) return false;
        if (now - lastMoveAt < FOLLOW_MIN_INTERVAL_MS) return false;
        double moved = GeoUtils.distanceMeters(current.lat, current.lng, lat, lng);
        if (moved >= FOLLOW_DISTANCE_M) return true;
        boolean muchMorePrecise = !Float.isNaN(accuracyM) && !Float.isNaN(current.accuracyM)
                && current.accuracyM > 100 && current.accuracyM > 3 * accuracyM;
        return muchMorePrecise && moved > accuracyM;
    }

    private static void updateFollowing() {
        Context app = appContext;
        boolean want = foreground && app != null && selected().kind == Kind.DEVICE && hasPermission(app);
        if (want && tracking == null) startFollowing(app);
        else if (!want && tracking != null) stopFollowing(app);
    }

    @SuppressLint("MissingPermission") // checked in updateFollowing()
    private static void startFollowing(Context app) {
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000)
                .setMinUpdateIntervalMillis(10_000)
                .setMinUpdateDistanceMeters(50)
                .build();
        LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location fix = result.getLastLocation();
                if (fix == null || tracking != this) return;
                float accuracy = fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN;
                long now = System.currentTimeMillis();
                if (!shouldFollow(selected(), fix.getLatitude(), fix.getLongitude(), accuracy, lastMoveAt, now)) {
                    return;
                }
                lastMoveAt = now;
                resolveAndSelect(app, fix, selected().label, new Callback() {
                    @Override
                    public void onLocation(Selected moved) {
                        Runnable r = onMoved;
                        if (r != null) r.run();
                    }

                    @Override
                    public void onFailure(Failure failure) {
                        // Not used: resolving a fix that already arrived cannot fail.
                    }
                });
            }
        };
        try {
            LocationServices.getFusedLocationProviderClient(app)
                    .requestLocationUpdates(request, callback, Looper.getMainLooper());
            tracking = callback;
        } catch (SecurityException | IllegalStateException e) {
            tracking = null;
        }
    }

    private static void stopFollowing(Context app) {
        LocationCallback callback = tracking;
        tracking = null;
        try {
            LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(callback);
        } catch (RuntimeException ignored) {
            // Play services unavailable: nothing was registered.
        }
    }

    // ------------------------------------------------------------------ searching for an area

    /**
     * Finds up to 5 matches for an area or landmark the user typed. Uses Google Places through the
     * backend (accurate coordinates and names, biased to Mumbai); falls back to the phone's Geocoder
     * when the server is unavailable.
     */
    public static void findAreas(Context context, String query, AreasCallback cb) {
        Context app = context.getApplicationContext();
        BackendClient.locate(query, new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                List<AreaMatch> matches = parseAreas(value);
                if (!matches.isEmpty()) cb.onAreas(matches);
                else geocoderAreas(app, query, cb, true);
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                geocoderAreas(app, query, cb, false);
            }
        });
    }

    /** Parses /places/locate (pure, unit tested). Invalid entries are skipped. */
    static List<AreaMatch> parseAreas(JSONObject body) {
        List<AreaMatch> out = new ArrayList<>();
        JSONArray results = body == null ? null : body.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < 5; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            double lat = r.optDouble("lat", Double.NaN);
            double lng = r.optDouble("lng", Double.NaN);
            String name = r.optString("name", "").trim();
            if (!GeoUtils.isValid(lat, lng) || name.isEmpty()) continue;
            out.add(new AreaMatch(new Selected(lat, lng, name, Kind.MANUAL), r.optString("address", ""), true));
        }
        return out;
    }

    /** Offline fallback: the phone's Geocoder, preferring matches inside the Mumbai region. */
    private static void geocoderAreas(Context app, String query, AreasCallback cb, boolean googleFoundNothing) {
        if (!Geocoder.isPresent()) {
            cb.onFailure(googleFoundNothing ? Failure.NOT_FOUND : Failure.NO_GEOCODER);
            return;
        }
        BackendClient.runInBackground(() -> {
            List<AreaMatch> found = new ArrayList<>();
            boolean failed = false;
            try {
                found = geocode(app, query);
            } catch (Exception e) {
                failed = true;
            }
            final List<AreaMatch> result = found;
            final boolean error = failed;
            BackendClient.postToMain(() -> {
                if (!result.isEmpty()) cb.onAreas(result);
                else cb.onFailure(error && !googleFoundNothing ? Failure.UNAVAILABLE : Failure.NOT_FOUND);
            });
        });
    }

    @SuppressWarnings("deprecation") // The listener variant is API 33+; this runs on a background thread.
    private static List<AreaMatch> geocode(Context app, String query) throws Exception {
        Geocoder geocoder = new Geocoder(app, Locale.getDefault());
        List<Address> results = geocoder.getFromLocationName(query, 5, MMR_SOUTH, MMR_WEST, MMR_NORTH, MMR_EAST);
        if (results == null || results.isEmpty()) results = geocoder.getFromLocationName(query + ", Mumbai", 5);
        List<AreaMatch> out = new ArrayList<>();
        if (results == null) return out;
        for (Address a : results) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue;
            String feature = a.getFeatureName();
            String label = feature != null && !feature.matches("[0-9\\s,-]+") ? feature
                    : a.getSubLocality() != null ? a.getSubLocality()
                    : a.getLocality() != null ? a.getLocality() : query;
            String address = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : "";
            out.add(new AreaMatch(new Selected(a.getLatitude(), a.getLongitude(), label, Kind.MANUAL),
                    address == null ? "" : address, false));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static String reverseGeocode(Context app, double lat, double lng) {
        if (!Geocoder.isPresent()) return null;
        try {
            List<Address> results = new Geocoder(app, Locale.getDefault()).getFromLocation(lat, lng, 1);
            if (results == null || results.isEmpty()) return null;
            Address a = results.get(0);
            return a.getSubLocality() != null ? a.getSubLocality()
                    : a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
        } catch (Exception e) {
            return null;
        }
    }
}
