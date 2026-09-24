package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CelestialAmbientLightQualityV2
import com.amaury.pointage.v2.CelestialAmbientLightStateV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CelestialAcceptanceMatrixV2Test {
    private val noAmbient = CelestialAmbientLightStateV2(
        lux = null,
        quality = CelestialAmbientLightQualityV2.UNAVAILABLE,
        measuredAtElapsedMs = null
    )

    @Test
    fun equinoxDayAndNightRemainAstronomicallyDistinct() {
        val day = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            timeMs = 1_774_008_000_000L
        )
        val night = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            timeMs = 1_773_964_800_000L
        )

        assertTrue(day.sun.altitudeDeg > 60.0)
        assertTrue(night.sun.altitudeDeg < -60.0)
        assertFalse(day.night)
        assertTrue(night.night)

        val dayAtmosphere = CelestialAtmosphereV2.resolve(day, null, noAmbient)
        val nightAtmosphere = CelestialAtmosphereV2.resolve(night, null, noAmbient)
        assertTrue(dayAtmosphere.solarLightLevel > 0.95)
        assertTrue(dayAtmosphere.starsVisibility < 0.01)
        assertTrue(nightAtmosphere.nightLevel > 0.95)
        assertTrue(nightAtmosphere.starsVisibility > 0.95)
    }

    @Test
    fun passageOfMidnightHasNoArtificialAstronomicalJump() {
        val before = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_798_761_540_000L
        )
        val after = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_798_761_660_000L
        )

        assertTrue(abs(after.sun.altitudeDeg - before.sun.altitudeDeg) < 1.0)
        assertTrue(abs(after.moon.altitudeDeg - before.moon.altitudeDeg) < 1.0)
        assertTrue(
            abs(
                after.moonPhase.illuminatedFraction -
                    before.moonPhase.illuminatedFraction
            ) < 0.01
        )
    }

    @Test
    fun supportedLatitudeSamplesProduceFiniteRealSky() {
        val latitudes = listOf(-60.0, 0.0, 46.67, 69.0)
        val dates = listOf(
            1_768_514_400_000L,
            1_784_152_800_000L
        )

        for (latitude in latitudes) {
            for (time in dates) {
                val snapshot = DefaultCelestialEngineV2.snapshot(
                    latitudeDeg = latitude,
                    longitudeDeg = -1.63,
                    timeMs = time
                )
                assertTrue(snapshot.sun.azimuthDeg.isFinite())
                assertTrue(snapshot.sun.altitudeDeg.isFinite())
                assertTrue(snapshot.moon.azimuthDeg.isFinite())
                assertTrue(snapshot.moon.altitudeDeg.isFinite())
                assertTrue(snapshot.moonPhase.illuminatedFraction in 0.0..1.0)
            }
        }
    }

    @Test
    fun realConstellationGeometryChangesWithSeasonWithoutDecoration() {
        val star = BrightStarV2(
            id = "acceptance-star",
            rightAscensionJ2000Deg = 101.287,
            declinationJ2000Deg = -16.716,
            visualMagnitude = -1.46,
            constellation = "CMa",
            commonName = "Sirius"
        )
        val winter = StarSkyProjectionV2.horizontal(
            star = star,
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_768_514_400_000L
        )
        val summer = StarSkyProjectionV2.horizontal(
            star = star,
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_784_152_800_000L
        )

        assertTrue(winter.azimuthDeg.isFinite())
        assertTrue(summer.azimuthDeg.isFinite())
        assertTrue(
            abs(winter.azimuthDeg - summer.azimuthDeg) > 5.0 ||
                abs(winter.apparentAltitudeDeg - summer.apparentAltitudeDeg) > 5.0
        )
    }

    @Test
    fun unknownSensorsNeverCreateDayNightTruth() {
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.63,
            timeMs = 1_768_514_400_000L
        )
        val atmosphere = CelestialAtmosphereV2.resolve(
            snapshot = snapshot,
            weather = null,
            ambient = noAmbient
        )

        val expectedNight = snapshot.sun.altitudeDeg < -0.833
        assertTrue(snapshot.night == expectedNight)
        assertTrue(atmosphere.solarLightLevel in 0.0..1.0)
        assertTrue(atmosphere.nightLevel in 0.0..1.0)
    }
}
