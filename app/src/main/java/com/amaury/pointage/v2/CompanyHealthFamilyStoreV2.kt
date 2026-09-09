package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerHealthFamilyV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage des taux patronaux maladie/allocations familiales, sauvegardé avec chaque entreprise Salaire V2. */
object CompanyHealthFamilyStoreV2 {
    private const val KEY = "employer_health_family_v2"
    private const val STORAGE_WARNING =
        "Maladie/allocations familiales employeur : stockage local incohérent ; calcul patronal bloqué."

    data class ReadResult(
        val records: List<EmployerHealthFamilyV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(emptyList(), false, listOf("Maladie/allocations familiales employeur : entreprise non identifiée."))
        }
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerHealthFamilyV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerHealthFamilyV2.Record): Boolean {
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

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerHealthFamilyV2.Snapshot =
        resolve(read(context, companyId), period)

    internal fun resolve(stored: ReadResult, period: YearMonth): EmployerHealthFamilyV2.Snapshot {
        if (!stored.reliable) {
            return EmployerHealthFamilyV2.Snapshot(
                healthRate = null,
                familyRate = null,
                source = null,
                reliable = false,
                warnings = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) }
            )
        }
        return EmployerHealthFamilyV2.resolve(stored.records, period)
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerHealthFamilyV2.Record>()
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

    private fun write(context: Context, companyId: String, items: List<EmployerHealthFamilyV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerHealthFamilyV2.Record) = JSONObject()
        .put("id", record.id)
        .put("healthRate", record.healthRate)
        .put("familyRate", record.familyRate)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source)

    private fun fromJson(o: JSONObject?): EmployerHealthFamilyV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val healthRate = (o.opt("healthRate") as? Number)?.toDouble() ?: return null
        val familyRate = (o.opt("familyRate") as? Number)?.toDouble() ?: return null
        val fromRaw = o.opt("effectiveFrom") as? String ?: return null
        val from = runCatching { YearMonth.parse(fromRaw) }.getOrNull() ?: return null
        val to = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = o.opt("source") as? String ?: return null
        return EmployerHealthFamilyV2.Record(id, healthRate, familyRate, from, to.value, source)
    }

    private data class OptionalMonth(val value: YearMonth?)

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalMonth(null)
        else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        else -> null
    }
}
