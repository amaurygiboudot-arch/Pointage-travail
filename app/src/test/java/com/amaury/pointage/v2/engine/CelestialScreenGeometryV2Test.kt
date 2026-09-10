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

    private val flatFacingNorth = CelestialDeviceFrameV2(
        rightEast = 1.0,
        rightNorth = 0.0,
        rightUp = 0.0,
        topEast = 0.0,
        topNorth = 1.0,
        topUp = 0.0,
        normalEast = 0.0,
        normalNorth = 0.0,
        normalUp = 1.0
    )

    private val uprightFacingNorth = CelestialDeviceFrameV2(
        rightEast = 1.0,
        rightNorth = 0.0,
        rightUp = 0.0,
        topEast = 0.0,
        topNorth = 0.0,
        topUp = 1.0,
        normalEast = 0.0,
        normalNorth = 1.0,
        normalUp = 0.0
    )

    @Test
    fun `telephone a plat projette est a droite et nord en haut`() {
        val east = CelestialScreenGeometryV2.projectInDeviceSky(body(90.0, 0.0), flatFacingNorth)
        val north = CelestialScreenGeometryV2.projectInDeviceSky(body(0.0, 0.0), flatFacingNorth)

        assertNotNull(east)
        assertNotNull(north)
        assertEquals(1.0, east!!.xRadiusFraction, 1e-9)
        assertEquals(0.0, east.yRadiusFraction, 1e-9)
        assertEquals(0.0, north!!.xRadiusFraction, 1e-9)
        assertEquals(-1.0, north.yRadiusFraction, 1e-9)
    }

    @Test
    fun `telephone a plat place zenith au centre reel`() {
        val zenith = CelestialScreenGeometryV2.projectInDeviceSky(body(123.0, 90.0), flatFacingNorth)
        assertNotNull(zenith)
        assertEquals(0.0, zenith!!.radialFraction, 1e-9)
        assertEquals(0.0, zenith.xRadiusFraction, 1e-9)
        assertEquals(0.0, zenith.yRadiusFraction, 1e-9)
    }

    @Test
    fun `telephone vertical face nord centre le vrai horizon nord`() {
        val northHorizon = CelestialScreenGeometryV2.projectInDeviceSky(body(0.0, 0.0), uprightFacingNorth)
        val zenith = CelestialScreenGeometryV2.projectInDeviceSky(body(0.0, 90.0), uprightFacingNorth)

        assertNotNull(northHorizon)
        assertEquals(0.0, northHorizon!!.radialFraction, 1e-9)
        assertNotNull(zenith)
        assertEquals(-1.0, zenith!!.yRadiusFraction, 1e-9)
    }

    @Test
    fun `astre derriere le telephone nest pas invente sur le cadran`() {
        assertNull(
            CelestialScreenGeometryV2.projectInDeviceSky(
                body = body(180.0, 0.0),
                frame = uprightFacingNorth
            )
        )
    }

    @Test
    fun `astre sous horizon civil nest pas dessine en projection 3d`() {
        assertNull(
            CelestialScreenGeometryV2.projectInDeviceSky(
                body = body(90.0, -1.0),
                frame = flatFacingNorth
            )
        )
    }

    @Test
    fun `terminateur utilise axes reels de lecran`() {
        val direction = CelestialScreenGeometryV2.directionToward(
            from = body(0.0, 0.0),
            to = body(90.0, 0.0),
            frame = flatFacingNorth
        )
        assertNotNull(direction)
        assertTrue(direction!!.x > 0.99)
        assertTrue(kotlin.math.abs(direction.y) < 0.01)
    }

    @Test
    fun `astre a horizon nord reste sur bord du dome historique`() {
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
    fun `astre au zenith rejoint rayon interne dans projection historique`() {
        val point = CelestialScreenGeometryV2.projectOnWatchDome(
            body = body(120.0, 90.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(point)
        assertEquals(CelestialScreenGeometryV2.ZENITH_RADIUS_FRACTION, point!!.radialFraction, 1e-9)
    }

    @Test
    fun `altitude intermediaire rapproche progressivement astre dans projection historique`() {
        val horizon = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 0.0), 0f)!!
        val middle = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 45.0), 0f)!!
        val high = CelestialScreenGeometryV2.projectOnWatchDome(body(90.0, 80.0), 0f)!!
        assertTrue(horizon.radialFraction > middle.radialFraction)
        assertTrue(middle.radialFraction > high.radialFraction)
    }

    @Test
    fun `rotation telephone tourne projection historique autour cadran`() {
        val northWhenNorth = CelestialScreenGeometryV2.projectOnWatchDome(body(0.0, 0.0), 0f)!!
        val northWhenEast = CelestialScreenGeometryV2.projectOnWatchDome(body(0.0, 0.0), 90f)!!
        assertTrue(northWhenNorth.yRadiusFraction < -0.99)
        assertTrue(northWhenEast.xRadiusFraction < -0.99)
    }

    @Test
    fun `axe ombre terrestre pointe vers anti soleil`() {
        val direction = CelestialScreenGeometryV2.directionTowardAntiSun(
            moon = body(170.0, 0.0),
            sun = body(0.0, 0.0),
            frame = flatFacingNorth
        )
        assertNotNull(direction)
        assertTrue(direction!!.x < 0.0)
    }
}
