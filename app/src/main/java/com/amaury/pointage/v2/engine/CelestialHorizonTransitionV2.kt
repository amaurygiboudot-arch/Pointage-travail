package com.amaury.pointage.v2.engine

/**
 * Transition visuelle continue au lever/coucher.
 *
 * La géométrie des astres reste inchangée : aucun disque n'est déplacé au-dessus
 * de l'horizon. Le Soleil peut produire une lueur de crépuscule lorsqu'il est
 * encore sous l'horizon, puis Soleil et Lune gagnent progressivement leur
 * opacité et leur taille après le seuil standard du disque.
 */
object CelestialHorizonTransitionV2 {
    const val CIVIL_TWILIGHT_START_DEG = -6.0
    const val DISK_FULLY_VISIBLE_DEG = 2.0
    const val SUN_GLOW_END_DEG = 4.0

    val diskHorizonDeg: Double
        get() = AtmosphericRefractionV2.STANDARD_SOLAR_DISK_HORIZON_DEG

    fun diskAlpha(altitudeDeg: Double): Double {
        if (!altitudeDeg.isFinite()) return 0.0
        return smoothStep(diskHorizonDeg, DISK_FULLY_VISIBLE_DEG, altitudeDeg)
    }

    fun diskScale(altitudeDeg: Double): Double =
        0.82 + 0.18 * diskAlpha(altitudeDeg)

    fun sunGlowAlpha(altitudeDeg: Double): Double {
        if (!altitudeDeg.isFinite()) return 0.0
        val beforeRise = smoothStep(CIVIL_TWILIGHT_START_DEG, diskHorizonDeg, altitudeDeg)
        val afterRiseFade = 1.0 - smoothStep(diskHorizonDeg, SUN_GLOW_END_DEG, altitudeDeg)
        return (beforeRise * afterRiseFade).coerceIn(0.0, 1.0)
    }

    fun altitudeForHorizonGlow(altitudeDeg: Double): Double =
        altitudeDeg.coerceAtLeast(diskHorizonDeg)

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge1 <= edge0) return if (value >= edge1) 1.0 else 0.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
