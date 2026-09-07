package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlasturgieSeniorityGenericParityV2Test {
    @Test
    fun `generic engine preserves validated plasturgie coefficient 800 calculation`() {
        val reference = LocalDate.of(2026, 9, 30)
        val seniorityDate = LocalDate.of(2023, 1, 1)
        val old = PlasturgieSeniorityPremiumV2.calculate(
            idcc = "292",
            coefficient = 800,
            referenceDate = reference,
            confirmedSeniorityDate = seniorityDate,
            monthlyBaseGross = 2500.0,
            monthlyRttDifferential = 0.0
        )
        val generic = ConventionSeniorityPremiumV2.calculate(
            rules = PlasturgieSeniorityPremiumV2.genericRules(),
            idcc = "0292",
            classification = ConventionClassificationV2(coefficient = 800),
            referenceDate = reference,
            confirmedSeniorityDate = seniorityDate,
            actualMonthlyBaseGross = 2500.0,
            conventionalMinimumMonthlyGross = null,
            confirmedMonthlySupplement = 0.0
        )

        assertTrue(old.reliable)
        assertTrue(generic.reliable)
        assertEquals(old.rate ?: 0.0, generic.rate ?: 0.0, 0.000001)
        assertEquals(old.monthlyAmount ?: 0.0, generic.monthlyAmount ?: 0.0, 0.001)
    }

    @Test
    fun `generic engine does not assume extension before publication date`() {
        val generic = ConventionSeniorityPremiumV2.calculate(
            rules = PlasturgieSeniorityPremiumV2.genericRules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 800),
            referenceDate = LocalDate.of(2011, 9, 30),
            confirmedSeniorityDate = LocalDate.of(2000, 1, 1),
            actualMonthlyBaseGross = 2000.0,
            conventionalMinimumMonthlyGross = null,
            confirmedMonthlySupplement = 0.0
        )

        assertFalse(generic.reliable)
        assertTrue(generic.warnings.any { it.contains("05/01/2012") || it.contains("2012-01-05") })
    }
}
