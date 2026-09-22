package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_RESTORE_NEEDS_PERMISSION = "geofence_restore_needs_permission"
        const val KEY_RESTORE_STATUS = "geofence_restore_status"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()

        // Après un redémarrage ou une mise à jour, HoraTrack restaure uniquement
        // ses tâches de fond. Android reste maître de l'ouverture de l'interface :
        // aucune Activity n'est lancée automatiquement depuis ce receiver.
        PauseScheduleManager.schedule(context)
        PauseScheduleManager.applyCurrentWindow(context)
        CompanyPauseAlarmManager.scheduleAll(context)

        if (DriveBackupManager.isConfigured(context)) DriveBackupScheduler.schedule(context)

        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) {
            val saved = prefs.edit()
                .putBoolean(KEY_RESTORE_NEEDS_PERMISSION, false)
                .remove(KEY_RESTORE_STATUS)
                .commit()
            if (!saved) {
                GeofenceManager.removeRegisteredGeofences(context)
                pendingResult.finish()
                return
            }
            GeofenceManager.resyncStoredZones(context) { _, _ -> pendingResult.finish() }
            return
        }

        if (!GeofenceManager.hasRequiredPermissions(context)) {
            // Ne désactive surtout pas le choix de l'utilisateur : Android peut retirer
            // l'autorisation en arrière-plan après coup. On garde le GPS demandé actif
            // et on mémorise pourquoi sa restauration est bloquée. À la prochaine
            // ouverture, l'écran GPS affiche déjà l'autorisation manquante et son bouton
            // permet de la réaccorder.
            val saved = prefs.edit()
                .putBoolean(KEY_RESTORE_NEEDS_PERMISSION, true)
                .putString(KEY_RESTORE_STATUS, "Autorisation GPS à réactiver")
                .commit()
            if (!saved) {
                GeofenceManager.removeRegisteredGeofences(context)
                pendingResult.finish()
                return
            }
            GeofenceManager.resyncStoredZones(context) { _, message ->
                prefs.edit().putString(KEY_RESTORE_STATUS, message).apply()
                pendingResult.finish()
            }
            return
        }

        val saved = prefs.edit()
            .putBoolean(KEY_RESTORE_NEEDS_PERMISSION, false)
            .remove(KEY_RESTORE_STATUS)
            .commit()
        if (!saved) {
            GeofenceManager.removeRegisteredGeofences(context)
            pendingResult.finish()
            return
        }
        GeofenceManager.resyncStoredZones(context) { success, message ->
            prefs.edit()
                .putBoolean(
                    KEY_RESTORE_NEEDS_PERMISSION,
                    !success && !GeofenceManager.hasRequiredPermissions(context)
                )
                .putString(KEY_RESTORE_STATUS, message)
                .apply()
            pendingResult.finish()
        }
    }
}
