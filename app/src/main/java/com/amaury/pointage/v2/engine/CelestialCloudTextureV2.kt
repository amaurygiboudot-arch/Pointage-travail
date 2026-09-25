package com.amaury.pointage.v2.engine

import kotlin.math.floor

/** A bounded, immutable recipe. Colours and density remain a visual interpretation. */
data class CloudTextureRecipeV2(
    val atmosphere: CloudAtmosphereStateV2,
    val weatherType: CelestialWeatherTypeV2,
    val width: Int,
    val height: Int,
    val octaves: Int,
    val seconds: Double,
    val day: Double,
    val twilight: Double,
    val night: Double
)

/** CPU work only on a worker; adapters upload the resulting straight-alpha ARGB pixels. */
object CelestialCloudTextureV2 {
    const val MAX_WIDTH = 128
    const val MAX_HEIGHT = 256

    fun rasterize(
        recipe: CloudTextureRecipeV2,
        cancelled: () -> Boolean = { false }
    ): IntArray? {
        if (recipe.width !in 2..MAX_WIDTH || recipe.height !in 2..MAX_HEIGHT ||
            recipe.octaves !in 1..4 || !recipe.seconds.isFinite() ||
            recipe.seconds !in -1.0e12..1.0e12 ||
            listOf(recipe.day, recipe.twilight, recipe.night).any { !it.isFinite() || it !in 0.0..1.0 } ||
            recipe.day + recipe.twilight + recipe.night <= 0.0) return null
        val a = recipe.atmosphere
        if (CelestialCloudAtmosphereV2.resolve(a.totalCoverage, a.lowCoverage,
                a.midCoverage, a.highCoverage, a.fog, a.visibilityMeters) == null) return null
        val pixels = IntArray(recipe.width * recipe.height)
        val rainy = recipe.weatherType == CelestialWeatherTypeV2.RAIN ||
            recipe.weatherType == CelestialWeatherTypeV2.DRIZZLE
        val storm = recipe.weatherType == CelestialWeatherTypeV2.THUNDERSTORM
        val snow = recipe.weatherType == CelestialWeatherTypeV2.SNOW
        val dayTop = when { storm -> intArrayOf(124, 132, 146); rainy -> intArrayOf(174, 182, 192)
            snow -> intArrayOf(242, 245, 247); else -> intArrayOf(238, 242, 246) }
        val dayBottom = when { storm -> intArrayOf(82, 90, 104); rainy -> intArrayOf(132, 143, 156)
            snow -> intArrayOf(210, 218, 225); else -> intArrayOf(190, 201, 212) }
        val duskChannels = doubleArrayOf(137.0, 129.0, 139.0)
        val nightChannels = doubleArrayOf(30.0, 37.0, 49.0)
        val shadingBand = CloudBandV2(CloudAltitudeV2.UNRESOLVED, 0.5)
        val totalLight = recipe.day + recipe.twilight + recipe.night
        for (y in 0 until recipe.height) {
            if (cancelled()) return null
            val y01 = y.toDouble() / (recipe.height - 1)
            for (x in 0 until recipe.width) {
                val density = CelestialCloudDensityV2.sample(a,
                    x.toDouble() / (recipe.width - 1), y01, recipe.seconds, recipe.octaves) ?: return null
                val alpha = byte(density * 255.0)
                if (alpha == 0) continue
                // Internal shading, never an outlined edge. Night stays dark and subdued.
                // Preserve internal volume even when 100% coverage saturates alpha.
                val interior = CelestialCloudDensityV2.sampleBand(shadingBand,
                    x.toDouble() / (recipe.width - 1), y01, recipe.seconds, 2) ?: return null
                val shade = (0.25 * y01 + 0.25 * density + 0.30 * interior / 0.74).coerceIn(0.0, 1.0)
                fun channel(i: Int): Int {
                    val daylight = dayTop[i] + (dayBottom[i] - dayTop[i]) * shade
                    val dusk = duskChannels[i] - 30.0 * shade
                    val dark = nightChannels[i] - 12.0 * shade
                    return byte((daylight * recipe.day + dusk * recipe.twilight + dark * recipe.night) / totalLight)
                }
                pixels[y * recipe.width + x] = (alpha shl 24) or
                    (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
            }
        }
        return pixels
    }

    private fun byte(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 255)
}
