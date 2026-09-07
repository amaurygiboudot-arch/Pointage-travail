package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionSeniorityPremiumV2Test {
    private val reference = LocalDate.of(2026, 9, 30)

    private fun percentageRule() = ConventionSeniorityPremiumV2.Rule(
        idcc = "1486",
        ruleId = "seniority-level-iii",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(level = "III"),
        basis = ConventionSeniorityPremiumV2.Basis.ACTUAL_MONTHLY_BASE,
        steps = listOf(
            ConventionSeniorityPremiumV2.Step(3, rate = 0.03),
            ConventionSeniorityPremiumV2.Step(6, rate = 0.06)
        ),
        source = "Légifrance KALI",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
    )

    @Test
    fun `generic percentage seniority works on non plasturgie idcc`() {
        val result = ConventionSeniorityPremiumV2.calculate(
            rules = listOf(percentageRule()),
            idcc = "01486",
            classification = ConventionClassificationV2(level = "iii", echelon = "2"),
            referenceDate = reference,
            confirmedSeniorityDate = LocalDate.of(2020, 1, 1),
            actualMonthlyBaseGross = 2500.0,
            conventionalMinimumMonthlyGross = null
        )

        assertTrue(result.reliable)
        assertEquals(6, result.stepYears)
        assertEquals(150.0, result.monthlyAmount ?: 0.0, 0.001)
    }

    @Test
    fun `fixed seniority amount needs no salary base`() {
        val fixed = percentageRule().copy(
            ruleId = "fixed",
            basis = ConventionSeniorityPremiumV2.Basis.FIXED_MONTHLY,
            steps = listOf(ConventionSeniorityPremiumV2.Step(2, fixedMonthlyAmount = 45.0))
        )
        val result = ConventionSeniorityPremiumV2.calculate(
            rules = listOf(fixed),
            idcc = "1486",
            classification = ConventionClassificationV2(level = "III"),
            referenceDate = reference,
            confirmedSeniorityDate = LocalDate.of(2020, 1, 1),
            actualMonthlyBaseGross = null,
            conventionalMinimumMonthlyGross = null
        )

        assertTrue(result.reliable)
        assertEquals(45.0, result.monthlyAmount ?: 0.0, 0.001)
    }

    @Test
    fun `missing confirmed seniority date blocks amount`() {
        val result = ConventionSeniorityPremiumV2.calculate(
            rules = listOf(percentageRule()),
            idcc = "1486",
            classification = ConventionClassificationV2(level = "III"),
            referenceDate = reference,
            confirmedSeniorityDate = null,
            actualMonthlyBaseGross = 2500.0,
            conventionalMinimumMonthlyGross = null
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("date d'ancienneté", ignoreCase = true) })
    }

    @Test
    fun `non extended seniority rule is not applied without company proof`() {
        val result = ConventionSeniorityPremiumV2.calculate(
            rules = listOf(percentageRule().copy(extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED)),
            idcc = "1486",
            classification = ConventionClassificationV2(level = "III"),
            referenceDate = reference,
            confirmedSeniorityDate = LocalDate.of(2020, 1, 1),
            actualMonthlyBaseGross = 2500.0,
            conventionalMinimumMonthlyGross = null
        )

        assertFalse(result.applicable)
        assertFalse(result.reliable)
    }
}
