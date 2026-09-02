package com.amaury.pointage.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkplaceGeometryEngineTest {
    @Test
    fun `un point dans le rayon de Paris est detecte`() {
        val center = GeoPoint(48.8566, 2.3522)
        val zone = WorkplaceZone(
            id = "paris-site",
            name = "Site Paris",
            kind = WorkplaceZoneKind.SITE,
            geometry = WorkplaceGeometry.Circle(center, 150.0)
        )

        assertTrue(WorkplaceGeometryEngine.contains(zone, GeoPoint(48.8567, 2.3523)))
        assertFalse(WorkplaceGeometryEngine.contains(zone, GeoPoint(48.8600, 2.3600)))
    }

    @Test
    fun `le rayon appartient a la zone et non a un reglage global`() {
        val center = GeoPoint(48.8566, 2.3522)
        val small = WorkplaceZone("small", "Petite zone", WorkplaceZoneKind.WORK_AREA, WorkplaceGeometry.Circle(center, 50.0))
        val large = WorkplaceZone("large", "Grande zone", WorkplaceZoneKind.SITE, WorkplaceGeometry.Circle(center, 500.0))
        val point = GeoPoint(48.8590, 2.3522)

        assertFalse(WorkplaceGeometryEngine.contains(small, point))
        assertTrue(WorkplaceGeometryEngine.contains(large, point))
    }

    @Test
    fun `un polygone detecte un point interieur sans fabriquer de travail`() {
        val polygon = WorkplaceGeometry.Polygon(
            listOf(
                GeoPoint(48.8550, 2.3500),
                GeoPoint(48.8550, 2.3550),
                GeoPoint(48.8580, 2.3550),
                GeoPoint(48.8580, 2.3500)
            )
        )
        val zone = WorkplaceZone("paris-building", "Bâtiment", WorkplaceZoneKind.BUILDING, polygon)

        assertTrue(WorkplaceGeometryEngine.contains(zone, GeoPoint(48.8566, 2.3522)))
        assertFalse(WorkplaceGeometryEngine.contains(zone, GeoPoint(48.8600, 2.3522)))
    }

    @Test
    fun `une sous zone peut etre rattachee au site`() {
        val zone = WorkplaceZone(
            id = "paris-office",
            name = "Bureau",
            kind = WorkplaceZoneKind.OFFICE,
            geometry = WorkplaceGeometry.Circle(GeoPoint(48.8566, 2.3522), 30.0),
            parentZoneId = "paris-site"
        )

        assertTrue(zone.parentZoneId == "paris-site")
    }
}
