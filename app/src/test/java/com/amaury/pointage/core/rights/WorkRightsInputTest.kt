package com.amaury.pointage.core.rights

import com.amaury.pointage.core.WorkFactsEngine
import com.amaury.pointage.core.WorkFactsInput
import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkEventSource
import com.amaury.pointage.core.time.WorkEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkRightsInputTest {
    private val minute = 60_000L

    @Test
    fun `le builder transmet les faits sans recalcul juridique`() {
        val events = listOf(
            WorkEvent("entree", WorkEventType.ENTRY, 8 * 60 * minute, WorkEventSource.MANUAL),
            WorkEvent("pause-debut", WorkEventType.PAUSE_START, 12 * 60 * minute, WorkEventSource.MANUAL),
            WorkEvent("pause-fin", WorkEventType.PAUSE_END, 12 * 60 * minute + 30 * minute, WorkEventSource.MANUAL),
            WorkEvent("sortie", WorkEventType.EXIT, 16 * 60 * minute, WorkEventSource.MANUAL)
        )

        val facts = WorkFactsEngine.evaluate(WorkFactsInput(events = events))
        val input = WorkRightsInputBuilder.from(facts)

        assertEquals(events, input.events)
        assertEquals(7 * 60 * minute + 30 * minute, input.workedMs)
        assertEquals(30 * minute, input.pausedMs)
        assertEquals(0L, input.presenceMs)
    }
}
