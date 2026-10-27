package com.example.touristguide;

/**
 * Indian-rupee formatting with Indian digit grouping (₹1,23,45,678).
 * Implemented by hand so it behaves identically on every device and in unit tests.
 */
public final class Money {
    private Money() {
    }

    public static String inr(long rupees) {
        String grouped = group(rupees);
        return rupees < 0 ? "−₹" + grouped.substring(1) : "₹" + grouped;
    }

    /** Groups digits as 12,34,567 (last three, then pairs). */
    public static String group(long value) {
        // Math.abs(Long.MIN_VALUE) overflows, so spell that one value out.
        String digits = value == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(Math.abs(value));
        if (digits.length() <= 3) return (value < 0 ? "-" : "") + digits;
        StringBuilder out = new StringBuilder(digits.substring(digits.length() - 3));
        int i = digits.length() - 3;
        while (i > 0) {
            int start = Math.max(0, i - 2);
            out.insert(0, digits.substring(start, i) + ",");
            i = start;
        }
        return (value < 0 ? "-" : "") + out;
    }
}
