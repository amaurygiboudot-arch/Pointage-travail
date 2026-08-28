package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Migration conservative vers le stockage V2.
 * L'ancienne base reste intacte et n'est utilisée ici qu'en lecture.
 */
object V2MigrationManager {
    private const val META_PREFS = "horatrack_v2_migration"
    private const val RUNTIME_PREFS = "horatrack_v2_test_runtime"
    private const val LEGACY_PREFS = "pointage"
    private const val LEGACY_KEY = "data"
    private const val HISTORY_KEY = "history"
    private const val VERSION = 2

    data class Result(val imported: Int, val skipped: Int, val legacyCount: Int, val v2Count: Int)

    fun ensureMigrated(context: Context): Result {
        if (!HoraTrackV2.ENABLED) return Result(0, 0, 0, 0)
        val app = context.applicationContext
        val legacyRaw = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(LEGACY_KEY, "[]").orEmpty()
        val legacy = runCatching { JSONArray(legacyRaw) }.getOrElse { JSONArray() }
        val runtime = app.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(runtime.getString(HISTORY_KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }

        val signatures = mutableSetOf<String>()
        for (i in 0 until history.length()) {
            history.optJSONObject(i)?.let { signatures += signatureV2(it) }
        }

        var imported = 0
        var skipped = 0
        for (i in 0 until legacy.length()) {
            val old = legacy.optJSONObject(i) ?: continue
            val realEntry = positive(old, "arrivalTime") ?: positive(old, "entry") ?: continue
            val countedEntry = positive(old, "countedEntryTime") ?: positive(old, "entry") ?: realEntry
            val realExit = positive(old, "exitTime") ?: positive(old, "exit")
            val countedExit = positive(old, "countedExitTime") ?: positive(old, "exit")
            val sig = "$realEntry:${realExit ?: 0L}:${countedEntry}:${countedExit ?: 0L}"
            if (sig in signatures) { skipped++; continue }

            val pauses = JSONArray()
            val oldPauses = old.optJSONArray("pauses") ?: JSONArray()
            for (p in 0 until oldPauses.length()) {
                val pause = oldPauses.optJSONObject(p) ?: continue
                val start = positive(pause, "start") ?: continue
                val end = positive(pause, "end") ?: continue
                if (end > start) pauses.put(JSONObject().put("start", start).put("end", end))
            }

            val item = JSONObject()
                .put("id", old.optString("id").ifBlank { "legacy-${UUID.randomUUID()}" })
                .put("realEntry", realEntry)
                .put("countedEntry", countedEntry)
                .put("realExit", realExit ?: JSONObject.NULL)
                .put("countedExit", countedExit ?: JSONObject.NULL)
                .put("pauses", pauses)
                .put("migratedFromLegacy", true)
                .put("companySlot", old.optInt("companySlot", 1).coerceIn(1, 2))
            history.put(item)
            signatures += sig
            imported++
        }

        if (imported > 0) runtime.edit().putString(HISTORY_KEY, history.toString()).apply()
        app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", VERSION)
            .putInt("legacy_count", legacy.length())
            .putInt("v2_count", history.length())
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
        return Result(imported, skipped, legacy.length(), history.length())
    }

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
