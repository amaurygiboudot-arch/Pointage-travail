package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/**
 * Pont strict entre une preuve de catégorie KALI et un agrément officiel de la
 * commission paritaire rattachée à l'APEC.
 *
 * L'IDCC ne suffit jamais pour rapprocher les deux sources : le même périmètre
 * conventionnel exact, la même classification, le même statut et la même catégorie
 * doivent tous être prouvés indépendamment.
 */
object ApecProtectionCategoryApprovalV2 {
    data class Decision(
        val idcc: String,
        val decisionId: String,
        val decisionDate: LocalDate,
        /** Date d'applicabilité juridiquement prouvée. Jamais déduite de decisionDate. */
        val applicableFrom: LocalDate?,
        /** Clé du périmètre exact : accord/avenant national ou régional explicitement identifié. */
        val scopeKey: String,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val source: String
    ) {
        fun structurallyValid(): Boolean {
            val status = professionalStatus.trim().uppercase(Locale.ROOT)
            return ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
                decisionId.isNotBlank() &&
                scopeKey.isNotBlank() &&
                !classification.isEmpty() &&
                status in setOf("CADRE", "NON_CADRE") &&
                categoryMatchesStatus(aniCategory, status) &&
                aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
                aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE &&
                source.isNotBlank()
        }
    }

    data class Result(
        val rule: ConventionProtectionCategoryV2.Rule?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun approve(
        kaliRule: ConventionProtectionCategoryV2.Rule,
        decision: Decision
    ): Result {
        if (!kaliRule.structurallyValid()) {
            return unresolved("preuve KALI de catégorie invalide")
        }
        if (!decision.structurallyValid()) {
            return unresolved("agrément APEC structurellement incomplet")
        }
        val kaliIdcc = ConventionMinimumSalaryV2.normalizeIdcc(kaliRule.idcc)
        val apecIdcc = ConventionMinimumSalaryV2.normalizeIdcc(decision.idcc)
        if (kaliIdcc != apecIdcc) {
            return unresolved("IDCC KALI et APEC différents")
        }
        val kaliScope = kaliRule.conventionScopeKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return unresolved("périmètre exact de l'accord KALI non prouvé")
        if (kaliScope != decision.scopeKey.trim()) {
            return unresolved("agrément APEC portant sur un autre accord ou périmètre conventionnel")
        }
        if (!kaliRule.classification.matches(decision.classification) ||
            !decision.classification.matches(kaliRule.classification)
        ) {
            return unresolved("classification KALI et classification agréée APEC différentes")
        }
        if (kaliRule.professionalStatus?.trim()?.uppercase(Locale.ROOT) !=
            decision.professionalStatus.trim().uppercase(Locale.ROOT)
        ) {
            return unresolved("statut professionnel KALI et APEC différent")
        }
        if (kaliRule.aniCategory != decision.aniCategory) {
            return unresolved("catégorie ANI KALI et catégorie agréée APEC différentes")
        }
        val applicableFrom = decision.applicableFrom
            ?: return unresolved("date d'applicabilité de l'agrément APEC non prouvée")

        val approved = kaliRule.copy(
            approvalStatus = ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED,
            approvalEffectiveFrom = applicableFrom,
            approvalSource = decision.source,
            approvalScopeKey = decision.scopeKey.trim(),
            approvalClassification = decision.classification.normalized(),
            approvalAniCategory = decision.aniCategory
        )
        if (!approved.structurallyValid()) {
            return unresolved("rapprochement KALI/APEC incohérent après validation")
        }
        return Result(
            rule = approved,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun categoryMatchesStatus(category: ProtectionCategoryV2.AniCategory, status: String): Boolean = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> status == "CADRE"
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2 -> status == "NON_CADRE"
        ProtectionCategoryV2.AniCategory.TO_CONFIRM,
        ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE -> false
    }

    private fun unresolved(reason: String): Result {
        val warning = "Agrément APEC catégorie ANI : $reason ; rapprochement bloqué."
        return Result(
            rule = null,
            reliable = false,
            warnings = listOf(warning)
        )
    }
}
