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
        val verified: Boolean = false,
        val effectiveFrom: String? = null,
        val effectiveTo: String? = null,
        val scope: String? = null
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
                    verified = o.optBoolean("verified", false),
                    effectiveFrom = o.optString("effectiveFrom").takeIf { it.isNotBlank() },
                    effectiveTo = o.optString("effectiveTo").takeIf { it.isNotBlank() },
                    scope = o.optString("scope").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        verified: Boolean
    ): Boolean {
        val current = list(context, companyId)
        var matched = false
        val updated = current.map { candidate ->
            if (!matched && candidate.agreementId == agreementId && candidate.category == category && candidate.excerpt == excerpt) {
                matched = true
                candidate.copy(verified = verified)
            } else candidate
        }
        return matched && save(context, companyId, updated)
    }

    fun setApplicability(
        context: Context,
        companyId: String,
        agreementId: String,
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        effectiveFrom: String?,
        effectiveTo: String?,
        scope: String?
    ): Boolean {
        val current = list(context, companyId)
        var matched = false
        val updated = current.map { candidate ->
            if (!matched && candidate.agreementId == agreementId && candidate.category == category && candidate.excerpt == excerpt) {
                matched = true
                candidate.copy(
                    effectiveFrom = effectiveFrom?.trim()?.takeIf { it.isNotBlank() },
                    effectiveTo = effectiveTo?.trim()?.takeIf { it.isNotBlank() },
                    scope = scope?.trim()?.takeIf { it.isNotBlank() }
                )
            } else candidate
        }
        return matched && save(context, companyId, updated)
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
                put("effectiveFrom", value.effectiveFrom ?: "")
                put("effectiveTo", value.effectiveTo ?: "")
                put("scope", value.scope ?: "")
            })
        }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }
}
