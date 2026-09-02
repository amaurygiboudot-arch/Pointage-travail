package com.amaury.pointage.core.location

import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkEventSource
import com.amaury.pointage.core.time.WorkEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkEventCoherenceEngineTest {
    private val minute = 60_000L

    @Test
    fun `un pointage avec contexte gps ne produit aucune anomalie`() {
        val event = event("entree", WorkEventType.ENTRY, 8 * 60 * minute)
        val findings = WorkEventCoherenceEngine.evaluate(
            listOf(WorkEventLocationContext(event, listOf("paris-site")))
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `un pointage sans gps reste valide et produit seulement une information`() {
        val event = event("sortie", WorkEventType.EXIT, 17 * 60 * minute)
        val findings = WorkEventCoherenceEngine.evaluate(
            listOf(WorkEventLocationContext(event, emptyList()))
        )

        assertEquals(1, findings.size)
        assertEquals(CoherenceLevel.INFO, findings.single().level)
        assertEquals(CoherenceCode.EXIT_OUTSIDE_KNOWN_ZONE, findings.single().code)
        assertEquals(event.id, findings.single().eventId)
        assertEquals(event.occurredAtMs, findings.single().occurredAtMs)
    }

    @Test
    fun `le moteur ne modifie jamais evenement source`() {
        val event = event("pause", WorkEventType.PAUSE_START, 12 * 60 * minute + 12_345L)
        val context = WorkEventLocationContext(event, emptyList())

        WorkEventCoherenceEngine.evaluate(listOf(context))

        assertEquals(event, context.event)
        assertEquals(12 * 60 * minute + 12_345L, context.event.occurredAtMs)
        assertEquals(WorkEventSource.MANUAL, context.event.source)
    }

    private fun event(id: String, type: WorkEventType, at: Long) = WorkEvent(
        id = id,
        type = type,
        occurredAtMs = at,
        source = WorkEventSource.MANUAL
    )
}
