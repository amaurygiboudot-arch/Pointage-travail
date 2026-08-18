package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PointageStore {
    private const val PREFS = "pointage"
    private const val KEY = "data"

    fun load(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString(KEY, "[]") ?: "[]")
    }

    fun save(context: Context, data: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, data.toString()).apply()
    }

    fun hasOpen(context: Context): Boolean = findOpenSession(load(context)) != null

    fun isPaused(context: Context): Boolean {
        val open = findOpenSession(load(context)) ?: return false
        val pauses = open.optJSONArray("pauses") ?: return false
        for (i in pauses.length() - 1 downTo 0) {
            val pause = pauses.optJSONObject(i) ?: continue
            if (pause.isNull("end")) return true
        }
        return false
    }

    fun entry(context: Context, zoneId: String? = null, zoneAddress: String? = null): Boolean {
        val data = load(context)
        if (findOpenSession(data) != null) return false

        val detectedZone = if (zoneId.isNullOrBlank() && zoneAddress.isNullOrBlank()) currentActiveZone(context) else null
        val finalZoneId = zoneId ?: detectedZone?.first
        val rawAddress = zoneAddress ?: detectedZone?.second
        val finalZoneAddress = rawAddress?.takeIf { it.isNotBlank() }?.let { PlaceNames.display(context, it) }

        val item = JSONObject()
            .put("entry", System.currentTimeMillis())
            .put("exit", JSONObject.NULL)
            .put("pauses", JSONArray())

        if (!finalZoneId.isNullOrBlank()) item.put("zoneId", finalZoneId)
        if (!finalZoneAddress.isNullOrBlank()) item.put("zoneAddress", finalZoneAddress)

        data.put(item)
        save(context, data)
        IconSwitcher.setWorking(context, true)
        return true
    }

    fun startPause(context: Context): Boolean {
        val data = load(context)
        val item = findOpenSession(data) ?: return false
        val pauses = item.optJSONArray("pauses") ?: JSONArray().also { item.put("pauses", it) }
        for (i in 0 until pauses.length()) {
            val pause = pauses.optJSONObject(i) ?: continue
            if (pause.isNull("end")) return false
        }
        pauses.put(JSONObject().put("start", System.currentTimeMillis()).put("end", JSONObject.NULL))
        save(context, data)
        PointageWidgetProvider.updateAll(context)
        return true
    }

    fun resumePause(context: Context): Boolean {
        val data = load(context)
        val item = findOpenSession(data) ?: return false
        val pauses = item.optJSONArray("pauses") ?: return false
        for (i in pauses.length() - 1 downTo 0) {
            val pause = pauses.optJSONObject(i) ?: continue
            if (pause.isNull("end")) {
                pause.put("end", System.currentTimeMillis())
                save(context, data)
                PointageWidgetProvider.updateAll(context)
                return true
            }
        }
        return false
    }

    fun addManualPause(context: Context, pauseStart: Long, pauseEnd: Long): Boolean {
        if (pauseEnd <= pauseStart) return false
        val data = load(context)
        var target: JSONObject? = null
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            val exit = if (item.isNull("exit")) System.currentTimeMillis() else item.optLong("exit", -1L)
            if (entry > 0 && pauseStart >= entry && pauseEnd <= exit) {
                target = item
                break
            }
        }
        val item = target ?: return false
        val pauses = item.optJSONArray("pauses") ?: JSONArray().also { item.put("pauses", it) }
        pauses.put(JSONObject().put("start", pauseStart).put("end", pauseEnd).put("manual", true))
        save(context, data)
        PointageWidgetProvider.updateAll(context)
        DriveBackupManager.syncCurrentMonthAsync(context)
        return true
    }

    fun pauseDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val pauses = item.optJSONArray("pauses") ?: return 0L
        var total = 0L
        for (i in 0 until pauses.length()) {
            val pause = pauses.optJSONObject(i) ?: continue
            val start = pause.optLong("start", -1L)
            val end = if (pause.isNull("end")) until else pause.optLong("end", -1L)
            if (start > 0 && end > start) total += end - start
        }
        return total.coerceAtLeast(0L)
    }

    fun workedDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val end = if (item.isNull("exit")) until else item.optLong("exit", until)
        return ((end - entry) - pauseDuration(item, end)).coerceAtLeast(0L)
    }

    fun exit(context: Context): Boolean {
        val data = load(context)
        for (i in data.length() - 1 downTo 0) {
            val item = data.getJSONObject(i)
            if (item.isNull("exit")) {
                val now = System.currentTimeMillis()
                val pauses = item.optJSONArray("pauses")
                if (pauses != null) {
                    for (j in pauses.length() - 1 downTo 0) {
                        val pause = pauses.optJSONObject(j) ?: continue
                        if (pause.isNull("end")) { pause.put("end", now); break }
                    }
                }
                if (item.optString("zoneId").isBlank() || item.optString("zoneAddress").isBlank()) {
                    currentActiveZone(context)?.let { (zoneId, rawAddress) ->
                        if (item.optString("zoneId").isBlank()) item.put("zoneId", zoneId)
                        if (item.optString("zoneAddress").isBlank()) item.put("zoneAddress", PlaceNames.display(context, rawAddress))
                    }
                }
                item.put("exit", now)
                save(context, data)
                IconSwitcher.setWorking(context, false)
                DriveBackupManager.syncCurrentMonthAsync(context)
                return true
            }
        }
        return false
    }

    private fun findOpenSession(data: JSONArray): JSONObject? {
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) return item
        }
        return null
    }

    private fun currentActiveZone(context: Context): Pair<String, String>? {
        val gpsPrefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!gpsPrefs.getBoolean("enabled", false)) return null
        val activeIds = gpsPrefs.getStringSet("active_zones", emptySet()).orEmpty()
        if (activeIds.isEmpty()) return null

        return try {
            val zones = JSONArray(gpsPrefs.getString("zones", "[]") ?: "[]")
            for (i in 0 until zones.length()) {
                val zone = zones.optJSONObject(i) ?: continue
                val id = zone.optString("id")
                if (id in activeIds) {
                    val address = zone.optString("address").trim()
                    if (address.isNotBlank()) return id to address
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
