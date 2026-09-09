package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerGeneralReductionContextV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local, mensuel et séparé par entreprise du contexte factuel RGDU. */
object CompanyEmployerGeneralReductionContextStoreV2 {
    private const val KEY = "employer_general_reduction_context_v2"
    private const val STORAGE_WARNING =
        "RGDU : stockage local du contexte mensuel incohérent ; calcul automatique bloqué."

    data class ReadResult(
        val records: List<EmployerGeneralReductionContextV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("RGDU : entreprise non identifiée ; contexte mensuel inaccessible.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerGeneralReductionContextV2.Record> =
        read(context, companyId).records

    fun save(
        context: Context,
        companyId: String,
        record: EmployerGeneralReductionContextV2.Record
    ): Boolean {
        if (companyId.isBlank()) return false
        val stored = read(context, companyId)
        // Une donnée illisible ne doit jamais disparaître par une réécriture automatique du seul
        // sous-ensemble décodable. Une correction explicite sera nécessaire avant toute mutation.
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
    ): EmployerGeneralReductionContextV2.Snapshot {
        val stored = read(context, companyId)
        if (!stored.reliable) {
            return EmployerGeneralReductionContextV2.Snapshot(
                fullMonthPresent = null,
                standardCommonLawCaseConfirmed = null,
                noOtherEmployerReductionConfirmed = null,
                source = null,
                reliable = false,
                warnings = stored.warnings
            )
        }
        return EmployerGeneralReductionContextV2.resolve(stored.records, month)
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerGeneralReductionContextV2.Record>()
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
        items: List<EmployerGeneralReductionContextV2.Record>
    ): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: EmployerGeneralReductionContextV2.Record) = JSONObject()
        .put("id", record.id)
        .put("month", record.month.toString())
        .put("fullMonthPresent", record.fullMonthPresent)
        .put("standardCommonLawCaseConfirmed", record.standardCommonLawCaseConfirmed)
        .put("noOtherEmployerReductionConfirmed", record.noOtherEmployerReductionConfirmed)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerGeneralReductionContextV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val monthRaw = o.opt("month") as? String ?: return null
        val month = runCatching { YearMonth.parse(monthRaw) }.getOrNull() ?: return null
        val fullMonthPresent = o.opt("fullMonthPresent") as? Boolean ?: return null
        val standardCommonLawCaseConfirmed =
            o.opt("standardCommonLawCaseConfirmed") as? Boolean ?: return null
        val noOtherEmployerReductionConfirmed =
            o.opt("noOtherEmployerReductionConfirmed") as? Boolean ?: return null
        val source = o.opt("source") as? String ?: return null
        return EmployerGeneralReductionContextV2.Record(
            id = id,
            month = month,
            fullMonthPresent = fullMonthPresent,
            standardCommonLawCaseConfirmed = standardCommonLawCaseConfirmed,
            noOtherEmployerReductionConfirmed = noOtherEmployerReductionConfirmed,
            source = source
        )
    }
}
