package com.amaury.pointage.v2.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.hypot

/** Screen tangent: +X right, +Y down. */
data class CelestialScreenDirectionV2(val x: Double, val y: Double)

/** Physical screen frame in local East / true North / Up. */
data class CelestialDeviceFrameV2(
    val rightEast: Double, val rightNorth: Double, val rightUp: Double,
    val topEast: Double, val topNorth: Double, val topUp: Double,
    val normalEast: Double, val normalNorth: Double, val normalUp: Double,
    val stabilizedHeadingDeg: Double? = null
)

data class CelestialWatchProjectionV2(
    val xRadiusFraction: Double, val yRadiusFraction: Double, val radialFraction: Double
)

/**
 * Platform facade of the spherical 360° map. The globe is the observer, not
 * the Sun. Camera elevation is an explicit presentation choice; astronomical
 * coordinates are never changed. Being behind the phone does not hide a body.
 */
object CelestialScreenGeometryV2 {
    const val VERTICAL_RENDER_ASPECT = 1.14
    const val STANDARD_DISK_HORIZON_DEG = AtmosphericRefractionV2.STANDARD_SOLAR_DISK_HORIZON_DEG
    @Deprecated("Use STANDARD_DISK_HORIZON_DEG")
    const val CIVIL_HORIZON_DEG = STANDARD_DISK_HORIZON_DEG
    @Deprecated("Spherical projection no longer uses a protected radial zenith")
    const val ZENITH_RADIUS_FRACTION = 0.34
    private const val HEADING_EPSILON = 1e-6
    private const val TOP_HEADING_MIN_HORIZONTAL = 0.20

    fun safeRenderSpan(width: Double, height: Double): Double {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return 0.0
        return minOf(width, height / VERTICAL_RENDER_ASPECT)
    }

    fun projectEarthCenteredSky(body: CelestialBodyV2, deviceAzimuthDeg: Float): CelestialWatchProjectionV2? {
        if (!body.altitudeDeg.isFinite() || body.altitudeDeg !in STANDARD_DISK_HORIZON_DEG..90.0) return null
        val apparent = AtmosphericRefractionV2.apparentAltitudeDeg(body.altitudeDeg)
        val p = CelestialDomeV2.project(body.azimuthDeg, apparent, deviceAzimuthDeg.toDouble()) ?: return null
        return CelestialWatchProjectionV2(p.x, p.y, hypot(p.x, p.y))
    }

    fun projectInDeviceSky(body: CelestialBodyV2, frame: CelestialDeviceFrameV2): CelestialWatchProjectionV2? =
        projectEarthCenteredSky(body, headingFromFrame(frame).toFloat())

    fun projectOnWatchDome(body: CelestialBodyV2, deviceAzimuthDeg: Float): CelestialWatchProjectionV2? =
        projectEarthCenteredSky(body, deviceAzimuthDeg)

    fun directionToward(from: CelestialBodyV2, to: CelestialBodyV2, deviceAzimuthDeg: Float): CelestialScreenDirectionV2? =
        directionTowardEarthCentered(from, horizontalUnit(to.azimuthDeg, to.altitudeDeg), deviceAzimuthDeg)

    fun directionTowardAntiSun(moon: CelestialBodyV2, sun: CelestialBodyV2, deviceAzimuthDeg: Float): CelestialScreenDirectionV2? {
        val s = horizontalUnit(sun.azimuthDeg, sun.altitudeDeg)
        return directionTowardEarthCentered(moon, doubleArrayOf(-s[0], -s[1], -s[2]), deviceAzimuthDeg)
    }

    fun directionToward(from: CelestialBodyV2, to: CelestialBodyV2, frame: CelestialDeviceFrameV2): CelestialScreenDirectionV2? =
        directionToward(from, to, headingFromFrame(frame).toFloat())

    fun directionTowardAntiSun(moon: CelestialBodyV2, sun: CelestialBodyV2, frame: CelestialDeviceFrameV2): CelestialScreenDirectionV2? =
        directionTowardAntiSun(moon, sun, headingFromFrame(frame).toFloat())

    /** Preserve the stabilized heading and the tested pitch/roll fallback. */
    fun headingFromFrame(frame: CelestialDeviceFrameV2): Double {
        frame.stabilizedHeadingDeg?.takeIf { it.isFinite() }?.let { return normalizeDegrees(it) }
        val topHorizontal = hypot(frame.topEast, frame.topNorth)
        if (topHorizontal >= TOP_HEADING_MIN_HORIZONTAL) {
            return normalizeDegrees(Math.toDegrees(atan2(frame.topEast / topHorizontal, frame.topNorth / topHorizontal)))
        }
        val rightHorizontal = hypot(frame.rightEast, frame.rightNorth)
        if (rightHorizontal > HEADING_EPSILON) {
            return normalizeDegrees(Math.toDegrees(atan2(-frame.rightNorth / rightHorizontal, frame.rightEast / rightHorizontal)))
        }
        val normalHorizontal = hypot(frame.normalEast, frame.normalNorth)
        if (normalHorizontal > HEADING_EPSILON) {
            return normalizeDegrees(Math.toDegrees(atan2(frame.normalEast / normalHorizontal, frame.normalNorth / normalHorizontal)))
        }
        return 0.0
    }

    private fun directionTowardEarthCentered(from: CelestialBodyV2, targetVector: DoubleArray,
                                              deviceAzimuthDeg: Float): CelestialScreenDirectionV2? {
        val v = horizontalUnit(from.azimuthDeg, from.altitudeDeg)
        val alignment = (v[0]*targetVector[0] + v[1]*targetVector[1] + v[2]*targetVector[2]).coerceIn(-1.0,1.0)
        val x = targetVector[0] - alignment*v[0]
        val y = targetVector[1] - alignment*v[1]
        val z = targetVector[2] - alignment*v[2]
        val length = sqrt(x*x+y*y+z*z)
        if (!length.isFinite() || length < 1e-9) return null
        val p = CelestialDomeV2.tangent(x/length,y/length,z/length,deviceAzimuthDeg.toDouble()) ?: return null
        return CelestialScreenDirectionV2(p.x,p.y)
    }

    private fun horizontalUnit(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg % 360.0)
        val alt = Math.toRadians(altitudeDeg)
        return doubleArrayOf(cos(alt)*sin(az), cos(alt)*cos(az), sin(alt))
    }
    private fun normalizeDegrees(value: Double): Double = ((value % 360.0)+360.0)%360.0
}
