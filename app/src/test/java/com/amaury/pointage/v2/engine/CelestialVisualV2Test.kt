package com.amaury.pointage.v2.engine

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CelestialVisualV2Test {
    private fun style(m: Double = 1.0, alt: Double = 30.0, id: Int = 2491,
                      t: Double = 0.0, v: Double = 1.0, animated: Boolean = true) =
        CelestialStarAppearanceV2.resolve(m, alt, id, t, v, animated)!!

    @Test fun invalidInputsAreNotInventedStars() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(CelestialStarAppearanceV2.resolve(bad,30.0,1,0.0,1.0,true))
            assertNull(CelestialStarAppearanceV2.resolve(1.0,bad,1,0.0,1.0,true))
            assertNull(CelestialStarAppearanceV2.resolve(1.0,30.0,1,bad,1.0,true))
            assertNull(CelestialDomeV2.project(bad,30.0,0.0))
            assertNull(CelestialDomeV2.project(0.0,bad,0.0))
        }
        assertNull(CelestialDomeV2.project(0.0,91.0,0.0))
        assertNull(CelestialStarAppearanceV2.resolve(1.0,30.0,1,0.0,-0.1,true))
    }

    @Test fun horizonIsZeroAndTwinkleDoesNotExposeBelowHorizonStars() {
        assertEquals(0.0, CelestialDomeV2.HORIZON_DEG, 0.0)
        for (h in listOf(-90.0,-1.0,0.0)) {
            assertEquals(0.0,style(alt=h).coreAlpha,0.0)
            assertEquals(0.0,style(alt=h).haloAlpha,0.0)
        }
        assertTrue(style(alt=0.001).coreAlpha < 0.000001)
        assertTrue(style(alt=1.0).coreAlpha > 0.0)
    }

    @Test fun apparentMagnitudeControlsSizeAndBrightnessNotFictionalDistance() {
        var previous = style(m=-1.5,animated=false)
        for (i in -14..80) {
            val next = style(m=i/10.0,animated=false)
            assertTrue(next.radius <= previous.radius + 1e-12)
            assertTrue(next.coreAlpha <= previous.coreAlpha + 1e-12)
            previous = next
        }
        assertEquals(243,CelestialStarAppearanceV2.RED)
        assertEquals(247,CelestialStarAppearanceV2.GREEN)
        assertEquals(255,CelestialStarAppearanceV2.BLUE)
    }

    @Test fun noStrobeAndNoTimeReset() {
        for (id in listOf(1,2491,7001,Int.MIN_VALUE)) {
            for (i in 0..2000) {
                val a = style(id=id,t=i*0.031)
                val b = style(id=id,t=i*0.031+0.001)
                assertTrue(a.coreAlpha in 0.0..1.0 && a.haloAlpha in 0.0..1.0)
                assertTrue(abs(a.coreAlpha-b.coreAlpha)<0.001)
                assertTrue(a.coreAlpha >= style(id=id,animated=false).coreAlpha*0.88)
            }
        }
        assertTrue(abs(style(t=5399.999).coreAlpha-style(t=5400.001).coreAlpha)<0.001)
    }

    @Test fun reducedMotionIsStableAndStarsDoNotBlinkTogether() {
        assertEquals(style(t=0.0,animated=false),style(t=123456.0,animated=false))
        assertEquals(style(m=4.0,t=0.0),style(m=4.0,t=100.0))
        assertNotEquals(style(id=1,t=7.0).coreAlpha,style(id=7001,t=7.0).coreAlpha)
        assertEquals(0.0,style(v=0.0).coreAlpha,0.0)
    }

    @Test fun hemisphereIsProjectedFromUnitVectorsNotFlatDiskRotation() {
        val r=CelestialDomeV2.RADIUS_FRACTION
        for (az in 0..360 step 5) for (alt in 0..90 step 3) for (heading in listOf(0.0,123.0,359.9)) {
            val p=CelestialDomeV2.project(az.toDouble(),alt.toDouble(),heading)!!
            assertEquals(1.0,(p.x*p.x+p.y*p.y)/(r*r)+p.depth*p.depth,1e-12)
            assertTrue(hypot(p.x,p.y)<=r+1e-12)
        }
        val north=CelestialDomeV2.project(0.0,0.0,0.0)!!
        val rotated=CelestialDomeV2.project(0.0,0.0,90.0)!!
        assertTrue(abs(hypot(north.x,north.y)-hypot(rotated.x,rotated.y))>0.2)
    }

    @Test fun noRearCullAndZenithIsIndependentOfAzimuth() {
        assertTrue(CelestialDomeV2.project(0.0,0.0,0.0)!!.depth<0)
        assertTrue(CelestialDomeV2.project(180.0,0.0,0.0)!!.depth>0)
        val zenith=CelestialDomeV2.project(0.0,90.0,0.0)!!
        for (a in 0..360) {
            val p=CelestialDomeV2.project(a.toDouble(),90.0,17.0)!!
            assertEquals(zenith.x,p.x,1e-12);assertEquals(zenith.y,p.y,1e-12)
        }
    }

    @Test fun sphereSeamAndHorizonAreContinuous() {
        val a=CelestialDomeV2.project(17.0,0.0,359.9999)!!
        val b=CelestialDomeV2.project(17.0,0.0,0.0001)!!
        assertTrue(hypot(a.x-b.x,a.y-b.y)<0.00001)
        val below=CelestialDomeV2.project(90.0,-0.0001,0.0)!!
        val above=CelestialDomeV2.project(90.0,0.0001,0.0)!!
        assertTrue(hypot(below.x-above.x,below.y-above.y)<0.00001)
    }

    @Test fun limbOrientationUsesSameSphereDifferential() {
        val p=CelestialDomeV2.tangent(1.0,0.0,0.0,0.0)!!
        assertEquals(1.0,p.x,1e-12);assertEquals(0.0,p.y,1e-12)
        assertNull(CelestialDomeV2.tangent(0.0,0.0,0.0,0.0))
    }
}
