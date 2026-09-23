package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarSkyProjectionV2Test {
    private val instant = 1_799_587_200_000L

    @Test
    fun `star on local meridian at observer declination reaches zenith`() {
        val lst = StarSkyProjectionV2.localSiderealDegrees(instant, 0.0)
        val star = BrightStarV2("test", lst, 0.0, 1.0)
        val position = StarSkyProjectionV2.horizontal(star, 0.0, 0.0, instant)
        assertTrue(position.geometricAltitudeDeg > 89.5)
    }

    @Test
    fun `six sidereal hours west places equatorial star on western horizon`() {
        val lst = StarSkyProjectionV2.localSiderealDegrees(instant, 0.0)
        val star = BrightStarV2("test", lst - 90.0, 0.0, 1.0)
        val position = StarSkyProjectionV2.horizontal(star, 0.0, 0.0, instant)
        assertTrue(kotlin.math.abs(position.geometricAltitudeDeg) < 0.6)
        assertTrue(position.azimuthDeg > 260.0 && position.azimuthDeg < 280.0)
    }

    @Test
    fun `device projection rejects stars behind the phone`() {
        val frame = CelestialDeviceFrameV2(
            rightEast = 1.0, rightNorth = 0.0, rightUp = 0.0,
            topEast = 0.0, topNorth = 0.0, topUp = 1.0,
            normalEast = 0.0, normalNorth = 1.0, normalUp = 0.0
        )
        assertNotNull(
            StarSkyProjectionV2.projectToDevice(
                LocalStarPositionV2(0.0, 30.0, 30.0),
                frame
            )
        )
        assertNull(
            StarSkyProjectionV2.projectToDevice(
                LocalStarPositionV2(180.0, 30.0, 30.0),
                frame
            )
        )
    }

    @Test
    fun `star background fades with real solar altitude`() {
        assertEquals(0.0, StarSkyProjectionV2.nightSkyOpacity(-3.0), 1e-12)
        assertTrue(StarSkyProjectionV2.nightSkyOpacity(-8.0) in 0.45..0.55)
        assertEquals(1.0, StarSkyProjectionV2.nightSkyOpacity(-12.0), 1e-12)
    }
}
