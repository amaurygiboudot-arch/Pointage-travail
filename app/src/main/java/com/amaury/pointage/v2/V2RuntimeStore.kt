package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Petit stockage isolé réservé aux essais du moteur V2. */
object V2RuntimeStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_ID = "session_id"
    private const val KEY_REAL_ENTRY = "real_entry"
    private const val KEY_COUNTED_ENTRY = "counted_entry"
    private const val KEY_REAL_EXIT = "real_exit"
    private const val KEY_COUNTED_EXIT = "counted_exit"
    private const val KEY_PAUSE_START = "pause_start"
    private const val KEY_PAUSES = "pauses"

    data class Snapshot(
        val session: WorkSessionV2?,
        val result: com.amaury.pointage.v2.engine.TimeResultV2?
    )

    fun entry(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        val open = prefs.getLong(KEY_REAL_ENTRY, 0L) > 0L && prefs.getLong(KEY_REAL_EXIT, 0L) == 0L
        if (open) return false
        prefs.edit().clear()
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putLong(KEY_REAL_ENTRY, nowMs)
            .putLong(KEY_COUNTED_ENTRY, HoraTrackV2.time.countedEntryFromRealArrival(nowMs))
            .putString(KEY_PAUSES, "[]")
            .apply()
        return true
    }

    fun togglePause(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_REAL_ENTRY, 0L) <= 0L || prefs.getLong(KEY_REAL_EXIT, 0L) > 0L) return false
        val start = prefs.getLong(KEY_PAUSE_START, 0L)
        if (start <= 0L) {
            prefs.edit().putLong(KEY_PAUSE_START, nowMs).apply()
        } else {
            appendPause(prefs.getString(KEY_PAUSES, "[]").orEmpty(), start, nowMs).also {
                prefs.edit().putString(KEY_PAUSES, it).remove(KEY_PAUSE_START).apply()
            }
        }
        return true
    }

    fun exit(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_REAL_ENTRY, 0L) <= 0L || prefs.getLong(KEY_REAL_EXIT, 0L) > 0L) return false
        val pauseStart = prefs.getLong(KEY_PAUSE_START, 0L)
        var pauses = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        if (pauseStart > 0L) pauses = appendPause(pauses, pauseStart, nowMs)
        prefs.edit()
            .putString(KEY_PAUSES, pauses)
            .remove(KEY_PAUSE_START)
            .putLong(KEY_REAL_EXIT, nowMs)
            .putLong(KEY_COUNTED_EXIT, HoraTrackV2.time.countedExitFromRealExit(nowMs, null))
            .apply()
        return true
    }

    fun reset(context: Context) = prefs(context).edit().clear().apply()

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val prefs = prefs(context)
        val realEntry = prefs.getLong(KEY_REAL_ENTRY, 0L).takeIf { it > 0L } ?: return Snapshot(null, null)
        val realExit = prefs.getLong(KEY_REAL_EXIT, 0L).takeIf { it > 0L }
        val countedEntry = prefs.getLong(KEY_COUNTED_ENTRY, 0L).takeIf { it > 0L }
        val countedExit = prefs.getLong(KEY_COUNTED_EXIT, 0L).takeIf { it > 0L }
        val pauses = parsePauses(prefs.getString(KEY_PAUSES, "[]").orEmpty()).toMutableList()
        prefs.getLong(KEY_PAUSE_START, 0L).takeIf { it > 0L }?.let {
            pauses += PauseV2(it, null, paid = false, source = EventSourceV2.MANUAL)
        }
        val session = WorkSessionV2(
            id = prefs.getString(KEY_ID, null) ?: "v2-test",
            employerId = null,
            realArrivalMs = realEntry,
            countedEntryMs = countedEntry,
            countedExitMs = countedExit,
            realExitMs = realExit,
            pauses = pauses,
            status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED
        )
        return Snapshot(session, HoraTrackV2.time.calculate(session, nowMs))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appendPause(raw: String, start: Long, end: Long): String {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        if (end > start) array.put(JSONObject().put("start", start).put("end", end))
        return array.toString()
    }

    private fun parsePauses(raw: String): List<PauseV2> {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optLong("start", 0L)
                val end = item.optLong("end", 0L)
                if (start > 0L && end > start) add(PauseV2(start, end, paid = false, source = EventSourceV2.MANUAL))
            }
        }
    }
}
