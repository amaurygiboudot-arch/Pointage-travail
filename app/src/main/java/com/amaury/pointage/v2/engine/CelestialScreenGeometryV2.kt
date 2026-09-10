package com.amaury.pointage.v2.engine

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direction 2D normalisée dans l'écran Android : +X droite, +Y bas. */
data class CelestialScreenDirectionV2(val x: Double, val y: Double)

/**
 * Repère physique de l'écran exprimé dans le monde local Est / Nord vrai / Zénith.
 *
 * Chaque axe est un vecteur unité :
 * - right = droite de l'écran ;
 * - top = haut de l'écran ;
 * - normal = direction perpendiculaire sortant de l'écran.
 */
data class CelestialDeviceFrameV2(
    val rightEast: Double,
    val rightNorth: Double,
    val rightUp: Double,
    val topEast: Double,
    val topNorth: Double,
    val topUp: Double,
    val normalEast: Double,
    val normalNorth: Double,
    val normalUp: Double
)

/**
 * Position d'un astre dans le dôme compact de l'horloge.
 * x/y sont exprimés en fraction du rayon disponible.
 */
data class CelestialWatchProjectionV2(
    val xRadiusFraction: Double,
    val yRadiusFraction: Double,
    val radialFraction: Double
)

/**
 * Géométrie physique du rendu céleste V2.
 *
 * La projection principale utilise désormais le repère 3D réel de l'écran.
 * Quand le téléphone est posé à plat, le centre de l'horloge correspond au
 * zénith et le bord à l'horizon. En inclinant ou en tournant le téléphone, le
 * ciel se déplace selon la vraie orientation de l'appareil : l'horloge devient
 * donc un viseur céleste et non plus un simple compas 2D.
 */
object CelestialScreenGeometryV2 {
    const val CIVIL_HORIZON_DEG = -0.833
    const val ZENITH_RADIUS_FRACTION = 0.34

