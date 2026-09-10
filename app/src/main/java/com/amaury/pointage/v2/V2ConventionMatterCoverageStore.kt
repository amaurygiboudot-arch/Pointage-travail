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
    private const val STORAGE_WARNING =
        "Couverture conventionnelle : stockage local des audits officiels incohérent ; aucun droit ni aucune absence de droit ne peut être déduit de cet historique."

    data class ReadResult(
        val records: List<ConventionMatterCoverageV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RECORDS)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_RECORDS, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun records(context: Context): List<ConventionMatterCoverageV2.Record> {
        val stored = read(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.records
    }

    fun save(context: Context, record: ConventionMatterCoverageV2.Record) {
        require(record.structurallyValid()) { "État de couverture conventionnelle invalide" }
        val stored = read(context)
        check(stored.reliable) { STORAGE_WARNING }

        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(record.idcc)
        val normalizedRecord = record.copy(idcc = normalized)
        val current = stored.records.toMutableList()
        val previousSameIdentity = current.filter { sameIdentity(it, normalizedRecord) }
        val inheritedAuthorities = previousSameIdentity.flatMapTo(linkedSetOf()) { previous ->
            buildSet {
                addAll(previous.acquiredAuthorities)
                if (previous.state != ConventionMatterCoverageV2.State.INCOMPLETE) {
                    addAll(previous.authorities)
                }
            }
        }
        val acquiredAuthorities = buildSet {
            addAll(record.acquiredAuthorities)
            addAll(inheritedAuthorities)
            if (record.state != ConventionMatterCoverageV2.State.INCOMPLETE) {
                addAll(record.authorities)
            }
        }
        current.removeAll { sameIdentity(it, normalizedRecord) }
        current += normalizedRecord.copy(acquiredAuthorities = acquiredAuthorities)
        persist(context, current)
    }

    fun resolve(
        context: Context,
        idcc: String,
        matter: ConventionMatterCoverageV2.Matter,
        date: LocalDate,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = null
    ): ConventionMatterCoverageV2.Snapshot = resolveStored(
        stored = read(context),
        idcc = idcc,
        matter = matter,
        date = date,
        classification = classification,
        professionalStatus = professionalStatus
    )

    internal fun resolveStored(
        stored: ReadResult,
        idcc: String,
        matter: ConventionMatterCoverageV2.Matter,
        date: LocalDate,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = null
    ): ConventionMatterCoverageV2.Snapshot {
        if (!stored.reliable) {
            return ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                record = null,
                reliable = false,
                warnings = (listOf(STORAGE_WARNING) + stored.warnings).distinct()
            )
        }
        return ConventionMatterCoverageV2.resolve(
            stored.records,
            idcc,
            matter,
            date,
            classification,
            professionalStatus
        )
    }

    internal fun acceptsPackage(records: List<ConventionMatterCoverageV2.Record>): Boolean =
        records.all { it.structurallyValid() } && !hasDuplicateIdentity(records)

    internal fun decodeRecords(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val records = mutableListOf<ConventionMatterCoverageV2.Record>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val record = obj?.let(::decode)?.takeIf { it.structurallyValid() }
            if (record == null) {
                malformed = true
            } else {
                records += record
            }
        }
        if (hasDuplicateIdentity(records)) malformed = true
        return ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun sameIdentity(
        left: ConventionMatterCoverageV2.Record,
        right: ConventionMatterCoverageV2.Record
    ): Boolean = ConventionMinimumSalaryV2.normalizeIdcc(left.idcc) == ConventionMinimumSalaryV2.normalizeIdcc(right.idcc) &&
        left.matter == right.matter &&
        left.effectiveFrom == right.effectiveFrom &&
        left.effectiveTo == right.effectiveTo &&
        left.classification.normalized() == right.classification.normalized() &&
        left.professionalStatus?.trim()?.uppercase() == right.professionalStatus?.trim()?.uppercase()

    private fun hasDuplicateIdentity(records: List<ConventionMatterCoverageV2.Record>): Boolean =
        records.indices.any { leftIndex ->
            ((leftIndex + 1) until records.size).any { rightIndex ->
                sameIdentity(records[leftIndex], records[rightIndex])
            }
        }

    private fun persist(context: Context, records: List<ConventionMatterCoverageV2.Record>) {
        check(acceptsPackage(records)) {
            "Couverture conventionnelle : historique local invalide ou ambigu."
        }
        val array = JSONArray()
        records.sortedWith(
            compareBy<ConventionMatterCoverageV2.Record> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.matter.name }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORDS, array.toString()).commit()
        }.getOrDefault(false)
        check(saved) { "Couverture conventionnelle : sauvegarde locale impossible." }
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
        .put("acquiredAuthorities", encodeAuthorities(record.acquiredAuthorities))

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
            classification = if (!obj.has("classification") || obj.isNull("classification")) {
                ConventionClassificationV2()
            } else {
                decodeClassification(obj.getJSONObject("classification"))
            },
            professionalStatus = obj.optString("professionalStatus").takeIf { it.isNotBlank() && it != "null" },
            state = ConventionMatterCoverageV2.State.valueOf(obj.getString("state")),
            source = obj.getString("source"),
            checkedAtMs = obj.getLong("checkedAtMs"),
            authorities = decodeAuthorities(obj, "authorities"),
            acquiredAuthorities = decodeAuthorities(obj, "acquiredAuthorities")
        )
    }.getOrNull()

    /** Les tableaux absents restent compatibles avec l'historique ; un tableau présent mais invalide bloque le record. */
    private fun decodeAuthorities(
        obj: JSONObject,
        key: String
    ): Set<ConventionMatterCoverageV2.Authority> {
        if (!obj.has(key) || obj.isNull(key)) return emptySet()
        val array = obj.getJSONArray(key)
        return buildSet {
            for (index in 0 until array.length()) {
                add(ConventionMatterCoverageV2.Authority.valueOf(array.getString(index)))
            }
        }
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
