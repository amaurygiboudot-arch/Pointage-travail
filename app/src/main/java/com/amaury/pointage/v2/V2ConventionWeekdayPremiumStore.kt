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

    fun history(context: Context): ConventionWeekdayPremiumHistoryV2 =
        ConventionWeekdayPremiumHistoryV2(load(context))

    fun saveConfirmed(context: Context, snapshot: ConventionWeekdayPremiumSnapshotV2) {
        val current = load(context).toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) &&
                it.rule.kind == snapshot.rule.kind &&
                it.versionId == snapshot.versionId
        }
        current += snapshot
        ConventionWeekdayPremiumHistoryV2(current)

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionWeekdayPremiumSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.rule.kind.name }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encode(it)) }

        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONFIRMED, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionWeekdayPremiumSnapshotV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
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

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
