package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialWeatherContextV2Test {
    @Test
    fun parseCloudCoverageAndCurrentWeather() {
        val state = CelestialWeatherContextV2.parseCurrentWeather(
            json = """
                {
                  "current": {
                    "cloud_cover": 73,
                    "cloud_cover_low": 45,
                    "cloud_cover_mid": 20,
                    "cloud_cover_high": 55,
                    "weather_code": 3,
                    "precipitation": 0.2,
                    "visibility": 12000
                  }
                }
            """.trimIndent(),
            fetchedAtMs = 1_000L,
            latitude = 46.67,
            longitude = -1.63,
            source = "test"
        )

        assertEquals(0.73, state.cloudCover, 1e-12)
        assertEquals(0.45, state.cloudCoverLow ?: -1.0, 1e-12)
        assertEquals(3, state.weatherCode)
        assertEquals(0.2, state.precipitationMm ?: -1.0, 1e-12)
        assertTrue(state.cloudTransmission < 0.4)
    }
}
