package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollEngineV2Test {
    private fun contract(rate: Double = 12.0, weeklyMinutes: Int? = 35 * 60) = ContractV2(
        id = "contract-test",
        employerId = "employer-test",
        type = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = weeklyMinutes,
        grossHourlyRate = rate,
        hireDateEpochDay = null
    )

    @Test
    fun regularWeekUsesOnlyConfirmedPaidMinutes() {
        val result = PayrollEngineV2.calculate(
            contract = contract(),
            weeks = listOf(PayrollWeekV2(paidMinutes = 35 * 60)),
            rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
        )

        assertEquals(420.0, result.regularGross, 0.001)
        assertEquals(0.0, result.overtimeGross, 0.001)
        assertEquals(420.0, result.grossEstimate, 0.001)
    }

    @Test
    fun overtimeIsNeverInventedWhenNoTierIsProvided() {
        val result = PayrollEngineV2.calculate(
            contract = contract(),
            weeks = listOf(PayrollWeekV2(paidMinutes = 40 * 60)),
            rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
        )

        assertEquals(420.0, result.regularGross, 0.001)
        assertEquals(0.0, result.overtimeGross, 0.001)
        assertTrue(result.traces.any { it.contains("Aucune majoration") })
    }

    @Test
    fun confirmedOvertimeTierIsAppliedDeterministically() {
        val result = PayrollEngineV2.calculate(
            contract = contract(),
            weeks = listOf(PayrollWeekV2(paidMinutes = 40 * 60)),
            rules = PayrollRulesV2(
                weeklyRegularMinutes = 35 * 60,
                overtimeTiers = listOf(
                    OvertimeTierV2(fromMinutes = 35 * 60, toMinutes = 43 * 60, multiplier = 1.25)
                )
            )
        )

        assertEquals(420.0, result.regularGross, 0.001)
        assertEquals(75.0, result.overtimeGross, 0.001)
        assertEquals(495.0, result.grossEstimate, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHourlyRateIsRejected() {
        PayrollEngineV2.calculate(
            contract = contract(rate = 0.0),
            weeks = listOf(PayrollWeekV2(paidMinutes = 35 * 60)),
            rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60)
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingWeeklyReferenceIsRejectedInsteadOfInvented() {
        PayrollEngineV2.calculate(
            contract = contract(weeklyMinutes = null),
            weeks = listOf(PayrollWeekV2(paidMinutes = 35 * 60)),
            rules = PayrollRulesV2(weeklyRegularMinutes = null)
        )
    }
}
