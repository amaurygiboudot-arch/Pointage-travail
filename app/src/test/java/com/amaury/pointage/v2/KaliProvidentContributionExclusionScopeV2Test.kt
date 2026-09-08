package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class KaliProvidentContributionExclusionScopeV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val articleId = "KALIARTI000000007700"
    private val textId = "KALITEXT000000007700"

    private fun profile(status: String = "NON_CADRE") = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = status,
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun category(
        value: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
    ) = ProtectionCategoryV2.Result(
        aniCategory = value,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun evidence(
        content: String,
        title: String = "Cotisations du régime de prévoyance"
    ) = KaliMatterEvidenceAuditV2.Evidence(
        idcc = "292",
        referenceDate = date,
        expressions = listOf("prévoyance cotisation"),
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
                status = "VIGUEUR_ETEN",
                content = content,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = null,
                title = title,
                extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
            )
        ),
        articleTextIds = mapOf(articleId to textId),
        ambiguousArticleTextIds = emptySet(),
        warnings = emptyList()
    )

    private fun exclusion(
        content: String,
        title: String = "Cotisations du régime de prévoyance",
        ani: ProtectionCategoryV2.Result = category(),
        status: String = "NON_CADRE"
    ) = KaliProvidentContributionAuditV2.explicitExclusion(
        profile(status),
        ani,
        evidence(content, title)
    )

    @Test
    fun `exclusion classification exacte est retenue`() {
        assertNotNull(exclusion("Non-cadres coefficient 700. Aucune cotisation de prevoyance."))
    }

    @Test
    fun `exclusion cadres sans classification est refusee au non cadre`() {
        assertNull(exclusion("Cadres : aucune cotisation de prevoyance."))
    }

    @Test
    fun `titre cadres restreint une exclusion generale du corps`() {
        assertNull(
            exclusion(
                content = "Aucune cotisation de prevoyance.",
                title = "Cadres - cotisations du régime de prévoyance"
            )
        )
    }

    @Test
    fun `titre coefficient voisin restreint une exclusion generale du corps`() {
        assertNull(
            exclusion(
                content = "Aucune cotisation de prevoyance.",
                title = "Coefficient 920 - cotisations du régime de prévoyance"
            )
        )
    }

    @Test
    fun `titre generique cotisations ne vaut pas mention positive`() {
        assertNotNull(
            exclusion(
                content = "Aucune cotisation de prevoyance.",
                title = "Cotisations du régime de prévoyance"
            )
        )
    }

    @Test
    fun `exclusion generale reste applicable au profil exact`() {
        assertNotNull(exclusion("Aucune cotisation de prevoyance."))
    }

    @Test
    fun `exclusion et mention positive dans la meme portee restent contradictoires`() {
        assertNull(
            exclusion(
                "Non-cadres coefficient 700. Aucune cotisation de prevoyance. " +
                    "La cotisation de prevoyance est fixee a 1 %."
            )
        )
    }

    @Test
    fun `exclusion article 2_2 ne vaut jamais pour un salarie hors 2_1 2_2`() {
        assertNull(
            exclusion(
                "Salariés relevant de l'article 2.2. Aucune cotisation de prevoyance."
            )
        )
    }

    @Test
    fun `exclusion article 2_2 reste exploitable pour la categorie 2_2 exacte`() {
        assertNotNull(
            exclusion(
                content = "Salariés relevant de l'article 2.2. Aucune cotisation de prevoyance.",
                ani = category(ProtectionCategoryV2.AniCategory.ARTICLE_2_2)
            )
        )
    }
}
