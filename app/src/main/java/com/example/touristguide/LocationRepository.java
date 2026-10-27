package com.example.touristguide;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The "selected location" that Explore, Map and Chat search around. It is either the device's
 * position (one-shot request, never tracked continuously), an area the user picked, or Mumbai
 * as the default.
 */
public final class LocationRepository {

    public enum Kind { DEVICE, MANUAL, DEFAULT }

    public static final class Selected {
        public final double lat;
        public final double lng;
        public final String label;
        public final Kind kind;

        public Selected(double lat, double lng, String label, Kind kind) {
            this.lat = lat;
            this.lng = lng;
            this.label = label;
            this.kind = kind;
        }
    }

    public interface Callback {
        void onLocation(Selected selected);

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

    private static Selected selected;

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
                    ? new Selected(lat, lng, o.optString("label", DEFAULT.label), kind)
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
        } catch (JSONException ignored) {
            // Coordinates are validated before they get here.
        }
        Store.saveLocation(o);
    }

    public static boolean hasPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Gets the device position once (balanced power, approximate is fine) and selects it. */
    public static void useDeviceLocation(Context context, String fallbackLabel, Callback cb) {
        Context app = context.getApplicationContext();
        if (!hasPermission(app)) {
            cb.onFailure(Failure.NO_PERMISSION);
            return;
        }
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(5 * 60 * 1000)
                .setDurationMillis(15_000)
                .build();
        try {
            LocationServices.getFusedLocationProviderClient(app)
                    .getCurrentLocation(request, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(location -> {
                        if (location == null) {
                            cb.onFailure(Failure.UNAVAILABLE);
                            return;
                        }
                        double lat = location.getLatitude();
                        double lng = location.getLongitude();
                        // Reverse-geocode a friendly label off the main thread; fall back to a generic one.
                        BackendClient.runInBackground(() -> {
                            String label = reverseGeocode(app, lat, lng);
                            Selected s = new Selected(lat, lng, label == null ? fallbackLabel : label, Kind.DEVICE);
                            BackendClient.postToMain(() -> {
                                select(s);
                                cb.onLocation(s);
                            });
                        });
                    })
                    .addOnFailureListener(e -> cb.onFailure(Failure.UNAVAILABLE));
        } catch (SecurityException e) {
            cb.onFailure(Failure.NO_PERMISSION);
        }
    }

    /** Looks up an area typed by the user using the on-device Geocoder (no API key needed). */
    public static void searchArea(Context context, String query, Callback cb) {
        Context app = context.getApplicationContext();
        if (!Geocoder.isPresent()) {
            cb.onFailure(Failure.NO_GEOCODER);
            return;
        }
        BackendClient.runInBackground(() -> {
            Selected found = null;
            boolean failed = false;
            try {
                found = geocode(app, query);
            } catch (Exception e) {
                failed = true;
            }
            final Selected result = found;
            final boolean error = failed;
            BackendClient.postToMain(() -> {
                if (result != null) {
                    select(result);
                    cb.onLocation(result);
                } else {
                    cb.onFailure(error ? Failure.UNAVAILABLE : Failure.NOT_FOUND);
                }
            });
        });
    }

    @SuppressWarnings("deprecation") // The listener variant is API 33+; this runs on a background thread.
    private static Selected geocode(Context app, String query) throws Exception {
        List<Address> results = new Geocoder(app, Locale.getDefault()).getFromLocationName(query, 1);
        if (results == null || results.isEmpty()) return null;
        Address a = results.get(0);
        if (!a.hasLatitude() || !a.hasLongitude()) return null;
        String label = a.getSubLocality() != null ? a.getSubLocality()
                : a.getLocality() != null ? a.getLocality() : query;
        return new Selected(a.getLatitude(), a.getLongitude(), label, Kind.MANUAL);
    }

    @SuppressWarnings("deprecation")
    private static String reverseGeocode(Context app, double lat, double lng) {
        if (!Geocoder.isPresent()) return null;
        try {
            List<Address> results = new Geocoder(app, Locale.getDefault()).getFromLocation(lat, lng, 1);
            if (results == null || results.isEmpty()) return null;
            Address a = results.get(0);
            return a.getSubLocality() != null ? a.getSubLocality() : a.getLocality();
        } catch (Exception e) {
            return null;
        }
    }
}
