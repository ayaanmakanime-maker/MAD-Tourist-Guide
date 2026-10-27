package com.example.touristguide;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists the manual budget exactly as typed (so half-finished input survives restarts) and
 * turns it into {@link BudgetCalculator} inputs.
 */
public final class BudgetRepository {

    /** Field keys, in display order. */
    public static final String TOTAL = "total";
    public static final String DAYS = "days";
    public static final String FOOD = "food";
    public static final String TRANSPORT = "transport";
    public static final String TICKETS = "tickets";
    public static final String SHOPPING = "shopping";
    public static final String OTHER = "other";
    public static final String[] EXPENSE_FIELDS = {FOOD, TRANSPORT, TICKETS, SHOPPING, OTHER};

    private BudgetRepository() {
    }

    /** The raw text the user typed for a field ("" if none). */
    public static String text(String field) {
        return Store.budgetJson().optString(field, "");
    }

    public static boolean isEmpty() {
        return Store.budgetJson().length() == 0;
    }

    public static void setText(String field, String value) {
        JSONObject o = Store.budgetJson();
        try {
            o.put(field, value == null ? "" : value);
        } catch (JSONException ignored) {
            // Plain string values only.
        }
        Store.saveBudget(o);
    }

    public static void reset() {
        Store.saveBudget(new JSONObject());
    }

    /**
     * Parsed inputs, or null when the total or days are missing/invalid. Invalid expense fields
     * count as 0 here; the UI shows their errors next to the field.
     */
    public static BudgetCalculator.Inputs inputs() {
        BudgetCalculator.Parsed total = BudgetCalculator.parseAmount(text(TOTAL));
        BudgetCalculator.Parsed days = BudgetCalculator.parseDays(text(DAYS));
        if (!total.ok() || !days.ok()) return null;
        return new BudgetCalculator.Inputs(total.value, (int) days.value,
                expense(FOOD), expense(TRANSPORT), expense(TICKETS), expense(SHOPPING), expense(OTHER));
    }

    public static BudgetCalculator.Summary summary() {
        BudgetCalculator.Inputs in = inputs();
        return in == null ? null : BudgetCalculator.calculate(in);
    }

    private static long expense(String field) {
        BudgetCalculator.Parsed p = BudgetCalculator.parseAmount(text(field));
        return p.ok() ? p.value : 0;
    }
}
