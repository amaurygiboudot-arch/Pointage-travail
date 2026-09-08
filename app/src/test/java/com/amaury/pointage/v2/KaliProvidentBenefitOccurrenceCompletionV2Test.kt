package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliProvidentBenefitOccurrenceCompletionV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun rule(
        id: String,
        family: ConventionProvidentBenefitV2.Family,
        articleId: String
    ) = ConventionProvidentBenefitV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = "CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
        minimumSeniorityMonths = 0,
        guarantees = listOf(
            ConventionProvidentBenefitV2.Guarantee(
                family = family,
                label = family.name,
                formula = ConventionProvidentBenefitV2.Formula(
                    basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                    coefficient = 1.0
                ),
                evidenceArticleIds = setOf(articleId)
            )
        ),
        source = "Légifrance KALI test",
        conventionScopeKey = "KALITEXT000000009710",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `une occurrence non structuree bloque le lot meme si la famille est structuree ailleurs`() {
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000009711"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000009712"),
            rule("invalidity", ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, "KALIARTI000000009713")
        )
        val core = rules.flatMap { it.guarantees }.map { it.family }.toSet()

        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = core,
            structuredFamilies = core,
            exclusions = emptyList(),
            resolutionReliable = true,
            referenceDate = date,
            unresolvedOccurrenceFamilies = setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL)
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
        assertTrue(completion.warnings.any { it.contains("occurrence", ignoreCase = true) })
    }
}
