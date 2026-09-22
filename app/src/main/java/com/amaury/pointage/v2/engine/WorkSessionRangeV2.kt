package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Détermine prudemment si une session peut toucher une période bornée. */
object WorkSessionRangeV2 {
    data class Interval(val startMs: Long, val endMs: Long)

    fun isClosedAndComplete(session: WorkSessionV2): Boolean {
        val realStart = session.realArrivalMs ?: return false
        val realEnd = session.realExitMs ?: return false
        val countedStart = session.countedEntryMs ?: return false
        val countedEnd = session.countedExitMs ?: return false
        return session.status == SessionStatusV2.CLOSED &&
            realStart > 0L && realEnd > realStart &&
            countedStart > 0L && countedEnd > countedStart
    }

    fun effectiveInterval(session: WorkSessionV2, openEndMs: Long? = null): Interval? {
        val start = session.countedEntryMs ?: session.realArrivalMs ?: return null
        val end = if (session.status == SessionStatusV2.OPEN) {
            openEndMs
        } else {
            session.countedExitMs ?: session.realExitMs
        } ?: return null
        return if (start > 0L && end > start) Interval(start, end) else null
    }

    fun clippedInterval(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long? = null
    ): Interval? {
        if (rangeEndMs <= rangeStartMs) return null
        val interval = effectiveInterval(session, openEndMs) ?: return null
        val start = maxOf(interval.startMs, rangeStartMs)
        val end = minOf(interval.endMs, rangeEndMs)
        return if (end > start) Interval(start, end) else null
    }

    fun potentiallyTouches(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long? = null
    ): Boolean {
        if (rangeEndMs <= rangeStartMs) return false
        val starts = listOfNotNull(session.countedEntryMs, session.realArrivalMs)
            .filter { it > 0L }
            .distinct()
        if (starts.isEmpty()) return false

        val openEnd = openEndMs?.takeIf { session.status == SessionStatusV2.OPEN && it > 0L }
        val ends = if (session.status == SessionStatusV2.OPEN) {
            listOfNotNull(openEnd)
        } else {
            listOfNotNull(session.countedExitMs, session.realExitMs)
                .filter { it > 0L }
                .distinct()
        }
        if (ends.isEmpty()) return starts.any { it >= rangeStartMs && it < rangeEndMs }

        return starts.any { start ->
            ends.any { end ->
                when {
                    openEnd != null && end == openEnd && end <= start -> false
                    end > start -> start < rangeEndMs && end > rangeStartMs
                    else -> {
                        val lower = minOf(start, end)
                        val upper = maxOf(start, end)
                        lower < rangeEndMs && upper > rangeStartMs
                    }
                }
            }
        }
    }
}
