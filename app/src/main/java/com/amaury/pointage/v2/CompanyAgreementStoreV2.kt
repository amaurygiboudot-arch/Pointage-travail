package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray
import org.json.JSONObject

/** Accords et règles internes propres à une entreprise. Aucune règle n'est appliquée sans validation. */
object CompanyAgreementStoreV2 {
    enum class Status { UNKNOWN, TO_PROVIDE, IMPORTED, VERIFIED }

    data class Agreement(
        val id: String,
        val title: String,
        val effectiveFrom: String?,
        val effectiveTo: String?,
        val sourceLabel: String,
        val status: Status,
        val notes: String = ""
    )

    private const val KEY = "company_agreements_v2"

    fun list(context: Context, companyId: String): List<Agreement> {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Agreement(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    effectiveFrom = o.optString("effectiveFrom").takeIf { it.isNotBlank() },
                    effectiveTo = o.optString("effectiveTo").takeIf { it.isNotBlank() },
                    sourceLabel = o.optString("sourceLabel"),
                    status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.UNKNOWN),
                    notes = o.optString("notes")
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, companyId: String, agreements: List<Agreement>): Boolean {
        val a = JSONArray()
        agreements.forEach { x ->
            a.put(JSONObject().apply {
                put("id", x.id); put("title", x.title)
                put("effectiveFrom", x.effectiveFrom ?: ""); put("effectiveTo", x.effectiveTo ?: "")
                put("sourceLabel", x.sourceLabel); put("status", x.status.name); put("notes", x.notes)
            })
        }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, a.toString()).commit()
    }

    fun hasVerifiedAgreement(context: Context, companyId: String): Boolean =
        list(context, companyId).any { it.status == Status.VERIFIED }
}
