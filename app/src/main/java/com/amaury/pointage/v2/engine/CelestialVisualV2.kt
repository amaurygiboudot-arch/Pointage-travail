package com.amaury.pointage.v2.engine

import kotlin.math.*

/** Presentation coordinates, NOT a star distance or an astronomical measurement. */
data class CelestialDomePointV2(val x: Double, val y: Double, val depth: Double)

/**
 * Orthographic view of a transparent, inclined celestial hemisphere.
 * Azimuth/altitude remain real. The camera tilt is a presentation choice, not
 * a phone sensor measurement. No rear-hemisphere cull: this is a 360° map.
 */
object CelestialDomeV2 {
    const val HORIZON_DEG = 0.0
    const val CAMERA_ELEVATION_DEG = 35.0
    const val RADIUS_FRACTION = 0.76 // Reserve the existing Sun/Moon icon margins.

    fun project(azimuthDeg: Double, apparentAltitudeDeg: Double, headingDeg: Double): CelestialDomePointV2? {
        if (!azimuthDeg.isFinite() || !headingDeg.isFinite() ||
            !apparentAltitudeDeg.isFinite() || apparentAltitudeDeg !in -90.0..90.0) return null
        val a = Math.toRadians(((azimuthDeg % 360.0) - (headingDeg % 360.0)) % 360.0)
        val h = Math.toRadians(apparentAltitudeDeg)
        val tilt = Math.toRadians(CAMERA_ELEVATION_DEG)
        val east = cos(h) * sin(a)
        val north = cos(h) * cos(a)
        val up = sin(h)
        return CelestialDomePointV2(
            RADIUS_FRACTION * east,
            -RADIUS_FRACTION * (north * sin(tilt) + up * cos(tilt)),
            up * sin(tilt) - north * cos(tilt)
        )
    }

    /** Differential of the same projection for phase/occultation orientation. */
    fun tangent(east: Double, north: Double, up: Double, headingDeg: Double): CelestialDomePointV2? {
        if (!listOf(east, north, up, headingDeg).all { it.isFinite() }) return null
        val a = Math.toRadians(headingDeg % 360.0)
        val tilt = Math.toRadians(CAMERA_ELEVATION_DEG)
        val x = east * cos(a) - north * sin(a)
        val n = east * sin(a) + north * cos(a)
        val y = -(n * sin(tilt) + up * cos(tilt))
        val length = hypot(x, y)
        if (!length.isFinite() || length < 1e-9) return null
        return CelestialDomePointV2(x / length, y / length, up * sin(tilt) - n * cos(tilt))
    }
}

data class CelestialStarStyleV2(
    val radius: Double,
    val haloRadius: Double,
    val coreAlpha: Double,
    val haloAlpha: Double
)

/**
 * One visual policy for Android/iOS. BSC5 visual magnitude already describes
 * apparent brightness; no fictional distance, new stars or colour temperature.
 * Pixel radii, tone curve, palette and shimmer are explicit artistic choices.
 */
object CelestialStarAppearanceV2 {
    const val TWINKLE_MAX_MAGNITUDE = 2.2
    const val RED = 243
    const val GREEN = 247
    const val BLUE = 255

    fun resolve(magnitude: Double, apparentAltitudeDeg: Double, starId: Int,
                elapsedSeconds: Double, visibility: Double, animated: Boolean): CelestialStarStyleV2? {
        if (!magnitude.isFinite() || !apparentAltitudeDeg.isFinite() ||
            apparentAltitudeDeg !in -90.0..90.0 || !elapsedSeconds.isFinite() ||
            !visibility.isFinite() || visibility !in 0.0..1.0) return null
        val level = 10.0.pow(-0.128 * (magnitude.coerceIn(-1.5, 8.0) + 1.5))
        val radius = 0.32 + 1.25 * level.pow(0.8)
        val horizon = smooth(0.0, 2.0, apparentAltitudeDeg)
        val phase = (((starId.toLong() % 4093L) + 4093L) % 4093L).toDouble() / 4093.0
        val shimmer = if (animated && magnitude <= TWINKLE_MAX_MAGNITUDE) {
            val t = elapsedSeconds.coerceIn(-1e12, 1e12)
            val signal = 0.65 * sin(t * (1.7 + phase) + phase * 2.0 * PI) +
                0.35 * sin(t * (2.9 + phase * 0.7) + phase * 11.0)
            val amplitude = 0.04 + 0.08 * (1.0 - smooth(0.0, 45.0, apparentAltitudeDeg))
            1.0 - amplitude * (0.5 + 0.5 * signal)
        } else 1.0
        val alpha = visibility * horizon * shimmer
        return CelestialStarStyleV2(radius, radius * (2.2 + 2.8 * level),
            alpha * (0.16 + 0.82 * level),
            if (magnitude <= 3.0) alpha * (0.025 + 0.24 * level) else 0.0)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
