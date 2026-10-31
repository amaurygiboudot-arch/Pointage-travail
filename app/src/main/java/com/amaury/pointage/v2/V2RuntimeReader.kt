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

    data class DiagnosticRead(val history: SessionsRead, val current: CurrentRead)

    internal const val DIAGNOSTIC_MIGRATION_WARNING =
        "Migration du journal du compte non achevée ou métadonnées illisibles : diagnostic sans migration, faits non présentés comme fiables."

    /** Keep completeness and current-account integrity separate, without relaxing payroll reads. */
    fun diagnostic(context: Context, nowMs: Long = System.currentTimeMillis()): DiagnosticRead =
        V2RuntimeStore.withTransaction {
            // allSessions/current normally migrate. An export must never trigger that mutation,
            // including repair of historical timestamps or generation of missing session IDs.
            if (!V2RuntimeAccountScope.available() || !V2MigrationManager.completedForDiagnostic(context)) {
                val currentWarnings = listOf(if (!V2RuntimeAccountScope.available())
                    V2RuntimeAccountScope.AUTH_WARNING else DIAGNOSTIC_MIGRATION_WARNING)
                val historyWarnings = currentWarnings + if (V2RuntimeAccountScope.available() &&
                    V2RuntimeAccountScope.hasUnassignedHistory(context)) listOf(V2RuntimeAccountScope.UNASSIGNED_WARNING)
                    else emptyList()
                V2RuntimeHistoryGuardV2.publishSourceState(false, historyWarnings)
                return@withTransaction DiagnosticRead(
                    SessionsRead(emptyList(), false, historyWarnings),
                    CurrentRead(V2RuntimeStore.Snapshot(null, null), false, currentWarnings)
                )
            }
            val history = allSessions(context, nowMs)
            val current = current(context, nowMs)
            // current() can succeed with unassigned legacy data. Do not leave that success as
            // the ambient state of the complete source consumed by existing callers.
            V2RuntimeHistoryGuardV2.publishSourceState(history.reliable, history.warnings)
            DiagnosticRead(history, current)
        }

    const val UNRELIABLE_MESSAGE =
        "Historique HoraTrack V2 non fiable : données, analyses et exports bloqués jusqu'à vérification."

    fun allSessions(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): SessionsRead = V2RuntimeStore.withTransaction {
        val sessions = V2RuntimeStore.allSessions(context, nowMs)
        val source = V2RuntimeHistoryGuardV2.sourceState()
        return@withTransaction SessionsRead(
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
    ): CurrentRead = V2RuntimeStore.withTransaction {
        V2RuntimeStore.allSessions(context, nowMs, requireCompleteHistory = false)
        val fullSource = V2RuntimeHistoryGuardV2.sourceState()
        if (!fullSource.reliable) {
            return@withTransaction CurrentRead(
                snapshot = V2RuntimeStore.Snapshot(null, null),
                reliable = false,
                warnings = fullSource.warnings
            )
        }

        val snapshot = V2RuntimeStore.snapshot(context, nowMs)
        val source = V2RuntimeHistoryGuardV2.sourceState()
        return@withTransaction CurrentRead(
            snapshot = if (source.reliable) snapshot else V2RuntimeStore.Snapshot(null, null),
            reliable = source.reliable,
            warnings = source.warnings
        )
    }

    fun warningText(warnings: List<String>): String =
        warnings.distinct().joinToString("\n").ifBlank { UNRELIABLE_MESSAGE }
}
