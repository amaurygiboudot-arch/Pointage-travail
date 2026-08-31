package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ajoute une session manuelle fermée directement dans l'historique V2. */
object V2ManualSessionWriter {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_HISTORY = "history"

    fun add(
        context: Context,
        realStartMs: Long,
        realEndMs: Long,
        companySlot: Int,
        place: String? = null
    ): Boolean {
        if (!HoraTrackV2.ENABLED || realStartMs <= 0L || realEndMs <= realStartMs) return false
        V2RuntimeStore.bind(context)
        V2MigrationManager.ensureMigrated(context)
        val slot = companySlot.coerceIn(1, 2)
        val employerId = V2ProfileStore.load(context, slot).employer?.id
        val countedEntry = HoraTrackV2.time.countedEntryFromRealArrival(realStartMs)
        val expectedEnd = V2ScheduleStore.expectedEnd(context, realStartMs, realEndMs)
        val countedExit = HoraTrackV2.time.countedExitFromRealExit(realEndMs, expectedEnd)
        val placeLabel = place?.trim()?.takeIf { it.isNotBlank() }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val signature = "$realStartMs:$realEndMs:$countedEntry:$countedExit:$slot"
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            val existing = "${o.optLong("realEntry", 0L)}:${o.optLong("realExit", 0L)}:${o.optLong("countedEntry", 0L)}:${o.optLong("countedExit", 0L)}:${o.optInt("companySlot", 1)}"
            if (existing == signature) return false
        }

        history.put(
            JSONObject()
                .put("id", "manual-${UUID.randomUUID()}")
                .put("employerId", employerId ?: JSONObject.NULL)
                .put("companySlot", slot)
                .put("realEntry", realStartMs)
                .put("countedEntry", countedEntry)
                .put("realExit", realEndMs)
                .put("countedExit", countedExit)
                .put("pauses", JSONArray())
                .put("source", "MANUAL")
                .put("placeId", JSONObject.NULL)
                .put("placeLabel", placeLabel ?: JSONObject.NULL)
                .put("place", placeLabel ?: JSONObject.NULL)
        )
        prefs.edit().putString(KEY_HISTORY, history.toString()).apply()
        return true
    }
}
