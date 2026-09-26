package com.amaury.pointage

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
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

internal sealed class StoredGeofencePlanV2 {
    data class Register(val zones: List<WorkZone>) : StoredGeofencePlanV2()
    data class Remove(val message: String) : StoredGeofencePlanV2()
}

internal fun planStoredGeofenceRegistrationV2(
    enabled: Boolean,
    hasHardware: Boolean,
    hasPermissions: Boolean,
    stored: GpsZonesReadResult,
    maxZones: Int = 10
): StoredGeofencePlanV2 {
    if (!enabled) return StoredGeofencePlanV2.Remove("GPS automatique désactivé")
    val zones = when (stored) {
        GpsZonesReadResult.Missing -> return StoredGeofencePlanV2.Remove("Aucune zone GPS configurée")
        is GpsZonesReadResult.Corrupt -> return StoredGeofencePlanV2.Remove(
            "Configuration GPS illisible : ${stored.reason}"
        )
        is GpsZonesReadResult.Valid -> stored.zones
    }
    if (zones.isEmpty()) return StoredGeofencePlanV2.Remove("Aucune adresse configurée")
    if (zones.size > maxZones) {
        return StoredGeofencePlanV2.Remove("Trop de zones GPS : $maxZones maximum")
    }
    if (!hasHardware) {
        return StoredGeofencePlanV2.Remove("Aucun service de localisation disponible sur cet appareil")
    }
    if (!hasPermissions) {
        return StoredGeofencePlanV2.Remove("Autorisation de localisation manquante")
    }
    return StoredGeofencePlanV2.Register(zones.map(StoredGpsZone::asWorkZone))
}

internal fun isCurrentStoredGeofenceRegistrationV2(
    registrationValid: Boolean,
    registeredFingerprint: String?,
    currentFingerprint: String
): Boolean = registrationValid &&
    !registeredFingerprint.isNullOrEmpty() &&
    registeredFingerprint == currentFingerprint

object GeofenceManager {
    private const val GPS_PREFS = "gps_settings"
    private const val LAST_GOOD_ZONES = "zones_last_good"
    private const val MAX_ZONES = 10
    private val reconfigurationLock = Any()
    private var reconfigurationRunning = false
    private var reconfigurationSerial = 0L
    private var clearBusinessStateRequested = false
    private var latestReconfigurationContext: Context? = null
    private val reconfigurationCallbacks = mutableListOf<(Boolean, String) -> Unit>()

    private fun isAutomaticGpsEnabled(context: Context): Boolean =
        shouldRegisterAutomaticGps(
            context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
        )

    internal fun isStoredRegistrationCurrent(context: Context): Boolean {
        val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        return isCurrentStoredGeofenceRegistrationV2(
            registrationValid = prefs.getBoolean(
                GpsPresenceStateKeysV2.REGISTRATION_VALID,
                false
            ),
            registeredFingerprint = prefs.getString(
                GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT,
                null
            ),
            currentFingerprint = storedGpsConfigurationFingerprint(prefs)
        )
    }

