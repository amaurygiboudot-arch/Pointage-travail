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

        // Les anciens appels peuvent encore imposer un slot 1/2. Le flux normal utilise désormais
        // l'identifiant stable de l'entreprise active, ce qui permet plus de deux entreprises.
        val profile = if (companySlot != null) {
            val slot = companySlot.coerceIn(1, 2)
            V2ProfileStore.setActiveCompanySlot(context, slot)
            V2ProfileStore.load(context, slot)
        } else {
            V2ProfileStore.loadActive(context)
        }
        val employerId = profile.employer?.id
        val legacySlot = profile.companySlot.takeIf { it in 1..2 }
        val knownExpected = expectedEndMs?.takeIf { it > nowMs }
            ?: V2ScheduleStore.expectedEndForEntry(context, nowMs)?.takeIf { it > nowMs }

        val editor = prefs.edit()
            .remove(KEY_ID).remove(KEY_EMPLOYER_ID).remove(KEY_COMPANY_SLOT)
            .remove(KEY_REAL_ENTRY).remove(KEY_COUNTED_ENTRY)
            .remove(KEY_REAL_EXIT).remove(KEY_COUNTED_EXIT).remove(KEY_EXPECTED_END)
            .remove(KEY_PAUSE_START).remove(KEY_PAUSE_SOURCE).remove(KEY_PAUSES)
            .remove(KEY_PLACE_ID).remove(KEY_PLACE_LABEL)
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putLong(KEY_REAL_ENTRY, nowMs)
            .putLong(KEY_COUNTED_ENTRY, HoraTrackV2.time.countedEntryFromRealArrival(nowMs))
            .putString(KEY_PAUSES, "[]")
        legacySlot?.let { editor.putInt(KEY_COMPANY_SLOT, it) }
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

    /**
     * Pauses réellement éditables par l'utilisateur : pauses non rémunérées créées
     * manuellement ou par l'ancien programmateur automatique. Les pauses payées sont préservées.
     */
    fun editablePauseRangesForDay(context: Context, dayStart: Long, dayEnd: Long): List<Pair<Long, Long>> {
        if (dayStart <= 0L || dayEnd <= dayStart) return emptyList()
        return allSessions(context)
            .flatMap { it.pauses }
            .filter { pause ->
                pause.paid != true &&
                    (pause.source == EventSourceV2.MANUAL || pause.source == EventSourceV2.SYSTEM)
            }
            .mapNotNull { pause ->
                val end = pause.endMs ?: return@mapNotNull null
                if (pause.startMs in dayStart until dayEnd && end > pause.startMs) pause.startMs to end else null
            }
            .distinct()
            .sortedBy { it.first }
    }

    /**
     * Remplace atomiquement les pauses éditables d'une journée. Les pauses payées ou provenant
     * d'autres sources restent intactes. Une liste vide supprime toutes les pauses éditables du jour.
     */
    fun replaceEditablePausesForDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        ranges: List<Pair<Long, Long>>
    ): Boolean {
        bind(context)
        V2MigrationManager.ensureMigrated(context)
        if (dayStart <= 0L || dayEnd <= dayStart) return false

        val clean = ranges
            .filter { (start, end) -> start > 0L && end > start }
            .distinct()
            .sortedBy { it.first }
        if (clean.any { (start, end) -> start !in dayStart until dayEnd || end > dayEnd }) return false

        val p = prefs(context)
        val history = runCatching { JSONArray(p.getString(KEY_HISTORY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val currentEntry = safeLong(p.all[KEY_REAL_ENTRY])
        val currentExit = safeLong(p.all[KEY_REAL_EXIT]).takeIf { it > 0L }
        val currentId = p.getString(KEY_ID, null)
        val now = System.currentTimeMillis()

        data class Target(val historyIndex: Int? = null, val current: Boolean = false)

        fun historyContains(o: JSONObject, start: Long, end: Long): Boolean {
            val entry = positive(o, "realEntry") ?: return false
            val exit = positive(o, "realExit") ?: return false
            return start >= entry && end <= exit
        }

        fun currentContains(start: Long, end: Long): Boolean {
            if (currentEntry <= 0L || start < currentEntry) return false
            val limit = currentExit ?: now
            return end <= limit
        }

        val targets = mutableListOf<Target>()
        for ((start, end) in clean) {
            var found: Target? = null
            for (i in 0 until history.length()) {
                val session = history.optJSONObject(i) ?: continue
                if (historyContains(session, start, end)) {
                    found = Target(historyIndex = i)
                    break
                }
            }
            if (found == null && currentContains(start, end)) found = Target(current = true)
            if (found == null) return false
            targets += found
        }

        fun filtered(raw: JSONArray): JSONArray = JSONArray().apply {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val start = positive(item, "start")
                val source = parseSource(item.optString("source"))
                val editable = start != null &&
                    start in dayStart until dayEnd &&
                    !item.optBoolean("paid", false) &&
                    (source == EventSourceV2.MANUAL || source == EventSourceV2.SYSTEM)
                if (!editable) put(item)
            }
        }

        for (i in 0 until history.length()) {
            val session = history.optJSONObject(i) ?: continue
            val pauses = session.optJSONArray("pauses") ?: JSONArray()
            session.put("pauses", filtered(pauses))
        }

        var currentPauses = filtered(runCatching { JSONArray(p.getString(KEY_PAUSES, "[]") ?: "[]") }.getOrElse { JSONArray() })

        clean.zip(targets).forEach { (range, target) ->
            val pause = JSONObject()
                .put("start", range.first)
                .put("end", range.second)
                .put("source", EventSourceV2.MANUAL.name)
                .put("paid", false)
            when {
                target.historyIndex != null -> {
                    val session = history.optJSONObject(target.historyIndex) ?: return false
                    val pauses = session.optJSONArray("pauses") ?: JSONArray().also { session.put("pauses", it) }
                    pauses.put(pause)
                }
                target.current -> currentPauses.put(pause)
            }
        }

        // Une session clôturée reste aussi dans les clés runtime courantes. On la garde synchronisée
        // avec sa copie d'historique afin qu'une modification ne réapparaisse pas après redémarrage.
        if (!currentId.isNullOrBlank() && currentEntry > 0L) {
            for (i in 0 until history.length()) {
                val session = history.optJSONObject(i) ?: continue
                if (session.optString("id") == currentId) {
                    currentPauses = session.optJSONArray("pauses") ?: JSONArray()
                    break
                }
            }
        }

        return p.edit()
            .putString(KEY_HISTORY, history.toString())
            .putString(KEY_PAUSES, currentPauses.toString())
            .commit()
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
            ?: V2ScheduleStore.expectedEnd(context, entry, nowMs)
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
        val storedSlot = safeInt(prefs.all[KEY_COMPANY_SLOT], 0).takeIf { it in 1..2 }
        val employerId = prefs.getString(KEY_EMPLOYER_ID, null)
            ?: storedSlot?.let { V2ProfileStore.load(context, it).employer?.id }
            ?: V2ProfileStore.loadActive(context).employer?.id
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
        val legacySlot = safeInt(prefs.all[KEY_COMPANY_SLOT], 0).takeIf { it in 1..2 }
        a.put(sessionToJson(session, legacySlot))
        prefs.edit().putString(KEY_HISTORY, a.toString()).apply()
    }

    private fun sessionToJson(session: WorkSessionV2, companySlot: Int?) = JSONObject()
        .put("id", session.id)
        .put("employerId", session.employerId ?: JSONObject.NULL)
        .apply { companySlot?.let { put("companySlot", it) } }
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
