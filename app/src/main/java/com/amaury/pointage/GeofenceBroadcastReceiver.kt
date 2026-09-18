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
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2SessionPlaceStore
import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
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
            Geofence.GEOFENCE_TRANSITION_ENTER -> candidateIds.forEach {
                SmartSetupManager.onCandidateEnter(context, it)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> candidateIds.forEach {
                SmartSetupManager.onCandidateExit(context, it)
            }
        }

        if (regularIds.isEmpty()) return

        val stored = readPersistedGpsZones(prefs)
        val canonicalZones = (stored as? GpsZonesReadResult.Valid)?.zones
        if (canonicalZones == null) {
            // Absent/corrompu != vide. Aucun ancien geofence de la plateforme ne doit
            // pouvoir fabriquer un événement métier à partir d'un état non prouvé.
            prefs.edit().remove("active_zones").apply()
            GeofenceManager.removeRegisteredGeofences(context)
            return
        }

        val zonesById = canonicalZones.associateBy { it.id }
        if (regularIds.any { it !in zonesById }) {
            // Un requestId inconnu est un geofence Android périmé, pas une zone de travail.
            // On refuse l'événement et on resynchronise la plateforme avec la configuration
            // canonique actuelle afin que le stale geofence ne puisse plus se représenter.
            prefs.edit().remove("active_zones").apply()
            if (canonicalZones.isEmpty()) {
                GeofenceManager.removeRegisteredGeofences(context)
            } else {
                GeofenceManager.registerAll(context, canonicalZones.map { it.asWorkZone() })
            }
            return
        }

        val activeZones = prefs.getStringSet("active_zones", emptySet())
            ?.filterTo(mutableSetOf()) { it in zonesById }
            ?: mutableSetOf()

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val wasOutsideAllZones = activeZones.isEmpty()
                activeZones.addAll(regularIds)
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (wasOutsideAllZones && activeZones.isNotEmpty()) {
                    val zone = zonesById[regularIds.first()] ?: return
                    val zoneAddress = zone.address
                    val zoneLabel = findZoneLabel(context, zone)
                    val zoneType = findZoneType(zone)
                    if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)) {
                        // Une association explicite à un employeur doit être certifiable avant
                        // qu'une entrée GPS puisse démarrer. Sinon l'entreprise active précédente
                        // pourrait recevoir silencieusement le pointage d'une autre zone.
                        if (!applyZoneEmployer(context, prefs, zone)) {
                            updateWidgets(context)
                            return
                        }
                        val now = System.currentTimeMillis()
                        val gpsEvent = GpsEventV2(
                            "gps-enter-${zone.id}-$now",
                            now,
                            zone.id,
                            zoneType,
                            GpsTransitionV2.ENTER
                        )
                        val decision = HoraTrackV2.gps.ingest(gpsEvent)
                        val outcome = GpsWorkStateCoordinatorV2.route(context, gpsEvent, decision)
                        if (outcome.action == GpsWorkStateCoordinatorV2.Action.ENTRY_STARTED ||
                            outcome.action == GpsWorkStateCoordinatorV2.Action.RETURNED_TO_POSTE
                        ) {
                            V2SessionPlaceStore.setCurrent(context, zone.id, zoneLabel)
                            updateWidgets(context)
                            if (!zoneAddress.isNullOrBlank()) {
                                showArrivalContactNotification(context, zoneAddress)
                            }
                        } else {
                            updateWidgets(context)
                        }
                    }
                }
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                activeZones.removeAll(regularIds.toSet())
                prefs.edit().putStringSet("active_zones", activeZones).apply()

                if (activeZones.isEmpty()) {
                    val zone = zonesById[regularIds.first()] ?: return
                    val zoneType = findZoneType(zone)
                    if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)) {
                        val now = System.currentTimeMillis()
                        val gpsEvent = GpsEventV2(
                            "gps-exit-${zone.id}-$now",
                            now,
                            zone.id,
                            zoneType,
                            GpsTransitionV2.EXIT
                        )
                        val decision = HoraTrackV2.gps.ingest(gpsEvent)
                        // La sortie GPS crée une demande de confirmation ; elle ne clôt pas la session ici.
                        // Le lieu courant est donc conservé jusqu'à la confirmation ou au prochain pointage.
                        GpsWorkStateCoordinatorV2.route(context, gpsEvent, decision)
                        updateWidgets(context)
                    }
                }
            }
        }
    }

    private fun applyZoneEmployer(
        context: Context,
        prefs: android.content.SharedPreferences,
        zone: StoredGpsZone
    ): Boolean {
        val companies = SalaryCompanyStore.readConfirmed(context)
        val legacySlot = zone.companySlot ?: resolveLegacyAddressCompanySlot(prefs, zone)
        return when (
            val resolution = resolveGpsZoneEmployerV2(
                companyId = zone.companyId,
                legacyCompanySlot = legacySlot,
                companiesReliable = companies.reliable,
                confirmedCompanyIds = companies.companies.map { it.id }
            )
        ) {
            GpsZoneEmployerResolutionV2.KeepCurrent -> true
            is GpsZoneEmployerResolutionV2.UseCompany ->
                V2ProfileStore.setActiveCompanyId(context, resolution.companyId)
            is GpsZoneEmployerResolutionV2.Block -> false
        }
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

    private fun findZoneType(zone: StoredGpsZone): GpsPointTypeV2 {
        val raw = (zone.pointTypeToken ?: zone.id).uppercase()
        return when {
            raw.contains("PARK") -> GpsPointTypeV2.PARKING
            raw.contains("OTHER") || raw.contains("AUTRE") -> GpsPointTypeV2.OTHER
            raw.contains("POSTE") || raw.contains("WORKPLACE") || raw.contains("WORK") -> GpsPointTypeV2.POSTE
            // Un type inconnu ne doit jamais devenir implicitement un poste et ouvrir une session.
            else -> GpsPointTypeV2.OTHER
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
