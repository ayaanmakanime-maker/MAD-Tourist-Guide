package com.example.touristguide;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns place data into display text. Only fields the provider actually returned are shown;
 * estimates are always labelled as estimates.
 */
final class Formats {
    private static final String SEP = " • ";

    private Formats() {
    }

    /** Curated entry fee label, e.g. "Free entry*" or "₹200 approx."; null when unknown. */
    static String priceLabel(Context c, int budget) {
        if (budget == Place.UNKNOWN) return null;
        return budget == 0
                ? c.getString(R.string.detail_free_entry)
                : c.getString(R.string.detail_budget_fmt, Money.inr(budget));
    }

    /** Google price information, clearly labelled; null when Google gave none. */
    static String googlePrice(Context c, Place p) {
        if (p.priceStart != null || p.priceEnd != null) {
            String from = p.priceStart == null ? null : Money.inr(p.priceStart);
            String to = p.priceEnd == null ? null : Money.inr(p.priceEnd);
            boolean inr = p.priceCurrency == null || "INR".equals(p.priceCurrency);
            if (inr) {
                if (from != null && to != null) return c.getString(R.string.price_range_fmt, from, to);
                if (from != null) return c.getString(R.string.price_from_fmt, from);
            }
        }
        String level = priceLevelLabel(c, p.priceLevel);
        return level == null ? null : c.getString(R.string.price_level_fmt, level);
    }

    static String priceLevelLabel(Context c, String level) {
        if (level == null) return null;
        switch (level) {
            case "PRICE_LEVEL_FREE": return c.getString(R.string.price_free);
            case "PRICE_LEVEL_INEXPENSIVE": return c.getString(R.string.price_inexpensive);
            case "PRICE_LEVEL_MODERATE": return c.getString(R.string.price_moderate);
            case "PRICE_LEVEL_EXPENSIVE": return c.getString(R.string.price_expensive);
            case "PRICE_LEVEL_VERY_EXPENSIVE": return c.getString(R.string.price_very_expensive);
            default: return null;
        }
    }

    static String rating(Context c, Place p) {
        if (p.rating == null) return null;
        String value = String.format(Locale.US, "%.1f", p.rating);
        return p.ratingCount == null
                ? c.getString(R.string.rating_fmt, value)
                : c.getString(R.string.rating_count_fmt, value, Money.group(p.ratingCount));
    }

    static String openStatus(Context c, Place p) {
        if ("CLOSED_TEMPORARILY".equals(p.businessStatus)) return c.getString(R.string.status_temporarily_closed);
        if (p.openNow == null) return null;
        return c.getString(p.openNow ? R.string.status_open_now : R.string.status_closed_now);
    }

    static String distance(Context c, Place p, double lat, double lng) {
        if (!GeoUtils.isValid(lat, lng)) return null;
        return c.getString(R.string.distance_fmt,
                GeoUtils.formatDistance(GeoUtils.distanceMeters(lat, lng, p.lat, p.lng)));
    }

    /** One line of known facts: rating • distance • open status • price. */
    static String facts(Context c, Place p, double lat, double lng) {
        List<String> parts = new ArrayList<>();
        add(parts, rating(c, p));
        add(parts, distance(c, p, lat, lng));
        add(parts, openStatus(c, p));
        if (p.isLive()) {
            add(parts, googlePrice(c, p));
        } else {
            if (p.minutes > 0) parts.add(c.getString(R.string.visit_minutes_fmt, p.minutes));
            add(parts, priceLabel(c, p.budget));
        }
        return TextUtils.join(SEP, parts);
    }

    static String subtitle(Context c, Place p) {
        if (p.category.isEmpty()) return p.area;
        if (p.area.isEmpty()) return p.category;
        return c.getString(R.string.place_sub_fmt, p.category, p.area);
    }

    /** Where the data came from, e.g. "Live from Google Maps" or "Curated guide". */
    static String source(Context c, Place p, PlaceRepository.Status status) {
        if (!p.isLive()) return c.getString(R.string.source_curated);
        if (status == PlaceRepository.Status.CACHED) return c.getString(R.string.source_cached);
        return c.getString(R.string.source_live);
    }

    static String ago(long time) {
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /** Google Maps directions to a place (by place id when we have one). */
    static Intent directions(Place p) {
        Uri.Builder b = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1");
        if (p.googleId() != null) {
            b.appendQueryParameter("destination", p.lat + "," + p.lng)
                    .appendQueryParameter("destination_place_id", p.googleId());
        } else {
            // Curated coordinates are approximate, so let Google Maps resolve the name.
            b.appendQueryParameter("destination", p.name + ", " + p.area + ", Mumbai");
        }
        return new Intent(Intent.ACTION_VIEW, b.build());
    }

    /** Error text for a backend failure. */
    static String error(Context c, BackendClient.ApiError e) {
        if (e == null) return "";
        switch (e.kind) {
            case NETWORK: return c.getString(R.string.error_network);
            case TIMEOUT: return c.getString(R.string.error_timeout);
            case NOT_CONFIGURED:
                return e.serverMessage != null ? e.serverMessage : c.getString(R.string.error_not_configured);
            case QUOTA: return c.getString(R.string.error_quota);
            case NOT_FOUND: return c.getString(R.string.error_not_found);
            case BAD_REQUEST: return c.getString(R.string.error_bad_request);
            default:
                return e.serverMessage != null ? e.serverMessage : c.getString(R.string.error_server);
        }
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.isEmpty()) parts.add(value);
    }
}
