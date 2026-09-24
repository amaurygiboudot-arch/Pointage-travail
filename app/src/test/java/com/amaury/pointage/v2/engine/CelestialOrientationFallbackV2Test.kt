package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialOrientationFallbackV2Test {
    @Test
    fun invalidHeadingUsesStableNorthInsteadOfInventingDirection() {
        val qualities = listOf(
            CelestialHeadingQualityV2.INACCURATE,
            CelestialHeadingQualityV2.UNRELIABLE,
            CelestialHeadingQualityV2.STALE,
            CelestialHeadingQualityV2.UNAVAILABLE
        )
        for (quality in qualities) {
            assertEquals(
                0.0,
                CelestialHeadingPolicyV2.renderingHeadingDeg(123.0, quality),
                0.0
            )
            assertTrue(CelestialHeadingPolicyV2.usesNeutralNorthMode(quality))
        }
    }

    @Test
    fun qualifiedHeadingIsNormalizedAndPreserved() {
        assertEquals(
            350.0,
            CelestialHeadingPolicyV2.renderingHeadingDeg(
                -10.0,
                CelestialHeadingQualityV2.VALID
            ),
            1e-12
        )
        assertFalse(
            CelestialHeadingPolicyV2.usesNeutralNorthMode(
                CelestialHeadingQualityV2.VALID
            )
        )
    }
}
