package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerGeneralReductionAnnualContextV2Test {
    private fun record(
        id: String = "annual-2026",
        year: Int = 2026,
        fullYear: Boolean = true,
        standardCase: Boolean = true,
        homogeneous: Boolean? = true,
        source: String = "DSN annuelle 2026",
        confirmedBand: EmployerWorkforceContributionsV2.Band? = null,
        confirmedContractType: ContractTypeV2? = null,
        confirmedWeeklyMinutes: Int? = null
    ) = EmployerGeneralReductionAnnualContextV2.Record(
        id = id,
        year = year,
        fullCalendarYearPresent = fullYear,
        standardCommonLawCaseConfirmed = standardCase,
        homogeneousAnnualParametersConfirmed = homogeneous,
        source = source,
        confirmedWorkforceBand = confirmedBand,
        confirmedContractType = confirmedContractType,
        confirmedContractualWeeklyMinutes = confirmedWeeklyMinutes
    )

    @Test
    fun `missing annual context stays unknown`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(emptyList(), 2026)

        assertFalse(result.reliable)
        assertNull(result.fullCalendarYearPresent)
        assertNull(result.homogeneousAnnualParametersConfirmed)
    }

    @Test
    fun `explicit false facts are preserved`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(fullYear = false, standardCase = false, homogeneous = false)),
            2026
        )

        assertTrue(result.reliable)
        assertFalse(result.fullCalendarYearPresent!!)
        assertFalse(result.standardCommonLawCaseConfirmed!!)
        assertFalse(result.homogeneousAnnualParametersConfirmed!!)
    }

    @Test
    fun `unknown stability remains unknown and visible`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(homogeneous = null)),
            2026
        )

        assertTrue(result.reliable)
        assertNull(result.homogeneousAnnualParametersConfirmed)
        assertTrue(result.warnings.any { it.contains("stabilité", ignoreCase = true) })
    }

    @Test
    fun `homogeneous flag without exact historical parameters stays visible and unusable downstream`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(homogeneous = true)),
            2026
        )

        assertTrue(result.reliable)
        assertNull(result.confirmedWorkforceBand)
        assertNull(result.confirmedContractType)
        assertNull(result.confirmedContractualWeeklyMinutes)
        assertTrue(result.warnings.any { it.contains("tranche d'effectif", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("type de contrat", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("durée contractuelle", ignoreCase = true) })
    }

    @Test
    fun `exact historical annual parameters are preserved without warning`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(
                record(
                    homogeneous = true,
                    confirmedBand = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
                    confirmedContractType = ContractTypeV2.FULL_TIME,
                    confirmedWeeklyMinutes = 35 * 60
                )
            ),
            2026
        )

        assertTrue(result.reliable)
        assertTrue(result.warnings.isEmpty())
        assertEquals(EmployerWorkforceContributionsV2.Band.AT_LEAST_50, result.confirmedWorkforceBand)
        assertEquals(ContractTypeV2.FULL_TIME, result.confirmedContractType)
        assertEquals(35 * 60, result.confirmedContractualWeeklyMinutes)
    }

    @Test
    fun `duplicate year blocks annual context`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(id = "a"), record(id = "b")),
            2026
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("plusieurs", ignoreCase = true) })
    }

    @Test
    fun `blank source anywhere blocks automatic annual context`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(year = 2025, source = ""), record()),
            2026
        )

        assertFalse(result.reliable)
        assertNull(result.source)
        assertTrue(result.warnings.any { it.contains("incomplet", ignoreCase = true) })
    }

    @Test
    fun `invalid confirmed weekly duration blocks annual context`() {
        val result = EmployerGeneralReductionAnnualContextV2.resolve(
            listOf(record(confirmedWeeklyMinutes = -1)),
            2026
        )

        assertFalse(result.reliable)
        assertNull(result.confirmedContractualWeeklyMinutes)
    }
}
