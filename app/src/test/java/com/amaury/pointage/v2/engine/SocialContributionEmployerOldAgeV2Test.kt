package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SocialContributionEmployerOldAgeV2Test {
    private fun ceiling(gross: Double): SocialSecurityCeilingV2.Snapshot = SocialSecurityCeilingV2.calculate(
        SocialSecurityCeilingV2.Input(
            year = 2026,
            referenceDate = LocalDate.of(2026, 1, 31),
            contractType = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 35 * 60,
            complementaryMinutes = 0,
            entryDate = LocalDate.of(2020, 1, 1),
            unpaidAbsenceDays = 0,
            forfaitAnnualDays = null
        )
    )

    @Test
    fun `employer old age uses 2 point 11 and 8 point 55 below PMSS`() {
        val gross = 3000.0
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = gross,
            year = 2026,
            ceiling = ceiling(gross),
            alsaceMoselleLocalRegime = false
        )
        val uncapped = estimate.lines.first { it.id == "old_age_uncapped" }
        val capped = estimate.lines.first { it.id == "old_age_capped" }

        assertEquals(0.0211, uncapped.employerRate, 0.000001)
        assertEquals(0.0855, capped.employerRate, 0.000001)
        assertEquals(gross * 0.0211, uncapped.employerAmount, 0.001)
        assertEquals(gross * 0.0855, capped.employerAmount, 0.001)
        assertEquals(gross * (0.0211 + 0.0855 + 0.0030 + 0.00016), estimate.employerContributions, 0.001)
    }

    @Test
    fun `employer capped old age stops at 2026 PMSS while uncapped continues`() {
        val gross = 5000.0
        val pmss = 4005.0
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = gross,
            year = 2026,
            ceiling = ceiling(gross),
            alsaceMoselleLocalRegime = false
        )
        val uncapped = estimate.lines.first { it.id == "old_age_uncapped" }
        val capped = estimate.lines.first { it.id == "old_age_capped" }

        assertEquals(gross, uncapped.baseAmount, 0.001)
        assertEquals(pmss, capped.baseAmount, 0.001)
        assertEquals(gross * 0.0211, uncapped.employerAmount, 0.001)
        assertEquals(pmss * 0.0855, capped.employerAmount, 0.001)
        assertEquals(gross * (0.0211 + 0.0030 + 0.00016) + pmss * 0.0855, estimate.employerContributions, 0.001)
    }
}
