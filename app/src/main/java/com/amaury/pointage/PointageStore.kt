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
    private const val ENTRY_SLOT_MS = 30L * 60L * 1000L
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
        val last = if (data.length() > 0) data.optJSONObject(data.length() - 1) else null
        if (last?.optBoolean("manual", false) == true && !last.has("autoPauseMinutes")) {
            val slot = last.optInt("companySlot", 0)
            last.put("autoPauseMinutes", if (slot in 1..2) CompanyBasePauseSettings.baseMinutes(context, slot) else 0)
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

    fun hasOpen(context: Context) = findOpenSession(load(context)) != null

    fun isPaused(context: Context): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open) != null
    }

    fun isPausedAutomatically(context: Context): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        return currentPause(open)?.optBoolean("automatic", false) == true
    }

    /** Vrai uniquement si la pause active appartient exactement au moteur demandé. */
    fun isPausedByOrigin(context: Context, origin: String): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        val pause = currentPause(open) ?: return false
        return pause.optBoolean("automatic", false) && pause.optString("origin") == origin
    }

    private fun scheduleIconSync(context: Context) {
        pendingIconSync?.let(mainHandler::removeCallbacks)
        val app = context.applicationContext
        val task = Runnable { IconSwitcher.sync(app) }
        pendingIconSync = task
        mainHandler.postDelayed(task, ICON_SYNC_DELAY_MS)
    }

    /** L'heure d'arrivée réelle définit l'heure d'embauche comptée, par tranche de 30 min inférieure. */
    private fun hiringTimeFromArrival(arrival: Long): Long = arrival - Math.floorMod(arrival, ENTRY_SLOT_MS)

    fun entry(context: Context, zoneId: String? = null, zoneAddress: String? = null): Boolean {
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
        val rawAddress = zoneAddress ?: detected?.second
        val finalAddress = rawAddress?.trim()?.takeIf { it.isNotBlank() }?.let { PlaceNames.display(context, it) }
        val shift = ShiftProfileManager.resolve(context, now)
        val slot = resolveCompanySlot(context, rawAddress)
        val companyPause = CompanyBasePauseSettings.baseMinutes(context, slot)
        val fallback = ShiftProfileManager.pauseMinutes(context, shift)
        val basePause = if (companyPause > 0) companyPause else fallback
        val countedEntry = hiringTimeFromArrival(now)

        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            if (findOpenSession(data) != null) false else {
                val item = JSONObject()
                    .put("entry", countedEntry)
                    .put("arrivalTime", now)
                    .put("exit", JSONObject.NULL)
                    .put("pauses", JSONArray())
                    .put("shiftType", shift.id)
                    .put("companySlot", slot)
                    .put("autoPauseMinutes", basePause)
                    .put("modifiedAt", now)
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

    fun startPause(context: Context, automatic: Boolean = false, origin: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            val item = findOpenSession(data) ?: return@synchronized false
            if (openPause(item) != null) return@synchronized false
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || now < entry) return@synchronized false
            val pauses = item.optJSONArray("pauses") ?: JSONArray().also { item.put("pauses", it) }
            val pause = JSONObject().put("start", now).put("end", JSONObject.NULL)
            if (automatic) {
                pause.put("automatic", true)
                origin?.takeIf { it.isNotBlank() }?.let { pause.put("origin", it) }
            }
            pauses.put(pause)
            item.put("modifiedAt", now)
            saveUnlocked(context, data)
            true
        }
        if (!changed) return false
        updateWidgets(context)
        scheduleIconSync(context)
        return true
    }

    /**
     * expectedOrigin protège les moteurs automatiques les uns des autres.
     * Une valeur non nulle interdit de fermer une pause créée par une autre origine.
     */
    fun resumePause(context: Context, automaticOnly: Boolean = false, expectedOrigin: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            val item = findOpenSession(data) ?: return@synchronized false
            val pause = openPause(item) ?: return@synchronized false
            if (automaticOnly && !pause.optBoolean("automatic", false)) return@synchronized false
            if (expectedOrigin != null && pause.optString("origin") != expectedOrigin) return@synchronized false
            val start = pause.optLong("start", -1L)
            if (start <= 0L || now < start) return@synchronized false
            pause.put("end", now)
            item.put("modifiedAt", now)
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
            item.put("modifiedAt", System.currentTimeMillis())
            saveUnlocked(context, data)
            true
        }
        if (!changed) return false
        updateWidgets(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
        return true
    }

    fun pauseDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long = WorkTimeMath.pauseDuration(item, until)
    fun workedDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long = WorkTimeMath.workedDuration(item, until)

    fun exit(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val changed = synchronized(storageLock) {
            val data = loadUnlocked(context)
            for (i in data.length() - 1 downTo 0) {
                val item = data.optJSONObject(i) ?: continue
                if (!item.isNull("exit")) continue
                val entry = item.optLong("entry", -1L)
                if (entry <= 0L || now < entry) continue
                openPause(item)?.let { pause -> if (pause.optLong("start", -1L) > 0L) pause.put("end", now) }
                item.put("exit", now).put("modifiedAt", now)
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
        val salary = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val address = rawAddress?.trim().orEmpty()
        if (address.isNotBlank()) {
            val map = runCatching { JSONObject(gps.getString("address_company_slots", "{}") ?: "{}") }.getOrNull()
            val direct = map?.optInt(address, 0) ?: 0
            if (direct in 1..2) return direct
        }
        return if (salary.getString("company_name", "").orEmpty().isNotBlank()) 1 else 0
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
