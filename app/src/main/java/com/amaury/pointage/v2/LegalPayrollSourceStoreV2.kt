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
    private const val MAX_RECORDS = 160

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

    data class Snapshot(
        val referenceAtMs: Long,
        val records: List<Record>,
        val coveredTopics: Set<OfficialLegalCodeSourceV2.Topic>,
        val missingTopics: Set<OfficialLegalCodeSourceV2.Topic>
    ) {
        val complete: Boolean get() = missingTopics.isEmpty()
    }

    fun all(context: Context): List<Record> = decode(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
    )

    fun replaceTopicSnapshot(
        context: Context,
        topic: OfficialLegalCodeSourceV2.Topic,
        referenceAtMs: Long,
        verified: List<OfficialLegalCodeVerifierV2.VerifiedArticle>
    ): Boolean {
        require(referenceAtMs > 0L) { "Date de référence LEGI invalide" }
        val current = all(context).toMutableList()
        current.removeAll { it.topic == topic && sameReferenceDay(it.referenceAtMs, referenceAtMs) }
        current += verified
            .filter { it.topic == topic && it.referenceAtMs == referenceAtMs }
            .map {
                Record(
                    topic = it.topic,
                    articleId = it.articleId,
                    articleNumber = it.articleNumber,
                    status = it.status,
                    excerpt = it.excerpt.take(1_200),
                    effectiveFromMs = it.effectiveFromMs,
                    effectiveToMs = it.effectiveToMs,
                    referenceAtMs = it.referenceAtMs,
                    checkedAtMs = it.checkedAtMs
                )
            }
        val kept = current
            .distinctBy { "${it.topic.name}:${it.articleId}:${referenceDay(it.referenceAtMs)}" }
            .sortedByDescending { it.checkedAtMs }
            .take(MAX_RECORDS)
        return save(context, kept)
    }

    /** Sources déjà vérifiées et applicables à la date demandée, quel que soit le mois où elles ont été auditées. */
    fun applicableAt(context: Context, atMs: Long): List<Record> {
        if (atMs <= 0L) return emptyList()
        return all(context)
            .filter { it.status.equals("VIGUEUR", ignoreCase = true) }
            .filter { atMs >= it.effectiveFromMs && (it.effectiveToMs == null || atMs <= it.effectiveToMs) }
            .groupBy { it.topic to it.articleId }
            .mapNotNull { (_, values) -> values.maxByOrNull { it.checkedAtMs } }
            .sortedWith(compareBy<Record> { it.topic.ordinal }.thenBy { it.articleNumber.orEmpty() })
    }

    fun snapshot(context: Context, atMs: Long): Snapshot {
        val records = applicableAt(context, atMs)
        val covered = records.map { it.topic }.toSet()
        val allTopics = OfficialLegalCodeSourceV2.Topic.entries.toSet()
        return Snapshot(
            referenceAtMs = atMs,
            records = records,
            coveredTopics = covered,
            missingTopics = allTopics - covered
        )
    }

    private fun save(context: Context, records: List<Record>): Boolean {
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
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun decode(raw: String): List<Record> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val topic = runCatching {
                    OfficialLegalCodeSourceV2.Topic.valueOf(obj.optString("topic"))
                }.getOrNull() ?: continue
                val articleId = obj.optString("articleId").trim()
                val from = obj.optLong("effectiveFromMs", -1L)
                val reference = obj.optLong("referenceAtMs", -1L)
                val checked = obj.optLong("checkedAtMs", -1L)
                if (!articleId.startsWith("LEGIARTI") || from <= 0L || reference <= 0L || checked <= 0L) continue
                add(Record(
                    topic = topic,
                    articleId = articleId,
                    articleNumber = obj.optString("articleNumber").takeIf { it.isNotBlank() && it != "null" },
                    status = obj.optString("status", "VIGUEUR"),
                    excerpt = obj.optString("excerpt").take(1_200),
                    effectiveFromMs = from,
                    effectiveToMs = if (obj.isNull("effectiveToMs")) null else obj.optLong("effectiveToMs").takeIf { it > 0L },
                    referenceAtMs = reference,
                    checkedAtMs = checked
                ))
            }
        }
    }.getOrElse { emptyList() }

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
