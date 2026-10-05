package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerAtMpRateHistoryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** Historique local AT/MP par entreprise, écrit et relu sous le verrou du store confirmé. */
object CompanyAtMpRateStoreV2 {
    private const val KEY = "employer_atmp_rate_history_v2"
    private const val SCHEMA_VERSION = 1
    private const val STORAGE_WARNING = "AT/MP employeur : stockage historique local incohérent ; aucun taux daté n'est appliqué."

    data class ReadResult(val records: List<EmployerAtMpRateHistoryV2.Record>, val reliable: Boolean, val warnings: List<String>)

    fun read(context: Context, companyId: String): ReadResult {
        val id = companyId.trim()
        if (id.isBlank()) return companyUnavailable()
        return SalaryCompanyStore.withConfirmedCompany(context, id) { readConfirmed(context, it.id) }
            ?: companyUnavailable()
    }

    fun save(context: Context, companyId: String, record: EmployerAtMpRateHistoryV2.Record): Boolean {
        val id = companyId.trim()
        if (id.isBlank() || !EmployerAtMpRateHistoryV2.structurallyValid(record)) return false
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            // Un taux d'un autre établissement ne peut pas être confirmé pour celui sélectionné.
            val stored = readConfirmed(context, company.id)
            val updated = recordsAfterSave(stored, record, company.siret.trim()) ?: return@withConfirmedCompany false
            writeConfirmed(context, company.id, updated)
        } == true
    }

    fun remove(context: Context, companyId: String, recordId: String): Boolean {
        val id = companyId.trim()
        if (id.isBlank() || recordId.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            val stored = readConfirmed(context, company.id)
            if (!stored.reliable) return@withConfirmedCompany false
            writeConfirmed(context, company.id, stored.records.filterNot { it.id == recordId })
        } == true
    }

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerAtMpRateHistoryV2.Snapshot {
        val id = companyId.trim()
        if (id.isBlank()) return EmployerAtMpRateHistoryV2.unavailable(companyUnavailable().warnings.single())
        return SalaryCompanyStore.withConfirmedCompany(context, id) { company ->
            resolve(readConfirmed(context, company.id), company.siret.trim(), period)
        } ?: EmployerAtMpRateHistoryV2.unavailable(companyUnavailable().warnings.single())
    }

    internal fun resolve(stored: ReadResult, establishmentSiret: String, period: YearMonth): EmployerAtMpRateHistoryV2.Snapshot {
        if (!stored.reliable) return EmployerAtMpRateHistoryV2.Snapshot(null, null, null, false,
            stored.warnings.ifEmpty { listOf(STORAGE_WARNING) })
        return EmployerAtMpRateHistoryV2.resolve(stored.records, establishmentSiret, period)
    }

    internal fun recordsAfterSave(stored: ReadResult, record: EmployerAtMpRateHistoryV2.Record, establishmentSiret: String): List<EmployerAtMpRateHistoryV2.Record>? {
        if (!stored.reliable || !EmployerAtMpRateHistoryV2.structurallyValid(record) ||
            record.establishmentSiret != establishmentSiret) return null
        return stored.records.filterNot { it.id == record.id } + record
    }

    private fun readConfirmed(context: Context, companyId: String): ReadResult {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return corrupted()
        return decodeRecords(raw)
    }

    internal fun encodeRecords(records: List<EmployerAtMpRateHistoryV2.Record>): String {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject()
            .put("id", record.id).put("establishmentSiret", record.establishmentSiret).put("rate", record.rate)
            .put("effectiveFrom", record.effectiveFrom.toString()).put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
            .put("source", record.source).put("confirmedAtMs", record.confirmedAtMs)) }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("records", array).toString()
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val envelope = JSONObject(raw)
        val version = envelope.opt("schemaVersion")
        if (version !is Number || version.toDouble() != SCHEMA_VERSION.toDouble()) return corrupted()
        val array = envelope.opt("records") as? JSONArray ?: return corrupted()
        val records = mutableListOf<EmployerAtMpRateHistoryV2.Record>()
        for (index in 0 until array.length()) {
            val o = array.opt(index) as? JSONObject ?: return corrupted()
            val id = o.opt("id") as? String ?: return corrupted()
            val siret = o.opt("establishmentSiret") as? String ?: return corrupted()
            val rate = (o.opt("rate") as? Number)?.toDouble() ?: return corrupted()
            val from = LocalDate.parse(o.opt("effectiveFrom") as? String ?: return corrupted())
            val to = when (val value = o.opt("effectiveTo")) {
                JSONObject.NULL -> null
                is String -> LocalDate.parse(value)
                else -> return corrupted()
            }
            val source = o.opt("source") as? String ?: return corrupted()
            val confirmed = o.opt("confirmedAtMs") as? Number ?: return corrupted()
            val ms = confirmed.toLong()
            if (confirmed.toDouble() != ms.toDouble()) return corrupted()
            val record = EmployerAtMpRateHistoryV2.Record(id, siret, rate, from, to, source, ms)
            if (!EmployerAtMpRateHistoryV2.structurallyValid(record)) return corrupted()
            records += record
        }
        if (records.groupingBy { it.id }.eachCount().any { it.value > 1 }) return corrupted()
        ReadResult(records, true, emptyList())
    }.getOrElse { corrupted() }

    private fun writeConfirmed(context: Context, companyId: String, records: List<EmployerAtMpRateHistoryV2.Record>): Boolean {
        val raw = encodeRecords(records)
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.edit().putString(KEY, raw).commit()) return false
        return runCatching { prefs.getString(KEY, null) == raw && decodeRecords(raw).reliable }.getOrDefault(false)
    }

    private fun companyUnavailable() = ReadResult(emptyList(), false,
        listOf("AT/MP employeur : entreprise absente ou stockage entreprises non fiable ; aucune préférence orpheline n'est utilisée."))
    private fun corrupted() = ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
}
