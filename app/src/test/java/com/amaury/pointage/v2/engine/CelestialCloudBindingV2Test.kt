package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import org.junit.Assert.*
import org.junit.Test

class CelestialCloudBindingV2Test {
    private val now = 1_700_000_000_000L
    private fun weather() = CelestialWeatherContextV2.State(
        cloudCover = 0.8, cloudCoverLow = 0.7, cloudCoverMid = null, cloudCoverHigh = 0.0,
        weatherCode = 3, precipitationMm = null, visibilityMeters = 10_000.0,
        fetchedAtMs = now, roundedLatitude = 46.67, roundedLongitude = -1.63, source = "test")
    private fun build(weather: CelestialWeatherContextV2.State?,
                      location: CelestialLocationQualityV2 = CelestialLocationQualityV2.VALID) =
        CelestialRenderStateFactoryV2.build(
            snapshot = DefaultCelestialEngineV2.snapshot(46.67, -1.63, now),
            weather = weather,
            ambient = CelestialAmbientLightStateV2(null, CelestialAmbientLightQualityV2.UNAVAILABLE, 0L),
            orientationQuality = CelestialHeadingQualityV2.VALID,
            locationQuality = location, nowMs = now, nowElapsedMs = 0L)

    @Test fun freshWeatherReachesTheSingleCanonicalCloudState() {
        val result = build(weather())
        assertEquals(CelestialDataStatusV2.FRESH, result.dataFreshness.weatherStatus)
        assertEquals(now, result.cloudsFetchedAtMs!!)
        assertEquals(now + 45 * 60_000L, result.cloudsExpiresAtMs!!)
        assertEquals(0.7, result.clouds!!.lowCoverage!!, 0.0)
        assertNull(result.clouds!!.midCoverage)
        assertEquals(0.0, result.clouds!!.highCoverage!!, 0.0)
        assertEquals(result.cloudCoverage!!, result.clouds!!.totalCoverage, 0.0)
    }

    @Test fun unusableWeatherCannotAttenuateAstronomicalBodies() {
        val neutral = build(null)
        val rejected = listOf(
            weather().copy(fetchedAtMs = Long.MIN_VALUE) to CelestialDataStatusV2.STALE,
            weather().copy(fetchedAtMs = now + 1) to CelestialDataStatusV2.INVALID,
            weather().copy(fetchedAtMs = now - 45 * 60_000L - 1) to CelestialDataStatusV2.STALE,
            weather().copy(roundedLatitude = 48.85) to CelestialDataStatusV2.INVALID,
            weather().copy(cloudCoverLow = Double.NaN) to CelestialDataStatusV2.INVALID,
            weather().copy(visibilityMeters = -1.0) to CelestialDataStatusV2.INVALID)
        for ((input, status) in rejected) {
            val result = build(input)
            assertEquals(status, result.dataFreshness.weatherStatus)
            assertNull(result.clouds)
            assertNull(result.cloudsExpiresAtMs)
            assertNull(result.cloudCoverage)
            assertEquals(CelestialWeatherTypeV2.UNKNOWN, result.weatherType)
            assertEquals(neutral.starsVisibility, result.starsVisibility, 0.0)
            assertEquals(neutral.sunVisibility, result.sunVisibility, 0.0)
            assertEquals(neutral.moonVisibility, result.moonVisibility, 0.0)
            assertEquals(neutral.sunAzimuthDeg, result.sunAzimuthDeg, 0.0)
        }
    }

    @Test fun unqualifiedLocationDoesNotAuthorizeACloudTexture() {
        for (quality in CelestialLocationQualityV2.values().filter { it != CelestialLocationQualityV2.VALID }) {
            assertNull(build(weather(), quality).clouds)
        }
    }

    @Test fun expiryBoundaryAndKnownZeroAreExplicit() {
        assertNotNull(build(weather().copy(fetchedAtMs = now - 45 * 60_000L)).clouds)
        assertNull(build(weather().copy(fetchedAtMs = now - 45 * 60_000L - 1)).clouds)
        val clear = build(weather().copy(cloudCover = 0.0, cloudCoverLow = 0.0, weatherCode = 0))
        assertNotNull(clear.clouds)
        assertEquals(0.0, clear.clouds!!.totalCoverage, 0.0)
        val fog = build(weather().copy(cloudCover = 0.0, cloudCoverLow = 0.0, weatherCode = 45))
        assertTrue(fog.clouds!!.fog)
    }
}
