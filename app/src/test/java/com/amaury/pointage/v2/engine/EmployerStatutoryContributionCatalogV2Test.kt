package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerStatutoryContributionCatalogV2Test {
    @Test
    fun `gross below PMSS uses full gross for all known lines`() {
        val result = EmployerStatutoryContributionCatalogV2.estimate(3000.0, 2026)

        assertEquals(63.30, result.lines.first { it.id == "employer_old_age_uncapped" }.employerAmount, 0.001)
        assertEquals(256.50, result.lines.first { it.id == "employer_old_age_capped" }.employerAmount, 0.001)
        assertEquals(9.00, result.lines.first { it.id == "employer_csa" }.employerAmount, 0.001)
        assertEquals(0.48, result.lines.first { it.id == "employer_social_dialogue" }.employerAmount, 0.001)
        assertEquals(329.28, result.knownEmployerContributions, 0.001)
        assertFalse(result.complete)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `old age capped contribution stops at 2026 PMSS`() {
        val result = EmployerStatutoryContributionCatalogV2.estimate(5000.0, 2026)
        val capped = result.lines.first { it.id == "employer_old_age_capped" }

        assertEquals(4005.0, capped.baseAmount, 0.001)
        assertEquals(4005.0 * 0.0855, capped.employerAmount, 0.001)
        assertEquals(105.50, result.lines.first { it.id == "employer_old_age_uncapped" }.employerAmount, 0.001)
        assertEquals(15.00, result.lines.first { it.id == "employer_csa" }.employerAmount, 0.001)
        assertEquals(0.80, result.lines.first { it.id == "employer_social_dialogue" }.employerAmount, 0.001)
    }

    @Test
    fun `provided reduced ceiling is reused for capped employer old age`() {
        val ceiling = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = java.time.LocalDate.of(2026, 1, 31),
                contractType = com.amaury.pointage.v2.model.ContractTypeV2.PART_TIME,
                contractualWeeklyMinutes = 28 * 60,
                complementaryMinutes = 0,
                entryDate = java.time.LocalDate.of(2020, 1, 1),
                unpaidAbsenceDays = 0,
                forfaitAnnualDays = null
            )
        )
        val result = EmployerStatutoryContributionCatalogV2.estimate(5000.0, 2026, ceiling)
        val capped = result.lines.first { it.id == "employer_old_age_capped" }

        assertEquals(3204.0, capped.baseAmount, 0.001)
        assertEquals(3204.0 * 0.0855, capped.employerAmount, 0.001)
    }
}
