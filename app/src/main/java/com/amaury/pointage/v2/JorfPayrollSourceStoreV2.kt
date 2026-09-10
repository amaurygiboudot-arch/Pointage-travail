package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Piste d'audit JORF locale, isolée par date de paie. */
object JorfPayrollSourceStoreV2 {
    private const val PREFS = "horatrack_v2_jorf_payroll_sources"
    private const val KEY = "verified_jorf"
    internal const val MAX_RECORDS = 160
    private const val STORAGE_WARNING =
        "JORF : stockage local des références officielles illisible ou incohérent ; aucune référence partielle n'est considérée vérifiée."

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
        referenceAtMs: Long,
        verified: List<OfficialJorfVerifierV2.VerifiedReference>
    ): Boolean {
        require(referenceAtMs > 0L) { "Date JORF invalide" }
        val stored = read(context)
        if (!stored.reliable) return false

        val incoming = verified.map {
            Record(
                referenceAtMs = referenceAtMs,
                textCid = it.textCid.trim(),
                title = it.title.trim().take(500),
                nature = it.nature?.trim()?.takeIf(String::isNotBlank),
                legalState = it.legalState?.trim()?.takeIf(String::isNotBlank),
                nor = it.nor?.trim()?.takeIf(String::isNotBlank),
                publicationDate = it.publicationDate.trim(),
                publicationNumber = it.publicationNumber?.trim()?.takeIf(String::isNotBlank),
                containerId = it.containerId.trim(),
                checkedAtMs = it.checkedAtMs
            )
        }
        if (incoming.any { !it.structurallyValid() } || hasDuplicateIdentity(incoming)) return false

        val current = stored.records.filterNot { sameReferenceDay(it.referenceAtMs, referenceAtMs) }
        val updated = current + incoming
        if (!acceptsRecordSet(updated)) return false
        return save(context, updated.sortedByDescending { it.checkedAtMs })
    }

    fun snapshot(context: Context, referenceAtMs: Long): List<Record> =
        snapshotResult(context, referenceAtMs).takeIf { it.reliable }?.records.orEmpty()

    fun snapshotResult(context: Context, referenceAtMs: Long): ReadResult =
        snapshotFrom(read(context), referenceAtMs)

    internal fun snapshotFrom(stored: ReadResult, referenceAtMs: Long): ReadResult {
        if (!stored.reliable) return stored.copy(records = emptyList())
        if (referenceAtMs <= 0L) return corruptResult()
        return stored.copy(
            records = stored.records
                .filter { sameReferenceDay(it.referenceAtMs, referenceAtMs) }
                .sortedByDescending { it.publicationDate }
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
        referenceAtMs > 0L &&
            textCid.matches(Regex("^JORFTEXT\\d+$")) &&
            title.isNotBlank() && title.length <= 500 &&
            publicationDate.isNotBlank() && OfficialJorfVerifierV2.parseDateAtMs(publicationDate) != null &&
            containerId.matches(Regex("^JORFCONT\\d+$")) &&
            checkedAtMs > 0L

    private fun save(context: Context, records: List<Record>): Boolean {
        if (!acceptsRecordSet(records)) return false
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
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }

    private fun decodeRecord(obj: JSONObject): Record? {
        val referenceAtMs = requiredLong(obj, "referenceAtMs")?.takeIf { it > 0L } ?: return null
        val textCid = requiredString(obj, "textCid") ?: return null
        val title = requiredString(obj, "title") ?: return null
        val nature = optionalString(obj, "nature"); if (!nature.valid) return null
        val legalState = optionalString(obj, "legalState"); if (!legalState.valid) return null
        val nor = optionalString(obj, "nor"); if (!nor.valid) return null
        val publicationDate = requiredString(obj, "publicationDate") ?: return null
        val publicationNumber = optionalString(obj, "publicationNumber"); if (!publicationNumber.valid) return null
        val containerId = requiredString(obj, "containerId") ?: return null
        val checkedAtMs = requiredLong(obj, "checkedAtMs")?.takeIf { it > 0L } ?: return null
        return Record(
            referenceAtMs = referenceAtMs,
            textCid = textCid,
            title = title,
            nature = nature.value,
            legalState = legalState.value,
            nor = nor.value,
            publicationDate = publicationDate,
            publicationNumber = publicationNumber.value,
            containerId = containerId,
            checkedAtMs = checkedAtMs
        )
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
        records.groupingBy { "${referenceDay(it.referenceAtMs)}:${it.textCid}" }
            .eachCount().any { it.value > 1 }

    private fun corruptResult() = ReadResult(emptyList(), false, listOf(STORAGE_WARNING))

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
