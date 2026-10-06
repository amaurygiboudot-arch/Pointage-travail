package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import org.junit.Assert.*
import org.junit.Test

class GpsExitConfirmationPolicyV2Test {
    private val hour = 60L * 60L * 1000L
    private fun session() = WorkSessionV2(
        id = "shift", employerId = "company", realArrivalMs = 8 * hour,
        countedEntryMs = 8 * hour, countedExitMs = null, realExitMs = null,
        pauses = listOf(PauseV2(12 * hour, 13 * hour, false, EventSourceV2.MANUAL)),
        status = SessionStatusV2.OPEN
    )

    @Test fun `GPS delayed seven minutes accepts explicit actual departure and seven paid hours`() {
        val actual = 16 * hour
        val detected = actual + 7 * 60_000L
        assertTrue(GpsExitConfirmationPolicyV2.canConfirm(session(), "shift", detected, actual))
        val closed = session().copy(realExitMs = actual, countedExitMs = actual, status = SessionStatusV2.CLOSED)
        val result = DefaultTimeEngineV2.calculate(closed)
        assertEquals(8 * hour, result.presenceMs)
        assertEquals(7 * hour, result.paidWorkMs)
    }

    @Test fun `confirmation does not invent a planned end when none exists`() {
        val detected = 16 * hour + 7 * 60_000L
        assertEquals(detected, WorkTimePolicyV2.countedExit(detected, null))
        assertEquals(16 * hour, WorkTimePolicyV2.countedExit(detected, 16 * hour))
        val late = 16 * hour + 21 * 60_000L
        assertEquals(late, WorkTimePolicyV2.countedExit(late, 16 * hour))
    }

    @Test fun `stale session future departure and departure before pauses are refused`() {
        val detected = 16 * hour + 7 * 60_000L
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(session(), "other", detected, 16 * hour))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(session(), "shift", detected, detected + 1))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(session(), "shift", detected, 12 * hour))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(session(), "shift", detected, 8 * hour))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(session().copy(status = SessionStatusV2.CLOSED), "shift", detected, 16 * hour))
        val paused = session().copy(pauses = listOf(PauseV2(16 * hour + 60_000L, null, true, EventSourceV2.MANUAL)))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(paused, "shift", detected, 16 * hour))
    }

    @Test fun `overnight departure keeps date of explicit correction`() {
        val night = session().copy(realArrivalMs = 21 * hour, countedEntryMs = 21 * hour, pauses = emptyList())
        assertTrue(GpsExitConfirmationPolicyV2.canConfirm(night, "shift", 30 * hour + 420_000L, 30 * hour))
        assertFalse(GpsExitConfirmationPolicyV2.canConfirm(night, "shift", 30 * hour + 420_000L, 6 * hour))
    }
}
