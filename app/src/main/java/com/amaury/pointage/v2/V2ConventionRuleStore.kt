package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage local non destructif des règles conventionnelles historiques confirmées.
 * Une observation de source officielle n'est jamais promue automatiquement en règle applicable
 * tant que sa date d'effet n'est pas connue.
 */
object V2ConventionRuleStore {
    private const val PREFS = "horatrack_v2_convention_rules"
    private const val KEY_CONFIRMED = "confirmed_snapshots"
    private const val KEY_OBSERVATIONS = "official_observations"
    private const val SOURCE_LEGIFRANCE = "https://www.legifrance.gouv.fr/liste/idcc"
    private const val STORAGE_WARNING =
        "KALI heures supplémentaires : historique local des règles conventionnelles incohérent ; aucune règle ni absence ne peut être déduite de ce stockage."

    data class ReadResult(
        val snapshots: List<ConventionRuleSnapshotV2>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readConfirmed(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CONFIRMED)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_CONFIRMED, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeConfirmed(raw)
    }

    fun history(context: Context): ConventionRuleHistoryV2 {
        val stored = readConfirmed(context)
        return if (stored.reliable) {
            ConventionRuleHistoryV2(stored.snapshots)
        } else {
            ConventionRuleHistoryV2.empty()
        }
    }

