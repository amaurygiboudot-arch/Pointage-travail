package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Piste d'audit BOCC locale, isolée par entreprise, IDCC et date de paie. */
object BoccPayrollSourceStoreV2 {
    private const val PREFS = "horatrack_v2_bocc_payroll_sources"
    private const val KEY = "verified_bocc"
    private const val MAX_RECORDS = 160

    data class Record(
        val companyId: String,
        val referenceAtMs: Long,
        val idcc: String,
        val title: String,
        val fileName: String,
        val pathToFile: String,
        val publicationDate: String?,
        val textDate: String?,
        val bulletinNumber: String?,
        val checkedAtMs: Long
    )

    fun all(context: Context): List<Record> = decode(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
    )

    fun replaceSnapshot(
        context: Context,
        companyId: String,
        referenceAtMs: Long,
        idcc: String,
        verified: List<OfficialBoccVerifierV2.VerifiedReference>
    ): Boolean {
        require(companyId.isNotBlank()) { "Entreprise BOCC invalide" }
        require(referenceAtMs > 0L) { "Date BOCC invalide" }
        val normalizedIdcc = idcc.filter(Char::isDigit)
        require(normalizedIdcc.isNotBlank()) { "IDCC BOCC invalide" }

        val current = all(context).toMutableList()
        current.removeAll {
            it.companyId == companyId && it.idcc == normalizedIdcc && sameReferenceDay(it.referenceAtMs, referenceAtMs)
        }
        current += verified.map {
            Record(
                companyId = companyId,
                referenceAtMs = referenceAtMs,
                idcc = normalizedIdcc,
                title = it.title.take(500),
                fileName = it.fileName,
                pathToFile = it.pathToFile.take(1_000),
                publicationDate = it.publicationDate,
                textDate = it.textDate,
                bulletinNumber = it.bulletinNumber,
                checkedAtMs = it.checkedAtMs
            )
        }
        val kept = current
            .distinctBy { "${it.companyId}:${it.idcc}:${referenceDay(it.referenceAtMs)}:${it.fileName}" }
            .sortedByDescending { it.checkedAtMs }
            .take(MAX_RECORDS)
        return save(context, kept)
    }

    fun snapshot(context: Context, companyId: String, referenceAtMs: Long, idcc: String): List<Record> {
        if (companyId.isBlank() || referenceAtMs <= 0L) return emptyList()
        val normalizedIdcc = idcc.filter(Char::isDigit)
        if (normalizedIdcc.isBlank()) return emptyList()
        return all(context)
            .filter {
                it.companyId == companyId && it.idcc == normalizedIdcc && sameReferenceDay(it.referenceAtMs, referenceAtMs)
            }
            .groupBy { it.fileName }
            .mapNotNull { (_, values) -> values.maxByOrNull { it.checkedAtMs } }
            .sortedByDescending { it.publicationDate.orEmpty() }
    }

    private fun save(context: Context, records: List<Record>): Boolean {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject()
                .put("companyId", record.companyId)
                .put("referenceAtMs", record.referenceAtMs)
                .put("idcc", record.idcc)
                .put("title", record.title)
                .put("fileName", record.fileName)
                .put("pathToFile", record.pathToFile)
                .put("publicationDate", record.publicationDate ?: JSONObject.NULL)
                .put("textDate", record.textDate ?: JSONObject.NULL)
                .put("bulletinNumber", record.bulletinNumber ?: JSONObject.NULL)
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
                val companyId = obj.optString("companyId").trim()
                val referenceAtMs = obj.optLong("referenceAtMs", -1L)
                val idcc = obj.optString("idcc").filter(Char::isDigit)
                val title = obj.optString("title").trim()
                val fileName = obj.optString("fileName").trim()
                val path = obj.optString("pathToFile").trim()
                val checkedAtMs = obj.optLong("checkedAtMs", -1L)
                if (companyId.isBlank() || referenceAtMs <= 0L || idcc.isBlank() || title.isBlank() ||
                    !fileName.endsWith(".pdf", ignoreCase = true) || path.isBlank() || checkedAtMs <= 0L) continue
                add(Record(
                    companyId = companyId,
                    referenceAtMs = referenceAtMs,
                    idcc = idcc,
                    title = title.take(500),
                    fileName = fileName,
                    pathToFile = path.take(1_000),
                    publicationDate = optional(obj, "publicationDate"),
                    textDate = optional(obj, "textDate"),
                    bulletinNumber = optional(obj, "bulletinNumber"),
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
