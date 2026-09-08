package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ApecProtectionCategoryApprovalV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import java.util.Locale

/**
 * Rapprochement strict d'une preuve APEC avec le KALITEXT exact déjà rattaché à la règle KALI.
 *
 * Aucun fuzzy matching : le titre formel complet du KALITEXT, normalisé uniquement pour casse,
 * accents et espaces, doit être présent textuellement dans le document APEC officiel.
 */
object ApecProtectionCategoryScopeV2 {
    data class Result(
        val decision: ApecProtectionCategoryApprovalV2.Decision?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun bind(
        kaliRule: ConventionProtectionCategoryV2.Rule,
        kaliIdentity: OfficialKaliTextIdentityV2.Identity,
        apecEvidence: OfficialApecProtectionCategoryParserV2.Evidence,
        apecDocument: OfficialApecProtectionCategoryParserV2.Document
    ): Result {
        if (!kaliRule.structurallyValid()) return unresolved("preuve KALI invalide")
        if (!kaliIdentity.reliableForCrossSourceScope) return unresolved("identité formelle du KALITEXT incomplète")
        if (kaliRule.conventionScopeKey != kaliIdentity.textId) {
            return unresolved("KALITEXT de la règle différent de l'identité KALI fournie")
        }
        if (apecEvidence.documentId != apecDocument.documentId || apecEvidence.source != apecDocument.sourceUrl) {
            return unresolved("preuve APEC et document officiel fournis ne correspondent pas")
        }

        val kaliIdcc = ConventionMinimumSalaryV2.normalizeIdcc(kaliRule.idcc)
        val apecIdcc = ConventionMinimumSalaryV2.normalizeIdcc(apecEvidence.idcc)
        if (kaliIdcc.isBlank() || kaliIdcc != apecIdcc) return unresolved("IDCC KALI et APEC différents")

        if (!kaliRule.classification.matches(apecEvidence.classification) ||
            !apecEvidence.classification.matches(kaliRule.classification)
        ) {
            return unresolved("classification KALI et classification APEC différentes")
        }
        val kaliStatus = kaliRule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        if (kaliStatus == null || kaliStatus != apecEvidence.professionalStatus.trim().uppercase(Locale.ROOT)) {
            return unresolved("statut professionnel KALI et APEC différent")
        }
        if (kaliRule.aniCategory != apecEvidence.aniCategory) {
            return unresolved("catégorie ANI KALI et APEC différente")
        }

        val formalTitle = kaliIdentity.title?.let(OfficialKaliProfileMatcherV2::normalize).orEmpty()
        if (formalTitle.length < 18) return unresolved("titre formel KALITEXT trop court pour un rapprochement sûr")
        val apecText = OfficialKaliProfileMatcherV2.normalize(apecDocument.content)
        if (!apecText.contains(formalTitle)) {
            return unresolved("référence formelle exacte du KALITEXT absente du document APEC")
        }

        val decision = ApecProtectionCategoryApprovalV2.Decision(
            idcc = apecEvidence.idcc,
            decisionId = apecEvidence.documentId,
            decisionDate = apecEvidence.decisionDate,
            applicableFrom = apecEvidence.applicableFrom,
            scopeKey = kaliIdentity.textId,
            classification = apecEvidence.classification,
            professionalStatus = apecEvidence.professionalStatus,
            aniCategory = apecEvidence.aniCategory,
            source = apecEvidence.source
        )
        if (!decision.structurallyValid()) return unresolved("décision APEC liée structurellement invalide")

        return Result(
            decision = decision,
            reliable = true,
            warnings = buildList {
                if (decision.applicableFrom == null) {
                    add("APEC catégorie ANI : périmètre exact rapproché, mais DATE D'EFFET non prouvée ; applicabilité finale bloquée.")
                }
                if (apecEvidence.requestedEffectiveFrom != null && decision.applicableFrom == null) {
                    add("APEC catégorie ANI : seule une DATE D'EFFET SOUHAITEE est disponible ; elle n'est pas utilisée comme date applicable.")
                }
            }
        )
    }

    private fun unresolved(reason: String) = Result(
        decision = null,
        reliable = false,
        warnings = listOf("Rapprochement KALI/APEC catégorie ANI : $reason ; aucun agrément n'est appliqué.")
    )
}
