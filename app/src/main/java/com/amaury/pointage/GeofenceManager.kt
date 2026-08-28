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

object GeofenceManager {
    private const val GPS_PREFS = "gps_settings"
    private const val LAST_GOOD_ZONES = "zones_last_good"
    private const val MAX_ZONES = 10

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
                try {
                    client.addGeofences(request, pendingIntent(context))
                        .addOnSuccessListener {
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
        val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("zones", "[]") ?: "[]"
        val hasZones = runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
        if (hasZones) {
            prefs.edit().putString(LAST_GOOD_ZONES, raw).apply()
        }
    }

    /**
     * Si seul le rayon a été modifié et qu'un géocodage temporaire a vidé la liste
     * des zones, restaure les dernières coordonnées connues puis applique le nouveau
     * rayon. La restauration n'est autorisée que si la liste des adresses est
     * strictement identique, afin de ne jamais réutiliser un ancien lieu par erreur.
     */
    private fun recoverRadiusOnlyUpdate(context: Context): List<WorkZone> {
        val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val currentZones = runCatching { JSONArray(prefs.getString("zones", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        if (currentZones.length() > 0) return emptyList()

        val currentAddresses = prefs.getString("address", "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
        if (currentAddresses.isEmpty()) return emptyList()

        val backup = runCatching { JSONArray(prefs.getString(LAST_GOOD_ZONES, "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        if (backup.length() == 0) return emptyList()

        val backupAddresses = buildSet {
            for (i in 0 until backup.length()) {
                val address = backup.optJSONObject(i)?.optString("address")?.trim().orEmpty()
                if (address.isNotBlank()) add(address.lowercase())
            }
        }
        if (backupAddresses != currentAddresses) return emptyList()

        val radius = prefs.getInt("radius", 150).coerceIn(50, 1000)
        val restoredJson = JSONArray()
        val restoredZones = mutableListOf<WorkZone>()

        for (i in 0 until backup.length()) {
            val old = backup.optJSONObject(i) ?: continue
            val id = old.optString("id").takeIf { it.isNotBlank() } ?: continue
            val latitude = old.optDouble("latitude", Double.NaN)
            val longitude = old.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue

            restoredJson.put(JSONObject(old.toString()).put("radius", radius))
            restoredZones += WorkZone(id, latitude, longitude, radius.toFloat())
        }

        if (restoredZones.isEmpty()) return emptyList()

        prefs.edit()
            .putString("zones", restoredJson.toString())
            .putBoolean("enabled", true)
            .remove("active_zones")
            .apply()

        return restoredZones
    }

    fun remove(context: Context) {
        try {
            val client = LocationServices.getGeofencingClient(context)
            val recoveredZones = recoverRadiusOnlyUpdate(context)
            client.removeGeofences(pendingIntent(context)).addOnCompleteListener {
                if (recoveredZones.isNotEmpty() && hasRequiredPermissions(context)) {
                    registerAll(context, recoveredZones)
                }
            }
        } catch (_: Exception) {
            // Ne bloque jamais l'application si les services de localisation du constructeur sont absents.
        }
    }
}
