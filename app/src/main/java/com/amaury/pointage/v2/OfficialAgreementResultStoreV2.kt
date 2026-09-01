package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONObject

/** Conserve la réponse officielle brute par entreprise avant validation des accords. */
object OfficialAgreementResultStoreV2 {
    private const val KEY = "company_agreement_official_search_v2"

    fun save(context: Context, companyId: String, siret: String, data: Any?): Boolean {
        val payload = JSONObject().apply {
            put("siret", siret)
            put("receivedAt", System.currentTimeMillis())
            put("verified", false)
            put("response", JSONObject.wrap(data))
        }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, payload.toString())
            .commit()
    }
}
