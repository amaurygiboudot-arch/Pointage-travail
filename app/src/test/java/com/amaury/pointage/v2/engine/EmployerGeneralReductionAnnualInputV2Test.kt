package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerGeneralReductionAnnualInputV2Test {
    private fun context(
        fullYear: Boolean? = true,
        standard: Boolean? = true,
        homogeneous: Boolean? = true,
        source: String? = "DSN annuelle 2026",
        reliable: Boolean = true,
        warnings: List<String> = emptyList(),
        confirmedFnal: EmployerWorkforceContributionsV2.FnalTreatment? =
            EmployerWorkforceContributionsV2.FnalTreatment.UNCAPPED_0_5_PERCENT,
        confirmedContractType: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        confirmedWeeklyMinutes: Int? = 35 * 60
    ) = EmployerGeneralReductionAnnualContextV2.Snapshot(
        fullCalendarYearPresent = fullYear,
        standardCommonLawCaseConfirmed = standard,
        homogeneousAnnualParametersConfirmed = homogeneous,
        source = source,
        reliable = reliable,
        warnings = warnings,
        confirmedFnalTreatment = confirmedFnal,
        confirmedContractType = confirmedContractType,
        confirmedContractualWeeklyMinutes = confirmedWeeklyMinutes
    )

    private fun month(
        month: Int,
        remuneration: Double = 2_000.0,
        additionalMinutes: Double = 0.0,
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment? =
            EmployerWorkforceContributionsV2.FnalTreatment.UNCAPPED_0_5_PERCENT,
        contractType: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        fullMonth: Boolean? = true,
        standard: Boolean? = true,
        paidHoursComplete: Boolean? = true,
        source: String? = "HoraTrack RGDU 2026 / mois $month",
        reliable: Boolean = true,
        warnings: List<String> = emptyList(),
        period: YearMonth = YearMonth.of(2026, month),
        advanceOverride: Double? = null
    ): EmployerGeneralReductionAnnualInputV2.Month {
        val automatic = if (
            remuneration.isFinite() && remuneration >= 0.0 &&
            fnalTreatment != null && contractType != null && weeklyMinutes != null
        ) {
            EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                EmployerGeneralReduction2026V2.Input(
                    year = period.year,
                    reductionRemunerationMonthly = remuneration,
                    fnalTreatment = fnalTreatment,
                    contractType = contractType,
                    contractualWeeklyMinutes = weeklyMinutes,
                    additionalPaidMinutes = additionalMinutes,
                    fullMonthPresent = fullMonth,
                    standardCommonLawCaseConfirmed = standard
                )
            ).amount
        } else null

        return EmployerGeneralReductionAnnualInputV2.Month(
            period = period,
            reductionRemunerationMonthly = remuneration,
            additionalPaidMinutes = additionalMinutes,
            automaticRgduAdvanceAmount = advanceOverride ?: automatic,
            fnalTreatment = fnalTreatment,
            contractType = contractType,
            contractualWeeklyMinutes = weeklyMinutes,
            fullMonthPresent = fullMonth,
            standardCommonLawCaseConfirmed = standard,
            paidHoursComplete = paidHoursComplete,
            source = source,
            reliable = reliable,
            warnings = warnings
        )
    }

    private fun fullYear(additionalMinutes: Double = 0.0) =
        (1..12).map { month(it, additionalMinutes = additionalMinutes) }

    @Test
    fun `twelve reliable homogeneous months build annual input and advances`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear(0.5), context())
        assertTrue(result.reliable)
        val input = result.annualInput!!
        assertEquals(24_000.0, input.annualReductionRemuneration, 0.000001)
        assertEquals(6.0, input.additionalPaidMinutesAnnual!!, 0.000001)
        assertEquals(EmployerWorkforceContributionsV2.FnalTreatment.UNCAPPED_0_5_PERCENT, input.fnalTreatment)
        assertEquals(ContractTypeV2.FULL_TIME, input.contractType)
        assertEquals(35 * 60, input.contractualWeeklyMinutes)
        assertEquals((1..12).toList(), result.monthlyAdvances.map { it.month })
    }

    @Test
    fun `fractional paid minutes are preserved across the year`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            (1..12).map { month(it, additionalMinutes = 0.125) },
            context()
        )
        assertTrue(result.reliable)
        assertEquals(1.5, result.annualInput!!.additionalPaidMinutesAnnual!!, 0.0000001)
    }

    @Test
    fun `missing month is never converted to zero`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear().dropLast(1), context())
        assertFalse(result.reliable)
        assertNull(result.annualInput)
        assertTrue(result.warnings.any { it.contains("2026-12") })
    }

    @Test
    fun `duplicate month blocks aggregation`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear() + month(12), context())
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("même mois", ignoreCase = true) })
    }

    @Test
    fun `month from another year blocks aggregation`() {
        val months = fullYear().dropLast(1) + month(12, period = YearMonth.of(2025, 12))
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("hors année", ignoreCase = true) })
    }

    @Test
    fun `unknown paid hours completeness blocks annual aggregation`() {
        val months = fullYear().map { if (it.period.monthValue == 4) it.copy(paidHoursComplete = null) else it }
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("exhaustivité", ignoreCase = true) })
    }

    @Test
    fun `actual FNAL treatment variation blocks standard annual case`() {
        val months = fullYear().map {
            if (it.period.monthValue == 7) {
                month(7, fnalTreatment = EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT)
            } else it
        }
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context(homogeneous = true))
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("FNAL/logement varie", ignoreCase = true) })
    }

    @Test
    fun `actual weekly duration variation blocks standard annual case`() {
        val months = fullYear().map { if (it.period.monthValue == 8) month(8, weeklyMinutes = 30 * 60) else it }
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context(homogeneous = true))
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("durée contractuelle", ignoreCase = true) })
    }

    @Test
    fun `stale automatic advance is rejected even when other facts are valid`() {
        val months = fullYear().map {
            if (it.period.monthValue == 5) it.copy(automaticRgduAdvanceAmount = it.automaticRgduAdvanceAmount!! + 1.0) else it
        }
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("ne correspond plus", ignoreCase = true) })
    }

    @Test
    fun `annual homogeneity confirmation must be explicit`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026, fullYear(), context(homogeneous = null, warnings = listOf("stabilité à confirmer"))
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("stabilité", ignoreCase = true) })
    }

    @Test
    fun `annual source is mandatory even if flags are true`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear(), context(source = ""))
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("source", ignoreCase = true) })
    }

    @Test
    fun `monthly warning is not silently ignored`() {
        val months = fullYear().map {
            if (it.period.monthValue == 3) it.copy(warnings = listOf("preuve mensuelle partielle")) else it
        }
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains("preuve mensuelle partielle"))
    }

    @Test
    fun `missing exact annual parameter snapshot blocks even when homogeneity flag is true`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            fullYear(),
            context(homogeneous = true, confirmedFnal = null, confirmedContractType = null, confirmedWeeklyMinutes = null)
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("annuel", ignoreCase = true) || it.contains("annuelle", ignoreCase = true) })
    }

    @Test
    fun `confirmed annual FNAL treatment must match all monthly facts`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            fullYear(),
            context(confirmedFnal = EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT)
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("FNAL/logement", ignoreCase = true) && it.contains("ne correspond pas", ignoreCase = true) })
    }

    @Test
    fun `confirmed annual contract type must match all monthly facts`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026, fullYear(), context(confirmedContractType = ContractTypeV2.PART_TIME)
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("type de contrat", ignoreCase = true) && it.contains("ne correspond pas", ignoreCase = true) })
    }

    @Test
    fun `confirmed annual weekly duration must match all monthly facts`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear(), context(confirmedWeeklyMinutes = 30 * 60))
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("durée contractuelle", ignoreCase = true) && it.contains("ne correspond pas", ignoreCase = true) })
    }

    @Test
    fun `prepared annual input can feed annual entitlement and regularization`() {
        val prepared = EmployerGeneralReductionAnnualInputV2.resolve(2026, fullYear(0.5), context())
        assertTrue(prepared.reliable)
        val annual = EmployerGeneralReductionAnnual2026V2.calculate(prepared.annualInput!!)
        assertTrue(annual.reliable)
        assertNotNull(annual.amount)
        val regularization = EmployerGeneralReductionAnnualRegularizationV2.resolve(2026, annual, prepared.monthlyAdvances)
        assertTrue(regularization.reliable)
        assertNotNull(regularization.adjustment)
    }
}
