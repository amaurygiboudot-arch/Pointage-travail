package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.json.JSONArray

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val activeZones = prefs.getStringSet("active_zones", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val triggeredIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val wasOutsideAllZones = activeZones.isEmpty()
                activeZones.addAll(triggeredIds)
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (wasOutsideAllZones && activeZones.isNotEmpty()) {
                    val zoneId = triggeredIds.firstOrNull()
                    val zoneAddress = findZoneAddress(prefs.getString("zones", "[]"), zoneId)

                    if (PointageStore.entry(context, zoneId, zoneAddress)) {
                        PointageWidgetProvider.updateAll(context)
                    }
                }
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                activeZones.removeAll(triggeredIds.toSet())
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (activeZones.isEmpty() && PointageStore.exit(context)) {
                    PointageWidgetProvider.updateAll(context)
                }
            }
        }
    }

    private fun findZoneAddress(zonesJson: String?, zoneId: String?): String? {
        if (zoneId.isNullOrBlank()) return null

        return try {
            val zones = JSONArray(zonesJson ?: "[]")
            for (i in 0 until zones.length()) {
                val zone = zones.getJSONObject(i)
                if (zone.optString("id") == zoneId) {
                    return zone.optString("address").takeIf { it.isNotBlank() }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
