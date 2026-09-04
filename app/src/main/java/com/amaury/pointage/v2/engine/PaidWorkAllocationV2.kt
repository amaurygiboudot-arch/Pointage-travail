package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.Calendar
import java.util.Locale

/**
 * Répartition calendaire du temps payé d'une session fermée.
 *
 * Les pauses non payées confirmées sont retirées à leur position réelle.
 * Une ancienne déduction fixe importée n'ayant pas de position horaire connue est
 * répartie proportionnellement sur le temps restant, ce qui préserve le total sans
 * inventer l'emplacement historique de cette pause.
 */
object PaidWorkAllocationV2 {
    data class WeekSlice(
        val weekYear: Int,
        val weekOfYear: Int,
        val startMs: Long,
        val endMs: Long,
        val paidMs: Long
    )

    fun paidOverlap(session: WorkSessionV2, rangeStartMs: Long, rangeEndMs: Long): Long {
        val sessionStart = session.countedEntryMs ?: return 0L
        val sessionEnd = session.countedExitMs ?: return 0L
        if (sessionEnd <= sessionStart || rangeEndMs <= rangeStartMs) return 0L

        val start = maxOf(sessionStart, rangeStartMs)
        val end = minOf(sessionEnd, rangeEndMs)
        if (end <= start) return 0L

        val unpaid = mergeIntervals(
            session.pauses
                .filter { it.status == DecisionStatusV2.CONFIRMED && it.paid == false }
                .mapNotNull { pause ->
                    val pauseEnd = pause.endMs ?: return@mapNotNull null
                    val pStart = maxOf(sessionStart, pause.startMs)
                    val pEnd = minOf(sessionEnd, pauseEnd)
                    if (pEnd > pStart) pStart to pEnd else null
                }
        )

        val fullSpan = sessionEnd - sessionStart
        val explicitUnpaidFull = overlapDuration(unpaid, sessionStart, sessionEnd)
        val explicitPaidFull = (fullSpan - explicitUnpaidFull).coerceAtLeast(0L)
        if (explicitPaidFull == 0L) return 0L

        val rangeSpan = end - start
        val explicitUnpaidRange = overlapDuration(unpaid, start, end)
        val explicitPaidRange = (rangeSpan - explicitUnpaidRange).coerceAtLeast(0L)
        if (explicitPaidRange == 0L) return 0L

        val fixed = session.legacyFixedUnpaidPauseMs.coerceIn(0L, explicitPaidFull)
        if (fixed == 0L) return explicitPaidRange

        val targetFull = explicitPaidFull - fixed
        return ((explicitPaidRange.toDouble() * targetFull.toDouble()) / explicitPaidFull.toDouble())
            .toLong()
            .coerceIn(0L, explicitPaidRange)
    }

    fun splitByIsoWeek(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): List<WeekSlice> {
        val sessionStart = session.countedEntryMs ?: return emptyList()
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
            val paid = paidOverlap(session, cursor, end)
            if (paid > 0L) out += WeekSlice(weekYear, week, cursor, end, paid)
            cursor = end
        }
        return out
    }

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

    private fun mergeIntervals(input: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = input.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Long, Long>>()
        var start = sorted.first().first
        var end = sorted.first().second
        for (i in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[i]
            if (nextStart <= end) end = maxOf(end, nextEnd)
            else {
                out += start to end
                start = nextStart
                end = nextEnd
            }
        }
        out += start to end
        return out
    }

    private fun overlapDuration(intervals: List<Pair<Long, Long>>, start: Long, end: Long): Long =
        intervals.sumOf { interval ->
            (minOf(end, interval.second) - maxOf(start, interval.first)).coerceAtLeast(0L)
        }
}
