package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2

enum class CelestialDataStatusV2 {
    FRESH,
    STALE,
    UNAVAILABLE,
    INVALID
}

data class CelestialDataFreshnessV2(
    val astronomyAgeMs: Long,
    val locationAgeMs: Long?,
    val headingAgeMs: Long?,
    val weatherAgeMs: Long?,
    val ambientAgeMs: Long?,
    val locationSource: String?,
    val weatherSource: String?,
    val astronomyStatus: CelestialDataStatusV2,
    val locationStatus: CelestialDataStatusV2,
    val headingStatus: CelestialDataStatusV2,
    val weatherStatus: CelestialDataStatusV2,
    val ambientStatus: CelestialDataStatusV2
)

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
    val dataFreshness: CelestialDataFreshnessV2,
    val warnings: Set<CelestialRenderWarningV2>,
    val clouds: CloudAtmosphereStateV2? = null,
    val cloudsExpiresAtMs: Long? = null,
    val cloudsFetchedAtMs: Long? = null
)

enum class CelestialRenderWarningV2 {
    WEATHER_UNAVAILABLE,
    WEATHER_STALE,
    AMBIENT_LIGHT_UNAVAILABLE,
    AMBIENT_LIGHT_STALE,
    LOCATION_STALE,
    ORIENTATION_UNQUALIFIED,
    ASTRONOMY_STALE
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
        locationQuality: CelestialLocationQualityV2 = CelestialLocationQualityV2.UNAVAILABLE,
        locationAgeMs: Long? = null,
        locationProvider: String? = null,
        headingAgeMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    ): CelestialRenderStateV2 {
        // Qualify once, before any visibility or texture can consume weather.
        val candidateClouds = weather?.let {
            CelestialCloudAtmosphereV2.resolve(
                it.cloudCover, it.cloudCoverLow, it.cloudCoverMid, it.cloudCoverHigh,
                CelestialAtmosphereV2.weatherType(it.weatherCode) == CelestialWeatherTypeV2.FOG,
                it.visibilityMeters
            )
        }
        val weatherStatus = when {
            weather == null -> CelestialDataStatusV2.UNAVAILABLE
            nowMs < weather.fetchedAtMs -> CelestialDataStatusV2.INVALID
            !weather.isFresh(nowMs) -> CelestialDataStatusV2.STALE
            locationQuality != CelestialLocationQualityV2.VALID ||
                !weather.matches(snapshot) || candidateClouds == null -> CelestialDataStatusV2.INVALID
            else -> CelestialDataStatusV2.FRESH
        }
        val qualifiedWeather = weather.takeIf { weatherStatus == CelestialDataStatusV2.FRESH }
        val clouds = candidateClouds.takeIf { weatherStatus == CelestialDataStatusV2.FRESH }
        val atmosphere = CelestialAtmosphereV2.resolve(snapshot, qualifiedWeather, ambient)
        val astronomyAgeMs = (nowMs - snapshot.atMs).coerceAtLeast(0L)
        val weatherAgeMs = weather?.let { (nowMs - it.fetchedAtMs).coerceAtLeast(0L) }
        val ambientAgeMs = ambient.ageMs(nowElapsedMs)

        val freshness = CelestialDataFreshnessV2(
            astronomyAgeMs = astronomyAgeMs,
            locationAgeMs = locationAgeMs,
            headingAgeMs = headingAgeMs,
            weatherAgeMs = weatherAgeMs,
            ambientAgeMs = ambientAgeMs,
            locationSource = locationProvider,
            weatherSource = weather?.source,
            astronomyStatus = if (astronomyAgeMs <= ASTRONOMY_FRESH_MS) {
                CelestialDataStatusV2.FRESH
            } else {
                CelestialDataStatusV2.STALE
            },
            locationStatus = locationStatus(locationQuality),
            headingStatus = headingStatus(orientationQuality),
            weatherStatus = weatherStatus,
            ambientStatus = ambientStatus(ambient.quality)
        )

        val warnings = linkedSetOf<CelestialRenderWarningV2>()
        when (freshness.weatherStatus) {
            CelestialDataStatusV2.UNAVAILABLE,
            CelestialDataStatusV2.INVALID ->
                warnings += CelestialRenderWarningV2.WEATHER_UNAVAILABLE
            CelestialDataStatusV2.STALE ->
                warnings += CelestialRenderWarningV2.WEATHER_STALE
            CelestialDataStatusV2.FRESH -> Unit
        }
        when (ambient.quality) {
            CelestialAmbientLightQualityV2.UNAVAILABLE,
            CelestialAmbientLightQualityV2.INVALID ->
                warnings += CelestialRenderWarningV2.AMBIENT_LIGHT_UNAVAILABLE
            CelestialAmbientLightQualityV2.STALE ->
                warnings += CelestialRenderWarningV2.AMBIENT_LIGHT_STALE
            CelestialAmbientLightQualityV2.VALID -> Unit
        }
        if (locationQuality == CelestialLocationQualityV2.STALE) {
            warnings += CelestialRenderWarningV2.LOCATION_STALE
        }
        if (!CelestialHeadingPolicyV2.isUsable(orientationQuality)) {
            warnings += CelestialRenderWarningV2.ORIENTATION_UNQUALIFIED
        }
        if (freshness.astronomyStatus == CelestialDataStatusV2.STALE) {
            warnings += CelestialRenderWarningV2.ASTRONOMY_STALE
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
            weatherAgeMs = weatherAgeMs,
            ambientLux = ambient.lux,
            ambientLightQuality = ambient.quality,
            ambientLightAgeMs = ambientAgeMs,
            starsVisibility = atmosphere.starsVisibility,
            constellationsVisibility = atmosphere.constellationsVisibility,
            sunVisibility = atmosphere.sunVisibility,
            moonVisibility = atmosphere.moonVisibility,
            atmosphereOpacity = atmosphere.atmosphereOpacity,
            orientationQuality = orientationQuality,
            dataFreshness = freshness,
            warnings = warnings,
            clouds = clouds,
            cloudsExpiresAtMs = qualifiedWeather?.renderExpiresAtMs,
            cloudsFetchedAtMs = qualifiedWeather?.fetchedAtMs
        )
    }

    private fun locationStatus(
        quality: CelestialLocationQualityV2
    ): CelestialDataStatusV2 = when (quality) {
        CelestialLocationQualityV2.VALID -> CelestialDataStatusV2.FRESH
        CelestialLocationQualityV2.STALE -> CelestialDataStatusV2.STALE
        CelestialLocationQualityV2.INACCURATE -> CelestialDataStatusV2.INVALID
        CelestialLocationQualityV2.NO_PERMISSION,
        CelestialLocationQualityV2.UNAVAILABLE -> CelestialDataStatusV2.UNAVAILABLE
    }

    private fun headingStatus(
        quality: CelestialHeadingQualityV2
    ): CelestialDataStatusV2 = when (quality) {
        CelestialHeadingQualityV2.VALID,
        CelestialHeadingQualityV2.UNKNOWN_ACCURACY -> CelestialDataStatusV2.FRESH
        CelestialHeadingQualityV2.STALE -> CelestialDataStatusV2.STALE
        CelestialHeadingQualityV2.INACCURATE,
        CelestialHeadingQualityV2.UNRELIABLE -> CelestialDataStatusV2.INVALID
        CelestialHeadingQualityV2.UNAVAILABLE -> CelestialDataStatusV2.UNAVAILABLE
    }

    private fun ambientStatus(
        quality: CelestialAmbientLightQualityV2
    ): CelestialDataStatusV2 = when (quality) {
        CelestialAmbientLightQualityV2.VALID -> CelestialDataStatusV2.FRESH
        CelestialAmbientLightQualityV2.STALE -> CelestialDataStatusV2.STALE
        CelestialAmbientLightQualityV2.INVALID -> CelestialDataStatusV2.INVALID
        CelestialAmbientLightQualityV2.UNAVAILABLE -> CelestialDataStatusV2.UNAVAILABLE
    }

    private const val ASTRONOMY_FRESH_MS = 5_000L
}
