package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerUnemploymentAgsV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage chômage/AGS employeur, isolé et sauvegardé avec chaque entreprise Salaire V2. */
object CompanyUnemploymentAgsStoreV2 {
    private const val KEY = "employer_unemployment_ags_v2"
    private const val STORAGE_WARNING =
        "Chômage/AGS employeur : stockage local incohérent ; calcul patronal bloqué."

    data class ReadResult(
        val records: List<EmployerUnemploymentAgsV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(emptyList(), false, listOf("Chômage/AGS employeur : entreprise non identifiée."))
        }
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerUnemploymentAgsV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerUnemploymentAgsV2.Record): Boolean {
        if (companyId.isBlank()) return false
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        val items = stored.records.toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return write(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        val stored = read(context, companyId)
        if (!stored.reliable) return false
        return write(context, companyId, stored.records.filterNot { it.id == id })
    }

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerUnemploymentAgsV2.Snapshot =
        resolve(read(context, companyId), period)

    internal fun resolve(stored: ReadResult, period: YearMonth): EmployerUnemploymentAgsV2.Snapshot {
        if (!stored.reliable) {
            return EmployerUnemploymentAgsV2.Snapshot(
                unemploymentRate = null,
                agsRate = null,
                source = null,
                reliable = false,
                warnings = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) }
            )
        }
        return EmployerUnemploymentAgsV2.resolve(stored.records, period)
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerUnemploymentAgsV2.Record>()
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

    private fun write(context: Context, companyId: String, items: List<EmployerUnemploymentAgsV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerUnemploymentAgsV2.Record) = JSONObject()
        .put("id", record.id)
        .put("unemploymentRate", record.unemploymentRate)
        .put("agsRate", record.agsRate)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerUnemploymentAgsV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val unemploymentRate = (o.opt("unemploymentRate") as? Number)?.toDouble() ?: return null
        val agsRate = (o.opt("agsRate") as? Number)?.toDouble() ?: return null
        val fromRaw = o.opt("effectiveFrom") as? String ?: return null
        val from = runCatching { YearMonth.parse(fromRaw) }.getOrNull() ?: return null
        val to = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = o.opt("source") as? String ?: return null
        return EmployerUnemploymentAgsV2.Record(id, unemploymentRate, agsRate, from, to.value, source)
    }

    private data class OptionalMonth(val value: YearMonth?)

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalMonth(null)
        else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        else -> null
    }
}