    /**
     * Refuse toute écriture si l'historique existant est illisible/incohérent.
     * L'exception est volontaire : les appels de l'audit KALI sont déjà entourés de runCatching,
     * ce qui transforme l'échec en `saved=false` sans écraser l'historique antérieur.
     */
    fun saveConfirmed(context: Context, snapshot: ConventionRuleSnapshotV2) {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        check(validSnapshotPayload(snapshot)) { "KALI heures supplémentaires : snapshot conventionnel invalide." }

        val current = stored.snapshots.toMutableList()
        current.removeAll { normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId }
        current += snapshot
        check(current.all(::validSnapshotPayload)) { "KALI heures supplémentaires : historique conventionnel invalide." }
        check(runCatching { ConventionRuleHistoryV2(current) }.isSuccess) {
            "KALI heures supplémentaires : historique conventionnel dupliqué ou chevauchant."
        }

        val array = JSONArray()
        current.sortedWith(compareBy<ConventionRuleSnapshotV2> { normalize(it.idcc) }.thenBy { it.effectiveFromEpochDay })
            .forEach { array.put(encodeSnapshot(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIRMED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI heures supplémentaires : stockage local du snapshot impossible." }
    }

    /**
     * Mémorise ce qui a été vu lors d'un contrôle officiel sans inventer une date d'effet.
     * Ces observations servent de piste d'audit et pourront être transformées en snapshots confirmés
     * seulement lorsqu'une date d'application officielle est connue.
     */
    fun recordOfficialCatalogObservation(
        context: Context,
        idcc: String,
        checkedAtMs: Long,
        rulesFingerprint: String
    ) {
        if (idcc.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = runCatching { JSONArray(prefs.getString(KEY_OBSERVATIONS, "[]")) }.getOrDefault(JSONArray())
        val item = JSONObject()
            .put("idcc", normalize(idcc))
            .put("checkedAtMs", checkedAtMs)
            .put("source", SOURCE_LEGIFRANCE)
            .put("rulesFingerprint", rulesFingerprint)
        existing.put(item)
        while (existing.length() > 250) existing.remove(0)
        prefs.edit().putString(KEY_OBSERVATIONS, existing.toString()).apply()
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val snapshots = mutableListOf<ConventionRuleSnapshotV2>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val snapshot = obj?.let(::decodeSnapshot)
            if (snapshot == null || !validSnapshotPayload(snapshot)) {
                malformed = true
            } else {
                snapshots += snapshot
            }
        }
        if (!malformed) {
            malformed = runCatching { ConventionRuleHistoryV2(snapshots) }.isFailure
        }
        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun encodeSnapshot(snapshot: ConventionRuleSnapshotV2): JSONObject {
        val tiers = JSONArray()
        snapshot.rules.overtimeTiers.forEach { tier ->
            tiers.put(JSONObject()
                .put("fromMinutes", tier.fromMinutes)
                .put("toMinutes", tier.toMinutes)
                .put("multiplier", tier.multiplier))
        }
        return JSONObject()
            .put("idcc", normalize(snapshot.idcc))
            .put("versionId", snapshot.versionId)
            .put("sourceId", snapshot.sourceId)
            .put("effectiveFromEpochDay", snapshot.effectiveFromEpochDay)
            .put("effectiveToEpochDay", snapshot.effectiveToEpochDay)
            .put("checkedAtMs", snapshot.checkedAtMs)
            .put("note", snapshot.note)
            .put("rules", JSONObject()
                .put("weeklyRegularMinutes", snapshot.rules.weeklyRegularMinutes)
                .put("nightMultiplier", snapshot.rules.nightMultiplier)
                .put("saturdayMultiplier", snapshot.rules.saturdayMultiplier)
                .put("sundayMultiplier", snapshot.rules.sundayMultiplier)
                .put("overtimeTiers", tiers))
    }

    private fun decodeSnapshot(obj: JSONObject): ConventionRuleSnapshotV2? = runCatching {
        val rules = obj.getJSONObject("rules")
        val tiersJson = when {
            !rules.has("overtimeTiers") -> JSONArray()
            rules.isNull("overtimeTiers") -> JSONArray()
            else -> rules.optJSONArray("overtimeTiers") ?: error("overtimeTiers invalide")
        }
        val tiers = buildList {
            for (index in 0 until tiersJson.length()) {
                val tier = tiersJson.opt(index) as? JSONObject ?: error("palier invalide")
                add(OvertimeTierV2(
                    fromMinutes = tier.getInt("fromMinutes"),
                    toMinutes = if (tier.isNull("toMinutes")) null else tier.getInt("toMinutes"),
                    multiplier = tier.getDouble("multiplier")
                ))
            }
        }
        ConventionRuleSnapshotV2(
            idcc = obj.getString("idcc"),
            versionId = obj.getString("versionId"),
            sourceId = obj.getString("sourceId"),
            effectiveFromEpochDay = obj.getLong("effectiveFromEpochDay"),
            effectiveToEpochDay = if (obj.isNull("effectiveToEpochDay")) null else obj.getLong("effectiveToEpochDay"),
            rules = PayrollRulesV2(
                weeklyRegularMinutes = if (rules.isNull("weeklyRegularMinutes")) null else rules.getInt("weeklyRegularMinutes"),
                overtimeTiers = tiers,
                nightMultiplier = if (rules.isNull("nightMultiplier")) null else rules.getDouble("nightMultiplier"),
                saturdayMultiplier = if (rules.isNull("saturdayMultiplier")) null else rules.getDouble("saturdayMultiplier"),
                sundayMultiplier = if (rules.isNull("sundayMultiplier")) null else rules.getDouble("sundayMultiplier")
            ),
            checkedAtMs = obj.getLong("checkedAtMs"),
            note = if (obj.isNull("note")) null else obj.optString("note").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun validSnapshotPayload(snapshot: ConventionRuleSnapshotV2): Boolean {
        if (snapshot.checkedAtMs < 0L) return false
        if (snapshot.rules.weeklyRegularMinutes?.let { it <= 0 } == true) return false
        val multipliers = listOf(
            snapshot.rules.nightMultiplier,
            snapshot.rules.saturdayMultiplier,
            snapshot.rules.sundayMultiplier
        ).filterNotNull()
        if (multipliers.any { !it.isFinite() || it < 1.0 }) return false
        return snapshot.rules.overtimeTiers.all { tier ->
            tier.fromMinutes >= 0 &&
                (tier.toMinutes == null || tier.toMinutes > tier.fromMinutes) &&
                tier.multiplier.isFinite() && tier.multiplier >= 1.0
        }
    }

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
