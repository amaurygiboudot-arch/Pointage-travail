package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Couche de décision distincte du capteur GPS.
 * GpsEngineV2 ne fait qu'accepter/filtrer les événements. Ce coordinateur
 * décide ensuite des conséquences métier et conserve les événements ambigus
 * pour confirmation utilisateur.
 */
object GpsWorkStateCoordinatorV2 {
    private const val PREFS = "horatrack_v2_gps_state"
    private const val KEY_PENDING_ID = "pending_id"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_PENDING_PLACE = "pending_place"
    private const val KEY_PENDING_TYPE = "pending_type"
    private const val KEY_PENDING_TRANSITION = "pending_transition"
    private const val KEY_PROMPTED_ID = "prompted_id"
    private const val RETURN_WINDOW_MS = 2L * 60_000L

    enum class Action {
        IGNORED,
        ENTRY_STARTED,
        RETURNED_TO_POSTE,
        EXIT_PENDING_CONFIRMATION,
        AMBIGUOUS_PENDING_CONFIRMATION,
        NO_CHANGE
    }

    data class Outcome(
        val action: Action,
        val requiresConfirmation: Boolean,
        val reason: String
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

    fun route(context: Context, event: GpsEventV2, decision: GpsDecisionV2): Outcome {
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
        var currentPending = pending(context)
        if (shouldDiscardPending(currentPending, current)) {
            clearPending(context)
            currentPending = null
        }

        if (event.pointType == GpsPointTypeV2.POSTE && event.transition == GpsTransitionV2.ENTER) {
            if (canApplyQuickReturn(currentPending, event, current)) {
                clearPending(context)
                return Outcome(Action.RETURNED_TO_POSTE, false, "Retour rapide au poste : sortie GPS annulée")
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
            if (!canQueuePending(currentPending, Pending.Kind.EXIT_WORKSITE)) {
                return Outcome(
                    Action.NO_CHANGE,
                    true,
                    "Une transition GPS attend déjà une confirmation"
                )
            }
            savePending(context, event, Pending.Kind.EXIT_WORKSITE)
            return Outcome(Action.EXIT_PENDING_CONFIRMATION, true, "Sortie du poste détectée : fin de journée à confirmer")
        }

        if (!canQueueAmbiguous(current, event.transition)) {
            return Outcome(
                Action.NO_CHANGE,
                false,
                "État de travail incompatible avec cette transition GPS ambiguë"
            )
        }
        if (!canQueuePending(currentPending, Pending.Kind.AMBIGUOUS)) {
            return Outcome(
                Action.NO_CHANGE,
                true,
                "Une transition GPS attend déjà une confirmation"
            )
        }
        savePending(context, event, Pending.Kind.AMBIGUOUS)
        return Outcome(Action.AMBIGUOUS_PENDING_CONFIRMATION, true, "Transition GPS ambiguë à qualifier")
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

    internal fun shouldDiscardPending(
        pending: Pending?,
        current: WorkSessionV2?,
        entryStarted: Boolean = false
    ): Boolean {
        if (pending == null) return false
        if (entryStarted) return true
        if (current?.status != SessionStatusV2.OPEN || current.realExitMs != null) return false
        val arrival = current.realArrivalMs ?: return false
        return pending.atMs < arrival
    }

    internal fun canQueuePending(existing: Pending?, incomingKind: Pending.Kind): Boolean =
        existing == null || (
            incomingKind == Pending.Kind.EXIT_WORKSITE &&
                existing.kind == Pending.Kind.AMBIGUOUS
            )

    internal fun isQuickReturnToPoste(pending: Pending?, event: GpsEventV2): Boolean {
        if (pending?.kind != Pending.Kind.EXIT_WORKSITE) return false
        if (event.pointType != GpsPointTypeV2.POSTE || event.transition != GpsTransitionV2.ENTER) return false
        if (pending.placeId != event.placeId || event.atMs < pending.atMs) return false
        return event.atMs - pending.atMs <= RETURN_WINDOW_MS
    }

    internal fun canApplyQuickReturn(
        pending: Pending?,
        event: GpsEventV2,
        current: WorkSessionV2?
    ): Boolean {
        if (current?.status != SessionStatusV2.OPEN || current.realExitMs != null) return false
        val arrival = current.realArrivalMs ?: return false
        if (pending == null || pending.atMs < arrival) return false
        return isQuickReturnToPoste(pending, event)
    }

    fun pending(context: Context): Pending? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_PENDING_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs.getLong(KEY_PENDING_AT, 0L).takeIf { it > 0L } ?: return null
        val place = prefs.getString(KEY_PENDING_PLACE, null)?.takeIf { it.isNotBlank() } ?: return null
        val raw = prefs.getString(KEY_PENDING_TYPE, null) ?: return null
        val parts = raw.split(':')
        val pointType = runCatching { GpsPointTypeV2.valueOf(parts.first()) }.getOrNull() ?: return null
        val kind = runCatching { Pending.Kind.valueOf(parts.getOrNull(1) ?: "") }.getOrNull() ?: return null
        val transition = runCatching {
            GpsTransitionV2.valueOf(prefs.getString(KEY_PENDING_TRANSITION, null).orEmpty())
        }.getOrNull() ?: return null
        return Pending(id, at, place, pointType, transition, kind)
    }

    fun shouldPrompt(context: Context, pending: Pending): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROMPTED_ID, null) != pending.id
    }

