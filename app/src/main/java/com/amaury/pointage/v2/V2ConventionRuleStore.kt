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

    fun history(context: Context): ConventionRuleHistoryV2 =
        ConventionRuleHistoryV2(loadConfirmed(context))

    fun saveConfirmed(context: Context, snapshot: ConventionRuleSnapshotV2) {
        val current = loadConfirmed(context).toMutableList()
        current.removeAll { normalize(it.idcc) == normalize(snapshot.idcc) && it.versionId == snapshot.versionId }
        current += snapshot
        val array = JSONArray()
        current.sortedWith(compareBy<ConventionRuleSnapshotV2> { normalize(it.idcc) }.thenBy { it.effectiveFromEpochDay })
            .forEach { array.put(encodeSnapshot(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONFIRMED, array.toString()).apply()
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

    private fun loadConfirmed(context: Context): List<ConventionRuleSnapshotV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decodeSnapshot(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
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
        val tiersJson = rules.optJSONArray("overtimeTiers") ?: JSONArray()
        val tiers = buildList {
            for (index in 0 until tiersJson.length()) {
                val tier = tiersJson.getJSONObject(index)
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

    private fun normalize(value: String): String = value.trim().padStart(4, '0')
}
