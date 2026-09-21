package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Garde-fou transversal contre le double comptage de pointages.
 *
 * Deux sessions d'une même entreprise ne sont jamais fusionnées automatiquement : leur
 * chevauchement rend le résultat dépendant non fiable jusqu'à correction/confirmation des faits.
 */
object WorkSessionOverlapV2 {
    const val WARNING =
        "Temps de travail : des pointages de la même entreprise se chevauchent ; le total payé reste à confirmer."

    fun hasOverlapWithinEmployerGroup(
        sessions: List<WorkSessionV2>,
        acceptedEmployerIds: Set<String>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long? = null
    ): Boolean {
        if (rangeEndMs <= rangeStartMs) return false
        val accepted = acceptedEmployerIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (accepted.isEmpty()) return false

        val intervals = sessions.asSequence()
            .filter { it.employerId?.trim() in accepted }
            .mapNotNull { clippedInterval(it, rangeStartMs, rangeEndMs, openEndMs) }
            .sortedWith(compareBy<Interval> { it.startMs }.thenBy { it.endMs })
            .toList()

        return hasOverlap(intervals)
    }

    fun hasSameEmployerOverlap(
        sessions: List<WorkSessionV2>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long? = null
    ): Boolean {
        if (rangeEndMs <= rangeStartMs) return false

        return sessions
            .mapNotNull { session ->
                val employerId = session.employerId?.trim()?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val interval = clippedInterval(session, rangeStartMs, rangeEndMs, openEndMs)
                    ?: return@mapNotNull null
                employerId to interval
            }
            .groupBy({ it.first }, { it.second })
            .values
            .any { intervals ->
                hasOverlap(intervals.sortedWith(compareBy<Interval> { it.startMs }.thenBy { it.endMs }))
            }
    }

    private fun clippedInterval(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long?
    ): Interval? {
        val start = session.countedEntryMs ?: session.realArrivalMs ?: return null
        val end = session.countedExitMs
            ?: session.realExitMs
            ?: openEndMs?.takeIf { session.status == SessionStatusV2.OPEN }
            ?: return null

        if (start <= 0L || end <= start) return null

        val clippedStart = maxOf(start, rangeStartMs)
        val clippedEnd = minOf(end, rangeEndMs)
        return if (clippedEnd > clippedStart) Interval(clippedStart, clippedEnd) else null
    }

    private fun hasOverlap(intervals: List<Interval>): Boolean {
        if (intervals.size < 2) return false
        var furthestEnd = intervals.first().endMs
        for (index in 1 until intervals.size) {
            val current = intervals[index]
            if (current.startMs < furthestEnd) return true
            if (current.endMs > furthestEnd) furthestEnd = current.endMs
        }
        return false
    }

    private data class Interval(
        val startMs: Long,
        val endMs: Long
    )
}
