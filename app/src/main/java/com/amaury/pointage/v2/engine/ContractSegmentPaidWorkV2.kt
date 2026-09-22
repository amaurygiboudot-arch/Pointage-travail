package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId

/**
 * Temps payé rattaché à une version contractuelle datée.
 *
 * L'allocateur ne crée aucune règle de paie : il découpe seulement les faits de pointage déjà
 * enregistrés selon les bornes calendaires des versions de contrat confirmées. Une pause au statut
 * payé/non payé incertain ou une session ouverte rend le segment non fiable au lieu d'être devinée.
 */
data class ContractSegmentPaidWeekV2(
    val weekYear: Int,
    val weekOfYear: Int,
    val paidMs: Long
)

data class ContractSegmentPaidWorkV2(
    val versionId: String,
    val sourceId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val contract: ContractV2,
    val weeks: List<ContractSegmentPaidWeekV2>,
    val paidMs: Long,
    val completedSessionCount: Int,
    val reliable: Boolean,
    val warnings: List<String>
)

data class ContractSegmentPaidWorkResultV2(
    val segments: List<ContractSegmentPaidWorkV2>,
    val totalPaidMs: Long,
    val reliable: Boolean,
    val warnings: List<String>
)

object ContractSegmentPaidWorkAllocatorV2 {
    const val INVALID_COVERAGE_WARNING =
        "Temps payé segmenté : les segments contractuels ne couvrent pas une période continue ; calcul bloqué."
    const val UNRELIABLE_SOURCE_WARNING =
        "Temps payé segmenté : historique de pointage non fiable ; calcul automatique bloqué."
    const val UNRELIABLE_SESSION_WARNING =
        "Temps payé segmenté : une session ou une pause traversant un segment reste à confirmer."
    const val OVERLAPPING_SESSION_WARNING =
        "Temps payé segmenté : des pointages de la même entreprise se chevauchent ; calcul automatique à confirmer."
    const val UNASSIGNED_EMPLOYER_WARNING =
        "Temps payé segmenté : un pointage de la période n'est rattaché à aucune entreprise ; calcul automatique bloqué."

    fun allocate(
        sessions: List<WorkSessionV2>,
        segments: List<EmploymentContractCoverageSegmentV2>,
        acceptedEmployerIds: Set<String>,
        sourceReliable: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis()
    ): ContractSegmentPaidWorkResultV2 {
        val normalizedIds = acceptedEmployerIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (segments.isEmpty() || normalizedIds.isEmpty() || !continuous(segments)) {
            return ContractSegmentPaidWorkResultV2(
                segments = emptyList(),
                totalPaidMs = 0L,
                reliable = false,
                warnings = listOf(INVALID_COVERAGE_WARNING)
            )
        }

        val sourceWarnings = if (sourceReliable) emptyList() else listOf(UNRELIABLE_SOURCE_WARNING)
        val allocated = segments.map { segment ->
            val startMs = startOfDayMs(segment.startEpochDay, zoneId)
            val endExclusiveMs = startOfDayMs(segment.endEpochDay + 1L, zoneId)
            val weekPaid = linkedMapOf<Pair<Int, Int>, Long>()
            var paidMs = 0L
            var completed = 0
            var segmentReliable = sourceReliable
            var touchedUnreliableSession = false
            val matchingSessions = sessions.filter { it.employerId?.trim() in normalizedIds }
            val overlappingSessions = WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = matchingSessions,
                acceptedEmployerIds = normalizedIds,
                rangeStartMs = startMs,
                rangeEndMs = endExclusiveMs,
                openEndMs = nowMs
            )
            val unassignedEmployerSession = WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                sessions = sessions,
                rangeStartMs = startMs,
                rangeEndMs = endExclusiveMs,
                openEndMs = nowMs
            )
            if (overlappingSessions || unassignedEmployerSession) segmentReliable = false

            matchingSessions.asSequence()
                .forEach { session ->
                    val overlap = PaidWorkAllocationV2.paidOverlapResult(session, startMs, endExclusiveMs)
                    if (!overlap.reliable && potentiallyTouches(session, startMs, endExclusiveMs, nowMs)) {
                        segmentReliable = false
                        touchedUnreliableSession = true
                    }
                    if (overlap.paidMs <= 0L) return@forEach

                    paidMs += overlap.paidMs
                    if (session.realExitMs != null) completed += 1
                    PaidWorkAllocationV2.splitByIsoWeek(session, startMs, endExclusiveMs).forEach { slice ->
                        if (!slice.reliable) {
                            segmentReliable = false
                            touchedUnreliableSession = true
                        }
                        val key = slice.weekYear to slice.weekOfYear
                        weekPaid[key] = weekPaid.getOrDefault(key, 0L) + slice.paidMs
                    }
                }

            val warnings = buildList {
                addAll(sourceWarnings)
                if (touchedUnreliableSession) add(UNRELIABLE_SESSION_WARNING)
                if (overlappingSessions) add(OVERLAPPING_SESSION_WARNING)
                if (unassignedEmployerSession) add(UNASSIGNED_EMPLOYER_WARNING)
            }.distinct()

            ContractSegmentPaidWorkV2(
                versionId = segment.snapshot.versionId,
                sourceId = segment.snapshot.sourceId,
                startEpochDay = segment.startEpochDay,
                endEpochDay = segment.endEpochDay,
                contract = segment.snapshot.contract,
                weeks = weekPaid.entries
                    .sortedWith(compareBy<Map.Entry<Pair<Int, Int>, Long>> { it.key.first }
                        .thenBy { it.key.second })
                    .map { (key, value) -> ContractSegmentPaidWeekV2(key.first, key.second, value) },
                paidMs = paidMs,
                completedSessionCount = completed,
                reliable = segmentReliable,
                warnings = warnings
            )
        }

        val warnings = allocated.flatMap { it.warnings }.distinct()
        return ContractSegmentPaidWorkResultV2(
            segments = allocated,
            totalPaidMs = allocated.sumOf { it.paidMs },
            reliable = sourceReliable && allocated.all { it.reliable },
            warnings = warnings
        )
    }

    private fun continuous(segments: List<EmploymentContractCoverageSegmentV2>): Boolean {
        val sorted = segments.sortedBy { it.startEpochDay }
        if (sorted.any { it.endEpochDay < it.startEpochDay }) return false
        for (index in 1 until sorted.size) {
            val previous = sorted[index - 1]
            val current = sorted[index]
            if (previous.endEpochDay == Long.MAX_VALUE || current.startEpochDay != previous.endEpochDay + 1L) {
                return false
            }
        }
        return true
    }

    private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private fun potentiallyTouches(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        openEndMs: Long
    ): Boolean {
        val start = session.countedEntryMs ?: session.realArrivalMs ?: return false
        if (start >= rangeEndMs) return false
        val end = session.countedExitMs
            ?: session.realExitMs
            ?: openEndMs.takeIf { session.status == SessionStatusV2.OPEN }
        if (end == null || end <= start) return start >= rangeStartMs && start < rangeEndMs
        return end > rangeStartMs
    }
}
