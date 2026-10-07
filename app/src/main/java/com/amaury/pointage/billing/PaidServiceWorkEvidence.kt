package com.amaury.pointage.billing

import com.amaury.pointage.v2.engine.MonthlyPaidWorkScopeV2
import com.amaury.pointage.v2.engine.PaidWorkAllocationV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Reporting slices use the same selection and paid allocation as canonical monthly payroll. */
object PaidServiceWorkEvidence {
    data class Slice(
        val session: WorkSessionV2,
        val realStartMs: Long?, val realEndMs: Long?, val realPresenceMs: Long,
        val countedStartMs: Long?, val countedEndMs: Long?, val countedSpanMs: Long,
        val paidMs: Long, val unpaidMs: Long, val crossesPeriod: Boolean
    )

    fun resolve(sessions: List<WorkSessionV2>, acceptedEmployerIds: Set<String>, startMs: Long, endMs: Long, nowMs: Long): List<Slice> {
        val scope = MonthlyPaidWorkScopeV2.resolve(sessions, acceptedEmployerIds, startMs, endMs, nowMs)
        check(scope.reliable && scope.selected.isNotEmpty()) {
            "Pointages absents ou preuves de temps non fiables : ${scope.warnings.joinToString(" ; ")}"
        }
        return scope.selected.sortedBy { it.realArrivalMs }.map { session ->
            val realStart = maxOf(session.realArrivalMs!!, startMs)
            val realEnd = minOf(session.realExitMs!!, endMs)
            val realSpan = (realEnd - realStart).coerceAtLeast(0L)
            val countedEntry = WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)!!
            val countedStart = maxOf(countedEntry, startMs)
            val countedEnd = minOf(session.countedExitMs!!, endMs)
            val countedSpan = (countedEnd - countedStart).coerceAtLeast(0L)
            val paid = PaidWorkAllocationV2.paidOverlapResult(session, startMs, endMs)
            check(paid.reliable) { "Répartition du temps payé à confirmer." }
            Slice(session, realStart.takeIf { realSpan > 0L }, realEnd.takeIf { realSpan > 0L }, realSpan,
                countedStart.takeIf { countedSpan > 0L }, countedEnd.takeIf { countedSpan > 0L }, countedSpan,
                paid.paidMs, (countedSpan - paid.paidMs).coerceAtLeast(0L),
                session.realArrivalMs < startMs || session.realExitMs > endMs || countedEntry < startMs || session.countedExitMs > endMs)
        }
    }
}
