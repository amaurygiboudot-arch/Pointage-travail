package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliProvidentBenefitExclusionScopeV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000009100"
    private val textId = "KALITEXT000000009100"
    private val defaultAniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1

    private fun profile(status: String = "CADRE") = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = status,
        classification = ConventionClassificationV2(coefficient = 910),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun evidence(content: String, status: String = "VIGUEUR_ETEN") = KaliMatterEvidenceAuditV2.Evidence(
        idcc = "292",
        referenceDate = date,
        expressions = listOf("prévoyance"),
        pagesRead = 1,
        candidates = 1,
        textsConsulted = 1,
        unresolvedSections = 0,
        articlesConsulted = 1,
        searchCoverageComplete = true,
        allTextsExpanded = true,
        allArticlesConsulted = true,
        articles = listOf(
            OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
                articleId = articleId,
                status = status,
                content = content,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = null,
                title = "Garanties prévoyance",
                extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
            )
        ),
        articleTextIds = mapOf(articleId to textId),
        warnings = emptyList()
    )

    private fun exclusions(
        profile: ConventionLegalProfileV2,
        source: KaliMatterEvidenceAuditV2.Evidence,
        category: ProtectionCategoryV2.AniCategory = defaultAniCategory
    ) = KaliProvidentBenefitAuditV2.explicitExclusions(
        profile = profile,
        protectionCategory = category,
        evidence = source
    )

    @Test
    fun `exclusion du coefficient voisin dans le même article est ignorée`() {
        val exclusions = exclusions(
            profile(),
            evidence(
                "Cadres coefficient 910. Le capital deces est garanti. " +
                    "Cadres coefficient 920. Aucune garantie invalidite."
            )
        )

        assertTrue(exclusions.isEmpty())
    }

    @Test
    fun `exclusion de la classification exacte est conservée`() {
        val exclusions = exclusions(
            profile(),
            evidence("Cadres coefficient 910. Aucune garantie invalidite.")
        )

        assertEquals(1, exclusions.size)
        assertEquals(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, exclusions.single().family)
        assertEquals(articleId, exclusions.single().articleId)
        assertEquals(textId, exclusions.single().conventionScopeKey)
    }

    @Test
    fun `article KALI hors statut actif ne peut jamais prouver une exclusion`() {
        val exclusions = exclusions(
            profile(),
            evidence("Cadres coefficient 910. Aucune garantie invalidite.", status = "ABROGE")
        )

        assertTrue(exclusions.isEmpty())
    }

    @Test
    fun `exclusion statut cadre sans classification est refusée au non cadre`() {
        val source = evidence("Cadres : aucune garantie capital deces n'est prévue.")

        val cadre = exclusions(profile("CADRE"), source)
        val nonCadre = exclusions(profile("NON_CADRE"), source)

        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), cadre.map { it.family }.toSet())
        assertTrue(nonCadre.isEmpty())
    }

    @Test
    fun `exclusion générale sans statut reste applicable aux deux statuts`() {
        val source = evidence("Aucune garantie capital deces n'est prévue.")

        val cadre = exclusions(profile("CADRE"), source)
        val nonCadre = exclusions(profile("NON_CADRE"), source)

        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), cadre.map { it.family }.toSet())
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), nonCadre.map { it.family }.toSet())
    }

    @Test
    fun `exclusion article 2_2 ne couvre jamais un salarie hors 2_1 2_2`() {
        val source = evidence(
            "Non-cadres coefficient 910 relevant de l'article 2.2. Aucune garantie invalidite."
        )

        val outside = exclusions(
            profile("NON_CADRE"),
            source,
            ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
        )

        assertTrue(outside.isEmpty())
    }

    @Test
    fun `exclusion article 2_2 reste valable pour la categorie 2_2 exacte`() {
        val source = evidence(
            "Non-cadres coefficient 910 relevant de l'article 2.2. Aucune garantie invalidite."
        )

        val article22 = exclusions(
            profile("NON_CADRE"),
            source,
            ProtectionCategoryV2.AniCategory.ARTICLE_2_2
        )

        assertEquals(1, article22.size)
        assertEquals(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION, article22.single().family)
    }
}
