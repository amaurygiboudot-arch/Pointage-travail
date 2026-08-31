package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray
import org.json.JSONObject

/** Stockage séparé des règles candidates extraites des accords. */
object CompanyAgreementRuleStoreV2 {
    data class StoredCandidate(
        val agreementId: String,
        val category: CompanyAgreementRuleExtractorV2.Category,
        val excerpt: String,
        val confidence: Double,
        val verified: Boolean = false
    )

    private const val KEY = "company_agreement_rule_candidates_v2"

    fun replaceForAgreement(
        context: Context,
        companyId: String,
        agreementId: String,
        candidates: List<CompanyAgreementRuleExtractorV2.Candidate>
    ): Boolean {
        val kept = list(context, companyId).filterNot { it.agreementId == agreementId }
        val merged = kept + candidates.map {
            StoredCandidate(
                agreementId = agreementId,
                category = it.category,
                excerpt = it.excerpt,
                confidence = it.confidence
            )
        }
        return save(context, companyId, merged)
    }

    fun list(context: Context, companyId: String): List<StoredCandidate> {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val category = runCatching {
                    CompanyAgreementRuleExtractorV2.Category.valueOf(o.optString("category"))
                }.getOrNull() ?: return@mapNotNull null
                StoredCandidate(
                    agreementId = o.optString("agreementId"),
                    category = category,
                    excerpt = o.optString("excerpt"),
                    confidence = o.optDouble("confidence", 0.0),
                    verified = o.optBoolean("verified", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, companyId: String, values: List<StoredCandidate>): Boolean {
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("agreementId", value.agreementId)
                put("category", value.category.name)
                put("excerpt", value.excerpt)
                put("confidence", value.confidence)
                put("verified", value.verified)
            })
        }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }
}
