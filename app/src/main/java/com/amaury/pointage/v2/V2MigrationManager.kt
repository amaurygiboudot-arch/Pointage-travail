package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
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
    private const val VERSION = 6

    private const val RUNTIME_REAL_ENTRY = "real_entry"
    private const val RUNTIME_COUNTED_ENTRY = "counted_entry"
    private const val LEGACY_STORAGE_WARNING =
        "Ancien historique de pointage illisible : migration V2 bloquée sans modifier les données existantes."

    data class Result(
        val imported: Int,
        val skipped: Int,
        val legacyCount: Int,
        val v2Count: Int,
        val reliable: Boolean = true,
        val warnings: List<String> = emptyList()
    )

    fun ensureMigrated(context: Context): Result {
        if (!HoraTrackV2.ENABLED) return Result(0, 0, 0, 0)
        val prefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(LEGACY_KEY)) return importLegacyArray(context, JSONArray())
        val raw = runCatching { prefs.getString(LEGACY_KEY, null) }.getOrNull()
            ?: return Result(0, 0, 0, 0, false, listOf(LEGACY_STORAGE_WARNING))
        if (raw.isBlank()) return Result(0, 0, 0, 0, false, listOf(LEGACY_STORAGE_WARNING))
        val legacy = runCatching { JSONArray(raw) }.getOrNull()
            ?: return Result(0, 0, 0, 0, false, listOf(LEGACY_STORAGE_WARNING))
        return importLegacyArray(context, legacy)
    }

    fun importLegacyArray(context: Context, legacy: JSONArray): Result {
        if (!HoraTrackV2.ENABLED) return Result(0, 0, legacy.length(), 0)
        val app = context.applicationContext
        val runtime = app.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val storedHistory = V2RuntimeHistoryGuardV2.read(app, allowLegacyMissingIds = true)
        if (!storedHistory.reliable) {
            return Result(
                imported = 0,
                skipped = legacy.length(),
                legacyCount = legacy.length(),
                v2Count = storedHistory.history.length(),
                reliable = false,
                warnings = storedHistory.warnings
            )
        }
        val history = storedHistory.history

        // Les toutes premières versions V2 pouvaient contenir un historique sans identifiant de
        // session. Les faits SESSION ont besoin d'une identité durable : on répare donc ces rares
        // entrées avant toute lecture et on persiste l'identifiant dans le même historique local.
        ensureStableHistoryIds(history)

        // Réparation strictement ciblée de la régression 15 min / 5 min. Les valeurs
        // manuelles ou historiques qui ne correspondent pas exactement à ce bug restent intactes.
        repairKnownCountedEntries(runtime, history)

        val signatures = mutableSetOf<String>()
        for (i in 0 until history.length()) history.optJSONObject(i)?.let { signatures += signatureV2(it) }

        var imported = 0
        var skipped = 0
        for (i in 0 until legacy.length()) {
            val old = legacy.optJSONObject(i)
            if (old == null) {
                skipped++
                continue
            }
            val realEntry = positive(old, "arrivalTime") ?: positive(old, "entry")
            if (realEntry == null) {
                skipped++
                continue
            }
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

        val inspected = V2RuntimeHistoryGuardV2.inspect(history)
        if (!inspected.reliable || !V2RuntimeHistoryGuardV2.save(app, history)) {
            return Result(
                imported = 0,
                skipped = legacy.length(),
                legacyCount = legacy.length(),
                v2Count = storedHistory.history.length(),
                reliable = false,
                warnings = inspected.warnings.ifEmpty { listOf("Historique V2 : sauvegarde de migration impossible ; données précédentes conservées.") }
            )
        }
        app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", VERSION)
            .putInt("legacy_count", legacy.length())
            .putInt("v2_count", history.length())
            .putLong("checked_at", System.currentTimeMillis())
            .commit()
        return Result(imported, skipped, legacy.length(), history.length())
    }

    private fun ensureStableHistoryIds(history: JSONArray) {
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val current = item.optString("id").trim()
            if (current.isNotBlank() && current != "null") continue
            val signature = signatureV2(item).replace(':', '-')
            item.put("id", "legacy-v2-$signature-$i")
        }
    }

    private fun repairKnownCountedEntries(runtime: android.content.SharedPreferences, history: JSONArray) {
        var historyChanged = false
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val realEntry = positive(item, "realEntry")
            val stored = positive(item, "countedEntry")
            val repaired = WorkTimePolicyV2.repairKnownCountedEntry(realEntry, stored)
            if (repaired != null && stored != null && repaired != stored) {
                item.put("countedEntry", repaired)
                historyChanged = true
            }
        }

        val currentReal = when (val value = runtime.all[RUNTIME_REAL_ENTRY]) {
            is Number -> value.toLong().takeIf { it > 0L }
            is String -> value.toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
        val currentStored = when (val value = runtime.all[RUNTIME_COUNTED_ENTRY]) {
            is Number -> value.toLong().takeIf { it > 0L }
            is String -> value.toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
        val currentRepaired = WorkTimePolicyV2.repairKnownCountedEntry(currentReal, currentStored)
        if (currentRepaired != null && currentStored != null && currentRepaired != currentStored) {
            runtime.edit().putLong(RUNTIME_COUNTED_ENTRY, currentRepaired).commit()
        }
        if (historyChanged) {
            // La sauvegarde finale est effectuée une seule fois par importLegacyArray après validation
            // de l'ensemble du paquet ; on évite donc ici toute écriture intermédiaire partielle.
        }
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
