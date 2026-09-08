package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class KaliProvidentContributionEmployeeOnlyExclusionV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000009200"
    private val textId = "KALITEXT000000009200"

    private val profile = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun evidence(content: String) = KaliMatterEvidenceAuditV2.Evidence(
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
                title = "Cotisations prévoyance",
                extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
            )
        ),
        articleTextIds = mapOf(articleId to textId),
        warnings = emptyList()
    )

    @Test
    fun `sans cotisation salariale ne prouve jamais absence de cotisation conventionnelle`() {
        val exclusion = KaliProvidentContributionAuditV2.explicitExclusion(
            profile = profile,
            protectionCategory = category,
            evidence = evidence(
                "Non-cadres coefficient 700 ne relevant pas des articles 2.1 et 2.2. " +
                    "Sans cotisation de prévoyance salariale. Le régime est financé par l'employeur."
            )
        )

        assertNull(exclusion)
    }

    @Test
    fun `absence totale explicite reste une preuve possible`() {
        val exclusion = KaliProvidentContributionAuditV2.explicitExclusion(
            profile = profile,
            protectionCategory = category,
            evidence = evidence(
                "Non-cadres coefficient 700 ne relevant pas des articles 2.1 et 2.2. " +
                    "Sans cotisation de prévoyance."
            )
        )

        assertNotNull(exclusion)
    }
}
