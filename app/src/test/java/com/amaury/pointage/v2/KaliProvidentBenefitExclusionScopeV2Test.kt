package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliProvidentBenefitExclusionScopeV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000009100"
    private val textId = "KALITEXT000000009100"

    private fun profile() = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "CADRE",
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

    @Test
    fun `exclusion du coefficient voisin dans le même article est ignorée`() {
        val exclusions = KaliProvidentBenefitAuditV2.explicitExclusions(
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
        val exclusions = KaliProvidentBenefitAuditV2.explicitExclusions(
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
        val exclusions = KaliProvidentBenefitAuditV2.explicitExclusions(
            profile(),
            evidence("Cadres coefficient 910. Aucune garantie invalidite.", status = "ABROGE")
        )

        assertTrue(exclusions.isEmpty())
    }
}
