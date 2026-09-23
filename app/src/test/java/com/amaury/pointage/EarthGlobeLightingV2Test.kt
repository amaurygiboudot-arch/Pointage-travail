package com.amaury.pointage

import org.junit.Assert.assertTrue
import org.junit.Test

class EarthGlobeLightingV2Test {
    @Test
    fun `sunlit face is visibly brighter than night face`() {
        val night = earthSunBrightnessV2(-1.0, 1.0)
        val twilight = earthSunBrightnessV2(-0.05, 1.0)
        val terminator = earthSunBrightnessV2(0.0, 1.0)
        val daylight = earthSunBrightnessV2(0.5, 1.0)
        val directSun = earthSunBrightnessV2(1.0, 1.0)

        assertTrue(night < twilight)
        assertTrue(twilight < terminator)
        assertTrue(daylight > terminator * 2.0)
        assertTrue(directSun > daylight)
        assertTrue(directSun > 1.0)
    }

    @Test
    fun `limb stays darker without hiding the illuminated hemisphere`() {
        val center = earthSunBrightnessV2(1.0, 1.0)
        val limb = earthSunBrightnessV2(1.0, 0.0)

        assertTrue(center > limb)
        assertTrue(limb > 0.70)
    }
}