    fun markPromptShown(context: Context, pending: Pending) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROMPTED_ID, pending.id).apply()
    }

    /** Une fermeture sans réponse ne transforme pas l'événement en décision. */
    fun allowPromptAgain(context: Context, pending: Pending) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PENDING_ID, null) == pending.id) {
            prefs.edit().remove(KEY_PROMPTED_ID).apply()
        }
    }

    fun confirmExit(
        context: Context,
        expectedPendingId: String,
        expectedEndMs: Long? = null
    ): Boolean {
        val pending = pending(context)
            ?.takeIf { matchesPendingId(it, expectedPendingId) }
            ?: return false
        if (pending.kind != Pending.Kind.EXIT_WORKSITE) return false
        val ok = V2RuntimeStore.exit(context, pending.atMs, expectedEndMs)
        if (ok) clearPending(context)
        return ok
    }

    /**
     * Confirme un début de pause détecté par GPS.
     *
     * Le statut payé/non payé est obligatoire : le GPS ne peut jamais l'inférer.
     * L'événement reste en attente si l'écriture runtime échoue.
     */
    fun confirmPauseStart(context: Context, expectedPendingId: String, paid: Boolean): Boolean {
        val pending = pending(context)
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

    /**
     * Confirme une reprise après une pause ouverte.
     *
     * Le statut payé mémorisé à l'ouverture reste la source canonique ; aucune nouvelle
     * classification n'est inventée à la fermeture.
     */
    fun confirmPauseEnd(context: Context, expectedPendingId: String): Boolean {
        val pending = pending(context)
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

    fun cancelPending(context: Context, expectedPendingId: String): Boolean {
        val current = pending(context) ?: return false
        if (!matchesPendingId(current, expectedPendingId)) return false
        clearPending(context)
        return true
    }

    internal fun matchesPendingId(pending: Pending?, expectedPendingId: String): Boolean =
        pending != null && expectedPendingId.isNotBlank() && pending.id == expectedPendingId

    private fun savePending(context: Context, event: GpsEventV2, kind: Pending.Kind) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_ID, event.id)
            .putLong(KEY_PENDING_AT, event.atMs)
            .putString(KEY_PENDING_PLACE, event.placeId)
            .putString(KEY_PENDING_TYPE, "${event.pointType.name}:${kind.name}")
            .putString(KEY_PENDING_TRANSITION, event.transition.name)
            .remove(KEY_PROMPTED_ID)
            .apply()
    }

    private fun clearPending(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
