package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualContextV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.json.JSONArray
import org.json.JSONObject

/** Stockage local, annuel et séparé par entreprise du contexte factuel RGDU. */
object CompanyEmployerGeneralReductionAnnualContextStoreV2 {
    private const val KEY = "employer_general_reduction_annual_context_v2"
    private const val STORAGE_WARNING =
        "RGDU annuelle : stockage local du contexte incohérent ; calcul automatique bloqué."

    data class ReadResult(
        val records: List<EmployerGeneralReductionAnnualContextV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("RGDU annuelle : entreprise non identifiée ; contexte annuel inaccessible.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerGeneralReductionAnnualContextV2.Record> =
        read(context, companyId).records

    fun save(
        context: Context,
        companyId: String,
        record: EmployerGeneralReductionAnnualContextV2.Record
    ): Boolean {
        if (companyId.isBlank()) return false
        val stored = read(context, companyId)
        // Ne jamais écraser implicitement un stockage partiellement illisible avec son seul
        // sous-ensemble décodable : l'incohérence doit rester visible jusqu'à correction explicite.
        if (!stored.reliable) return false
        val items = stored.records.filterNot { it.year == record.year }.toMutableList()
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
        year: Int
    ): EmployerGeneralReductionAnnualContextV2.Snapshot {
        val stored = read(context, companyId)
        if (!stored.reliable) {
            return EmployerGeneralReductionAnnualContextV2.Snapshot(
                fullCalendarYearPresent = null,
                standardCommonLawCaseConfirmed = null,
                homogeneousAnnualParametersConfirmed = null,
                source = null,
                reliable = false,
                warnings = stored.warnings
            )
        }
        return EmployerGeneralReductionAnnualContextV2.resolve(stored.records, year)
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerGeneralReductionAnnualContextV2.Record>()
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
        items: List<EmployerGeneralReductionAnnualContextV2.Record>
    ): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: EmployerGeneralReductionAnnualContextV2.Record) = JSONObject()
        .put("id", record.id)
        .put("year", record.year)
        .put("fullCalendarYearPresent", record.fullCalendarYearPresent)
        .put("standardCommonLawCaseConfirmed", record.standardCommonLawCaseConfirmed)
        .put("homogeneousAnnualParametersConfirmed", record.homogeneousAnnualParametersConfirmed ?: JSONObject.NULL)
        .put("source", record.source)
        .put("confirmedWorkforceBand", record.confirmedWorkforceBand?.name ?: JSONObject.NULL)
        .put("confirmedContractType", record.confirmedContractType?.name ?: JSONObject.NULL)
        .put("confirmedContractualWeeklyMinutes", record.confirmedContractualWeeklyMinutes ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): EmployerGeneralReductionAnnualContextV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val year = (o.opt("year") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
        val fullCalendarYearPresent = o.opt("fullCalendarYearPresent") as? Boolean ?: return null
        val standardCommonLawCaseConfirmed =
            o.opt("standardCommonLawCaseConfirmed") as? Boolean ?: return null
        val homogeneousAnnualParametersConfirmed = when (val value = o.opt("homogeneousAnnualParametersConfirmed")) {
            null, JSONObject.NULL -> null
            is Boolean -> value
            else -> return null
        }
        val source = o.opt("source") as? String ?: return null
        val confirmedWorkforceBand = when (val value = o.opt("confirmedWorkforceBand")) {
            null, JSONObject.NULL -> null
            is String -> runCatching { EmployerWorkforceContributionsV2.Band.valueOf(value) }.getOrNull() ?: return null
            else -> return null
        }
        val confirmedContractType = when (val value = o.opt("confirmedContractType")) {
            null, JSONObject.NULL -> null
            is String -> runCatching { ContractTypeV2.valueOf(value) }.getOrNull() ?: return null
            else -> return null
        }
        val confirmedContractualWeeklyMinutes = when (val value = o.opt("confirmedContractualWeeklyMinutes")) {
            null, JSONObject.NULL -> null
            is Number -> {
                val raw = value.toDouble()
                val minutes = value.toInt()
                minutes.takeIf { raw.isFinite() && it > 0 && raw == it.toDouble() } ?: return null
            }
            else -> return null
        }
        return EmployerGeneralReductionAnnualContextV2.Record(
            id = id,
            year = year,
            fullCalendarYearPresent = fullCalendarYearPresent,
            standardCommonLawCaseConfirmed = standardCommonLawCaseConfirmed,
            homogeneousAnnualParametersConfirmed = homogeneousAnnualParametersConfirmed,
            source = source,
            confirmedWorkforceBand = confirmedWorkforceBand,
            confirmedContractType = confirmedContractType,
            confirmedContractualWeeklyMinutes = confirmedContractualWeeklyMinutes
        )
    }
}
