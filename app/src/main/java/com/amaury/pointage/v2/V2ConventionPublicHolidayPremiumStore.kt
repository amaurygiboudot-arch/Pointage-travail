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

    fun history(context: Context): ConventionPublicHolidayPremiumHistoryV2 =
        ConventionPublicHolidayPremiumHistoryV2(load(context))

    fun saveConfirmed(context: Context, snapshot: ConventionPublicHolidayPremiumSnapshotV2) {
        val current = load(context).toMutableList()
        current.removeAll {
            normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId
        }
        current += snapshot
        ConventionPublicHolidayPremiumHistoryV2(current)

        val array = JSONArray()
        current.sortedWith(
            compareBy<ConventionPublicHolidayPremiumSnapshotV2> { normalize(it.idcc) }
                .thenBy { it.effectiveFromEpochDay }
        ).forEach { array.put(encode(it)) }

        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONFIRMED, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionPublicHolidayPremiumSnapshotV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
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

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
