package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot local séparé de la règle LEGI vérifiée du 1er mai. */
object MayFirstLegalRuleStoreV2 {
    private const val PREFS = "horatrack_v2_may_first_legal_rule"
    private const val KEY = "verified_snapshots"
    private const val MAX_RECORDS = 24

    data class Snapshot(
        val articleId: String,
        val articleNumber: String,
        val effectiveFromMs: Long,
        val effectiveToMs: Long?,
        val referenceAtMs: Long,
        val checkedAtMs: Long,
        val extraMultiplier: Double
    ) {
        val totalMultiplier: Double get() = 1.0 + extraMultiplier

        init {
            require(articleId.startsWith("LEGIARTI"))
            require(articleNumber == "L3133-6")
            require(effectiveFromMs > 0L && referenceAtMs > 0L && checkedAtMs > 0L)
            require(extraMultiplier == 1.0) { "La règle 1er mai doit provenir de l'égalité explicite de l'indemnité au salaire" }
        }

        fun appliesTo(atMs: Long): Boolean =
            atMs > 0L && atMs >= effectiveFromMs && (effectiveToMs == null || atMs <= effectiveToMs)
    }

    fun save(context: Context, rule: OfficialMayFirstLegalRuleV2.Rule): Boolean {
        val current = all(context).toMutableList()
        current.removeAll { sameReferenceDay(it.referenceAtMs, rule.referenceAtMs) }
        current += Snapshot(
            articleId = rule.articleId,
            articleNumber = rule.articleNumber,
            effectiveFromMs = rule.effectiveFromMs,
            effectiveToMs = rule.effectiveToMs,
            referenceAtMs = rule.referenceAtMs,
            checkedAtMs = rule.checkedAtMs,
            extraMultiplier = rule.extraMultiplier
        )
        val kept = current.sortedByDescending { it.checkedAtMs }.take(MAX_RECORDS)
        val array = JSONArray()
        kept.forEach { value ->
            array.put(JSONObject()
                .put("articleId", value.articleId)
                .put("articleNumber", value.articleNumber)
                .put("effectiveFromMs", value.effectiveFromMs)
                .put("effectiveToMs", value.effectiveToMs ?: JSONObject.NULL)
                .put("referenceAtMs", value.referenceAtMs)
                .put("checkedAtMs", value.checkedAtMs)
                .put("extraMultiplier", value.extraMultiplier))
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }

    fun applicableAt(context: Context, atMs: Long): Snapshot? = all(context)
        .filter { sameReferenceDay(it.referenceAtMs, atMs) && it.appliesTo(atMs) }
        .maxByOrNull { it.checkedAtMs }

    private fun all(context: Context): List<Snapshot> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val snapshot = runCatching {
                        Snapshot(
                            articleId = obj.getString("articleId"),
                            articleNumber = obj.getString("articleNumber"),
                            effectiveFromMs = obj.getLong("effectiveFromMs"),
                            effectiveToMs = if (obj.isNull("effectiveToMs")) null else obj.getLong("effectiveToMs"),
                            referenceAtMs = obj.getLong("referenceAtMs"),
                            checkedAtMs = obj.getLong("checkedAtMs"),
                            extraMultiplier = obj.getDouble("extraMultiplier")
                        )
                    }.getOrNull()
                    if (snapshot != null) add(snapshot)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun sameReferenceDay(a: Long, b: Long): Boolean = a / 86_400_000L == b / 86_400_000L
}
