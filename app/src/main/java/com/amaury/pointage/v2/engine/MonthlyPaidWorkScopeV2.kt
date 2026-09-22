package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Sélection et fiabilité canoniques du temps payé d'une entreprise sur un mois.
 *
 * Les sessions complètes peuvent traverser les bornes de période :
 * [PaidWorkAllocationV2] les découpe ensuite. Une session pertinente mais ouverte,
 * à confirmer ou incomplète bloque en revanche tout montant dépendant du temps.
 */
object MonthlyPaidWorkScopeV2 {
    const val INCOMPLETE_WARNING =
        "Temps de travail : un pointage de l'entreprise est ouvert, à confirmer ou incomplet ; le temps payé et le brut restent à confirmer."
    const val PAUSE_WARNING =
        "Pause à confirmer ou statut payé/non payé inconnu : le temps payé et le brut restent à confirmer."

    data class Result(
        val selected: List<WorkSessionV2>,
        val reliable: Boolean,
        val incompleteSession: Boolean,
        val allocationReliable: Boolean,
        val overlappingSessions: Boolean,
        val unassignedEmployerSession: Boolean,
        val unpaidPauseMs: Long?,
        val warnings: List<String>
    )

    fun resolve(
        sessions: List<WorkSessionV2>,
        acceptedEmployerIds: Set<String>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        nowMs: Long
    ): Result {
        val ids = acceptedEmployerIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (rangeEndMs <= rangeStartMs || ids.isEmpty()) {
            return Result(
                selected = emptyList(),
                reliable = false,
                incompleteSession = false,
                allocationReliable = false,
                overlappingSessions = false,
                unassignedEmployerSession = false,
                unpaidPauseMs = null,
                warnings = listOf(INCOMPLETE_WARNING)
            )
        }

        val candidates = sessions.filter { session ->
            session.employerId?.trim() in ids &&
                WorkSessionRangeV2.potentiallyTouches(
                    session = session,
                    rangeStartMs = rangeStartMs,
                    rangeEndMs = rangeEndMs,
                    openEndMs = nowMs
                )
        }
        val incomplete = candidates.any { !WorkSessionRangeV2.isClosedAndComplete(it) }
        val selected = candidates.filter(WorkSessionRangeV2::isClosedAndComplete)
        val allocationReliable = selected.all {
            PaidWorkAllocationV2.isReliableForRange(it, rangeStartMs, rangeEndMs)
        }
        val overlapping = WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
            sessions = sessions,
            acceptedEmployerIds = ids,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
            openEndMs = nowMs
        )
        val unassigned = WorkSessionEmployerAssignmentV2.hasUnassignedSession(
            sessions = sessions,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
            openEndMs = nowMs
        )
        val reliable = !incomplete && allocationReliable && !overlapping && !unassigned
        val unpaidPauseMs = if (reliable) {
            selected.sumOf { session ->
                val countedStart = WorkTimePolicyV2.repairKnownCountedEntry(
                    session.realArrivalMs,
                    session.countedEntryMs
                ) ?: return@sumOf 0L
                val countedEnd = session.countedExitMs ?: return@sumOf 0L
                val start = maxOf(countedStart, rangeStartMs)
                val end = minOf(countedEnd, rangeEndMs)
                val countedSpan = (end - start).coerceAtLeast(0L)
                val paid = PaidWorkAllocationV2.paidOverlap(session, rangeStartMs, rangeEndMs)
                (countedSpan - paid).coerceAtLeast(0L)
            }
        } else null
        val warnings = buildList {
            if (incomplete) add(INCOMPLETE_WARNING)
            if (!allocationReliable) add(PAUSE_WARNING)
            if (overlapping) add(WorkSessionOverlapV2.WARNING)
            if (unassigned) add(WorkSessionEmployerAssignmentV2.WARNING)
        }.distinct()

        return Result(
            selected = selected,
            reliable = reliable,
            incompleteSession = incomplete,
            allocationReliable = allocationReliable,
            overlappingSessions = overlapping,
            unassignedEmployerSession = unassigned,
            unpaidPauseMs = unpaidPauseMs,
            warnings = warnings
        )
    }

}
