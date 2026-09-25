package com.amaury.pointage.v2.engine

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class CelestialCloudDensityV2Test {
    @Test fun missingAndUnusableWeatherIsNotClearSky() {
        assertNull(CelestialCloudAtmosphereV2.resolve(null))
        assertNull(CelestialCloudAtmosphereV2.resolve(0.8, usable = false))
        assertNull(CelestialCloudDensityV2.sample(null, 0.5, 0.5, 0.0))
        val clear = CelestialCloudAtmosphereV2.resolve(0.0)
        assertEquals(0.0, CelestialCloudDensityV2.sample(clear, 0.5, 0.5, 0.0)!!, 0.0)
    }

    @Test fun invalidInputsAreRejectedRatherThanClamped() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertNull(CelestialCloudAtmosphereV2.resolve(bad))
            assertNull(CelestialCloudAtmosphereV2.resolve(0.8, lowCoverage = bad))
            assertNull(CelestialCloudAtmosphereV2.resolve(0.8, midCoverage = bad))
            assertNull(CelestialCloudAtmosphereV2.resolve(0.8, highCoverage = bad))
        }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertNull(CelestialCloudAtmosphereV2.resolve(0.8, visibilityMeters = bad))
        }
    }

    @Test fun aggregateCoverDoesNotInventAnAltitude() {
        val state = CelestialCloudAtmosphereV2.resolve(0.8)!!
        assertEquals(listOf(CloudBandV2(CloudAltitudeV2.UNRESOLVED, 0.8)), state.bands)
        assertNull(state.lowCoverage)
        assertNull(state.midCoverage)
        assertNull(state.highCoverage)
        assertFalse(state.hasCompleteAltitudeCoverage)
    }

    @Test fun partialAltitudeDataKeepsMissingDistinctFromZero() {
        val state = CelestialCloudAtmosphereV2.resolve(0.8, highCoverage = 0.0)!!
        assertEquals(listOf(CloudBandV2(CloudAltitudeV2.HIGH, 0.0)), state.bands)
        assertNull(state.lowCoverage)
        assertFalse(state.hasCompleteAltitudeCoverage)
        val aggregate = CelestialCloudAtmosphereV2.resolve(0.8)!!
        val expected = CelestialCloudDensityV2.sample(aggregate, 0.5, 0.5, 0.0)!!
        assertTrue(expected > 0.0)
        assertEquals(expected, CelestialCloudDensityV2.sample(state, 0.5, 0.5, 0.0)!!, 1e-12)
    }

    @Test fun allMeasuredLayersArePreservedInCompositingOrder() {
        val state = CelestialCloudAtmosphereV2.resolve(0.9, 0.7, 0.2, 0.4)!!
        assertTrue(state.hasCompleteAltitudeCoverage)
        assertEquals(listOf(CloudAltitudeV2.HIGH, CloudAltitudeV2.MID, CloudAltitudeV2.LOW), state.bands.map { it.altitude })
        assertEquals(listOf(0.4, 0.2, 0.7), state.bands.map { it.coverage })
    }

    @Test fun fogIsLowAndDoesNotRequireNonzeroTotalClouds() {
        val fog = CelestialCloudAtmosphereV2.resolve(0.0, fog = true, visibilityMeters = 100.0)!!
        val high = CelestialCloudDensityV2.sample(fog, 0.5, 0.1, 0.0)!!
        val low = CelestialCloudDensityV2.sample(fog, 0.5, 0.9, 0.0)!!
        assertEquals(0.0, high, 0.0)
        assertTrue(low > high)
    }

    @Test fun invalidSampleCoordinatesDoNotProduceFakeDensity() {
        val state = CelestialCloudAtmosphereV2.resolve(0.8)!!
        assertNull(CelestialCloudDensityV2.sample(state, Double.NaN, 0.5, 0.0))
        assertNull(CelestialCloudDensityV2.sample(state, 0.5, -0.1, 0.0))
        assertNull(CelestialCloudDensityV2.sample(state, 0.5, 0.5, Double.POSITIVE_INFINITY))
        assertNull(CelestialCloudDensityV2.sample(state, 0.5, 0.5, 0.0, 0))
        assertNull(CelestialCloudDensityV2.sample(state, 0.5, 0.5, 0.0, 7))
    }

    @Test fun gridIsBoundedMonotonicAndPeriodicAtEveryQuality() {
        for (altitude in CloudAltitudeV2.values()) {
            for (coverage in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0)) {
                val band = CloudBandV2(altitude, coverage)
                for (detail in listOf(1, 3, 6)) {
                    for (seconds in listOf(-1.0, 0.0, 5399.999, 5400.001, 1790288568.0)) {
                        for (yi in 0..6) for (xi in 0..8) {
                            val x = xi / 8.0; val y = yi / 6.0
                            val d = CelestialCloudDensityV2.sampleBand(band, x, y, seconds, detail)!!
                            assertTrue(d.isFinite() && d in 0.0..1.0)
                            if (coverage == 0.0) assertEquals(0.0, d, 0.0)
                            if (coverage == 1.0) assertTrue(d > 0.25)
                            assertEquals(d, CelestialCloudDensityV2.sampleBand(band, x + 1.0, y, seconds, detail)!!, 1e-8)
                            if (coverage < 1.0) {
                                val more = band.copy(coverage = coverage + 0.01)
                                assertTrue(CelestialCloudDensityV2.sampleBand(more, x, y, seconds, detail)!! + 1e-12 >= d)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun animationDoesNotJumpAtTheOldNinetyMinuteReset() {
        val band = CloudBandV2(CloudAltitudeV2.LOW, 0.5)
        for (i in 0..100) {
            val a = CelestialCloudDensityV2.sampleBand(band, i / 100.0, 0.4, 5399.999)!!
            val b = CelestialCloudDensityV2.sampleBand(band, i / 100.0, 0.4, 5400.001)!!
            assertTrue(abs(a - b) < 0.0001)
        }
    }

    @Test fun portableGoldenValuesStayIdenticalToSwift() {
        // Regression references for the visual algorithm, not atmospheric measurements.
        assertEquals(0.23600633808504407, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.LOW, 0.5), 1.0 / 8.0, 1.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.03434054316166834, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.LOW, 0.5), 4.0 / 8.0, 1.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.656844648837901, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.MID, 0.5), 7.0 / 8.0, 1.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.6022278574982859, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.MID, 0.5), 1.0 / 8.0, 2.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.030484846770205323, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.HIGH, 0.5), 7.0 / 8.0, 1.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.2983091649278087, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.HIGH, 0.5), 1.0 / 8.0, 2.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.16862794989625637, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.UNRESOLVED, 0.5), 3.0 / 8.0, 2.0 / 6.0, 5399.999, 3)!!, 1e-9)
        assertEquals(0.4255023954932288, CelestialCloudDensityV2.sampleBand(CloudBandV2(CloudAltitudeV2.UNRESOLVED, 0.5), 6.0 / 8.0, 2.0 / 6.0, 5399.999, 3)!!, 1e-9)
    }
}
