package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionPublicHolidayPremiumHistoryV2
import com.amaury.pointage.v2.engine.ConventionPublicHolidayPremiumSnapshotV2
import com.amaury.pointage.v2.engine.PublicHolidayPremiumRuleV2
import org.json.JSONArray
import org.json.JSONObject

/** Stockage local séparé des majorations de jours fériés officiellement confirmées. */
object V2ConventionPublicHolidayPremiumStore {
    private const val PREFS = "horatrack_v2_convention_public_holiday_premiums"
    private const val KEY_CONFIRMED = "confirmed_snapshots"
    private const val STORAGE_WARNING =
        "KALI jours fériés : historique local des règles conventionnelles incohérent ; aucune règle ne peut être déduite de ce stockage."

    data class ReadResult(
        val snapshots: List<ConventionPublicHolidayPremiumSnapshotV2>,
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

    fun history(context: Context): ConventionPublicHolidayPremiumHistoryV2 {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return ConventionPublicHolidayPremiumHistoryV2(stored.snapshots)
    }

    fun saveConfirmed(context: Context, snapshot: ConventionPublicHolidayPremiumSnapshotV2) {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        check(validSnapshotPayload(snapshot)) { "KALI jours fériés : snapshot conventionnel invalide." }

        val current = stored.snapshots.toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId
        }
        current += snapshot

        check(current.all(::validSnapshotPayload)) { "KALI jours fériés : historique conventionnel invalide." }
        check(runCatching { ConventionPublicHolidayPremiumHistoryV2(current) }.isSuccess) {
            "KALI jours fériés : historique conventionnel dupliqué ou chevauchant."
        }

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionPublicHolidayPremiumSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encode(it)) }

        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIRMED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI jours fériés : stockage local du snapshot impossible." }
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val snapshots = mutableListOf<ConventionPublicHolidayPremiumSnapshotV2>()
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
            malformed = runCatching { ConventionPublicHolidayPremiumHistoryV2(snapshots) }.isFailure
        }
        return ReadResult(
            snapshots = snapshots,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun encode(snapshot: ConventionPublicHolidayPremiumSnapshotV2): JSONObject =
        JSONObject()
            .put("idcc", normalize(snapshot.idcc))
            .put("versionId", snapshot.versionId)
            .put("sourceId", snapshot.sourceId)
            .put("effectiveFromEpochDay", snapshot.effectiveFromEpochDay)
            .put("effectiveToEpochDay", snapshot.effectiveToEpochDay)
            .put("checkedAtMs", snapshot.checkedAtMs)
            .put("note", snapshot.note)
            .put("multiplier", snapshot.rule.multiplier)

    private fun decode(obj: JSONObject): ConventionPublicHolidayPremiumSnapshotV2? = runCatching {
        ConventionPublicHolidayPremiumSnapshotV2(
            idcc = obj.getString("idcc"),
            versionId = obj.getString("versionId"),
            sourceId = obj.getString("sourceId"),
            effectiveFromEpochDay = obj.getLong("effectiveFromEpochDay"),
            effectiveToEpochDay = if (obj.isNull("effectiveToEpochDay")) null else obj.getLong("effectiveToEpochDay"),
            rule = PublicHolidayPremiumRuleV2(obj.getDouble("multiplier")),
            checkedAtMs = obj.getLong("checkedAtMs"),
            note = if (obj.isNull("note")) null else obj.optString("note").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun validSnapshotPayload(snapshot: ConventionPublicHolidayPremiumSnapshotV2): Boolean =
        snapshot.idcc.isNotBlank() &&
            snapshot.versionId.isNotBlank() &&
            snapshot.sourceId.isNotBlank() &&
            snapshot.checkedAtMs >= 0L &&
            snapshot.rule.multiplier.isFinite() &&
            snapshot.rule.multiplier >= 1.0

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
