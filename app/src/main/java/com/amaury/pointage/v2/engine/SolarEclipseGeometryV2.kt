package com.amaury.pointage.v2.engine

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Type d'occultation solaire réellement possible depuis l'observateur. */
enum class SolarEclipseStageV2 { NONE, PARTIAL, ANNULAR, TOTAL }

/**
 * Géométrie apparente Soleil/Lune vue depuis la position topocentrique courante.
 *
 * Les rayons et la séparation sont angulaires. Le rendu peut donc agrandir les
 * symboles pour rester lisible sans transformer une simple proximité graphique
 * en fausse éclipse.
 */
data class SolarEclipseV2(
    val stage: SolarEclipseStageV2,
    val angularSeparationDeg: Double,
    val sunAngularRadiusDeg: Double,
    val moonAngularRadiusDeg: Double,
    /** Fraction de la surface apparente du Soleil occultée, entre 0 et 1. */
    val obscuredFraction: Double
) {
    val isEclipse: Boolean get() = stage != SolarEclipseStageV2.NONE
}

/**
 * Calcul pur de l'occultation solaire à partir des corps déjà produits par V2.
 *
 * La séparation utilise les directions topocentriques affichées par HoraTrack.
 * Le Soleil est suffisamment éloigné pour que sa parallaxe résiduelle soit très
 * faible à l'échelle de ce moteur astronomique léger ; la Lune, elle, est déjà
 * corrigée de la parallaxe par CelestialEngineV2.
 */
object SolarEclipseGeometryV2 {
    private const val SUN_RADIUS_KM = 696_340.0
    private const val MOON_RADIUS_KM = 1_737.4

    fun evaluate(sun: CelestialBodyV2, moon: CelestialBodyV2): SolarEclipseV2 {
        val separationDeg = angularSeparationDeg(sun, moon)
        val sunRadiusDeg = apparentAngularRadiusDeg(SUN_RADIUS_KM, sun.distanceKm)
        val moonRadiusDeg = apparentAngularRadiusDeg(MOON_RADIUS_KM, moon.distanceKm)
        return evaluateDisks(
            angularSeparationDeg = separationDeg,
            sunAngularRadiusDeg = sunRadiusDeg,
            moonAngularRadiusDeg = moonRadiusDeg
        )
    }

    /** Visible pour tests et pour de futurs moteurs d'éphémérides plus précis. */
    fun evaluateDisks(
        angularSeparationDeg: Double,
        sunAngularRadiusDeg: Double,
        moonAngularRadiusDeg: Double
    ): SolarEclipseV2 {
        require(angularSeparationDeg >= 0.0 && angularSeparationDeg.isFinite())
        require(sunAngularRadiusDeg > 0.0 && sunAngularRadiusDeg.isFinite())
        require(moonAngularRadiusDeg > 0.0 && moonAngularRadiusDeg.isFinite())

        val d = angularSeparationDeg
        val rs = sunAngularRadiusDeg
        val rm = moonAngularRadiusDeg
        val overlap = circleOverlapArea(rs, rm, d)
        val obscured = (overlap / (PI * rs * rs)).coerceIn(0.0, 1.0)

        val stage = when {
            d >= rs + rm -> SolarEclipseStageV2.NONE
            d <= kotlin.math.abs(rm - rs) && rm >= rs -> SolarEclipseStageV2.TOTAL
            d <= kotlin.math.abs(rs - rm) && rm < rs -> SolarEclipseStageV2.ANNULAR
            else -> SolarEclipseStageV2.PARTIAL
        }

        return SolarEclipseV2(
            stage = stage,
            angularSeparationDeg = d,
            sunAngularRadiusDeg = rs,
            moonAngularRadiusDeg = rm,
            obscuredFraction = obscured
        )
    }

    private fun apparentAngularRadiusDeg(radiusKm: Double, distanceKm: Double): Double {
        require(distanceKm > radiusKm && distanceKm.isFinite())
        return Math.toDegrees(asin((radiusKm / distanceKm).coerceIn(0.0, 1.0)))
    }

    private fun angularSeparationDeg(a: CelestialBodyV2, b: CelestialBodyV2): Double {
        val azA = Math.toRadians(a.azimuthDeg)
        val altA = Math.toRadians(a.altitudeDeg)
        val azB = Math.toRadians(b.azimuthDeg)
        val altB = Math.toRadians(b.altitudeDeg)
        val cosine = (
            sin(altA) * sin(altB) +
                cos(altA) * cos(altB) * cos(azA - azB)
            ).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    /** Aire d'intersection de deux disques, dans la même unité au carré. */
    private fun circleOverlapArea(r1: Double, r2: Double, distance: Double): Double {
        if (distance >= r1 + r2) return 0.0
        if (distance <= kotlin.math.abs(r1 - r2)) {
            val smaller = min(r1, r2)
            return PI * smaller * smaller
        }

        val d = max(distance, 1e-12)
        val alpha = acos(((d * d + r1 * r1 - r2 * r2) / (2.0 * d * r1)).coerceIn(-1.0, 1.0))
        val beta = acos(((d * d + r2 * r2 - r1 * r1) / (2.0 * d * r2)).coerceIn(-1.0, 1.0))
        val triangle = 0.5 * sqrt(
            max(
                0.0,
                (-d + r1 + r2) *
                    (d + r1 - r2) *
                    (d - r1 + r2) *
                    (d + r1 + r2)
            )
        )
        return r1 * r1 * alpha + r2 * r2 * beta - triangle
    }
}
