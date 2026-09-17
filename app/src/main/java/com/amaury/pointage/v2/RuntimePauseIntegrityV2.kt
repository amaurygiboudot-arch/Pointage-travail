package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2

/**
 * Règles d'intégrité minimales autour d'une pause runtime ouverte.
 *
 * Ces helpers restent purs : aucune règle d'entreprise n'est inventée ici. Ils servent uniquement
 * à préserver une information connue et à refuser qu'un événement automatique agisse sur une autre
 * pause que celle qu'il a réellement ouverte.
 */
internal object RuntimePauseIntegrityV2 {
    fun paidForClose(
        hasStoredPaid: Boolean,
        storedPaid: Boolean,
        fallbackPaid: Boolean?
    ): Boolean? = if (hasStoredPaid) storedPaid else fallbackPaid

    fun matchesAutomaticPause(openPause: PauseV2?, expectedStartMs: Long?): Boolean =
        expectedStartMs != null &&
            expectedStartMs > 0L &&
            openPause != null &&
            openPause.endMs == null &&
            openPause.source == EventSourceV2.SYSTEM &&
            openPause.startMs == expectedStartMs
}
