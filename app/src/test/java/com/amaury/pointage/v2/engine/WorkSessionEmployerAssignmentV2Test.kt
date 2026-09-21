package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSessionEmployerAssignmentV2Test {
    private val minute = 60_000L

    @Test
    fun `une session sans employeur dans la periode est detectee`() {
        assertTrue(
            WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                sessions = listOf(session(null, 8 * 60 * minute, 10 * 60 * minute)),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `un employeur vide est traite comme absent`() {
        assertTrue(
            WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                sessions = listOf(session("   ", 8 * 60 * minute, 10 * 60 * minute)),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `une session sans employeur hors periode ne contamine pas le calcul`() {
        assertFalse(
            WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                sessions = listOf(session(null, 1 * 60 * minute, 2 * 60 * minute)),
                rangeStartMs = 8 * 60 * minute,
                rangeEndMs = 18 * 60 * minute
            )
        )
    }

    @Test
    fun `une session avec employeur explicite reste attribuee`() {
        assertFalse(
            WorkSessionEmployerAssignmentV2.hasUnassignedSession(
                sessions = listOf(session("company", 8 * 60 * minute, 10 * 60 * minute)),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    private fun session(
        employerId: String?,
        start: Long,
        end: Long
    ) = WorkSessionV2(
        id = "session-$start",
        employerId = employerId,
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = SessionStatusV2.CLOSED
    )
}
