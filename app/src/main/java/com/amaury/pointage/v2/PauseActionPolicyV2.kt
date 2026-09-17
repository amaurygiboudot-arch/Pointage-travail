package com.amaury.pointage.v2

/**
 * Décision pure pour une action manuelle de pause V2.
 *
 * Une nouvelle pause n'est jamais ouverte tant que son statut payé/non payé n'a pas été
 * explicitement choisi. Une pause déjà ouverte est seulement refermée : son statut canonique
 * reste celui enregistré au démarrage.
 */
internal object PauseActionPolicyV2 {
    enum class Next {
        NO_OPEN_SESSION,
        CLOSE_EXISTING,
        SELECT_PAID_STATUS,
        INVALID_MULTIPLE_OPEN_PAUSES
    }

    fun next(hasOpenSession: Boolean, openPauseCount: Int): Next = when {
        !hasOpenSession -> Next.NO_OPEN_SESSION
        openPauseCount < 0 -> Next.INVALID_MULTIPLE_OPEN_PAUSES
        openPauseCount == 0 -> Next.SELECT_PAID_STATUS
        openPauseCount == 1 -> Next.CLOSE_EXISTING
        else -> Next.INVALID_MULTIPLE_OPEN_PAUSES
    }
}
