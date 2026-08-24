package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Après remplacement de l'APK, Android nous avertit que la nouvelle version est en place.
        // On tente alors de rouvrir HP Travail directement. Certains Android/HyperOS peuvent
        // bloquer un lancement d'activité depuis l'arrière-plan : dans ce cas le système garde
        // son comportement de sécurité normal, sans boucle ni crash.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launch)
                }
            }
        }

        PauseScheduleManager.schedule(context)
        PauseScheduleManager.applyCurrentWindow(context)

        // Au redémarrage / après mise à jour, on réarme la sauvegarde. Le vrai travail
        // Drive est exécuté par DriveBackupReceiver avec goAsync(), pas ici.
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
            val radius = item.optDouble("radius", 150.0).toFloat().coerceIn(50f, 1000f)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            zones += WorkZone(id, latitude, longitude, radius)
        }
        if (zones.isNotEmpty()) GeofenceManager.registerAll(context, zones)
    }
}
