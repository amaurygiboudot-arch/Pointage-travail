package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerGeneralReduction2026V2Test {
    private fun input(
        gross: Double = 2000.0,
        band: EmployerWorkforceContributionsV2.Band? = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
        type: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0.0,
        fullMonth: Boolean? = true,
        standardCase: Boolean? = true
    ) = EmployerGeneralReduction2026V2.Input(
        year = 2026,
        reductionRemunerationMonthly = gross,
        workforceBand = band,
        contractType = type,
        contractualWeeklyMinutes = weeklyMinutes,
        additionalPaidMinutes = additionalMinutes,
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standardCase
    )

    @Test
    fun `urssaf example at least 50 gives 0 point 3178 and 635 point 60 euros`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input())

        assertTrue(result.reliable)
        assertEquals(0.3178, result.coefficient!!, 0.0000001)
        assertEquals(635.60, result.amount!!, 0.001)
        assertEquals(1823.0333333333333, result.referenceMinimumMonthly!!, 0.000001)
    }

    @Test
    fun `under 50 uses lower maximum coefficient`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(band = EmployerWorkforceContributionsV2.Band.FROM_11_TO_49)
        )

        assertEquals(0.3147, result.coefficient!!, 0.0000001)
        assertEquals(629.40, result.amount!!, 0.001)
    }

    @Test
    fun `coefficient is capped at legal maximum below smic`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross = 1500.0, band = EmployerWorkforceContributionsV2.Band.UNDER_11)
        )

        assertEquals(0.3981, result.coefficient!!, 0.0000001)
        assertEquals(597.15, result.amount!!, 0.001)
    }

    @Test
    fun `reduction becomes zero at three times reference minimum`() {
        val monthlySmic = 12.02 * 35.0 * 52.0 / 12.0
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross = 3.0 * monthlySmic)
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.coefficient!!, 0.0)
        assertEquals(0.0, result.amount!!, 0.0)
    }

    @Test
    fun `part time reference minimum is prorated and complementary hours are added without premium`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                gross = 1600.0,
                type = ContractTypeV2.PART_TIME,
                weeklyMinutes = 28 * 60,
                additionalMinutes = 120.0
            )
        )
        val expected = (12.02 * 35.0 * 52.0 / 12.0 * 0.8) + (12.02 * 2.0)

        assertEquals(expected, result.referenceMinimumMonthly!!, 0.000001)
        assertTrue(result.reliable)
    }

    @Test
    fun `fractional paid minutes are preserved in reference minimum`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes = 1040.5)
        )
        val expected = (12.02 * 35.0 * 52.0 / 12.0) + 12.02 * (1040.5 / 60.0)

        assertEquals(expected, result.referenceMinimumMonthly!!, 0.000001)
    }

    @Test
    fun `additional paid hours must be explicitly known even when zero`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(additionalMinutes = null))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("heures supplémentaires/complémentaires") })
    }

    @Test
    fun `incomplete month is fail closed until legal proration is available`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(fullMonth = false))

        assertFalse(result.reliable)
        assertNull(result.coefficient)
        assertTrue(result.warnings.any { it.contains("mois incomplet") })
    }

    @Test
    fun `non standard legal case never receives common law coefficient by default`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(standardCase = null))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("droit commun non confirmé") })
    }

    @Test
    fun `unsupported contract type remains blocked`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(type = ContractTypeV2.FORFAIT_DAYS)
        )

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("règle dédiée") })
    }
}
