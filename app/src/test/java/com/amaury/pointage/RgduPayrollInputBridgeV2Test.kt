package com.amaury.pointage

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduPayrollInputBridgeV2Test {
    private fun salary(
        gross: Double = 2000.0,
        grossReliable: Boolean = true,
        tiers: List<V2SalaryAdapter.TierDuration> = emptyList(),
        complementaryMinutes: Int = 0
    ) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = tiers,
        totalWorkedMs = 0L,
        regularGross = gross,
        overtimeGross = 0.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = gross,
        monthlyGrossReliable = grossReliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = complementaryMinutes,
        completedSessions = 0,
        warnings = emptyList()
    )

    @Test
    fun `full time keeps fractional structural and variable paid minutes`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(
                    V2SalaryAdapter.TierDuration("structurelles", 62_430_000L, 1.25),
                    V2SalaryAdapter.TierDuration("variables", 7_200_000L, 1.25)
                )
            ),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )

        assertTrue(result.reliable)
        assertEquals(1160.5, result.additionalPaidMinutes!!, 0.000001)
    }

    @Test
    fun `reduction remuneration adds benefits in kind to reliable gross`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(gross = 2000.0),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 125.50,
            paidHoursComplete = true
        )

        assertEquals(2125.50, result.reductionRemunerationMonthly!!, 0.001)
    }

    @Test
    fun `empty overtime tiers never prove zero when paid hours are incomplete`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = null
        )

        assertFalse(result.reliable)
        assertNull(result.additionalPaidMinutes)
        assertTrue(result.warnings.any { it.contains("exhaustivité") })
    }

    @Test
    fun `zero additional hours is accepted only when paid hours coverage is confirmed`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.additionalPaidMinutes!!, 0.0)
    }

    @Test
    fun `part time tiers must agree with complementary minutes`() {
        val mismatch = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("complémentaires", 60L * 60_000L, 1.10)),
                complementaryMinutes = 120
            ),
            contractType = ContractTypeV2.PART_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )

        assertFalse(mismatch.reliable)
        assertNull(mismatch.additionalPaidMinutes)
        assertTrue(mismatch.warnings.any { it.contains("incohérence") })
    }

    @Test
    fun `part time matching tiers expose complementary minutes`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("complémentaires", 120L * 60_000L, 1.10)),
                complementaryMinutes = 120
            ),
            contractType = ContractTypeV2.PART_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )

        assertTrue(result.reliable)
        assertEquals(120.0, result.additionalPaidMinutes!!, 0.0)
    }

    @Test
    fun `unreliable gross blocks RGDU remuneration even if hours are complete`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(grossReliable = false),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )

        assertFalse(result.reliable)
        assertNull(result.reductionRemunerationMonthly)
        assertTrue(result.warnings.any { it.contains("brut mensuel HoraTrack non fiable") })
    }
}
