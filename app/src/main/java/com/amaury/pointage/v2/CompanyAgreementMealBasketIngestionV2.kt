package com.amaury.pointage.v2

import android.content.Context

/** Raccord prudent entre `/consult/acco` vérifié par SIRET et le store local des paniers repas. */
object CompanyAgreementMealBasketIngestionV2 {
    data class Result(
        val detected: Boolean,
        val structured: Boolean,
        val packageComplete: Boolean,
        val savedCount: Int,
        val warnings: List<String>
    ) {
        val storageFailure: Boolean get() = packageComplete && savedCount == 0
    }

    fun ingestVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Result {
        val detected = CompanyAgreementRuleExtractorV2.extract(verifiedContent.text)
            .any { it.category == CompanyAgreementRuleExtractorV2.Category.MEAL }
        if (!detected) return Result(false, false, false, 0, emptyList())

        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return blocked("profil juridique local introuvable")
        val expectedSiret = profile.siret.filter(Char::isDigit)
        val verifiedSiret = verifiedContent.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14 || verifiedSiret != expectedSiret) {
            return blocked("SIRET officiel différent du profil local")
        }

        val diagnostic = OfficialAccoMealBasketParserV2.parse(profile, agreementId, verifiedContent.text)
        if (!diagnostic.fullyStructured) {
            return Result(
                detected = true,
                structured = diagnostic.rules.isNotEmpty(),
                packageComplete = false,
                savedCount = 0,
                warnings = (diagnostic.reasons +
                    "ACCO repas : le paquet du profil n'est pas intégralement structuré ; aucune règle partielle n'est enregistrée pour le calcul.").distinct()
            )
        }

        var saved = 0
        val warnings = mutableListOf<String>()
        diagnostic.rules.forEach { rule ->
            if (V2CompanyMealBasketStore.saveVerified(context, companyId, rule)) saved++
            else warnings += "ACCO repas : ${rule.agreementId}/${rule.benefitId} n'a pas pu être stocké localement."
        }
        return Result(
            detected = true,
            structured = diagnostic.rules.isNotEmpty(),
            packageComplete = saved == diagnostic.rules.size && diagnostic.rules.isNotEmpty(),
            savedCount = saved,
            warnings = (diagnostic.reasons + warnings).distinct()
        )
    }

    private fun blocked(reason: String) = Result(
        detected = true,
        structured = false,
        packageComplete = false,
        savedCount = 0,
        warnings = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est enregistrée.")
    )
}
