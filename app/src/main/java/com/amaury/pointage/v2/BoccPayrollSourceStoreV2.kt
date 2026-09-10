package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Piste d'audit BOCC locale, isolée par entreprise, IDCC et date de paie. */
object BoccPayrollSourceStoreV2 {
    private const val PREFS = "horatrack_v2_bocc_payroll_sources"
    private const val KEY = "verified_bocc"
    internal const val MAX_RECORDS = 160
    private const val STORAGE_WARNING =
        "BOCC : stockage local des références officielles illisible ou incohérent ; aucune référence partielle n'est considérée vérifiée."

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

    data class ReadResult(
        val records: List<Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    private data class OptionalString(val valid: Boolean, val value: String?)

    internal fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return corruptResult()
        return decodeRecords(raw)
    }

    fun all(context: Context): List<Record> = read(context).takeIf { it.reliable }?.records.orEmpty()

    fun replaceSnapshot(
        context: Context,
        companyId: String,
        referenceAtMs: Long,
        idcc: String,
        verified: List<OfficialBoccVerifierV2.VerifiedReference>
    ): Boolean {
        require(companyId.isNotBlank()) { "Entreprise BOCC invalide" }
        require(referenceAtMs > 0L) { "Date BOCC invalide" }
        val normalizedIdcc = normalizeIdcc(idcc)
        require(normalizedIdcc != null) { "IDCC BOCC invalide" }

        val stored = read(context)
        if (!stored.reliable) return false
        val incoming = verified.map {
            Record(
                companyId = companyId.trim(),
                referenceAtMs = referenceAtMs,
                idcc = normalizedIdcc,
                title = it.title.trim().take(500),
                fileName = it.fileName.trim(),
                pathToFile = it.pathToFile.trim().take(1_000),
                publicationDate = it.publicationDate?.trim()?.takeIf(String::isNotBlank),
                textDate = it.textDate?.trim()?.takeIf(String::isNotBlank),
                bulletinNumber = it.bulletinNumber?.trim()?.takeIf(String::isNotBlank),
                checkedAtMs = it.checkedAtMs
            )
        }
        if (incoming.any { !it.structurallyValid() } || hasDuplicateIdentity(incoming)) return false

        val current = stored.records.filterNot {
            it.companyId == companyId.trim() && it.idcc == normalizedIdcc &&
                sameReferenceDay(it.referenceAtMs, referenceAtMs)
        }
        val updated = current + incoming
        if (!acceptsRecordSet(updated)) return false
        return save(context, updated.sortedByDescending { it.checkedAtMs })
    }

    fun snapshot(context: Context, companyId: String, referenceAtMs: Long, idcc: String): List<Record> =
        snapshotResult(context, companyId, referenceAtMs, idcc).takeIf { it.reliable }?.records.orEmpty()

    fun snapshotResult(
        context: Context,
        companyId: String,
        referenceAtMs: Long,
        idcc: String
    ): ReadResult = snapshotFrom(read(context), companyId, referenceAtMs, idcc)

    internal fun snapshotFrom(
        stored: ReadResult,
        companyId: String,
        referenceAtMs: Long,
        idcc: String
    ): ReadResult {
        if (!stored.reliable) return stored.copy(records = emptyList())
        val normalizedIdcc = normalizeIdcc(idcc) ?: return corruptResult()
        val normalizedCompanyId = companyId.trim()
        if (normalizedCompanyId.isBlank() || referenceAtMs <= 0L) return corruptResult()
        return stored.copy(
            records = stored.records
                .filter {
                    it.companyId == normalizedCompanyId && it.idcc == normalizedIdcc &&
                        sameReferenceDay(it.referenceAtMs, referenceAtMs)
                }
                .sortedByDescending { it.publicationDate.orEmpty() }
        )
    }

    internal fun decodeRecords(raw: String): ReadResult {
        if (raw.isBlank()) return corruptResult()
        return runCatching {
            val array = JSONArray(raw)
            var malformed = false
            val records = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index)
                    if (obj == null) {
                        malformed = true
                        continue
                    }
                    val record = decodeRecord(obj)
                    if (record == null || !record.structurallyValid()) malformed = true else add(record)
                }
            }
            if (!acceptsRecordSet(records)) malformed = true
            ReadResult(records, !malformed, if (malformed) listOf(STORAGE_WARNING) else emptyList())
        }.getOrElse { corruptResult() }
    }

    internal fun acceptsRecordSet(records: List<Record>): Boolean =
        records.size <= MAX_RECORDS && records.all { it.structurallyValid() } && !hasDuplicateIdentity(records)

    private fun Record.structurallyValid(): Boolean =
        companyId.isNotBlank() &&
            referenceAtMs > 0L &&
            normalizeIdcc(idcc) == idcc &&
            title.isNotBlank() && title.length <= 500 &&
            fileName.endsWith(".pdf", ignoreCase = true) && fileName.length <= 500 &&
            pathToFile.isNotBlank() && pathToFile.length <= 1_000 &&
            checkedAtMs > 0L

    private fun save(context: Context, records: List<Record>): Boolean {
        if (!acceptsRecordSet(records)) return false
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
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }

    private fun decodeRecord(obj: JSONObject): Record? {
        val companyId = requiredString(obj, "companyId") ?: return null
        val referenceAtMs = requiredLong(obj, "referenceAtMs")?.takeIf { it > 0L } ?: return null
        val rawIdcc = requiredString(obj, "idcc") ?: return null
        val idcc = normalizeIdcc(rawIdcc) ?: return null
        val title = requiredString(obj, "title") ?: return null
        val fileName = requiredString(obj, "fileName") ?: return null
        val path = requiredString(obj, "pathToFile") ?: return null
        val publicationDate = optionalString(obj, "publicationDate"); if (!publicationDate.valid) return null
        val textDate = optionalString(obj, "textDate"); if (!textDate.valid) return null
        val bulletinNumber = optionalString(obj, "bulletinNumber"); if (!bulletinNumber.valid) return null
        val checkedAtMs = requiredLong(obj, "checkedAtMs")?.takeIf { it > 0L } ?: return null
        return Record(
            companyId = companyId,
            referenceAtMs = referenceAtMs,
            idcc = idcc,
            title = title,
            fileName = fileName,
            pathToFile = path,
            publicationDate = publicationDate.value,
            textDate = textDate.value,
            bulletinNumber = bulletinNumber.value,
            checkedAtMs = checkedAtMs
        )
    }

    private fun normalizeIdcc(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank() || raw.any { !it.isDigit() }) return null
        return raw.toIntOrNull()?.takeIf { it > 0 }?.toString()
    }

    private fun requiredString(obj: JSONObject, key: String): String? =
        (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun optionalString(obj: JSONObject, key: String): OptionalString {
        if (!obj.has(key) || obj.isNull(key)) return OptionalString(true, null)
        val raw = obj.opt(key) as? String ?: return OptionalString(false, null)
        return OptionalString(true, raw.trim().takeIf { it.isNotBlank() })
    }

    private fun requiredLong(obj: JSONObject, key: String): Long? {
        val number = obj.opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Long.MIN_VALUE.toDouble() ||
            value > Long.MAX_VALUE.toDouble()) return null
        return number.toLong()
    }

    private fun hasDuplicateIdentity(records: List<Record>): Boolean =
        records.groupingBy {
            "${it.companyId}:${it.idcc}:${referenceDay(it.referenceAtMs)}:${it.fileName}"
        }.eachCount().any { it.value > 1 }

    private fun corruptResult() = ReadResult(emptyList(), false, listOf(STORAGE_WARNING))

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
