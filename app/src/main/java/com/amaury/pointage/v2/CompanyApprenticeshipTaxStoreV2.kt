package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerApprenticeshipTaxV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage de la taxe d'apprentissage, sauvegardé avec les données de l'entreprise Salaire V2. */
object CompanyApprenticeshipTaxStoreV2 {
    private const val KEY = "employer_apprenticeship_tax_v2"
    private const val STORAGE_WARNING =
        "Taxe d’apprentissage : stockage local incohérent ; calcul patronal bloqué."

    data class ReadResult(
        val records: List<EmployerApprenticeshipTaxV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    internal fun companyUnavailableResult(): ReadResult = ReadResult(
        emptyList(),
        false,
        listOf("Taxe d’apprentissage : entreprise absente ou stockage des entreprises non fiable.")
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) return companyUnavailableResult()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            readConfirmed(context, companyId)
        } ?: companyUnavailableResult()
    }

    fun list(context: Context, companyId: String): List<EmployerApprenticeshipTaxV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerApprenticeshipTaxV2.Record): Boolean {
        if (companyId.isBlank()) return false
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

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerApprenticeshipTaxV2.Snapshot =
        resolve(read(context, companyId), period)

    internal fun resolve(stored: ReadResult, period: YearMonth): EmployerApprenticeshipTaxV2.Snapshot {
        if (!stored.reliable) {
            return EmployerApprenticeshipTaxV2.Snapshot(
                principalRate = null,
                balanceRate = null,
                source = null,
                reliable = false,
                warnings = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) }
            )
        }
        return EmployerApprenticeshipTaxV2.resolve(stored.records, period)
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerApprenticeshipTaxV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        if (records.groupingBy { it.id }.eachCount().any { it.value > 1 }) malformed = true
        ReadResult(records, !malformed, if (malformed) listOf(STORAGE_WARNING) else emptyList())
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    private fun readConfirmed(context: Context, companyId: String): ReadResult {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    private fun writeConfirmed(context: Context, companyId: String, items: List<EmployerApprenticeshipTaxV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerApprenticeshipTaxV2.Record) = JSONObject()
        .put("id", record.id)
        .put("principalRate", record.principalRate)
        .put("balanceRate", record.balanceRate)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerApprenticeshipTaxV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val principal = (o.opt("principalRate") as? Number)?.toDouble() ?: return null
        val balance = (o.opt("balanceRate") as? Number)?.toDouble() ?: return null
        val fromRaw = o.opt("effectiveFrom") as? String ?: return null
        val from = runCatching { YearMonth.parse(fromRaw) }.getOrNull() ?: return null
        val to = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = o.opt("source") as? String ?: return null
        return EmployerApprenticeshipTaxV2.Record(id, principal, balance, from, to.value, source)
    }

    private data class OptionalMonth(val value: YearMonth?)

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalMonth(null)
        else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        else -> null
    }
}
