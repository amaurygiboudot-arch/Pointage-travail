package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2

/** Paid time reports must be immutable facts; an open session or pause is never a priced snapshot. */
object ConfirmedWorkPdfPolicyV2 {
    const val WARNING = "Termine ou confirme les pointages et les pauses de la période avant de générer ce PDF."

    fun stableSession(session: WorkSessionV2): Boolean =
        WorkSessionRangeV2.isClosedAndComplete(session) &&
            session.pauses.all { it.endMs != null } &&
            DefaultTimeEngineV2.calculate(session, session.realExitMs!!).reliable

    fun requireStable(sessions: List<WorkSessionV2>) {
        check(sessions.all(::stableSession)) { WARNING }
    }

    fun requireStableForRange(sessions: List<WorkSessionV2>, startMs: Long, endMs: Long, nowMs: Long,
                              acceptedEmployerIds: Set<String>? = null) {
        check(endMs > startMs) { "Période PDF invalide." }
        requireStable(sessions.filter { session ->
            (acceptedEmployerIds == null || session.employerId in acceptedEmployerIds) &&
                (listOfNotNull(session.realArrivalMs, session.countedEntryMs).any {
                    it > 0L && it >= startMs && it < endMs
                } || WorkSessionRangeV2.potentiallyTouches(session, startMs, endMs, nowMs))
        })
    }
}
