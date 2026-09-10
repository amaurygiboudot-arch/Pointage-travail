package com.amaury.pointage.v2.engine

/** Qualité de la position utilisée pour afficher le ciel réel. */
enum class CelestialLocationQualityV2 {
    VALID,
    NO_PERMISSION,
    UNAVAILABLE,
    STALE,
    INACCURATE
}

/**
 * Politique fail-closed du suivi céleste V2.
 *
 * Une position trop vieille, trop imprécise ou sans précision connue ne doit
 * jamais servir à inventer un ciel présenté comme réel.
 */
object CelestialTrackingPolicyV2 {
    const val MAX_LOCATION_AGE_MS = 10L * 60_000L
    const val MAX_LOCATION_ACCURACY_METERS = 2_000f
    const val MAX_FUTURE_SKEW_MS = 2L * 60_000L

    /**
     * API canonique quand l'appelant connaît déjà l'âge de la position.
     *
     * Sur Android, cet âge doit de préférence venir de Location.elapsedRealtimeNanos
     * et SystemClock.elapsedRealtimeNanos : contrairement à l'horloge murale, cette
     * base monotone ne saute pas si l'utilisateur ou le réseau corrige l'heure.
     */
    fun classifyAge(
        hasPermission: Boolean,
        hasLocation: Boolean,
        locationAgeMs: Long?,
        accuracyMeters: Float?
    ): CelestialLocationQualityV2 {
        if (!hasPermission) return CelestialLocationQualityV2.NO_PERMISSION
        if (!hasLocation || locationAgeMs == null) {
            return CelestialLocationQualityV2.UNAVAILABLE
        }

        if (locationAgeMs > MAX_LOCATION_AGE_MS || locationAgeMs < -MAX_FUTURE_SKEW_MS) {
            return CelestialLocationQualityV2.STALE
        }

        if (accuracyMeters == null || !accuracyMeters.isFinite() ||
            accuracyMeters < 0f || accuracyMeters > MAX_LOCATION_ACCURACY_METERS
        ) {
            return CelestialLocationQualityV2.INACCURATE
        }

        return CelestialLocationQualityV2.VALID
    }

    /**
     * Compatibilité de l'ancienne API fondée sur deux timestamps muraux.
     * Les nouveaux appelants Android doivent utiliser [classifyAge].
     */
    fun classify(
        hasPermission: Boolean,
        hasLocation: Boolean,
        nowMs: Long,
        locationTimeMs: Long?,
        accuracyMeters: Float?
    ): CelestialLocationQualityV2 {
        val ageMs = locationTimeMs
            ?.takeIf { it > 0L }
            ?.let { nowMs - it }
        return classifyAge(
            hasPermission = hasPermission,
            hasLocation = hasLocation && locationTimeMs != null && locationTimeMs > 0L,
            locationAgeMs = ageMs,
            accuracyMeters = accuracyMeters
        )
    }
}
