package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialSensorFallbackPolicyV2Test {
    @Test
    fun `absence de rotation vector active le secours`() {
        assertTrue(
            CelestialSensorFallbackPolicyV2.shouldUseFallback(
                rotationVectorRegistered = false,
                rotationVectorReportedUnreliable = false,
                lastRotationVectorAgeMs = null
            )
        )
    }

    @Test
    fun `rotation vector silencieux active le secours apres delai`() {
        assertTrue(
            CelestialSensorFallbackPolicyV2.shouldUseFallback(
                rotationVectorRegistered = true,
                rotationVectorReportedUnreliable = false,
                lastRotationVectorAgeMs =
                    CelestialSensorFallbackPolicyV2.ROTATION_VECTOR_TIMEOUT_MS + 1L
            )
        )
    }

    @Test
    fun `rotation vector fiable et frais evite les capteurs en double`() {
        assertFalse(
            CelestialSensorFallbackPolicyV2.shouldUseFallback(
                rotationVectorRegistered = true,
                rotationVectorReportedUnreliable = false,
                lastRotationVectorAgeMs = 500L
            )
        )
    }

    @Test
    fun `rotation vector declare non fiable active le secours immediatement`() {
        assertTrue(
            CelestialSensorFallbackPolicyV2.shouldUseFallback(
                rotationVectorRegistered = true,
                rotationVectorReportedUnreliable = true,
                lastRotationVectorAgeMs = 0L
            )
        )
    }
}
