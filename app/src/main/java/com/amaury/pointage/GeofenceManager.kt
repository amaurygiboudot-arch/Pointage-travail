package com.amaury.pointage

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

data class WorkZone(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float
)

object GeofenceManager {

    private fun pendingIntent(context: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            301,
            Intent(context, GeofenceBroadcastReceiver::class.java),
            flags
        )
    }

    fun hasLocationHardware(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            manager.allProviders.isNotEmpty()
        }.getOrDefault(false)
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    fun register(
        context: Context,
        latitude: Double,
        longitude: Double,
        radius: Float,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        registerAll(
            context,
            listOf(WorkZone("workplace_1", latitude, longitude, radius)),
            onResult
        )
    }

    fun registerAll(
        context: Context,
        zones: List<WorkZone>,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!hasLocationHardware(context)) {
            onResult(false, "Aucun service de localisation disponible sur cet appareil")
            return
        }

        if (!hasRequiredPermissions(context)) {
            onResult(false, "Autorisation de localisation manquante")
            return
        }

        if (zones.isEmpty()) {
            remove(context)
            onResult(false, "Aucune adresse configurée")
            return
        }

        val geofences = zones.take(10).map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setNotificationResponsiveness(30_000)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        try {
            val client = LocationServices.getGeofencingClient(context)
            client.removeGeofences(pendingIntent(context)).addOnCompleteListener {
                try {
                    client.addGeofences(request, pendingIntent(context))
                        .addOnSuccessListener {
                            onResult(true, "${geofences.size} zone(s) GPS activée(s)")
                        }
                        .addOnFailureListener {
                            onResult(
                                false,
                                it.message ?: "Service GPS automatique indisponible sur cet appareil"
                            )
                        }
                } catch (_: SecurityException) {
                    onResult(false, "Autorisation de localisation manquante")
                } catch (_: Exception) {
                    onResult(false, "Service GPS automatique indisponible sur cet appareil")
                }
            }
        } catch (_: SecurityException) {
            onResult(false, "Autorisation de localisation manquante")
        } catch (_: Exception) {
            // Certains appareils Android sans services Google (par ex. certaines variantes Huawei)
            // ne proposent pas l'API Geofencing de Google. Le reste de l'application reste utilisable.
            onResult(false, "GPS automatique indisponible : utilise le pointage manuel ou le widget")
        }
    }

    fun remove(context: Context) {
        try {
            LocationServices.getGeofencingClient(context)
                .removeGeofences(pendingIntent(context))
        } catch (_: Exception) {
            // Ne bloque jamais l'application si les services de localisation du constructeur sont absents.
        }
    }
}
