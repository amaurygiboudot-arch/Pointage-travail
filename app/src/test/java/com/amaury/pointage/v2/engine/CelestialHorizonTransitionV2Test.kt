package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialHorizonTransitionV2Test {
    @Test
    fun `sun glow starts at civil twilight and peaks near sunrise`() {
        assertEquals(0.0, CelestialHorizonTransitionV2.sunGlowAlpha(-6.1), 1e-12)
        val early = CelestialHorizonTransitionV2.sunGlowAlpha(-5.0)
        val late = CelestialHorizonTransitionV2.sunGlowAlpha(-2.0)
        val horizon = CelestialHorizonTransitionV2.sunGlowAlpha(
            CelestialHorizonTransitionV2.diskHorizonDeg
        )

        assertTrue(early > 0.0)
        assertTrue(late > early)
        assertTrue(horizon >= late)
    }

    @Test
    fun `sun and moon disks fade in instead of popping at horizon`() {
        val horizon = CelestialHorizonTransitionV2.diskHorizonDeg
        assertEquals(0.0, CelestialHorizonTransitionV2.diskAlpha(horizon), 1e-12)

        val halfVisible = CelestialHorizonTransitionV2.diskAlpha(
            (horizon + CelestialHorizonTransitionV2.DISK_FULLY_VISIBLE_DEG) / 2.0
        )
        assertTrue(halfVisible in 0.45..0.55)
        assertEquals(
            1.0,
            CelestialHorizonTransitionV2.diskAlpha(
                CelestialHorizonTransitionV2.DISK_FULLY_VISIBLE_DEG
            ),
            1e-12
        )
    }

    @Test
    fun `disk scale follows same smooth transition`() {
        val horizon = CelestialHorizonTransitionV2.diskHorizonDeg
        assertEquals(0.82, CelestialHorizonTransitionV2.diskScale(horizon), 1e-12)
        assertEquals(
            1.0,
            CelestialHorizonTransitionV2.diskScale(
                CelestialHorizonTransitionV2.DISK_FULLY_VISIBLE_DEG
            ),
            1e-12
        )
    }
}
