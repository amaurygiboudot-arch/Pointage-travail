package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import org.junit.Assert.*
import org.junit.Test

class ConfirmedWorkPdfPolicyV2Test {
    private val hour = 3_600_000L
    private val boundary = 24 * hour
    private fun closed() = WorkSessionV2("closed", "company", boundary - 2 * hour, boundary - 2 * hour,
        boundary + 6 * hour, boundary + 6 * hour, status = SessionStatusV2.CLOSED)

    @Test fun openSessionAndOpenPauseCannotBeFrozenForPurchase() {
        val base = closed()
        val open = base.copy(status = SessionStatusV2.OPEN, realExitMs = null, countedExitMs = null)
        assertNotEquals(DefaultTimeEngineV2.calculate(open, boundary).paidWorkMs, DefaultTimeEngineV2.calculate(open, boundary + hour).paidWorkMs)
        assertFalse(ConfirmedWorkPdfPolicyV2.stableSession(open))
        val paused = base.copy(pauses = listOf(PauseV2(boundary - hour, null, false, EventSourceV2.MANUAL)))
        assertFalse(ConfirmedWorkPdfPolicyV2.stableSession(paused))
        assertTrue(runCatching { ConfirmedWorkPdfPolicyV2.requireStable(listOf(paused)) }.isFailure)
    }

    @Test fun confirmedClosedNightAndPaidOrUnpaidPausesRemainStableAcrossTime() {
        for (paid in listOf(true, false)) {
            val session = closed().copy(pauses = listOf(PauseV2(boundary, boundary + hour / 2, paid, EventSourceV2.MANUAL)))
            assertTrue(ConfirmedWorkPdfPolicyV2.stableSession(session))
            assertEquals(DefaultTimeEngineV2.calculate(session, boundary + 7 * hour), DefaultTimeEngineV2.calculate(session, boundary + 100 * hour))
            ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(session), boundary, boundary + 30 * 24 * hour, boundary + 100 * hour)
        }
    }

    @Test fun incompleteUnqualifiedAndIncoherentFactsRemainBlocked() {
        val variants = listOf(closed().copy(status = SessionStatusV2.TO_CONFIRM), closed().copy(realExitMs = null),
            closed().copy(countedExitMs = null), closed().copy(realExitMs = closed().realArrivalMs),
            closed().copy(countedExitMs = closed().countedEntryMs),
            closed().copy(pauses = listOf(PauseV2(boundary, boundary + hour, null, EventSourceV2.MANUAL))))
        variants.forEach { assertFalse(ConfirmedWorkPdfPolicyV2.stableSession(it)) }
    }

    @Test fun rangeAndEmployerSelectionDoNotBlockUnrelatedSessionsButKeepBoundaryOverlap() {
        val open = closed().copy(status = SessionStatusV2.OPEN, realExitMs = null, countedExitMs = null)
        assertTrue(runCatching { ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(open), boundary, boundary + 24 * hour, boundary + hour, setOf("company")) }.isFailure)
        ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(open.copy(employerId = "other")), boundary, boundary + 24 * hour, boundary + hour, setOf("company"))
        ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(open.copy(realArrivalMs = boundary + 48 * hour, countedEntryMs = boundary + 48 * hour)), boundary, boundary + 24 * hour, boundary + 49 * hour)
        ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(closed()), boundary, boundary + 24 * hour, boundary + hour)
    }

    @Test fun futureOpenAnchoredInsideReportPeriodCannotBypassOverlapGuard() {
        val future = closed().copy(realArrivalMs = boundary + 12 * hour, countedEntryMs = boundary + 12 * hour,
            status = SessionStatusV2.OPEN, realExitMs = null, countedExitMs = null)
        assertFalse(WorkSessionRangeV2.potentiallyTouches(future, boundary, boundary + 24 * hour, boundary - hour))
        assertTrue(runCatching { ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(future), boundary,
            boundary + 24 * hour, boundary - hour, setOf("company")) }.isFailure)
        ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(future.copy(employerId = "other")), boundary,
            boundary + 24 * hour, boundary - hour, setOf("company"))
        ConfirmedWorkPdfPolicyV2.requireStableForRange(listOf(future.copy(realArrivalMs = boundary + 48 * hour,
            countedEntryMs = boundary + 48 * hour)), boundary, boundary + 24 * hour, boundary - hour)
    }
}
