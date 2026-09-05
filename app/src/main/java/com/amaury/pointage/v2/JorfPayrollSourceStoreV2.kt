package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Piste d'audit JORF locale, isolée par date de paie. */
object JorfPayrollSourceStoreV2 {
    private const val PREFS = "horatrack_v2_jorf_payroll_sources"
    private const val KEY = "verified_jorf"
    private const val MAX_RECORDS = 160

    data class Record(
        val referenceAtMs: Long,
        val textCid: String,
        val title: String,
        val nature: String?,
        val legalState: String?,
        val nor: String?,
        val publicationDate: String,
        val publicationNumber: String?,
        val containerId: String,
        val checkedAtMs: Long
    )

    fun all(context: Context): List<Record> = decode(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
    )

    fun replaceSnapshot(
        context: Context,
        referenceAtMs: Long,
        verified: List<OfficialJorfVerifierV2.VerifiedReference>
    ): Boolean {
        require(referenceAtMs > 0L) { "Date JORF invalide" }
        val current = all(context).toMutableList()
        current.removeAll { sameReferenceDay(it.referenceAtMs, referenceAtMs) }
        current += verified.map {
            Record(
                referenceAtMs = referenceAtMs,
                textCid = it.textCid,
                title = it.title.take(500),
                nature = it.nature,
                legalState = it.legalState,
                nor = it.nor,
                publicationDate = it.publicationDate,
                publicationNumber = it.publicationNumber,
                containerId = it.containerId,
                checkedAtMs = it.checkedAtMs
            )
        }
        val kept = current
            .distinctBy { "${referenceDay(it.referenceAtMs)}:${it.textCid}" }
            .sortedByDescending { it.checkedAtMs }
            .take(MAX_RECORDS)
        return save(context, kept)
    }

    fun snapshot(context: Context, referenceAtMs: Long): List<Record> {
        if (referenceAtMs <= 0L) return emptyList()
        return all(context)
            .filter { sameReferenceDay(it.referenceAtMs, referenceAtMs) }
            .groupBy { it.textCid }
            .mapNotNull { (_, values) -> values.maxByOrNull { it.checkedAtMs } }
            .sortedByDescending { it.publicationDate }
    }

    private fun save(context: Context, records: List<Record>): Boolean {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject()
                .put("referenceAtMs", record.referenceAtMs)
                .put("textCid", record.textCid)
                .put("title", record.title)
                .put("nature", record.nature ?: JSONObject.NULL)
                .put("legalState", record.legalState ?: JSONObject.NULL)
                .put("nor", record.nor ?: JSONObject.NULL)
                .put("publicationDate", record.publicationDate)
                .put("publicationNumber", record.publicationNumber ?: JSONObject.NULL)
                .put("containerId", record.containerId)
                .put("checkedAtMs", record.checkedAtMs))
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun decode(raw: String): List<Record> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val referenceAtMs = obj.optLong("referenceAtMs", -1L)
                val textCid = obj.optString("textCid").trim()
                val title = obj.optString("title").trim()
                val publicationDate = obj.optString("publicationDate").trim()
                val containerId = obj.optString("containerId").trim()
                val checkedAtMs = obj.optLong("checkedAtMs", -1L)
                if (referenceAtMs <= 0L || !textCid.startsWith("JORFTEXT") || title.isBlank() ||
                    publicationDate.isBlank() || !containerId.startsWith("JORFCONT") || checkedAtMs <= 0L) continue
                add(Record(
                    referenceAtMs = referenceAtMs,
                    textCid = textCid,
                    title = title.take(500),
                    nature = optional(obj, "nature"),
                    legalState = optional(obj, "legalState"),
                    nor = optional(obj, "nor"),
                    publicationDate = publicationDate,
                    publicationNumber = optional(obj, "publicationNumber"),
                    containerId = containerId,
                    checkedAtMs = checkedAtMs
                ))
            }
        }
    }.getOrElse { emptyList() }

    private fun optional(obj: JSONObject, key: String): String? =
        obj.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
