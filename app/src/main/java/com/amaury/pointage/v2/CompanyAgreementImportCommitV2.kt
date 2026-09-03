package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore

/** Commits agreement metadata and extracted candidates in one SharedPreferences transaction. */
object CompanyAgreementImportCommitV2 {
    data class Result(val saved: Boolean, val duplicate: Boolean, val candidateCount: Int)

    fun commit(
        context: Context,
        companyId: String,
        agreement: CompanyAgreementStoreV2.Agreement,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ): Result {
        val agreements = CompanyAgreementStoreV2.list(context, companyId)
        val previous = agreements.firstOrNull { it.id == agreement.id }
        val imported = agreement.copy(
            status = if (previous?.status == CompanyAgreementStoreV2.Status.VERIFIED) {
                CompanyAgreementStoreV2.Status.VERIFIED
            } else {
                CompanyAgreementStoreV2.Status.IMPORTED
            }
        )
        val mergedAgreements = if (previous == null) {
            agreements + imported
        } else {
            agreements.map { if (it.id == agreement.id) imported else it }
        }
        val mergedCandidates = CompanyAgreementRuleStoreV2.mergePreservingValidation(
            CompanyAgreementRuleStoreV2.list(context, companyId),
            agreement.id,
            candidates
        )
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val saved = prefs.edit()
            .putString(CompanyAgreementStoreV2.KEY, CompanyAgreementStoreV2.encode(mergedAgreements))
            .putString(CompanyAgreementRuleStoreV2.KEY, CompanyAgreementRuleStoreV2.encode(mergedCandidates))
            .putLong("company_agreement_import_completed_at", System.currentTimeMillis())
            .putLong("company_agreement_import_revision", prefs.getLong("company_agreement_import_revision", 0L) + 1L)
            .commit()
        return Result(saved = saved, duplicate = previous != null, candidateCount = candidates.size)
    }
}
