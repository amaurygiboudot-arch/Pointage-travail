package com.amaury.pointage.v2.engine

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
    val warnings: List<String> = emptyList(),
    /** Faux dès qu'une information nécessaire au temps payé reste inconnue ou à confirmer. */
    val reliable: Boolean = true
)

object DefaultTimeEngineV2 : TimeEngineV2 {
    override fun countedEntryFromRealArrival(realArrivalMs: Long): Long =
        WorkTimePolicyV2.countedEntry(realArrivalMs)

    override fun countedExitFromRealExit(realExitMs: Long, expectedEndMs: Long?): Long =
        WorkTimePolicyV2.countedExit(realExitMs, expectedEndMs)

    override fun calculate(session: WorkSessionV2, nowMs: Long): TimeResultV2 {
        val warnings = mutableListOf<String>()

        val realStart = session.realArrivalMs
        val realEnd = session.realExitMs ?: if (session.status.name == "OPEN") nowMs else null
        val presenceMs = validDuration(realStart, realEnd).also {
            if (realStart == null) warnings += "Arrivée réelle manquante"
            if (realEnd == null) warnings += "Sortie réelle manquante"
        }

        val countedStart = WorkTimePolicyV2.repairKnownCountedEntry(realStart, session.countedEntryMs)
        val countedEnd = session.countedExitMs ?: if (session.status.name == "OPEN") nowMs else null
        val countedSpanMs = validDuration(countedStart, countedEnd).also {
            if (countedStart == null) warnings += "Entrée comptée manquante"
            if (countedEnd == null) warnings += "Sortie comptée manquante"
        }

        if (countedStart == null || countedEnd == null || countedEnd <= countedStart) {
            return TimeResultV2(
                presenceMs = presenceMs,
                countedSpanMs = 0L,
                paidWorkMs = 0L,
                unpaidPauseMs = 0L,
                paidPauseMs = 0L,
                warnings = warnings.distinct(),
                reliable = false
            )
        }

        val pauseResolution = PaidPauseResolutionV2.resolve(
            pauses = session.pauses,
            rangeStartMs = countedStart,
            rangeEndMs = countedEnd,
            openPauseEndMs = nowMs
        )
        if (pauseResolution.unresolvedCount > 0) {
            warnings += "${pauseResolution.unresolvedCount} pause(s) à confirmer : temps payé non fiable"
        }

        val explicitUnpaidMs = PaidPauseResolutionV2.duration(pauseResolution.unpaidIntervals)
        val importedFixedMs = session.legacyFixedUnpaidPauseMs.coerceAtLeast(0L)
        if (importedFixedMs > 0L) warnings += "Déduction fixe historique importée"
        val unpaidPauseMs = (explicitUnpaidMs + importedFixedMs).coerceAtMost(countedSpanMs)

        val explicitPaidMs = (
            PaidPauseResolutionV2.duration(pauseResolution.paidIntervals) -
                PaidPauseResolutionV2.overlapDuration(
                    pauseResolution.paidIntervals,
                    pauseResolution.unpaidIntervals
                )
            ).coerceAtLeast(0L)
        val paidPauseMs = explicitPaidMs.coerceAtMost(countedSpanMs - unpaidPauseMs)

        return TimeResultV2(
            presenceMs = presenceMs,
            countedSpanMs = countedSpanMs,
            paidWorkMs = (countedSpanMs - unpaidPauseMs).coerceAtLeast(0L),
            unpaidPauseMs = unpaidPauseMs,
            paidPauseMs = paidPauseMs,
            warnings = warnings.distinct(),
            reliable = pauseResolution.reliable
        )
    }

    private fun validDuration(start: Long?, end: Long?): Long {
        if (start == null || end == null || start <= 0L || end <= start) return 0L
        return end - start
    }
}
