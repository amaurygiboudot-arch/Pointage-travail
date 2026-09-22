package com.amaury.pointage.v2.engine

/** Politique pure du secours accelerometre + magnetometre Android. */
object CelestialSensorFallbackPolicyV2 {
    const val ROTATION_VECTOR_TIMEOUT_MS = 3_000L

    /**
     * Le secours est necessaire si le capteur fusionne est absent, explicitement
     * non fiable, ou s'il n'a plus emis depuis le delai de surveillance.
     */
    fun shouldUseFallback(
        rotationVectorRegistered: Boolean,
        rotationVectorReportedUnreliable: Boolean,
        lastRotationVectorAgeMs: Long?
    ): Boolean {
        if (!rotationVectorRegistered || rotationVectorReportedUnreliable) return true
        return lastRotationVectorAgeMs == null ||
            lastRotationVectorAgeMs > ROTATION_VECTOR_TIMEOUT_MS
    }
}
