package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local et daté du taux personnel de prélèvement à la source pour une entreprise. */
object CompanyIncomeTaxRateStoreV2 {
    private const val KEY = "income_tax_rates_v2"
    private const val STORAGE_WARNING =
        "PAS : stockage local des taux datés incohérent ; calcul après impôt bloqué et ancien taux non réutilisé."

    data class ReadResult(
        val records: List<CompanyIncomeTaxRateResolverV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    internal fun companyUnavailableResult(): ReadResult = ReadResult(
        records = emptyList(),
        reliable = false,
        warnings = listOf("PAS : entreprise absente ou stockage des entreprises non fiable ; taux personnel inaccessible.")
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) return companyUnavailableResult()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            readConfirmed(context, companyId)
        } ?: companyUnavailableResult()
    }

    fun list(context: Context, companyId: String): List<CompanyIncomeTaxRateResolverV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: CompanyIncomeTaxRateResolverV2.Record): Boolean {
        if (companyId.isBlank() || !valid(record)) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readConfirmed(context, companyId)
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

    fun resolve(context: Context, companyId: String, period: YearMonth): CompanyIncomeTaxRateResolverV2.Snapshot {
        if (companyId.isBlank()) return resolve(companyUnavailableResult(), period)
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            resolve(readConfirmed(context, companyId), period)
        } ?: resolve(companyUnavailableResult(), period)
    }

    /**
     * hasDatedRecords=true est volontaire en cas de corruption : le fallback legacy ne doit
     * jamais masquer un store V2 incohérent en réutilisant un ancien taux sans période.
     */
    internal fun resolve(stored: ReadResult, period: YearMonth): CompanyIncomeTaxRateResolverV2.Snapshot {
        if (stored.reliable) return CompanyIncomeTaxRateResolverV2.resolve(stored.records, period)
        return CompanyIncomeTaxRateResolverV2.Snapshot(
            rate = null,
            ratePercent = null,
            source = null,
            hasDatedRecords = true,
            reliable = false,
            warnings = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) }
        )
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<CompanyIncomeTaxRateResolverV2.Record>()
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
        items: List<CompanyIncomeTaxRateResolverV2.Record>
    ): Boolean {
        if (items.any { !valid(it) } || items.groupingBy { it.id }.eachCount().any { it.value > 1 }) return false
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, array.toString())
            .commit()
    }

    private fun toJson(record: CompanyIncomeTaxRateResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("ratePercent", record.ratePercent)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyIncomeTaxRateResolverV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val ratePercent = (o.opt("ratePercent") as? Number)?.toDouble() ?: return null
        val effectiveFrom = parseRequiredMonth(o, "effectiveFrom") ?: return null
        val effectiveTo = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = (o.opt("source") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return CompanyIncomeTaxRateResolverV2.Record(
            id = id,
            ratePercent = ratePercent,
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

    private fun valid(record: CompanyIncomeTaxRateResolverV2.Record): Boolean {
        if (record.id.isBlank()) return false
        if (!record.ratePercent.isFinite() || record.ratePercent < 0.0 || record.ratePercent > 100.0) return false
        val start = record.effectiveFrom ?: return false
        val end = record.effectiveTo
        if (end != null && end < start) return false
        return !record.source.isNullOrBlank()
    }
}
