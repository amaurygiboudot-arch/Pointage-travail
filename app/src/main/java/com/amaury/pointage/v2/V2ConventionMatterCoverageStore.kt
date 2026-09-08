package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Historique local des matières conventionnelles effectivement auditées auprès de sources officielles. */
object V2ConventionMatterCoverageStore {
    private const val PREFS = "horatrack_v2_convention_matter_coverage"
    private const val KEY_RECORDS = "records"

    fun records(context: Context): List<ConventionMatterCoverageV2.Record> = load(context)

    fun save(context: Context, record: ConventionMatterCoverageV2.Record) {
        require(record.structurallyValid()) { "État de couverture conventionnelle invalide" }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(record.idcc)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                it.matter == record.matter &&
                it.effectiveFrom == record.effectiveFrom &&
                it.effectiveTo == record.effectiveTo &&
                it.classification.normalized() == record.classification.normalized() &&
                it.professionalStatus?.trim()?.uppercase() == record.professionalStatus?.trim()?.uppercase()
        }
        current += record.copy(idcc = normalized)
        persist(context, current)
    }

    fun resolve(
        context: Context,
        idcc: String,
        matter: ConventionMatterCoverageV2.Matter,
        date: LocalDate,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = null
    ): ConventionMatterCoverageV2.Snapshot = ConventionMatterCoverageV2.resolve(
        load(context), idcc, matter, date, classification, professionalStatus
    )

    private fun persist(context: Context, records: List<ConventionMatterCoverageV2.Record>) {
        val array = JSONArray()
        records.sortedWith(
            compareBy<ConventionMatterCoverageV2.Record> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.matter.name }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionMatterCoverageV2.Record> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.takeIf { it.structurallyValid() }?.let(::add)
            }
        }
    }

    private fun encode(record: ConventionMatterCoverageV2.Record): JSONObject = JSONObject()
        .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(record.idcc))
        .put("matter", record.matter.name)
        .put("effectiveFrom", record.effectiveFrom.toString())
        .put("effectiveTo", record.effectiveTo?.toString())
        .put("classification", encodeClassification(record.classification))
        .put("professionalStatus", record.professionalStatus)
        .put("state", record.state.name)
        .put("source", record.source)
        .put("checkedAtMs", record.checkedAtMs)
        .put("authorities", encodeAuthorities(record.authorities))

    private fun encodeAuthorities(authorities: Set<ConventionMatterCoverageV2.Authority>): JSONArray = JSONArray().apply {
        authorities.sortedBy { it.name }.forEach { put(it.name) }
    }

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient)
        .put("level", value.level)
        .put("echelon", value.echelon)
        .put("position", value.position)
        .put("group", value.group)
        .put("category", value.category)
        .put("employment", value.employment)

    private fun decode(obj: JSONObject): ConventionMatterCoverageV2.Record? = runCatching {
        ConventionMatterCoverageV2.Record(
            idcc = obj.getString("idcc"),
            matter = ConventionMatterCoverageV2.Matter.valueOf(obj.getString("matter")),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            professionalStatus = obj.optString("professionalStatus").takeIf { it.isNotBlank() && it != "null" },
            state = ConventionMatterCoverageV2.State.valueOf(obj.getString("state")),
            source = obj.getString("source"),
            checkedAtMs = obj.getLong("checkedAtMs"),
            authorities = decodeAuthorities(obj.optJSONArray("authorities"))
        )
    }.getOrNull()

    private fun decodeAuthorities(array: JSONArray?): Set<ConventionMatterCoverageV2.Authority> {
        if (array == null) return emptySet()
        val result = linkedSetOf<ConventionMatterCoverageV2.Authority>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).takeIf { it.isNotBlank() } ?: continue
            runCatching { ConventionMatterCoverageV2.Authority.valueOf(value) }.getOrNull()?.let(result::add)
        }
        return result
    }

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2 = ConventionClassificationV2(
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient").takeIf { it > 0 },
        level = obj.optString("level").takeIf { it.isNotBlank() && it != "null" },
        echelon = obj.optString("echelon").takeIf { it.isNotBlank() && it != "null" },
        position = obj.optString("position").takeIf { it.isNotBlank() && it != "null" },
        group = obj.optString("group").takeIf { it.isNotBlank() && it != "null" },
        category = obj.optString("category").takeIf { it.isNotBlank() && it != "null" },
        employment = obj.optString("employment").takeIf { it.isNotBlank() && it != "null" }
    )
}
