package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.EventSourceV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Migration conservative vers le stockage V2. L'ancienne base reste intacte. */
object V2MigrationManager {
    private const val META_PREFS = "horatrack_v2_migration"
    private const val RUNTIME_PREFS = "horatrack_v2_test_runtime"
    private const val LEGACY_PREFS = "pointage"
    private const val LEGACY_KEY = "data"
    private const val HISTORY_KEY = "history"
    private const val VERSION = 4

    data class Result(val imported: Int, val skipped: Int, val legacyCount: Int, val v2Count: Int)

    fun ensureMigrated(context: Context): Result {
        if (!HoraTrackV2.ENABLED) return Result(0, 0, 0, 0)
        val raw = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, "[]").orEmpty()
        return importLegacyArray(context, runCatching { JSONArray(raw) }.getOrElse { JSONArray() })
    }

    fun importLegacyArray(context: Context, legacy: JSONArray): Result {
        if (!HoraTrackV2.ENABLED) return Result(0, 0, legacy.length(), 0)
        val app = context.applicationContext
        val runtime = app.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(runtime.getString(HISTORY_KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val signatures = mutableSetOf<String>()
        for (i in 0 until history.length()) history.optJSONObject(i)?.let { signatures += signatureV2(it) }

        var imported = 0
        var skipped = 0
        for (i in 0 until legacy.length()) {
            val old = legacy.optJSONObject(i) ?: continue
            val realEntry = positive(old, "arrivalTime") ?: positive(old, "entry") ?: continue
            val countedEntry = positive(old, "countedEntryTime") ?: positive(old, "entry") ?: realEntry
            val realExit = positive(old, "exitTime") ?: positive(old, "exit")
            val countedExit = positive(old, "countedExitTime") ?: positive(old, "exit")
            val sig = "$realEntry:${realExit ?: 0L}:$countedEntry:${countedExit ?: 0L}"
            if (sig in signatures) {
                enrichExisting(history, sig, old)
                skipped++
                continue
            }

            val basePauseMinutes = old.optInt("autoPauseMinutes", 0).coerceIn(0, 480)
            val placeLabel = legacyPlace(old)
            history.put(
                JSONObject()
                    .put("id", old.optString("id").ifBlank { "legacy-${UUID.randomUUID()}" })
                    .put("realEntry", realEntry)
                    .put("countedEntry", countedEntry)
                    .put("realExit", realExit ?: JSONObject.NULL)
                    .put("countedExit", countedExit ?: JSONObject.NULL)
                    .put("pauses", migratePauses(old, basePauseMinutes))
                    .put("legacyFixedUnpaidPauseMs", basePauseMinutes * 60_000L)
                    .put("migratedFromLegacy", true)
                    .put("companySlot", old.optInt("companySlot", 1).coerceIn(1, 2))
                    .put("placeId", JSONObject.NULL)
                    .put("placeLabel", placeLabel ?: JSONObject.NULL)
            )
            signatures += sig
            imported++
        }

        runtime.edit().putString(HISTORY_KEY, history.toString()).apply()
        app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", VERSION)
            .putInt("legacy_count", legacy.length())
            .putInt("v2_count", history.length())
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
        return Result(imported, skipped, legacy.length(), history.length())
    }

    private fun migratePauses(old: JSONObject, basePauseMinutes: Int): JSONArray {
        val pauses = JSONArray()
        val oldPauses = old.optJSONArray("pauses") ?: JSONArray()
        for (p in 0 until oldPauses.length()) {
            val pause = oldPauses.optJSONObject(p) ?: continue
            if (basePauseMinutes > 0 && pause.optBoolean("automatic", false)) continue
            val start = positive(pause, "start") ?: continue
            val end = positive(pause, "end") ?: continue
            if (end <= start) continue
            pauses.put(JSONObject().put("start", start).put("end", end).put("paid", false).put("source", EventSourceV2.IMPORT.name))
        }
        return pauses
    }

    private fun enrichExisting(history: JSONArray, signature: String, old: JSONObject) {
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            if (signatureV2(item) != signature) continue
            val basePauseMinutes = old.optInt("autoPauseMinutes", 0).coerceIn(0, 480)
            if (!item.has("legacyFixedUnpaidPauseMs")) item.put("legacyFixedUnpaidPauseMs", basePauseMinutes * 60_000L)
            val existingPauses = item.optJSONArray("pauses") ?: JSONArray()
            if (existingPauses.length() == 0) item.put("pauses", migratePauses(old, basePauseMinutes))
            if (item.optString("placeLabel").isBlank()) legacyPlace(old)?.let { item.put("placeLabel", it) }
            item.put("migratedFromLegacy", true)
            return
        }
    }

    private fun legacyPlace(old: JSONObject): String? = listOf("zoneAddress", "placeLabel", "place", "address")
        .asSequence()
        .map { old.optString(it).trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }

    private fun positive(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val value = o.opt(key)) {
            is Number -> value.toLong().takeIf { it > 0L }
            is String -> value.toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
    }

    private fun signatureV2(o: JSONObject): String {
        val re = positive(o, "realEntry") ?: 0L
        val rx = positive(o, "realExit") ?: 0L
        val ce = positive(o, "countedEntry") ?: 0L
        val cx = positive(o, "countedExit") ?: 0L
        return "$re:$rx:$ce:$cx"
    }
}
