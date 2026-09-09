package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerReductionAdjustmentV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

object CompanyEmployerReductionStoreV2 {
    private const val KEY = "employer_reductions_v2"
    private const val STORAGE_WARNING =
        "Réductions/exonérations patronales : stockage local incohérent ; aucun ajustement automatique n'est appliqué."

    data class ReadResult(
        val records: List<EmployerReductionAdjustmentV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("Réductions/exonérations patronales : entreprise non identifiée ; lecture bloquée.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerReductionAdjustmentV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerReductionAdjustmentV2.Record): Boolean {
        if (companyId.isBlank()) return false
        val stored = read(context, companyId)
        // Ne jamais « réparer » implicitement un stockage partiellement illisible en réécrivant
        // uniquement le sous-ensemble décodable : cela effacerait une incohérence à auditer.
        if (!stored.reliable) return false
        val items = stored.records.filterNot { it.month == record.month }.toMutableList()
        items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank()) return false
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        return write(context, companyId, stored.records.filterNot { it.id == id })
    }

    fun resolve(
        context: Context,
        companyId: String,
        month: YearMonth
    ): EmployerReductionAdjustmentV2.Snapshot {
        val stored = read(context, companyId)
        if (!stored.reliable) {
            return EmployerReductionAdjustmentV2.Snapshot(
                amount = null,
                source = null,
                note = null,
                reliable = false,
                warnings = stored.warnings
            )
        }
        return EmployerReductionAdjustmentV2.resolve(stored.records, month)
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerReductionAdjustmentV2.Record>()
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
        items: List<EmployerReductionAdjustmentV2.Record>
    ): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: EmployerReductionAdjustmentV2.Record) = JSONObject()
        .put("id", record.id)
        .put("month", record.month.toString())
        .put("amount", record.totalReductionAmount)
        .put("source", record.source)
        .put("note", record.note)

    private fun fromJson(o: JSONObject?): EmployerReductionAdjustmentV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val monthRaw = o.opt("month") as? String ?: return null
        val month = runCatching { YearMonth.parse(monthRaw) }.getOrNull() ?: return null
        val amount = (o.opt("amount") as? Number)?.toDouble() ?: return null
        val source = o.opt("source") as? String ?: return null
        val note = when (val value = o.opt("note")) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> return null
        }
        return EmployerReductionAdjustmentV2.Record(id, month, amount, source, note)
    }
}
