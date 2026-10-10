package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.TimeBasisV2
import com.amaury.pointage.v2.model.TravelClassificationV2
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
        val realEnd = if (session.status == SessionStatusV2.OPEN) {
            nowMs
        } else {
            session.realExitMs
        }
        val presenceMs = validDuration(realStart, realEnd).also {
            if (realStart == null) warnings += "Arrivée réelle manquante"
            if (realEnd == null) warnings += "Sortie réelle manquante"
        }

        val countedStart = WorkTimePolicyV2.repairKnownCountedEntry(realStart, session.countedEntryMs)
        val countedEnd = if (session.status == SessionStatusV2.OPEN) {
            nowMs
        } else {
            session.countedExitMs
        }
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

        val problems = mutableListOf<String>()
        if (session.status != SessionStatusV2.CLOSED) problems += "Session ouverte ou à confirmer : décompte provisoire"
        if (realStart == null || realStart <= 0L || realEnd == null || realEnd <= realStart) {
            problems += "Chronologie réelle absente ou invalide"
        }
        if (session.status == SessionStatusV2.CLOSED &&
            (session.realExitMs == null || session.realExitMs <= (realStart ?: 0L))) {
            problems += "Sortie réelle à confirmer"
        }
        if (realStart != null && realEnd != null && realEnd > realStart) {
            when (session.timeBasis) {
                TimeBasisV2.REAL_FACTS -> if (countedStart != realStart || countedEnd != realEnd) {
                    problems += "Temps réel et temps compté divergent : correction nécessaire"
                }
                TimeBasisV2.LEGACY_UNVERIFIED -> if (countedStart != realStart || countedEnd != realEnd) {
                    problems += "Ancien arrondi non justifié : confirmer la règle applicable avant la paie"
                }
            }
        }
        if (session.legacyFixedUnpaidPauseMs < 0L || session.legacyFixedUnpaidPauseMs > countedSpanMs) {
            problems += "Déduction historique de pause invalide"
        }
        if (session.legacyFixedUnpaidPauseMs > 0L && (session.pauses.isNotEmpty() || session.workSegments.isNotEmpty())) {
            problems += "Déduction historique et pauses explicites : chevauchement impossible à exclure"
        }
        if (session.pauses.any { p ->
                p.startMs <= 0L || p.startMs < (realStart ?: 0L) ||
                    (p.endMs != null && (p.endMs <= p.startMs ||
                        (realEnd != null && p.endMs > realEnd)))
            }) problems += "Pause extérieure ou chronologiquement invalide"
        if (session.travels.any { travel ->
                travel.startMs <= 0L || travel.endMs == null || travel.endMs <= travel.startMs ||
                    travel.startMs < (realStart ?: 0L) ||
                    (realEnd != null && travel.endMs > realEnd) ||
                    (travel.classification != TravelClassificationV2.PAID &&
                        session.workSegments.isEmpty()) || travel.classification == TravelClassificationV2.TO_CONFIRM
            }) problems += "Déplacement à qualifier : présence ne prouve pas un temps payé"

        val pauseResolution = PaidPauseResolutionV2.resolve(
            pauses = session.pauses,
            rangeStartMs = countedStart,
            rangeEndMs = countedEnd,
            openPauseEndMs = nowMs,
            allowOpenPause = session.status == SessionStatusV2.OPEN
        )
        problems += pauseResolution.issues
        if (pauseResolution.unresolvedCount > 0) {
            warnings += "${pauseResolution.unresolvedCount} pause(s) à confirmer : temps payé non fiable"
        }
        if (session.legacyFixedUnpaidPauseMs > 0L) {
            warnings += "Déduction fixe historique importée"
        }

        val segmentResolution = QualifiedWorkSegmentsV2.resolve(session, countedStart, countedEnd)
        problems += segmentResolution.issues
        val eligiblePeriods = segmentResolution.workedIntervals
        val personalTravelOverConfirmedWork = session.travels.any { travel ->
            travel.classification == TravelClassificationV2.PERSONAL &&
                travel.endMs?.let { travelEnd ->
                    eligiblePeriods.any { (workStart, workEnd) ->
                        maxOf(workStart, travel.startMs) < minOf(workEnd, travelEnd)
                    }
                } == true
        }
        if (personalTravelOverConfirmedWork) {
            problems += "Déplacement personnel chevauchant un temps de travail confirmé"
        }
        val eligibleMs = PaidPauseResolutionV2.duration(eligiblePeriods)
        val explicitUnpaidMs = eligiblePeriods.sumOf { (a, b) ->
            PaidPauseResolutionV2.overlapDuration(pauseResolution.unpaidIntervals, a, b)
        }
        val validLegacyFixed = session.legacyFixedUnpaidPauseMs.takeIf {
            it > 0L && it <= countedSpanMs && session.pauses.isEmpty() && session.workSegments.isEmpty()
        } ?: 0L
        val unpaidPauseMs = (explicitUnpaidMs + validLegacyFixed).coerceAtMost(eligibleMs)

        val paidPausesMs = eligiblePeriods.sumOf { (a, b) ->
            PaidPauseResolutionV2.overlapDuration(pauseResolution.paidIntervals, a, b)
        }
        val paidOverlapMs = eligiblePeriods.sumOf { (a, b) ->
            val paidClipped = pauseResolution.paidIntervals.mapNotNull { (start, end) ->
                val clippedStart = maxOf(a, start)
                val clippedEnd = minOf(b, end)
                (clippedStart to clippedEnd).takeIf { clippedEnd > clippedStart }
            }
            PaidPauseResolutionV2.overlapDuration(paidClipped, pauseResolution.unpaidIntervals)
        }
        val paidPauseMs = (paidPausesMs - paidOverlapMs).coerceIn(0L, (eligibleMs - unpaidPauseMs).coerceAtLeast(0L))

        return TimeResultV2(
            presenceMs = presenceMs,
            countedSpanMs = countedSpanMs,
            paidWorkMs = (eligibleMs - unpaidPauseMs).coerceAtLeast(0L),
            unpaidPauseMs = unpaidPauseMs,
            paidPauseMs = paidPauseMs,
            warnings = (warnings + problems).distinct(),
            reliable = problems.isEmpty() && pauseResolution.reliable
        )
    }

    private fun validDuration(start: Long?, end: Long?): Long {
        if (start == null || end == null || start <= 0L || end <= start) return 0L
        return end - start
    }
}
