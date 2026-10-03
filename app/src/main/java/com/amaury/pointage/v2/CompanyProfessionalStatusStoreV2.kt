package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyProfessionalStatusResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.time.LocalDate

/** Stockage des versions datées et sourcées du statut cadre/non-cadre, isolées par entreprise. */
object CompanyProfessionalStatusStoreV2 {
    private const val KEY = "professional_status_history_v2"
    private const val STORAGE_WARNING =
        "Statut professionnel : stockage local des versions incohérent ; ancien statut non réutilisé."

    data class ReadResult(
        val records: List<CompanyProfessionalStatusResolverV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    internal fun companyUnavailableResult(): ReadResult = ReadResult(
        records = emptyList(),
        reliable = false,
        warnings = listOf("Statut professionnel : entreprise absente ou stockage des entreprises non fiable ; historique inaccessible.")
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) return companyUnavailableResult()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) { company ->
            readConfirmed(context, company.id)
        } ?: companyUnavailableResult()
    }

    fun list(context: Context, companyId: String): List<CompanyProfessionalStatusResolverV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: CompanyProfessionalStatusResolverV2.Record): Boolean {
        if (companyId.isBlank() || !valid(record)) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) { company ->
            val stored = readConfirmed(context, company.id)
            if (!stored.reliable) return@withConfirmedCompany false
            val items = stored.records.toMutableList()
            val index = items.indexOfFirst { it.id == record.id }
            if (index >= 0) items[index] = record else items += record
            writeConfirmed(context, company.id, items)
        } == true
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) { company ->
            val stored = readConfirmed(context, company.id)
            if (!stored.reliable) return@withConfirmedCompany false
            writeConfirmed(context, company.id, stored.records.filterNot { it.id == id })
        } == true
    }

    fun resolve(context: Context, companyId: String, period: YearMonth): CompanyProfessionalStatusResolverV2.Snapshot {
        if (companyId.isBlank()) return resolve(companyUnavailableResult(), period)
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) { company ->
            resolve(readConfirmed(context, company.id), period)
        } ?: resolve(companyUnavailableResult(), period)
    }

    /** La corruption interdit toute promotion de données partielles ou courantes. */
    internal fun resolve(stored: ReadResult, period: YearMonth): CompanyProfessionalStatusResolverV2.Snapshot {
        if (stored.reliable) return CompanyProfessionalStatusResolverV2.resolve(stored.records, period)
        return CompanyProfessionalStatusResolverV2.Snapshot(null, false,
            stored.warnings.ifEmpty { listOf(STORAGE_WARNING) })
    }

    internal fun decode(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<CompanyProfessionalStatusResolverV2.Record>()
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
        val raw = runCatching { SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]") }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decode(raw)
    }

    private fun writeConfirmed(
        context: Context,
        companyId: String,
        items: List<CompanyProfessionalStatusResolverV2.Record>
    ): Boolean {
        if (items.any { !valid(it) } || items.groupingBy { it.id }.eachCount().any { it.value > 1 }) return false
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        val raw = array.toString()
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.edit().putString(KEY, raw).commit()) return false
        return runCatching { prefs.getString(KEY, null) == raw && decode(raw).reliable }.getOrDefault(false)
    }

    private fun toJson(record: CompanyProfessionalStatusResolverV2.Record) = JSONObject()
        .put("id", record.id).put("status", record.status.name)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source).put("confirmedAtMs", record.confirmedAtMs)

    private fun fromJson(o: JSONObject?): CompanyProfessionalStatusResolverV2.Record? = runCatching {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val status = CompanyProfessionalStatusResolverV2.Status.valueOf(o.opt("status") as? String ?: return null)
        val start = LocalDate.parse(o.opt("effectiveFrom") as? String ?: return null)
        val end = when (val raw = o.opt("effectiveTo")) {
            null, JSONObject.NULL -> null
            is String -> LocalDate.parse(raw)
            else -> return null
        }
        val source = (o.opt("source") as? String)?.trim() ?: return null
        val stamp = o.opt("confirmedAtMs") as? Number ?: return null
        val confirmed = stamp.toLong()
        if (stamp.toDouble() != confirmed.toDouble()) return null
        CompanyProfessionalStatusResolverV2.Record(id, status, start, end, source, confirmed).takeIf(::valid)
    }.getOrNull()

    private fun valid(record: CompanyProfessionalStatusResolverV2.Record): Boolean =
        CompanyProfessionalStatusResolverV2.valid(record)
}
