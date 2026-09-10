package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CelestialTrackingPolicyV2Test {
    private val now = 1_800_000_000_000L

    @Test
    fun `age monotone recent et precis est valide`() {
        val quality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = true,
            hasLocation = true,
            locationAgeMs = 60_000L,
            accuracyMeters = 25f
        )
        assertEquals(CelestialLocationQualityV2.VALID, quality)
    }

    @Test
    fun `age monotone trop ancien est refuse`() {
        val quality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = true,
            hasLocation = true,
            locationAgeMs = CelestialTrackingPolicyV2.MAX_LOCATION_AGE_MS + 1L,
            accuracyMeters = 20f
        )
        assertEquals(CelestialLocationQualityV2.STALE, quality)
    }

    @Test
    fun `age monotone anormalement futur est refuse`() {
        val quality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = true,
            hasLocation = true,
            locationAgeMs = -CelestialTrackingPolicyV2.MAX_FUTURE_SKEW_MS - 1L,
            accuracyMeters = 20f
        )
        assertEquals(CelestialLocationQualityV2.STALE, quality)
    }

    @Test
    fun `precision inconnue est refusee avec age monotone`() {
        val quality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = true,
            hasLocation = true,
            locationAgeMs = 0L,
            accuracyMeters = null
        )
        assertEquals(CelestialLocationQualityV2.INACCURATE, quality)
    }

    @Test
    fun `absence de permission reste explicite`() {
        val quality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = false,
            hasLocation = false,
            locationAgeMs = null,
            accuracyMeters = null
        )
        assertEquals(CelestialLocationQualityV2.NO_PERMISSION, quality)
    }

    @Test
    fun `ancienne api murale reste compatible`() {
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
    fun `ancienne api refuse toujours une position trop ancienne`() {
        val quality = CelestialTrackingPolicyV2.classify(
            hasPermission = true,
            hasLocation = true,
            nowMs = now,
            locationTimeMs = now - CelestialTrackingPolicyV2.MAX_LOCATION_AGE_MS - 1L,
            accuracyMeters = 20f
        )
        assertEquals(CelestialLocationQualityV2.STALE, quality)
    }
}
