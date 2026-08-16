package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        if (!prefs.contains("latitude") || !prefs.contains("longitude")) return
        if (!GeofenceManager.hasRequiredPermissions(context)) return

        val latitude = java.lang.Double.longBitsToDouble(prefs.getLong("latitude", 0L))
        val longitude = java.lang.Double.longBitsToDouble(prefs.getLong("longitude", 0L))
        val radius = prefs.getInt("radius", 150).toFloat()

        GeofenceManager.register(context, latitude, longitude, radius)
    }
}
