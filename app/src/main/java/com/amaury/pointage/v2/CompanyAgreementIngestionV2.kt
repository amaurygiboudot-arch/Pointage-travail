package com.amaury.pointage.v2

import android.content.Context

/** Point d'entrée unique quand HoraTrack reçoit le texte intégral d'un accord officiel. */
object CompanyAgreementIngestionV2 {
    data class Result(
        val extractedCount: Int,
        val saved: Boolean
    )

    fun ingest(
        context: Context,
        companyId: String,
        agreementId: String,
        officialText: String
    ): Result {
        val candidates = CompanyAgreementRuleExtractorV2.extract(officialText)
        val saved = CompanyAgreementRuleStoreV2.replaceForAgreement(
            context = context,
            companyId = companyId,
            agreementId = agreementId,
            candidates = candidates
        )
        return Result(candidates.size, saved)
    }
}
