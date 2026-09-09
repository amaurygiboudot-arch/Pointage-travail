package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerGeneralReductionAnnual2026V2Test {
    private fun annualInput(
        remuneration: Double = 24_000.0,
        band: EmployerWorkforceContributionsV2.Band? = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
        type: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0.0,
        fullYear: Boolean? = true,
        standardCase: Boolean? = true
    ) = EmployerGeneralReductionAnnual2026V2.Input(
        year = 2026,
        annualReductionRemuneration = remuneration,
        workforceBand = band,
        contractType = type,
        contractualWeeklyMinutes = weeklyMinutes,
        additionalPaidMinutesAnnual = additionalMinutes,
        fullCalendarYearPresent = fullYear,
        standardCommonLawCaseConfirmed = standardCase
    )

    @Test
    fun `annual standard case reuses the legal 2026 coefficient without monthly amount rounding`() {
        val result = EmployerGeneralReductionAnnual2026V2.calculate(annualInput())

        assertTrue(result.reliable)
        assertEquals(0.3178, result.coefficient!!, 0.0000001)
        assertEquals(7_627.20, result.amount!!, 0.001)
        assertEquals(21_876.40, result.referenceMinimumAnnual!!, 0.000001)
        assertEquals(65_629.20, result.thresholdAnnual!!, 0.000001)
    }

    @Test
    fun `part time annual reference minimum is prorated`() {
        val result = EmployerGeneralReductionAnnual2026V2.calculate(
            annualInput(
                remuneration = 14_000.0,
                type = ContractTypeV2.PART_TIME,
                weeklyMinutes = 17 * 60 + 30
            )
        )

        assertTrue(result.reliable)
        assertEquals(10_938.20, result.referenceMinimumAnnual!!, 0.000001)
    }

    @Test
    fun `fractional additional minute is preserved in annual reference minimum`() {
        val base = EmployerGeneralReductionAnnual2026V2.calculate(annualInput(additionalMinutes = 0.0))
        val halfMinute = EmployerGeneralReductionAnnual2026V2.calculate(annualInput(additionalMinutes = 0.5))

        assertTrue(base.reliable)
        assertTrue(halfMinute.reliable)
        assertEquals(12.02 * 0.5 / 60.0, halfMinute.referenceMinimumAnnual!! - base.referenceMinimumAnnual!!, 0.000001)
    }

    @Test
    fun `incomplete calendar year stays blocked until a dedicated proration rule exists`() {
        val result = EmployerGeneralReductionAnnual2026V2.calculate(annualInput(fullYear = false))

        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any { it.contains("année civile complète", ignoreCase = true) })
    }

    @Test
    fun `unsupported contract type remains fail closed`() {
        val result = EmployerGeneralReductionAnnual2026V2.calculate(
            annualInput(type = ContractTypeV2.FORFAIT_DAYS)
        )

        assertFalse(result.reliable)
        assertNull(result.coefficient)
        assertTrue(result.warnings.any { it.contains("type de contrat", ignoreCase = true) })
    }

    @Test
    fun `regularization adds a positive balance when annual entitlement exceeds advances`() {
        val annual = EmployerGeneralReductionAnnual2026V2.Result(
            amount = 1_500.0,
            coefficient = 0.1,
            referenceMinimumAnnual = 20_000.0,
            thresholdAnnual = 60_000.0,
            reliable = true,
            warnings = emptyList()
        )
        val advances = (1..12).map { EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(it, 100.0) }

        val result = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, advances)

        assertTrue(result.reliable)
        assertEquals(1_200.0, result.advancesTotal!!, 0.001)
        assertEquals(300.0, result.adjustment!!, 0.001)
    }

    @Test
    fun `regularization can produce a negative recovery`() {
        val annual = EmployerGeneralReductionAnnual2026V2.Result(
            amount = 900.0,
            coefficient = 0.1,
            referenceMinimumAnnual = 20_000.0,
            thresholdAnnual = 60_000.0,
            reliable = true,
            warnings = emptyList()
        )
        val advances = (1..12).map { EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(it, 100.0) }

        val result = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, advances)

        assertTrue(result.reliable)
        assertEquals(-300.0, result.adjustment!!, 0.001)
    }

    @Test
    fun `missing monthly advance is not silently treated as zero`() {
        val annual = EmployerGeneralReductionAnnual2026V2.Result(
            amount = 1_000.0,
            coefficient = 0.1,
            referenceMinimumAnnual = 20_000.0,
            thresholdAnnual = 60_000.0,
            reliable = true,
            warnings = emptyList()
        )
        val advances = (1..11).map { EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(it, 80.0) }

        val result = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, advances)

        assertFalse(result.reliable)
        assertNull(result.adjustment)
        assertTrue(result.warnings.any { it.contains("mois manquants", ignoreCase = true) })
    }

    @Test
    fun `duplicate month blocks annual regularization`() {
        val annual = EmployerGeneralReductionAnnual2026V2.Result(
            amount = 1_000.0,
            coefficient = 0.1,
            referenceMinimumAnnual = 20_000.0,
            thresholdAnnual = 60_000.0,
            reliable = true,
            warnings = emptyList()
        )
        val advances = (1..12).map { EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(it, 80.0) } +
            EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(12, 10.0)

        val result = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, advances)

        assertFalse(result.reliable)
        assertNull(result.advancesTotal)
        assertTrue(result.warnings.any { it.contains("même mois", ignoreCase = true) })
    }

    @Test
    fun `blocked annual entitlement propagates its reason`() {
        val annual = EmployerGeneralReductionAnnual2026V2.Result(
            amount = null,
            coefficient = null,
            referenceMinimumAnnual = null,
            thresholdAnnual = null,
            reliable = false,
            warnings = listOf("RGDU annuelle : contexte à confirmer")
        )
        val advances = (1..12).map { EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(it, 0.0) }

        val result = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, advances)

        assertFalse(result.reliable)
        assertTrue(result.warnings.contains("RGDU annuelle : contexte à confirmer"))
    }
}
