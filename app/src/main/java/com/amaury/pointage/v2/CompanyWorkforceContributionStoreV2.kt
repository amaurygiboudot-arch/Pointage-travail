package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage des paramètres effectif/FNAL/formation, sauvegardés avec l'entreprise Salaire V2. */
object CompanyWorkforceContributionStoreV2 {
    private const val KEY = "employer_workforce_contributions_v2"
    private const val STORAGE_WARNING =
        "Effectif employeur : stockage local incohérent ; FNAL/formation bloqués."

    data class ReadResult(
        val records: List<EmployerWorkforceContributionsV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(emptyList(), false, listOf("Effectif employeur : entreprise non identifiée."))
        }
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun list(context: Context, companyId: String): List<EmployerWorkforceContributionsV2.Record> =
        read(context, companyId).records

    fun save(context: Context, companyId: String, record: EmployerWorkforceContributionsV2.Record): Boolean {
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

    fun resolve(context: Context, companyId: String, period: YearMonth): EmployerWorkforceContributionsV2.Snapshot =
        resolve(read(context, companyId), period)

    internal fun resolve(stored: ReadResult, period: YearMonth): EmployerWorkforceContributionsV2.Snapshot {
        if (!stored.reliable) {
            return EmployerWorkforceContributionsV2.Snapshot(
                band = null,
                source = null,
                reliable = false,
                warnings = stored.warnings.ifEmpty { listOf(STORAGE_WARNING) },
                fnalTreatment = null,
                trainingTreatment = null
            )
        }
        return EmployerWorkforceContributionsV2.resolve(stored.records, period)
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<EmployerWorkforceContributionsV2.Record>()
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

    private fun write(context: Context, companyId: String, items: List<EmployerWorkforceContributionsV2.Record>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId).edit().putString(KEY, array.toString()).commit()
    }

    private fun toJson(record: EmployerWorkforceContributionsV2.Record) = JSONObject()
        .put("id", record.id)
        .put("band", record.band.name)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("source", record.source)
        .put("fnalTreatment", record.fnalTreatment?.name ?: JSONObject.NULL)
        .put("trainingTreatment", record.trainingTreatment?.name ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): EmployerWorkforceContributionsV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bandRaw = o.opt("band") as? String ?: return null
        val band = runCatching { EmployerWorkforceContributionsV2.Band.valueOf(bandRaw) }.getOrNull()
            ?: return null
        val fromRaw = o.opt("effectiveFrom") as? String ?: return null
        val from = runCatching { YearMonth.parse(fromRaw) }.getOrNull() ?: return null
        val to = parseOptionalMonth(o, "effectiveTo") ?: return null
        val source = o.opt("source") as? String ?: return null
        val fnalTreatment = parseOptionalFnalTreatment(o, "fnalTreatment") ?: return null
        val trainingTreatment = parseOptionalTrainingTreatment(o, "trainingTreatment") ?: return null
        return EmployerWorkforceContributionsV2.Record(
            id = id,
            band = band,
            effectiveFrom = from,
            effectiveTo = to.value,
            source = source,
            fnalTreatment = fnalTreatment.value,
            trainingTreatment = trainingTreatment.value
        )
    }

    private data class OptionalMonth(val value: YearMonth?)
    private data class OptionalFnalTreatment(val value: EmployerWorkforceContributionsV2.FnalTreatment?)
    private data class OptionalTrainingTreatment(val value: EmployerWorkforceContributionsV2.TrainingTreatment?)

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalMonth(null)
        else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        else -> null
    }

    private fun parseOptionalFnalTreatment(o: JSONObject, key: String): OptionalFnalTreatment? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalFnalTreatment(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalFnalTreatment(null)
        else runCatching {
            OptionalFnalTreatment(EmployerWorkforceContributionsV2.FnalTreatment.valueOf(raw))
        }.getOrNull()
        else -> null
    }

    private fun parseOptionalTrainingTreatment(o: JSONObject, key: String): OptionalTrainingTreatment? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalTrainingTreatment(null)
        is String -> if (raw.isBlank() || raw == "null") OptionalTrainingTreatment(null)
        else runCatching {
            OptionalTrainingTreatment(EmployerWorkforceContributionsV2.TrainingTreatment.valueOf(raw))
        }.getOrNull()
        else -> null
    }
}
