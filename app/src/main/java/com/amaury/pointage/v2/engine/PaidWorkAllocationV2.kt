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
        val sessionStart = effectiveSessionStart(session) ?: return PaidOverlapResult(0L, false)
        val sessionEnd = session.countedExitMs ?: return PaidOverlapResult(0L, false)
        if (sessionEnd <= sessionStart || rangeEndMs <= rangeStartMs) return PaidOverlapResult(0L, false)

        val start = maxOf(sessionStart, rangeStartMs)
        val end = minOf(sessionEnd, rangeEndMs)
        if (end <= start) return PaidOverlapResult(0L, true)

        val fullPauseResolution = PaidPauseResolutionV2.resolve(
            pauses = session.pauses,
            rangeStartMs = sessionStart,
            rangeEndMs = sessionEnd,
            openPauseEndMs = sessionEnd
        )
        val rangePauseResolution = PaidPauseResolutionV2.resolve(
            pauses = session.pauses,
            rangeStartMs = start,
            rangeEndMs = end,
            openPauseEndMs = end
        )
        val unpaid = fullPauseResolution.unpaidIntervals

        val fullSpan = sessionEnd - sessionStart
        val explicitUnpaidFull = PaidPauseResolutionV2.overlapDuration(unpaid, sessionStart, sessionEnd)
        val explicitPaidFull = (fullSpan - explicitUnpaidFull).coerceAtLeast(0L)
        if (explicitPaidFull == 0L) return PaidOverlapResult(0L, rangePauseResolution.reliable)

        val rangeSpan = end - start
        val explicitUnpaidRange = PaidPauseResolutionV2.overlapDuration(unpaid, start, end)
        val explicitPaidRange = (rangeSpan - explicitUnpaidRange).coerceAtLeast(0L)
        if (explicitPaidRange == 0L) return PaidOverlapResult(0L, rangePauseResolution.reliable)

        val fixed = session.legacyFixedUnpaidPauseMs.coerceIn(0L, explicitPaidFull)
        if (fixed == 0L) return PaidOverlapResult(explicitPaidRange, rangePauseResolution.reliable)

        val targetFull = explicitPaidFull - fixed
        val paid = ((explicitPaidRange.toDouble() * targetFull.toDouble()) / explicitPaidFull.toDouble())
            .toLong()
            .coerceIn(0L, explicitPaidRange)
        return PaidOverlapResult(paid, rangePauseResolution.reliable)
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
