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
        val notes: String = "",
        val documentName: String = "",
        val documentMimeType: String = "",
        val documentSha256: String = "",
        val documentPath: String = "",
        val importedAtEpochMs: Long? = null
    )

    internal const val KEY = "company_agreements_v2"

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
                    notes = o.optString("notes"),
                    documentName = o.optString("documentName"),
                    documentMimeType = o.optString("documentMimeType"),
                    documentSha256 = o.optString("documentSha256"),
                    documentPath = o.optString("documentPath"),
                    importedAtEpochMs = o.optLong("importedAtEpochMs", 0L).takeIf { it > 0L }
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, companyId: String, agreements: List<Agreement>): Boolean =
        SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, encode(agreements)).commit()

    internal fun encode(agreements: List<Agreement>): String {
        val a = JSONArray()
        agreements.forEach { x ->
            a.put(JSONObject().apply {
                put("id", x.id); put("title", x.title)
                put("effectiveFrom", x.effectiveFrom ?: ""); put("effectiveTo", x.effectiveTo ?: "")
                put("sourceLabel", x.sourceLabel); put("status", x.status.name); put("notes", x.notes)
                put("documentName", x.documentName); put("documentMimeType", x.documentMimeType)
                put("documentSha256", x.documentSha256); put("documentPath", x.documentPath)
                put("importedAtEpochMs", x.importedAtEpochMs ?: 0L)
            })
        }
        return a.toString()
    }

    fun hasVerifiedAgreement(context: Context, companyId: String): Boolean =
        list(context, companyId).any { it.status == Status.VERIFIED }
}
