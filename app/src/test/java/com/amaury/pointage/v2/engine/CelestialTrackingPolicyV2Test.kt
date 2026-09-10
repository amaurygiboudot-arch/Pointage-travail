package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `mesure recente mais hors tolerance ne remplace pas une position valide`() {
        val replace = CelestialTrackingPolicyV2.shouldReplaceLocation(
            currentAgeMs = 30_000L,
            currentAccuracyMeters = 12f,
            candidateAgeMs = 0L,
            candidateAccuracyMeters = CelestialTrackingPolicyV2.MAX_LOCATION_ACCURACY_METERS + 500f
        )
        assertFalse(replace)
    }

    @Test
    fun `position fraiche valide remplace une ancienne position devenue perimee`() {
        val replace = CelestialTrackingPolicyV2.shouldReplaceLocation(
            currentAgeMs = CelestialTrackingPolicyV2.MAX_LOCATION_AGE_MS + 1L,
            currentAccuracyMeters = 8f,
            candidateAgeMs = 5_000L,
            candidateAccuracyMeters = 900f
        )
        assertTrue(replace)
    }

    @Test
    fun `entre deux positions valides la plus fraiche gagne`() {
        val replace = CelestialTrackingPolicyV2.shouldReplaceLocation(
            currentAgeMs = 40_000L,
            currentAccuracyMeters = 10f,
            candidateAgeMs = 5_000L,
            candidateAccuracyMeters = 1_500f
        )
        assertTrue(replace)
    }

    @Test
    fun `a age egal la meilleure precision gagne`() {
        val replace = CelestialTrackingPolicyV2.shouldReplaceLocation(
            currentAgeMs = 10_000L,
            currentAccuracyMeters = 400f,
            candidateAgeMs = 10_000L,
            candidateAccuracyMeters = 25f
        )
        assertTrue(replace)
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
