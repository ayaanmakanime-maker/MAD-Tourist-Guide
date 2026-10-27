package com.example.touristguide;

import java.util.Locale;

/** Straight-line distance helpers. These are "as the crow flies", not travel distances. */
public final class GeoUtils {
    private static final double EARTH_RADIUS_M = 6_371_000d;

    private GeoUtils() {
    }

    /** Haversine distance in metres. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** "350 m", "1.2 km", "14 km". */
    public static String formatDistance(double meters) {
        if (meters < 0 || Double.isNaN(meters)) return "";
        if (meters < 1000) return Math.round(meters / 10d) * 10 + " m";
        if (meters < 10_000) {
            String km = String.format(Locale.US, "%.1f", meters / 1000d);
            return (km.endsWith(".0") ? km.substring(0, km.length() - 2) : km) + " km";
        }
        return Math.round(meters / 1000d) + " km";
    }

    public static boolean isValid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }
}
