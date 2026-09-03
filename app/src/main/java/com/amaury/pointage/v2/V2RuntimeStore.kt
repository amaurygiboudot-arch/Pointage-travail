package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.WidgetLocationExpiryScheduler
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stockage runtime V2. Les anciennes données restent séparées et sont migrées en lecture seule. */
object V2RuntimeStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_ID = "session_id"
    private const val KEY_EMPLOYER_ID = "employer_id"
    private const val KEY_COMPANY_SLOT = "company_slot"
    private const val KEY_REAL_ENTRY = "real_entry"
    private const val KEY_COUNTED_ENTRY = "counted_entry"
    private const val KEY_REAL_EXIT = "real_exit"
    private const val KEY_COUNTED_EXIT = "counted_exit"
    private const val KEY_EXPECTED_END = "expected_end"
    private const val KEY_PAUSE_START = "pause_start"
    private const val KEY_PAUSE_SOURCE = "pause_source"
    private const val KEY_PAUSES = "pauses"
    private const val KEY_PLACE_ID = "place_id"
    private const val KEY_PLACE_LABEL = "place_label"
    private const val KEY_HISTORY = "history"

    @Volatile private var boundContext: Context? = null

    data class Snapshot(
        val session: WorkSessionV2?,
        val result: com.amaury.pointage.v2.engine.TimeResultV2?
    )

    fun bind(context: Context) {
        boundContext = context.applicationContext
        V2ProfileStore.bind(context)
    }

    fun snapshotBound(nowMs: Long = System.currentTimeMillis()): Snapshot? = boundContext?.let { snapshot(it, nowMs) }
    fun allSessionsBound(nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> = boundContext?.let { allSessions(it, nowMs) }.orEmpty()

    fun entry(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        expectedEndMs: Long? = null,
        companySlot: Int? = null
    ): Boolean {
        bind(context)
        V2MigrationManager.ensureMigrated(context)
        val prefs = prefs(context)
        val open = safeLong(prefs.all[KEY_REAL_ENTRY]) > 0L && safeLong(prefs.all[KEY_REAL_EXIT]) == 0L
        if (open) return false

        val slot = (companySlot ?: V2ProfileStore.activeCompanySlot(context)).coerceIn(1, 2)
        if (companySlot != null) V2ProfileStore.setActiveCompanySlot(context, slot)
        val employerId = V2ProfileStore.load(context, slot).employer?.id
        val knownExpected = expectedEndMs?.takeIf { it > nowMs }

        val editor = prefs.edit()
            .remove(KEY_ID).remove(KEY_EMPLOYER_ID).remove(KEY_COMPANY_SLOT)
            .remove(KEY_REAL_ENTRY).remove(KEY_COUNTED_ENTRY)
            .remove(KEY_REAL_EXIT).remove(KEY_COUNTED_EXIT).remove(KEY_EXPECTED_END)
            .remove(KEY_PAUSE_START).remove(KEY_PAUSE_SOURCE).remove(KEY_PAUSES)
            .remove(KEY_PLACE_ID).remove(KEY_PLACE_LABEL)
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putInt(KEY_COMPANY_SLOT, slot)
            .putLong(KEY_REAL_ENTRY, nowMs)
            .putLong(KEY_COUNTED_ENTRY, HoraTrackV2.time.countedEntryFromRealArrival(nowMs))
            .putString(KEY_PAUSES, "[]")
        employerId?.let { editor.putString(KEY_EMPLOYER_ID, it) }
        knownExpected?.let { editor.putLong(KEY_EXPECTED_END, it) }
        editor.apply()
        return true
    }

    fun setExpectedEnd(context: Context, expectedEndMs: Long?): Boolean {
        bind(context)
        val prefs = prefs(context)
        val entry = safeLong(prefs.all[KEY_REAL_ENTRY])
        val closed = safeLong(prefs.all[KEY_REAL_EXIT]) > 0L
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

    fun expectedEnd(context: Context): Long? = safeLong(prefs(context).all[KEY_EXPECTED_END]).takeIf { it > 0L }

    fun togglePause(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        source: EventSourceV2 = EventSourceV2.MANUAL,
        paid: Boolean = false
    ): Boolean {
        bind(context)
        val prefs = prefs(context)
        if (safeLong(prefs.all[KEY_REAL_ENTRY]) <= 0L || safeLong(prefs.all[KEY_REAL_EXIT]) > 0L) return false
        val start = safeLong(prefs.all[KEY_PAUSE_START])
        if (start <= 0L) {
            prefs.edit().putLong(KEY_PAUSE_START, nowMs).putString(KEY_PAUSE_SOURCE, source.name).apply()
        } else {
            val storedSource = parseSource(prefs.getString(KEY_PAUSE_SOURCE, null))
            prefs.edit()
                .putString(KEY_PAUSES, appendPause(prefs.getString(KEY_PAUSES, "[]").orEmpty(), start, nowMs, storedSource, paid))
                .remove(KEY_PAUSE_START)
                .remove(KEY_PAUSE_SOURCE)
                .apply()
        }
        return true
    }

    fun addManualPauses(context: Context, ranges: List<Pair<Long, Long>>): Int {
        bind(context)
        val prefs = prefs(context)
        val entry = safeLong(prefs.all[KEY_REAL_ENTRY])
        if (entry <= 0L) return 0
        val realExit = safeLong(prefs.all[KEY_REAL_EXIT]).takeIf { it > 0L }
        var raw = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        var added = 0
        ranges.filter { (s, e) -> s > 0L && e > s && s >= entry && (realExit == null || e <= realExit) }.forEach { (s, e) ->
            val before = runCatching { JSONArray(raw).length() }.getOrDefault(0)
            raw = appendPause(raw, s, e, EventSourceV2.MANUAL, false)
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
        val entry = safeLong(prefs.all[KEY_REAL_ENTRY])
        if (entry <= 0L || safeLong(prefs.all[KEY_REAL_EXIT]) > 0L) return false

        val pauseStart = safeLong(prefs.all[KEY_PAUSE_START])
        var pauses = prefs.getString(KEY_PAUSES, "[]").orEmpty()
        if (pauseStart > 0L) {
            pauses = appendPause(pauses, pauseStart, nowMs, parseSource(prefs.getString(KEY_PAUSE_SOURCE, null)), false)
        }

        val knownExpectedEnd = expectedEndMs
            ?: safeLong(prefs.all[KEY_EXPECTED_END]).takeIf { it > 0L }
        val countedExit = HoraTrackV2.time.countedExitFromRealExit(nowMs, knownExpectedEnd)

        prefs.edit()
            .putString(KEY_PAUSES, pauses)
            .remove(KEY_PAUSE_START)
            .remove(KEY_PAUSE_SOURCE)
            .putLong(KEY_REAL_EXIT, nowMs)
            .putLong(KEY_COUNTED_EXIT, countedExit)
            .apply()

        snapshot(context, nowMs).session?.let { persistClosed(context, it) }
        WidgetLocationExpiryScheduler.schedule(context, nowMs)
        return true
    }

    /** Réservé aux tests isolés. Aucun écran utilisateur ne doit appeler ce reset. */
    fun reset(context: Context) {
        bind(context)
        prefs(context).edit().clear().apply()
    }

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): Snapshot {
        bind(context)
        val prefs = prefs(context)
        val realEntry = safeLong(prefs.all[KEY_REAL_ENTRY]).takeIf { it > 0L } ?: return Snapshot(null, null)
        val realExit = safeLong(prefs.all[KEY_REAL_EXIT]).takeIf { it > 0L }
        val countedEntry = safeLong(prefs.all[KEY_COUNTED_ENTRY]).takeIf { it > 0L }
        val countedExit = safeLong(prefs.all[KEY_COUNTED_EXIT]).takeIf { it > 0L }
        val pauses = parsePauses(prefs.getString(KEY_PAUSES, "[]").orEmpty()).toMutableList()
        safeLong(prefs.all[KEY_PAUSE_START]).takeIf { it > 0L }?.let {
            pauses += PauseV2(it, null, paid = false, source = parseSource(prefs.getString(KEY_PAUSE_SOURCE, null)))
        }
        val slot = safeInt(prefs.all[KEY_COMPANY_SLOT], V2ProfileStore.activeCompanySlot(context)).coerceIn(1, 2)
        val employerId = prefs.getString(KEY_EMPLOYER_ID, null) ?: V2ProfileStore.load(context, slot).employer?.id
        val placeId = prefs.getString(KEY_PLACE_ID, null)?.trim()?.takeIf { it.isNotBlank() }
        val placeLabel = prefs.getString(KEY_PLACE_LABEL, null)?.trim()?.takeIf { it.isNotBlank() }
        val session = WorkSessionV2(
            id = prefs.getString(KEY_ID, null) ?: "v2-runtime",
            employerId = employerId,
            realArrivalMs = realEntry,
            countedEntryMs = countedEntry,
            countedExitMs = countedExit,
            realExitMs = realExit,
            pauses = pauses,
            status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED,
            placeId = placeId,
            placeLabel = placeLabel
        )
        return Snapshot(session, HoraTrackV2.time.calculate(session, nowMs))
    }

    fun allSessions(context: Context, nowMs: Long = System.currentTimeMillis()): List<WorkSessionV2> {
        bind(context)
        V2MigrationManager.ensureMigrated(context)
        val history = parseHistory(context, prefs(context).getString(KEY_HISTORY, "[]").orEmpty()).toMutableList()
        val current = snapshot(context, nowMs).session
        if (current != null && history.none { it.id == current.id }) history += current
        return history.distinctBy { it.id }.sortedBy { it.realArrivalMs ?: Long.MAX_VALUE }
    }

    private fun persistClosed(context: Context, session: WorkSessionV2) {
        if (session.status != SessionStatusV2.CLOSED) return
        val prefs = prefs(context)
        val a = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == session.id) return
        val slot = safeInt(prefs.all[KEY_COMPANY_SLOT], V2ProfileStore.activeCompanySlot(context)).coerceIn(1, 2)
        a.put(sessionToJson(session, slot))
        prefs.edit().putString(KEY_HISTORY, a.toString()).apply()
    }

    private fun sessionToJson(session: WorkSessionV2, companySlot: Int) = JSONObject()
        .put("id", session.id)
        .put("employerId", session.employerId ?: JSONObject.NULL)
        .put("companySlot", companySlot)
        .put("realEntry", session.realArrivalMs ?: JSONObject.NULL)
        .put("countedEntry", session.countedEntryMs ?: JSONObject.NULL)
        .put("realExit", session.realExitMs ?: JSONObject.NULL)
        .put("countedExit", session.countedExitMs ?: JSONObject.NULL)
        .put("placeId", session.placeId ?: JSONObject.NULL)
        .put("placeLabel", session.placeLabel ?: JSONObject.NULL)
        .put("legacyFixedUnpaidPauseMs", session.legacyFixedUnpaidPauseMs)
        .put("pauses", pausesToJson(session.pauses))

    private fun parseHistory(context: Context, raw: String): List<WorkSessionV2> {
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val realEntry = positive(o, "realEntry") ?: continue
                val realExit = positive(o, "realExit")
                val slot = o.optInt("companySlot", 1).coerceIn(1, 2)
                val employerId = o.optString("employerId").takeIf { it.isNotBlank() && it != "null" }
                    ?: V2ProfileStore.load(context, slot).employer?.id
                val placeId = o.optString("placeId").trim().takeIf { it.isNotBlank() && it != "null" }
                val placeLabel = o.optString("placeLabel").trim().takeIf { it.isNotBlank() && it != "null" }
                add(
                    WorkSessionV2(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        employerId = employerId,
                        realArrivalMs = realEntry,
                        countedEntryMs = positive(o, "countedEntry"),
                        countedExitMs = positive(o, "countedExit"),
                        realExitMs = realExit,
                        pauses = parsePauses(o.optJSONArray("pauses")?.toString() ?: "[]"),
                        status = if (realExit == null) SessionStatusV2.OPEN else SessionStatusV2.CLOSED,
                        placeId = placeId,
                        placeLabel = placeLabel,
                        legacyFixedUnpaidPauseMs = positiveOrZero(o, "legacyFixedUnpaidPauseMs")
                    )
                )
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appendPause(raw: String, start: Long, end: Long, source: EventSourceV2, paid: Boolean): String {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        if (end <= start) return array.toString()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optLong("start") == start && o.optLong("end") == end) return array.toString()
        }
        array.put(JSONObject().put("start", start).put("end", end).put("source", source.name).put("paid", paid))
        return array.toString()
    }

    private fun pausesToJson(pauses: List<PauseV2>) = JSONArray().apply {
        pauses.filter { it.endMs != null && it.endMs!! > it.startMs }.forEach { p ->
            put(JSONObject().put("start", p.startMs).put("end", p.endMs).put("source", p.source.name).put("paid", p.paid ?: false))
        }
    }

    private fun parsePauses(raw: String): List<PauseV2> {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = positive(item, "start") ?: continue
                val end = positive(item, "end") ?: continue
                if (end > start) {
                    add(
                        PauseV2(
                            startMs = start,
                            endMs = end,
                            paid = item.optBoolean("paid", false),
                            source = parseSource(item.optString("source"))
                        )
                    )
                }
            }
        }
    }

    private fun parseSource(raw: String?): EventSourceV2 = runCatching {
        EventSourceV2.valueOf(raw.orEmpty())
    }.getOrDefault(EventSourceV2.MANUAL)

    private fun safeLong(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun safeInt(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    private fun positive(o: JSONObject, key: String): Long? = when (val value = o.opt(key)) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private fun positiveOrZero(o: JSONObject, key: String): Long = when (val value = o.opt(key)) {
        is Number -> value.toLong().coerceAtLeast(0L)
        is String -> value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        else -> 0L
    }
}
