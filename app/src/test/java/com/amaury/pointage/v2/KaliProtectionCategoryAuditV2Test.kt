package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliProtectionCategoryAuditV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000050828557"
    private val textId = "KALITEXT000050828500"

    private fun profile(idcc: String = "292") = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = idcc,
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

    private fun article() = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = articleId,
        status = "VIGUEUR_ETEN",
        content = "Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940.",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Catégories objectives de protection sociale complémentaire",
        extensionEffectiveFrom = LocalDate.of(2024, 12, 26)
    )

    private fun evidence(
        idcc: String = "0292",
        mapping: Map<String, String> = mapOf(articleId to textId),
        ambiguous: Set<String> = emptySet()
    ) = KaliMatterEvidenceAuditV2.Evidence(
        idcc = idcc,
        referenceDate = date,
        expressions = listOf("article 2.1 prévoyance cadres"),
        pagesRead = 1,
        candidates = 2,
        textsConsulted = 1,
        unresolvedSections = 0,
        articlesConsulted = 1,
        searchCoverageComplete = true,
        allTextsExpanded = true,
        allArticlesConsulted = true,
        articles = listOf(article()),
        articleTextIds = mapping,
        ambiguousArticleTextIds = ambiguous,
        warnings = emptyList()
    )

    @Test
    fun `preuve KALI parse puis scoped reste APEC non vérifiée`() {
        val result = KaliProtectionCategoryAuditV2.structureEvidence(profile(), date, evidence())

        assertEquals(1, result.parsedRules)
        assertEquals(1, result.scopedRules.size)
        val rule = result.scopedRules.single()
        assertEquals(textId, rule.conventionScopeKey)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, rule.aniCategory)
        assertEquals(ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED, rule.approvalStatus)
        assertTrue(V2ConventionProtectionCategoryStore.acceptsVerifiedRule(rule))
    }

    @Test
    fun `preuve parseable sans KALITEXT parent n est jamais persistable`() {
        val result = KaliProtectionCategoryAuditV2.structureEvidence(
            profile(), date, evidence(mapping = emptyMap())
        )

        assertEquals(1, result.parsedRules)
        assertTrue(result.scopedRules.isEmpty())
        assertTrue(result.warnings.any { it.contains("KALITEXT parent exact") })
    }

    @Test
    fun `périmètre KALITEXT ambigu bloque le scope`() {
        val result = KaliProtectionCategoryAuditV2.structureEvidence(
            profile(), date, evidence(mapping = emptyMap(), ambiguous = setOf(articleId))
        )

        assertEquals(1, result.parsedRules)
        assertTrue(result.scopedRules.isEmpty())
        assertTrue(result.warnings.any { it.contains("plusieurs KALITEXT") })
    }

    @Test
    fun `IDCC de collecte différent bloque tout avant parsing`() {
        val result = KaliProtectionCategoryAuditV2.structureEvidence(profile(), date, evidence(idcc = "0493"))

        assertEquals(0, result.parsedRules)
        assertTrue(result.scopedRules.isEmpty())
        assertTrue(result.warnings.any { it.contains("IDCC") })
    }

    @Test
    fun `date de collecte différente bloque tout avant parsing`() {
        val result = KaliProtectionCategoryAuditV2.structureEvidence(
            profile(), LocalDate.of(2026, 9, 7), evidence()
        )

        assertEquals(0, result.parsedRules)
        assertTrue(result.scopedRules.isEmpty())
        assertTrue(result.warnings.any { it.contains("date") })
    }
}
