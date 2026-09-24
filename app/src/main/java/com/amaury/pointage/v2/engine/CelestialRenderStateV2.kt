package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2

data class CelestialRenderStateV2(
    val timestampMs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val sunAzimuthDeg: Double,
    val sunAltitudeDeg: Double,
    val moonAzimuthDeg: Double,
    val moonAltitudeDeg: Double,
    val moonIlluminatedFraction: Double,
    val solarLightLevel: Double,
    val twilightLevel: Double,
    val nightLevel: Double,
    val cloudCoverage: Double?,
    val weatherType: CelestialWeatherTypeV2,
    val weatherAgeMs: Long?,
    val ambientLux: Double?,
    val ambientLightQuality: CelestialAmbientLightQualityV2,
    val ambientLightAgeMs: Long?,
    val starsVisibility: Double,
    val constellationsVisibility: Double,
    val sunVisibility: Double,
    val moonVisibility: Double,
    val atmosphereOpacity: Double,
    val orientationQuality: CelestialHeadingQualityV2,
    val warnings: Set<CelestialRenderWarningV2>
)

enum class CelestialRenderWarningV2 {
    WEATHER_UNAVAILABLE,
    AMBIENT_LIGHT_UNAVAILABLE,
    AMBIENT_LIGHT_STALE,
    ORIENTATION_UNQUALIFIED
}

/**
 * Fabrique l'unique état destiné au rendu Céleste.
 *
 * Les renderers doivent consommer cet état plutôt que recalculer leurs propres
 * coefficients jour/nuit, météo ou luminosité.
 */
object CelestialRenderStateFactoryV2 {
    fun build(
        snapshot: CelestialSnapshotV2,
        weather: CelestialWeatherContextV2.State?,
        ambient: CelestialAmbientLightStateV2,
        orientationQuality: CelestialHeadingQualityV2,
        nowMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    ): CelestialRenderStateV2 {
        val atmosphere = CelestialAtmosphereV2.resolve(snapshot, weather, ambient)
        val warnings = linkedSetOf<CelestialRenderWarningV2>()

        if (weather == null) warnings += CelestialRenderWarningV2.WEATHER_UNAVAILABLE
        when (ambient.quality) {
            CelestialAmbientLightQualityV2.UNAVAILABLE,
            CelestialAmbientLightQualityV2.INVALID ->
                warnings += CelestialRenderWarningV2.AMBIENT_LIGHT_UNAVAILABLE
            CelestialAmbientLightQualityV2.STALE ->
                warnings += CelestialRenderWarningV2.AMBIENT_LIGHT_STALE
            CelestialAmbientLightQualityV2.VALID -> Unit
        }
        if (!CelestialHeadingPolicyV2.isUsable(orientationQuality)) {
            warnings += CelestialRenderWarningV2.ORIENTATION_UNQUALIFIED
        }

        return CelestialRenderStateV2(
            timestampMs = snapshot.atMs,
            latitudeDeg = snapshot.latitudeDeg,
            longitudeDeg = snapshot.longitudeDeg,
            sunAzimuthDeg = snapshot.sun.azimuthDeg,
            sunAltitudeDeg = snapshot.sun.altitudeDeg,
            moonAzimuthDeg = snapshot.moon.azimuthDeg,
            moonAltitudeDeg = snapshot.moon.altitudeDeg,
            moonIlluminatedFraction = snapshot.moonPhase.illuminatedFraction,
            solarLightLevel = atmosphere.solarLightLevel,
            twilightLevel = atmosphere.twilightLevel,
            nightLevel = atmosphere.nightLevel,
            cloudCoverage = atmosphere.cloudCoverage,
            weatherType = atmosphere.weatherType,
            weatherAgeMs = weather?.let { (nowMs - it.fetchedAtMs).coerceAtLeast(0L) },
            ambientLux = ambient.lux,
            ambientLightQuality = ambient.quality,
            ambientLightAgeMs = ambient.ageMs(nowElapsedMs),
            starsVisibility = atmosphere.starsVisibility,
            constellationsVisibility = atmosphere.constellationsVisibility,
            sunVisibility = atmosphere.sunVisibility,
            moonVisibility = atmosphere.moonVisibility,
            atmosphereOpacity = atmosphere.atmosphereOpacity,
            orientationQuality = orientationQuality,
            warnings = warnings
        )
    }
}
