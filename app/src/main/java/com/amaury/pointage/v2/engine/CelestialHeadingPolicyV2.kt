package com.amaury.pointage.v2.engine

/** Qualité du cap utilisé pour orienter la carte céleste 360°. */
enum class CelestialHeadingQualityV2 {
    VALID,
    /** Le téléphone fournit une orientation exploitable mais pas d'incertitude numérique. */
    UNKNOWN_ACCURACY,
    /** Android fournit une incertitude numérique trop grande pour un ciel crédible. */
    INACCURATE,
    /** Le capteur Android déclare explicitement son résultat non fiable. */
    UNRELIABLE,
    /** L'orientation n'a pas été rafraîchie depuis trop longtemps. */
    STALE,
    UNAVAILABLE
}

/**
 * Politique fail-closed du cap céleste.
 *
 * Le seuil de 15° est un choix qualité HoraTrack pour le rendu de l'horloge ;
 * ce n'est pas un seuil imposé par Android. Une précision numérique absente ne
 * suffit pas à condamner un appareil : certains capteurs ne publient simplement
 * pas cette métadonnée. En revanche un état explicitement UNRELIABLE, une
 * précision connue trop mauvaise ou un cap périmé ne doivent jamais être
 * présentés comme une direction exacte du ciel.
 */
object CelestialHeadingPolicyV2 {
    const val MAX_HEADING_ACCURACY_DEG = 15f
    const val MAX_HEADING_AGE_MS = 5_000L

    fun classify(
        hasOrientation: Boolean,
        headingAgeMs: Long?,
        sensorReportedUnreliable: Boolean,
        headingAccuracyDeg: Float?
    ): CelestialHeadingQualityV2 {
        if (!hasOrientation || headingAgeMs == null) {
            return CelestialHeadingQualityV2.UNAVAILABLE
        }
        if (headingAgeMs < 0L || headingAgeMs > MAX_HEADING_AGE_MS) {
            return CelestialHeadingQualityV2.STALE
        }
        if (sensorReportedUnreliable) {
            return CelestialHeadingQualityV2.UNRELIABLE
        }
        if (headingAccuracyDeg != null) {
            if (!headingAccuracyDeg.isFinite() || headingAccuracyDeg < 0f) {
                return CelestialHeadingQualityV2.UNRELIABLE
            }
            if (headingAccuracyDeg > MAX_HEADING_ACCURACY_DEG) {
                return CelestialHeadingQualityV2.INACCURATE
            }
            return CelestialHeadingQualityV2.VALID
        }
        return CelestialHeadingQualityV2.UNKNOWN_ACCURACY
    }

    fun isUsable(quality: CelestialHeadingQualityV2): Boolean =
        quality == CelestialHeadingQualityV2.VALID ||
            quality == CelestialHeadingQualityV2.UNKNOWN_ACCURACY
}
