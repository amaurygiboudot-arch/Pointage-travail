package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage historique conservé pour rollback.
 *
 * Quand HoraTrack V2 est actif, cette classe devient uniquement une façade de
 * compatibilité pour les anciens widgets/exports encore en migration : aucune
 * mutation métier n'est faite dans l'ancienne base de pointage.
 */
object PointageStore {
    private const val PREFS = "pointage"
    private const val KEY = "data"
    private const val ICON_SYNC_DELAY_MS = 1500L
    private const val ENTRY_SLOT_MS = 30L * 60L * 1000L
    private const val ENTRY_GRACE_MS = 10L * 60L * 1000L
    private val storageLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIconSync: Runnable? = null

    private fun v2Active(): Boolean = HoraTrackV2.ENABLED

    private fun loadUnlocked(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        if (raw.isBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("corrupt_data_backup", raw).apply()
            JSONArray()
        }
    }

    private fun saveUnlocked(context: Context, data: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, data.toString()).apply()
    }

    private fun v2CompatData(context: Context): JSONArray = JSONArray().apply {
        V2RuntimeStore.allSessions(context).forEach { session ->
            val item = JSONObject()
                .put("id", session.id)
                .put("arrivalTime", session.realArrivalMs ?: JSONObject.NULL)
                .put("countedEntryTime", session.countedEntryMs ?: JSONObject.NULL)
                .put("entry", session.countedEntryMs ?: session.realArrivalMs ?: JSONObject.NULL)
                .put("exitTime", session.realExitMs ?: JSONObject.NULL)
                .put("countedExitTime", session.countedExitMs ?: JSONObject.NULL)
                .put("exit", session.countedExitMs ?: session.realExitMs ?: JSONObject.NULL)
                .put("autoPauseMinutes", (session.legacyFixedUnpaidPauseMs / 60_000L).toInt())
                .put("pauses", JSONArray().apply {
                    session.pauses.forEach { pause ->
                        put(
                            JSONObject()
                                .put("start", pause.startMs)
                                .put("end", pause.endMs ?: JSONObject.NULL)
                                .put("manual", pause.source.name == "MANUAL")
                                .put("automatic", pause.source.name == "SYSTEM")
                        )
                    }
                })
            put(item)
        }
    }

    fun load(context: Context): JSONArray =
        if (v2Active()) v2CompatData(context)
        else synchronized(storageLock) { loadUnlocked(context) }

    fun save(context: Context, data: JSONArray) = synchronized(storageLock) {
        check(!v2Active()) { "Écriture PointageStore interdite : HoraTrack V2 est actif" }
        val last = if (data.length() > 0) data.optJSONObject(data.length() - 1) else null
        if (last?.optBoolean("manual", false) == true && !last.has("autoPauseMinutes")) {
            val slot = last.optInt("companySlot", 0)
            last.put("autoPauseMinutes", if (slot in 1..2) CompanyBasePauseSettings.baseMinutes(context, slot) else 0)
            if (!last.has("pauses")) last.put("pauses", JSONArray())
        }
        saveUnlocked(context, data)
    }

    internal fun <T> update(context: Context, block: (JSONArray) -> T): T = synchronized(storageLock) {
        check(!v2Active()) { "Mutation PointageStore interdite : HoraTrack V2 est actif" }
        val data = loadUnlocked(context)
        val result = block(data)
        saveUnlocked(context, data)
        result
    }

    fun hasOpen(context: Context): Boolean {
        if (v2Active()) {
            val session = V2RuntimeStore.snapshot(context).session
            return session != null && session.realExitMs == null
        }
        return findOpenSession(load(context)) != null
    }

    fun isPaused(context: Context): Boolean {
        if (v2Active()) {
            val session = V2RuntimeStore.snapshot(context).session ?: return false
            return session.realExitMs == null && session.pauses.any { it.endMs == null }
        }
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open) != null
    }

    fun isPausedAutomatically(context: Context): Boolean {
        if (v2Active()) return false
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open)?.optBoolean("automatic", false) == true
    }

    private fun scheduleIconSync(context: Context) {
        pendingIconSync?.let(mainHandler::removeCallbacks)
        val app = context.applicationContext
        val task = Runnable { IconSwitcher.sync(app) }
        pendingIconSync = task
        mainHandler.postDelayed(task, ICON_SYNC_DELAY_MS)
    }

    /** Ancienne règle conservée uniquement pour rollback legacy. */
    private fun hiringTimeFromArrival(arrival: Long): Long {
        val remainder = Math.floorMod(arrival, ENTRY_SLOT_MS)
        val currentSlot = arrival - remainder
        if (remainder == 0L) return currentSlot
        return if (remainder <= ENTRY_GRACE_MS) currentSlot else currentSlot + ENTRY_SLOT_MS
    }

    fun entry(context: Context, zoneId: String? = null, zoneAddress: String? = null): Boolean {
        if (v2Active()) return V2RuntimeStore.entry(context)

        val now = System.currentTimeMillis()
        val resumed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            val item = findOpenSession(data) ?: return@synchronized false
            val pause = openPause(item) ?: return@synchronized false
            val start = pause.optLong("start", -1L)
            if (start <= 0L || now < start) return@synchronized false
            pause.put("end", now).put("resumedByEntry", true)
            saveUnlocked(context, data)
            true
        }
        if (resumed) {
            updateWidgets(context)
            scheduleIconSync(context)
            DriveBackupManager.syncCurrentMonthAsync(context)
            return true
        }

        val detected = if (zoneId.isNullOrBlank() && zoneAddress.isNullOrBlank()) currentActiveZone(context) else null
        val finalId = zoneId ?: detected?.first
        val raw = zoneAddress ?: detected?.second
        val finalAddress = raw?.trim()?.takeIf { it.isNotBlank() }?.let { PlaceNames.display(context, it) }
        val slot = resolveCompanySlot(context, raw)
        val base = CompanyBasePauseSettings.baseMinutes(context, slot)
        val countedEntry = hiringTimeFromArrival(now)

        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            if (findOpenSession(data) != null) false else {
                val item = JSONObject()
                    .put("arrivalTime", now)
                    .put("countedEntryTime", countedEntry)
                    .put("entry", countedEntry)
                    .put("countedExitTime", JSONObject.NULL)
                    .put("exitTime", JSONObject.NULL)
                    .put("exit", JSONObject.NULL)
                    .put("pauses", JSONArray())
                    .put("companySlot", slot)
                    .put("autoPauseMinutes", base)
                if (!finalId.isNullOrBlank()) item.put("zoneId", finalId)
                if (!finalAddress.isNullOrBlank()) item.put("zoneAddress", finalAddress)
                data.put(item)
                saveUnlocked(context, data)
                true
            }
        }
        if (!changed) return false
        PauseScheduleManager.applyCurrentWindow(context)
        updateWidgets(context)
        scheduleIconSync(context)
        return true
    }

    fun startPause(context: Context, automatic: Boolean = false): Boolean {
        if (v2Active()) return V2RuntimeStore.togglePause(context)
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            val item = findOpenSession(data) ?: return@synchronized false
            if (openPause(item) != null) return@synchronized false
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || now < entry) return@synchronized false
            val pauses = item.optJSONArray("pauses") ?: JSONArray().also { item.put("pauses", it) }
            val pause = JSONObject().put("start", now).put("end", JSONObject.NULL)
            if (automatic) pause.put("automatic", true)
            pauses.put(pause)
            saveUnlocked(context, data)
            true
        }
        if (!changed) return false
        updateWidgets(context)
        scheduleIconSync(context)
        return true
    }

    fun resumePause(context: Context, automaticOnly: Boolean = false): Boolean {
        if (v2Active()) {
            if (automaticOnly || !isPaused(context)) return false
            return V2RuntimeStore.togglePause(context)
        }
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            val item = findOpenSession(data) ?: return@synchronized false
            val pause = if (automaticOnly) openPause(item)?.takeIf { it.optBoolean("automatic", false) } else currentPause(item)
            pause ?: return@synchronized false
            val start = pause.optLong("start", -1L)
            if (start <= 0L || now < start) return@synchronized false
            pause.put("end", now)
            saveUnlocked(context, data)
            true
        }
        if (!changed) return false
        updateWidgets(context)
        scheduleIconSync(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
        return true
    }

    fun resumeAnyPause(context: Context): Boolean = resumePause(context, automaticOnly = false)

    fun addManualPause(context: Context, pauseStart: Long, pauseEnd: Long): Boolean {
        if (pauseStart <= 0L || pauseEnd <= pauseStart) return false
        if (v2Active()) return V2RuntimeStore.addManualPauses(context, listOf(pauseStart to pauseEnd)) > 0

        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            var target: JSONObject? = null
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                val entry = item.optLong("entry", -1L)
                if (entry <= 0L) continue
                val end = if (item.isNull("exit")) System.currentTimeMillis() else item.optLong("exit", -1L)
                if (end >= entry && pauseStart >= entry && pauseEnd <= end) {
                    target = item
                    break
                }
            }
            val item = target ?: return@synchronized false
            val pauses = item.optJSONArray("pauses") ?: JSONArray().also { item.put("pauses", it) }
            pauses.put(JSONObject().put("start", pauseStart).put("end", pauseEnd).put("manual", true))
            saveUnlocked(context, data)
            true
        }
        if (!changed) return false
        updateWidgets(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
        return true
    }

    fun pauseDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val sessionEnd = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (sessionEnd <= entry) return 0L
        val raw = sessionEnd - entry
        val base = item.optInt("autoPauseMinutes", 0).coerceIn(0, 480) * 60000L
        val pauses = item.optJSONArray("pauses")
        val intervals = mutableListOf<Pair<Long, Long>>()
        if (pauses != null) for (i in 0 until pauses.length()) {
            val pause = pauses.optJSONObject(i) ?: continue
            if (base > 0 && pause.optBoolean("automatic", false)) continue
            val start = pause.optLong("start", -1L)
            val end = if (pause.isNull("end")) until else pause.optLong("end", -1L)
            if (start <= 0L || end <= start) continue
            val a = start.coerceAtLeast(entry)
            val b = end.coerceAtMost(sessionEnd)
            if (b > a) intervals += a to b
        }
        var additional = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var start = intervals.first().first
            var end = intervals.first().second
            for (i in 1 until intervals.size) {
                val (a, b) = intervals[i]
                if (a <= end) end = maxOf(end, b) else {
                    additional += end - start
                    start = a
                    end = b
                }
            }
            additional += end - start
        }
        return (base + additional).coerceIn(0L, raw)
    }

    fun workedDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val end = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (end <= entry) return 0L
        return ((end - entry) - pauseDuration(item, end)).coerceAtLeast(0L)
    }

    fun exit(context: Context): Boolean {
        if (v2Active()) return V2RuntimeStore.exit(context)
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                if (!item.isNull("exit")) continue
                val entry = item.optLong("entry", -1L)
                if (entry <= 0L || now < entry) continue
                openPause(item)?.let { pause -> if (pause.optLong("start", -1L) > 0L) pause.put("end", now) }
                item.put("exitTime", now)
                item.put("countedExitTime", now)
                item.put("exit", now)
                saveUnlocked(context, data)
                return@synchronized true
            }
            false
        }
        if (!changed) return false
        updateWidgets(context)
        scheduleIconSync(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
        return true
    }

    fun manualPausesForDay(context: Context, dayStart: Long, dayEnd: Long): List<Pair<Long, Long>> {
        if (dayStart <= 0L || dayEnd <= dayStart) return emptyList()
        if (v2Active()) {
            return V2RuntimeStore.allSessions(context)
                .flatMap { it.pauses }
                .filter { it.source.name == "MANUAL" }
                .mapNotNull { pause ->
                    val end = pause.endMs ?: return@mapNotNull null
                    if (pause.startMs >= dayStart && pause.startMs < dayEnd && end > pause.startMs) pause.startMs to end else null
                }
                .distinct()
                .sortedBy { it.first }
                .take(5)
        }
        val result = mutableListOf<Pair<Long, Long>>()
        val data = load(context)
        for (i in 0 until data.length()) {
            val pauses = data.optJSONObject(i)?.optJSONArray("pauses") ?: continue
            for (j in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(j) ?: continue
                if (!pause.optBoolean("manual", false)) continue
                val start = pause.optLong("start", -1L)
                val end = pause.optLong("end", -1L)
                if (start >= dayStart && start < dayEnd && end > start) result += start to end
            }
        }
        return result.distinct().sortedBy { it.first }.take(5)
    }

    private fun currentPause(item: JSONObject, now: Long = System.currentTimeMillis()): JSONObject? {
        val pauses = item.optJSONArray("pauses") ?: return null
        for (i in pauses.length() - 1 downTo 0) {
            val pause = pauses.optJSONObject(i) ?: continue
            val start = pause.optLong("start", -1L)
            if (start <= 0L || now < start) continue
            if (pause.isNull("end")) return pause
            val end = pause.optLong("end", -1L)
            if (end > start && now < end) return pause
        }
        return null
    }

    private fun openPause(item: JSONObject): JSONObject? {
        val pauses = item.optJSONArray("pauses") ?: return null
        for (i in pauses.length() - 1 downTo 0) {
            val pause = pauses.optJSONObject(i) ?: continue
            if (pause.optLong("start", -1L) > 0L && pause.isNull("end")) return pause
        }
        return null
    }

    private fun findOpenSession(data: JSONArray): JSONObject? {
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optLong("entry", -1L) > 0L && item.isNull("exit")) return item
        }
        return null
    }

    private fun resolveCompanySlot(context: Context, rawAddress: String?): Int {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val address = rawAddress?.trim().orEmpty()
        if (address.isNotBlank()) {
            val map = runCatching { JSONObject(gps.getString("address_company_slots", "{}") ?: "{}") }.getOrNull()
            val direct = map?.optInt(address, 0) ?: 0
            if (direct in 1..2) return direct
        }
        return 1
    }

    private fun currentActiveZone(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return null
        val ids = prefs.getStringSet("active_zones", emptySet()).orEmpty()
        if (ids.isEmpty()) return null
        return runCatching {
            val zones = JSONArray(prefs.getString("zones", "[]") ?: "[]")
            for (i in 0 until zones.length()) {
                val zone = zones.optJSONObject(i) ?: continue
                val id = zone.optString("id")
                if (id in ids) {
                    val address = zone.optString("address").trim()
                    if (address.isNotBlank()) return@runCatching id to address
                }
            }
            null
        }.getOrNull()
    }

    private fun updateWidgets(context: Context) {
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }
}
