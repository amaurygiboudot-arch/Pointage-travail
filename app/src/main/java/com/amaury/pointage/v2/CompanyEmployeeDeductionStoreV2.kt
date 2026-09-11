package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyEmployeeDeductionResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local, daté et strictement séparé par entreprise des retenues de bulletin. */
object CompanyEmployeeDeductionStoreV2 {
    private const val KEY = "employee_deductions_v2"
    private const val STORAGE_WARNING =
        "Retenues salarié / fiscales : stockage local incohérent ; calcul bloqué et aucune ancienne valeur n'est réutilisée."

    data class ReadResult(
        val records: List<CompanyEmployeeDeductionResolverV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    internal fun companyUnavailableResult(): ReadResult = ReadResult(
        records = emptyList(),
        reliable = false,
        warnings = listOf("Retenues salarié / fiscales : entreprise absente ou stockage des entreprises non fiable.")
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) return companyUnavailableResult()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            readConfirmed(context, companyId)
        } ?: companyUnavailableResult()
    }

    fun list(context: Context, companyId: String): List<CompanyEmployeeDeductionResolverV2.Record> =
        read(context, companyId).records

    fun save(
        context: Context,
        companyId: String,
        record: CompanyEmployeeDeductionResolverV2.Record
    ): Boolean {
        if (companyId.isBlank() || !valid(record)) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readConfirmed(context, companyId)
            // Ne jamais réécrire silencieusement uniquement le sous-ensemble décodable d'un stockage
            // partiellement corrompu : il faut d'abord rendre l'incohérence visible à l'utilisateur.
            if (!stored.reliable) return@withConfirmedCompany false
            val items = stored.records.toMutableList()
            val index = items.indexOfFirst { it.id == record.id }
            if (index >= 0) items[index] = record else items += record
            writeConfirmed(context, companyId, items)
        } == true
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readConfirmed(context, companyId)
            if (!stored.reliable) return@withConfirmedCompany false
            writeConfirmed(context, companyId, stored.records.filterNot { it.id == id })
        } == true
    }

    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth
    ): CompanyEmployeeDeductionResolverV2.Snapshot {
        if (companyId.isBlank()) return resolve(companyUnavailableResult(), period)
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            resolve(readConfirmed(context, companyId), period)
        } ?: resolve(companyUnavailableResult(), period)
    }

    /**
     * En cas de stockage incohérent, chaque type est marqué comme ayant une donnée datée bloquante.
     * Cela interdit volontairement à `withLegacyFallback` de ressusciter les anciennes préférences
     * non datées et de masquer la corruption du store V2.
     */
    internal fun resolve(
        stored: ReadResult,
        period: YearMonth
    ): CompanyEmployeeDeductionResolverV2.Snapshot {
        if (stored.reliable) {
            return CompanyEmployeeDeductionResolverV2.resolve(stored.records, period)
        }
        val warning = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) }
        return CompanyEmployeeDeductionResolverV2.Snapshot(
            values = CompanyEmployeeDeductionResolverV2.Kind.entries.associateWith {
                CompanyEmployeeDeductionResolverV2.Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = true,
                    reliable = false,
                    warnings = warning
                )
            },
            warnings = warning
        )
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<CompanyEmployeeDeductionResolverV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        if (records.groupingBy { it.id }.eachCount().any { it.value > 1 }) malformed = true
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
        items: List<CompanyEmployeeDeductionResolverV2.Record>
    ): Boolean {
        if (items.any { !valid(it) } || items.groupingBy { it.id }.eachCount().any { it.value > 1 }) {
            return false
        }
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: CompanyEmployeeDeductionResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("kind", record.kind.name)
        .put("amount", record.amount)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyEmployeeDeductionResolverV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val kindRaw = o.opt("kind") as? String ?: return null
        val kind = runCatching {
            CompanyEmployeeDeductionResolverV2.Kind.valueOf(kindRaw)
        }.getOrNull() ?: return null
        val amount = (o.opt("amount") as? Number)?.toDouble() ?: return null
        val effectiveFrom = parseRequiredMonth(o, "effectiveFrom") ?: return null
        val effectiveTo = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = (o.opt("source") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return CompanyEmployeeDeductionResolverV2.Record(
            id = id,
            kind = kind,
            amount = amount,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo.value,
            source = source
        ).takeIf(::valid)
    }

    private data class OptionalMonth(val value: YearMonth?)

    private fun parseRequiredMonth(o: JSONObject, key: String): YearMonth? {
        val raw = o.opt(key) as? String ?: return null
        if (raw.isBlank() || raw == "null") return null
        return runCatching { YearMonth.parse(raw) }.getOrNull()
    }

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> {
            if (raw.isBlank() || raw == "null") OptionalMonth(null)
            else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        }
        else -> null
    }

    private fun valid(record: CompanyEmployeeDeductionResolverV2.Record): Boolean {
        if (record.id.isBlank() || !record.amount.isFinite() || record.amount < 0.0) return false
        if (record.source?.trim().isNullOrEmpty()) return false
        val start = record.effectiveFrom ?: return false
        val end = record.effectiveTo
        return end == null || end >= start
    }
}
