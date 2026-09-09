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

    fun list(context: Context, companyId: String): List<EmployerGeneralReductionContextV2.Record> {
        if (companyId.isBlank()) return emptyList()
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    fromJson(array.optJSONObject(i))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(
        context: Context,
        companyId: String,
        record: EmployerGeneralReductionContextV2.Record
    ): Boolean {
        if (companyId.isBlank()) return false
        // Un seul contexte factuel par mois. Un nouvel enregistrement remplace explicitement
        // le précédent pour ce mois au lieu de créer une ambiguïté silencieuse.
        val items = list(context, companyId).filterNot { it.month == record.month }.toMutableList()
        items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean =
        write(context, companyId, list(context, companyId).filterNot { it.id == id })

    fun resolve(
        context: Context,
        companyId: String,
        month: YearMonth
    ): EmployerGeneralReductionContextV2.Snapshot =
        EmployerGeneralReductionContextV2.resolve(list(context, companyId), month)

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
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val month = o.optString("month")
            .let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: return null
        if (!o.has("fullMonthPresent") || !o.has("standardCommonLawCaseConfirmed") ||
            !o.has("noOtherEmployerReductionConfirmed")) return null
        val source = o.optString("source")
        return EmployerGeneralReductionContextV2.Record(
            id = id,
            month = month,
            fullMonthPresent = o.optBoolean("fullMonthPresent"),
            standardCommonLawCaseConfirmed = o.optBoolean("standardCommonLawCaseConfirmed"),
            noOtherEmployerReductionConfirmed = o.optBoolean("noOtherEmployerReductionConfirmed"),
            source = source
        )
    }
}
