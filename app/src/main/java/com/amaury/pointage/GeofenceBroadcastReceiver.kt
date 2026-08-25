package com.amaury.pointage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.json.JSONArray
import org.json.JSONObject

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val triggeredIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (triggeredIds.isEmpty()) return

        val candidateIds = triggeredIds.filter { SmartSetupManager.isCandidateZone(context, it) }
        val regularIds = triggeredIds.filterNot { it in candidateIds }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                candidateIds.forEach { SmartSetupManager.onCandidateEnter(context, it) }
                if (regularIds.isEmpty()) return

                val activeZones = prefs.getStringSet("active_zones", emptySet())?.toMutableSet() ?: mutableSetOf()
                val wasOutsideAllZones = activeZones.isEmpty()
                activeZones.addAll(regularIds)
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (wasOutsideAllZones && activeZones.isNotEmpty()) {
                    val zoneId = regularIds.firstOrNull()
                    val zoneAddress = findZoneAddress(prefs.getString("zones", "[]"), zoneId)
                    if (PointageStore.entry(context, zoneId, zoneAddress)) {
                        updateWidgets(context)
                        if (!zoneAddress.isNullOrBlank()) showArrivalContactNotification(context, zoneAddress)
                    }
                }
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                candidateIds.forEach { SmartSetupManager.onCandidateExit(context, it) }
                if (regularIds.isEmpty()) return

                val activeZones = prefs.getStringSet("active_zones", emptySet())?.toMutableSet() ?: mutableSetOf()
                activeZones.removeAll(regularIds.toSet())
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (activeZones.isEmpty() && PointageStore.exit(context)) updateWidgets(context)
            }
        }
    }

    private fun updateWidgets(context: Context) {
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }

    private fun findZoneAddress(zonesJson: String?, zoneId: String?): String? {
        if (zoneId.isNullOrBlank()) return null
        return try {
            val zones = JSONArray(zonesJson ?: "[]")
            for (i in 0 until zones.length()) {
                val zone = zones.optJSONObject(i) ?: continue
                if (zone.optString("id") == zoneId) return zone.optString("address").takeIf { it.isNotBlank() }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun showArrivalContactNotification(context: Context, address: String) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val contact = runCatching {
            val contacts = JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}")
            contacts.optJSONObject(address)
        }.getOrNull() ?: return

        if (!contact.optBoolean("enabled", false)) return
        val phone = contact.optString("phone").trim()
        if (phone.isBlank()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val placeName = PlaceNames.get(context, address)?.takeIf { it.isNotBlank() } ?: address
        val contactName = contact.optString("contactName").trim().takeIf { it.isNotBlank() } ?: phone
        val message = "Bonjour, je viens d'arriver à $placeName."

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(phone)}")
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            address.hashCode(),
            smsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "arrival_contact"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Prévenir à l'arrivée", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Arrivé à $placeName")
            .setContentText("Prévenir $contactName")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tu viens d'arriver à $placeName. Appuie ici pour prévenir $contactName par SMS."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(address.hashCode(), notification)
    }
}
