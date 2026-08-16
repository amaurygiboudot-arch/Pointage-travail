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

    fun hasOpen(context: Context): Boolean {
        val data = load(context)
        for (i in 0 until data.length()) {
            if (data.getJSONObject(i).isNull("exit")) return true
        }
        return false
    }

    fun entry(context: Context, zoneId: String? = null, zoneAddress: String? = null): Boolean {
        val data = load(context)
        if (hasOpen(context)) return false

        val detectedZone = if (zoneId.isNullOrBlank() && zoneAddress.isNullOrBlank()) currentActiveZone(context) else null
        val finalZoneId = zoneId ?: detectedZone?.first
        val rawAddress = zoneAddress ?: detectedZone?.second
        val finalZoneAddress = rawAddress?.takeIf { it.isNotBlank() }?.let { PlaceNames.display(context, it) }

        val item = JSONObject()
            .put("entry", System.currentTimeMillis())
            .put("exit", JSONObject.NULL)

        if (!finalZoneId.isNullOrBlank()) item.put("zoneId", finalZoneId)
        if (!finalZoneAddress.isNullOrBlank()) item.put("zoneAddress", finalZoneAddress)

        data.put(item)
        save(context, data)
        IconSwitcher.setWorking(context, true)
        return true
    }

    fun exit(context: Context): Boolean {
        val data = load(context)
        for (i in data.length() - 1 downTo 0) {
            val item = data.getJSONObject(i)
            if (item.isNull("exit")) {
                if (item.optString("zoneId").isBlank() || item.optString("zoneAddress").isBlank()) {
                    currentActiveZone(context)?.let { (zoneId, rawAddress) ->
                        if (item.optString("zoneId").isBlank()) item.put("zoneId", zoneId)
                        if (item.optString("zoneAddress").isBlank()) item.put("zoneAddress", PlaceNames.display(context, rawAddress))
                    }
                }
                item.put("exit", System.currentTimeMillis())
                save(context, data)
                IconSwitcher.setWorking(context, false)
                DriveBackupManager.syncCurrentMonthAsync(context)
                return true
            }
        }
        return false
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
