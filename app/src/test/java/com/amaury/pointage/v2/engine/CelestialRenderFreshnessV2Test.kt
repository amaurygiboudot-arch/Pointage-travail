package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialRenderFreshnessV2Test {
    @Test
    fun freshSourcesKeepTheirAgeAndOrigin() {
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_700_000_000_000L
        )
        val weather = CelestialWeatherContextV2.parseCurrentWeather(
            json = """{"current":{"cloud_cover":20,"weather_code":1}}""",
            fetchedAtMs = snapshot.atMs + 1_000L,
            latitude = 46.67,
            longitude = -1.63,
            source = "https://weather.test"
        )
        val ambient = CelestialAmbientLightStateV2(
            lux = 120.0,
            quality = CelestialAmbientLightQualityV2.VALID,
            measuredAtElapsedMs = 4_000L
        )

        val state = CelestialRenderStateFactoryV2.build(
            snapshot = snapshot,
            weather = weather,
            ambient = ambient,
            orientationQuality = CelestialHeadingQualityV2.VALID,
            locationQuality = CelestialLocationQualityV2.VALID,
            locationAgeMs = 800L,
            locationProvider = "gps",
            headingAgeMs = 300L,
            nowMs = snapshot.atMs + 2_000L,
            nowElapsedMs = 5_000L
        )

        val freshness = state.dataFreshness
        assertEquals(CelestialDataStatusV2.FRESH, freshness.astronomyStatus)
        assertEquals(CelestialDataStatusV2.FRESH, freshness.locationStatus)
        assertEquals(CelestialDataStatusV2.FRESH, freshness.headingStatus)
        assertEquals(CelestialDataStatusV2.FRESH, freshness.weatherStatus)
        assertEquals(CelestialDataStatusV2.FRESH, freshness.ambientStatus)
        assertEquals("gps", freshness.locationSource)
        assertEquals("https://weather.test", freshness.weatherSource)
        assertEquals(1_000L, freshness.weatherAgeMs)
        assertEquals(1_000L, freshness.ambientAgeMs)
    }

    @Test
    fun staleOrInvalidInputsAreExplicitlyQualified() {
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_700_000_000_000L
        )
        val weather = CelestialWeatherContextV2.parseCurrentWeather(
            json = """{"current":{"cloud_cover":80,"weather_code":45}}""",
            fetchedAtMs = snapshot.atMs - 60 * 60_000L,
            latitude = 46.67,
            longitude = -1.63,
            source = "test"
        )
        val ambient = CelestialAmbientLightStateV2(
            lux = null,
            quality = CelestialAmbientLightQualityV2.STALE,
            measuredAtElapsedMs = 1_000L
        )

        val state = CelestialRenderStateFactoryV2.build(
            snapshot = snapshot,
            weather = weather,
            ambient = ambient,
            orientationQuality = CelestialHeadingQualityV2.INACCURATE,
            locationQuality = CelestialLocationQualityV2.STALE,
            locationAgeMs = 120_000L,
            locationProvider = "network",
            headingAgeMs = 10_000L,
            nowMs = snapshot.atMs + 10_000L,
            nowElapsedMs = 20_000L
        )

        val freshness = state.dataFreshness
        assertEquals(CelestialDataStatusV2.STALE, freshness.astronomyStatus)
        assertEquals(CelestialDataStatusV2.STALE, freshness.locationStatus)
        assertEquals(CelestialDataStatusV2.INVALID, freshness.headingStatus)
        assertEquals(CelestialDataStatusV2.STALE, freshness.weatherStatus)
        assertEquals(CelestialDataStatusV2.STALE, freshness.ambientStatus)
        assertTrue(CelestialRenderWarningV2.WEATHER_STALE in state.warnings)
        assertTrue(CelestialRenderWarningV2.LOCATION_STALE in state.warnings)
        assertTrue(CelestialRenderWarningV2.ASTRONOMY_STALE in state.warnings)
        assertTrue(CelestialRenderWarningV2.ORIENTATION_UNQUALIFIED in state.warnings)
    }
}
