package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.Calendar
import java.util.Locale

/**
 * Répartition calendaire du temps payé d'une session fermée.
 *
 * Aucune règle de métier, d'équipe ou d'horaire n'est inventée ici. Une pause enregistrée
 * comme non payée est déduite ; une pause explicitement payée reste du temps payé ; une pause
 * inconnue rend le résultat non fiable. Une ancienne déduction fixe importée reste une
 * déduction fixe et n'est jamais reclassée arbitrairement.
 */
object PaidWorkAllocationV2 {
    data class PaidOverlapResult(
        val paidMs: Long,
        val reliable: Boolean
    )

    data class WeekSlice(
        val weekYear: Int,
        val weekOfYear: Int,
        val startMs: Long,
        val endMs: Long,
        val paidMs: Long,
        val reliable: Boolean = true
    )

    fun paidOverlap(session: WorkSessionV2, rangeStartMs: Long, rangeEndMs: Long): Long =
        paidOverlapResult(session, rangeStartMs, rangeEndMs).paidMs

    fun paidOverlapResult(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): PaidOverlapResult {
        if (!WorkSessionRangeV2.isClosedAndComplete(session)) return PaidOverlapResult(0L, false)
        val sessionStart = effectiveSessionStart(session) ?: return PaidOverlapResult(0L, false)
        val sessionEnd = session.countedExitMs ?: return PaidOverlapResult(0L, false)
        if (sessionEnd <= sessionStart || rangeEndMs <= rangeStartMs) return PaidOverlapResult(0L, false)

        // Reuse the complete session's trust gate: payroll may not ignore unresolved facts.
        val reliable = DefaultTimeEngineV2.calculate(session, session.realExitMs!!).reliable
        val start = maxOf(sessionStart, rangeStartMs)
        val end = minOf(sessionEnd, rangeEndMs)
        if (end <= start) return PaidOverlapResult(0L, reliable)

        val pauseResolution = PaidPauseResolutionV2.resolve(
            pauses = session.pauses,
            rangeStartMs = sessionStart,
            rangeEndMs = sessionEnd,
            allowOpenPause = false
        )
        val segments = QualifiedWorkSegmentsV2.resolve(session, sessionStart, sessionEnd)
        fun workedAfterPauses(a: Long, b: Long): Long = segments.workedIntervals.sumOf { (periodStart, periodEnd) ->
            val clippedStart = maxOf(a, periodStart)
            val clippedEnd = minOf(b, periodEnd)
            if (clippedEnd <= clippedStart) 0L else {
                val full = clippedEnd - clippedStart
                val unpaid = PaidPauseResolutionV2.overlapDuration(
                    pauseResolution.unpaidIntervals, clippedStart, clippedEnd
                )
                (full - unpaid).coerceAtLeast(0L)
            }
        }
        val explicitFull = workedAfterPauses(sessionStart, sessionEnd)
        val explicitRange = workedAfterPauses(start, end)
        val fixed = session.legacyFixedUnpaidPauseMs.takeIf {
            it > 0L && it <= explicitFull && session.pauses.isEmpty() && session.workSegments.isEmpty()
        } ?: 0L
        val consistent = reliable && pauseResolution.reliable && segments.reliable
        if (fixed == 0L || explicitFull == 0L) return PaidOverlapResult(explicitRange, consistent)

        // A standalone historical fixed deduction remains provisional; never add to explicit pauses.
        val prorated = ((explicitRange.toDouble() * (explicitFull - fixed).toDouble()) / explicitFull.toDouble())
            .toLong().coerceIn(0L, explicitRange)
        return PaidOverlapResult(prorated, consistent)
    }

    fun splitByIsoWeek(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): List<WeekSlice> {
        val sessionStart = effectiveSessionStart(session) ?: return emptyList()
        val sessionEnd = session.countedExitMs ?: return emptyList()
        var cursor = maxOf(sessionStart, rangeStartMs)
        val limit = minOf(sessionEnd, rangeEndMs)
        if (limit <= cursor) return emptyList()

        val out = mutableListOf<WeekSlice>()
        while (cursor < limit) {
            val current = calendar(cursor)
            val weekYear = current.getWeekYear()
            val week = current.get(Calendar.WEEK_OF_YEAR)
            val nextMonday = nextIsoWeekStart(cursor)
            val end = minOf(limit, nextMonday)
            val result = paidOverlapResult(session, cursor, end)
            if (result.paidMs > 0L || !result.reliable) {
                out += WeekSlice(
                    weekYear = weekYear,
                    weekOfYear = week,
                    startMs = cursor,
                    endMs = end,
                    paidMs = result.paidMs,
                    reliable = result.reliable
                )
            }
            cursor = end
        }
        return out
    }

    fun isReliableForRange(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Boolean = paidOverlapResult(session, rangeStartMs, rangeEndMs).reliable

    private fun effectiveSessionStart(session: WorkSessionV2): Long? =
        WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)

    private fun nextIsoWeekStart(atMs: Long): Long {
        val c = calendar(atMs)
        val day = c.get(Calendar.DAY_OF_WEEK)
        val daysToMonday = if (day == Calendar.SUNDAY) 1 else Calendar.MONDAY - day + 7
        c.add(Calendar.DAY_OF_YEAR, daysToMonday)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun calendar(ms: Long) = Calendar.getInstance(Locale.FRANCE).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = ms
    }
}
