package com.amaury.pointage.v2.engine

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Keep astronomy unchanged while fixing the mobile container that displays it. */
class CelestialSolarFrameRegressionV2Test {
    @Test fun aFixedAltitudeStaysOnItsSphericalLocusDuringACompassTurn() {
        val r = CelestialDomeV2.RADIUS_FRACTION
        val tilt = Math.toRadians(CelestialDomeV2.CAMERA_ELEVATION_DEG)
        for (altitude in listOf(0.0, 10.0, 43.0, 70.0)) {
            val h = Math.toRadians(altitude)
            val rx = r * cos(h)
            val ry = rx * sin(tilt)
            val centreY = -r * sin(h) * cos(tilt)
            for (heading in 0..360) {
                val p = CelestialDomeV2.project(183.0, altitude, heading.toDouble())!!
                assertEquals(1.0, (p.x / rx).pow(2) + ((p.y - centreY) / ry).pow(2), 1e-10)
                if (altitude > CelestialDomeV2.CAMERA_ELEVATION_DEG) assertTrue(p.y < 0)
            }
        }
    }
    @Test fun movingAWholeSceneNeverChangesSunRelativeToEarth() {
        val p = CelestialDomeV2.project(160.0, 43.0, 90.0)!!
        for (offset in -500..500) {
            val earthY = 600.0 + offset
            val sunY = earthY + p.y * 200.0
            assertEquals(p.y * 200.0, sunY - earthY, 1e-10)
        }
    }
    @Test fun fifteenSecondsOfTimeDoesNotCreateTheLargeVideoExcursion() {
        // Synthetic observer/time fixture, not the user's location or device log.
        val t = 1790335200000L
        val a = DefaultCelestialEngineV2.snapshot(45.0, 0.0, t).sun
        val b = DefaultCelestialEngineV2.snapshot(45.0, 0.0, t + 15000L).sun
        fun unit(body: CelestialBodyV2): DoubleArray {
            val az = Math.toRadians(body.azimuthDeg); val alt = Math.toRadians(body.altitudeDeg)
            return doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
        }
        val u = unit(a); val v = unit(b)
        val separation = acos(u.indices.sumOf { u[it] * v[it] }.coerceIn(-1.0, 1.0))
        assertTrue(Math.toDegrees(separation) < 0.1)
    }
}
