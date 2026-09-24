package com.amaury.pointage.v2.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class BrightStarV2(
    val id: String,
    val rightAscensionJ2000Deg: Double,
    val declinationJ2000Deg: Double,
    val visualMagnitude: Double,
    val constellation: String? = null,
    val commonName: String? = null
)

data class LocalStarPositionV2(
    val azimuthDeg: Double,
    val geometricAltitudeDeg: Double,
    val apparentAltitudeDeg: Double
) {
    val aboveApparentHorizon: Boolean
        get() = apparentAltitudeDeg >= 0.0
}

data class StarDeviceProjectionV2(
    /** -1...1 across the visible device hemisphere; +X is screen-right. */
    val x: Double,
    /** -1...1 across the visible device hemisphere; +Y is screen-down. */
    val y: Double,
    /** >0 means the star is in front of the phone display normal. */
    val depth: Double
)

object StarSkyProjectionV2 {
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val J2000 = 2_451_545.0

    fun localSiderealDegrees(timeMs: Long, longitudeDeg: Double): Double {
        require(timeMs > 0L)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
        val jd = julianDay(timeMs)
        val t = (jd - J2000) / 36_525.0
        val gmst = normalizeDegrees(
            280.46061837 +
                360.98564736629 * (jd - J2000) +
                0.000387933 * t * t -
                t * t * t / 38_710_000.0
        )
        return normalizeDegrees(gmst + longitudeDeg)
    }

    fun horizontal(
        star: BrightStarV2,
        latitudeDeg: Double,
        longitudeDeg: Double,
        timeMs: Long
    ): LocalStarPositionV2 {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
        require(star.rightAscensionJ2000Deg.isFinite())
        require(star.declinationJ2000Deg.isFinite() && star.declinationJ2000Deg in -90.0..90.0)

        val equatorial = precessJ2000(
            star.rightAscensionJ2000Deg,
            star.declinationJ2000Deg,
            julianDay(timeMs)
        )
        val localSidereal = localSiderealDegrees(timeMs, longitudeDeg)
        val hourAngle = Math.toRadians(signedDegrees(localSidereal - equatorial.first))
        val declination = Math.toRadians(equatorial.second)
        val latitude = Math.toRadians(latitudeDeg)

        val geometricAltitude = asin(
            (
                sin(latitude) * sin(declination) +
                    cos(latitude) * cos(declination) * cos(hourAngle)
                ).coerceIn(-1.0, 1.0)
        )
        val azimuth = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(latitude) - tan(declination) * cos(latitude)
        )
        val altitudeDeg = Math.toDegrees(geometricAltitude)
        return LocalStarPositionV2(
            azimuthDeg = normalizeDegrees(Math.toDegrees(azimuth) + 180.0),
            geometricAltitudeDeg = altitudeDeg,
            apparentAltitudeDeg = AtmosphericRefractionV2.apparentAltitudeDeg(altitudeDeg)
        )
    }

    /**
     * Orthographic projection of the local sky onto the physical device frame.
     * The centre of the screen is exactly the direction of the device normal.
     * A star behind the phone is rejected instead of being mirrored.
     */
    fun projectToDevice(
        position: LocalStarPositionV2,
        frame: CelestialDeviceFrameV2
    ): StarDeviceProjectionV2? {
        val altitude = Math.toRadians(position.apparentAltitudeDeg)
        val azimuth = Math.toRadians(position.azimuthDeg)
        val east = cos(altitude) * sin(azimuth)
        val north = cos(altitude) * cos(azimuth)
        val up = sin(altitude)

        val right = east * frame.rightEast + north * frame.rightNorth + up * frame.rightUp
        val top = east * frame.topEast + north * frame.topNorth + up * frame.topUp
        val depth = east * frame.normalEast + north * frame.normalNorth + up * frame.normalUp
        if (depth <= 0.0) return null

        return StarDeviceProjectionV2(
            x = right.coerceIn(-1.0, 1.0),
            y = (-top).coerceIn(-1.0, 1.0),
            depth = depth.coerceIn(0.0, 1.0)
        )
    }

    /**
     * Sensor-independent real-sky fallback.
     *
     * Maps the complete above-horizon hemisphere to the dial with zenith at the
     * centre and true North at the top. This never invents device orientation:
     * it is used only when a trustworthy physical frame is unavailable.
     */
    fun projectToZenithMap(position: LocalStarPositionV2): StarDeviceProjectionV2? {
        if (!position.apparentAltitudeDeg.isFinite() || position.apparentAltitudeDeg < 0.0) {
            return null
        }
        val radius = ((90.0 - position.apparentAltitudeDeg) / 90.0).coerceIn(0.0, 1.0)
        val azimuth = Math.toRadians(position.azimuthDeg)
        return StarDeviceProjectionV2(
            x = radius * sin(azimuth),
            y = -radius * cos(azimuth),
            depth = 1.0
        )
    }

    /**
     * Visual intensity only. Astronomy remains available during daytime, but the
     * Home background follows what the naked eye can realistically see.
     */
    fun nightSkyOpacity(sunGeometricAltitudeDeg: Double): Double {
        if (!sunGeometricAltitudeDeg.isFinite()) return 0.0
        val t = ((-sunGeometricAltitudeDeg - 4.0) / 8.0).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun precessJ2000(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
        val t = (jd - J2000) / 36_525.0
        val zeta = Math.toRadians(
            (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0
        )
        val z = Math.toRadians(
            (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0
        )
        val theta = Math.toRadians(
            (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0
        )
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val a = cos(dec) * sin(ra + zeta)
        val b = cos(theta) * cos(dec) * cos(ra + zeta) - sin(theta) * sin(dec)
        val c = sin(theta) * cos(dec) * cos(ra + zeta) + cos(theta) * sin(dec)
        return normalizeDegrees(Math.toDegrees(atan2(a, b)) + Math.toDegrees(z)) to
            Math.toDegrees(asin(c.coerceIn(-1.0, 1.0)))
    }

    private fun julianDay(timeMs: Long): Double = timeMs / MILLIS_PER_DAY + 2_440_587.5
    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun signedDegrees(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
}
