package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionProvidentBenefitStoreTest {
    private fun rule(
        scope: String = "KALITEXT000000000001",
        articleId: String = "KALIARTI000000000001",
        ruleId: String = "benefit-test",
        coefficient: Double = 1.0,
        waitingPeriodDays: Int? = null,
        minimumSeniorityMonths: Int = 0
    ) = ConventionProvidentBenefitV2.Rule(
        idcc = "292",
        ruleId = ruleId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 910),
        professionalStatus = "CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
        minimumSeniorityMonths = minimumSeniorityMonths,
        guarantees = listOf(
            ConventionProvidentBenefitV2.Guarantee(
                family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                label = "Capital décès",
                formula = ConventionProvidentBenefitV2.Formula(
                    basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                    coefficient = coefficient
                ),
                waitingPeriodDays = waitingPeriodDays,
                socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
                evidenceArticleIds = setOf(articleId)
            )
        ),
        source = "Légifrance KALI — $scope",
        conventionScopeKey = scope,
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `règle exacte KALITEXT et KALIARTI est persistable`() {
        assertTrue(V2ConventionProvidentBenefitStore.acceptsVerifiedRule(rule()))
    }

    @Test
    fun `scope libre ressemblant à un accord est refusé`() {
        assertFalse(
            V2ConventionProvidentBenefitStore.acceptsVerifiedRule(
                rule(scope = "IDCC0292:ACCORD-2024-06-27")
            )
        )
    }

    @Test
    fun `preuve article non KALIARTI est refusée`() {
        assertFalse(
            V2ConventionProvidentBenefitStore.acceptsVerifiedRule(
                rule(articleId = "ARTICLE-12")
            )
        )
    }

    @Test
    fun `règle sans extension datée est refusée structurellement`() {
        val invalid = rule().copy(
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = null
        )
        assertFalse(V2ConventionProvidentBenefitStore.acceptsVerifiedRule(invalid))
    }

    @Test
    fun `meme article et famille gardent la meme identite malgré une formule franchise ou anciennete revisee`() {
        val previous = rule(
            ruleId = "benefit-old",
            coefficient = 1.0,
            waitingPeriodDays = 30,
            minimumSeniorityMonths = 0
        )
        val revised = rule(
            ruleId = "benefit-new",
            coefficient = 1.5,
            waitingPeriodDays = 60,
            minimumSeniorityMonths = 12
        )

        assertTrue(V2ConventionProvidentBenefitStore.sameLegalIdentity(previous, revised))
    }

    @Test
    fun `article juridique different ne peut jamais remplacer une ancienne regle`() {
        val first = rule(articleId = "KALIARTI000000000001", ruleId = "benefit-a")
        val second = rule(articleId = "KALIARTI000000000002", ruleId = "benefit-b")

        assertFalse(V2ConventionProvidentBenefitStore.sameLegalIdentity(first, second))
    }

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2ConventionProvidentBenefitStore.decodeVerified("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend le stockage non fiable`() {
        val result = V2ConventionProvidentBenefitStore.decodeVerified("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend le stockage non fiable`() {
        val result = V2ConventionProvidentBenefitStore.decodeVerified("[{}]")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `paquet vide reste valide pour permettre une suppression explicite`() {
        assertTrue(V2ConventionProvidentBenefitStore.acceptsVerifiedPackage(emptyList()))
    }

    @Test
    fun `paquet refuse deux variantes concurrentes de la meme identite juridique`() {
        assertFalse(
            V2ConventionProvidentBenefitStore.acceptsVerifiedPackage(
                listOf(
                    rule(ruleId = "benefit-old", coefficient = 1.0),
                    rule(ruleId = "benefit-new", coefficient = 1.5)
                )
            )
        )
    }

    @Test
    fun `paquet refuse un meme ruleId pour deux preuves juridiques differentes`() {
        assertFalse(
            V2ConventionProvidentBenefitStore.acceptsVerifiedPackage(
                listOf(
                    rule(articleId = "KALIARTI000000000001", ruleId = "same-id"),
                    rule(articleId = "KALIARTI000000000002", ruleId = "same-id")
                )
            )
        )
    }

    @Test
    fun `paquet accepte deux preuves juridiquement distinctes avec des ids distincts`() {
        assertTrue(
            V2ConventionProvidentBenefitStore.acceptsVerifiedPackage(
                listOf(
                    rule(articleId = "KALIARTI000000000001", ruleId = "benefit-a"),
                    rule(articleId = "KALIARTI000000000002", ruleId = "benefit-b")
                )
            )
        )
    }
}
