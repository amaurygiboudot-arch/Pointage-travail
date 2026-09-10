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

    @Test(expected = IllegalArgumentException::class)
    fun `latitude impossible est refusee`() {
        engine.snapshot(91.0, 0.0, Instant.parse("2026-03-03T11:38:00Z").toEpochMilli())
    }

    private fun snapshot(instant: String): CelestialSnapshotV2 = engine.snapshot(
        latitudeDeg = 46.67,
        longitudeDeg = -1.43,
        timeMs = Instant.parse(instant).toEpochMilli()
    )

    private fun angularDelta(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)
}
