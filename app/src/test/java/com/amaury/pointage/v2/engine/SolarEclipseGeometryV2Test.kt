package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SolarEclipseGeometryV2Test {

    @Test
    fun `deux disques proches mais separes ne font pas une eclipse`() {
        val eclipse = SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDeg = 0.70,
            sunAngularRadiusDeg = 0.266,
            moonAngularRadiusDeg = 0.272
        )

        assertEquals(SolarEclipseStageV2.NONE, eclipse.stage)
        assertEquals(0.0, eclipse.obscuredFraction, 1e-12)
    }

    @Test
    fun `recouvrement partiel est classe partiel`() {
        val eclipse = SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDeg = 0.30,
            sunAngularRadiusDeg = 0.266,
            moonAngularRadiusDeg = 0.272
        )

        assertEquals(SolarEclipseStageV2.PARTIAL, eclipse.stage)
        assertTrue(eclipse.obscuredFraction in 0.0..1.0)
        assertTrue(eclipse.obscuredFraction > 0.0)
    }

    @Test
    fun `lune plus grande et centree produit totalite`() {
        val eclipse = SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDeg = 0.0,
            sunAngularRadiusDeg = 0.266,
            moonAngularRadiusDeg = 0.275
        )

        assertEquals(SolarEclipseStageV2.TOTAL, eclipse.stage)
        assertEquals(1.0, eclipse.obscuredFraction, 1e-12)
    }

    @Test
    fun `lune plus petite et centree produit anneau`() {
        val eclipse = SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDeg = 0.0,
            sunAngularRadiusDeg = 0.266,
            moonAngularRadiusDeg = 0.250
        )

        assertEquals(SolarEclipseStageV2.ANNULAR, eclipse.stage)
        assertTrue(eclipse.obscuredFraction < 1.0)
        assertTrue(eclipse.obscuredFraction > 0.80)
    }

    @Test
    fun `nouvelle lune hors bande eclipse ne devient pas fausse eclipse`() {
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 51.509,
            longitudeDeg = -0.029,
            timeMs = Instant.parse("2026-02-17T12:16:00Z").toEpochMilli()
        )
        val eclipse = SolarEclipseGeometryV2.evaluate(snapshot.sun, snapshot.moon)

        assertFalse(eclipse.isEclipse)
        assertEquals(SolarEclipseStageV2.NONE, eclipse.stage)
        assertTrue(
            eclipse.angularSeparationDeg >
                eclipse.sunAngularRadiusDeg + eclipse.moonAngularRadiusDeg
        )
    }
}
