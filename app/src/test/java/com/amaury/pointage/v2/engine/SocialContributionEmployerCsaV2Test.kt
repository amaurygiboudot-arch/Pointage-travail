package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SocialContributionEmployerCsaV2Test {
    private fun ceiling() = SocialSecurityCeilingV2.calculate(
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
    fun `CSA is employer only at 0 point 30 percent in 2026`() {
        val gross = 2500.0
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = gross,
            year = 2026,
            ceiling = ceiling(),
            alsaceMoselleLocalRegime = false
        )
        val csa = estimate.lines.first { it.id == "csa_employer" }

        assertEquals(0.0, csa.rate, 0.000001)
        assertEquals(0.0, csa.employeeAmount, 0.001)
        assertEquals(0.0030, csa.employerRate, 0.000001)
        assertEquals(7.50, csa.employerAmount, 0.001)
        assertTrue(estimate.employerContributions >= csa.employerAmount)
    }
}
