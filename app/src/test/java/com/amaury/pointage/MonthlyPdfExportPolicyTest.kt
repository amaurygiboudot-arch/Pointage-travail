package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.model.WorkSessionV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.EventSourceV2

class MonthlyPdfExportPolicyTest {
    private val hash = "a".repeat(64)
    @Test fun closedReportDurationDoesNotDependOnCurrentTime() {
        val session = WorkSessionV2("stable", null, 100L, 100L, 200L, 200L, status = SessionStatusV2.CLOSED)
        assertEquals(DefaultTimeEngineV2.calculate(session, 300L), DefaultTimeEngineV2.calculate(session, 900L))
    }
    @Test fun dynamicOpenSessionsAndPausesAreRejected() {
        val closed = WorkSessionV2("stable", null, 100L, 100L, 1000L, 1000L, status = SessionStatusV2.CLOSED)
        val open = closed.copy(status = SessionStatusV2.OPEN)
        val openPause = closed.copy(pauses = listOf(PauseV2(150L, null, false, EventSourceV2.MANUAL)))
        fun eligible(s: WorkSessionV2) = MonthlyPdfExportPolicy.stableSession(
            s.status == SessionStatusV2.CLOSED, s.realArrivalMs, s.countedEntryMs,
            s.realExitMs, s.countedExitMs, s.pauses.all { it.endMs != null },
            DefaultTimeEngineV2.calculate(s, s.realExitMs ?: 0L).reliable
        )
        assertTrue(DefaultTimeEngineV2.calculate(open, 300L).paidWorkMs != DefaultTimeEngineV2.calculate(open, 900L).paidWorkMs)
        assertTrue(DefaultTimeEngineV2.calculate(openPause, 300L).paidWorkMs != DefaultTimeEngineV2.calculate(openPause, 900L).paidWorkMs)
        assertFalse(eligible(open))
        assertFalse(eligible(openPause))
        assertTrue(eligible(closed))
    }
    @Test fun onlyClosedReliableSessionsHaveStableCachedDurations() {
        assertTrue(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, 200L, 200L, true, true))
        assertFalse(MonthlyPdfExportPolicy.stableSession(false, 100L, 100L, 200L, 200L, true, true))
        assertFalse(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, null, 200L, true, true))
        assertFalse(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, 200L, null, true, true))
        assertFalse(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, 200L, 200L, false, true))
        assertFalse(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, 200L, 200L, true, false))
        assertFalse(MonthlyPdfExportPolicy.stableSession(true, 100L, 100L, 50L, 200L, true, true))
    }
    @Test fun explicitPeriodRequiresBothValidCalendarFields() {
        assertTrue(MonthlyPdfExportPolicy.validPeriod(2026, 0))
        assertTrue(MonthlyPdfExportPolicy.validPeriod(2026, 11))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(2026, -1))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(2026, 12))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(-1, 0))
    }
    @Test fun authorizedBytesForSameAccountCanExport() {
        assertTrue(MonthlyPdfExportPolicy.allows("alice", "alice", hash, hash))
    }
    @Test fun accountChangeOrMissingAccountCannotExport() {
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "bob", hash, hash))
        assertFalse(MonthlyPdfExportPolicy.allows(null, null, hash, hash))
    }
    @Test fun changedBytesOrLostAuthorizationCannotExport() {
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", hash, "b".repeat(64)))
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", null, hash))
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", "invalid", "invalid"))
    }
}
