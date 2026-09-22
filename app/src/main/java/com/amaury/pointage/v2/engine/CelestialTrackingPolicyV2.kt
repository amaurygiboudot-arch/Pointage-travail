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
    /**
     * Avec l'acquisition active à 30 s, 5 minutes représentent déjà plusieurs
     * mises à jour manquées. Au-delà, une ancienne position ne doit plus piloter
     * un ciel présenté comme réellement local lorsque l'utilisateur peut se déplacer.
     */
    const val MAX_LOCATION_AGE_MS = 5L * 60_000L

    /**
     * 2 km paraissent larges pour un GPS, mais correspondent à environ 0,018°
     * sur la sphère terrestre. Cette erreur reste inférieure à la résolution utile
     * du cadran et n'explique pas une erreur visible de plusieurs degrés.
     */
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
     * Arbitrage pur entre deux mesures Android déjà converties en âge monotone.
     *
     * Règle essentielle : une nouvelle mesure non qualifiée ne doit jamais écraser
     * une position encore VALID. C'était possible lorsque le tracker choisissait
     * uniquement le timestamp le plus récent parmi plusieurs providers.
     *
     * Entre deux positions VALID, la plus fraîche est préférée ; à âge égal, la
     * meilleure précision horizontale départage. Entre deux positions invalides,
     * on conserve aussi la plus exploitable pour exposer un diagnostic cohérent.
     */
    fun shouldReplaceLocation(
        currentAgeMs: Long?,
        currentAccuracyMeters: Float?,
        candidateAgeMs: Long?,
        candidateAccuracyMeters: Float?
    ): Boolean {
        val currentQuality = classifyAge(
            hasPermission = true,
            hasLocation = currentAgeMs != null,
            locationAgeMs = currentAgeMs,
            accuracyMeters = currentAccuracyMeters
        )
        val candidateQuality = classifyAge(
            hasPermission = true,
            hasLocation = candidateAgeMs != null,
            locationAgeMs = candidateAgeMs,
            accuracyMeters = candidateAccuracyMeters
        )

        val currentValid = currentQuality == CelestialLocationQualityV2.VALID
        val candidateValid = candidateQuality == CelestialLocationQualityV2.VALID
        if (currentValid != candidateValid) return candidateValid

        if (candidateAgeMs == null) return false
        if (currentAgeMs == null) return true
        if (candidateAgeMs != currentAgeMs) return candidateAgeMs < currentAgeMs

        val currentAccuracy = currentAccuracyMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: Float.POSITIVE_INFINITY
        val candidateAccuracy = candidateAccuracyMeters
            ?.takeIf { it.isFinite() && it >= 0f }
            ?: Float.POSITIVE_INFINITY
        return candidateAccuracy < currentAccuracy
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
