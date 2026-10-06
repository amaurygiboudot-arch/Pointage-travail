package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Observation à un instant unique ; ne répare ni ne réécrit un événement enregistré. */
object RuntimeObservationV2 {
    const val CLOCK_MESSAGE =
        "Horaire du pointage à confirmer : vérifie la date et l'heure du téléphone. Aucun pointage n'a été modifié."
    const val BOUNDED_PAUSE_MESSAGE =
        "Cette pause a déjà une heure de fin. Pour reprendre plus tôt, modifie sa fin dans la saisie manuelle des pauses."

    data class State(
        val reliable: Boolean,
        val paused: Boolean = false,
        val hasActiveBoundedPause: Boolean = false,
        val activePauseStartMs: Long? = null
    )

    fun assess(session: WorkSessionV2?, nowMs: Long): State {
        if (session == null) return State(true)
        val entry = session.realArrivalMs ?: return State(false)
        // Une fin bornée future est permise pour une pause déjà commencée, pas pour une sortie.
        if (nowMs <= 0L || entry > nowMs || session.realExitMs?.let { it > nowMs } == true ||
            session.pauses.any { it.startMs > nowMs }) return State(false)
        if (session.status != SessionStatusV2.OPEN) return State(true)
        val active = session.pauses.filter { it.startMs <= nowMs && (it.endMs == null || it.endMs > nowMs) }
        return State(true, active.isNotEmpty(), active.any { it.endMs != null }, active.minOfOrNull { it.startMs })
    }
}
