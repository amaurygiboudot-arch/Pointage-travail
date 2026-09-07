package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SocialContributionEmployerDialogueV2Test {
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
    fun `dialogue social is employer only at 0 point 016 percent in 2026`() {
        val gross = 2500.0
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = gross,
            year = 2026,
            ceiling = ceiling(),
            alsaceMoselleLocalRegime = false
        )
        val line = estimate.lines.first { it.id == "social_dialogue_employer" }

        assertEquals(0.0, line.rate, 0.000001)
        assertEquals(0.0, line.employeeAmount, 0.001)
        assertEquals(0.00016, line.employerRate, 0.000001)
        assertEquals(0.40, line.employerAmount, 0.001)
    }
}
