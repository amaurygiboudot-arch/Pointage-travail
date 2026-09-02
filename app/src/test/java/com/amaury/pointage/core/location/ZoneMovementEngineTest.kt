package com.amaury.pointage.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneMovementEngineTest {
    private val minute = 60_000L

    @Test
    fun `les sous zones imbriquees forment une sequence ordonnee`() {
        val site = zone("paris-site", WorkplaceZoneKind.SITE)
        val parking = zone("paris-parking", WorkplaceZoneKind.PARKING, "paris-site")
        val building = zone("paris-batiment", WorkplaceZoneKind.BUILDING, "paris-site")
        val workshop = zone("paris-atelier", WorkplaceZoneKind.WORKSHOP, "paris-batiment")

        val result = ZoneMovementEngine.evaluate(
            zones = listOf(site, parking, building, workshop),
            presenceFacts = listOf(
                fact("paris-site", 8 * 60 * minute, 17 * 60 * minute),
                fact("paris-parking", 8 * 60 * minute + minute, 8 * 60 * minute + 5 * minute),
                fact("paris-batiment", 8 * 60 * minute + 5 * minute, 17 * 60 * minute - minute),
                fact("paris-atelier", 8 * 60 * minute + 7 * minute, 16 * 60 * minute + 55 * minute)
            )
        )

        assertEquals(1, result.size)
        assertEquals(
            listOf("paris-site", "paris-parking", "paris-batiment", "paris-atelier"),
            result.single().steps.map { it.zoneId }
        )
    }

    @Test
    fun `deux zones independantes sans continuite restent deux sequences`() {
        val first = zone("paris-site-a", WorkplaceZoneKind.SITE)
        val second = zone("paris-site-b", WorkplaceZoneKind.SITE)

        val result = ZoneMovementEngine.evaluate(
            zones = listOf(first, second),
            presenceFacts = listOf(
                fact("paris-site-a", 8 * 60 * minute, 9 * 60 * minute),
                fact("paris-site-b", 14 * 60 * minute, 15 * 60 * minute)
            )
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `une presence ouverte reste un contexte gps et rien de plus`() {
        val site = zone("paris-site", WorkplaceZoneKind.SITE)
        val result = ZoneMovementEngine.evaluate(
            zones = listOf(site),
            presenceFacts = listOf(fact("paris-site", 9 * 60 * minute, null))
        )

        assertEquals(1, result.size)
        assertEquals(WorkplaceZoneKind.SITE, result.single().steps.single().kind)
        assertNull(result.single().endedAtMs)
    }

    private fun zone(id: String, kind: WorkplaceZoneKind, parent: String? = null) = WorkplaceZone(
        id = id,
        name = id,
        kind = kind,
        geometry = WorkplaceGeometry.Circle(GeoPoint(48.8566, 2.3522), 100.0),
        parentZoneId = parent
    )

    private fun fact(id: String, entered: Long, exited: Long?) =
        ZonePresenceFact(id, entered, exited)
}
