package com.amaury.pointage

import com.amaury.pointage.v2.engine.EmployerGeneralReduction2026V2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduPayrollInputBridgeV2Test {
    private fun salary(
        gross: Double = 2_000.0,
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
        completedSessions = 1,
        warnings = emptyList()
    )

    @Test
    fun `full time complete month preserves exact paid additional minutes`() {
        val result = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(
                    V2SalaryAdapter.TierDuration("25 %", 90_030L, 1.25),
                    V2SalaryAdapter.TierDuration("50 %", 30_000L, 1.50)
                )
            ),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 125.50,
            paidHoursComplete = true
        )

        assertTrue(result.reliable)
        assertEquals(2_125.50, result.reductionRemunerationMonthly!!, 0.000001)
        assertEquals(2.0005, result.additionalPaidMinutes!!, 0.000000001)
    }

    @Test
    fun `empty overtime list never becomes zero until paid hours are explicitly complete`() {
        val unknown = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = null
        )
        assertFalse(unknown.reliable)
        assertNull(unknown.additionalPaidMinutes)
        assertTrue(unknown.warnings.any { it.contains("exhaustivité", ignoreCase = true) })

        val confirmed = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertTrue(confirmed.reliable)
        assertEquals(0.0, confirmed.additionalPaidMinutes!!, 0.0)
    }

    @Test
    fun `part time accepts matching complementary minutes and blocks mismatch`() {
        val matching = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("Complémentaires", 30L * 60_000L, 1.10)),
                complementaryMinutes = 30
            ),
            contractType = ContractTypeV2.PART_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertTrue(matching.reliable)
        assertEquals(30.0, matching.additionalPaidMinutes!!, 0.0)

        val mismatch = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("Complémentaires", 29L * 60_000L, 1.10)),
                complementaryMinutes = 30
            ),
            contractType = ContractTypeV2.PART_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertFalse(mismatch.reliable)
        assertNull(mismatch.additionalPaidMinutes)
        assertTrue(mismatch.warnings.any { it.contains("incohérence", ignoreCase = true) })
    }

    @Test
    fun `unsupported contract and invalid durations fail closed`() {
        val unsupported = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FORFAIT_DAYS,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertFalse(unsupported.reliable)
        assertNull(unsupported.additionalPaidMinutes)

        val negative = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("Invalide", -1L, 1.25))
            ),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertFalse(negative.reliable)
        assertNull(negative.additionalPaidMinutes)
        assertTrue(negative.warnings.any { it.contains("négative", ignoreCase = true) })
    }

    @Test
    fun `invalid gross or benefits in kind block remuneration`() {
        val gross = RgduPayrollInputBridgeV2.resolve(
            salary = salary(gross = Double.POSITIVE_INFINITY),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertFalse(gross.reliable)
        assertNull(gross.reductionRemunerationMonthly)

        val benefits = RgduPayrollInputBridgeV2.resolve(
            salary = salary(),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = Double.NaN,
            paidHoursComplete = true
        )
        assertFalse(benefits.reliable)
        assertNull(benefits.reductionRemunerationMonthly)
    }

    @Test
    fun `fractional minute reaches RGDU reference minimum without upstream rounding`() {
        val bridge = RgduPayrollInputBridgeV2.resolve(
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("Fraction", 90_030L, 1.25))
            ),
            contractType = ContractTypeV2.FULL_TIME,
            benefitsInKindGross = 0.0,
            paidHoursComplete = true
        )
        assertTrue(bridge.reliable)
        assertEquals(1.5005, bridge.additionalPaidMinutes!!, 0.000000001)

        val rgdu = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            EmployerGeneralReduction2026V2.Input(
                year = 2026,
                reductionRemunerationMonthly = bridge.reductionRemunerationMonthly!!,
                workforceBand = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
                contractType = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                additionalPaidMinutes = bridge.additionalPaidMinutes,
                fullMonthPresent = true,
                standardCommonLawCaseConfirmed = true
            )
        )

        assertTrue(rgdu.reliable)
        val expected = 12.02 * 35.0 * 52.0 / 12.0 + 12.02 * (1.5005 / 60.0)
        assertEquals(expected, rgdu.referenceMinimumMonthly!!, 0.000000001)
    }
}
