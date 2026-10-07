package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONObject
import java.time.YearMonth

/** Observed monthly transfers, never inferred from an absence estimate or added to employer pay. */
object ConfirmedMonthlySicknessCashV2 {
    data class Record(val companyId: String, val period: String, val directEmployeeNetBeforeTax: Double?,
        val subrogatedEmployerNetBeforeTax: Double?, val source: String, val confirmedAtMs: Long)
    data class Result(val record: Record?, val reliable: Boolean, val warnings: List<String>)
    private const val PREFS = "salary_confirmed_sickness_cash_v2"
    private const val WARNING = "IJSS mensuelles : source locale à vérifier ; aucun montant confirmé utilisable."
    fun valid(record: Record): Boolean = record.companyId.isNotBlank() && !record.companyId.contains("|") &&
        runCatching { YearMonth.parse(record.period).year in 1..9999 }.getOrDefault(false) && record.source.trim().isNotEmpty() &&
        record.source.length <= 500 && record.confirmedAtMs > 0 &&
        (record.directEmployeeNetBeforeTax != null || record.subrogatedEmployerNetBeforeTax != null) &&
        listOfNotNull(record.directEmployeeNetBeforeTax, record.subrogatedEmployerNetBeforeTax).all { it.isFinite() && it >= 0.0 }
    private fun key(companyId: String, period: String) = "$companyId|$period"
    fun read(context: Context, companyId: String, period: String): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(companyId, period)
        if (!prefs.contains(key)) return Result(null, true, emptyList())
        return decode(runCatching { prefs.getString(key, null) }.getOrNull(), companyId, period)
    }
    internal fun decode(raw: String?, companyId: String, period: String): Result = runCatching {
        val json = JSONObject(raw ?: error("invalid"))
        fun integer(name: String): Long {
            val number = json.get(name)
            require(number is Number)
            return java.math.BigDecimal(number.toString()).longValueExact()
        }
        require(integer("version") == 1L)
        require(json.get("companyId") is String && json.get("period") is String && json.get("source") is String)
        fun amount(name: String): Double? {
            require(json.has(name))
            if (json.isNull(name)) return null
            require(json.get(name) is Number)
            return json.getDouble(name)
        }
        val record = Record(json.getString("companyId"), json.getString("period"), amount("direct"), amount("subrogated"), json.getString("source"), integer("confirmedAt"))
        require(valid(record) && record.companyId == companyId && record.period == period)
        Result(record, true, emptyList())
    }.getOrElse { Result(null, false, listOf(WARNING)) }
    fun save(context: Context, record: Record, confirmed: Boolean): Boolean {
        if (!confirmed || !valid(record)) return false
        // Never overwrite unreadable existing evidence silently.
        if (!read(context, record.companyId, record.period).reliable) return false
        val raw = JSONObject().put("version", 1).put("companyId", record.companyId).put("period", record.period)
            .put("direct", record.directEmployeeNetBeforeTax ?: JSONObject.NULL)
            .put("subrogated", record.subrogatedEmployerNetBeforeTax ?: JSONObject.NULL)
            .put("source", record.source).put("confirmedAt", record.confirmedAtMs).toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.edit().putString(key(record.companyId, record.period), raw).commit() &&
            read(context, record.companyId, record.period).let { it.reliable && it.record == record }
    }
    fun remove(context: Context, companyId: String, period: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(companyId, period)).commit()
}
