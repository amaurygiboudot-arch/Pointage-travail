package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Android moderne bloque fréquemment le lancement d'une Activity depuis un receiver
        // exécuté en arrière-plan. Après une mise à jour, on ne tente donc pas de rouvrir
        // HoraTrack automatiquement : on réarme uniquement les mécanismes persistants.
        // L'utilisateur retrouve normalement l'application au prochain lancement explicite.

        PauseScheduleManager.schedule(context)
        PauseScheduleManager.applyCurrentWindow(context)

        if (DriveBackupManager.isConfigured(context)) {
            DriveBackupScheduler.schedule(context)
        }

        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        if (!GeofenceManager.hasRequiredPermissions(context)) return

        prefs.edit().remove("active_zones").apply()
        val array = runCatching { JSONArray(prefs.getString("zones", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        val zones = mutableListOf<WorkZone>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            val rawRadius = item.optDouble("radius", 150.0)
            if (!latitude.isFinite() || latitude !in -90.0..90.0) continue
            if (!longitude.isFinite() || longitude !in -180.0..180.0) continue
            if (!rawRadius.isFinite()) continue
            val radius = rawRadius.toFloat().coerceIn(50f, 1000f)
            zones += WorkZone(id, latitude, longitude, radius)
        }
        if (zones.isEmpty()) return

        // La réinscription Geofencing est asynchrone (remove puis add). goAsync() garde le
        // receiver vivant jusqu'au callback final. L'appel est également protégé contre les
        // exceptions synchrones de construction Play Services afin de toujours libérer le receiver.
        val pendingResult = goAsync()
        try {
            GeofenceManager.registerAll(context, zones) { _, _ ->
                pendingResult.finish()
            }
        } catch (_: Throwable) {
            pendingResult.finish()
        }
    }
}
