package com.amaury.pointage.v2

import android.content.Context

/** Raccord prudent entre `/consult/acco` vérifié par SIRET et le store local des paniers repas. */
object CompanyAgreementMealBasketIngestionV2 {
    data class StructuredPackage(
        val detected: Boolean,
        val rules: List<OfficialAccoMealBasketParserV2.Rule>,
        val packageComplete: Boolean,
        val subjects: Set<String>,
        val warnings: List<String>
    ) {
        val structured: Boolean get() = rules.isNotEmpty()
    }

    data class Result(
        val detected: Boolean,
        val structured: Boolean,
        val legalPackageComplete: Boolean,
        val storageComplete: Boolean,
        val savedCount: Int,
        val ruleCount: Int,
        val subjects: Set<String>,
        val warnings: List<String>
    ) {
        val packageComplete: Boolean get() = legalPackageComplete && storageComplete
        val storageFailure: Boolean get() = legalPackageComplete && !storageComplete
    }

    internal fun structure(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): StructuredPackage {
        val normalizedText = OfficialKaliProfileMatcherV2.normalize(verifiedContent.text)
        val detected = dedicatedMealMarkerRegex.containsMatchIn(normalizedText)
        if (!detected) {
            return StructuredPackage(
                detected = false,
                rules = emptyList(),
                packageComplete = false,
                subjects = emptySet(),
                warnings = emptyList()
            )
        }

        val expectedSiret = profile.siret.filter(Char::isDigit)
        val verifiedSiret = verifiedContent.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14 || verifiedSiret != expectedSiret) {
            return blockedStructure("SIRET officiel différent du profil local")
        }

        val diagnostic = OfficialAccoMealBasketParserV2.parse(
            profile = profile,
            agreementId = agreementId,
            officialText = verifiedContent.text
        )
        val subjects = diagnostic.rules
            .map { com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2.subject(it.benefitId) }
            .toSet()

        if (!diagnostic.fullyStructured) {
            return StructuredPackage(
                detected = true,
                rules = diagnostic.rules,
                packageComplete = false,
                subjects = subjects,
                warnings = (
                    diagnostic.reasons +
                        "ACCO repas : le paquet du profil n'est pas intégralement structuré ; aucune règle partielle ne peut alimenter le calcul."
                    ).distinct()
            )
        }

        return StructuredPackage(
            detected = true,
            rules = diagnostic.rules,
            packageComplete = true,
            subjects = subjects,
            warnings = diagnostic.reasons.distinct()
        )
    }

    fun ingestVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Result {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return blockedResult("profil juridique local introuvable")

        val structured = structure(profile, agreementId, verifiedContent)
        if (!structured.detected) {
            return Result(
                detected = false,
                structured = false,
                legalPackageComplete = false,
                storageComplete = true,
                savedCount = 0,
                ruleCount = 0,
                subjects = emptySet(),
                warnings = structured.warnings
            )
        }
        if (!structured.packageComplete) {
            return Result(
                detected = true,
                structured = structured.structured,
                legalPackageComplete = false,
                storageComplete = true,
                savedCount = 0,
                ruleCount = structured.rules.size,
                subjects = structured.subjects,
                warnings = structured.warnings
            )
        }

        var saved = 0
        val warnings = mutableListOf<String>()
        structured.rules.forEach { rule ->
            if (V2CompanyMealBasketStore.saveVerified(context, companyId, rule)) {
                saved++
            } else {
                warnings += "ACCO repas : ${rule.agreementId}/${rule.benefitId} n'a pas pu être stocké localement."
            }
        }

        val storageComplete = structured.rules.isNotEmpty() && saved == structured.rules.size
        return Result(
            detected = true,
            structured = structured.rules.isNotEmpty(),
            legalPackageComplete = true,
            storageComplete = storageComplete,
            savedCount = saved,
            ruleCount = structured.rules.size,
            subjects = structured.subjects,
            warnings = (structured.warnings + warnings).distinct()
        )
    }

    private fun blockedStructure(reason: String) = StructuredPackage(
        detected = true,
        rules = emptyList(),
        packageComplete = false,
        subjects = emptySet(),
        warnings = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est structurée.")
    )

    private fun blockedResult(reason: String) = Result(
        detected = true,
        structured = false,
        legalPackageComplete = false,
        storageComplete = true,
        savedCount = 0,
        ruleCount = 0,
        subjects = emptySet(),
        warnings = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est enregistrée.")
    )

    private val dedicatedMealMarkerRegex = Regex(
        "\\b(?:paniers?(?: repas| de nuit)?|indemnite(?:s)?(?: de)? repas|allocation(?:s)? de repas|prime(?:s)? de panier)\\b"
    )
}
