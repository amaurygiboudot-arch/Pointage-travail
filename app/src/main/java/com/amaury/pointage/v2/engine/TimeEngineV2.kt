package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Moteur Temps HoraTrack V2 : source unique des calculs de présence et de temps payé. */
interface TimeEngineV2 {
    fun countedEntryFromRealArrival(realArrivalMs: Long): Long
    fun countedExitFromRealExit(realExitMs: Long, expectedEndMs: Long?): Long
    fun calculate(session: WorkSessionV2, nowMs: Long = System.currentTimeMillis()): TimeResultV2
}

data class TimeResultV2(
    val presenceMs: Long,
    val countedSpanMs: Long,
    val paidWorkMs: Long,
    val unpaidPauseMs: Long,
    val paidPauseMs: Long,
    val warnings: List<String> = emptyList()
)

object DefaultTimeEngineV2 : TimeEngineV2 {
    private const val ENTRY_SLOT_MS = 15L * 60L * 1000L
    private const val ENTRY_GRACE_MS = 5L * 60L * 1000L

    override fun countedEntryFromRealArrival(realArrivalMs: Long): Long {
        require(realArrivalMs > 0L) { "realArrivalMs doit être positif" }
        val remainder = Math.floorMod(realArrivalMs, ENTRY_SLOT_MS)
        val currentSlot = realArrivalMs - remainder
        return if (remainder == 0L || remainder <= ENTRY_GRACE_MS) currentSlot else currentSlot + ENTRY_SLOT_MS
    }

    override fun countedExitFromRealExit(realExitMs: Long, expectedEndMs: Long?): Long {
        require(realExitMs > 0L) { "realExitMs doit être positif" }
        if (expectedEndMs == null || expectedEndMs <= 0L) return realExitMs
        // La sortie réelle reste la trace physique. La sortie comptée reste l'heure de fin
        // du profil lorsqu'elle est atteinte : un dépassement ne devient pas implicitement
        // du temps compté. Les heures supplémentaires doivent être qualifiées séparément.
        return if (realExitMs >= expectedEndMs) expectedEndMs else realExitMs
    }

    override fun calculate(session: WorkSessionV2, nowMs: Long): TimeResultV2 {
        val warnings = mutableListOf<String>()

        val realStart = session.realArrivalMs
        val realEnd = session.realExitMs ?: if (session.status.name == "OPEN") nowMs else null
        val presenceMs = validDuration(realStart, realEnd).also {
            if (realStart == null) warnings += "Arrivée réelle manquante"
            if (realEnd == null) warnings += "Sortie réelle manquante"
        }

        val countedStart = session.countedEntryMs
        val countedEnd = session.countedExitMs ?: if (session.status.name == "OPEN") nowMs else null
        val countedSpanMs = validDuration(countedStart, countedEnd).also {
            if (countedStart == null) warnings += "Entrée comptée manquante"
            if (countedEnd == null) warnings += "Sortie comptée manquante"
        }

        if (countedStart == null || countedEnd == null || countedEnd <= countedStart) {
            return TimeResultV2(presenceMs, 0L, 0L, 0L, 0L, warnings.distinct())
        }

        val confirmed = session.pauses.filter { it.status == DecisionStatusV2.CONFIRMED }
        val unresolved = session.pauses.count { it.status == DecisionStatusV2.TO_CONFIRM || it.paid == null }
        if (unresolved > 0) warnings += "$unresolved pause(s) à confirmer"

        val unpaidIntervals = mergeIntervals(
            confirmed.filter { it.paid == false }.mapNotNull { clippedPause(it, countedStart, countedEnd, nowMs) }
        )
        val paidIntervals = mergeIntervals(
            confirmed.filter { it.paid == true }.mapNotNull { clippedPause(it, countedStart, countedEnd, nowMs) }
        )

        val explicitUnpaidMs = duration(unpaidIntervals)
        val importedFixedMs = session.legacyFixedUnpaidPauseMs.coerceAtLeast(0L)
        if (importedFixedMs > 0L) warnings += "Déduction fixe historique importée"
        val unpaidPauseMs = (explicitUnpaidMs + importedFixedMs).coerceAtMost(countedSpanMs)

        val paidPauseMs = (duration(paidIntervals) - overlapDuration(paidIntervals, unpaidIntervals))
            .coerceAtLeast(0L)
            .coerceAtMost(countedSpanMs - unpaidPauseMs)

        return TimeResultV2(
            presenceMs = presenceMs,
            countedSpanMs = countedSpanMs,
            paidWorkMs = (countedSpanMs - unpaidPauseMs).coerceAtLeast(0L),
            unpaidPauseMs = unpaidPauseMs,
            paidPauseMs = paidPauseMs,
            warnings = warnings.distinct()
        )
    }

    private fun validDuration(start: Long?, end: Long?): Long {
        if (start == null || end == null || start <= 0L || end <= start) return 0L
        return end - start
    }

    private fun clippedPause(pause: PauseV2, rangeStart: Long, rangeEnd: Long, nowMs: Long): Pair<Long, Long>? {
        val pauseEnd = pause.endMs ?: nowMs
        val start = maxOf(pause.startMs, rangeStart)
        val end = minOf(pauseEnd, rangeEnd)
        return if (pause.startMs > 0L && end > start) start to end else null
    }

    private fun mergeIntervals(input: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (input.isEmpty()) return emptyList()
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

    private fun duration(intervals: List<Pair<Long, Long>>): Long =
        intervals.sumOf { (start, end) -> (end - start).coerceAtLeast(0L) }

    private fun overlapDuration(a: List<Pair<Long, Long>>, b: List<Pair<Long, Long>>): Long {
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
}
