package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.EventSourceV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Barrière de fiabilité du journal factuel des sessions V2.
 *
 * Ce composant ne calcule aucune durée et ne répare aucune donnée métier. Il vérifie uniquement
 * qu'un historique déjà présent peut être relu sans perte avant qu'un écrivain ne le modifie.
 */
object V2RuntimeHistoryGuardV2 {
    const val PREFS = "horatrack_v2_test_runtime"
    const val KEY_HISTORY = "history"
    const val KEY_PAUSES = "pauses"

    private const val HISTORY_WARNING =
        "Historique de pointage V2 illisible ou incohérent : aucune modification destructive n'est autorisée."

    data class ReadResult(
        val history: JSONArray,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(
        context: Context,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_HISTORY)) return ReadResult(JSONArray(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_HISTORY, null) }.getOrNull()
            ?: return corrupt()
        return decode(raw, allowLegacyMissingIds)
    }

    internal fun decode(
        raw: String,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        if (raw.isBlank()) return corrupt()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return corrupt()
        return inspect(array, allowLegacyMissingIds)
    }

    internal fun inspect(
        array: JSONArray,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        var malformed = false
        val identities = mutableSetOf<String>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                malformed = true
                continue
            }
            val id = optionalString(item, "id")
            if (id == null && !allowLegacyMissingIds) malformed = true
            if (id != null && !identities.add(id)) malformed = true

            val realEntry = positiveLong(item, "realEntry")
            if (realEntry == null) malformed = true
            val realExit = nullablePositiveLong(item, "realExit")
            if (!realExit.valid || (realEntry != null && realExit.value != null && realExit.value <= realEntry)) {
                malformed = true
            }

            val countedEntry = nullablePositiveLong(item, "countedEntry")
            val countedExit = nullablePositiveLong(item, "countedExit")
            if (!countedEntry.valid || !countedExit.valid) malformed = true
            if (countedEntry.value != null && countedExit.value != null && countedExit.value <= countedEntry.value) {
                malformed = true
            }

            if (item.has("companySlot") && !item.isNull("companySlot")) {
                val slot = strictInt(item.opt("companySlot"))
                if (slot !in 1..2) malformed = true
            }
            if (item.has("employerId") && !item.isNull("employerId") && optionalString(item, "employerId") == null) {
                malformed = true
            }
            if (item.has("placeId") && !item.isNull("placeId") && optionalString(item, "placeId") == null) {
                malformed = true
            }
            if (item.has("placeLabel") && !item.isNull("placeLabel") && optionalString(item, "placeLabel") == null) {
                malformed = true
            }
            if (item.has("legacyFixedUnpaidPauseMs") && !item.isNull("legacyFixedUnpaidPauseMs")) {
                val value = strictLong(item.opt("legacyFixedUnpaidPauseMs"))
                if (value == null || value < 0L) malformed = true
            }

            val pauses = item.optJSONArray(KEY_PAUSES)
            if (pauses == null) {
                malformed = true
            } else if (!validPauseArray(pauses)) {
                malformed = true
            }
        }

        return ReadResult(
            history = array,
            reliable = !malformed,
            warnings = if (malformed) listOf(HISTORY_WARNING) else emptyList()
        )
    }

    fun save(context: Context, history: JSONArray): Boolean {
        val inspected = inspect(history)
        if (!inspected.reliable) return false
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, history.toString())
                .commit()
        }.getOrDefault(false)
    }

    internal fun validPauseArray(array: JSONArray): Boolean {
        val identities = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return false
            val start = positiveLong(item, "start") ?: return false
            val end = positiveLong(item, "end") ?: return false
            if (end <= start) return false

            if (!item.has("source") || item.isNull("source")) return false
            val source = optionalString(item, "source") ?: return false
            if (runCatching { EventSourceV2.valueOf(source) }.isFailure) return false

            if (!item.has("paid") || item.opt("paid") !is Boolean) return false
            val identity = "$start:$end:$source:${item.optBoolean("paid")}" 
            if (!identities.add(identity)) return false
        }
        return true
    }

    private data class NullableLong(val valid: Boolean, val value: Long?)

    private fun nullablePositiveLong(item: JSONObject, key: String): NullableLong {
        if (!item.has(key) || item.isNull(key)) return NullableLong(true, null)
        val value = strictLong(item.opt(key)) ?: return NullableLong(false, null)
        return NullableLong(value > 0L, value.takeIf { it > 0L })
    }

    private fun positiveLong(item: JSONObject, key: String): Long? =
        strictLong(item.opt(key))?.takeIf { it > 0L }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            number.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        }
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun strictInt(value: Any?): Int? {
        val long = strictLong(value) ?: return null
        return long.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun optionalString(item: JSONObject, key: String): String? =
        (item.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private fun corrupt() = ReadResult(JSONArray(), false, listOf(HISTORY_WARNING))
}
