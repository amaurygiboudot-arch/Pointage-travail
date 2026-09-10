package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionWeekdayPremiumHistoryV2
import com.amaury.pointage.v2.engine.ConventionWeekdayPremiumSnapshotV2
import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2
import com.amaury.pointage.v2.engine.WeekdayPremiumRuleV2
import org.json.JSONArray
import org.json.JSONObject

/** Stockage local non destructif des majorations samedi/dimanche officiellement confirmées. */
object V2ConventionWeekdayPremiumStore {
    private const val PREFS = "horatrack_v2_convention_weekday_premiums"
    private const val KEY_CONFIRMED = "confirmed_snapshots"
    private const val STORAGE_WARNING =
        "KALI samedi/dimanche : historique local des majorations conventionnelles incohérent ; aucune règle ne peut être déduite de ce stockage."

    data class ReadResult(
        val snapshots: List<ConventionWeekdayPremiumSnapshotV2>,
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

    fun history(context: Context): ConventionWeekdayPremiumHistoryV2 {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return ConventionWeekdayPremiumHistoryV2(stored.snapshots)
    }

    fun saveConfirmed(context: Context, snapshot: ConventionWeekdayPremiumSnapshotV2) {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        check(validSnapshotPayload(snapshot)) { "KALI samedi/dimanche : snapshot conventionnel invalide." }

        val current = stored.snapshots.toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) &&
                it.rule.kind == snapshot.rule.kind &&
                it.versionId == snapshot.versionId
        }
        current += snapshot

        check(current.all(::validSnapshotPayload)) {
            "KALI samedi/dimanche : historique conventionnel invalide."
        }
        check(runCatching { ConventionWeekdayPremiumHistoryV2(current) }.isSuccess) {
            "KALI samedi/dimanche : historique conventionnel dupliqué ou chevauchant."
        }

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionWeekdayPremiumSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.rule.kind.name }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encode(it)) }

        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIRMED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI samedi/dimanche : stockage local du snapshot impossible." }
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val snapshots = mutableListOf<ConventionWeekdayPremiumSnapshotV2>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val snapshot = obj?.let(::decode)
            if (snapshot == null || !validSnapshotPayload(snapshot)) {
                malformed = true
            } else {
                snapshots += snapshot
            }
        }
        if (!malformed) {
            malformed = runCatching { ConventionWeekdayPremiumHistoryV2(snapshots) }.isFailure
        }
        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun encode(snapshot: ConventionWeekdayPremiumSnapshotV2): JSONObject =
        JSONObject()
            .put("idcc", normalize(snapshot.idcc))
            .put("versionId", snapshot.versionId)
            .put("sourceId", snapshot.sourceId)
            .put("effectiveFromEpochDay", snapshot.effectiveFromEpochDay)
            .put("effectiveToEpochDay", snapshot.effectiveToEpochDay)
            .put("checkedAtMs", snapshot.checkedAtMs)
            .put("note", snapshot.note)
            .put("kind", snapshot.rule.kind.name)
            .put("multiplier", snapshot.rule.multiplier)

    private fun decode(obj: JSONObject): ConventionWeekdayPremiumSnapshotV2? = runCatching {
        ConventionWeekdayPremiumSnapshotV2(
            idcc = obj.getString("idcc"),
            versionId = obj.getString("versionId"),
            sourceId = obj.getString("sourceId"),
            effectiveFromEpochDay = obj.getLong("effectiveFromEpochDay"),
            effectiveToEpochDay = if (obj.isNull("effectiveToEpochDay")) null else obj.getLong("effectiveToEpochDay"),
            rule = WeekdayPremiumRuleV2(
                kind = WeekdayPremiumKindV2.valueOf(obj.getString("kind")),
                multiplier = obj.getDouble("multiplier")
            ),
            checkedAtMs = obj.getLong("checkedAtMs"),
            note = if (obj.isNull("note")) null else obj.optString("note").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun validSnapshotPayload(snapshot: ConventionWeekdayPremiumSnapshotV2): Boolean =
        snapshot.checkedAtMs >= 0L &&
            snapshot.rule.multiplier.isFinite() &&
            snapshot.rule.multiplier >= 1.0

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
