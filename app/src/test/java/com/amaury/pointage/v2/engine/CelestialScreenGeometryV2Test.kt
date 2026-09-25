package com.amaury.pointage.v2.engine

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Existing heading/layout contracts retained; coordinates now describe the approved 3D dome. */
class CelestialScreenGeometryV2Test {
    private fun body(az: Double, alt: Double) = CelestialBodyV2(azimuthDeg=az,altitudeDeg=alt,distanceKm=1.0,apparentScale=1.0)
    private fun point(az: Double, alt: Double=0.0, heading: Float=0f) =
        CelestialScreenGeometryV2.projectEarthCenteredSky(body(az,alt),heading)!!
    private val flat=CelestialDeviceFrameV2(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
    private val upright=CelestialDeviceFrameV2(1.0,0.0,0.0,0.0,0.0,-1.0,0.0,1.0,0.0)
    private val east=CelestialDeviceFrameV2(0.0,-1.0,0.0,0.0,0.0,-1.0,1.0,0.0,0.0)

    @Test fun safeSpanFitsLandscapeAndTablet() {
        for ((w,h) in listOf(800.0 to 280.0,1200.0 to 540.0))
            assertTrue(CelestialScreenGeometryV2.safeRenderSpan(w,h)*CelestialScreenGeometryV2.VERTICAL_RENDER_ASPECT<=h)
    }
    @Test fun safeSpanKeepsPortraitWidthAndRejectsInvalidSizes() {
        assertEquals(360.0,CelestialScreenGeometryV2.safeRenderSpan(360.0,640.0),1e-9)
        assertEquals(0.0,CelestialScreenGeometryV2.safeRenderSpan(Double.NaN,640.0),0.0)
    }
    @Test fun cardinalCoordinatesBelongToInclinedSphere() {
        val altitude=Math.toRadians(AtmosphericRefractionV2.apparentAltitudeDeg(0.0))
        val tilt=Math.toRadians(35.0)
        val r=0.76
        assertEquals(0.0,point(0.0).xRadiusFraction,1e-12)
        assertEquals(-r*(cos(altitude)*sin(tilt)+sin(altitude)*cos(tilt)),point(0.0).yRadiusFraction,1e-12)
        assertEquals(r*cos(altitude),point(90.0).xRadiusFraction,1e-12)
        assertEquals(-r*sin(altitude)*cos(tilt),point(90.0).yRadiusFraction,1e-12)
        assertTrue(point(180.0).yRadiusFraction>0.4)
        assertTrue(point(270.0).xRadiusFraction< -0.75)
    }
    @Test fun headingThirtySevenStillPreservesAllCardinalDirections() {
        assertTrue(point(37.0,heading=37f).yRadiusFraction< -0.4)
        assertTrue(point(127.0,heading=37f).xRadiusFraction>0.75)
        assertTrue(point(217.0,heading=37f).yRadiusFraction>0.4)
        assertTrue(point(307.0,heading=37f).xRadiusFraction< -0.75)
    }
    @Test fun oppositeAzimuthDoesNotDisappearOnThreeSixtyMap() {
        assertNotNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(180.0,25.0),0f))
    }
    @Test fun physicalFrameCompatibilityDoesNotCullRearBodies() {
        assertNotNull(CelestialScreenGeometryV2.projectInDeviceSky(body(180.0,25.0),upright))
    }
    @Test fun stabilizedHeadingStillWinsOverRawFrame() {
        val frame=flat.copy(stabilizedHeadingDeg=90.0)
        assertEquals(90.0,CelestialScreenGeometryV2.headingFromFrame(frame),1e-9)
        assertTrue(CelestialScreenGeometryV2.projectInDeviceSky(body(0.0,0.0),frame)!!.xRadiusFraction< -0.75)
    }
    @Test fun tiltToVerticalDoesNotFlipHeading() {
        val c=sqrt(0.5)
        val half=CelestialDeviceFrameV2(1.0,0.0,0.0,0.0,c,-c,0.0,c,c)
        for (f in listOf(flat,half,upright)) assertEquals(0.0,CelestialScreenGeometryV2.headingFromFrame(f),1e-9)
    }
    @Test fun tiltInOtherDirectionDoesNotFlipHeading() {
        val c=sqrt(0.5)
        val half=CelestialDeviceFrameV2(1.0,0.0,0.0,0.0,c,c,0.0,-c,c)
        val vertical=CelestialDeviceFrameV2(1.0,0.0,0.0,0.0,0.0,1.0,0.0,-1.0,0.0)
        for (f in listOf(half,vertical)) assertEquals(0.0,CelestialScreenGeometryV2.headingFromFrame(f),1e-9)
    }
    @Test fun rollBeyondNinetyDoesNotFlipHeading() {
        val c=cos(Math.toRadians(120.0));val s=sin(Math.toRadians(120.0))
        val frame=CelestialDeviceFrameV2(c,0.0,s,0.0,1.0,0.0,-s,0.0,c)
        assertEquals(0.0,CelestialScreenGeometryV2.headingFromFrame(frame),1e-9)
        assertTrue(CelestialScreenGeometryV2.projectInDeviceSky(body(0.0,0.0),frame)!!.yRadiusFraction< -0.4)
    }
    @Test fun uprightEastUsesRightAxisFallback() {
        assertEquals(90.0,CelestialScreenGeometryV2.headingFromFrame(east),1e-9)
    }
    @Test fun rotationChangesDepthNotJustFlatDiskAngle() {
        val north=point(0.0);val rotated=point(0.0,heading=90f)
        assertTrue(north.yRadiusFraction<0);assertTrue(rotated.xRadiusFraction<0)
        assertTrue(abs(north.radialFraction-rotated.radialFraction)>0.2)
    }
    @Test fun increasingAltitudeHasContinuousSphericalTrajectory() {
        var previous=point(90.0,0.0)
        for (i in 1..900) {
            val p=point(90.0,i/10.0)
            assertTrue(hypot(p.xRadiusFraction-previous.xRadiusFraction,p.yRadiusFraction-previous.yRadiusFraction)<0.003)
            previous=p
        }
    }
    @Test fun refractionRaisesApparentAltitudeWithoutChangingEphemeris() {
        val b=body(90.0,-0.5)
        val p=CelestialScreenGeometryV2.projectEarthCenteredSky(b,0f)!!
        assertTrue(p.yRadiusFraction<0)
        assertEquals(-0.5,b.altitudeDeg,0.0)
    }
    @Test fun zenithNoLongerOrbitsCentreWithUndefinedAzimuth() {
        val a=point(0.0,90.0);val b=point(180.0,90.0)
        assertEquals(a.xRadiusFraction,b.xRadiusFraction,1e-12)
        assertEquals(a.yRadiusFraction,b.yRadiusFraction,1e-12)
        assertEquals(-0.76*cos(Math.toRadians(35.0)),a.yRadiusFraction,1e-12)
    }
    @Test fun diskProjectionAndFadeShareTheirExistingCutoff() {
        assertNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0,-0.84),0f))
        assertNotNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0,-0.82),0f))
        for (i in -1000..3000) {
            val h=i/1000.0
            val alpha=CelestialHorizonTransitionV2.diskAlpha(h)
            if (alpha>0) assertNotNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0,h),0f))
            assertTrue(abs(alpha-CelestialHorizonTransitionV2.diskAlpha(h+0.001))<0.001)
        }
    }
    @Test fun invalidBodyCannotCreateScreenCoordinate() {
        for (h in listOf(Double.NaN,Double.POSITIVE_INFINITY,91.0))
            assertNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(90.0,h),0f))
        assertNull(CelestialScreenGeometryV2.projectEarthCenteredSky(body(Double.NaN,30.0),0f))
    }
    @Test fun originalIconsFitInsideDialWithReservedMargin() {
        for (az in 0..360 step 5) for (h in 0..90 step 3)
            assertTrue(point(az.toDouble(),h.toDouble()).radialFraction+0.22<1)
    }
    @Test fun terminatorFollowsSameSphericalTangent() {
        val d=CelestialScreenGeometryV2.directionToward(body(0.0,0.0),body(90.0,0.0),0f)!!
        assertTrue(d.x>0.99);assertTrue(abs(d.y)<0.01)
    }
    @Test fun earthShadowStillPointsTowardAntiSun() {
        val d=CelestialScreenGeometryV2.directionTowardAntiSun(body(170.0,0.0),body(0.0,0.0),0f)!!
        assertTrue(d.x<0)
    }
}
