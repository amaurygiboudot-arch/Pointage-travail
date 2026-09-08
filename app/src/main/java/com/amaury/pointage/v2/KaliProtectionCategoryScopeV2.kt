package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2

/**
 * Rattache une preuve de catégorie ANI à son KALITEXT parent uniquement à partir
 * de la filiation officielle collectée par /consult/kaliText.
 *
 * Aucun titre, libellé ou rapprochement sémantique n'est utilisé pour inventer un périmètre.
 */
object KaliProtectionCategoryScopeV2 {
    data class Result(
        val rule: ConventionProtectionCategoryV2.Rule?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun attach(
        rule: ConventionProtectionCategoryV2.Rule,
        articleId: String,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): Result {
        val normalizedArticleId = articleId.trim().uppercase()
        if (!normalizedArticleId.matches(Regex("^KALIARTI\\d+$"))) {
            return unresolved("identifiant KALIARTI invalide")
        }
        if (!rule.structurallyValid()) {
            return unresolved("preuve KALI de catégorie invalide")
        }
        if (ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) != ConventionMinimumSalaryV2.normalizeIdcc(evidence.idcc)) {
            return unresolved("IDCC de la règle et de la collecte différents")
        }
        if (evidence.articles.none { it.articleId.equals(normalizedArticleId, ignoreCase = true) }) {
            return unresolved("article absent des KALIARTI officiellement consultés")
        }
        if (normalizedArticleId in evidence.ambiguousArticleTextIds) {
            return unresolved("article rattaché à plusieurs KALITEXT dans la collecte")
        }
        val textId = evidence.articleTextIds[normalizedArticleId]
            ?: evidence.articleTextIds.entries.firstOrNull { it.key.equals(normalizedArticleId, ignoreCase = true) }?.value
            ?: return unresolved("KALITEXT parent exact non prouvé pour cet article")
        if (!textId.matches(Regex("^KALITEXT\\d+$"))) {
            return unresolved("identifiant KALITEXT parent invalide")
        }

        val scoped = rule.copy(conventionScopeKey = textId)
        if (!scoped.structurallyValid()) {
            return unresolved("preuve KALI incohérente après rattachement du périmètre")
        }
        return Result(scoped, true, emptyList())
    }

    private fun unresolved(reason: String) = Result(
        rule = null,
        reliable = false,
        warnings = listOf("Périmètre KALI catégorie ANI : $reason ; rapprochement APEC bloqué.")
    )
}
