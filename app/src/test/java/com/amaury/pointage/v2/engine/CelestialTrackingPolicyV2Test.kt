package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CelestialTrackingPolicyV2Test {
    private val now = 1_800_000_000_000L

    @Test
    fun `position recente et precise est valide`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = true,
            hasLocation = true,
            nowMs = now,
            locationTimeMs = now - 60_000L,
            accuracyMeters = 25f
        )
        assertEquals(CelestialLocationQualityV2.VALID, quality)
    }

    @Test
    fun `position trop ancienne est refusee`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = true,
            hasLocation = true,
            nowMs = now,
            locationTimeMs = now - CelestialTrackingPolicyV2.MAX_LOCATION_AGE_MS - 1L,
            accuracyMeters = 20f
        )
        assertEquals(CelestialLocationQualityV2.STALE, quality)
    }

    @Test
    fun `position trop imprecise est refusee`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = true,
            hasLocation = true,
            nowMs = now,
            locationTimeMs = now,
            accuracyMeters = CelestialTrackingPolicyV2.MAX_LOCATION_ACCURACY_METERS + 1f
        )
        assertEquals(CelestialLocationQualityV2.INACCURATE, quality)
    }

    @Test
    fun `precision inconnue est refusee`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = true,
            hasLocation = true,
            nowMs = now,
            locationTimeMs = now,
            accuracyMeters = null
        )
        assertEquals(CelestialLocationQualityV2.INACCURATE, quality)
    }

    @Test
    fun `absence de permission est explicite`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = false,
            hasLocation = false,
            nowMs = now,
            locationTimeMs = null,
            accuracyMeters = null
        )
        assertEquals(CelestialLocationQualityV2.NO_PERMISSION, quality)
    }
}