    private fun invalidateStoredRegistration(context: Context): Boolean =
        context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, false)
            .remove(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT)
            .commit()

    private fun validateStoredRegistration(
        prefs: android.content.SharedPreferences,
        fingerprint: String,
        registeredZones: List<WorkZone>? = null
    ): Boolean {
        if (storedGpsConfigurationFingerprint(prefs) != fingerprint) return false
        if (registeredZones != null) {
            val stored = readPersistedGpsZones(prefs) as? GpsZonesReadResult.Valid ?: return false
            val canonical = stored.zones.map(StoredGpsZone::asWorkZone).sortedBy(WorkZone::id)
            if (canonical != registeredZones.sortedBy(WorkZone::id)) return false
        }
        return prefs.edit()
            .putString(GpsPresenceStateKeysV2.REGISTRATION_FINGERPRINT, fingerprint)
            .putBoolean(GpsPresenceStateKeysV2.REGISTRATION_VALID, true)
            .commit()
    }

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
        invalidateStoredRegistration(context)
        if (!isAutomaticGpsEnabled(context)) {
            removeRegisteredGeofences(context)
            onResult(false, "GPS automatique désactivé")
            return
        }

        if (!hasLocationHardware(context)) {
            removeRegisteredGeofences(context)
            onResult(false, "Aucun service de localisation disponible sur cet appareil")
            return
        }

        if (!hasRequiredPermissions(context)) {
            removeRegisteredGeofences(context)
            onResult(false, "Autorisation de localisation manquante")
            return
        }

        if (zones.isEmpty()) {
            removeRegisteredGeofences(context)
            onResult(false, "Aucune adresse configurée")
            return
        }

        if (zones.size > MAX_ZONES) {
            removeRegisteredGeofences(context)
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
                                removeRegisteredGeofences(context)
                                onResult(false, "GPS automatique désactivé")
                                return@addOnSuccessListener
                            }
                            val prefs = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
                            val fingerprint = storedGpsConfigurationFingerprint(prefs)
                            if (!validateStoredRegistration(prefs, fingerprint, zones)) {
                                removeRegisteredGeofences(context)
                                onResult(false, "Impossible de valider les zones GPS")
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

    /** Réconcilie une vraie modification de configuration et invalide ses anciens états. */
    fun reconfigureStoredZones(
        context: Context,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) = enqueueStoredZonesReconciliation(context, clearBusinessState = true, onResult)

    /** Resynchronise uniquement la plateforme après un callback Android périmé. */
    fun resyncStoredZones(
        context: Context,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) = enqueueStoredZonesReconciliation(context, clearBusinessState = false, onResult)

    private fun enqueueStoredZonesReconciliation(
        context: Context,
        clearBusinessState: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val shouldStart = synchronized(reconfigurationLock) {
            latestReconfigurationContext = context.applicationContext
            reconfigurationSerial++
            clearBusinessStateRequested = clearBusinessStateRequested || clearBusinessState
            reconfigurationCallbacks += onResult
            if (reconfigurationRunning) false else {
                reconfigurationRunning = true
                true
            }
        }
        if (shouldStart) runQueuedStoredZonesReconciliation()
    }

    private fun runQueuedStoredZonesReconciliation() {
        val (app, serial, clearBusinessState) = synchronized(reconfigurationLock) {
            val context = checkNotNull(latestReconfigurationContext)
            val currentSerial = reconfigurationSerial
            val mustClear = clearBusinessStateRequested
            clearBusinessStateRequested = false
            Triple(context, currentSerial, mustClear)
        }
        val prefs = app.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        promoteLegacyEmployerBindingsIfPossible(app, prefs)
        val presenceEditor = prefs.edit()
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach(presenceEditor::remove)
        val presenceCleared = presenceEditor.commit()
        val businessCleared = !clearBusinessState ||
            GpsWorkStateCoordinatorV2.clearForGpsConfigurationChange(app)
        if (!presenceCleared || !businessCleared) {
            removeRegisteredGeofences(app)
            finishStoredZonesReconciliation(serial, false, "Impossible de réinitialiser l'état GPS")
            return
        }

        val fingerprint = storedGpsConfigurationFingerprint(prefs)
        val plan = planStoredGeofenceRegistrationV2(
            enabled = prefs.getBoolean("enabled", false),
            hasHardware = hasLocationHardware(app),
            hasPermissions = hasRequiredPermissions(app),
            stored = readPersistedGpsZones(prefs),
            maxZones = MAX_ZONES
        )

        try {
            val client = LocationServices.getGeofencingClient(app)
            client.removeGeofences(pendingIntent(app)).addOnCompleteListener { removed ->
                if (restartStoredZonesReconciliationIfStale(serial, prefs, fingerprint)) return@addOnCompleteListener
                if (!removed.isSuccessful) {
                    finishStoredZonesReconciliation(serial, false, "Impossible de retirer les anciennes zones GPS")
                    return@addOnCompleteListener
                }
                when (plan) {
                    is StoredGeofencePlanV2.Remove ->
                        finishStoredZonesReconciliation(serial, false, plan.message)
                    is StoredGeofencePlanV2.Register -> addSerializedGeofences(
                        app,
                        prefs,
                        serial,
                        fingerprint,
                        plan.zones
                    )
                }
            }
        } catch (_: Exception) {
            finishStoredZonesReconciliation(
                serial,
                false,
                "GPS automatique indisponible : utilise le pointage manuel ou le widget"
            )
        }
    }

    private fun addSerializedGeofences(
        context: Context,
        prefs: android.content.SharedPreferences,
        serial: Long,
        fingerprint: String,
        zones: List<WorkZone>
    ) {
        val geofences = zones.map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setNotificationResponsiveness(30_000)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnCompleteListener { added ->
                    if (restartStoredZonesReconciliationIfStale(serial, prefs, fingerprint)) {
                        return@addOnCompleteListener
                    }
                    if (added.isSuccessful) {
                        if (!validateStoredRegistration(prefs, fingerprint)) {
                            removeRegisteredGeofences(context)
                            finishStoredZonesReconciliation(
                                serial,
                                false,
                                "Impossible de valider les zones GPS"
                            )
                            return@addOnCompleteListener
                        }
                        rememberLastGoodZones(context)
                        finishStoredZonesReconciliation(
                            serial,
                            true,
                            "${geofences.size} zone(s) GPS activée(s)"
                        )
                    } else {
                        removeRegisteredGeofences(context)
                        finishStoredZonesReconciliation(
                            serial,
                            false,
                            added.exception?.message ?: "Service GPS automatique indisponible sur cet appareil"
                        )
                    }
                }
        } catch (_: Exception) {
            removeRegisteredGeofences(context)
            finishStoredZonesReconciliation(serial, false, "Service GPS automatique indisponible sur cet appareil")
        }
    }

    private fun restartStoredZonesReconciliationIfStale(
        serial: Long,
        prefs: android.content.SharedPreferences,
        fingerprint: String
    ): Boolean {
        val stale = synchronized(reconfigurationLock) {
            if (storedGpsConfigurationFingerprint(prefs) != fingerprint && serial == reconfigurationSerial) {
                reconfigurationSerial++
            }
            serial != reconfigurationSerial
        }
        if (stale) runQueuedStoredZonesReconciliation()
        return stale
    }

    private fun finishStoredZonesReconciliation(serial: Long, success: Boolean, message: String) {
        val callbacks = synchronized(reconfigurationLock) {
            if (serial != reconfigurationSerial) return@synchronized null
            reconfigurationRunning = false
            latestReconfigurationContext = null
            reconfigurationCallbacks.toList().also { reconfigurationCallbacks.clear() }
        }
        if (callbacks == null) {
            runQueuedStoredZonesReconciliation()
            return
        }
        callbacks.forEach { callback -> callback(success, message) }
    }

    private fun promoteLegacyEmployerBindingsIfPossible(
        context: Context,
        prefs: android.content.SharedPreferences
    ) {
        if (!HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)) return
        val storedZones = readPersistedGpsZones(prefs)
        if (storedZones !is GpsZonesReadResult.Valid || storedZones.zones.isEmpty()) return

        val companies = SalaryCompanyStore.readConfirmed(context)
        if (!companies.reliable || companies.companies.isEmpty()) return

        val legacyMap = if (prefs.contains("address_company_slots")) {
            val raw = runCatching { prefs.getString("address_company_slots", null) }.getOrNull()
            runCatching { raw?.let(::JSONObject) }.getOrNull()
        } else {
            null
        }

        val editable = storedZones.toMutableJsonArrayOrNull() ?: return
        val promoted = promoteLegacyGpsEmployerBindingsV2(
            zones = editable,
            legacyAddressSlots = legacyMap,
            confirmedCompanyIds = companies.companies.map { it.id }
        )
        if (promoted <= 0) return

        // Conserver les anciens slots comme métadonnées de compatibilité/rollback.
        // Le runtime V2 utilisera désormais companyId en priorité.
        prefs.edit().putString("zones", editable.toString()).commit()
    }

    private fun storedGpsConfigurationFingerprint(prefs: android.content.SharedPreferences): String {
        val zones = prefs.all["zones"]
        return "${prefs.getBoolean("enabled", false)}|${zones?.javaClass?.name}|$zones"
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
            .remove("entry_resolution_pending")
            .remove("entry_resolution_token")
            .remove("pending_exit_zones")
            .apply()

        return restoredZones
    }

    /** Retire uniquement les geofences Android, sans tenter de restaurer une configuration. */
    fun removeRegisteredGeofences(context: Context) {
        invalidateStoredRegistration(context)
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
