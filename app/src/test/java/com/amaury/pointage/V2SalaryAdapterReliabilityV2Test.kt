package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SalaryAdapterReliabilityV2Test {
    @Test
    fun provisionalComplementaryRateMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = true
        )

        assertFalse(reliable)
    }

    @Test
    fun noComplementaryFallbackKeepsOtherwiseReliableGrossReliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = false
        )

        assertTrue(reliable)
    }

    @Test
    fun provisionalOvertimeRateStillMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = true,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = false
        )

        assertFalse(reliable)
    }
}
