package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ApecProtectionCategoryApprovalV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import java.time.LocalDate
import java.util.Locale

/**
 * Pipeline final fail-closed de la catégorie objective ANI :
 * KALI scoped -> identité KALITEXT -> document APEC officiel -> décision scoped -> résolution à date.
 */
object ProtectionCategoryApprovalPipelineV2 {
    data class Result(
        /** Preuve KALI/APEC complète pouvant être conservée localement, même si elle n'est pas encore active à la date auditée. */
        val approvedRule: ConventionProtectionCategoryV2.Rule?,
        /** Résolution juridique pour la date demandée. */
        val resolution: ConventionProtectionCategoryV2.Resolution,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        kaliRule: ConventionProtectionCategoryV2.Rule,
        kaliIdentity: OfficialKaliTextIdentityV2.Identity,
        apecDocument: OfficialApecProtectionCategoryParserV2.Document
    ): Result {
        val profileMismatch = profileMismatch(profile, kaliRule)
        if (profileMismatch != null) return unresolved(profile, referenceDate, profileMismatch)

        val apec = OfficialApecProtectionCategoryParserV2.parse(apecDocument, profile)
        val apecEvidence = apec.evidence ?: return unresolved(
            profile,
            referenceDate,
            apec.reasons.joinToString().ifBlank { "preuve APEC non structurée" }
        )

        val scoped = ApecProtectionCategoryScopeV2.bind(
            kaliRule = kaliRule,
            kaliIdentity = kaliIdentity,
            apecEvidence = apecEvidence,
            apecDocument = apecDocument
        )
        val decision = scoped.decision ?: return unresolved(
            profile,
            referenceDate,
            (apec.reasons + scoped.warnings).joinToString().ifBlank { "périmètre APEC non rapproché" }
        )

        val approval = ApecProtectionCategoryApprovalV2.approve(kaliRule, decision)
        val approvedRule = approval.rule ?: return unresolved(
            profile,
            referenceDate,
            (apec.reasons + scoped.warnings + approval.warnings).joinToString().ifBlank { "agrément APEC non applicable" }
        )

        val resolution = ConventionProtectionCategoryV2.resolve(
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            rules = listOf(approvedRule)
        )
        val warnings = (apec.reasons + scoped.warnings + approval.warnings + resolution.warnings).distinct()
        return Result(
            approvedRule = approvedRule,
            resolution = resolution,
            reliable = resolution.reliable,
            warnings = warnings
        )
    }

    private fun profileMismatch(
        profile: ConventionLegalProfileV2,
        rule: ConventionProtectionCategoryV2.Rule
    ): String? {
        if (!rule.structurallyValid()) return "preuve KALI scoped structurellement invalide"
        if (ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc) != ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)) {
            return "IDCC du profil et de la preuve KALI différents"
        }
        if (!profile.classification.matches(rule.classification) || !rule.classification.matches(profile.classification)) {
            return "classification du profil et de la preuve KALI différentes"
        }
        val profileStatus = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val ruleStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        if (profileStatus == null || profileStatus != ruleStatus) {
            return "statut professionnel du profil et de la preuve KALI différent"
        }
        return null
    }

    private fun unresolved(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        reason: String
    ): Result {
        val resolution = ConventionProtectionCategoryV2.resolve(
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            rules = emptyList()
        )
        val warning = "Catégorie ANI KALI/APEC : $reason ; calcul dépendant de cette catégorie bloqué."
        return Result(
            approvedRule = null,
            resolution = resolution,
            reliable = false,
            warnings = (listOf(warning) + resolution.warnings).distinct()
        )
    }
}
