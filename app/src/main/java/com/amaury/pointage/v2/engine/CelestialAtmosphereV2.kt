package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import kotlin.math.max

enum class CelestialWeatherTypeV2 {
    CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    RAIN,
    SNOW,
    THUNDERSTORM,
    UNKNOWN
}

data class CelestialAtmosphereStateV2(
    val solarLightLevel: Double,
    val twilightLevel: Double,
    val nightLevel: Double,
    val starsVisibility: Double,
    val constellationsVisibility: Double,
    val sunVisibility: Double,
    val moonVisibility: Double,
    val atmosphereOpacity: Double,
    val cloudCoverage: Double?,
    val weatherType: CelestialWeatherTypeV2,
    val weatherTransmission: Double,
    val ambientStarFactor: Double,
    val ambientMoonFactor: Double
)

/**
 * Propriétaire pur des coefficients atmosphériques de Céleste.
 *
 * L'astronomie reste la vérité de niveau 1. Météo et luminosité ne déplacent
 * jamais un astre : elles ne font qu'atténuer ou renforcer le rendu visuel.
 */
object CelestialAtmosphereV2 {
    fun resolve(
        snapshot: CelestialSnapshotV2,
        weather: CelestialWeatherContextV2.State?,
        ambient: CelestialAmbientLightStateV2
    ): CelestialAtmosphereStateV2 {
        val sunAltitude = snapshot.sun.altitudeDeg
        val solarLight = smoothStep(-6.0, 8.0, sunAltitude)
        val night = 1.0 - smoothStep(-18.0, -6.0, sunAltitude)
        val twilight = (1.0 - max(solarLight, night)).coerceIn(0.0, 1.0)

        val astronomicalStarLevel = 1.0 - smoothStep(-14.0, -4.0, sunAltitude)
        val weatherType = weatherType(weather?.weatherCode)
        val weatherTransmission = weatherTransmission(weather, weatherType)
        val ambientFactor = ambientStarFactor(ambient)
        val ambientMoonFactor = ambientMoonFactor(ambient)

        val stars = (
            astronomicalStarLevel * weatherTransmission * ambientFactor
            ).coerceIn(0.0, 1.0)

        val sunCloudTransmission = weather?.cloudCover
            ?.let { (1.0 - it.coerceIn(0.0, 1.0) * 0.72).coerceIn(0.18, 1.0) }
            ?: 1.0
        val moonCloudTransmission = weather?.cloudCover
            ?.let { (1.0 - it.coerceIn(0.0, 1.0) * 0.88).coerceIn(0.08, 1.0) }
            ?: 1.0

        val sunVisibility = (
            CelestialHorizonTransitionV2.diskAlpha(snapshot.sun.altitudeDeg) *
                sunCloudTransmission * phenomenonTransmission(weatherType)
            ).coerceIn(0.0, 1.0)
        val moonVisibility = (
            CelestialHorizonTransitionV2.diskAlpha(snapshot.moon.altitudeDeg) *
                moonCloudTransmission * phenomenonTransmission(weatherType) *
                ambientMoonFactor
            ).coerceIn(0.0, 1.0)

        return CelestialAtmosphereStateV2(
            solarLightLevel = solarLight,
            twilightLevel = twilight,
            nightLevel = night,
            starsVisibility = stars,
            constellationsVisibility = stars,
            sunVisibility = sunVisibility,
            moonVisibility = moonVisibility,
            atmosphereOpacity = (1.0 - weatherTransmission).coerceIn(0.0, 1.0),
            cloudCoverage = weather?.cloudCover,
            weatherType = weatherType,
            weatherTransmission = weatherTransmission,
            ambientStarFactor = ambientFactor,
            ambientMoonFactor = ambientMoonFactor
        )
    }

    fun weatherType(code: Int?): CelestialWeatherTypeV2 = when (code) {
        0 -> CelestialWeatherTypeV2.CLEAR
        1, 2 -> CelestialWeatherTypeV2.PARTLY_CLOUDY
        3 -> CelestialWeatherTypeV2.OVERCAST
        45, 48 -> CelestialWeatherTypeV2.FOG
        51, 53, 55, 56, 57 -> CelestialWeatherTypeV2.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> CelestialWeatherTypeV2.RAIN
        71, 73, 75, 77, 85, 86 -> CelestialWeatherTypeV2.SNOW
        95, 96, 99 -> CelestialWeatherTypeV2.THUNDERSTORM
        else -> CelestialWeatherTypeV2.UNKNOWN
    }

    private fun weatherTransmission(
        weather: CelestialWeatherContextV2.State?,
        type: CelestialWeatherTypeV2
    ): Double {
        if (weather == null) return 1.0

        val cloud = (1.0 - weather.cloudCover.coerceIn(0.0, 1.0) * 0.90)
            .coerceIn(0.08, 1.0)
        val visibility = weather.visibilityMeters?.let {
            smoothStep(300.0, 10_000.0, it)
        } ?: 1.0
        val phenomenon = phenomenonTransmission(type)

        return (cloud * visibility * phenomenon).coerceIn(0.03, 1.0)
    }

    private fun phenomenonTransmission(type: CelestialWeatherTypeV2): Double = when (type) {
        CelestialWeatherTypeV2.FOG -> 0.36
        CelestialWeatherTypeV2.DRIZZLE -> 0.74
        CelestialWeatherTypeV2.RAIN -> 0.60
        CelestialWeatherTypeV2.SNOW -> 0.58
        CelestialWeatherTypeV2.THUNDERSTORM -> 0.42
        CelestialWeatherTypeV2.OVERCAST -> 0.82
        else -> 1.0
    }

    private fun ambientStarFactor(ambient: CelestialAmbientLightStateV2): Double {
        if (ambient.quality != CelestialAmbientLightQualityV2.VALID) return 1.0
        val lux = ambient.lux ?: return 1.0
        if (!lux.isFinite() || lux < 0.0) return 1.0

        return when {
            lux <= 1.0 -> 1.08
            lux <= 50.0 -> lerp(1.08, 1.0, (lux - 1.0) / 49.0)
            lux <= 1_000.0 -> lerp(1.0, 0.75, (lux - 50.0) / 950.0)
            lux <= 10_000.0 -> lerp(0.75, 0.35, (lux - 1_000.0) / 9_000.0)
            lux <= 100_000.0 -> lerp(0.35, 0.12, (lux - 10_000.0) / 90_000.0)
            else -> 0.12
        }.coerceIn(0.12, 1.08)
    }

    private fun ambientMoonFactor(ambient: CelestialAmbientLightStateV2): Double {
        if (ambient.quality != CelestialAmbientLightQualityV2.VALID) return 1.0
        val lux = ambient.lux ?: return 1.0
        if (!lux.isFinite() || lux < 0.0) return 1.0

        return when {
            lux <= 50.0 -> 1.0
            lux <= 1_000.0 -> lerp(1.0, 0.92, (lux - 50.0) / 950.0)
            lux <= 10_000.0 -> lerp(0.92, 0.75, (lux - 1_000.0) / 9_000.0)
            lux <= 100_000.0 -> lerp(0.75, 0.60, (lux - 10_000.0) / 90_000.0)
            else -> 0.60
        }.coerceIn(0.60, 1.0)
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        if (!value.isFinite()) return 0.0
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t.coerceIn(0.0, 1.0)
}
