package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.RightsEngineV2
import com.amaury.pointage.v2.engine.RightsSnapshotV2
import com.amaury.pointage.v2.model.CounterV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage V2 des compteurs de droits.
 *
 * Un compteur saisi/importé reste une valeur déclarée : HoraTrack ne fabrique
 * jamais de jours de congé ou de repos à partir d'une règle supposée.
 */
object V2RightsStore {
    private const val PREFS = "horatrack_v2_rights"
    private const val KEY_COUNTERS = "counters"

    data class Balance(
        val id: String,
        val label: String,
        val acquired: Double?,
        val available: Double?,
        val taken: Double?,
        val anticipated: Double?,
        val remaining: Double?,
        val unit: String,
        val referenceStartMs: Long,
        val referenceEndMs: Long,
        val source: String = "MANUAL"
    )

    fun all(context: Context): List<Balance> = decode(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COUNTERS, "[]").orEmpty()
    )

    fun upsert(context: Context, balance: Balance) {
        require(balance.id.isNotBlank()) { "Identifiant compteur manquant" }
        require(balance.referenceEndMs >= balance.referenceStartMs) { "Période de référence invalide" }
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == balance.id }
        if (index >= 0) list[index] = balance else list += balance
        save(context, list)
    }

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): RightsSnapshotV2 {
        val counters = all(context).flatMap { b ->
            buildList {
                b.acquired?.let { add(counter(b, "acquired", "${b.label} — acquis", it)) }
                b.available?.let { add(counter(b, "available", "${b.label} — disponible", it)) }
                b.taken?.let { add(counter(b, "taken", "${b.label} — pris", it)) }
                b.anticipated?.let { add(counter(b, "anticipated", "${b.label} — anticipé", it)) }
                b.remaining?.let { add(counter(b, "remaining", "${b.label} — restant", it)) }
            }
        }
        val base = RightsEngineV2.snapshot(counters, nowMs)
        val consistency = all(context).flatMap { b ->
            buildList {
                if (b.acquired != null && b.taken != null && b.remaining != null) {
                    val expected = b.acquired + (b.anticipated ?: 0.0) - b.taken
                    if (kotlin.math.abs(expected - b.remaining) > 0.01) {
                        add("${b.label} : solde déclaré différent du calcul acquis + anticipé - pris")
                    }
                }
                if (b.available != null && b.remaining != null && b.available < 0.0) add("${b.label} : disponible négatif à vérifier")
            }
        }
        return base.copy(warnings = base.warnings + consistency)
    }

    private fun counter(b: Balance, suffix: String, label: String, value: Double) = CounterV2(
        id = "${b.id}:$suffix",
        label = label,
        value = value,
        unit = b.unit,
        referenceStartMs = b.referenceStartMs,
        referenceEndMs = b.referenceEndMs
    )

    private fun save(context: Context, balances: List<Balance>) {
        val array = JSONArray()
        balances.forEach { b ->
            array.put(JSONObject()
                .put("id", b.id).put("label", b.label)
                .put("acquired", b.acquired ?: JSONObject.NULL)
                .put("available", b.available ?: JSONObject.NULL)
                .put("taken", b.taken ?: JSONObject.NULL)
                .put("anticipated", b.anticipated ?: JSONObject.NULL)
                .put("remaining", b.remaining ?: JSONObject.NULL)
                .put("unit", b.unit)
                .put("referenceStartMs", b.referenceStartMs)
                .put("referenceEndMs", b.referenceEndMs)
                .put("source", b.source))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COUNTERS, array.toString()).apply()
    }

    private fun decode(raw: String): List<Balance> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                add(Balance(
                    id = id,
                    label = o.optString("label", id),
                    acquired = nullableDouble(o, "acquired"),
                    available = nullableDouble(o, "available"),
                    taken = nullableDouble(o, "taken"),
                    anticipated = nullableDouble(o, "anticipated"),
                    remaining = nullableDouble(o, "remaining"),
                    unit = o.optString("unit", "jours"),
                    referenceStartMs = o.optLong("referenceStartMs", 0L),
                    referenceEndMs = o.optLong("referenceEndMs", Long.MAX_VALUE),
                    source = o.optString("source", "MANUAL")
                ))
            }
        }
    }.getOrElse { emptyList() }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeUnless { it.isNaN() }
}
