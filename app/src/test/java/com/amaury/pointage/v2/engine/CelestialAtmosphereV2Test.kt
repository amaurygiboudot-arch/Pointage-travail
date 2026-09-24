package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialAtmosphereV2Test {
    private fun ambient(
        lux: Double? = null,
        quality: CelestialAmbientLightQualityV2 = CelestialAmbientLightQualityV2.UNAVAILABLE
    ) = CelestialAmbientLightStateV2(lux, quality, 1_000L)

    @Test
    fun daylightNeverBecomesNightBecauseAmbientSensorIsDark() {
        val noon = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_789_128_000_000L
        )
        val result = CelestialAtmosphereV2.resolve(
            snapshot = noon,
            weather = null,
            ambient = ambient(0.0, CelestialAmbientLightQualityV2.VALID)
        )

        if (noon.sun.altitudeDeg > 8.0) {
            assertTrue(result.solarLightLevel > 0.95)
            assertEquals(0.0, result.starsVisibility, 1e-9)
        }
    }

    @Test
    fun cloudAndFogAttenuateStarsWithoutChangingAstronomy() {
        val night = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_789_171_200_000L
        )
        val clear = CelestialAtmosphereV2.resolve(night, null, ambient())
        val fog = CelestialWeatherContextV2.parseCurrentWeather(
            json = """{"current":{"cloud_cover":95,"weather_code":45,"visibility":300}}""",
            fetchedAtMs = night.atMs,
            latitude = night.latitudeDeg,
            longitude = night.longitudeDeg,
            source = "test"
        )
        val foggy = CelestialAtmosphereV2.resolve(night, fog, ambient())

        assertEquals(night.sun.azimuthDeg, night.sun.azimuthDeg, 0.0)
        assertTrue(foggy.starsVisibility <= clear.starsVisibility)
        assertTrue(foggy.weatherTransmission < 0.2)
    }

    @Test
    fun wmoWeatherCodesUseStableSharedCategories() {
        assertEquals(CelestialWeatherTypeV2.CLEAR, CelestialAtmosphereV2.weatherType(0))
        assertEquals(CelestialWeatherTypeV2.FOG, CelestialAtmosphereV2.weatherType(45))
        assertEquals(CelestialWeatherTypeV2.RAIN, CelestialAtmosphereV2.weatherType(81))
        assertEquals(CelestialWeatherTypeV2.SNOW, CelestialAtmosphereV2.weatherType(86))
        assertEquals(CelestialWeatherTypeV2.THUNDERSTORM, CelestialAtmosphereV2.weatherType(95))
        assertEquals(CelestialWeatherTypeV2.UNKNOWN, CelestialAtmosphereV2.weatherType(null))
    }
}
