package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedAdvanceV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/**
 * Stockage local, mensuel et séparé par entreprise des montants RGDU réellement constatés.
 *
 * Ce stockage ne contient jamais le total global des réductions/exonérations patronales.
 */
object CompanyEmployerGeneralReductionObservedAdvanceStoreV2 {
    private const val KEY = "employer_general_reduction_observed_advances_v2"
    private const val STORAGE_WARNING =
        "RGDU observée : stockage local incohérent ; base historique bloquée."

    data class ReadResult(
        val records: List<EmployerGeneralReductionObservedAdvanceV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("RGDU observée : entreprise non identifiée ; stockage inaccessible.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerGeneralReductionObservedAdvanceV2.Record> =
        read(context, companyId).records

    fun save(
        context: Context,
        companyId: String,
        record: EmployerGeneralReductionObservedAdvanceV2.Record
    ): Boolean {
        if (companyId.isBlank()) return false
        if (!record.amount.isFinite() || record.amount < 0.0 || record.id.isBlank() || record.source.isBlank()) {
            return false
        }
        val stored = read(context, companyId)
        // Un stockage partiellement illisible ne doit jamais être écrasé par une réécriture
        // silencieuse de la seule partie décodable.
        if (!stored.reliable) return false
        val items = stored.records.filterNot { it.month == record.month }.toMutableList()
        items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        return write(context, companyId, stored.records.filterNot { it.id == id })
    }

    fun resolveYear(
        context: Context,
        companyId: String,
        year: Int
    ): EmployerGeneralReductionObservedAdvanceV2.YearSnapshot {
        val stored = read(context, companyId)
        if (!stored.reliable) {
            return EmployerGeneralReductionObservedAdvanceV2.YearSnapshot(
                state = EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID,
                monthlyAdvances = emptyList(),
                sources = emptyList(),
                warnings = stored.warnings
            )
        }
        return EmployerGeneralReductionObservedAdvanceV2.resolveYear(stored.records, year)
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerGeneralReductionObservedAdvanceV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    private fun write(
        context: Context,
        companyId: String,
        items: List<EmployerGeneralReductionObservedAdvanceV2.Record>
    ): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: EmployerGeneralReductionObservedAdvanceV2.Record) = JSONObject()
        .put("id", record.id)
        .put("month", record.month.toString())
        .put("amount", record.amount)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerGeneralReductionObservedAdvanceV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val monthRaw = o.opt("month") as? String ?: return null
        val month = runCatching { YearMonth.parse(monthRaw) }.getOrNull() ?: return null
        val amount = (o.opt("amount") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        val source = (o.opt("source") as? String)?.takeIf { it.isNotBlank() } ?: return null
        return EmployerGeneralReductionObservedAdvanceV2.Record(
            id = id,
            month = month,
            amount = amount,
            source = source
        )
    }
}
