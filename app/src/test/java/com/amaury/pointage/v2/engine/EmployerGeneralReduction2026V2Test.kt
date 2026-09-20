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
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment? =
            EmployerWorkforceContributionsV2.FnalTreatment.UNCAPPED_0_5_PERCENT,
        type: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0.0,
        fullMonth: Boolean? = true,
        standardCase: Boolean? = true
    ) = EmployerGeneralReduction2026V2.Input(
        year = 2026,
        reductionRemunerationMonthly = gross,
        fnalTreatment = fnalTreatment,
        contractType = type,
        contractualWeeklyMinutes = weeklyMinutes,
        additionalPaidMinutes = additionalMinutes,
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standardCase
    )

    @Test
    fun `uncapped 0 point 5 percent regime gives 0 point 3178 and 635 point 60 euros`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input())

        assertTrue(result.reliable)
        assertEquals(0.3178, result.coefficient!!, 0.0000001)
        assertEquals(635.60, result.amount!!, 0.001)
        assertEquals(1823.0333333333333, result.referenceMinimumMonthly!!, 0.000001)
    }

    @Test
    fun `capped 0 point 1 percent regime uses lower delta without workforce inference`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(fnalTreatment = EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT)
        )

        assertTrue(result.reliable)
        assertEquals(0.3147, result.coefficient!!, 0.0000001)
        assertEquals(629.40, result.amount!!, 0.001)
    }

    @Test
    fun `coefficient is capped at legal maximum for capped 0 point 1 percent regime`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                gross = 1500.0,
                fnalTreatment = EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT
            )
        )

        assertEquals(0.3981, result.coefficient!!, 0.0000001)
        assertEquals(597.15, result.amount!!, 0.001)
    }

    @Test
    fun `missing FNAL treatment is fail closed and never inferred`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(fnalTreatment = null))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertNull(result.coefficient)
        assertTrue(result.warnings.any { it.contains("FNAL/logement") })
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
    fun `fractional paid minutes are preserved without upstream rounding`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes = 0.5)
        )
        val expected = (12.02 * 35.0 * 52.0 / 12.0) + (12.02 * 0.5 / 60.0)

        assertTrue(result.reliable)
        assertEquals(expected, result.referenceMinimumMonthly!!, 0.000000001)
    }

    @Test
    fun `additional paid hours must be explicitly known even when zero`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(additionalMinutes = null))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("heures supplémentaires/complémentaires") })
    }

    @Test
    fun `non finite additional paid minutes are rejected`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes = Double.POSITIVE_INFINITY)
        )

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
