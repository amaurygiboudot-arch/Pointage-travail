package com.amaury.pointage.v2.engine

import com.amaury.pointage.DailyDiagnosticStoreV2
import com.amaury.pointage.DailyDiagnosticKindV2
import com.amaury.pointage.DailyDiagnosticModelV2
import android.content.Context
import android.content.SharedPreferences
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.GpsExitDeliveryV2
import com.amaury.pointage.GpsExitDeliveryRecordV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.UUID

/**
 * Couche de décision distincte du capteur GPS.
 * GpsEngineV2 ne fait qu'accepter/filtrer les événements. Ce coordinateur
 * décide ensuite des conséquences métier et conserve les événements ambigus
 * pour confirmation utilisateur.
 */
object GpsWorkStateCoordinatorV2 {
    private const val PREFS = "horatrack_v2_gps_state"
    private const val KEY_EVIDENCE_VERSION = "departure_evidence_version"
    private const val KEY_PENDING_ID = "pending_id"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_PENDING_PLACE = "pending_place"
    private const val KEY_PENDING_TYPE = "pending_type"
    private const val KEY_PENDING_TRANSITION = "pending_transition"
    private const val KEY_PROMPTED_ID = "prompted_id"
    private const val KEY_PROMPTED_PROCESS = "prompted_process"
    private val processToken = UUID.randomUUID().toString()
    private const val KEY_PENDING_DELIVERY = "pending_delivery_binding"
    private const val KEY_PENDING_ACCOUNT_SCOPE = "pending_account_scope"
    private const val KEY_DELIVERY_RECEIPTS = "delivery_receipts"
    private const val KEY_RECEIPT_CONTEXT = "delivery_receipt_context"
    private const val RETURN_WINDOW_MS = 2L * 60_000L

    private const val MIGRATED = "account_storage_initialized"
    private val unconfirmedMigrations = mutableSetOf<String>()

