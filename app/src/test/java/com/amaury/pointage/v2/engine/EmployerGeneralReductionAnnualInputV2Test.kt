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
        warnings: List<String> = emptyList()
    ) = EmployerGeneralReductionAnnualContextV2.Snapshot(
        fullCalendarYearPresent = fullYear,
        standardCommonLawCaseConfirmed = standard,
        homogeneousAnnualParametersConfirmed = homogeneous,
        source = source,
        reliable = reliable,
        warnings = warnings
    )

    private fun month(
        month: Int,
        remuneration: Double = 2_000.0,
        additionalMinutes: Double = 0.0,
        band: EmployerWorkforceContributionsV2.Band? = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
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
            band != null && contractType != null && weeklyMinutes != null
        ) {
            EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                EmployerGeneralReduction2026V2.Input(
                    year = period.year,
                    reductionRemunerationMonthly = remuneration,
                    workforceBand = band,
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
            workforceBand = band,
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

    private fun fullYear(
        additionalMinutes: Double = 0.0
    ) = (1..12).map { month(it, additionalMinutes = additionalMinutes) }

    @Test
    fun `twelve reliable homogeneous months build annual input and advances`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            year = 2026,
            months = fullYear(additionalMinutes = 0.5),
            annualContext = context()
        )

        assertTrue(result.reliable)
        val input = result.annualInput!!
        assertEquals(24_000.0, input.annualReductionRemuneration, 0.000001)
        assertEquals(6.0, input.additionalPaidMinutesAnnual!!, 0.000001)
        assertEquals(EmployerWorkforceContributionsV2.Band.AT_LEAST_50, input.workforceBand)
        assertEquals(ContractTypeV2.FULL_TIME, input.contractType)
        assertEquals(35 * 60, input.contractualWeeklyMinutes)
        assertTrue(input.fullCalendarYearPresent == true)
        assertTrue(input.standardCommonLawCaseConfirmed == true)
        assertTrue(input.homogeneousAnnualParametersConfirmed == true)
        assertEquals((1..12).toList(), result.monthlyAdvances.map { it.month })
    }

    @Test
    fun `fractional paid minutes are preserved across the year`() {
        val months = (1..12).map { month(it, additionalMinutes = 0.125) }

        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())

        assertTrue(result.reliable)
        assertEquals(1.5, result.annualInput!!.additionalPaidMinutesAnnual!!, 0.0000001)
    }

    @Test
    fun `missing month is never converted to zero`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            fullYear().dropLast(1),
            context()
        )

        assertFalse(result.reliable)
        assertNull(result.annualInput)
        assertTrue(result.warnings.any { it.contains("2026-12") })
    }

    @Test
    fun `duplicate month blocks aggregation`() {
        val months = fullYear() + month(12)

        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())

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
        val months = fullYear().map {
            if (it.period.monthValue == 4) it.copy(paidHoursComplete = null) else it
        }

        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context())

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("exhaustivité", ignoreCase = true) })
    }

    @Test
    fun `actual monthly workforce variation blocks standard annual case`() {
        val months = fullYear().map {
            if (it.period.monthValue == 7) {
                val changed = month(
                    month = 7,
                    band = EmployerWorkforceContributionsV2.Band.FROM_11_TO_49
                )
                changed
            } else it
        }

        val result = EmployerGeneralReductionAnnualInputV2.resolve(2026, months, context(homogeneous = true))

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("effectif varie", ignoreCase = true) })
    }

    @Test
    fun `actual weekly duration variation blocks standard annual case`() {
        val months = fullYear().map {
            if (it.period.monthValue == 8) {
                month(month = 8, weeklyMinutes = 30 * 60)
            } else it
        }

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
            2026,
            fullYear(),
            context(homogeneous = null, warnings = listOf("stabilité à confirmer"))
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("stabilité", ignoreCase = true) })
    }

    @Test
    fun `annual source is mandatory even if flags are true`() {
        val result = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            fullYear(),
            context(source = "")
        )

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
    fun `prepared annual input can feed annual entitlement and regularization`() {
        val prepared = EmployerGeneralReductionAnnualInputV2.resolve(
            2026,
            fullYear(additionalMinutes = 0.5),
            context()
        )

        assertTrue(prepared.reliable)
        val annual = EmployerGeneralReductionAnnual2026V2.calculate(prepared.annualInput!!)
        assertTrue(annual.reliable)
        assertNotNull(annual.amount)

        val regularization = EmployerGeneralReductionAnnualRegularizationV2.resolve(
            2026,
            annual,
            prepared.monthlyAdvances
        )
        assertTrue(regularization.reliable)
        assertNotNull(regularization.adjustment)
    }
}
