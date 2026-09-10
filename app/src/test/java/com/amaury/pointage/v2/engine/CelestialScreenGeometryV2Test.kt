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

    // Repère droit physiquement cohérent : écran vertical, normale vers le Nord,
    // haut d'écran vers le bas du monde après bascule depuis la position à plat.
    private val uprightFacingNorth = CelestialDeviceFrameV2(
        rightEast = 1.0,
        rightNorth = 0.0,
        rightUp = 0.0,
        topEast = 0.0,
        topNorth = 0.0,
        topUp = -1.0,
        normalEast = 0.0,
        normalNorth = 1.0,
        normalUp = 0.0
    )

    private val uprightFacingEast = CelestialDeviceFrameV2(
        rightEast = 0.0,
        rightNorth = -1.0,
        rightUp = 0.0,
        topEast = 0.0,
        topNorth = 0.0,
        topUp = -1.0,
        normalEast = 1.0,
        normalNorth = 0.0,
        normalUp = 0.0
    )

    @Test
    fun `carte terre centree place nord en haut et est a droite`() {
        val north = CelestialScreenGeometryV2.projectEarthCenteredSky(body(0.0, 0.0), 0f)
        val east = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, 0.0), 0f)

        assertNotNull(north)
        assertNotNull(east)
        assertEquals(0.0, north!!.xRadiusFraction, 1e-9)
        assertTrue(north.yRadiusFraction < -0.99)
        assertTrue(east!!.xRadiusFraction > 0.99)
        assertEquals(0.0, east.yRadiusFraction, 1e-9)
    }

    @Test
    fun `astre oppose au cap reste visible sur carte 360`() {
        val south = CelestialScreenGeometryV2.projectEarthCenteredSky(body(180.0, 0.0), 0f)
        assertNotNull(south)
        assertEquals(0.0, south!!.xRadiusFraction, 1e-9)
        assertTrue(south.yRadiusFraction > 0.99)
    }

    @Test
    fun `compatibilite frame ne masque plus astre derriere telephone`() {
        val south = CelestialScreenGeometryV2.projectInDeviceSky(
            body = body(180.0, 0.0),
            frame = uprightFacingNorth
        )
        assertNotNull(south)
        assertTrue(south!!.yRadiusFraction > 0.99)
    }

    @Test
    fun `cap stabilise du tracker prime sur azimut brut du frame`() {
        val rawNorthButFilteredEast = flatFacingNorth.copy(stabilizedHeadingDeg = 90.0)

        assertEquals(90.0, CelestialScreenGeometryV2.headingFromFrame(rawNorthButFilteredEast), 1e-9)
        val north = CelestialScreenGeometryV2.projectInDeviceSky(
            body = body(0.0, 0.0),
            frame = rawNorthButFilteredEast
        )
        assertNotNull(north)
        assertTrue(north!!.xRadiusFraction < -0.99)
    }

    @Test
    fun `cap est stable de plat a vertical vers nord`() {
        val c = kotlin.math.sqrt(0.5)
        val halfTilt = CelestialDeviceFrameV2(
            rightEast = 1.0,
            rightNorth = 0.0,
            rightUp = 0.0,
            topEast = 0.0,
            topNorth = c,
            topUp = -c,
            normalEast = 0.0,
            normalNorth = c,
            normalUp = c
        )

        assertEquals(0.0, CelestialScreenGeometryV2.headingFromFrame(flatFacingNorth), 1e-9)
        assertEquals(0.0, CelestialScreenGeometryV2.headingFromFrame(halfTilt), 1e-9)
        assertEquals(0.0, CelestialScreenGeometryV2.headingFromFrame(uprightFacingNorth), 1e-9)
    }

    @Test
    fun `incliner dans lautre sens ne retourne pas le ciel de 180 degres`() {
        val c = kotlin.math.sqrt(0.5)
        val halfTiltTowardUser = CelestialDeviceFrameV2(
            rightEast = 1.0,
            rightNorth = 0.0,
            rightUp = 0.0,
            topEast = 0.0,
            topNorth = c,
            topUp = c,
            normalEast = 0.0,
            normalNorth = -c,
            normalUp = c
        )
        val uprightFacingSouth = CelestialDeviceFrameV2(
            rightEast = 1.0,
            rightNorth = 0.0,
            rightUp = 0.0,
            topEast = 0.0,
            topNorth = 0.0,
            topUp = 1.0,
            normalEast = 0.0,
            normalNorth = -1.0,
            normalUp = 0.0
        )

        assertEquals(0.0, CelestialScreenGeometryV2.headingFromFrame(halfTiltTowardUser), 1e-9)
        assertEquals(0.0, CelestialScreenGeometryV2.headingFromFrame(uprightFacingSouth), 1e-9)
    }

    @Test
    fun `cap est deduit de axe droit pour orientation est`() {
        assertEquals(90.0, CelestialScreenGeometryV2.headingFromFrame(uprightFacingEast), 1e-9)
    }

    @Test
    fun `rotation vers est tourne ciel autour terre`() {
        val northWhenFacingNorth = CelestialScreenGeometryV2.projectEarthCenteredSky(body(0.0, 0.0), 0f)!!
        val northWhenFacingEast = CelestialScreenGeometryV2.projectEarthCenteredSky(body(0.0, 0.0), 90f)!!

        assertTrue(northWhenFacingNorth.yRadiusFraction < -0.99)
        assertTrue(northWhenFacingEast.xRadiusFraction < -0.99)
    }

    @Test
    fun `altitude rapproche progressivement astre du centre`() {
        val horizon = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, 0.0), 0f)!!
        val middle = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, 45.0), 0f)!!
        val high = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, 80.0), 0f)!!

        assertTrue(horizon.radialFraction > middle.radialFraction)
        assertTrue(middle.radialFraction > high.radialFraction)
    }

    @Test
    fun `refraction remonte legerement astre au voisinage horizon`() {
        val geometricHorizon = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, 0.0), 0f)!!
        assertTrue(geometricHorizon.radialFraction < 1.0)

        val justBelow = CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, -0.5), 0f)
        assertNotNull(justBelow)
        assertTrue(justBelow!!.radialFraction < 1.0)
    }

    @Test
    fun `zenith garde rayon interne pour ne pas masquer terre`() {
        val zenith = CelestialScreenGeometryV2.projectEarthCenteredSky(body(123.0, 90.0), 0f)
        assertNotNull(zenith)
        assertEquals(CelestialScreenGeometryV2.ZENITH_RADIUS_FRACTION, zenith!!.radialFraction, 1e-9)
    }

    @Test
    fun `astre sous seuil standard du disque nest pas dessine`() {
        assertNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, -0.84), 0f))
        assertNotNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0, -0.82), 0f))
    }

    @Test
    fun `terminateur est coherent avec carte terre centree`() {
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
    fun `axe ombre terrestre pointe vers anti soleil`() {
        val direction = CelestialScreenGeometryV2.directionTowardAntiSun(
            moon = body(170.0, 0.0),
            sun = body(0.0, 0.0),
            deviceAzimuthDeg = 0f
        )
        assertNotNull(direction)
        assertTrue(direction!!.x < 0.0)
    }
}
