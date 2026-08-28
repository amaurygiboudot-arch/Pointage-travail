package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stockage de test V2 isolé de PointageStore. */
object V2RuntimeStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_ID = "session_id"
    private const val KEY_REAL_ENTRY = "real_entry"
    private const val KEY_COUNTED_ENTRY = "counted_entry"
    private const val KEY_REAL_EXIT = "real_exit"
    private const val KEY_COUNTED_EXIT = "counted_exit"
    private const val KEY_EXPECTED_END = "expected_end"
    private const val KEY_PAUSE_START = "pause_start"
    private const val KEY_PAUSES = "pauses"
    private const val KEY_HISTORY = "history"

    @Volatile private var boundContext: Context? = null

    data class Snapshot(
        val session: WorkSessionV2?,
        val result: com.amaury.pointage.v2.engine.TimeResultV2?
    )

    fun bind(context: Context) { boundContext = context.applicationContext }
    fun snapshotBound(nowMs: Long = System.currentTimeMillis()): Snapshot? = boundContext?.let { snapshot(it, nowMs) }
    fun allSessionsBound(nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> = boundContext?.let { allSessions(it, nowMs) }.orEmpty()

    fun entry(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        expectedEndMs: Long? = null
    ): Boolean {
        bind(context)
        val prefs = prefs(context)
        val open = prefs.getLong(KEY_REAL_ENTRY, 0L) > 0L && prefs.getLong(KEY_REAL_EXIT, 0L) == 0L
        if (open) return false

        val editor = prefs.edit()
            .remove(KEY_ID).remove(KEY_REAL_ENTRY).remove(KEY_COUNTED_ENTRY)
            .remove(KEY_REAL_EXIT).remove(KEY_COUNTED_EXIT).remove(KEY_EXPECTED_END)
            .remove(KEY_PAUSE_START).remove(KEY_PAUSES)
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putLong(KEY_REAL_ENTRY, nowMs)
            .putLong(KEY_COUNTED_ENTRY, HoraTrackV2.time.countedEntryFromRealArrival(nowMs))
            .putString(KEY_PAUSES, "[]")

        expectedEndMs?.takeIf { it > nowMs }?.let { editor.putLong(KEY_EXPECTED_END, it) }
        editor.apply()
        return true
    }

    /**
     * Enregistre uniquement une fin prévue explicitement connue.
     * Cette méthode ne calcule ni n'invente une heure de sortie.
     */
    fun setExpectedEnd(context: Context, expectedEndMs: Long?): Boolean {
        bind(context)
        val prefs = prefs(context)
        val entry = prefs.getLong(KEY_REAL_ENTRY, 0L)
        val closed = prefs.getLong(KEY_REAL_EXIT, 0L) > 0L
        if (entry <= 0L || closed) return false
        val editor = prefs.edit()
        if (expectedEndMs == null) {
            editor.remove(KEY_EXPECTED_END).apply()
            return true
        }
        if (expectedEndMs <= entry) return false
        editor.putLong(KEY_EXPECTED_END, expectedEndMs).apply()
        return true
    }

    fun expectedEnd(context: Context): Long? =
        prefs(context).getLong(KEY_EXPECTED_END, 0L).takeIf { it > 0L }

    fun togglePause(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        bind(context)
        val prefs = prefs(context)
        if (prefs.getLong(KEY_REAL_ENTRY, 0L) <= 0L || prefs.getLong(KEY_REAL_EXIT, 0L) > 0L) return false
        val start = prefs.getLong(KEY_PAUSE_START, 0L)
        if (start <= 0L) {
            prefs.edit().putLong(KEY_PAUSE_START, nowMs).apply()
        } else {
            prefs.edit().putString(KEY_PAUSES, appendPause(prefs.getString(KEY_PAUSES, "[]").orEmpty(), start, nowMs)).remove(KEY_PAUSE_START).apply()
        }
        return true
    }

    fun addManualPauses(context: Context, ranges: List<Pair<Long, Long>>): Int {
        bind(context)
        val prefs = prefs(context)
        val entry = prefs.getLong(KEY_REAL_ENTRY, 0L)
        if (entry <= 0L) return 0
        val realExit = prefs.getLong(KEY_REAL_EXIT, 0L).takeIf { it > 0L }
        var raw = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        var added = 0
        ranges.filter { (s, e) -> s > 0L && e > s && s >= entry && (realExit == null || e <= realExit) }.forEach { (s, e) ->
            val before = runCatching { JSONArray(raw).length() }.getOrDefault(0)
            raw = appendPause(raw, s, e)
            val after = runCatching { JSONArray(raw).length() }.getOrDefault(before)
            if (after > before) added++
        }
        if (added > 0) prefs.edit().putString(KEY_PAUSES, raw).apply()
        return added
    }

    fun exit(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        expectedEndMs: Long? = null
    ): Boolean {
        bind(context)
        val prefs = prefs(context)
        if (prefs.getLong(KEY_REAL_ENTRY, 0L) <= 0L || prefs.getLong(KEY_REAL_EXIT, 0L) > 0L) return false

        val pauseStart = prefs.getLong(KEY_PAUSE_START, 0L)
        var pauses = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        if (pauseStart > 0L) pauses = appendPause(pauses, pauseStart, nowMs)

        val knownExpectedEnd = expectedEndMs
            ?: prefs.getLong(KEY_EXPECTED_END, 0L).takeIf { it > 0L }
        val countedExit = HoraTrackV2.time.countedExitFromRealExit(nowMs, knownExpectedEnd)

        prefs.edit()
            .putString(KEY_PAUSES, pauses)
            .remove(KEY_PAUSE_START)
            .putLong(KEY_REAL_EXIT, nowMs)
            .putLong(KEY_COUNTED_EXIT, countedExit)
            .apply()

        snapshot(context, nowMs).session?.let { persistClosed(context, it) }
        return true
    }

    fun reset(context: Context) {
        bind(context)
        prefs(context).edit().clear().apply()
    }

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): Snapshot {
        bind(context)
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

    fun allSessions(context: Context, nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> {
        bind(context)
        val history = parseHistory(prefs(context).getString(KEY_HISTORY, "[]").orEmpty()).toMutableList()
        val current = snapshot(context, nowMs).session
        if (current != null && history.none { it.id == current.id }) history += current
        return history.sortedBy { it.realArrivalMs ?: Long.MAX_VALUE }
    }

    private fun persistClosed(context: Context, session: WorkSessionV2) {
        if (session.status != SessionStatusV2.CLOSED) return
        val prefs = prefs(context)
        val a = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == session.id) return
        a.put(sessionToJson(session))
        prefs.edit().putString(KEY_HISTORY, a.toString()).apply()
    }

    private fun sessionToJson(session: WorkSessionV2) = JSONObject()
        .put("id", session.id)
        .put("realEntry", session.realArrivalMs ?: JSONObject.NULL)
        .put("countedEntry", session.countedEntryMs ?: JSONObject.NULL)
        .put("realExit", session.realExitMs ?: JSONObject.NULL)
        .put("countedExit", session.countedExitMs ?: JSONObject.NULL)
        .put("pauses", pausesToJson(session.pauses))

    private fun parseHistory(raw: String): List<WorkSessionV2> {
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val realEntry = o.optLong("realEntry", 0L).takeIf { it > 0L } ?: continue
                val realExit = o.optLong("realExit", 0L).takeIf { it > 0L }
                add(
                    WorkSessionV2(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        employerId = null,
                        realArrivalMs = realEntry,
                        countedEntryMs = o.optLong("countedEntry", 0L).takeIf { it > 0L },
                        countedExitMs = o.optLong("countedExit", 0L).takeIf { it > 0L },
                        realExitMs = realExit,
                        pauses = parsePauses(o.optJSONArray("pauses")?.toString() ?: "[]"),
                        status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED
                    )
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appendPause(raw: String, start: Long, end: Long): String {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        if (end <= start) return array.toString()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optLong("start") == start && o.optLong("end") == end) return array.toString()
        }
        array.put(JSONObject().put("start", start).put("end", end))
        return array.toString()
    }

    private fun pausesToJson(pauses: List<PauseV2>) = JSONArray().apply {
        pauses.filter { it.endMs != null && it.endMs!! > it.startMs }
            .forEach { p -> put(JSONObject().put("start", p.startMs).put("end", p.endMs)) }
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
