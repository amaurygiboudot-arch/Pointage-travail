package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConfirmedProrationMethodV2
import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

data class SegmentedProrationSourceV2(
    val proration: ConfirmedSegmentedMonthlyProrationV2?,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Stockage Android d'une proratisation mensuelle explicitement confirmée.
 *
 * Aucune absence de donnée n'est transformée en prorata. La validation contre la timeline
 * contractuelle reste faite par ConfirmedSegmentedMonthlyProrationCalculatorV2.
 */
object V2SegmentedProrationStore {
    const val PREFS = "horatrack_v2_segmented_proration"
    const val MISSING_WARNING =
        "Proratisation mensuelle : aucune base planifiée confirmée n'est disponible pour ce mois."
    const val STORAGE_WARNING =
        "Proratisation mensuelle : stockage local incohérent ; aucun prorata n'est utilisable."
    const val COMPANY_WARNING =
        "Proratisation mensuelle : entreprise absente ou stockage des entreprises non fiable."

    private const val PREFIX = "salary_segmented_proration_v2."

    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth
    ): SegmentedProrationSourceV2 {
        val company = companyId.trim()
        if (SalaryCompanyStore.confirmedCompany(
                SalaryCompanyStore.readConfirmed(context), company
            ) == null
        ) {
            return SegmentedProrationSourceV2(null, false, listOf(COMPANY_WARNING))
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(key(company, period))) {
            return SegmentedProrationSourceV2(null, false, listOf(MISSING_WARNING))
        }
        val raw = runCatching { prefs.getString(key(company, period), null) }.getOrNull()
            ?: return SegmentedProrationSourceV2(null, false, listOf(STORAGE_WARNING))
        val value = decode(raw)
            ?: return SegmentedProrationSourceV2(null, false, listOf(STORAGE_WARNING))
        return SegmentedProrationSourceV2(value, true, emptyList())
    }

    @Synchronized
    fun save(
        context: Context,
        companyId: String,
        period: YearMonth,
        proration: ConfirmedSegmentedMonthlyProrationV2
    ): Boolean = SalaryCompanyStore.withConfirmedCompany(context, companyId.trim()) {
        val raw = encode(proration) ?: return@withConfirmedCompany false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(key(companyId.trim(), period), raw).commit()) {
            return@withConfirmedCompany false
        }
        val read = resolve(context, companyId, period)
        read.reliable && read.proration == proration
    } ?: false

    @Synchronized
    fun remove(context: Context, companyId: String, period: YearMonth): Boolean =
        SalaryCompanyStore.withConfirmedCompany(context, companyId.trim()) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val k = key(companyId.trim(), period)
            if (!prefs.contains(k)) return@withConfirmedCompany true
            prefs.edit().remove(k).commit() && !prefs.contains(k)
        } ?: false

    internal fun decode(raw: String): ConfirmedSegmentedMonthlyProrationV2? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val sourceId = root.optString("sourceId").trim()
        val checkedAtMs = strictLong(root.opt("checkedAtMs")) ?: return null
        val method = runCatching {
            ConfirmedProrationMethodV2.valueOf(root.optString("method"))
        }.getOrNull() ?: return null
        val array = root.optJSONArray("segments") ?: return null
        val segments = mutableListOf<ConfirmedProrationSegmentV2>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val versionId = item.optString("versionId").trim()
            val start = strictLong(item.opt("startEpochDay")) ?: return null
            val end = strictLong(item.opt("endEpochDay")) ?: return null
            val minutesLong = strictLong(item.opt("scheduledMinutes")) ?: return null
            val minutes = minutesLong.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                ?: return null
            segments += ConfirmedProrationSegmentV2(versionId, start, end, minutes)
        }
        val result = ConfirmedSegmentedMonthlyProrationV2(
            sourceId = sourceId,
            checkedAtMs = checkedAtMs,
            method = method,
            segments = segments
        )
        return result.takeIf(::valid)
    }

    internal fun encode(value: ConfirmedSegmentedMonthlyProrationV2): String? {
        if (!valid(value)) return null
        val array = JSONArray()
        value.segments.forEach {
            array.put(
                JSONObject()
                    .put("versionId", it.versionId.trim())
                    .put("startEpochDay", it.startEpochDay)
                    .put("endEpochDay", it.endEpochDay)
                    .put("scheduledMinutes", it.scheduledMinutes)
            )
        }
        return JSONObject()
            .put("sourceId", value.sourceId.trim())
            .put("checkedAtMs", value.checkedAtMs)
            .put("method", value.method.name)
            .put("segments", array)
            .toString()
    }

    internal fun valid(value: ConfirmedSegmentedMonthlyProrationV2): Boolean {
        if (value.sourceId.trim().isEmpty() ||
            value.checkedAtMs < 0L ||
            value.method != ConfirmedProrationMethodV2.SCHEDULED_MINUTES ||
            value.segments.isEmpty()
        ) return false
        val keys = mutableSetOf<Triple<String, Long, Long>>()
        var total = 0L
        for (segment in value.segments) {
            val version = segment.versionId.trim()
            if (version.isEmpty() ||
                segment.endEpochDay < segment.startEpochDay ||
                segment.scheduledMinutes < 0 ||
                !keys.add(Triple(version, segment.startEpochDay, segment.endEpochDay))
            ) return false
            val next = total + segment.scheduledMinutes.toLong()
            if (next < total) return false
            total = next
        }
        return total > 0L
    }

    private fun key(companyId: String, period: YearMonth): String =
        PREFIX + companyId.trim() + "." + period.toString()

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> (value as Number).toDouble()
            .takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
