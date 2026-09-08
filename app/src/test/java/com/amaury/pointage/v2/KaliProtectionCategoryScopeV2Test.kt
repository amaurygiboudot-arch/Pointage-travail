package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliProtectionCategoryScopeV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000000001"
    private val textId = "KALITEXT000000000001"

    private fun rule(idcc: String = "292") = ConventionProtectionCategoryV2.Rule(
        idcc = idcc,
        ruleId = "KALI-PROTECTION-CATEGORY-$articleId",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 910),
        professionalStatus = "CADRE",
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        source = "Légifrance KALI — $articleId",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun article() = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = articleId,
        status = "VIGUEUR_ETEN",
        content = "Catégorie ANI 2.1 coefficient 910",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Catégories objectives",
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun evidence(
        idcc: String = "0292",
        mapping: Map<String, String> = mapOf(articleId to textId),
        ambiguous: Set<String> = emptySet(),
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle> = listOf(article())
    ) = KaliMatterEvidenceAuditV2.Evidence(
        idcc = idcc,
        referenceDate = date,
        expressions = listOf("article 2.1"),
        pagesRead = 1,
        candidates = 1,
        textsConsulted = 1,
        unresolvedSections = 0,
        articlesConsulted = 1,
        searchCoverageComplete = true,
        allTextsExpanded = true,
        allArticlesConsulted = true,
        articles = articles,
        articleTextIds = mapping,
        ambiguousArticleTextIds = ambiguous,
        warnings = emptyList()
    )

    @Test
    fun `parent KALITEXT exact devient le seul périmètre de la preuve`() {
        val result = KaliProtectionCategoryScopeV2.attach(rule(), articleId, evidence())

        assertTrue(result.reliable)
        assertEquals(textId, result.rule?.conventionScopeKey)
        assertEquals(ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED, result.rule?.approvalStatus)
    }

    @Test
    fun `article direct sans parent KALITEXT reste bloqué`() {
        val result = KaliProtectionCategoryScopeV2.attach(rule(), articleId, evidence(mapping = emptyMap()))

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `article vu sous plusieurs KALITEXT reste bloqué`() {
        val result = KaliProtectionCategoryScopeV2.attach(
            rule(), articleId, evidence(mapping = emptyMap(), ambiguous = setOf(articleId))
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `article non consulté ne peut jamais recevoir un périmètre`() {
        val result = KaliProtectionCategoryScopeV2.attach(rule(), articleId, evidence(articles = emptyList()))

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `IDCC différent bloque le rattachement même avec le même article`() {
        val result = KaliProtectionCategoryScopeV2.attach(rule(idcc = "292"), articleId, evidence(idcc = "0493"))

        assertFalse(result.reliable)
        assertNull(result.rule)
    }
}
