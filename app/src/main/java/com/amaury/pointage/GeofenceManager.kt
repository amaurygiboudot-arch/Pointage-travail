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
import org.json.JSONArray
import org.json.JSONObject

data class WorkZone(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float
)

internal fun shouldRegisterAutomaticGps(enabled: Boolean): Boolean = enabled

internal fun shouldRecoverAutomaticGpsZones(
    enabled: Boolean,
    currentZoneCount: Int,
    currentAddresses: Set<String>,
    backupAddresses: Set<String>
): Boolean = enabled &&
    currentZoneCount == 0 &&
    currentAddresses.isNotEmpty() &&
    currentAddresses == backupAddresses

object GeofenceManager {
    private const val GPS_PREFS = "gps_settings"
    private const val LAST_GOOD_ZONES = "zones_last_good"
    private const val MAX_ZONES = 10

    private fun isAutomaticGpsEnabled(context: Context): Boolean =
        shouldRegisterAutomaticGps(
            context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
        )

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
        if (!isAutomaticGpsEnabled(context)) {
            remove(context)
            onResult(false, "GPS automatique désactivé")
            return
        }

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

        if (zones.size > MAX_ZONES) {
            onResult(false, "Trop de zones GPS : $MAX_ZONES maximum")
            return
        }

        val geofences = zones.map { zone ->
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
                if (!isAutomaticGpsEnabled(context)) {
                    onResult(false, "GPS automatique désactivé")
                    return@addOnCompleteListener
                }
                try {
                    client.addGeofences(request, pendingIntent(context))
                        .addOnSuccessListener {
                            if (!isAutomaticGpsEnabled(context)) {
                                remove(context)
                                onResult(false, "GPS automatique désactivé")
                                return@addOnSuccessListener
                            }
                            rememberLastGoodZones(context)
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

    private fun rememberLastGoodZones(context: Context) {
        if (!isAutomaticGpsEnabled(context)) return
        val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val parsed = readPersistedGpsZones(prefs)
        if (parsed !is GpsZonesReadResult.Valid || parsed.zones.isEmpty()) return
        val raw = runCatching { prefs.getString("zones", null) }.getOrNull() ?: return
        prefs.edit().putString(LAST_GOOD_ZONES, raw).apply()
    }

    /**
     * Si seul le rayon a été modifié et qu'un géocodage temporaire a vidé la liste
     * des zones, restaure les dernières coordonnées connues puis applique le nouveau
     * rayon. La restauration n'est autorisée que si la liste courante est explicitement
     * valide et vide, et si les adresses sont strictement identiques. Une configuration
     * absente ou corrompue n'est jamais assimilée à une liste vide.
     */
    private fun recoverRadiusOnlyUpdate(context: Context): List<WorkZone> {
        val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val current = readPersistedGpsZones(prefs)
        if (current !is GpsZonesReadResult.Valid) return emptyList()

        val currentAddresses = prefs.getString("address", "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()

        val backup = readPersistedGpsZones(prefs, LAST_GOOD_ZONES)
        if (backup !is GpsZonesReadResult.Valid || backup.zones.isEmpty()) return emptyList()

        val backupAddresses = backup.zones
            .mapNotNull { it.address?.trim()?.takeIf(String::isNotBlank) }
            .map { it.lowercase() }
            .toSet()

        if (!shouldRecoverAutomaticGpsZones(
                enabled = prefs.getBoolean("enabled", false),
                currentZoneCount = current.zones.size,
                currentAddresses = currentAddresses,
                backupAddresses = backupAddresses
            )
        ) return emptyList()

        val radius = prefs.getInt("radius", 150).coerceIn(50, 1000)
        val restoredJson = JSONArray()
        val restoredZones = mutableListOf<WorkZone>()

        backup.zones.forEach { old ->
            restoredJson.put(JSONObject(old.sourceJson).put("radius", radius))
            restoredZones += WorkZone(old.id, old.latitude, old.longitude, radius.toFloat())
        }

        if (restoredZones.isEmpty() || !isAutomaticGpsEnabled(context)) return emptyList()

        prefs.edit()
            .putString("zones", restoredJson.toString())
            .remove("active_zones")
            .apply()

        return restoredZones
    }

    /** Retire uniquement les geofences Android, sans tenter de restaurer une configuration. */
    fun removeRegisteredGeofences(context: Context) {
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        } catch (_: Exception) {
            // L'absence des services de localisation du constructeur ne doit pas bloquer l'application.
        }
    }

    fun remove(context: Context) {
        try {
            val client = LocationServices.getGeofencingClient(context)
            val recoveredZones = recoverRadiusOnlyUpdate(context)
            client.removeGeofences(pendingIntent(context)).addOnCompleteListener {
                if (recoveredZones.isNotEmpty() &&
                    isAutomaticGpsEnabled(context) &&
                    hasRequiredPermissions(context)
                ) {
                    registerAll(context, recoveredZones)
                }
            }
        } catch (_: Exception) {
            // Ne bloque jamais l'application si les services de localisation du constructeur sont absents.
        }
    }
}
