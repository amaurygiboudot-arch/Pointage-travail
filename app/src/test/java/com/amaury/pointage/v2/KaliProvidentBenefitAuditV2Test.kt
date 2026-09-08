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

class KaliProvidentBenefitAuditV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val core = setOf(
        ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
        ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT,
        ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION
    )

    private fun guarantee(
        family: ConventionProvidentBenefitV2.Family,
        article: String
    ) = ConventionProvidentBenefitV2.Guarantee(
        family = family,
        label = family.name,
        formula = ConventionProvidentBenefitV2.Formula(
            basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
            coefficient = 1.0
        ),
        evidenceArticleIds = setOf(article)
    )

    private fun rule(
        id: String,
        family: ConventionProvidentBenefitV2.Family,
        article: String
    ) = ConventionProvidentBenefitV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 910),
        professionalStatus = "CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
        minimumSeniorityMonths = 0,
        guarantees = listOf(guarantee(family, article)),
        source = "Légifrance KALI — KALITEXT000000000001",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun exclusion(
        family: ConventionProvidentBenefitV2.Family,
        extended: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = KaliProvidentBenefitAuditV2.ExclusionEvidence(
        family = family,
        articleId = "KALIARTI000000009999",
        conventionScopeKey = "KALITEXT000000000009",
        extensionEffectiveFrom = extended
    )

    @Test
    fun `trois familles coeur structurées sauvegardées et étendues ferment le lot`() {
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002"),
            rule("invalidity", ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, "KALIARTI000000000003")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = core,
            structuredFamilies = core,
            exclusions = emptyList(),
            resolutionReliable = true,
            referenceDate = date
        )

        assertTrue(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_RULES, completion.state)
    }

    @Test
    fun `couverture technique incomplète bloque même avec trois règles parfaites`() {
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002"),
            rule("invalidity", ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, "KALIARTI000000000003")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = false,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = core,
            structuredFamilies = core,
            exclusions = emptyList(),
            resolutionReliable = true,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
    }

    @Test
    fun `famille coeur manquante bloque sans exclusion explicite`() {
        val structured = core - ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = structured,
            structuredFamilies = structured,
            exclusions = emptyList(),
            resolutionReliable = true,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertTrue(completion.warnings.any { it.contains("décès") || it.contains("invalidité") })
    }

    @Test
    fun `invalidité explicitement exclue et étendue peut compléter décès et incapacité`() {
        val structured = setOf(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT
        )
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = structured + ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION,
            structuredFamilies = structured,
            exclusions = listOf(exclusion(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION)),
            resolutionReliable = true,
            referenceDate = date
        )

        assertTrue(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_RULES, completion.state)
    }

    @Test
    fun `exclusion sans date extension ne ferme jamais la famille`() {
        val structured = setOf(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT
        )
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = structured,
            structuredFamilies = structured,
            exclusions = listOf(exclusion(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, null)),
            resolutionReliable = true,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertTrue(completion.warnings.any { it.contains("exclusions") })
    }

    @Test
    fun `présence et exclusion de la même famille est une contradiction bloquante`() {
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002"),
            rule("invalidity", ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, "KALIARTI000000000003")
        )
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = core,
            structuredFamilies = core,
            exclusions = listOf(exclusion(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL)),
            resolutionReliable = true,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertTrue(completion.warnings.any { it.contains("contradiction") })
    }

    @Test
    fun `rente conjoint observée mais non structurée ni exclue bloque le lot`() {
        val rules = listOf(
            rule("death", ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, "KALIARTI000000000001"),
            rule("incapacity", ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, "KALIARTI000000000002"),
            rule("invalidity", ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, "KALIARTI000000000003")
        )
        val observed = core + ConventionProvidentBenefitV2.Family.SPOUSE_PENSION
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = rules,
            savedRuleIds = rules.map { it.ruleId }.toSet(),
            observedFamilies = observed,
            structuredFamilies = core,
            exclusions = emptyList(),
            resolutionReliable = true,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertTrue(completion.warnings.any { it.contains("SPOUSE_PENSION") })
    }

    @Test
    fun `toutes les familles coeur explicitement exclues et étendues ferment no rule sans règle positive`() {
        val exclusions = core.map(::exclusion)
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = emptyList(),
            savedRuleIds = emptySet(),
            observedFamilies = core,
            structuredFamilies = emptySet(),
            exclusions = exclusions,
            resolutionReliable = false,
            referenceDate = date
        )

        assertTrue(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE, completion.state)
    }

    @Test
    fun `no rule explicite reste bloqué si une exclusion coeur n est pas étendue`() {
        val exclusions = core.map { family ->
            exclusion(
                family = family,
                extended = if (family == ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION) null else LocalDate.of(2025, 1, 1)
            )
        }
        val completion = KaliProvidentBenefitAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rules = emptyList(),
            savedRuleIds = emptySet(),
            observedFamilies = core,
            structuredFamilies = emptySet(),
            exclusions = exclusions,
            resolutionReliable = false,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
    }
}
