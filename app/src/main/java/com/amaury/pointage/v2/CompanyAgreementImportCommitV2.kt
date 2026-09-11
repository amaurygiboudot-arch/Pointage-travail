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
    ): Result = SalaryCompanyStore.withConfirmedCompany(context, companyId) {
        val storedAgreements = CompanyAgreementStoreV2.read(context, companyId)
        val storedCandidates = CompanyAgreementRuleStoreV2.read(context, companyId)
        val previous = storedAgreements.agreements.firstOrNull { it.id == agreement.id }
        if (!storedAgreements.reliable || !storedCandidates.reliable) {
            return@withConfirmedCompany Result(
                saved = false,
                duplicate = previous != null,
                candidateCount = candidates.size
            )
        }

        val agreements = storedAgreements.agreements
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
            storedCandidates.records,
            agreement.id,
            candidates
        )
        val snapshotEntries = buildSnapshotEntries(mergedAgreements, mergedCandidates)
            ?: return@withConfirmedCompany Result(
                saved = false,
                duplicate = previous != null,
                candidateCount = candidates.size
            )

        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val editor = prefs.edit()
        snapshotEntries.forEach { (key, value) -> editor.putString(key, value) }
        val saved = editor
            .putLong("company_agreement_import_completed_at", System.currentTimeMillis())
            .putLong("company_agreement_import_revision", prefs.getLong("company_agreement_import_revision", 0L) + 1L)
            .commit()
        Result(saved = saved, duplicate = previous != null, candidateCount = candidates.size)
    } ?: Result(saved = false, duplicate = false, candidateCount = candidates.size)

    internal fun buildSnapshotEntries(
        agreements: List<CompanyAgreementStoreV2.Agreement>,
        candidates: List<CompanyAgreementRuleStoreV2.StoredCandidate>
    ): Map<String, String>? {
        val agreementEntries = CompanyAgreementStoreV2.snapshotEntries(agreements) ?: return null
        val candidateEntries = CompanyAgreementRuleStoreV2.snapshotEntries(candidates) ?: return null
        if (agreementEntries.keys.any(candidateEntries::containsKey)) return null
        return linkedMapOf<String, String>().apply {
            putAll(agreementEntries)
            putAll(candidateEntries)
        }
    }
}
