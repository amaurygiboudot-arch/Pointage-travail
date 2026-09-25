package com.amaury.pointage.v2.engine

import kotlin.math.floor
import kotlin.math.max

/** An unresolved altitude is deliberately not inferred from the total cloud cover. */
enum class CloudAltitudeV2 { LOW, MID, HIGH, UNRESOLVED }

data class CloudBandV2(val altitude: CloudAltitudeV2, val coverage: Double)

/** Qualified weather inputs, not a reconstruction of individual real clouds. */
data class CloudAtmosphereStateV2 internal constructor(
    val totalCoverage: Double,
    val lowCoverage: Double?,
    val midCoverage: Double?,
    val highCoverage: Double?,
    val fog: Boolean,
    val visibilityMeters: Double?
) {
    val bands: List<CloudBandV2> = run {
        val known = listOfNotNull(
            highCoverage?.let { CloudBandV2(CloudAltitudeV2.HIGH, it) },
            midCoverage?.let { CloudBandV2(CloudAltitudeV2.MID, it) },
            lowCoverage?.let { CloudBandV2(CloudAltitudeV2.LOW, it) }
        )
        // An aggregate-only response must not become an invented low cloud.
        known.ifEmpty { listOf(CloudBandV2(CloudAltitudeV2.UNRESOLVED, totalCoverage)) }
    }

    val hasCompleteAltitudeCoverage: Boolean
        get() = lowCoverage != null && midCoverage != null && highCoverage != null
}

object CelestialCloudAtmosphereV2 {
    /** Missing/stale/foreign weather and corrupt percentages are NOT clear sky. */
    fun resolve(
        totalCoverage: Double?,
        lowCoverage: Double? = null,
        midCoverage: Double? = null,
        highCoverage: Double? = null,
        fog: Boolean = false,
        visibilityMeters: Double? = null,
        usable: Boolean = true
    ): CloudAtmosphereStateV2? {
        if (!usable || totalCoverage == null || !validCoverage(totalCoverage)) return null
        if (listOf(lowCoverage, midCoverage, highCoverage).any { it != null && !validCoverage(it) }) return null
        if (visibilityMeters != null && (!visibilityMeters.isFinite() || visibilityMeters < 0.0)) return null
        return CloudAtmosphereStateV2(
            totalCoverage, lowCoverage, midCoverage, highCoverage, fog, visibilityMeters
        )
    }

    private fun validCoverage(value: Double): Boolean = value.isFinite() && value in 0.0..1.0
}

/**
 * Portable, deterministic density kernel for the V5 cloud renderer.
 *
 * Recipes, optical weights and drift are visual interpretations, NOT measured
 * microphysics, cloud positions or upper-air wind. No GPS, clock or platform API
 * is read here. Callers cache textures and supply a continuous time coordinate.
 * x01 is periodic at the panorama seam; y01 goes from zenith/top to horizon/bottom.
 */
object CelestialCloudDensityV2 {
    fun sample(
        state: CloudAtmosphereStateV2?,
        x01: Double,
        y01: Double,
        elapsedSeconds: Double,
        octaves: Int = 4
    ): Double? {
        if (state == null || !validCoordinates(x01, y01, elapsedSeconds, octaves)) return null
        var transmission = 1.0
        for (band in state.bands) {
            val alpha = sampleBand(band, x01, y01, elapsedSeconds, octaves) ?: return null
            transmission *= 1.0 - alpha
        }
        if (!state.hasCompleteAltitudeCoverage && state.bands.none { it.altitude == CloudAltitudeV2.UNRESOLVED }) {
            // A partial profile cannot turn aggregate cloudiness into clear sky.
            // This neutral fallback is graphical only: no missing altitude is filled.
            val aggregate = sampleBand(
                CloudBandV2(CloudAltitudeV2.UNRESOLVED, state.totalCoverage),
                x01, y01, elapsedSeconds, octaves
            ) ?: return null
            transmission = minOf(transmission, 1.0 - aggregate)
        }
        if (state.fog) {
            // Fog is a low continuous veil, even if the total cloud cover is zero.
            val strength = state.visibilityMeters?.let { 1.0 - smooth(100.0, 2_000.0, it) } ?: 0.65
            val veil = (0.15 + 0.70 * strength) * smooth(0.20, 1.0, y01)
            transmission *= 1.0 - veil
        }
        return (1.0 - transmission).coerceIn(0.0, 1.0)
    }

