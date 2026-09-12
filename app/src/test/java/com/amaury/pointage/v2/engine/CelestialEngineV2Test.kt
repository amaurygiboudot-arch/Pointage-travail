package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class CelestialEngineV2Test {
    private val engine = DefaultCelestialEngineV2

    @Test
    fun `nouvelle lune officielle du 17 fevrier 2026 est quasiment noire`() {
        val snapshot = snapshot("2026-02-17T12:01:00Z")
        assertTrue(snapshot.moonPhase.illuminatedFraction < 0.01)
        assertTrue(snapshot.moonPhase.elongationDeg < 2.0)
    }

    @Test
    fun `pleine lune officielle du 3 mars 2026 est quasiment pleine`() {
        val snapshot = snapshot("2026-03-03T11:38:00Z")
        assertTrue(snapshot.moonPhase.illuminatedFraction > 0.995)
        assertTrue(snapshot.moonPhase.elongationDeg > 178.0)
    }

    @Test
    fun `eclipse lunaire totale du 3 mars 2026 est reconnue`() {
        val snapshot = snapshot("2026-03-03T11:34:52Z")
        assertEquals(LunarEclipseStageV2.TOTAL, snapshot.lunarEclipse.stage)
        assertTrue(snapshot.lunarEclipse.umbralMagnitude > 1.0)
        assertTrue(snapshot.lunarEclipse.umbralMagnitude < 1.30)
        assertTrue(snapshot.lunarEclipse.umbraRadiusMoonRadii > 2.4)
    }

    @Test
    fun `eclipse lunaire partielle du 28 aout 2026 est reconnue`() {
        val snapshot = snapshot("2026-08-28T04:14:04Z")
        assertEquals(LunarEclipseStageV2.PARTIAL, snapshot.lunarEclipse.stage)
        assertTrue(snapshot.lunarEclipse.umbralMagnitude in 0.70..0.99)
    }

    @Test
    fun `eclipse penombrale du 20 fevrier 2027 ne devient pas une fausse partielle`() {
        val snapshot = snapshot("2027-02-20T23:14:06Z")
        assertEquals(LunarEclipseStageV2.PENUMBRAL, snapshot.lunarEclipse.stage)
        assertTrue(snapshot.lunarEclipse.umbralMagnitude <= 0.0)
        assertTrue(snapshot.lunarEclipse.penumbralMagnitude > 0.0)
    }

    @Test
    fun `nouvelle lune garde soleil et lune proches dans le ciel local`() {
        val snapshot = engine.snapshot(
            latitudeDeg = 51.509,
            longitudeDeg = -0.029,
            timeMs = Instant.parse("2026-02-17T12:16:00Z").toEpochMilli()
        )
        assertTrue(angularDelta(snapshot.sun.azimuthDeg, snapshot.moon.azimuthDeg) < 3.0)
        assertTrue(abs(snapshot.sun.altitudeDeg - snapshot.moon.altitudeDeg) < 4.0)
    }

    @Test
    fun `position solaire progresse continument avec le temps`() {
        val startMs = Instant.parse("2026-09-10T08:00:00Z").toEpochMilli()
        val first = engine.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            timeMs = startMs
        )
        val tenSecondsLater = engine.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            timeMs = startMs + 10_000L
        )

        val motionDeg = angularDelta(first.sun.azimuthDeg, tenSecondsLater.sun.azimuthDeg) +
            abs(first.sun.altitudeDeg - tenSecondsLater.sun.altitudeDeg)

        // Le moteur doit produire un déplacement réel mais continu sur 10 s :
        // ni image figée, ni saut de plusieurs degrés.
        assertTrue(motionDeg > 0.001)
        assertTrue(motionDeg < 0.20)
    }

    @Test
    fun `ephemeride reste proche de la reference haute precision en Vendee`() {
        val result = engine.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            observerAltitudeMeters = 50.0,
            timeMs = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        )

        assertPositionClose(result.sun, expectedAzimuth = 178.9901, expectedAltitude = 48.1438, toleranceDeg = 0.03)
        assertPositionClose(result.moon, expectedAzimuth = 191.3692, expectedAltitude = 49.3633, toleranceDeg = 0.08)
    }

    @Test
    fun `ephemeride reste proche de la reference haute precision dans hemisphere sud`() {
        val result = engine.snapshot(
            latitudeDeg = -33.8688,
            longitudeDeg = 151.2093,
            observerAltitudeMeters = 30.0,
            timeMs = Instant.parse("2026-06-21T12:00:00Z").toEpochMilli()
        )

        assertPositionClose(result.sun, expectedAzimuth = 255.5052, expectedAltitude = -62.4221, toleranceDeg = 0.03)
        assertPositionClose(result.moon, expectedAzimuth = 283.9110, expectedAltitude = 18.7330, toleranceDeg = 0.08)
    }

    @Test
    fun `ephemeride reste proche de la reference haute precision pres de equateur`() {
        val result = engine.snapshot(
            latitudeDeg = -0.1807,
            longitudeDeg = -78.4678,
            observerAltitudeMeters = 2850.0,
            timeMs = Instant.parse("2026-03-03T11:35:00Z").toEpochMilli()
        )

        assertPositionClose(result.sun, expectedAzimuth = 96.7163, expectedAltitude = 2.3135, toleranceDeg = 0.03)
        assertPositionClose(result.moon, expectedAzimuth = 276.3942, expectedAltitude = -3.4309, toleranceDeg = 0.08)
    }

    @Test
    fun `ephemeride reste proche de la reference haute precision aux hautes latitudes`() {
        val result = engine.snapshot(
            latitudeDeg = 69.6492,
            longitudeDeg = 18.9553,
            observerAltitudeMeters = 10.0,
            timeMs = Instant.parse("2026-12-21T12:00:00Z").toEpochMilli()
        )

        assertPositionClose(result.sun, expectedAzimuth = 197.8270, expectedAltitude = -4.1328, toleranceDeg = 0.03)
        assertPositionClose(result.moon, expectedAzimuth = 53.6912, expectedAltitude = 11.2834, toleranceDeg = 0.08)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `latitude impossible est refusee`() {
        engine.snapshot(91.0, 0.0, Instant.parse("2026-03-03T11:38:00Z").toEpochMilli())
    }

    private fun snapshot(instant: String): CelestialSnapshotV2 = engine.snapshot(
        latitudeDeg = 46.67,
        longitudeDeg = -1.43,
        timeMs = Instant.parse(instant).toEpochMilli()
    )

    private fun assertPositionClose(
        actual: CelestialBodyV2,
        expectedAzimuth: Double,
        expectedAltitude: Double,
        toleranceDeg: Double
    ) {
        assertTrue(
            "azimut attendu=$expectedAzimuth obtenu=${actual.azimuthDeg}",
            angularDelta(actual.azimuthDeg, expectedAzimuth) <= toleranceDeg
        )
        assertTrue(
            "altitude attendue=$expectedAltitude obtenue=${actual.altitudeDeg}",
            abs(actual.altitudeDeg - expectedAltitude) <= toleranceDeg
        )
    }

    private fun angularDelta(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)
}
