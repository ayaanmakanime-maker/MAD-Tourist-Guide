package com.example.touristguide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BudgetCalculatorTest {

    private static BudgetCalculator.Summary calc(long total, int days, long food, long transport,
                                                 long tickets, long shopping, long other) {
        return BudgetCalculator.calculate(new BudgetCalculator.Inputs(total, days, food, transport, tickets, shopping, other));
    }

    @Test
    public void typicalTripWithinBudget() {
        BudgetCalculator.Summary s = calc(10_000, 2, 3_000, 1_500, 1_000, 2_000, 500);
        assertEquals(8_000, s.planned);
        assertEquals(2_000, s.remaining);
        assertEquals(5_000, s.dailyBudget);
        assertEquals(4_000, s.plannedPerDay);
        assertEquals(1_000, s.remainingPerDay);
        assertEquals(80.0, s.utilisationPercent, 0.0001);
        assertFalse(s.overBudget);
    }

    @Test
    public void overBudgetIsFlaggedAndRemainingPerDayIsZero() {
        BudgetCalculator.Summary s = calc(5_000, 2, 4_000, 2_000, 0, 0, 0);
        assertTrue(s.overBudget);
        assertEquals(-1_000, s.remaining);
        assertEquals(0, s.remainingPerDay);
        assertEquals(120.0, s.utilisationPercent, 0.0001);
    }

    @Test
    public void exactlyOnBudgetIsNotOver() {
        BudgetCalculator.Summary s = calc(3_000, 1, 1_000, 1_000, 1_000, 0, 0);
        assertFalse(s.overBudget);
        assertEquals(0, s.remaining);
        assertEquals(100.0, s.utilisationPercent, 0.0001);
    }

    @Test
    public void zeroTotalHasUndefinedUtilisation() {
        BudgetCalculator.Summary s = calc(0, 1, 0, 0, 0, 0, 0);
        assertEquals(-1, s.utilisationPercent, 0.0001);
        assertFalse(s.overBudget);
        BudgetCalculator.Summary over = calc(0, 1, 10, 0, 0, 0, 0);
        assertTrue(over.overBudget);
    }

    @Test
    public void perDayValuesRoundToNearestRupee() {
        BudgetCalculator.Summary s = calc(1_000, 3, 100, 0, 0, 0, 0);
        assertEquals(333, s.dailyBudget);  // 333.33
        assertEquals(33, s.plannedPerDay); // 33.33
        assertEquals(300, s.remainingPerDay);
        BudgetCalculator.Summary t = calc(2_000, 3, 0, 0, 0, 0, 0);
        assertEquals(667, t.dailyBudget);  // 666.67
    }

    @Test
    public void maximumValuesDoNotOverflow() {
        long max = BudgetCalculator.MAX_AMOUNT;
        BudgetCalculator.Summary s = calc(max, 1, max, max, max, max, max);
        assertEquals(5 * max, s.planned);
        assertEquals(-4 * max, s.remaining);
        assertTrue(s.overBudget);
        assertEquals(500.0, s.utilisationPercent, 0.0001);
    }

    @Test
    public void outOfRangeInputsAreClamped() {
        BudgetCalculator.Summary s = calc(Long.MAX_VALUE, 0, -50, Long.MAX_VALUE, 0, 0, 0);
        assertEquals(BudgetCalculator.MAX_AMOUNT, s.planned);
        assertEquals(BudgetCalculator.MAX_AMOUNT, s.dailyBudget); // days clamped to 1
        BudgetCalculator.Summary d = calc(365_000, 10_000, 0, 0, 0, 0, 0);
        assertEquals(1_000, d.dailyBudget); // days clamped to 365
    }

    @Test
    public void parsesValidAmounts() {
        assertEquals(1500, BudgetCalculator.parseAmount("1500").value);
        assertEquals(150000, BudgetCalculator.parseAmount("1,50,000").value);
        assertEquals(2500, BudgetCalculator.parseAmount(" ₹ 2,500 ").value);
        assertEquals(7, BudgetCalculator.parseAmount("007").value);
        assertEquals(0, BudgetCalculator.parseAmount("0").value);
        assertTrue(BudgetCalculator.parseAmount("100000000").ok());
    }

    @Test
    public void rejectsInvalidAmounts() {
        assertEquals(BudgetCalculator.Error.EMPTY, BudgetCalculator.parseAmount("").error);
        assertEquals(BudgetCalculator.Error.EMPTY, BudgetCalculator.parseAmount("   ").error);
        assertEquals(BudgetCalculator.Error.EMPTY, BudgetCalculator.parseAmount(null).error);
        assertEquals(BudgetCalculator.Error.NEGATIVE, BudgetCalculator.parseAmount("-200").error);
        assertEquals(BudgetCalculator.Error.INVALID, BudgetCalculator.parseAmount("12.50").error);
        assertEquals(BudgetCalculator.Error.INVALID, BudgetCalculator.parseAmount("abc").error);
        assertEquals(BudgetCalculator.Error.INVALID, BudgetCalculator.parseAmount("1e5").error);
        assertEquals(BudgetCalculator.Error.INVALID, BudgetCalculator.parseAmount("--5").error);
        assertEquals(BudgetCalculator.Error.TOO_LARGE, BudgetCalculator.parseAmount("100000001").error);
        // Would overflow a long if parsed naively.
        assertEquals(BudgetCalculator.Error.TOO_LARGE, BudgetCalculator.parseAmount("99999999999999999999999").error);
    }

    @Test
    public void parsesDays() {
        assertEquals(3, BudgetCalculator.parseDays("3").value);
        assertEquals(BudgetCalculator.Error.ZERO_DAYS, BudgetCalculator.parseDays("0").error);
        assertEquals(BudgetCalculator.Error.TOO_LARGE, BudgetCalculator.parseDays("366").error);
        assertEquals(BudgetCalculator.Error.EMPTY, BudgetCalculator.parseDays("").error);
        assertTrue(BudgetCalculator.parseDays("365").ok());
    }

    @Test
    public void formatsIndianRupees() {
        assertEquals("₹0", Money.inr(0));
        assertEquals("₹999", Money.inr(999));
        assertEquals("₹1,000", Money.inr(1_000));
        assertEquals("₹1,00,000", Money.inr(100_000));
        assertEquals("₹12,34,567", Money.inr(1_234_567));
        assertEquals("₹10,00,00,000", Money.inr(100_000_000));
        assertEquals("−₹1,500", Money.inr(-1_500));
        assertEquals("-92,23,37,20,36,85,47,75,808", Money.group(Long.MIN_VALUE));
    }
}