    fun sampleBand(
        band: CloudBandV2,
        x01: Double,
        y01: Double,
        elapsedSeconds: Double,
        octaves: Int = 4
    ): Double? {
        if (!validCoordinates(x01, y01, elapsedSeconds, octaves) ||
            !band.coverage.isFinite() || band.coverage !in 0.0..1.0) return null
        if (band.coverage == 0.0) return 0.0

        val seed: Int
        val stretch: Double
        val opacity: Double
        val drift: Double
        when (band.altitude) {
            CloudAltitudeV2.HIGH -> { seed = 113; stretch = 9.0; opacity = 0.38; drift = 0.000009 }
            CloudAltitudeV2.MID -> { seed = 227; stretch = 2.8; opacity = 0.70; drift = 0.000012 }
            CloudAltitudeV2.LOW -> { seed = 349; stretch = 1.4; opacity = 0.88; drift = 0.000016 }
            CloudAltitudeV2.UNRESOLVED -> { seed = 491; stretch = 2.0; opacity = 0.74; drift = 0.000012 }
        }
        // No short modulo-time reset: translation and deformation stay continuous.
        val x = fract(x01 + elapsedSeconds * drift)
        val y = y01 * stretch + elapsedSeconds * 0.000003
        val warp = noise(x * 4.0, y * 1.7, 4, seed + 17) - 0.5
        var value = 0.0
        var weight = 0.5
        var totalWeight = 0.0
        var period = 4
        repeat(octaves) {
            value += weight * noise(
                fract(x + warp * 0.08) * period,
                (y + warp * 0.22) * period,
                period,
                seed
            )
            totalWeight += weight
            weight *= 0.5
            period *= 2
        }
        val field = ((value / totalWeight - 0.5) * 1.65 + 0.5).coerceIn(0.0, 1.0)
        val threshold = 1.0 - band.coverage
        val softMask = smooth(threshold - 0.18, threshold + 0.18, field)
        val overcastFloor = 0.70 * smooth(0.85, 1.0, band.coverage)
        return (max(overcastFloor, softMask) * opacity).coerceIn(0.0, 1.0)
    }

    private fun validCoordinates(x: Double, y: Double, seconds: Double, octaves: Int): Boolean =
        x.isFinite() && y.isFinite() && seconds.isFinite() &&
            x in -1.0e12..1.0e12 && y in 0.0..1.0 &&
            seconds in -1.0e12..1.0e12 && octaves in 1..6

    /** Periodic value noise with smooth interpolation and explicit wrapping hash. */
    private fun noise(x: Double, y: Double, period: Int, seed: Int): Double {
        val x0 = floor(x).toInt()
        // Wrapping a coordinate before Int conversion avoids overflow after long uptime.
        val wrappedY = y - floor(y / 1_048_576.0) * 1_048_576.0
        val y0 = floor(wrappedY).toInt()
        val tx = smooth(0.0, 1.0, x - floor(x))
        val ty = smooth(0.0, 1.0, wrappedY - floor(wrappedY))
        fun h(ix: Int, iy: Int): Double = hash((ix % period + period) % period, (iy % 1_048_576 + 1_048_576) % 1_048_576, seed)
        val top = lerp(h(x0, y0), h(x0 + 1, y0), tx)
        val bottom = lerp(h(x0, y0 + 1), h(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty)
    }

    private fun hash(x: Int, y: Int, seed: Int): Double {
        // JVM Int overflow is intentional; Swift uses the same UInt32 wrapping operations.
        var bits = x * 374761393 + y * 668265263 + seed * 982451653
        bits = (bits xor (bits ushr 13)) * 1274126177
        bits = bits xor (bits ushr 16)
        return (bits.toLong() and 0xffffffffL).toDouble() / 4294967295.0
    }

    private fun fract(value: Double): Double = value - floor(value)
    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    private fun smooth(a: Double, b: Double, value: Double): Double {
        val t = ((value - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
