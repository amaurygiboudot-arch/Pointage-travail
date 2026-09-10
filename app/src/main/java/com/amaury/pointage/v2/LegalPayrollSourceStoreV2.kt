package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piste d'audit locale des articles du Code du travail vérifiés pour la paie.
 * Les enregistrements restent des sources documentaires : ils ne deviennent jamais seuls une règle chiffrée.
 */
object LegalPayrollSourceStoreV2 {
    private const val PREFS = "horatrack_v2_legal_payroll_sources"
    private const val KEY = "verified_articles"
    internal const val MAX_RECORDS = 160
    private const val STORAGE_WARNING =
        "Sources légales LEGI : stockage local incohérent ; aucun article n'est utilisé tant que l'audit officiel n'a pas reconstruit un historique fiable."

    data class Record(
        val topic: OfficialLegalCodeSourceV2.Topic,
        val articleId: String,
        val articleNumber: String?,
        val status: String,
        val excerpt: String,
        val effectiveFromMs: Long,
        val effectiveToMs: Long?,
        val referenceAtMs: Long,
        val checkedAtMs: Long
    )

    data class ReadResult(
        val records: List<Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Snapshot(
        val referenceAtMs: Long,
        val records: List<Record>,
        val coveredTopics: Set<OfficialLegalCodeSourceV2.Topic>,
        val missingTopics: Set<OfficialLegalCodeSourceV2.Topic>,
        val reliable: Boolean = true,
        val warnings: List<String> = emptyList()
    ) {
        val complete: Boolean get() = reliable && missingTopics.isEmpty()
    }

    fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun all(context: Context): List<Record> {
        val stored = read(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.records
    }

    fun replaceTopicSnapshot(
        context: Context,
        topic: OfficialLegalCodeSourceV2.Topic,
        referenceAtMs: Long,
        verified: List<OfficialLegalCodeVerifierV2.VerifiedArticle>
    ): Boolean {
        require(referenceAtMs > 0L) { "Date de référence LEGI invalide" }
        val stored = read(context)
        if (!stored.reliable) return false

        val current = stored.records.toMutableList()
        current.removeAll { it.topic == topic && sameReferenceDay(it.referenceAtMs, referenceAtMs) }
        current += verified
            .filter { it.topic == topic && sameReferenceDay(it.referenceAtMs, referenceAtMs) }
            .map {
                Record(
                    topic = it.topic,
                    articleId = it.articleId,
                    articleNumber = it.articleNumber,
                    status = it.status,
                    excerpt = it.excerpt.take(1_200),
                    effectiveFromMs = it.effectiveFromMs,
                    effectiveToMs = it.effectiveToMs,
                    referenceAtMs = referenceAtMs,
                    checkedAtMs = it.checkedAtMs
                )
            }

        val candidate = current.sortedByDescending { it.checkedAtMs }
        if (!acceptsPackage(candidate)) return false
        return save(context, candidate)
    }

    /** Sources vérifiées spécifiquement pour la même date de paie demandée. */
    fun applicableAt(context: Context, atMs: Long): List<Record> =
        applicableFrom(read(context), atMs)

    internal fun applicableFrom(stored: ReadResult, atMs: Long): List<Record> {
        if (!stored.reliable || atMs <= 0L) return emptyList()
        return stored.records
            .filter { sameReferenceDay(it.referenceAtMs, atMs) }
            .filter { isApplicableStatus(it.status) }
            .filter { atMs >= it.effectiveFromMs && (it.effectiveToMs == null || atMs <= it.effectiveToMs) }
            .groupBy { it.topic to it.articleId }
            .mapNotNull { (_, values) -> values.maxByOrNull { it.checkedAtMs } }
            .sortedWith(compareBy<Record> { it.topic.ordinal }.thenBy { it.articleNumber.orEmpty() })
    }

    internal fun isApplicableStatus(status: String): Boolean =
        status.equals("VIGUEUR", ignoreCase = true) ||
            status.equals("VIGUEUR_DIFF", ignoreCase = true)

    fun snapshot(context: Context, atMs: Long): Snapshot {
        val stored = read(context)
        val records = applicableFrom(stored, atMs)
        val covered = records.map { it.topic }.toSet()
        val allTopics = OfficialLegalCodeSourceV2.Topic.entries.toSet()
        return Snapshot(
            referenceAtMs = atMs,
            records = records,
            coveredTopics = covered,
            missingTopics = allTopics - covered,
            reliable = stored.reliable,
            warnings = stored.warnings
        )
    }

    internal fun decodeRecords(raw: String): ReadResult {
        if (raw.isBlank()) return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))

        val records = mutableListOf<Record>()
        var malformed = array.length() > MAX_RECORDS
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val record = obj?.let(::decodeRecord)
            if (record == null) malformed = true else records += record
        }
        if (!acceptsPackage(records)) malformed = true

        return ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun acceptsPackage(records: List<Record>): Boolean =
        records.size <= MAX_RECORDS &&
            records.all(::structurallyValid) &&
            records.map(::identity).distinct().size == records.size

    private fun save(context: Context, records: List<Record>): Boolean {
        if (!acceptsPackage(records)) return false
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject()
                .put("topic", record.topic.name)
                .put("articleId", record.articleId)
                .put("articleNumber", record.articleNumber ?: JSONObject.NULL)
                .put("status", record.status)
                .put("excerpt", record.excerpt)
                .put("effectiveFromMs", record.effectiveFromMs)
                .put("effectiveToMs", record.effectiveToMs ?: JSONObject.NULL)
                .put("referenceAtMs", record.referenceAtMs)
                .put("checkedAtMs", record.checkedAtMs))
        }
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }

    private fun decodeRecord(obj: JSONObject): Record? = runCatching {
        val topic = OfficialLegalCodeSourceV2.Topic.valueOf(obj.getString("topic"))
        val articleId = obj.getString("articleId").trim()
        val status = obj.getString("status").trim()
        val excerpt = obj.getString("excerpt").trim().take(1_200)
        val from = obj.getLong("effectiveFromMs")
        val reference = obj.getLong("referenceAtMs")
        val checked = obj.getLong("checkedAtMs")
        val to = when {
            !obj.has("effectiveToMs") -> return null
            obj.isNull("effectiveToMs") -> null
            else -> obj.getLong("effectiveToMs")
        }
        Record(
            topic = topic,
            articleId = articleId,
            articleNumber = if (!obj.has("articleNumber") || obj.isNull("articleNumber")) {
                null
            } else {
                obj.getString("articleNumber").trim().takeIf { it.isNotBlank() }
            },
            status = status,
            excerpt = excerpt,
            effectiveFromMs = from,
            effectiveToMs = to,
            referenceAtMs = reference,
            checkedAtMs = checked
        ).takeIf(::structurallyValid)
    }.getOrNull()

    private fun structurallyValid(record: Record): Boolean =
        record.articleId.startsWith("LEGIARTI") &&
            record.articleId.length > "LEGIARTI".length &&
            record.status.isNotBlank() &&
            record.excerpt.isNotBlank() &&
            record.effectiveFromMs > 0L &&
            record.referenceAtMs > 0L &&
            record.checkedAtMs > 0L &&
            (record.effectiveToMs == null || record.effectiveToMs >= record.effectiveFromMs)

    private fun identity(record: Record): String =
        "${record.topic.name}:${record.articleId}:${referenceDay(record.referenceAtMs)}"

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
