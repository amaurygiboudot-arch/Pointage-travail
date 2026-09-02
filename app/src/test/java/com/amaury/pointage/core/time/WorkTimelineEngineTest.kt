package com.amaury.pointage.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTimelineEngineTest {
    private val minute = 60_000L

    @Test
    fun `la presence gps ne gonfle jamais le temps travaille`() {
        val input = WorkTimelineInput(
            events = listOf(
                WorkEvent("entry", WorkEventType.ENTRY, 8 * 60 * minute, WorkEventSource.MANUAL),
                WorkEvent("exit", WorkEventType.EXIT, 16 * 60 * minute, WorkEventSource.MANUAL)
            ),
            presence = listOf(
                PresenceInterval("paris-site", 7 * 60 * minute + 50 * minute, 16 * 60 * minute + 15 * minute)
            )
        )

        val result = WorkTimelineEngine.evaluate(input)

        assertEquals(8 * 60 * minute, result.workedMs)
        assertEquals(8 * 60 * minute + 25 * minute, result.presenceMs)
    }

    @Test
    fun `les horodatages reels sont preserves sans arrondi`() {
        val entry = 8 * 60 * minute + 2 * minute + 17_000L
        val exit = 16 * 60 * minute + 3 * minute + 41_000L

        val result = WorkTimelineEngine.evaluate(
            WorkTimelineInput(
                events = listOf(
                    WorkEvent("entry", WorkEventType.ENTRY, entry, WorkEventSource.MANUAL),
                    WorkEvent("exit", WorkEventType.EXIT, exit, WorkEventSource.MANUAL)
                )
            )
        )

        assertEquals(entry, result.firstEntryMs)
        assertEquals(exit, result.lastExitMs)
        assertEquals(exit - entry, result.workedMs)
    }

    @Test
    fun `une pause est separee du travail reel`() {
        val result = WorkTimelineEngine.evaluate(
            WorkTimelineInput(
                events = listOf(
                    WorkEvent("entry", WorkEventType.ENTRY, 8 * 60 * minute, WorkEventSource.MANUAL),
                    WorkEvent("pause-start", WorkEventType.PAUSE_START, 12 * 60 * minute, WorkEventSource.MANUAL),
                    WorkEvent("pause-end", WorkEventType.PAUSE_END, 12 * 60 * minute + 30 * minute, WorkEventSource.MANUAL),
                    WorkEvent("exit", WorkEventType.EXIT, 16 * 60 * minute, WorkEventSource.MANUAL)
                )
            )
        )

        assertEquals(7 * 60 * minute + 30 * minute, result.workedMs)
        assertEquals(30 * minute, result.pausedMs)
    }

    @Test
    fun `une journee ouverte ne fabrique jamais une sortie`() {
        val result = WorkTimelineEngine.evaluate(
            WorkTimelineInput(
                events = listOf(
                    WorkEvent("entry", WorkEventType.ENTRY, 8 * 60 * minute, WorkEventSource.MANUAL)
                )
            )
        )

        assertEquals(null, result.lastExitMs)
        assertEquals(0L, result.workedMs)
        assertTrue(result.warnings.any { it.contains("aucune heure de sortie") })
    }
}
