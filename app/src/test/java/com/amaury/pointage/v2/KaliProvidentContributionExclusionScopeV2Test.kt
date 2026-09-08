package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
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

    @Test
    fun `exclusion classification exacte est retenue`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile(),
            evidence("Non-cadres coefficient 700. Aucune cotisation de prevoyance.")
        )

        assertNotNull(result)
    }

    @Test
    fun `exclusion cadres sans classification est refusee au non cadre`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile("NON_CADRE"),
            evidence("Cadres : aucune cotisation de prevoyance.")
        )

        assertNull(result)
    }

    @Test
    fun `titre cadres restreint une exclusion generale du corps`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile("NON_CADRE"),
            evidence(
                content = "Aucune cotisation de prevoyance.",
                title = "Cadres - cotisations du régime de prévoyance"
            )
        )

        assertNull(result)
    }

    @Test
    fun `titre coefficient voisin restreint une exclusion generale du corps`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile(),
            evidence(
                content = "Aucune cotisation de prevoyance.",
                title = "Coefficient 920 - cotisations du régime de prévoyance"
            )
        )

        assertNull(result)
    }

    @Test
    fun `titre generique cotisations ne vaut pas mention positive`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile(),
            evidence(
                content = "Aucune cotisation de prevoyance.",
                title = "Cotisations du régime de prévoyance"
            )
        )

        assertNotNull(result)
    }

    @Test
    fun `exclusion generale reste applicable au profil exact`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile(),
            evidence("Aucune cotisation de prevoyance.")
        )

        assertNotNull(result)
    }

    @Test
    fun `exclusion et mention positive dans la meme portee restent contradictoires`() {
        val result = KaliProvidentContributionAuditV2.explicitExclusion(
            profile(),
            evidence(
                "Non-cadres coefficient 700. Aucune cotisation de prevoyance. " +
                    "La cotisation de prevoyance est fixee a 1 %."
            )
        )

        assertNull(result)
    }
}
