package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for the {@link ChatUsage} record: canonical constructor + accessor round-trip,
 * the {@link ChatUsage#unknown()} factory, and the record-generated equals/hashCode/toString
 * contract. All cases are pure and deterministic (no OWLAPI / Protege / network / UI).
 */
class ChatUsageTest {

    // ---- canonical constructor + accessors ----

    @Test
    void constructorRoundTripsPositiveValuesAndNonNullCost() {
        ChatUsage u = new ChatUsage(100, 200, 50, 0.15);
        assertEquals(100, u.inputTokens(), "inputTokens accessor should return constructor value");
        assertEquals(200, u.outputTokens(), "outputTokens accessor should return constructor value");
        assertEquals(50, u.cachedInputTokens(), "cachedInputTokens accessor should return constructor value");
        assertEquals(0.15, u.costUsd(), "costUsd accessor should return constructor value");
    }

    @Test
    void constructorWithUnknownSentinelValues() {
        ChatUsage u = new ChatUsage(-1, -1, -1, null);
        assertEquals(-1, u.inputTokens(), "inputTokens should be -1 sentinel");
        assertEquals(-1, u.outputTokens(), "outputTokens should be -1 sentinel");
        assertEquals(-1, u.cachedInputTokens(), "cachedInputTokens should be -1 sentinel");
        assertNull(u.costUsd(), "costUsd should be null for unknown");
    }

    @Test
    void constructorWithMixedValues() {
        ChatUsage u = new ChatUsage(42, -1, 7, null);
        assertEquals(42, u.inputTokens(), "inputTokens should be preserved");
        assertEquals(-1, u.outputTokens(), "outputTokens unknown preserved");
        assertEquals(7, u.cachedInputTokens(), "cachedInputTokens preserved");
        assertNull(u.costUsd(), "costUsd null preserved");
    }

    @Test
    void constructorWithZeroValues() {
        ChatUsage u = new ChatUsage(0, 0, 0, 0.0);
        assertEquals(0, u.inputTokens(), "inputTokens zero");
        assertEquals(0, u.outputTokens(), "outputTokens zero");
        assertEquals(0, u.cachedInputTokens(), "cachedInputTokens zero");
        assertEquals(0.0, u.costUsd(), "costUsd zero");
    }

    @Test
    void constructorWithMaxIntegerValues() {
        ChatUsage u = new ChatUsage(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, u.inputTokens(), "inputTokens MAX");
        assertEquals(Integer.MAX_VALUE, u.outputTokens(), "outputTokens MAX");
        assertEquals(Integer.MAX_VALUE, u.cachedInputTokens(), "cachedInputTokens MAX");
        assertEquals(Double.MAX_VALUE, u.costUsd(), "costUsd MAX");
    }

    @Test
    void constructorWithMinIntegerValues() {
        ChatUsage u = new ChatUsage(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, -1.0);
        assertEquals(Integer.MIN_VALUE, u.inputTokens(), "inputTokens MIN");
        assertEquals(Integer.MIN_VALUE, u.outputTokens(), "outputTokens MIN");
        assertEquals(Integer.MIN_VALUE, u.cachedInputTokens(), "cachedInputTokens MIN");
        assertEquals(-1.0, u.costUsd(), "costUsd negative");
    }

    @Test
    void constructorWithInfiniteCost() {
        ChatUsage pos = new ChatUsage(1, 2, 3, Double.POSITIVE_INFINITY);
        ChatUsage neg = new ChatUsage(1, 2, 3, Double.NEGATIVE_INFINITY);
        assertEquals(Double.POSITIVE_INFINITY, pos.costUsd(), "costUsd positive infinity");
        assertEquals(Double.NEGATIVE_INFINITY, neg.costUsd(), "costUsd negative infinity");
    }

    @Test
    void constructorWithNaNCost() {
        ChatUsage u = new ChatUsage(1, 2, 3, Double.NaN);
        assertTrue(Double.isNaN(u.costUsd()), "costUsd should be NaN");
    }

    @Test
    void constructorWithVerySmallPositiveCost() {
        ChatUsage u = new ChatUsage(1, 2, 3, Double.MIN_VALUE);
        assertEquals(Double.MIN_VALUE, u.costUsd(), "costUsd smallest positive double");
    }

    @Test
    void inputTokensAccessorReturnsNegativeValue() {
        assertEquals(-9999, new ChatUsage(-9999, 0, 0, null).inputTokens(),
                "inputTokens accessor returns arbitrary negative value");
    }

    @Test
    void outputTokensAccessorReturnsNegativeValue() {
        assertEquals(-9999, new ChatUsage(0, -9999, 0, null).outputTokens(),
                "outputTokens accessor returns arbitrary negative value");
    }

    @Test
    void cachedInputTokensAccessorReturnsNegativeValue() {
        assertEquals(-9999, new ChatUsage(0, 0, -9999, null).cachedInputTokens(),
                "cachedInputTokens accessor returns arbitrary negative value");
    }

    // ---- unknown() factory ----

    @Test
    void unknownFactoryHasAllUnknownFields() {
        ChatUsage u = ChatUsage.unknown();
        assertEquals(-1, u.inputTokens(), "unknown() inputTokens should be -1");
        assertEquals(-1, u.outputTokens(), "unknown() outputTokens should be -1");
        assertEquals(-1, u.cachedInputTokens(), "unknown() cachedInputTokens should be -1");
        assertNull(u.costUsd(), "unknown() costUsd should be null");
    }

    @Test
    void unknownFactoryEqualsManuallyConstructedUnknown() {
        assertEquals(new ChatUsage(-1, -1, -1, null), ChatUsage.unknown(),
                "unknown() should equal the equivalent manually-built instance");
    }

    @Test
    void multipleUnknownCallsAreEqual() {
        ChatUsage a = ChatUsage.unknown();
        ChatUsage b = ChatUsage.unknown();
        assertEquals(a, b, "two unknown() instances should be equal");
        assertEquals(a.hashCode(), b.hashCode(), "two unknown() instances should share a hashCode");
    }

    // ---- equals ----

    @Test
    void identicalFieldsAreEqual() {
        ChatUsage a = new ChatUsage(10, 20, 5, 0.5);
        ChatUsage b = new ChatUsage(10, 20, 5, 0.5);
        assertEquals(a, b, "identical field values should be equal");
    }

    @Test
    void differentInputTokensAreNotEqual() {
        assertNotEquals(new ChatUsage(10, 20, 5, 0.5), new ChatUsage(11, 20, 5, 0.5),
                "differing inputTokens should break equality");
    }

    @Test
    void differentOutputTokensAreNotEqual() {
        assertNotEquals(new ChatUsage(10, 20, 5, 0.5), new ChatUsage(10, 21, 5, 0.5),
                "differing outputTokens should break equality");
    }

    @Test
    void differentCachedInputTokensAreNotEqual() {
        assertNotEquals(new ChatUsage(10, 20, 5, 0.5), new ChatUsage(10, 20, 6, 0.5),
                "differing cachedInputTokens should break equality");
    }

    @Test
    void differentCostUsdAreNotEqual() {
        assertNotEquals(new ChatUsage(10, 20, 5, 0.5), new ChatUsage(10, 20, 5, 0.6),
                "differing costUsd should break equality");
    }

    @Test
    void nullCostVersusNonNullCostAreNotEqual() {
        assertNotEquals(new ChatUsage(10, 20, 5, null), new ChatUsage(10, 20, 5, 0.0),
                "null cost should differ from 0.0 cost");
    }

    @Test
    void equalsWithNullReturnsFalse() {
        assertFalse(new ChatUsage(1, 2, 3, 4.0).equals(null),
                "equals(null) should be false");
    }

    @Test
    void equalsWithDifferentTypeReturnsFalse() {
        assertFalse(new ChatUsage(1, 2, 3, 4.0).equals("not a ChatUsage"),
                "equals against a different type should be false");
    }

    @Test
    void equalsIsReflexive() {
        ChatUsage u = new ChatUsage(1, 2, 3, 4.0);
        assertEquals(u, u, "a ChatUsage should equal itself");
    }

    // ---- hashCode ----

    @Test
    void equalInstancesShareHashCode() {
        assertEquals(new ChatUsage(10, 20, 5, 0.5).hashCode(),
                new ChatUsage(10, 20, 5, 0.5).hashCode(),
                "equal instances must have equal hashCodes");
    }

    @Test
    void hashCodeIsStableAcrossCalls() {
        ChatUsage u = new ChatUsage(10, 20, 5, 0.5);
        int first = u.hashCode();
        assertEquals(first, u.hashCode(), "hashCode should be stable across repeated calls");
    }

    // ---- toString ----

    @Test
    void toStringIsNonNullAndContainsClassName() {
        String s = new ChatUsage(10, 20, 5, 0.5).toString();
        assertNotNull(s, "toString should not be null");
        assertTrue(s.contains("ChatUsage"), "toString should contain the record name, was: " + s);
    }

    @Test
    void toStringContainsFieldNames() {
        String s = new ChatUsage(10, 20, 5, 0.5).toString();
        assertTrue(s.contains("inputTokens"), "toString should contain inputTokens, was: " + s);
        assertTrue(s.contains("outputTokens"), "toString should contain outputTokens, was: " + s);
        assertTrue(s.contains("cachedInputTokens"), "toString should contain cachedInputTokens, was: " + s);
        assertTrue(s.contains("costUsd"), "toString should contain costUsd, was: " + s);
    }

    @Test
    void toStringContainsFieldValues() {
        String s = new ChatUsage(10, 20, 5, 0.5).toString();
        assertTrue(s.contains("10"), "toString should contain inputTokens value, was: " + s);
        assertTrue(s.contains("20"), "toString should contain outputTokens value, was: " + s);
        assertTrue(s.contains("0.5"), "toString should contain costUsd value, was: " + s);
    }

    @Test
    void toStringForUnknownRepresentsSentinels() {
        String s = ChatUsage.unknown().toString();
        assertTrue(s.contains("-1"), "unknown().toString() should show -1 sentinels, was: " + s);
        assertTrue(s.contains("null"), "unknown().toString() should show null cost, was: " + s);
    }
}
