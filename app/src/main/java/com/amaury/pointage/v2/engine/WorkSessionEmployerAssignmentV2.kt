package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Garde-fou pour les calculs dépendant d'une entreprise.
 *
 * Une session sans employeur explicite ne doit jamais être attribuée par défaut à l'entreprise
 * courante, au premier profil ou à un ancien slot. Si elle touche la période étudiée, le calcul
 * dépendant de l'entreprise doit rester à confirmer.
 */
object WorkSessionEmployerAssignmentV2 {
    const val WARNING =
        "Temps de travail : un pointage de la période n'est rattaché à aucune entreprise ; calcul automatique bloqué."

    fun hasUnassignedSession(
        sessions: List<WorkSessionV2>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long? = null
    ): Boolean {
        if (rangeEndMs <= rangeStartMs) return false
        return sessions.any { session ->
            session.employerId?.trim().isNullOrEmpty() &&
                potentiallyTouches(session, rangeStartMs, rangeEndMs, openEndMs)
        }
    }

    private fun potentiallyTouches(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long?
    ): Boolean {
        val start = session.countedEntryMs ?: session.realArrivalMs ?: return false
        if (start <= 0L || start >= rangeEndMs) return false

        val end = session.countedExitMs
            ?: session.realExitMs
            ?: openEndMs?.takeIf { session.status == SessionStatusV2.OPEN }

        if (end == null) return start >= rangeStartMs && start < rangeEndMs
        if (end <= start) {
            val lower = minOf(start, end)
            val upper = maxOf(start, end)
            return lower < rangeEndMs && upper > rangeStartMs
        }
        return end > rangeStartMs
    }
}
