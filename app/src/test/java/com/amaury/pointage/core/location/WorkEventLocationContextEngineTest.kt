package com.amaury.pointage.core.location

import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkEventSource
import com.amaury.pointage.core.time.WorkEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkEventLocationContextEngineTest {
    private val minute = 60_000L

    @Test
    fun `un pointage conserve exactement son horodatage et sa source`() {
        val event = event("entree", WorkEventType.ENTRY, 8 * 60 * minute + 17_000L)
        val result = WorkEventLocationContextEngine.evaluate(
            events = listOf(event),
            zones = listOf(zone("paris-site")),
            presenceFacts = listOf(ZonePresenceFact("paris-site", 7 * 60 * minute, 17 * 60 * minute))
        ).single()

        assertEquals(event, result.event)
        assertEquals(8 * 60 * minute + 17_000L, result.event.occurredAtMs)
        assertEquals(WorkEventSource.MANUAL, result.event.source)
        assertEquals(listOf("paris-site"), result.matchingZoneIds)
    }

    @Test
    fun `un pointage hors zone reste valide sans contexte gps`() {
        val event = event("sortie", WorkEventType.EXIT, 18 * 60 * minute)
        val result = WorkEventLocationContextEngine.evaluate(
            events = listOf(event),
            zones = listOf(zone("paris-site")),
            presenceFacts = listOf(ZonePresenceFact("paris-site", 8 * 60 * minute, 17 * 60 * minute))
        ).single()

        assertEquals(event, result.event)
        assertFalse(result.hasGpsContext)
    }

    @Test
    fun `un pointage peut avoir plusieurs zones imbriquees comme contexte`() {
        val site = zone("paris-site")
        val office = zone("paris-bureau", WorkplaceZoneKind.OFFICE, "paris-site")
        val event = event("pause", WorkEventType.PAUSE_START, 12 * 60 * minute)

        val result = WorkEventLocationContextEngine.evaluate(
            events = listOf(event),
            zones = listOf(site, office),
            presenceFacts = listOf(
                ZonePresenceFact("paris-site", 8 * 60 * minute, 17 * 60 * minute),
                ZonePresenceFact("paris-bureau", 9 * 60 * minute, 16 * 60 * minute)
            )
        ).single()

        assertTrue(result.hasGpsContext)
        assertEquals(listOf("paris-bureau", "paris-site"), result.matchingZoneIds)
    }

    private fun event(id: String, type: WorkEventType, at: Long) = WorkEvent(
        id = id,
        type = type,
        occurredAtMs = at,
        source = WorkEventSource.MANUAL
    )

    private fun zone(
        id: String,
        kind: WorkplaceZoneKind = WorkplaceZoneKind.SITE,
        parent: String? = null
    ) = WorkplaceZone(
        id = id,
        name = id,
        kind = kind,
        geometry = WorkplaceGeometry.Circle(GeoPoint(48.8566, 2.3522), 100.0),
        parentZoneId = parent
    )
}
