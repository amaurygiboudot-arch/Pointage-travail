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

    internal fun companyUnavailableResult(companyId: String): ReadResult {
        val id = companyId.trim()
        val warning = if (id.isBlank()) {
            "RGDU observée : entreprise non identifiée ; stockage inaccessible."
        } else {
            "RGDU observée : entreprise $id absente ou stockage entreprises non fiable ; aucune avance observée orpheline n'est utilisée."
        }
        return ReadResult(emptyList(), false, listOf(warning))
    }

    fun read(context: Context, companyId: String): ReadResult {
        val id = companyId.trim()
        if (id.isBlank()) return companyUnavailableResult(id)
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            readConfirmed(context, company.id)
        } ?: companyUnavailableResult(id)
    }

    fun list(context: Context, companyId: String): List<EmployerGeneralReductionObservedAdvanceV2.Record> =
        read(context, companyId).records

    fun save(
        context: Context,
        companyId: String,
        record: EmployerGeneralReductionObservedAdvanceV2.Record
    ): Boolean {
        val id = companyId.trim()
        if (id.isBlank()) return false
        if (!record.amount.isFinite() || record.amount < 0.0 || record.id.isBlank() || record.source.isBlank()) {
            return false
        }
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            val stored = readConfirmed(context, company.id)
            // Un stockage partiellement illisible ne doit jamais être écrasé par une réécriture
            // silencieuse de la seule partie décodable.
            if (!stored.reliable) return@withConfirmedCompany false
            val items = stored.records.filterNot { it.month == record.month }.toMutableList()
            items += record
            writeConfirmed(context, company.id, items)
        } == true
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        val companyIdNormalized = companyId.trim()
        if (companyIdNormalized.isBlank() || id.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyIdNormalized) { company ->
            val stored = readConfirmed(context, company.id)
            if (!stored.reliable) return@withConfirmedCompany false
            writeConfirmed(context, company.id, stored.records.filterNot { it.id == id })
        } == true
    }

    fun resolveYear(
        context: Context,
        companyId: String,
        year: Int
    ): EmployerGeneralReductionObservedAdvanceV2.YearSnapshot {
        val id = companyId.trim()
        if (id.isBlank()) return resolveYear(companyUnavailableResult(id), year)
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            resolveYear(readConfirmed(context, company.id), year)
        } ?: resolveYear(companyUnavailableResult(id), year)
    }

    internal fun resolveYear(
        stored: ReadResult,
        year: Int
    ): EmployerGeneralReductionObservedAdvanceV2.YearSnapshot {
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

    private fun readConfirmed(context: Context, companyId: String): ReadResult {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    private fun writeConfirmed(
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
