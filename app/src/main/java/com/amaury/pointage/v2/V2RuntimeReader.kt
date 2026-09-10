package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Lecture canonique du runtime V2 pour les écrans, analyses et exports.
 *
 * La liste vide ne suffit jamais à prouver qu'il n'existe aucune session : elle peut aussi être
 * le résultat fail-closed d'une migration, d'un historique ou d'une session courante non fiable.
 * Cette façade associe donc toujours les données à leur état de fiabilité.
 */
object V2RuntimeReader {
    data class SessionsRead(
        val sessions: List<WorkSessionV2>,
        val reliable: Boolean,
        val warnings: List<String>
    ) {
        fun requireReliable(): List<WorkSessionV2> {
            check(reliable) { warnings.joinToString(" ").ifBlank { UNRELIABLE_MESSAGE } }
            return sessions
        }
    }

    data class CurrentRead(
        val snapshot: V2RuntimeStore.Snapshot,
        val reliable: Boolean,
        val warnings: List<String>
    )

    const val UNRELIABLE_MESSAGE =
        "Historique HoraTrack V2 non fiable : données, analyses et exports bloqués jusqu'à vérification."

    fun allSessions(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): SessionsRead {
        val sessions = V2RuntimeStore.allSessions(context, nowMs)
        val source = V2RuntimeHistoryGuardV2.sourceState()
        return SessionsRead(
            sessions = sessions,
            reliable = source.reliable,
            warnings = source.warnings
        )
    }

    /**
     * Une lecture de la session courante doit d'abord valider la migration et l'historique complet.
     * Sinon un historique corrompu pourrait être masqué par une session courante apparemment valide.
     */
    fun current(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): CurrentRead {
        V2RuntimeStore.allSessions(context, nowMs)
        val fullSource = V2RuntimeHistoryGuardV2.sourceState()
        if (!fullSource.reliable) {
            return CurrentRead(
                snapshot = V2RuntimeStore.Snapshot(null, null),
                reliable = false,
                warnings = fullSource.warnings
            )
        }

        val snapshot = V2RuntimeStore.snapshot(context, nowMs)
        val source = V2RuntimeHistoryGuardV2.sourceState()
        return CurrentRead(
            snapshot = if (source.reliable) snapshot else V2RuntimeStore.Snapshot(null, null),
            reliable = source.reliable,
            warnings = source.warnings
        )
    }

    fun warningText(warnings: List<String>): String =
        warnings.distinct().joinToString("\n").ifBlank { UNRELIABLE_MESSAGE }
}
