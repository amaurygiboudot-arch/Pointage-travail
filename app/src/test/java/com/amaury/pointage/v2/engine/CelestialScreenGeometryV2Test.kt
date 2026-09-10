package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialScreenGeometryV2Test {

    private fun body(az: Double, alt: Double) = CelestialBodyV2(
        azimuthDeg = az,
        altitudeDeg = alt,
        distanceKm = 1.0,
        apparentScale = 1.0
    )

    @Test
    fun `astre a horizon nord reste sur bord du dome`() {
        val point = CelestialScreenGeometryV2.projectOnWatchDome(
            body = body(0.0, 0.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(point)
        assertEquals(1.0, point!!.radialFraction, 1e-9)
        assertEquals(0.0, point.xRadiusFraction, 1e-9)
        assertEquals(-1.0, point.yRadiusFraction, 1e-9)
    }

    @Test
    fun `astre au zenith rejoint rayon interne sans masquer centre`() {
        val point = CelestialScreenGeometryV2.projectOnWatchDome(
            body = body(120.0, 90.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(point)
        assertEquals(CelestialScreenGeometryV2.ZENITH_RADIUS_FRACTION, point!!.radialFraction, 1e-9)
    }

    @Test
    fun `altitude intermediaire rapproche progressivement astre du centre`() {
        val horizon = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 0.0), 0f)!!
        val middle = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 45.0), 0f)!!
        val high = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 80.0), 0f)!!
        assertTrue(horizon.radialFraction > middle.radialFraction)
        assertTrue(middle.radialFraction > high.radialFraction)
    }

    @Test
    fun `astre sous horizon civil nest pas dessine`() {
        assertNull(
            CelestialScreenGeometryV2.projectOnWatchDome(
                body = body(180.0, -1.0),
                deviceAzimuthDeg = 0f
            )
        )
    }

    @Test
    fun `rotation telephone tourne projection autour cadran`() {
        val northWhenNorth = CelestialScreenGeometryV2.projectOnWatchDome(body(0.0, 0.0), 0f)!!
        val northWhenEast = CelestialScreenGeometryV2.projectOnWatchDome(body(0.0, 0.0), 90f)!!
        assertTrue(northWhenNorth.yRadiusFraction < -0.99)
        assertTrue(northWhenEast.xRadiusFraction < -0.99)
    }

    @Test
    fun `soleil a est depuis lune nord eclaire vers droite`() {
        val direction = CelestialScreenGeometryV2.directionToward(
            from = body(0.0, 0.0),
            to = body(90.0, 0.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(direction)
        assertTrue(direction!!.x > 0.99)
        assertTrue(kotlin.math.abs(direction.y) < 0.01)
    }

    @Test
    fun `soleil plus haut depuis meme azimut eclaire vers centre du cadran`() {
        val direction = CelestialScreenGeometryV2.directionToward(
            from = body(0.0, 0.0),
            to = body(0.0, 45.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(direction)
        assertTrue(kotlin.math.abs(direction!!.x) < 0.01)
        assertTrue(direction.y > 0.99)
    }

    @Test
    fun `rotation du telephone tourne aussi la direction ecran`() {
        val direction = CelestialScreenGeometryV2.directionToward(
            from = body(0.0, 0.0),
            to = body(90.0, 0.0),
            deviceAzimuthDeg = 90f
        )
        assertNotNull(direction)
        assertTrue(kotlin.math.abs(direction!!.x) < 0.01)
        assertTrue(direction.y < -0.99)
    }

    @Test
    fun `axe ombre terrestre pointe vers anti soleil`() {
        val direction = CelestialScreenGeometryV2.directionTowardAntiSun(
            moon = body(170.0, 0.0),
            sun = body(0.0, 0.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(direction)
        assertTrue(direction!!.x < 0.0)
        assertTrue(direction.y > 0.0)
    }
}
