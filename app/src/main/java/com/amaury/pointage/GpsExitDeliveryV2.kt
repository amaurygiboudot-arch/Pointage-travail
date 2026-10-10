package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.GpsDecisionV2
import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.google.firebase.auth.FirebaseAuth

/** Durable outbox in the same preference file/commit as the final GPS presence transition. */
internal object GpsExitDeliveryV2 {
    const val KEY = "pending_exit_deliveries"
    const val OBSERVATION_CONTEXT_KEY = "pending_exit_observation_context"
    private const val MAX_PENDING = 256

    internal fun accountScope(): String? = runCatching {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) "guest" else user.uid.trim().takeIf(String::isNotBlank)?.let { "uid:$it" }
    }.getOrNull()

    fun observationContext(context: Context): String? {
        val scope = accountScope() ?: return null
        val runtime = V2RuntimeReader.current(context)
        val session = runtime.snapshot.session ?: return null
        val arrival = session.realArrivalMs ?: return null
        if (!runtime.reliable || session.status != SessionStatusV2.OPEN || session.realExitMs != null) return null
        val gps = context.applicationContext.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val stored = readPersistedGpsZones(gps) as? GpsZonesReadResult.Valid ?: return null
        if (!gps.getBoolean("enabled", false)) return null
        return GpsExitDeliveryRecordV2.encodeObservationContext(session.id, arrival, scope,
            storedGpsAutomaticFingerprintV2(true, stored))
    }

    fun prepare(context: Context, event: GpsEventV2, observationContext: String?): GpsExitDeliveryRecordV2? {
        if (!HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)) return null
        if (observationContext == null || event.transition != GpsTransitionV2.EXIT) return null
        val scope = accountScope() ?: return null
        val runtime = V2RuntimeReader.current(context, event.atMs)
        val session = runtime.snapshot.session ?: return null
        val arrival = session.realArrivalMs ?: return null
        if (!runtime.reliable || session.status != SessionStatusV2.OPEN ||
            session.realExitMs != null || event.atMs < arrival) return null
        val gps = context.applicationContext.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val stored = readPersistedGpsZones(gps) as? GpsZonesReadResult.Valid ?: return null
        if (!gps.getBoolean("enabled", false) || stored.zones.none { it.id == event.placeId }) return null
        return GpsExitDeliveryRecordV2(event, session.id, arrival, scope,
            storedGpsAutomaticFingerprintV2(true, stored)).takeIf { it.observationContext() == observationContext }
    }

    internal fun matchesContext(context: Context, delivery: GpsExitDeliveryRecordV2): Boolean? {
        val scope = accountScope() ?: return null
        val gps = context.applicationContext.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val stored = readPersistedGpsZones(gps)
        if (stored !is GpsZonesReadResult.Valid) return null
        return scope == delivery.accountScope &&
            storedGpsAutomaticFingerprintV2(gps.getBoolean("enabled", false), stored) == delivery.configurationFingerprint
    }

    internal fun hasProvenReturn(context: Context, delivery: GpsExitDeliveryRecordV2): Boolean {
        val gps = context.applicationContext.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val qualification = GpsReturnObservationV2.Qualification.decode(runCatching {
            gps.getString(GpsReturnObservationV2.QUALIFICATION_KEY, null)
        }.getOrNull()) ?: return false
        val now = System.currentTimeMillis()
        if (now < qualification.availableAtMs) return false
        val raw = runCatching { gps.getStringSet(GpsReturnObservationV2.KEY, emptySet()) }.getOrNull()
            ?: return false
        val zones = (readPersistedGpsZones(gps) as? GpsZonesReadResult.Valid)?.zones?.associateBy { it.id }
            ?: return false
        return GpsReturnObservationV2.provesReturn(
            GpsReturnObservationV2.decode(raw).filterKeys { it == qualification.zoneId }, delivery,
            observationContext(context), now) { exited, returned, exitAt, returnAt ->
            GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
                zones[exited], zones[returned], exitAt, returnAt
            )
        }
    }

    /** Caller supplies the already prepared presence mutation; nothing is published before commit. */
    @Synchronized
    fun commitPresence(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        delivery: GpsExitDeliveryRecordV2?
    ): Boolean {
        if (delivery != null) {
            val existing = readRaw(prefs) ?: return false
            val encoded = delivery.encode()
            if (existing.size >= MAX_PENDING && encoded !in existing) return false
            editor.putStringSet(KEY, existing + encoded)
        }
        return editor.commit()
    }

    /** Called on the existing prompt lifecycle, so recovery requires no new GPS callback/service. */
    @Synchronized
    fun replay(context: Context, allowReturnAcknowledgement: Boolean = true) {
        if (!HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.GPS)) return
        val app = context.applicationContext
        val gps = app.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val raw = readRaw(gps) ?: return
        if (raw.isEmpty()) return
        val scope = accountScope() ?: return // Auth unavailable is unknown, not a guest account.
        val stored = readPersistedGpsZones(gps)
        if (stored !is GpsZonesReadResult.Valid) return
        val fingerprint = storedGpsAutomaticFingerprintV2(gps.getBoolean("enabled", false), stored)
        val ordered = raw.sortedWith(compareBy(
            { GpsExitDeliveryRecordV2.decode(it)?.event?.atMs ?: Long.MAX_VALUE },
            { GpsExitDeliveryRecordV2.decode(it)?.event?.id.orEmpty() }
        ))
        for (encoded in ordered) {
            val delivery = GpsExitDeliveryRecordV2.decode(encoded) ?: continue
            if (scope != delivery.accountScope || fingerprint != delivery.configurationFingerprint) {
                removeAcknowledged(gps, encoded)
                continue
            }
            V2RuntimeStore.withTransaction {
                val runtime = V2RuntimeReader.current(app)
                if (!runtime.reliable) return@withTransaction
                val session = runtime.snapshot.session
                if (session?.status != SessionStatusV2.OPEN || session.realExitMs != null ||
                    !delivery.matchesSession(session.id, session.realArrivalMs)) {
                    removeAcknowledged(gps, encoded)
                    return@withTransaction
                }
                if (allowReturnAcknowledgement && hasProvenReturn(app, delivery)) {
                    if (GpsWorkStateCoordinatorV2.acknowledgeReturnedDelivery(app, delivery))
                        removeAcknowledged(gps, encoded)
                    return@withTransaction
                }
                // The receipt survives user confirmation/cancellation and makes replay idempotent
                // even when the previous outbox cleanup failed after a successful delivery.
                when (GpsWorkStateCoordinatorV2.confirmDurableDeliveryReceipt(app, delivery)) {
                    GpsWorkStateCoordinatorV2.DeliveryReceiptState.ACKNOWLEDGED -> {
                        removeAcknowledged(gps, encoded)
                        return@withTransaction
                    }
                    GpsWorkStateCoordinatorV2.DeliveryReceiptState.RETRY -> return@withTransaction
                    GpsWorkStateCoordinatorV2.DeliveryReceiptState.MISSING -> Unit
                }
                val outcome = GpsWorkStateCoordinatorV2.route(
                    app, delivery.event,
                    GpsDecisionV2(true, false, true, "Reprise d'une observation GPS persistée"),
                    durableDelivery = delivery
                )
                // Do not ingest again: the RAM anti-bounce cache cannot acknowledge a disk write.
                if (outcome.durableAcknowledgement) removeAcknowledged(gps, encoded)
            }
        }
    }

    private fun readRaw(prefs: SharedPreferences): Set<String>? = runCatching {
        prefs.getStringSet(KEY, emptySet())?.toSet()
    }.getOrNull()

    private fun removeAcknowledged(prefs: SharedPreferences, encoded: String): Boolean {
        val current = readRaw(prefs) ?: return false
        return prefs.edit().putStringSet(KEY, current - encoded).commit()
    }
}
