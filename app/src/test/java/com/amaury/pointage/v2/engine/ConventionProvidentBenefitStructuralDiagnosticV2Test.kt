package com.amaury.pointage.v2.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProvidentBenefitStructuralDiagnosticV2Test {
    @Test
    fun `règle décès minimale expose chaque garde structurel`() {
        val guarantee = ConventionProvidentBenefitV2.Guarantee(
            family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            label = "Capital décès",
            formula = ConventionProvidentBenefitV2.Formula(
                basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                coefficient = 1.0
            ),
            socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
            evidenceArticleIds = setOf("KALIARTI000000000001")
        )
        val rule = ConventionProvidentBenefitV2.Rule(
            idcc = "292",
            ruleId = "diagnostic",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            classification = ConventionClassificationV2(coefficient = 910),
            professionalStatus = "CADRE",
            aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
            minimumSeniorityMonths = 0,
            guarantees = listOf(guarantee),
            source = "Légifrance KALI — KALITEXT000000000001",
            conventionScopeKey = "KALITEXT000000000001",
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )

        assertTrue("formula invalid: ${guarantee.formula}", guarantee.formula.structurallyValid())
        assertTrue("evidence regex invalid: ${guarantee.evidenceArticleIds}", guarantee.evidenceArticleIds.all { it.matches(Regex("^KALIARTI\\d+$")) })
        assertTrue("guarantee invalid: $guarantee", guarantee.structurallyValid())
        assertTrue("scope regex invalid: ${rule.conventionScopeKey}", rule.conventionScopeKey.matches(Regex("^KALITEXT\\d+$")))
        assertTrue("rule invalid: $rule", rule.structurallyValid())
    }
}