    /**
     * Projection 3D écran : le centre correspond à la normale de l'écran et le
     * bord à 90° de celle-ci. Un astre derrière le plan de l'écran n'est pas
     * affiché. Un astre réellement sous l'horizon civil n'est jamais inventé.
     */
    fun projectInDeviceSky(
        body: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialWatchProjectionV2? {
        if (body.altitudeDeg < CIVIL_HORIZON_DEG) return null

        val world = horizontalUnit(body.azimuthDeg, body.altitudeDeg)
        val screenX = dot(
            world,
            frame.rightEast,
            frame.rightNorth,
            frame.rightUp
        )
        val screenTop = dot(
            world,
            frame.topEast,
            frame.topNorth,
            frame.topUp
        )
        val screenNormal = dot(
            world,
            frame.normalEast,
            frame.normalNorth,
            frame.normalUp
        ).coerceIn(-1.0, 1.0)

        // L'astre est dans l'hémisphère opposé à celui regardé par l'écran.
        if (screenNormal < -1e-6) return null

        val angularDistance = acos(screenNormal)
        val radialFraction = (angularDistance / (Math.PI / 2.0)).coerceIn(0.0, 1.0)
        val tangentLength = sqrt(screenX * screenX + screenTop * screenTop)

        if (tangentLength < 1e-9 || radialFraction < 1e-9) {
            return CelestialWatchProjectionV2(0.0, 0.0, 0.0)
        }

        return CelestialWatchProjectionV2(
            xRadiusFraction = (screenX / tangentLength) * radialFraction,
            yRadiusFraction = (-screenTop / tangentLength) * radialFraction,
            radialFraction = radialFraction
        )
    }

    /**
     * Ancienne projection azimut/altitude conservée uniquement comme outil de
     * compatibilité et de test. Le rendu principal doit utiliser
     * projectInDeviceSky().
     */
    fun projectOnWatchDome(
        body: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialWatchProjectionV2? {
        if (body.altitudeDeg < CIVIL_HORIZON_DEG) return null

        val altitude = body.altitudeDeg.coerceIn(0.0, 90.0)
        val altitudeFraction = altitude / 90.0
        val radialFraction = 1.0 - altitudeFraction * (1.0 - ZENITH_RADIUS_FRACTION)
        val theta = Math.toRadians(shortestDelta(deviceAzimuthDeg.toDouble(), body.azimuthDeg))

        return CelestialWatchProjectionV2(
            xRadiusFraction = sin(theta) * radialFraction,
            yRadiusFraction = -cos(theta) * radialFraction,
            radialFraction = radialFraction
        )
    }

    fun directionToward(
        from: CelestialBodyV2,
        to: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialScreenDirectionV2? = directionTowardVector(
        from = from,
        targetVector = horizontalUnit(to.azimuthDeg, to.altitudeDeg),
        frame = frame
    )

    fun directionTowardAntiSun(
        moon: CelestialBodyV2,
        sun: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialScreenDirectionV2? {
        val sunVector = horizontalUnit(sun.azimuthDeg, sun.altitudeDeg)
        return directionTowardVector(
            from = moon,
            targetVector = doubleArrayOf(-sunVector[0], -sunVector[1], -sunVector[2]),
            frame = frame
        )
    }

    /** Compatibilité de l'ancien cadran 2D. */
    fun directionToward(
        from: CelestialBodyV2,
        to: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? = directionTowardVectorLegacy(
        from = from,
        targetVector = horizontalUnit(to.azimuthDeg, to.altitudeDeg),
        deviceAzimuthDeg = deviceAzimuthDeg
    )

    /** Compatibilité de l'ancien cadran 2D. */
    fun directionTowardAntiSun(
        moon: CelestialBodyV2,
        sun: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? {
        val sunVector = horizontalUnit(sun.azimuthDeg, sun.altitudeDeg)
        return directionTowardVectorLegacy(
            from = moon,
            targetVector = doubleArrayOf(-sunVector[0], -sunVector[1], -sunVector[2]),
            deviceAzimuthDeg = deviceAzimuthDeg
        )
    }

    private fun directionTowardVector(
        from: CelestialBodyV2,
        targetVector: DoubleArray,
        frame: CelestialDeviceFrameV2
    ): CelestialScreenDirectionV2? {
        val fromVector = horizontalUnit(from.azimuthDeg, from.altitudeDeg)
        val tangent = tangentToward(fromVector, targetVector) ?: return null

        val screenX = dot(
            tangent,
            frame.rightEast,
            frame.rightNorth,
            frame.rightUp
        )
        val screenY = -dot(
            tangent,
            frame.topEast,
            frame.topNorth,
            frame.topUp
        )
        val length = sqrt(screenX * screenX + screenY * screenY)
        if (length < 1e-9) return null

        return CelestialScreenDirectionV2(screenX / length, screenY / length)
    }

    private fun directionTowardVectorLegacy(
        from: CelestialBodyV2,
        targetVector: DoubleArray,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? {
        val fromVector = horizontalUnit(from.azimuthDeg, from.altitudeDeg)
        val tangent = tangentToward(fromVector, targetVector) ?: return null

        val az = Math.toRadians(from.azimuthDeg)
        val alt = Math.toRadians(from.altitudeDeg)
        val eastAz = doubleArrayOf(cos(az), -sin(az), 0.0)
        val upAlt = doubleArrayOf(
            -sin(alt) * sin(az),
            -sin(alt) * cos(az),
            cos(alt)
        )
        val azComponent = dot(tangent, eastAz[0], eastAz[1], eastAz[2])
        val altComponent = dot(tangent, upAlt[0], upAlt[1], upAlt[2])

        val theta = Math.toRadians(shortestDelta(deviceAzimuthDeg.toDouble(), from.azimuthDeg))
        val screenX = azComponent * cos(theta) - altComponent * sin(theta)
        val screenY = azComponent * sin(theta) + altComponent * cos(theta)
        val length = sqrt(screenX * screenX + screenY * screenY)
        if (length < 1e-9) return null

        return CelestialScreenDirectionV2(screenX / length, screenY / length)
    }

    private fun tangentToward(fromVector: DoubleArray, targetVector: DoubleArray): DoubleArray? {
        val alignment = (
            fromVector[0] * targetVector[0] +
                fromVector[1] * targetVector[1] +
                fromVector[2] * targetVector[2]
            ).coerceIn(-1.0, 1.0)

        var x = targetVector[0] - alignment * fromVector[0]
        var y = targetVector[1] - alignment * fromVector[1]
        var z = targetVector[2] - alignment * fromVector[2]
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1e-9) return null
        x /= length
        y /= length
        z /= length
        return doubleArrayOf(x, y, z)
    }

    private fun horizontalUnit(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg)
        val alt = Math.toRadians(altitudeDeg)
        val cosAlt = cos(alt)
        return doubleArrayOf(
            cosAlt * sin(az), // Est vrai
            cosAlt * cos(az), // Nord vrai
            sin(alt)          // Zénith
        )
    }

    private fun dot(vector: DoubleArray, east: Double, north: Double, up: Double): Double =
        vector[0] * east + vector[1] * north + vector[2] * up

    private fun shortestDelta(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0
}
