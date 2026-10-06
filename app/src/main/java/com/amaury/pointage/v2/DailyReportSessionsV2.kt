package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PaidPauseResolutionV2
import com.amaury.pointage.v2.engine.PaidWorkAllocationV2
import com.amaury.pointage.v2.engine.WorkSessionRangeV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Vue d'export bornée ; les sessions et les faits de pause stockés restent inchangés. */
internal object DailyReportSessionsV2 {
    data class Row(
        val sessionId: String,
        val startMs: Long,
        val endMs: Long,
        val paidMs: Long,
        val explicitUnpaidMs: Long,
        val allocatedFixedUnpaidMs: Long,
        val pauses: List<PauseV2>
    )

    fun rows(sessions: List<WorkSessionV2>, dayStart: Long, dayEnd: Long): List<Row> {
        require(dayStart > 0L && dayEnd > dayStart) { "Période de rapport invalide" }
        return sessions.mapNotNull { session ->
            if (session.status == SessionStatusV2.OPEN) return@mapNotNull null
            val countedStart = WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)
            val normalized = session.copy(countedEntryMs = countedStart)
            if (!WorkSessionRangeV2.potentiallyTouches(normalized, dayStart, dayEnd)) return@mapNotNull null
            check(WorkSessionRangeV2.isClosedAndComplete(normalized)) {
                "Session ${session.id} incomplète : rapport à confirmer"
            }
            val fullStart = requireNotNull(countedStart)
            val fullEnd = requireNotNull(session.countedExitMs)
            val start = maxOf(fullStart, dayStart)
            val end = minOf(fullEnd, dayEnd)
            if (end <= start) return@mapNotNull null

            // Une qualification inconnue ailleurs dans la session peut affecter la répartition
            // de sa déduction fixe. Ne jamais publier un total définitif dans ce cas.
            val fullPauses = PaidPauseResolutionV2.resolve(session.pauses, fullStart, fullEnd)
            val allocation = PaidWorkAllocationV2.paidOverlapResult(session, start, end)
            check(fullPauses.reliable && allocation.reliable) {
                "Pauses de la session ${session.id} à confirmer : export bloqué"
            }
            val pauses = session.pauses.mapNotNull { pause ->
                val pauseEnd = pause.endMs ?: error("Pause non terminée : export bloqué")
                val clippedStart = maxOf(start, pause.startMs)
                val clippedEnd = minOf(end, pauseEnd)
                if (clippedEnd > clippedStart) pause.copy(startMs = clippedStart, endMs = clippedEnd) else null
            }
            val unpaid = PaidPauseResolutionV2.overlapDuration(fullPauses.unpaidIntervals, start, end)
            Row(session.id, start, end, allocation.paidMs, unpaid,
                (end - start - unpaid - allocation.paidMs).coerceAtLeast(0L), pauses)
        }.sortedBy { it.startMs }
    }
}
