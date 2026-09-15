package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.PauseV2

/**
 * Interprétation canonique V2 des pauses pour le Temps et la Paie.
 *
 * Cette couche est volontairement indépendante du métier, de l'entreprise, du nom du poste
 * et de l'heure de début de journée. Une pause n'est payée que si cette information est
 * explicitement confirmée dans [PauseV2]. Une pause inconnue ou à confirmer n'est jamais
 * transformée silencieusement en pause payée ou non payée.
 */
object PaidPauseResolutionV2 {
    data class Resolution(
        val unpaidIntervals: List<Pair<Long, Long>>,
        val paidIntervals: List<Pair<Long, Long>>,
        val unresolvedCount: Int
    ) {
        val reliable: Boolean get() = unresolvedCount == 0
    }

    fun resolve(
        pauses: List<PauseV2>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openPauseEndMs: Long = rangeEndMs
    ): Resolution {
        if (rangeEndMs <= rangeStartMs) return Resolution(emptyList(), emptyList(), 0)

        val unresolvedCount = pauses.count { pause ->
            overlaps(pause, rangeStartMs, rangeEndMs, openPauseEndMs) &&
                (pause.status != DecisionStatusV2.CONFIRMED || pause.paid == null)
        }
        val confirmed = pauses.filter {
            it.status == DecisionStatusV2.CONFIRMED && it.paid != null
        }

        return Resolution(
            unpaidIntervals = mergeIntervals(
                confirmed.filter { it.paid == false }
                    .mapNotNull { clipped(it, rangeStartMs, rangeEndMs, openPauseEndMs) }
            ),
            paidIntervals = mergeIntervals(
                confirmed.filter { it.paid == true }
                    .mapNotNull { clipped(it, rangeStartMs, rangeEndMs, openPauseEndMs) }
            ),
            unresolvedCount = unresolvedCount
        )
    }

    fun duration(intervals: List<Pair<Long, Long>>): Long =
        intervals.sumOf { (start, end) -> (end - start).coerceAtLeast(0L) }

    fun overlapDuration(
        intervals: List<Pair<Long, Long>>,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Long = intervals.sumOf { interval ->
        (minOf(rangeEndMs, interval.second) - maxOf(rangeStartMs, interval.first)).coerceAtLeast(0L)
    }

    fun overlapDuration(
        a: List<Pair<Long, Long>>,
        b: List<Pair<Long, Long>>
    ): Long {
        var total = 0L
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].first, b[j].first)
            val end = minOf(a[i].second, b[j].second)
            if (end > start) total += end - start
            if (a[i].second <= b[j].second) i++ else j++
        }
        return total
    }

    private fun overlaps(
        pause: PauseV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openPauseEndMs: Long
    ): Boolean = clipped(pause, rangeStartMs, rangeEndMs, openPauseEndMs) != null

    private fun clipped(
        pause: PauseV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openPauseEndMs: Long
    ): Pair<Long, Long>? {
        if (pause.startMs <= 0L) return null
        val pauseEnd = pause.endMs ?: openPauseEndMs
        val start = maxOf(pause.startMs, rangeStartMs)
        val end = minOf(pauseEnd, rangeEndMs)
        return if (end > start) start to end else null
    }

    private fun mergeIntervals(input: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = input.filter { it.second > it.first }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()

        val out = mutableListOf<Pair<Long, Long>>()
        var start = sorted.first().first
        var end = sorted.first().second
        for (index in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[index]
            if (nextStart <= end) {
                end = maxOf(end, nextEnd)
            } else {
                out += start to end
                start = nextStart
                end = nextEnd
            }
        }
        out += start to end
        return out
    }
}
