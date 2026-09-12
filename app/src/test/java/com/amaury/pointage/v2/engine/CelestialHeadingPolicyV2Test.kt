package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialHeadingPolicyV2Test {

    @Test
    fun `orientation absente est indisponible`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = false,
            headingAgeMs = null,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = null
        )
        assertEquals(CelestialHeadingQualityV2.UNAVAILABLE, quality)
        assertFalse(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `cap recent et precis est valide`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = 120L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = 3f
        )
        assertEquals(CelestialHeadingQualityV2.VALID, quality)
        assertTrue(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `precision numerique absente reste exploitable sans alarme capteur`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = 80L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = null
        )
        assertEquals(CelestialHeadingQualityV2.UNKNOWN_ACCURACY, quality)
        assertTrue(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `capteur explicitement non fiable est bloque`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = 100L,
            sensorReportedUnreliable = true,
            headingAccuracyDeg = 2f
        )
        assertEquals(CelestialHeadingQualityV2.UNRELIABLE, quality)
        assertFalse(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `incertitude trop grande est bloquee`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = 100L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = CelestialHeadingPolicyV2.MAX_HEADING_ACCURACY_DEG + 0.1f
        )
        assertEquals(CelestialHeadingQualityV2.INACCURATE, quality)
        assertFalse(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `cap trop ancien est bloque`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = CelestialHeadingPolicyV2.MAX_HEADING_AGE_MS + 1L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = 1f
        )
        assertEquals(CelestialHeadingQualityV2.STALE, quality)
        assertFalse(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `age futur impossible est bloque`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = -1L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = 1f
        )
        assertEquals(CelestialHeadingQualityV2.STALE, quality)
        assertFalse(CelestialHeadingPolicyV2.isUsable(quality))
    }

    @Test
    fun `seuil de precision est inclus`() {
        val quality = CelestialHeadingPolicyV2.classify(
            hasOrientation = true,
            headingAgeMs = 0L,
            sensorReportedUnreliable = false,
            headingAccuracyDeg = CelestialHeadingPolicyV2.MAX_HEADING_ACCURACY_DEG
        )
        assertEquals(CelestialHeadingQualityV2.VALID, quality)
    }
}
