package com.amaury.pointage.v2.engine

import kotlin.math.tan

/**
 * Réfraction atmosphérique standard utilisée uniquement pour le rendu apparent.
 *
 * Les éphémérides V2 conservent les altitudes géométriques pour les calculs
 * astronomiques (phase, éclipses, jour/nuit). Cette correction sert seulement à
 * rapprocher la position graphique de ce qu'un observateur voit près de l'horizon.
 *
 * Formules par morceaux reprises du modèle public du NOAA Solar Calculator.
 * Elles supposent une atmosphère moyenne : pression, température, humidité et
 * horizon local réel peuvent déplacer légèrement la position apparente.
 */
object AtmosphericRefractionV2 {
    /** Réfraction moyenne adoptée à l'horizon : 34 minutes d'arc. */
    const val STANDARD_HORIZON_REFRACTION_DEG = 34.0 / 60.0

    /**
     * Correction positive en degrés à ajouter à l'altitude géométrique.
     *
     * Pour HoraTrack, aucune extrapolation n'est faite sous -1° : le cadran ne
     * doit pas prétendre connaître les mirages/réfractions anormales très basses.
     */
    fun correctionDeg(geometricAltitudeDeg: Double): Double {
        if (!geometricAltitudeDeg.isFinite()) return 0.0
        val h = geometricAltitudeDeg
        if (h >= 85.0 || h < -1.0) return 0.0

        val correctionArcSeconds = when {
            h > 5.0 -> {
                val t = tan(Math.toRadians(h))
                if (kotlin.math.abs(t) < 1e-12) 0.0 else {
                    val inv = 1.0 / t
                    58.1 * inv - 0.07 * inv * inv * inv +
                        0.000086 * inv * inv * inv * inv * inv
                }
            }

            h >= -0.575 -> {
                1735.0 -
                    518.2 * h +
                    103.4 * h * h -
                    12.79 * h * h * h +
                    0.711 * h * h * h * h
            }

            else -> {
                val t = tan(Math.toRadians(h))
                if (kotlin.math.abs(t) < 1e-12) 0.0 else -20.774 / t
            }
        }

        return (correctionArcSeconds / 3600.0).coerceAtLeast(0.0)
    }

    fun apparentAltitudeDeg(geometricAltitudeDeg: Double): Double =
        geometricAltitudeDeg + correctionDeg(geometricAltitudeDeg)
}