    /** Copy only explicitly owned legacy state; keep the original as recovery evidence.
     * The marker survives configuration resets so old questions cannot be resurrected.
     */
    @Synchronized
    private fun preferences(context: Context): SharedPreferences {
        val scope = V2RuntimeAccountScope.operationScope()
        val target = V2RuntimeAccountScope.preferences(context, PREFS)
        if (scope == null) return target
        if (target.getBoolean(MIGRATED, false) && scope !in unconfirmedMigrations) return target
        val editor = target.edit()
        if (!target.getBoolean(MIGRATED, false) && target.all.isEmpty()) {
            val legacy = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val values = legacy.all
            // Unbound or foreign-account questions are never adopted by the current account.
            val binding = (values[KEY_PENDING_DELIVERY] as? String)?.let(GpsExitDeliveryRecordV2::decode)
            val pendingOwned = values[KEY_PENDING_ACCOUNT_SCOPE] == scope &&
                (!values.containsKey(KEY_PENDING_DELIVERY) || binding?.accountScope == scope)
            if (pendingOwned) {
                for (key in listOf(KEY_EVIDENCE_VERSION, KEY_PENDING_ID, KEY_PENDING_AT, KEY_PENDING_PLACE,
                    KEY_PENDING_TYPE, KEY_PENDING_TRANSITION, KEY_PENDING_ACCOUNT_SCOPE,
                    KEY_PENDING_DELIVERY)) {
                    when (val value = values[key]) {
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Int -> editor.putInt(key, value)
                        is Set<*> -> if (value.all { it is String })
                            editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
            // Receipts can outlive a question (including a return acknowledged before display).
            // Their serialized observation context proves ownership independently of pending fields.
            val receiptContext = values[KEY_RECEIPT_CONTEXT] as? String
            val receiptOwner = receiptContext?.let { raw -> runCatching {
                val parts = raw.split('|').map { java.net.URLDecoder.decode(it, "UTF-8") }
                require(parts.size == 5 && parts[0] == "1" && parts[1].isNotBlank() &&
                    parts[2].toLong() > 0 && parts[4].isNotBlank())
                require(GpsExitDeliveryRecordV2.encodeObservationContext(parts[1], parts[2].toLong(),
                    parts[3], parts[4]) == raw)
                parts[3]
            }.getOrNull() }
            val receipts = values[KEY_DELIVERY_RECEIPTS] as? Set<*>
            if (receiptOwner == scope && (!values.containsKey(KEY_PENDING_ID) || pendingOwned) &&
                receipts != null && receipts.all { it is String }) {
                editor.putString(KEY_RECEIPT_CONTEXT, receiptContext)
                    .putStringSet(KEY_DELIVERY_RECEIPTS, receipts.filterIsInstance<String>().toSet())
            }
        }
        // Failed commits may modify the RAM cache: reconfirm it before exposing migrated receipts.
        if (!editor.putBoolean(MIGRATED, true).commit()) {
            unconfirmedMigrations.add(scope)
            return V2RuntimeAccountScope.guardedPreferences(target) { false }
        }
        unconfirmedMigrations.remove(scope)
        return target
    }

    enum class Action {
        IGNORED,
        ENTRY_STARTED,
        RETURNED_TO_POSTE,
        DEPARTURE_OBSERVED,
        AMBIGUOUS_PENDING_CONFIRMATION,
        NO_CHANGE
    }

    data class Outcome(
        val action: Action,
        val requiresConfirmation: Boolean,
        val reason: String,
        val durableAcknowledgement: Boolean = false
    )

    data class Pending(
        val id: String,
        val atMs: Long,
        val placeId: String,
        val pointType: GpsPointTypeV2,
        val transition: GpsTransitionV2,
        val kind: Kind
    ) {
        enum class Kind { EXIT_WORKSITE, AMBIGUOUS }
    }

    internal fun route(
        context: Context,
        event: GpsEventV2,
        decision: GpsDecisionV2,
        verifiedOverlappingWorksiteReturn: Boolean = false,
        durableDelivery: GpsExitDeliveryRecordV2? = null
    ): Outcome = V2RuntimeAccountScope.withScope {
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_EVENT, event.atMs, "${event.pointType}_${event.transition}", zoneId = event.placeId)
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_DECISION, event.atMs,
            if (decision.duplicate) "DUPLICATE" else if (decision.accepted) "ACCEPTED" else "REJECTED", zoneId = event.placeId)
        val outcome = routeInternal(context, event, decision, verifiedOverlappingWorksiteReturn, durableDelivery)
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_OUTCOME, event.atMs, outcome.action.name, zoneId = event.placeId)
        if (outcome.action == Action.NO_CHANGE) DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_REASON, event.atMs, DailyDiagnosticModelV2.reasonCode(outcome.reason), zoneId = event.placeId)
        outcome
    }

    private fun routeInternal(
        context: Context,
        event: GpsEventV2,
        decision: GpsDecisionV2,
        verifiedOverlappingWorksiteReturn: Boolean = false,
        durableDelivery: GpsExitDeliveryRecordV2? = null
    ): Outcome {
        V2RuntimeAccountScope.withScope {
            if (!decision.accepted || decision.duplicate) {
                return Outcome(Action.IGNORED, false, decision.reason)
            }

            val runtime = V2RuntimeReader.current(context, event.atMs)
            if (!canRouteWithRuntime(runtime.reliable)) {
                return Outcome(
                    Action.NO_CHANGE,
                    false,
                    "Runtime V2 non fiable : transition GPS bloquée"
                )
            }
            val current = runtime.snapshot.session
            if (durableDelivery != null && (durableDelivery.event != event ||
                    GpsExitDeliveryV2.matchesContext(context, durableDelivery) != true ||
                    !durableDelivery.matchesSession(current?.id, current?.realArrivalMs))) {
                return Outcome(Action.NO_CHANGE, false, "Observation GPS liée à un autre contexte")
            }
            var currentPending = pending(context)
            val binding = pendingBindingMatches(context, current)
            if (binding == null) return Outcome(Action.NO_CHANGE, false, "Contexte GPS à vérifier")
            if (binding == false || shouldDiscardPending(currentPending, current)) {
                if (!clearPending(context)) return Outcome(Action.NO_CHANGE, false, "Effacement GPS non enregistré")
                currentPending = null
            }

            if (event.pointType == GpsPointTypeV2.POSTE && event.transition == GpsTransitionV2.ENTER) {
                if (canApplyReturnToPoste(currentPending, event, current) ||
                    canApplyVerifiedOverlappingReturn(
                        currentPending, event, current, verifiedOverlappingWorksiteReturn
                    )) {
                    if (!clearPending(context)) return Outcome(Action.NO_CHANGE, false, "Retour GPS non enregistré")
                    return Outcome(
                        Action.RETURNED_TO_POSTE,
                        false,
                        "Retour au poste : ancienne demande de fin de journée annulée"
                    )
                }

                if (currentPending?.kind == Pending.Kind.EXIT_WORKSITE) {
                    if (!clearPending(context)) return Outcome(Action.NO_CHANGE, false, "Retour GPS non enregistr?")
                    currentPending = null
                }
                if (current == null || current.realExitMs != null) {
                    val started = V2RuntimeStore.entry(context, event.atMs)
                    if (shouldDiscardPending(currentPending, current, entryStarted = started)) {
                        clearPending(context)
                    }
                    return Outcome(
                        if (started) Action.ENTRY_STARTED else Action.NO_CHANGE,
                        false,
                        if (started) "Arrivée poste confirmée par GPS" else "Session déjà ouverte"
                    )
                }
                return Outcome(Action.NO_CHANGE, false, "Session V2 déjà ouverte")
            }

            if (event.pointType == GpsPointTypeV2.POSTE && event.transition == GpsTransitionV2.EXIT) {
                if (current == null || current.realExitMs != null) {
                    return Outcome(Action.NO_CHANGE, false, "Aucune session V2 ouverte à terminer")
                }
                if (currentPending?.id != event.id && !canQueuePending(currentPending, Pending.Kind.EXIT_WORKSITE)) {
                    return Outcome(
                        Action.NO_CHANGE,
                        true,
                        "Une transition GPS attend déjà une confirmation"
                    )
                }
                val saved = savePending(context, event, Pending.Kind.EXIT_WORKSITE, durableDelivery)
                return Outcome(if (saved) Action.DEPARTURE_OBSERVED else Action.NO_CHANGE,
                    false, if (saved) "Hors site / \u00e0 v\u00e9rifier" else "Sortie GPS non enregistr\u00e9e",
                    durableAcknowledgement = saved && durableDelivery != null)
            }

            if (!canQueueAmbiguous(current, event.transition)) {
                return Outcome(
                    Action.NO_CHANGE,
                    false,
                    "État de travail incompatible avec cette transition GPS ambiguë"
                )
            }
            if (currentPending?.id != event.id && !canQueuePending(currentPending, Pending.Kind.AMBIGUOUS)) {
                return Outcome(
                    Action.NO_CHANGE,
                    true,
                    "Une transition GPS attend déjà une confirmation"
                )
            }
            val saved = savePending(context, event, Pending.Kind.AMBIGUOUS, durableDelivery)
            return Outcome(if (saved) Action.AMBIGUOUS_PENDING_CONFIRMATION else Action.NO_CHANGE,
                saved, if (saved) "Transition GPS ambiguë à qualifier" else "Transition GPS non enregistrée",
                durableAcknowledgement = saved && durableDelivery != null)
        }
    }

    internal fun canQueueAmbiguous(
        session: WorkSessionV2?,
        transition: GpsTransitionV2
    ): Boolean {
        if (session?.status != SessionStatusV2.OPEN || session.realExitMs != null) return false
        val hasOpenPause = session.pauses.any { it.endMs == null }
        return when (transition) {
            GpsTransitionV2.ENTER -> !hasOpenPause
            GpsTransitionV2.EXIT -> hasOpenPause
        }
    }

    internal fun canRouteWithRuntime(runtimeReliable: Boolean): Boolean = runtimeReliable

    /** GPS arrival is acknowledged only after a persisted entry/return, or after
     * verifying that the *same employer* already has a durable open session.
     * No rejected callback can silently clear the entry recovery marker.
     */
    internal fun entryResolutionAcknowledged(
        employerReady: Boolean,
        gpsEnabled: Boolean,
        action: Action?,
        existingSessionVerified: Boolean = false
    ): Boolean = employerReady && (!gpsEnabled ||
        action == Action.ENTRY_STARTED ||
        action == Action.RETURNED_TO_POSTE ||
        (action == Action.NO_CHANGE && existingSessionVerified))

    internal fun shouldDiscardPending(
        pending: Pending?,
        current: WorkSessionV2?,
        entryStarted: Boolean = false
    ): Boolean {
        if (pending == null) return false
        if (entryStarted) return true
        // A GPS exit prompt must never outlive the session that was closed manually.
        // Keep unknown/corrupt runtime guarded by the caller's reliable-source check.
        if (current?.status != SessionStatusV2.OPEN || current.realExitMs != null) return true
        val arrival = current.realArrivalMs ?: return true
        return pending.atMs < arrival
    }

    internal fun canQueuePending(existing: Pending?, incomingKind: Pending.Kind): Boolean =
        existing == null

    internal fun isQuickReturnToPoste(pending: Pending?, event: GpsEventV2): Boolean {
        if (pending?.kind != Pending.Kind.EXIT_WORKSITE) return false
        if (event.pointType != GpsPointTypeV2.POSTE || event.transition != GpsTransitionV2.ENTER) return false
        if (pending.placeId != event.placeId || event.atMs < pending.atMs) return false
        return event.atMs - pending.atMs <= RETURN_WINDOW_MS
    }

    internal fun canApplyReturnToPoste(
        pending: Pending?,
        event: GpsEventV2,
        current: WorkSessionV2?
    ): Boolean {
        if (current?.status != SessionStatusV2.OPEN || current.realExitMs != null) return false
        val arrival = current.realArrivalMs ?: return false
        if (pending == null || pending.atMs < arrival) return false
        if (pending.kind != Pending.Kind.EXIT_WORKSITE) return false
        if (event.pointType != GpsPointTypeV2.POSTE || event.transition != GpsTransitionV2.ENTER) return false
        if (pending.placeId != event.placeId || event.atMs < pending.atMs) return false
        return true
    }

    /**
     * A distinct GPS zone cancels an earlier pending EXIT only when the geofence
     * caller has proved same employer+place, overlapping WORK circles and a
     * <=2-minute transition. Never infer continuity from a common job title.
     */
    internal fun canApplyVerifiedOverlappingReturn(
        pending: Pending?,
        event: GpsEventV2,
        current: WorkSessionV2?,
        verifiedOverlap: Boolean
    ): Boolean {
        if (!verifiedOverlap || pending?.kind != Pending.Kind.EXIT_WORKSITE ||
            current?.status != SessionStatusV2.OPEN || current.realExitMs != null ||
            event.pointType != GpsPointTypeV2.POSTE ||
            event.transition != GpsTransitionV2.ENTER ||
            pending.placeId == event.placeId ||
            pending.atMs < (current.realArrivalMs ?: Long.MAX_VALUE) ||
            event.atMs < pending.atMs) return false
        return event.atMs - pending.atMs <= 120_000L
    }

    internal fun canApplyQuickReturn(
        pending: Pending?,
        event: GpsEventV2,
        current: WorkSessionV2?
    ): Boolean {
        if (!canApplyReturnToPoste(pending, event, current)) return false
        val pendingAt = pending?.atMs ?: return false
        return event.atMs - pendingAt <= RETURN_WINDOW_MS
    }

    fun pending(context: Context): Pending? {
        val prefs = preferences(context)
        val id = prefs.getString(KEY_PENDING_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs.getLong(KEY_PENDING_AT, 0L).takeIf { it > 0L } ?: return null
        val place = prefs.getString(KEY_PENDING_PLACE, null)?.takeIf { it.isNotBlank() } ?: return null
        val raw = prefs.getString(KEY_PENDING_TYPE, null) ?: return null
        val parts = raw.split(':')
        val pointType = runCatching { GpsPointTypeV2.valueOf(parts.first()) }.getOrNull() ?: return null
        val kind = runCatching { Pending.Kind.valueOf(parts.getOrNull(1) ?: "") }.getOrNull() ?: return null
        // Old questions are not new observations; never close retroactively after an update.
        if (kind == Pending.Kind.EXIT_WORKSITE && prefs.all[KEY_EVIDENCE_VERSION] != 1) {
            clearPending(context)
            return null
        }
        val transition = runCatching {
            GpsTransitionV2.valueOf(prefs.getString(KEY_PENDING_TRANSITION, null).orEmpty())
        }.getOrNull() ?: return null
        return Pending(id, at, place, pointType, transition, kind)
    }

    /**
     * A pending geofence exit is only actionable for a verified OPEN session.
     * Closing a day through the manual button must immediately revoke the stale
     * GPS question, rather than inviting another end-of-day confirmation.
     */
    fun pendingForOpenSession(context: Context): Pending? {
        V2RuntimeAccountScope.withScope {
            GpsExitDeliveryV2.replay(context)
            val found = pending(context) ?: return null
            val runtime = V2RuntimeReader.current(context)
            if (!runtime.reliable) return null
            val binding = pendingBindingMatches(context, runtime.snapshot.session)
            if (binding == null) return null
            if (binding == false || shouldDiscardPending(found, runtime.snapshot.session)) {
                clearPending(context)
                return null
            }
            val delivery = pendingDelivery(context)
            if (delivery != null && GpsExitDeliveryV2.hasProvenReturn(context, delivery)) {
                acknowledgeReturnedDelivery(context, delivery)
                return null
            }
            // Keep the durable question, but neither show nor confirm an old departure while
            // an observed return is waiting for complete batch arbitration (including restart).
            if (delivery != null && GpsExitDeliveryV2.hasUnresolvedReturn(context, delivery)) return null
            return found
        }
    }

    /** One canonical observation, with existing durable return/arbitration guards. */
    internal fun eligibleDepartureAt(context: Context, nowMs: Long, automatic: Boolean = false): Long? {
        val found = pendingForOpenSession(context)?.takeIf { it.kind == Pending.Kind.EXIT_WORKSITE } ?: return null
        val delivery = pendingDelivery(context) ?: return null
        if (confirmDurableDeliveryReceipt(context, delivery) != DeliveryReceiptState.ACKNOWLEDGED) return null
        val runtime = V2RuntimeReader.current(context, nowMs)
        val session = runtime.snapshot.session ?: return null
        val gps = context.applicationContext.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val zones = (com.amaury.pointage.readPersistedGpsZones(gps) as? com.amaury.pointage.GpsZonesReadResult.Valid)?.zones ?: return null
        val zone = zones.singleOrNull { it.id == found.placeId } ?: return null
        val employer = session.employerId?.takeIf { it.isNotBlank() } ?: return null
        val episodeConsistent = delivery.episodeZoneIds.all { id ->
            val other = zones.singleOrNull { it.id == id } ?: return@all false
            other.companyId == employer && (id == zone.id ||
                com.amaury.pointage.GpsTriggeredZoneSelectionV2.pointType(other) == GpsPointTypeV2.PARKING ||
                com.amaury.pointage.GpsOverlappingWorkZoneContinuityV2.hasEquivalentWorkGeometry(zone, other))
        }
        // A missing stable company binding is unknown, not permission to infer an employer.
        val active = runCatching { gps.getStringSet(com.amaury.pointage.GpsPresenceStateKeysV2.ACTIVE_ZONES, null) }.getOrNull() ?: return null
        val facts = DeparturePolicyV2.Facts(found.atMs, session.realArrivalMs ?: return null, nowMs,
            runtime.reliable && episodeConsistent && zone.companyId == employer && pendingBindingMatches(context, session) == true,
            session.status == SessionStatusV2.OPEN && session.realExitMs == null,
            active.isNotEmpty(),
            gps.getBoolean(com.amaury.pointage.GpsPresenceStateKeysV2.ENTRY_RESOLUTION_PENDING, false) ||
                session.pauses.any { it.endMs == null || it.paid == null || (it.endMs ?: Long.MAX_VALUE) > found.atMs } ||
                session.travels.isNotEmpty() || session.workSegments.any { it.endMs == null || (it.endMs ?: Long.MAX_VALUE) > found.atMs },
            lastObservedAtMs = delivery.lastObservedAtMs)
        val eligible = if (automatic) DeparturePolicyV2.automatic(facts,
            com.amaury.pointage.v2.V2DeparturePolicyStore.enabled(context, employer), V2RuntimeStore.expectedEnd(context))
            else DeparturePolicyV2.eligible(facts)
        return found.atMs.takeIf { eligible }
    }

    internal fun resolveAutomaticDeparture(context: Context, nowMs: Long = System.currentTimeMillis(), movementResolved: Boolean = false): Boolean =
        V2RuntimeStore.withTransaction {
            // No complete canonical travel proof is wired on Android yet. Production callers
            // must remain fail-closed until such a source explicitly supplies this fact.
            if (!movementResolved) return@withTransaction false
            val at = eligibleDepartureAt(context, nowMs, automatic = true) ?: return@withTransaction false
            val closed = V2RuntimeStore.exit(context, at, origin = com.amaury.pointage.ClosureOriginV2.AUTOMATIC, departureObservedAtMs = at)
            if (closed) clearPending(context)
            closed
        }

    fun shouldPrompt(context: Context, pending: Pending): Boolean {
        if (pending.kind == Pending.Kind.EXIT_WORKSITE) return false
        val prefs = preferences(context)
        return prefs.getString(KEY_PROMPTED_ID, null) != pending.id ||
            prefs.getString(KEY_PROMPTED_PROCESS, null) != processToken
    }

    fun markPromptShown(context: Context, pending: Pending) {
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.PROMPT_SHOWN, pending.atMs)
        preferences(context)
            .edit().putString(KEY_PROMPTED_ID, pending.id)
            .putString(KEY_PROMPTED_PROCESS, processToken).apply()
    }

    /** Une fermeture sans réponse ne transforme pas l'événement en décision. */
    fun allowPromptAgain(context: Context, pending: Pending) {
        val prefs = preferences(context)
        if (prefs.getString(KEY_PENDING_ID, null) == pending.id) {
            prefs.edit().remove(KEY_PROMPTED_ID).remove(KEY_PROMPTED_PROCESS).apply()
        }
    }

    fun confirmExit(
        context: Context,
        expectedPendingId: String,
        expectedEndMs: Long? = null
    ): Boolean {
        return V2RuntimeStore.withTransaction {
            val found = pendingForOpenSession(context) ?: return@withTransaction false
            if (!matchesPendingId(found, expectedPendingId)) return@withTransaction false
            val at = eligibleDepartureAt(context, System.currentTimeMillis()) ?: return@withTransaction false
            val closed = V2RuntimeStore.exit(context, at, origin = com.amaury.pointage.ClosureOriginV2.MANUAL, departureObservedAtMs = at)
            if (closed) clearPending(context)
            closed
        }
    }

    /**
     * Confirme un début de pause détecté par GPS.
     *
     * Le statut payé/non payé est obligatoire : le GPS ne peut jamais l'inférer.
     * L'événement reste en attente si l'écriture runtime échoue.
     */
    fun confirmPauseStart(context: Context, expectedPendingId: String, paid: Boolean): Boolean {
        V2RuntimeAccountScope.withScope {
            DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.CONFIRM_PAUSE_START)
            val pending = pendingForOpenSession(context)
                ?.takeIf { matchesPendingId(it, expectedPendingId) }
                ?: return false
            if (pending.kind != Pending.Kind.AMBIGUOUS || pending.transition != GpsTransitionV2.ENTER) {
                return false
            }
            val session = V2RuntimeStore.snapshot(context, pending.atMs).session ?: return false
            if (session.realExitMs != null || session.pauses.any { it.endMs == null }) return false

            val ok = V2RuntimeStore.togglePause(
                context = context,
                nowMs = pending.atMs,
                source = EventSourceV2.GPS,
                paid = paid
            )
            if (ok) clearPending(context)
            return ok
        }
    }

    /**
     * Confirme une reprise après une pause ouverte.
     *
     * Le statut payé mémorisé à l'ouverture reste la source canonique ; aucune nouvelle
     * classification n'est inventée à la fermeture.
     */
    fun confirmPauseEnd(context: Context, expectedPendingId: String): Boolean {
        V2RuntimeAccountScope.withScope {
            DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.CONFIRM_PAUSE_END)
            val pending = pendingForOpenSession(context)
                ?.takeIf { matchesPendingId(it, expectedPendingId) }
                ?: return false
            if (pending.kind != Pending.Kind.AMBIGUOUS || pending.transition != GpsTransitionV2.EXIT) {
                return false
            }
            val session = V2RuntimeStore.snapshot(context, pending.atMs).session ?: return false
            if (session.realExitMs != null || session.pauses.none { it.endMs == null }) return false

            val ok = V2RuntimeStore.togglePause(
                context = context,
                nowMs = pending.atMs,
                source = EventSourceV2.GPS
            )
            if (ok) clearPending(context)
            return ok
        }
    }

    fun cancelPending(context: Context, expectedPendingId: String): Boolean {
        V2RuntimeAccountScope.withScope {
            DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.CONFIRM_CANCEL)
            val current = pendingForOpenSession(context) ?: return false
            if (!matchesPendingId(current, expectedPendingId)) return false
            return clearPending(context)
        }
    }

    internal fun matchesPendingId(pending: Pending?, expectedPendingId: String): Boolean =
        pending != null && expectedPendingId.isNotBlank() && pending.id == expectedPendingId

    /**
     * Une restauration ou une modification des zones invalide toute confirmation liée à
     * l'ancienne configuration. L'effacement synchrone précède la réinscription des geofences.
     */
    fun clearForGpsConfigurationChange(context: Context): Boolean =
        preferences(context)
            .edit()
            .clear()
            .putBoolean(MIGRATED, true)
            .commit()

    private fun savePending(context: Context, event: GpsEventV2, kind: Pending.Kind,
        delivery: GpsExitDeliveryRecordV2? = null): Boolean {
        val prefs = preferences(context)
        val accountScope = GpsExitDeliveryV2.accountScope() ?: return false
        val editor = prefs.edit()
            .putInt(KEY_EVIDENCE_VERSION, 1)
            .putString(KEY_PENDING_ID, event.id)
            .putLong(KEY_PENDING_AT, event.atMs)
            .putString(KEY_PENDING_PLACE, event.placeId)
            .putString(KEY_PENDING_TYPE, "${event.pointType.name}:${kind.name}")
            .putString(KEY_PENDING_TRANSITION, event.transition.name)
            .putString(KEY_PENDING_ACCOUNT_SCOPE, accountScope)
        if (prefs.getString(KEY_PENDING_ID, null) != event.id)
            editor.remove(KEY_PROMPTED_ID).remove(KEY_PROMPTED_PROCESS)
        if (delivery == null) editor.remove(KEY_PENDING_DELIVERY)
        else {
            val receipts = if (prefs.getString(KEY_RECEIPT_CONTEXT, null) == delivery.observationContext()) {
                runCatching { prefs.getStringSet(KEY_DELIVERY_RECEIPTS, emptySet()) }.getOrNull() ?: return false
            } else emptySet()
            editor.putString(KEY_PENDING_DELIVERY, delivery.encode())
                .putString(KEY_RECEIPT_CONTEXT, delivery.observationContext())
                .putStringSet(KEY_DELIVERY_RECEIPTS, receipts + delivery.receiptId())
        }
        return editor.commit()
    }

    internal enum class DeliveryReceiptState { MISSING, ACKNOWLEDGED, RETRY }

    internal fun acknowledgeReturnedDelivery(context: Context, delivery: GpsExitDeliveryRecordV2): Boolean {
        val prefs = preferences(context)
        val receipts = if (prefs.getString(KEY_RECEIPT_CONTEXT, null) == delivery.observationContext()) {
            runCatching { prefs.getStringSet(KEY_DELIVERY_RECEIPTS, emptySet()) }.getOrNull() ?: return false
        } else emptySet()
        val editor = prefs.edit().putString(KEY_RECEIPT_CONTEXT, delivery.observationContext())
            .putStringSet(KEY_DELIVERY_RECEIPTS, receipts + delivery.receiptId())
        if (prefs.getString(KEY_PENDING_ID, null) == delivery.event.id) removePendingFields(editor)
        return editor.commit()
    }

    internal fun confirmDurableDeliveryReceipt(context: Context, delivery: GpsExitDeliveryRecordV2): DeliveryReceiptState {
        val prefs = preferences(context)
        if (prefs.getString(KEY_RECEIPT_CONTEXT, null) != delivery.observationContext()) return DeliveryReceiptState.MISSING
        val receipts = runCatching { prefs.getStringSet(KEY_DELIVERY_RECEIPTS, emptySet()) }.getOrNull()
            ?: return DeliveryReceiptState.RETRY
        if (delivery.receiptId() !in receipts) return DeliveryReceiptState.MISSING
        // commit=false may still mutate SharedPreferences' RAM cache. Recommit before ACK.
        return if (prefs.edit().putStringSet(KEY_DELIVERY_RECEIPTS, receipts.toSet()).commit())
            DeliveryReceiptState.ACKNOWLEDGED else DeliveryReceiptState.RETRY
    }

    private fun pendingBindingMatches(context: Context, current: WorkSessionV2?): Boolean? {
        val prefs = preferences(context)
        val storedScope = runCatching { prefs.getString(KEY_PENDING_ACCOUNT_SCOPE, null) }.getOrNull()
            ?: return false // Legacy unbound questions remain historical and cannot be confirmed.
        val currentScope = GpsExitDeliveryV2.accountScope() ?: return null
        if (storedScope != currentScope) return false
        if (!prefs.contains(KEY_PENDING_DELIVERY)) return true
        val raw = runCatching { prefs.getString(KEY_PENDING_DELIVERY, null) }.getOrNull() ?: return null
        val delivery = GpsExitDeliveryRecordV2.decode(raw) ?: return null
        if (!delivery.matchesSession(current?.id, current?.realArrivalMs)) return false
        return GpsExitDeliveryV2.matchesContext(context, delivery)
    }

    private fun pendingDelivery(context: Context): GpsExitDeliveryRecordV2? {
        val prefs = preferences(context)
        return runCatching { prefs.getString(KEY_PENDING_DELIVERY, null) }.getOrNull()
            ?.let { GpsExitDeliveryRecordV2.decode(it) }
    }

    private fun clearPending(context: Context): Boolean {
        // ACK receipts survive cancellation/confirmation and failed outbox cleanup.
        val editor = preferences(context).edit()
        removePendingFields(editor)
        return editor.commit()
    }

    private fun removePendingFields(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_EVIDENCE_VERSION).remove(KEY_PENDING_ID).remove(KEY_PENDING_AT).remove(KEY_PENDING_PLACE)
            .remove(KEY_PENDING_TYPE).remove(KEY_PENDING_TRANSITION).remove(KEY_PROMPTED_ID)
            .remove(KEY_PROMPTED_PROCESS)
            .remove(KEY_PENDING_DELIVERY)
    }
}
