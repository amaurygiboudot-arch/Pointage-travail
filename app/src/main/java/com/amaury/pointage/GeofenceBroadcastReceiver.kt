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
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2SessionPlaceStore
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.json.JSONObject
import java.util.UUID

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private companion object {
        const val ENTRY_BATCH_WINDOW_MS = 2_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val triggeredIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (triggeredIds.isEmpty()) return

        val stored = readPersistedGpsZones(prefs)
        val canonicalZones = (stored as? GpsZonesReadResult.Valid)?.zones
        if (canonicalZones == null) {
            // Absent/corrompu != vide. Aucun ancien geofence de la plateforme ne doit
            // pouvoir fabriquer un événement métier à partir d'un état non prouvé.
            finishAfterGpsReconciliation(context, configurationChanged = true)
            return
        }

        if (!GeofenceManager.isStoredRegistrationCurrent(context)) {
            // Le verrou d'empreinte protège aussi les zones candidates : une ancienne
            // géométrie ne doit ni pointer ni alimenter un apprentissage de présence.
            finishAfterGpsReconciliation(
                context,
                configurationChanged = canonicalZones.isEmpty()
            )
            return
        }

        val candidateIds = triggeredIds.filter { SmartSetupManager.isCandidateZone(context, it) }
        val regularIds = triggeredIds.filterNot { it in candidateIds }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> candidateIds.forEach {
                SmartSetupManager.onCandidateEnter(context, it)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> candidateIds.forEach {
                SmartSetupManager.onCandidateExit(context, it)
            }
        }

        if (regularIds.isEmpty()) return

        val zonesById = canonicalZones.associateBy { it.id }
        if (regularIds.any { it !in zonesById }) {
            // Un requestId inconnu est un geofence Android périmé, pas une zone de travail.
            // On refuse l'événement et on resynchronise la plateforme avec la configuration
            // canonique actuelle afin que le stale geofence ne puisse plus se représenter.
            clearZonePresenceState(prefs)
            if (canonicalZones.isEmpty()) {
                finishAfterGpsReconciliation(context, configurationChanged = true)
            } else {
                finishAfterGpsReconciliation(context, configurationChanged = false)
            }
            return
        }

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GpsTransitionV2.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GpsTransitionV2.EXIT
            else -> return
        }
        val activeZones = prefs.getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, emptySet())
            ?.filterTo(mutableSetOf()) { it in zonesById }
            ?: mutableSetOf()
        val pendingExitZones = prefs
            .getStringSet(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES, emptySet())
            ?.filterTo(mutableSetOf()) { it in zonesById }
            ?: mutableSetOf()
        val plan = GpsActiveZoneTransitionV2.plan(
            activeZoneIds = activeZones,
            triggeredZoneIds = regularIds,
            transition = transition,
            entryResolutionPending = prefs.getBoolean(
                GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING,
                false
            ),
            pendingExitZoneIds = pendingExitZones
        )

        when (val action = plan.action) {
            GpsActiveZoneTransitionV2.Action.None -> {
                if (GpsActiveZoneTransitionV2.needsDeferredEntryResolution(plan)) {
                    // Replanifier aussi après redémarrage du processus : plusieurs callbacks
                    // renouvellent le jeton et invalident les temporisations plus anciennes.
                    scheduleEntryResolution(
                        context,
                        prefs,
                        zonesById,
                        plan.activeZoneIds,
                        plan.pendingExitZoneIds
                    )
                } else {
                    persistZonePresenceState(
                        prefs,
                        plan.activeZoneIds,
                        plan.entryResolutionPending,
                        plan.pendingExitZoneIds
                    )
                }
            }

            is GpsActiveZoneTransitionV2.Action.ResolveEntry -> {
                if (transition == GpsTransitionV2.ENTER) {
                    scheduleEntryResolution(
                        context,
                        prefs,
                        zonesById,
                        plan.activeZoneIds,
                        pendingExitZoneIds = plan.pendingExitZoneIds
                    )
                } else {
                    resolveEntryNow(
                        context = context,
                        prefs = prefs,
                        zoneIds = action.zoneIds,
                        zonesById = zonesById,
                        activeZoneIds = plan.activeZoneIds,
                        pendingExitZoneIds = plan.pendingExitZoneIds
                    )
                }
            }

            is GpsActiveZoneTransitionV2.Action.ResolveExit -> {
                val choice = chooseExitZone(context, action.zoneIds, zonesById)
                val stateSaved = persistZonePresenceState(
                    prefs,
                    plan.activeZoneIds,
                    entryResolutionPending = false,
                    pendingExitZoneIds = plan.pendingExitZoneIds
                )
                if (stateSaved && choice != null &&
                    HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)
                ) {
                    dispatchExit(context, choice, System.currentTimeMillis())
                }
            }
        }
        updateWidgets(context)
    }

    private fun finishAfterGpsReconciliation(context: Context, configurationChanged: Boolean) {
        val pendingResult = goAsync()
        val callback: (Boolean, String) -> Unit = { _, _ -> pendingResult.finish() }
        if (configurationChanged) {
            GeofenceManager.reconfigureStoredZones(context, callback)
        } else {
            GeofenceManager.resyncStoredZones(context, callback)
        }
    }

    private data class TriggeredZoneChoice(
        val zone: StoredGpsZone,
        val employerResolution: GpsZoneEmployerResolutionV2
    )

    private fun chooseEntryZone(
        context: Context,
        prefs: android.content.SharedPreferences,
        zoneIds: List<String>,
        zonesById: Map<String, StoredGpsZone>
    ): TriggeredZoneChoice? {
        val companies = SalaryCompanyStore.readConfirmed(context)
        val confirmedCompanyIds = companies.companies.map { it.id }
        val confirmedActiveCompanyId = if (companies.reliable) {
            V2ProfileStore.activeCompanyId(context)?.takeIf { it in confirmedCompanyIds }
        } else {
            null
        }
        val resolved = zoneIds.distinct().mapNotNull { id ->
            val zone = zonesById[id] ?: return null
            val legacySlot = zone.companySlot ?: resolveLegacyAddressCompanySlot(prefs, zone)
            val employerResolution = resolveGpsZoneEmployerV2(
                companyId = zone.companyId,
                legacyCompanySlot = legacySlot,
                companiesReliable = companies.reliable,
                confirmedCompanyIds = confirmedCompanyIds
            )
            val employerKey = GpsTriggeredZoneSelectionV2.employerKey(
                employerResolution,
                confirmedActiveCompanyId,
                companiesReliable = companies.reliable
            ) ?: return null
            TriggeredZoneChoice(zone, employerResolution) to
                GpsTriggeredZoneSelectionV2.Candidate(
                    zoneId = zone.id,
                    employerKey = employerKey,
                    pointType = GpsTriggeredZoneSelectionV2.pointType(zone),
                    placeKey = GpsTriggeredZoneSelectionV2.placeKey(zone)
                )
        }

        val preferredZoneId = GpsWorkStateCoordinatorV2.pending(context)
            ?.takeIf { it.kind == GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE }
            ?.placeId
        return when (
            val selected = GpsTriggeredZoneSelectionV2.select(
                resolved.map { it.second },
                preferredZoneId
            )
        ) {
            is GpsTriggeredZoneSelectionV2.Result.Blocked -> null
            is GpsTriggeredZoneSelectionV2.Result.Selected ->
                resolved.firstOrNull { it.first.zone.id == selected.zoneId }?.first
        }
    }

    private fun chooseExitZone(
        context: Context,
        zoneIds: List<String>,
        zonesById: Map<String, StoredGpsZone>
    ): StoredGpsZone? {
        val now = System.currentTimeMillis()
        val runtime = V2RuntimeReader.current(context, now)
        val openSession = runtime.snapshot.session
            ?.takeIf { it.status == SessionStatusV2.OPEN }
        val openSessionPlaceId = openSession?.placeId
        val candidates = zoneIds.mapNotNull { zoneId ->
            zonesById[zoneId]?.let { zone ->
                GpsTriggeredZoneSelectionV2.ExitCandidate(
                    zoneId = zone.id,
                    pointType = GpsTriggeredZoneSelectionV2.pointType(zone),
                    placeKey = GpsTriggeredZoneSelectionV2.placeKey(zone)
                )
            }
        }
        return when (
            val selected = GpsTriggeredZoneSelectionV2.selectForExit(
                candidates,
                openSessionPlaceId,
                runtimeReliable = runtime.reliable,
                openSessionAvailable = openSession != null
            )
        ) {
            is GpsTriggeredZoneSelectionV2.Result.Blocked -> null
            is GpsTriggeredZoneSelectionV2.Result.Selected -> zonesById[selected.zoneId]
        }
    }

    private fun dispatchEntry(context: Context, zone: StoredGpsZone, now: Long) {
        val event = GpsTriggeredZoneSelectionV2.event(
            zoneId = zone.id,
            pointType = GpsTriggeredZoneSelectionV2.pointType(zone),
            transition = GpsTransitionV2.ENTER,
            atMs = now
        )
        val decision = HoraTrackV2.gps.ingest(event)
        val outcome = GpsWorkStateCoordinatorV2.route(context, event, decision)
        if (outcome.action == GpsWorkStateCoordinatorV2.Action.ENTRY_STARTED ||
            outcome.action == GpsWorkStateCoordinatorV2.Action.RETURNED_TO_POSTE
        ) {
            V2SessionPlaceStore.setCurrent(context, zone.id, findZoneLabel(context, zone))
            if (!zone.address.isNullOrBlank()) {
                showArrivalContactNotification(context, zone.address)
            }
        }
    }

    private fun scheduleEntryResolution(
        context: Context,
        prefs: android.content.SharedPreferences,
        zonesById: Map<String, StoredGpsZone>,
        activeZoneIds: Set<String>,
        pendingExitZoneIds: Set<String>
    ) {
        val token = UUID.randomUUID().toString()
        if (!persistZonePresenceState(
                prefs,
                activeZoneIds,
                entryResolutionPending = true,
                pendingExitZoneIds = pendingExitZoneIds,
                entryResolutionToken = token
            )
        ) return
        val app = context.applicationContext
        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!GpsPresenceStateKeysV2.isCurrentEntryResolution(
                        expectedToken = token,
                        entryResolutionPending = prefs.getBoolean(
                            GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING,
                            false
                        ),
                        storedToken = prefs.getString(
                            GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN,
                            null
                        )
                    )
                ) return@postDelayed
                val activeZoneIds = prefs
                    .getStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, emptySet())
                    ?.filterTo(mutableSetOf()) { it in zonesById }
                    ?: mutableSetOf()
                val pendingExitZoneIds = prefs
                    .getStringSet(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES, emptySet())
                    ?.filterTo(mutableSetOf()) { it in zonesById }
                    ?: mutableSetOf()
                if (activeZoneIds.isEmpty()) {
                    persistZonePresenceState(
                        prefs,
                        emptySet(),
                        entryResolutionPending = false,
                        pendingExitZoneIds = emptySet()
                    )
                } else {
                    resolveEntryNow(
                        context = app,
                        prefs = prefs,
                        zoneIds = activeZoneIds.sorted(),
                        zonesById = zonesById,
                        activeZoneIds = activeZoneIds,
                        pendingExitZoneIds = pendingExitZoneIds
                    )
                }
                updateWidgets(app)
            } finally {
                pendingResult.finish()
            }
        }, ENTRY_BATCH_WINDOW_MS)
    }

    private fun resolveEntryNow(
        context: Context,
        prefs: android.content.SharedPreferences,
        zoneIds: List<String>,
        zonesById: Map<String, StoredGpsZone>,
        activeZoneIds: Set<String>,
        pendingExitZoneIds: Set<String>
    ) {
        val resolutionAtMs = System.currentTimeMillis()
        val v2GpsActive = HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)
        if (v2GpsActive && !V2RuntimeReader.current(context, resolutionAtMs).reliable) {
            persistZonePresenceState(
                prefs,
                activeZoneIds,
                entryResolutionPending = true,
                pendingExitZoneIds = pendingExitZoneIds
            )
            return
        }
        val choice = chooseEntryZone(context, prefs, zoneIds, zonesById)
        val employerReady = choice != null &&
            (!v2GpsActive ||
                applyZoneEmployer(context, choice.employerResolution))
        val remainsPending = !employerReady
        val stateSaved = persistZonePresenceState(
            prefs,
            activeZoneIds,
            remainsPending,
            pendingExitZoneIds
        )
        if (stateSaved && employerReady && v2GpsActive) {
            // Une résolution différée est horodatée maintenant, jamais au premier ENTER ambigu.
            dispatchEntry(context, checkNotNull(choice).zone, resolutionAtMs)
        }
    }

    private fun dispatchExit(context: Context, zone: StoredGpsZone, now: Long) {
        val event = GpsTriggeredZoneSelectionV2.event(
            zoneId = zone.id,
            pointType = GpsTriggeredZoneSelectionV2.pointType(zone),
            transition = GpsTransitionV2.EXIT,
            atMs = now
        )
        val decision = HoraTrackV2.gps.ingest(event)
        // La sortie GPS crée une demande de confirmation ; elle ne clôt pas la session ici.
        // Le lieu courant est donc conservé jusqu'à la confirmation ou au prochain pointage.
        GpsWorkStateCoordinatorV2.route(context, event, decision)
    }

    private fun persistZonePresenceState(
        prefs: android.content.SharedPreferences,
        activeZoneIds: Set<String>,
        entryResolutionPending: Boolean,
        pendingExitZoneIds: Set<String>,
        entryResolutionToken: String? = null
    ): Boolean {
        val editor = prefs.edit()
            .putStringSet(GpsPresenceStateKeysV2.ACTIVE_ZONES, activeZoneIds.toSet())
        if (pendingExitZoneIds.isEmpty()) {
            editor.remove(GpsPresenceStateKeysV2.PENDING_EXIT_ZONES)
        } else {
            editor.putStringSet(
                GpsPresenceStateKeysV2.PENDING_EXIT_ZONES,
                pendingExitZoneIds.toSet()
            )
        }
        if (entryResolutionPending) {
            editor.putBoolean(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, true)
            if (entryResolutionToken.isNullOrBlank()) {
                editor.remove(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN)
            } else {
                editor.putString(
                    GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN,
                    entryResolutionToken
                )
            }
        } else {
            editor.remove(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING)
            editor.remove(GpsPresenceStateKeysV2.ENTRY_RESOLUTION_TOKEN)
        }
        return editor.commit()
    }

    private fun clearZonePresenceState(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { editor.remove(it) }
        editor.commit()
    }

    private fun applyZoneEmployer(
        context: Context,
        resolution: GpsZoneEmployerResolutionV2
    ): Boolean = when (resolution) {
        GpsZoneEmployerResolutionV2.KeepCurrent -> true
        is GpsZoneEmployerResolutionV2.UseCompany ->
            V2ProfileStore.setActiveCompanyId(context, resolution.companyId)
        is GpsZoneEmployerResolutionV2.Block -> false
    }

    /**
     * Compatibilité des installations qui stockaient encore le lien adresse -> Entreprise 1/2
     * hors du JSON de zone. Aucune absence de lien ne retombe sur un ancien slot actif : une zone
     * non associée doit conserver l'employeur V2 actuellement choisi, y compris le 3e ou suivant.
     */
    private fun resolveLegacyAddressCompanySlot(
        prefs: android.content.SharedPreferences,
        zone: StoredGpsZone
    ): Int? {
        if (!prefs.contains("address_company_slots")) return null
        val raw = try {
            prefs.getString("address_company_slots", null)
        } catch (_: ClassCastException) {
            return null
        }
        val map = try {
            JSONObject(raw ?: return null)
        } catch (_: Exception) {
            return null
        }

        val candidates = listOfNotNull(zone.address, zone.id)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        candidates.forEach { key ->
            val slot = map.optInt(key, 0)
            if (slot in 1..2) return slot
            val keys = map.keys()
            while (keys.hasNext()) {
                val saved = keys.next()
                if (saved.equals(key, ignoreCase = true)) {
                    val savedSlot = map.optInt(saved, 0)
                    if (savedSlot in 1..2) return savedSlot
                }
            }
        }
        return null
    }

    private fun updateWidgets(context: Context) {
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }

    /** Nom court uniquement : jamais l'adresse complète dans l'état V2 ou le widget. */
    private fun findZoneLabel(context: Context, zone: StoredGpsZone): String? {
        if (!zone.label.isNullOrBlank()) return zone.label
        return zone.address?.let {
            PlaceNames.get(context, it)?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun showArrivalContactNotification(context: Context, address: String) {
        val prefs = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val contact = runCatching {
            JSONObject(prefs.getString("arrival_contacts", "{}") ?: "{}").optJSONObject(address)
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
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Tu viens d'arriver à $placeName. Appuie ici pour prévenir $contactName par SMS.")
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(address.hashCode(), notification)
    }
}
