package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConfirmedProrationMethodV2
import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.json.JSONArray
import org.json.JSONObject

data class SegmentedProrationSourceV2(
    val proration: ConfirmedSegmentedMonthlyProrationV2?,
    val reliable: Boolean,
    val warnings: List<String>
)

object V2SegmentedProrationStore {
    const val PREFS = "horatrack_v2_segmented_proration"
    const val MISSING_WARNING =
        "Proratisation mensuelle : aucune base planifiée confirmée n'est disponible pour ce mois."
    const val STORAGE_WARNING =
        "Proratisation mensuelle : stockage local incohérent ; aucun prorata n'est utilisable."
    const val COMPANY_WARNING =
        "Proratisation mensuelle : entreprise absente ou stockage des entreprises non fiable."

    internal fun storageKey(companyId: String, year: Int, monthZeroBased: Int): String? {
        val id = companyId.trim()
        if (id.isBlank() || year !in 1900..2200 || monthZeroBased !in 0..11) return null
        return id + "." + year + "-" + (monthZeroBased + 1).toString().padStart(2, '0')
    }

    fun resolve(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int
    ): SegmentedProrationSourceV2 {
        val id = companyId.trim()
        val company = SalaryCompanyStore.confirmedCompany(
            SalaryCompanyStore.readConfirmed(context), id
        ) ?: return SegmentedProrationSourceV2(null, false, listOf(COMPANY_WARNING))
        val key = storageKey(company.id, year, monthZeroBased)
            ?: return SegmentedProrationSourceV2(null, false, listOf(STORAGE_WARNING))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(key)) {
            return SegmentedProrationSourceV2(null, false, listOf(MISSING_WARNING))
        }
        val raw = runCatching { prefs.getString(key, null) }.getOrNull()
            ?: return SegmentedProrationSourceV2(null, false, listOf(STORAGE_WARNING))
        val value = decode(raw)
            ?: return SegmentedProrationSourceV2(null, false, listOf(STORAGE_WARNING))
        return SegmentedProrationSourceV2(value, true, emptyList())
    }

    @Synchronized
    fun save(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int,
        proration: ConfirmedSegmentedMonthlyProrationV2
    ): Boolean {
        val id = companyId.trim()
        val company = SalaryCompanyStore.confirmedCompany(
            SalaryCompanyStore.readConfirmed(context), id
        ) ?: return false
        val key = storageKey(company.id, year, monthZeroBased) ?: return false
        val raw = encode(proration) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(key, raw).commit()) return false
        return resolve(context, company.id, year, monthZeroBased).proration == proration
    }

    @Synchronized
    fun remove(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int
    ): Boolean {
        val id = companyId.trim()
        val company = SalaryCompanyStore.confirmedCompany(
            SalaryCompanyStore.readConfirmed(context), id
        ) ?: return false
        val key = storageKey(company.id, year, monthZeroBased) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().remove(key).commit()) return false
        return !prefs.contains(key)
    }

    internal fun readRaw(context: Context): Map<String, String>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = linkedMapOf<String, String>()
        for ((key, value) in prefs.all) {
            val raw = value as? String ?: return null
            if (!isStorageKey(key) || decode(raw) == null) return null
            result[key] = raw
        }
        return result
    }

    internal fun mergeRaw(
        current: Map<String, String>,
        saved: Map<String, String>
    ): Map<String, String>? {
        if (!validRawMap(current) || !validRawMap(saved)) return null
        val merged = current.toMutableMap()
        for ((key, value) in saved) {
            val local = merged[key]
            if (local != null && local != value) return null
            merged[key] = value
        }
        return merged.toSortedMap()
    }

    internal fun replaceAllForRestore(
        context: Context,
        values: Map<String, String>
    ): Boolean {
        if (!validRawMap(values)) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().clear()
        values.toSortedMap().forEach { (key, value) -> editor.putString(key, value) }
        if (!editor.commit()) return false
        return readRaw(context) == values.toSortedMap()
    }

    internal fun isStorageKey(key: String): Boolean {
        val lastDot = key.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == key.lastIndex) return false
        val company = key.substring(0, lastDot).trim()
        val period = key.substring(lastDot + 1)
        val parts = period.split('-')
        if (company.isBlank() || parts.size != 2) return false
        val year = parts[0].toIntOrNull() ?: return false
        val month = parts[1].toIntOrNull() ?: return false
        return year in 1900..2200 && month in 1..12 &&
            parts[1].length == 2
    }

    private fun validRawMap(values: Map<String, String>): Boolean =
        values.all { (key, raw) -> isStorageKey(key) && decode(raw) != null }

    internal fun encode(value: ConfirmedSegmentedMonthlyProrationV2): String? {
        if (!valid(value)) return null
        val segments = JSONArray()
        value.segments.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("versionId", segment.versionId.trim())
                    .put("startEpochDay", segment.startEpochDay)
                    .put("endEpochDay", segment.endEpochDay)
                    .put("scheduledMinutes", segment.scheduledMinutes)
            )
        }
        return JSONObject()
            .put("sourceId", value.sourceId.trim())
            .put("checkedAtMs", value.checkedAtMs)
            .put("method", value.method.name)
            .put("segments", segments)
            .toString()
    }

    internal fun decode(raw: String): ConfirmedSegmentedMonthlyProrationV2? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val sourceId = root.optString("sourceId").trim().takeIf { it.isNotBlank() } ?: return null
        val checkedAt = strictLong(root.opt("checkedAtMs")) ?: return null
        val method = runCatching {
            ConfirmedProrationMethodV2.valueOf(root.optString("method"))
        }.getOrNull() ?: return null
        val array = root.optJSONArray("segments") ?: return null
        val segments = mutableListOf<ConfirmedProrationSegmentV2>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            val versionId = item.optString("versionId").trim().takeIf { it.isNotBlank() } ?: return null
            val start = strictLong(item.opt("startEpochDay")) ?: return null
            val end = strictLong(item.opt("endEpochDay")) ?: return null
            val minutes = strictLong(item.opt("scheduledMinutes"))
                ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                ?.toInt() ?: return null
            segments += ConfirmedProrationSegmentV2(versionId, start, end, minutes)
        }
        return ConfirmedSegmentedMonthlyProrationV2(
            sourceId = sourceId,
            checkedAtMs = checkedAt,
            method = method,
            segments = segments
        ).takeIf(::valid)
    }

    internal fun valid(value: ConfirmedSegmentedMonthlyProrationV2): Boolean {
        if (value.sourceId.trim().isBlank() ||
            value.checkedAtMs < 0L ||
            value.method != ConfirmedProrationMethodV2.SCHEDULED_MINUTES ||
            value.segments.isEmpty()
        ) return false
        val keys = mutableSetOf<Triple<String, Long, Long>>()
        var total = 0L
        for (segment in value.segments) {
            val version = segment.versionId.trim()
            if (version.isBlank() || segment.endEpochDay < segment.startEpochDay ||
                segment.scheduledMinutes < 0 ||
                !keys.add(Triple(version, segment.startEpochDay, segment.endEpochDay))
            ) return false
            val minutes = segment.scheduledMinutes.toLong()
            if (total > Long.MAX_VALUE - minutes) return false
            total += minutes
        }
        return total > 0L
    }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> (value as Number).toDouble()
            .takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
