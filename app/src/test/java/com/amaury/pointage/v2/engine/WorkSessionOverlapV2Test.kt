package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSessionOverlapV2Test {
    private val minute = 60_000L

    @Test
    fun `deux sessions du meme employeur qui se chevauchent sont detectees`() {
        val sessions = listOf(
            session("a", "company", 8 * 60 * minute, 10 * 60 * minute),
            session("b", "company", 9 * 60 * minute, 11 * 60 * minute)
        )

        assertTrue(
            WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `deux sessions jointives ne sont pas un chevauchement`() {
        val sessions = listOf(
            session("a", "company", 8 * 60 * minute, 10 * 60 * minute),
            session("b", "company", 10 * 60 * minute, 12 * 60 * minute)
        )

        assertFalse(
            WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `deux employeurs distincts ne contaminent pas leurs totaux respectifs`() {
        val sessions = listOf(
            session("a", "company-a", 8 * 60 * minute, 10 * 60 * minute),
            session("b", "company-b", 9 * 60 * minute, 11 * 60 * minute)
        )

        assertFalse(
            WorkSessionOverlapV2.hasSameEmployerOverlap(
                sessions = sessions,
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `des alias acceptes d une meme entreprise sont controles ensemble`() {
        val sessions = listOf(
            session("a", "company", 8 * 60 * minute, 10 * 60 * minute),
            session("b", "legacy-slot-1", 9 * 60 * minute, 11 * 60 * minute)
        )

        assertTrue(
            WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = sessions,
                acceptedEmployerIds = setOf("company", "legacy-slot-1"),
                rangeStartMs = 0L,
                rangeEndMs = 24 * 60 * minute
            )
        )
    }

    @Test
    fun `un chevauchement entierement hors periode ne bloque pas la periode demandee`() {
        val sessions = listOf(
            session("a", "company", 1 * 60 * minute, 2 * 60 * minute),
            session("b", "company", 90 * minute, 150 * minute)
        )

        assertFalse(
            WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = 8 * 60 * minute,
                rangeEndMs = 18 * 60 * minute
            )
        )
    }

    @Test
    fun `une session ouverte ignore une ancienne sortie pour les periodes futures`() {
        val open = session("open", "company", 8 * 60 * minute, 72 * 60 * minute)
            .copy(status = SessionStatusV2.OPEN)
        val future = session("future", "company", 48 * 60 * minute, 50 * 60 * minute)

        assertFalse(
            WorkSessionOverlapV2.hasOverlapWithinEmployerGroup(
                sessions = listOf(open, future),
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = 48 * 60 * minute,
                rangeEndMs = 72 * 60 * minute,
                openEndMs = 12 * 60 * minute
            )
        )
    }

    private fun session(
        id: String,
        employerId: String,
        start: Long,
        end: Long
    ) = WorkSessionV2(
        id = id,
        employerId = employerId,
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = SessionStatusV2.CLOSED
    )
}
