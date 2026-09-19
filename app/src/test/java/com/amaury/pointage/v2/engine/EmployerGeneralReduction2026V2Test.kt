package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerGeneralReduction2026V2Test {
    private fun rateContext(
        regime: EmployerGeneralReductionRateContext2026V2.HousingContributionRegime =
            EmployerGeneralReductionRateContext2026V2.HousingContributionRegime.L813_5_2,
        rateSum: Double = 0.4021
    ): EmployerGeneralReductionRateContext2026V2.Snapshot =
        EmployerGeneralReductionRateContext2026V2.resolve(
            records = listOf(
                EmployerGeneralReductionRateContext2026V2.Record(
                    id = "rate",
                    housingContributionRegime = regime,
                    eligibleEmployerRateSum = rateSum,
                    effectiveFrom = YearMonth.of(2026, 1),
                    source = "DSN / paramétrage paie 2026"
                )
            ),
            period = YearMonth.of(2026, 9)
        )

    private fun input(
        gross: Double = 2000.0,
        context: EmployerGeneralReductionRateContext2026V2.Snapshot? = rateContext(),
        type: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0.0,
        fullMonth: Boolean? = true,
        standardCase: Boolean? = true
    ) = EmployerGeneralReduction2026V2.Input(
        year = 2026,
        reductionRemunerationMonthly = gross,
        rateContext = context,
        contractType = type,
        contractualWeeklyMinutes = weeklyMinutes,
        additionalPaidMinutes = additionalMinutes,
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standardCase
    )

    @Test
    fun `urssaf example with L813 5 second regime gives 0 point 3178 and 635 point 60 euros`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input())

        assertTrue(result.reliable)
        assertEquals(0.3178, result.coefficient!!, 0.0000001)
        assertEquals(635.60, result.amount!!, 0.001)
        assertEquals(1823.0333333333333, result.referenceMinimumMonthly!!, 0.000001)
    }

    @Test
    fun `L813 5 first regime uses lower legal coefficient regardless of raw workforce`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                context = rateContext(
                    regime = EmployerGeneralReductionRateContext2026V2.HousingContributionRegime.L813_5_1,
                    rateSum = 0.3981
                )
            )
        )

        assertEquals(0.3147, result.coefficient!!, 0.0000001)
        assertEquals(629.40, result.amount!!, 0.001)
    }

    @Test
    fun `coefficient is capped at confirmed eligible employer rates`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross = 1500.0, context = rateContext(rateSum = 0.3500))
        )

        assertTrue(result.reliable)
        assertEquals(0.3500, result.coefficient!!, 0.0000001)
        assertEquals(525.00, result.amount!!, 0.001)
    }

    @Test
    fun `missing rate context never falls back to workforce assumptions`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(context = null))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("régime de contribution logement", ignoreCase = true) })
    }

    @Test
    fun `unreliable rate context propagates its reason`() {
        val result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                context = EmployerGeneralReductionRateContext2026V2.Snapshot(
                    tDelta = null,
                    maximumCoefficient = null,
                    source = null,
                    reliable = false,
                    warnings = listOf("Contexte RGDU 2026 à confirmer")
                )
            )
        )

        assertFalse(result.reliable)
        assertNull(result.coefficient)
        assertTrue(result.warnings.contains("Contexte RGDU 2026 à confirmer"))
    }

    @Test
    fun `coefficient becomes zero at three times reference minimum`() {
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
