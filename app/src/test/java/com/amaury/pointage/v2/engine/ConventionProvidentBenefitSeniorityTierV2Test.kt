package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProvidentBenefitSeniorityTierV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun rule(
        id: String,
        minimumSeniorityMonths: Int,
        coefficient: Double,
        articleId: String
    ) = ConventionProvidentBenefitV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        aniCategories = emptySet(),
        minimumSeniorityMonths = minimumSeniorityMonths,
        guarantees = listOf(
            ConventionProvidentBenefitV2.Guarantee(
                family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                label = "Capital décès",
                formula = ConventionProvidentBenefitV2.Formula(
                    basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                    coefficient = coefficient
                ),
                socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
                evidenceArticleIds = setOf(articleId)
            )
        ),
        source = "Légifrance KALI test",
        conventionScopeKey = "KALITEXT000000009300",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private val baseRule = rule(
        id = "KALI-BENEFIT-BASE",
        minimumSeniorityMonths = 0,
        coefficient = 1.0,
        articleId = "KALIARTI000000009301"
    )
    private val twelveMonthRule = rule(
        id = "KALI-BENEFIT-12M",
        minimumSeniorityMonths = 12,
        coefficient = 2.0,
        articleId = "KALIARTI000000009302"
    )

    private fun resolve(seniorityMonths: Int) = ConventionProvidentBenefitV2.resolve(
        rules = listOf(baseRule, twelveMonthRule),
        idcc = "292",
        referenceDate = date,
        classification = classification,
        professionalStatus = "NON_CADRE",
        protectionCategory = null,
        seniorityMonths = seniorityMonths
    )

    @Test
    fun `apres douze mois le palier eligible le plus eleve remplace le palier de base`() {
        val result = resolve(18)

        assertTrue(result.reliable)
        assertEquals(1, result.guarantees.size)
        assertEquals(2.0, result.guarantees.single().formula.coefficient!!, 0.000001)
        assertEquals(listOf("KALI-BENEFIT-12M"), result.selectedRules.map { it.ruleId })
    }

    @Test
    fun `avant douze mois le palier de base reste selectionne`() {
        val result = resolve(6)

        assertTrue(result.reliable)
        assertEquals(1, result.guarantees.size)
        assertEquals(1.0, result.guarantees.single().formula.coefficient!!, 0.000001)
        assertEquals(listOf("KALI-BENEFIT-BASE"), result.selectedRules.map { it.ruleId })
    }
}
