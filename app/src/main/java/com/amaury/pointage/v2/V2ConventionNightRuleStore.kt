package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionNightRuleHistoryV2
import com.amaury.pointage.v2.engine.ConventionNightRuleSnapshotV2
import com.amaury.pointage.v2.engine.NightPremiumRuleV2
import org.json.JSONArray
import org.json.JSONObject

/** Stockage local dédié aux règles de nuit confirmées. */
object V2ConventionNightRuleStore {
    private const val PREFS = "horatrack_v2_convention_night_rules"
    private const val KEY_CONFIRMED = "confirmed_night_snapshots"
    private const val STORAGE_WARNING =
        "KALI nuit : historique local des règles conventionnelles incohérent ; aucune règle ne peut être déduite de ce stockage."

    data class ReadResult(
        val snapshots: List<ConventionNightRuleSnapshotV2>,
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

    fun history(context: Context): ConventionNightRuleHistoryV2 {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return ConventionNightRuleHistoryV2(stored.snapshots)
    }

    fun saveConfirmed(context: Context, snapshot: ConventionNightRuleSnapshotV2) {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        check(validSnapshotPayload(snapshot)) { "KALI nuit : snapshot conventionnel invalide." }

        val current = stored.snapshots.toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId
        }
        current += snapshot

        check(current.all(::validSnapshotPayload)) { "KALI nuit : historique conventionnel invalide." }
        check(runCatching { ConventionNightRuleHistoryV2(current) }.isSuccess) {
            "KALI nuit : historique conventionnel dupliqué ou chevauchant."
        }

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionNightRuleSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encodeSnapshot(it)) }

        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIRMED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI nuit : stockage local du snapshot impossible." }
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val snapshots = mutableListOf<ConventionNightRuleSnapshotV2>()
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
            malformed = runCatching { ConventionNightRuleHistoryV2(snapshots) }.isFailure
        }
        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun encodeSnapshot(snapshot: ConventionNightRuleSnapshotV2): JSONObject = JSONObject()
        .put("idcc", normalize(snapshot.idcc))
        .put("versionId", snapshot.versionId)
        .put("sourceId", snapshot.sourceId)
        .put("effectiveFromEpochDay", snapshot.effectiveFromEpochDay)
        .put("effectiveToEpochDay", snapshot.effectiveToEpochDay)
        .put("checkedAtMs", snapshot.checkedAtMs)
        .put("note", snapshot.note)
        .put(
            "rule",
            JSONObject()
                .put("startMinute", snapshot.rule.startMinute)
                .put("endMinute", snapshot.rule.endMinute)
                .put("multiplier", snapshot.rule.multiplier)
        )

    private fun decodeSnapshot(obj: JSONObject): ConventionNightRuleSnapshotV2? = runCatching {
        val rule = obj.getJSONObject("rule")
        ConventionNightRuleSnapshotV2(
            idcc = obj.getString("idcc"),
            versionId = obj.getString("versionId"),
            sourceId = obj.getString("sourceId"),
            effectiveFromEpochDay = obj.getLong("effectiveFromEpochDay"),
            effectiveToEpochDay = if (obj.isNull("effectiveToEpochDay")) null else obj.getLong("effectiveToEpochDay"),
            rule = NightPremiumRuleV2(
                startMinute = rule.getInt("startMinute"),
                endMinute = rule.getInt("endMinute"),
                multiplier = rule.getDouble("multiplier")
            ),
            checkedAtMs = obj.getLong("checkedAtMs"),
            note = if (obj.isNull("note")) null else obj.optString("note").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun validSnapshotPayload(snapshot: ConventionNightRuleSnapshotV2): Boolean =
        snapshot.checkedAtMs >= 0L &&
            snapshot.rule.startMinute in 0 until 24 * 60 &&
            snapshot.rule.endMinute in 0 until 24 * 60 &&
            snapshot.rule.startMinute != snapshot.rule.endMinute &&
            snapshot.rule.multiplier.isFinite() &&
            snapshot.rule.multiplier >= 1.0

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
