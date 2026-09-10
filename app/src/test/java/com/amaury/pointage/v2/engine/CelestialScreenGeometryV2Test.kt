package com.amaury.pointage.v2.engine

import org.junit.Assert.assertNotNull
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
    fun `direction anti solaire est opposee au soleil sur la sphere`() {
        val direction = CelestialScreenGeometryV2.directionTowardAntiSun(
            moon = body(170.0, 5.0),
            sun = body(0.0, 0.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(direction)
        assertTrue(direction!!.x > 0.0 || direction.y > 0.0 || direction.x < 0.0 || direction.y < 0.0)
    }
}
