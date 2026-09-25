package com.amaury.pointage.v2.engine

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CelestialGlobeOrientationV2Test {
    private fun rotation(heading: Double?) = CelestialGlobeOrientationV2.counterRotationDeg(heading)
    private fun rotate(x: Double, y: Double, heading: Double): Pair<Double, Double> {
        val a = Math.toRadians(rotation(heading))
        return (x * cos(a) - y * sin(a)) to (x * sin(a) + y * cos(a))
    }

    @Test fun cardinalHeadingsCounterRotateEarthNotItsCentre() {
        assertEquals(0.0, rotation(0.0), 0.0)
        assertEquals(-90.0, rotation(90.0), 0.0)
        assertEquals(-180.0, rotation(180.0), 0.0)
        assertEquals(90.0, rotation(270.0), 0.0)
        assertEquals(0.0, rotation(360.0), 0.0)
    }
    @Test fun northPointsToActualNorthAcrossAFullTurn() {
        for (i in 0..3600) {
            val h = i / 10.0
            val p = rotate(0.0, -1.0, h)
            assertEquals(-sin(Math.toRadians(h)), p.first, 1e-12)
            assertEquals(-cos(Math.toRadians(h)), p.second, 1e-12)
        }
    }
    @Test fun fixedPivotForPortraitLandscapeAndTabletSizes() {
        for ((w, h) in listOf(360.0 to 640.0, 640.0 to 360.0, 1200.0 to 800.0)) {
            val cx = w / 2; val cy = h / 2
            for (heading in 0..360 step 5) {
                val p = rotate(cx-cx, cy-cy, heading.toDouble())
                assertEquals(cx, cx+p.first, 0.0)
                assertEquals(cy, cy+p.second, 0.0)
            }
        }
    }
    @Test fun rotationPreservesGeographicOffsetsAndScale() {
        for ((x, y) in listOf(0.2 to -0.3, -0.5 to 0.7, 1.0 to 0.0, 0.0 to 0.0)) {
            for (h in 0..360 step 3) {
                val p = rotate(x, y, h.toDouble())
                assertEquals(hypot(x, y), hypot(p.first, p.second), 1e-12)
            }
        }
    }
    @Test fun wholeTurnsAndNegativeHeadingsAreEquivalent() {
        for (i in -7200..7200) {
            val h = i / 10.0
            assertEquals(rotation(h), rotation(h+720.0), 1e-10)
            assertTrue(rotation(h) in -180.0..180.0)
        }
    }
    @Test fun northSeamHasNoAlmostFullTurn() {
        assertEquals(0.1, rotation(359.9), 1e-12)
        assertEquals(-0.1, rotation(0.1), 1e-12)
        assertTrue(abs(rotation(359.999)-rotation(0.001))<0.003)
        val a=rotate(0.0,-1.0,179.999);val b=rotate(0.0,-1.0,180.001)
        assertTrue(hypot(a.first-b.first,a.second-b.second)<0.0001)
    }
    @Test fun invalidInputIsNeutralRatherThanFakeOrientation() {
        assertEquals(0.0, rotation(null), 0.0)
        for (v in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            assertEquals(0.0,rotation(v),0.0)
        for (v in listOf(Double.MAX_VALUE,-Double.MAX_VALUE)) {
            assertTrue(rotation(v).isFinite())
            assertTrue(rotation(v) in -180.0..180.0)
        }
    }
}
