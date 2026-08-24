package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

object PointageStore {
    private const val ICON_SYNC_DELAY_MS = 1500L
    private val storageLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIconSync: Runnable? = null

    private fun loadUnlocked(context: Context): JSONArray =
        AtomicPointageStorage.read(context)

    private fun saveUnlocked(context: Context, data: JSONArray) {
        AtomicPointageStorage.write(context, data)
    }

    fun load(context: Context): JSONArray = synchronized(storageLock) { loadUnlocked(context) }

    fun save(context: Context, data: JSONArray) = synchronized(storageLock) { saveUnlocked(context, data) }

    internal fun <T> update(context: Context, block: (JSONArray) -> T): T = synchronized(storageLock) {
        val data = loadUnlocked(context)
        val result = block(data)
        saveUnlocked(context, data)
        result
    }

    fun hasOpen(context: Context): Boolean = findOpenSession(load(context)) != null

    fun isPaused(context: Context): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open) != null
    }

    fun isPausedAutomatically(context: Context): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open)?.optBoolean("automatic", false) == true
    }

    private fun scheduleIconSync(context: Context) {
        pendingIconSync?.let(mainHandler::removeCallbacks)
        val appContext = context.applicationContext
        val task = Runnable { IconSwitcher.sync(appContext) }
        pendingIconSync = task
        mainHandler.postDelayed(task, ICON_SYNC_DELAY_MS)
    }

    fun entry(context: Context, zoneId: String? = null, zoneAddress: String? = null): Boolean {
        val detectedZone = if (zoneId.isNullOrBlank() && zoneAddress.isNullOrBlank()) currentActiveZone(context) else null
        val finalZoneId = zoneId ?: detectedZone?.first
        val rawAddress = zoneAddress ?: detectedZone?.second
        val finalZoneAddress = rawAddress?.trim()?.takeIf { it.isNotBlank() }?.let { PlaceNames.display(context, it) }
        val now = System.currentTimeMillis()
        val shift = ShiftProfileManager.resolve(context, now)
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            if (findOpenSession(data) != null) false else {
                val item = JSONObject().put("entry", now).put("exit", JSONObject.NULL).put("pauses", JSONArray()).put("shiftType", shift.id).put("autoPauseMinutes", ShiftProfileManager.pauseMinutes(context, shift))
                if (!finalZoneId.isNullOrBlank()) item.put("zoneId", finalZoneId)
                if (!finalZoneAddress.isNullOrBlank()) item.put("zoneAddress", finalZoneAddress)
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

    fun addManualPause(context: Context, pauseStart: Long, pauseEnd: Long): Boolean {
        if (pauseStart <= 0L || pauseEnd <= pauseStart) return false
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            var target: JSONObject? = null
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                val entry = item.optLong("entry", -1L)
                if (entry <= 0L) continue
                val sessionEnd = if (item.isNull("exit")) System.currentTimeMillis() else item.optLong("exit", -1L)
                if (sessionEnd >= entry && pauseStart >= entry && pauseEnd <= sessionEnd) { target = item; break }
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
        val rawDuration = sessionEnd - entry
        val pauses = item.optJSONArray("pauses")
        val intervals = mutableListOf<Pair<Long, Long>>()
        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                val rawStart = pause.optLong("start", -1L)
                val rawEnd = if (pause.isNull("end")) until else pause.optLong("end", -1L)
                if (rawStart <= 0L || rawEnd <= rawStart) continue
                val start = rawStart.coerceAtLeast(entry)
                val end = rawEnd.coerceAtMost(sessionEnd)
                if (end > start) intervals += start to end
            }
        }
        var recorded = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var currentStart = intervals.first().first
            var currentEnd = intervals.first().second
            for (i in 1 until intervals.size) {
                val (start, end) = intervals[i]
                if (start <= currentEnd) currentEnd = maxOf(currentEnd, end)
                else { recorded += currentEnd - currentStart; currentStart = start; currentEnd = end }
            }
            recorded += currentEnd - currentStart
        }
        val automatic = item.optInt("autoPauseMinutes", 0).coerceIn(0, 240) * 60_000L
        return maxOf(recorded, automatic).coerceIn(0L, rawDuration)
    }

    fun workedDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val end = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (end <= entry) return 0L
        return ((end - entry) - pauseDuration(item, end)).coerceAtLeast(0L)
    }

    fun exit(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                if (!item.isNull("exit")) continue
                val entry = item.optLong("entry", -1L)
                if (entry <= 0L || now < entry) continue
                openPause(item)?.let { pause -> val start = pause.optLong("start", -1L); if (start > 0L && now >= start) pause.put("end", now) }
                if (item.optString("zoneId").isBlank() || item.optString("zoneAddress").isBlank()) {
                    currentActiveZone(context)?.let { (zoneId, rawAddress) ->
                        if (item.optString("zoneId").isBlank()) item.put("zoneId", zoneId)
                        if (item.optString("zoneAddress").isBlank()) item.put("zoneAddress", PlaceNames.display(context, rawAddress))
                    }
                }
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
        val result = mutableListOf<Pair<Long, Long>>()
        val data = load(context)
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val pauses = item.optJSONArray("pauses") ?: continue
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
            val start = pause.optLong("start", -1L)
            if (start > 0L && pause.isNull("end")) return pause
        }
        return null
    }

    private fun findOpenSession(data: JSONArray): JSONObject? {
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry > 0L && item.isNull("exit")) return item
        }
        return null
    }

    private fun currentActiveZone(context: Context): Pair<String, String>? {
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!gpsPrefs.getBoolean("enabled", false)) return null
        val activeIds = gpsPrefs.getStringSet("active_zones", emptySet()).orEmpty()
        if (activeIds.isEmpty()) return null
        return runCatching {
            val zones = JSONArray(gpsPrefs.getString("zones", "[]") ?: "[]")
            for (i in 0 until zones.length()) {
                val zone = zones.optJSONObject(i) ?: continue
                val id = zone.optString("id")
                if (id in activeIds) {
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
