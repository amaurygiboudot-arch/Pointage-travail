package com.amaury.pointage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

object PointageStore {
    private const val PREFS = "pointage"
    private const val KEY = "data"
    private const val ICON_SYNC_DELAY_MS = 1500L
    private val storageLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIconSync: Runnable? = null

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

    fun load(context: Context): JSONArray = synchronized(storageLock) { loadUnlocked(context) }

    fun save(context: Context, data: JSONArray) = synchronized(storageLock) {
        // La saisie manuelle ajoute sa nouvelle journée à la fin du tableau.
        // On lui attache ici la pause de base de l'entreprise choisie pour que
        // tous les consommateurs de workedDuration() (historique, analyses,
        // salaire, PDF, widgets) utilisent exactement la même règle.
        val last = if (data.length() > 0) data.optJSONObject(data.length() - 1) else null
        if (last?.optBoolean("manual", false) == true && !last.has("autoPauseMinutes")) {
            val companySlot = last.optInt("companySlot", 0)
            val basePause = if (companySlot in 1..2) {
                CompanyBasePauseSettings.baseMinutes(context, companySlot)
            } else 0
            last.put("autoPauseMinutes", basePause)
            if (!last.has("pauses")) last.put("pauses", JSONArray())
        }
        saveUnlocked(context, data)
    }

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
        val companySlot = resolveCompanySlot(context, rawAddress)
        val companyPause = CompanyBasePauseSettings.baseMinutes(context, companySlot)
        val fallbackShiftPause = ShiftProfileManager.pauseMinutes(context, shift)
        val basePause = if (companyPause > 0) companyPause else fallbackShiftPause

        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            if (findOpenSession(data) != null) false else {
                val item = JSONObject()
                    .put("entry", now)
                    .put("exit", JSONObject.NULL)
                    .put("pauses", JSONArray())
                    .put("shiftType", shift.id)
                    .put("companySlot", companySlot)
                    .put("autoPauseMinutes", basePause)
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
        val basePause = item.optInt("autoPauseMinutes", 0).coerceIn(0, 240) * 60_000L
        val pauses = item.optJSONArray("pauses")
        val intervals = mutableListOf<Pair<Long, Long>>()

        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                // Si une pause de base existe, les pauses automatiques programmées
                // représentent cette même pause et ne doivent pas être déduites deux fois.
                if (basePause > 0L && pause.optBoolean("automatic", false)) continue
                val rawStart = pause.optLong("start", -1L)
                val rawEnd = if (pause.isNull("end")) until else pause.optLong("end", -1L)
                if (rawStart <= 0L || rawEnd <= rawStart) continue
                val start = rawStart.coerceAtLeast(entry)
                val end = rawEnd.coerceAtMost(sessionEnd)
                if (end > start) intervals += start to end
            }
        }

        var additional = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var currentStart = intervals.first().first
            var currentEnd = intervals.first().second
            for (i in 1 until intervals.size) {
                val (start, end) = intervals[i]
                if (start <= currentEnd) currentEnd = maxOf(currentEnd, end)
                else {
                    additional += currentEnd - currentStart
                    currentStart = start
                    currentEnd = end
                }
            }
            additional += currentEnd - currentStart
        }

        return (basePause + additional).coerceIn(0L, rawDuration)
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
                openPause(item)?.let { pause ->
                    val start = pause.optLong("start", -1L)
                    if (start > 0L && now >= start) pause.put("end", now)
                }
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

    private fun resolveCompanySlot(context: Context, rawAddress: String?): Int {
        val salaryPrefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val address = rawAddress?.trim().orEmpty()
        if (address.isNotBlank()) {
            val map = runCatching { JSONObject(gpsPrefs.getString("address_company_slots", "{}") ?: "{}") }.getOrNull()
            val direct = map?.optInt(address, 0) ?: 0
            if (direct in 1..2) return direct
            val keys = map?.keys()
            while (keys != null && keys.hasNext()) {
                val key = keys.next()
                if (key.equals(address, ignoreCase = true)) {
                    val slot = map.optInt(key, 0)
                    if (slot in 1..2) return slot
                }
            }
        }
        val company1Exists = salaryPrefs.getString("company_siret", "").orEmpty().isNotBlank() ||
            salaryPrefs.getString("company_name", "").orEmpty().isNotBlank()
        return if (company1Exists) 1 else 1
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