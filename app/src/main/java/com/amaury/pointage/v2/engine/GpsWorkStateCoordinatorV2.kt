package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeStore

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

        val current = V2RuntimeStore.snapshot(context, event.atMs).session

        if (event.pointType == GpsPointTypeV2.POSTE && event.transition == GpsTransitionV2.ENTER) {
            val pending = pending(context)
            if (pending?.kind == Pending.Kind.EXIT_WORKSITE &&
                pending.placeId == event.placeId &&
                event.atMs >= pending.atMs &&
                event.atMs - pending.atMs <= RETURN_WINDOW_MS
            ) {
                clearPending(context)
                return Outcome(Action.RETURNED_TO_POSTE, false, "Retour rapide au poste : sortie GPS annulée")
            }

            if (current == null || current.realExitMs != null) {
                val started = V2RuntimeStore.entry(context, event.atMs)
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
            savePending(context, event, Pending.Kind.EXIT_WORKSITE)
            return Outcome(Action.EXIT_PENDING_CONFIRMATION, true, "Sortie du poste détectée : fin de journée à confirmer")
        }

        savePending(context, event, Pending.Kind.AMBIGUOUS)
        return Outcome(Action.AMBIGUOUS_PENDING_CONFIRMATION, true, "Transition GPS ambiguë à qualifier")
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

    fun confirmExit(context: Context, expectedEndMs: Long? = null): Boolean {
        val pending = pending(context) ?: return false
        if (pending.kind != Pending.Kind.EXIT_WORKSITE) return false
        val ok = V2RuntimeStore.exit(context, pending.atMs, expectedEndMs)
        if (ok) clearPending(context)
        return ok
    }

    fun cancelPending(context: Context) = clearPending(context)

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
