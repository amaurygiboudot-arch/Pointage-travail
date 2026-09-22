package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyPaidWorkScopeV2Test {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val rangeStart = day
    private val rangeEnd = 2 * day
    private val now = rangeStart + 12 * 60 * minute

    @Test
    fun `une periode vide reste fiable`() {
        assertTrue(resolve(emptyList()).reliable)
    }

    @Test
    fun `une session fermee complete est selectionnee`() {
        val result = resolve(listOf(session(start = rangeStart + minute, end = rangeStart + 2 * minute)))

        assertTrue(result.reliable)
        assertEquals(1, result.selected.size)
    }

    @Test
    fun `une session complete traversant la borne est allouee`() {
        val crossing = session(start = rangeStart - minute, end = rangeStart + minute)
        val result = resolve(listOf(crossing))

        assertTrue(result.reliable)
        assertEquals(minute, PaidWorkAllocationV2.paidOverlap(crossing, rangeStart, rangeEnd))
    }

    @Test
    fun `les pauses d une session traversante sont allouees au bon mois`() {
        val outsidePause = PauseV2(
            startMs = rangeStart - 2 * minute,
            endMs = rangeStart - minute,
            paid = false,
            source = EventSourceV2.MANUAL
        )
        val insidePause = PauseV2(
            startMs = rangeStart,
            endMs = rangeStart + minute,
            paid = false,
            source = EventSourceV2.MANUAL
        )
        val crossing = session(start = rangeStart - 2 * minute, end = rangeStart + 3 * minute)
            .copy(pauses = listOf(outsidePause, insidePause))
        val result = resolve(listOf(crossing))

        assertTrue(result.reliable)
        assertEquals(minute, result.unpaidPauseMs)
    }

    @Test
    fun `une session ouverte affectee bloque le mois courant`() {
        val result = resolve(listOf(session(start = rangeStart + minute, end = null, status = SessionStatusV2.OPEN)))

        assertFalse(result.reliable)
        assertTrue(result.incompleteSession)
        assertTrue(MonthlyPaidWorkScopeV2.INCOMPLETE_WARNING in result.warnings)
    }

    @Test
    fun `une session ouverte ne contamine pas une periode future`() {
        val open = session(start = rangeStart + minute, end = null, status = SessionStatusV2.OPEN)
        val future = MonthlyPaidWorkScopeV2.resolve(
            sessions = listOf(open),
            acceptedEmployerIds = setOf("company"),
            rangeStartMs = 3 * day,
            rangeEndMs = 4 * day,
            nowMs = now
        )

        assertTrue(future.reliable)
        assertTrue(future.selected.isEmpty())
    }

    @Test
    fun `une session ouverte ignore une ancienne sortie stockee`() {
        val open = session(
            start = rangeStart + minute,
            end = rangeEnd + day,
            status = SessionStatusV2.OPEN
        )
        val future = MonthlyPaidWorkScopeV2.resolve(
            sessions = listOf(open),
            acceptedEmployerIds = setOf("company"),
            rangeStartMs = 3 * day,
            rangeEndMs = 4 * day,
            nowMs = now
        )

        assertTrue(future.reliable)
        assertTrue(future.selected.isEmpty())
    }

    @Test
    fun `une session fermee sans sortie reelle bloque le calcul`() {
        val broken = session(start = rangeStart + minute, end = rangeStart + 2 * minute)
            .copy(realExitMs = null)

        assertFalse(resolve(listOf(broken)).reliable)
    }

    @Test
    fun `une session sans sortie comptee bloque le calcul`() {
        val broken = session(start = rangeStart + minute, end = rangeStart + 2 * minute)
            .copy(countedExitMs = null)

        assertFalse(resolve(listOf(broken)).reliable)
    }

    @Test
    fun `une session sans entree comptee bloque le calcul`() {
        val broken = session(start = rangeStart + minute, end = rangeStart + 2 * minute)
            .copy(countedEntryMs = null)

        assertFalse(resolve(listOf(broken)).reliable)
    }

    @Test
    fun `une session a confirmer bloque le calcul`() {
        val uncertain = session(
            start = rangeStart + minute,
            end = rangeStart + 2 * minute,
            status = SessionStatusV2.TO_CONFIRM
        )

        assertFalse(resolve(listOf(uncertain)).reliable)
    }

    @Test
    fun `une pause au statut inconnu bloque le calcul`() {
        val uncertainPause = PauseV2(
            startMs = rangeStart + 2 * minute,
            endMs = rangeStart + 3 * minute,
            paid = null,
            source = EventSourceV2.MANUAL
        )
        val withPause = session(start = rangeStart + minute, end = rangeStart + 4 * minute)
            .copy(pauses = listOf(uncertainPause))
        val result = resolve(listOf(withPause))

        assertFalse(result.reliable)
        assertFalse(result.allocationReliable)
        assertTrue(MonthlyPaidWorkScopeV2.PAUSE_WARNING in result.warnings)
    }

    @Test
    fun `un chevauchement entre alias bloque le calcul`() {
        val first = session("a", "company", rangeStart + minute, rangeStart + 4 * minute)
        val second = session("b", "legacy", rangeStart + 2 * minute, rangeStart + 5 * minute)
        val result = MonthlyPaidWorkScopeV2.resolve(
            sessions = listOf(first, second),
            acceptedEmployerIds = setOf("company", "legacy"),
            rangeStartMs = rangeStart,
            rangeEndMs = rangeEnd,
            nowMs = now
        )

        assertFalse(result.reliable)
        assertTrue(result.overlappingSessions)
    }

    @Test
    fun `une autre entreprise ne contamine pas le calcul`() {
        val own = session("a", "company", rangeStart + minute, rangeStart + 2 * minute)
        val otherOpen = session("b", "other", rangeStart + minute, null, SessionStatusV2.OPEN)

        assertTrue(resolve(listOf(own, otherOpen)).reliable)
    }

    @Test
    fun `une session sans employeur bloque le calcul`() {
        val unassigned = session("a", null, rangeStart + minute, rangeStart + 2 * minute)
        val result = resolve(listOf(unassigned))

        assertFalse(result.reliable)
        assertTrue(result.unassignedEmployerSession)
    }

    @Test
    fun `des bornes inversees bloquent le calcul`() {
        val invalid = session(start = rangeStart + 3 * minute, end = rangeStart + 2 * minute)

        assertFalse(resolve(listOf(invalid)).reliable)
    }

    @Test
    fun `une sortie comptee invalide ne masque pas la traversee reelle`() {
        val invalid = session(start = rangeStart - minute, end = rangeStart + minute)
            .copy(countedExitMs = rangeStart - 2 * minute)

        assertFalse(resolve(listOf(invalid)).reliable)
    }

    @Test
    fun `une entree apres la periode avec sortie inversee dedans reste detectee`() {
        val invalid = session(start = rangeEnd + minute, end = rangeStart + minute)

        assertFalse(resolve(listOf(invalid)).reliable)
    }

    @Test
    fun `des bornes inversees englobant la periode restent detectees`() {
        val invalid = session(start = rangeEnd + minute, end = rangeStart - minute)

        assertFalse(resolve(listOf(invalid)).reliable)
    }

    private fun resolve(sessions: List<WorkSessionV2>) = MonthlyPaidWorkScopeV2.resolve(
        sessions = sessions,
        acceptedEmployerIds = setOf("company"),
        rangeStartMs = rangeStart,
        rangeEndMs = rangeEnd,
        nowMs = now
    )

    private fun session(
        start: Long,
        end: Long?,
        status: SessionStatusV2 = SessionStatusV2.CLOSED
    ) = session("session-$start", "company", start, end, status)

    private fun session(
        id: String,
        employerId: String?,
        start: Long,
        end: Long?,
        status: SessionStatusV2 = SessionStatusV2.CLOSED
    ) = WorkSessionV2(
        id = id,
        employerId = employerId,
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = status
    )
}
