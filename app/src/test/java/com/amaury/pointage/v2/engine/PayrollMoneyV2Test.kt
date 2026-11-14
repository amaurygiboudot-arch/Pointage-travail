package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayrollMoneyV2Test {
    @Test
    fun decimalHalfCentsRoundAwayFromZeroWithoutBinaryProduct() {
        assertEquals(101L, PayrollMoneyV2.cents(1.005))
        assertEquals(268L, PayrollMoneyV2.cents(2.675))
        assertEquals(-101L, PayrollMoneyV2.cents(-1.005))
        assertEquals(100L, PayrollMoneyV2.cents(1.0049999))
        assertEquals(101L, PayrollMoneyV2.cents(1.0050001))
        assertEquals(12057L, PayrollMoneyV2.contribution(2411.3, 0.05))
        assertEquals(12057L, PayrollMoneyV2.tax(241130L, 0.05))
        assertEquals(0L, PayrollMoneyV2.cents(1e-300))
        assertEquals(0L, PayrollMoneyV2.cents(-1e-300))
        assertEquals(0L, PayrollMoneyV2.tax(241130L, 1e-300))
    }

    @Test
    fun signedSumsPreserveEveryCent() {
        assertEquals(-26957L, PayrollMoneyV2.sum(listOf(0L, -20957L, -6000L)))
        assertEquals(77127L, PayrollMoneyV2.sum(listOf(-26957L, 100000L, 2884L, 1200L)))
        assertEquals(-269.57, PayrollMoneyV2.amount(-26957L)!!, 0.0)
    }

    @Test
    fun nonFiniteInputsAndInvalidRatesAreNotMoney() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(PayrollMoneyV2.cents(invalid))
            assertNull(PayrollMoneyV2.contribution(invalid, 0.05))
            assertNull(PayrollMoneyV2.contribution(100.0, invalid))
            assertNull(PayrollMoneyV2.tax(10000L, invalid))
        }
        assertNull(PayrollMoneyV2.tax(100L, -0.01))
        assertNull(PayrollMoneyV2.tax(100L, 1.01))
        assertNull(PayrollMoneyV2.tax(-1L, 0.05))
        assertNull(PayrollMoneyV2.contribution(-100.0, 0.05))
        assertNull(PayrollMoneyV2.contribution(100.0, -0.05))
    }

    @Test
    fun overflowAndLossyDoubleRoundTripFailExplicitly() {
        assertNull(PayrollMoneyV2.cents(Double.MAX_VALUE))
        assertNull(PayrollMoneyV2.contribution(Double.MAX_VALUE, Double.MAX_VALUE))
        assertNull(PayrollMoneyV2.sum(listOf(PayrollMoneyV2.MAX_CENTS, 1L)))
        assertNull(PayrollMoneyV2.sum(listOf(-PayrollMoneyV2.MAX_CENTS, -1L)))
        assertNull(PayrollMoneyV2.amount(Long.MAX_VALUE))
        assertNull(PayrollMoneyV2.amount(Long.MIN_VALUE))
        // .91 becomes .9 in Double at this magnitude: the missing cent must not be published.
        assertNull(PayrollMoneyV2.amount(9_007_199_254_740_991L))
        assertEquals(9_007_199_254_740_990L, PayrollMoneyV2.cents(90_071_992_547_409.90))
    }
}
