package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Les pauses automatiques doivent survivre à un redémarrage ou à une mise à jour,
        // indépendamment de l'activation du pointage GPS.
        PauseScheduleManager.schedule(context)
        PauseScheduleManager.applyCurrentWindow(context)

        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(context)) return

        prefs.edit().remove("active_zones").apply()

        val zonesJson = prefs.getString("zones", "[]") ?: "[]"
        val array = JSONArray(zonesJson)
        val zones = mutableListOf<WorkZone>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            zones.add(
                WorkZone(
                    id = item.getString("id"),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    radius = item.getDouble("radius").toFloat()
                )
            )
        }

        if (zones.isNotEmpty()) {
            GeofenceManager.registerAll(context, zones)
        }
    }
}
