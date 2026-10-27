package com.example.touristguide;

import android.content.Context;

final class Formats {
    private Formats() {
    }

    static String priceLabel(Context c, int budget) {
        return budget == 0
                ? c.getString(R.string.detail_free_entry)
                : c.getString(R.string.detail_budget_fmt, budget);
    }

    static String metaLabel(Context c, int minutes, int budget) {
        return c.getString(R.string.place_meta_fmt, minutes, priceLabel(c, budget));
    }
}
