package com.amaury.pointage.core

import com.amaury.pointage.core.location.GeoPoint
import com.amaury.pointage.core.location.WorkplaceGeometry
import com.amaury.pointage.core.location.WorkplaceZone
import com.amaury.pointage.core.location.WorkplaceZoneKind
import com.amaury.pointage.core.location.ZonePresenceFact
import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkEventSource
import com.amaury.pointage.core.time.WorkEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFactsEngineTest {
    private val minute = 60_000L

    @Test
    fun `le gps enrichit le contexte sans modifier le temps pointe`() {
        val events = listOf(
            event("entree", WorkEventType.ENTRY, 8 * 60 * minute),
            event("sortie", WorkEventType.EXIT, 16 * 60 * minute)
        )
        val zone = WorkplaceZone(
            id = "paris-site",
            name = "Paris site",
            kind = WorkplaceZoneKind.SITE,
            geometry = WorkplaceGeometry.Circle(GeoPoint(48.8566, 2.3522), 150.0)
        )

        val result = WorkFactsEngine.evaluate(
            WorkFactsInput(
                events = events,
                zones = listOf(zone),
                presenceFacts = listOf(
                    ZonePresenceFact("paris-site", 7 * 60 * minute + 50 * minute, 16 * 60 * minute + 10 * minute)
                )
            )
        )

        assertEquals(8 * 60 * minute, result.timeline.firstEntryMs)
        assertEquals(16 * 60 * minute, result.timeline.lastExitMs)
        assertEquals(8 * 60 * minute, result.timeline.workedMs)
        assertEquals(events, result.timeline.orderedEvents)
        assertTrue(result.coherenceFindings.isEmpty())
    }

    @Test
    fun `une presence ouverte ne fabrique aucune duree gps fermee`() {
        val event = event("entree", WorkEventType.ENTRY, 9 * 60 * minute)
        val zone = WorkplaceZone(
            id = "paris-site",
            name = "Paris site",
            kind = WorkplaceZoneKind.SITE,
            geometry = WorkplaceGeometry.Circle(GeoPoint(48.8566, 2.3522), 150.0)
        )

        val result = WorkFactsEngine.evaluate(
            WorkFactsInput(
                events = listOf(event),
                zones = listOf(zone),
                presenceFacts = listOf(ZonePresenceFact("paris-site", 8 * 60 * minute, null))
            )
        )

        assertEquals(0L, result.timeline.presenceMs)
        assertEquals(1, result.movementSequences.size)
        assertTrue(result.eventContexts.single().hasGpsContext)
    }

    private fun event(id: String, type: WorkEventType, at: Long) = WorkEvent(
        id = id,
        type = type,
        occurredAtMs = at,
        source = WorkEventSource.MANUAL
    )
}
