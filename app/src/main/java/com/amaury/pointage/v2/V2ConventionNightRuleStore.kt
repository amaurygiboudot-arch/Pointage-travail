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

    fun history(context: Context): ConventionNightRuleHistoryV2 =
        ConventionNightRuleHistoryV2(loadConfirmed(context))

    fun saveConfirmed(context: Context, snapshot: ConventionNightRuleSnapshotV2) {
        val current = loadConfirmed(context).toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId
        }
        current += snapshot

        // Valide les doublons/chevauchements AVANT toute écriture persistante.
        ConventionNightRuleHistoryV2(current)

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionNightRuleSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encodeSnapshot(it)) }

        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIRMED, array.toString())
            .apply()
    }

    private fun loadConfirmed(context: Context): List<ConventionNightRuleSnapshotV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decodeSnapshot(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
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

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
