package com.example.touristguide;

/**
 * Manual trip budget maths. Every number here is entered by the user; nothing comes from the AI
 * or from Google price levels. Uses {@code long} rupees with explicit limits, so it cannot overflow.
 */
public final class BudgetCalculator {

    /** Largest amount accepted in any single field: ₹10,00,00,000 (10 crore). */
    public static final long MAX_AMOUNT = 100_000_000L;
    public static final int MAX_DAYS = 365;

    public enum Error { NONE, EMPTY, INVALID, NEGATIVE, TOO_LARGE, ZERO_DAYS }

    /** Result of parsing one text field. */
    public static final class Parsed {
        public final long value;
        public final Error error;

        Parsed(long value, Error error) {
            this.value = value;
            this.error = error;
        }

        public boolean ok() {
            return error == Error.NONE;
        }
    }

    /** All user inputs, in whole rupees. */
    public static final class Inputs {
        public final long total;
        public final int days;
        public final long food;
        public final long transport;
        public final long tickets;
        public final long shopping;
        public final long other;

        public Inputs(long total, int days, long food, long transport, long tickets, long shopping, long other) {
            this.total = total;
            this.days = days;
            this.food = food;
            this.transport = transport;
            this.tickets = tickets;
            this.shopping = shopping;
            this.other = other;
        }
    }

    /** Calculated summary. */
    public static final class Summary {
        public final long planned;
        public final long remaining;
        public final long dailyBudget;
        public final long plannedPerDay;
        /** Remaining money per day; 0 when the plan is already over budget. */
        public final long remainingPerDay;
        /** Planned / total × 100, or -1 when the total is 0 (undefined). */
        public final double utilisationPercent;
        public final boolean overBudget;

        Summary(long planned, long remaining, long dailyBudget, long plannedPerDay,
                long remainingPerDay, double utilisationPercent, boolean overBudget) {
            this.planned = planned;
            this.remaining = remaining;
            this.dailyBudget = dailyBudget;
            this.plannedPerDay = plannedPerDay;
            this.remainingPerDay = remainingPerDay;
            this.utilisationPercent = utilisationPercent;
            this.overBudget = overBudget;
        }
    }

    private BudgetCalculator() {
    }

    /**
     * Parses a rupee amount. Accepts digits with optional commas/spaces and an optional "₹".
     * Empty input is reported as {@link Error#EMPTY} with value 0, so callers can decide
     * whether an empty field means zero (expense categories) or is required (total).
     */
    public static Parsed parseAmount(String raw) {
        if (raw == null) return new Parsed(0, Error.EMPTY);
        String s = raw.trim().replace("₹", "").replace(",", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) return new Parsed(0, Error.EMPTY);
        if (s.startsWith("-")) {
            return isDigits(s.substring(1)) ? new Parsed(0, Error.NEGATIVE) : new Parsed(0, Error.INVALID);
        }
        if (!isDigits(s)) return new Parsed(0, Error.INVALID);
        String trimmed = s.replaceFirst("^0+(?=\\d)", "");
        // Check length before parsing so huge inputs can't overflow a long.
        if (trimmed.length() > 9) return new Parsed(0, Error.TOO_LARGE);
        long value = Long.parseLong(trimmed);
        if (value > MAX_AMOUNT) return new Parsed(0, Error.TOO_LARGE);
        return new Parsed(value, Error.NONE);
    }

    /** Parses the number of trip days (1..365). */
    public static Parsed parseDays(String raw) {
        Parsed p = parseAmount(raw);
        if (!p.ok()) return p;
        if (p.value == 0) return new Parsed(0, Error.ZERO_DAYS);
        if (p.value > MAX_DAYS) return new Parsed(0, Error.TOO_LARGE);
        return p;
    }

    public static Summary calculate(Inputs in) {
        long total = clamp(in.total);
        int days = Math.max(1, Math.min(MAX_DAYS, in.days));
        long planned = clamp(in.food) + clamp(in.transport) + clamp(in.tickets)
                + clamp(in.shopping) + clamp(in.other); // ≤ 5 × 10^8, far below Long.MAX_VALUE
        long remaining = total - planned;
        double utilisation = total == 0 ? -1 : Math.round(planned * 1000d / total) / 10d;
        return new Summary(
                planned,
                remaining,
                divideRounded(total, days),
                divideRounded(planned, days),
                remaining > 0 ? divideRounded(remaining, days) : 0,
                utilisation,
                planned > total);
    }

    private static long clamp(long v) {
        return Math.max(0, Math.min(MAX_AMOUNT, v));
    }

    private static long divideRounded(long amount, int days) {
        return Math.round((double) amount / days);
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }
}
