package com.amaury.pointage.v2.ui

import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.model.SessionStatusV2

/** Carte de statut de l'écran principal : même observation que les widgets et l'icône. */
internal object RuntimeStatusTextV2 {
    fun format(read: V2RuntimeReader.CurrentRead, formatTime: (Long) -> String): String {
        if (!read.reliable || !read.observation.reliable) return "STATUT ACTUEL\n⚠ DONNÉES À VÉRIFIER"
        val session = read.snapshot.session ?: return "STATUT ACTUEL\n○ Aucune entrée en cours"
        return when (session.status) {
            SessionStatusV2.CLOSED -> "STATUT ACTUEL\n● SESSION TERMINÉE"
            SessionStatusV2.TO_CONFIRM -> "STATUT ACTUEL\n● SESSION À CONFIRMER"
            SessionStatusV2.OPEN -> {
                val start = if (read.observation.paused) read.observation.activePauseStartMs else session.realArrivalMs
                val label = if (read.observation.paused) "⏸ PAUSE EN COURS" else "● ENTRÉE EN COURS"
                "STATUT ACTUEL\n$label" + (start?.let { "\nDepuis ${formatTime(it)}" } ?: "")
            }
        }
    }
}
