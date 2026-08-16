package com.amaury.pointage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (PointageStore.entry(context)) {
                    PointageWidgetProvider.updateAll(context)
                }
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                if (PointageStore.exit(context)) {
                    PointageWidgetProvider.updateAll(context)
                }
            }
        }
    }
}
