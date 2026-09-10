package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot local séparé de la règle LEGI vérifiée du 1er mai. */
object MayFirstLegalRuleStoreV2 {
    private const val PREFS = "horatrack_v2_may_first_legal_rule"
    private const val KEY = "verified_snapshots"
    internal const val MAX_RECORDS = 24
    private const val STORAGE_WARNING =
        "1er mai LEGI : stockage local incohérent ; aucune règle L3133-6 n'est utilisée tant qu'un nouvel audit officiel n'a pas reconstruit l'historique."

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
            require(articleId.startsWith("LEGIARTI") && articleId.length > "LEGIARTI".length)
            require(articleNumber == "L3133-6")
            require(effectiveFromMs > 0L && referenceAtMs > 0L && checkedAtMs > 0L)
            require(effectiveToMs == null || effectiveToMs >= effectiveFromMs) {
                "Période de vigueur L3133-6 invalide"
            }
            require(extraMultiplier == 1.0) { "La règle 1er mai doit provenir de l'égalité explicite de l'indemnité au salaire" }
        }

        fun appliesTo(atMs: Long): Boolean =
            atMs > 0L && atMs >= effectiveFromMs && (effectiveToMs == null || atMs <= effectiveToMs)
    }

    data class ReadResult(
        val snapshots: List<Snapshot>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Resolution(
        val snapshot: Snapshot?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeSnapshots(raw)
    }

    fun save(context: Context, rule: OfficialMayFirstLegalRuleV2.Rule): Boolean {
        val stored = read(context)
        if (!stored.reliable) return false

        val incoming = runCatching {
            Snapshot(
                articleId = rule.articleId,
                articleNumber = rule.articleNumber,
                effectiveFromMs = rule.effectiveFromMs,
                effectiveToMs = rule.effectiveToMs,
                referenceAtMs = rule.referenceAtMs,
                checkedAtMs = rule.checkedAtMs,
                extraMultiplier = rule.extraMultiplier
            )
        }.getOrNull() ?: return false

        val current = stored.snapshots.toMutableList()
        current.removeAll { sameReferenceDay(it.referenceAtMs, incoming.referenceAtMs) }
        current += incoming
        val candidate = current.sortedByDescending { it.checkedAtMs }
        if (!acceptsPackage(candidate)) return false
        return persist(context, candidate)
    }

    fun resolve(context: Context, atMs: Long): Resolution = resolveFrom(read(context), atMs)

    fun applicableAt(context: Context, atMs: Long): Snapshot? = resolve(context, atMs).snapshot

    internal fun resolveFrom(stored: ReadResult, atMs: Long): Resolution {
        if (!stored.reliable) {
            return Resolution(
                snapshot = null,
                reliable = false,
                warnings = (listOf(STORAGE_WARNING) + stored.warnings).distinct()
            )
        }
        if (atMs <= 0L) return Resolution(null, true, emptyList())
        val matching = stored.snapshots
            .filter { sameReferenceDay(it.referenceAtMs, atMs) && it.appliesTo(atMs) }
        if (matching.size > 1) {
            return Resolution(null, false, listOf(STORAGE_WARNING))
        }
        return Resolution(
            snapshot = matching.singleOrNull(),
            reliable = true,
            warnings = emptyList()
        )
    }

    internal fun decodeSnapshots(raw: String): ReadResult {
        if (raw.isBlank()) return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))

        val snapshots = mutableListOf<Snapshot>()
        var malformed = array.length() > MAX_RECORDS
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val snapshot = obj?.let(::decodeSnapshot)
            if (snapshot == null) malformed = true else snapshots += snapshot
        }
        if (!acceptsPackage(snapshots)) malformed = true

        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun acceptsPackage(snapshots: List<Snapshot>): Boolean =
        snapshots.size <= MAX_RECORDS &&
            snapshots.map { referenceDay(it.referenceAtMs) }.distinct().size == snapshots.size

    private fun persist(context: Context, snapshots: List<Snapshot>): Boolean {
        if (!acceptsPackage(snapshots)) return false
        val array = JSONArray()
        snapshots.forEach { value ->
            array.put(JSONObject()
                .put("articleId", value.articleId)
                .put("articleNumber", value.articleNumber)
                .put("effectiveFromMs", value.effectiveFromMs)
                .put("effectiveToMs", value.effectiveToMs ?: JSONObject.NULL)
                .put("referenceAtMs", value.referenceAtMs)
                .put("checkedAtMs", value.checkedAtMs)
                .put("extraMultiplier", value.extraMultiplier))
        }
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }

    private fun decodeSnapshot(obj: JSONObject): Snapshot? = runCatching {
        val to = when {
            !obj.has("effectiveToMs") -> return null
            obj.isNull("effectiveToMs") -> null
            else -> obj.getLong("effectiveToMs")
        }
        Snapshot(
            articleId = obj.getString("articleId").trim(),
            articleNumber = obj.getString("articleNumber").trim(),
            effectiveFromMs = obj.getLong("effectiveFromMs"),
            effectiveToMs = to,
            referenceAtMs = obj.getLong("referenceAtMs"),
            checkedAtMs = obj.getLong("checkedAtMs"),
            extraMultiplier = obj.getDouble("extraMultiplier")
        )
    }.getOrNull()

    private fun referenceDay(ms: Long): Long = ms / 86_400_000L
    private fun sameReferenceDay(a: Long, b: Long): Boolean = referenceDay(a) == referenceDay(b)
}
