package com.amaury.pointage.v2.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direction 2D normalisée dans l'écran Android : +X droite, +Y bas. */
data class CelestialScreenDirectionV2(val x: Double, val y: Double)

/**
 * Position d'un astre dans le dôme compact de l'horloge.
 * x/y sont exprimés en fraction du rayon d'horizon :
 * - horizon = rayon 1.0 ;
 * - zénith = rayon interne 0.34 afin de préserver la Terre centrale ;
 * - sous l'horizon réel = non rendu.
 */
data class CelestialWatchProjectionV2(
    val xRadiusFraction: Double,
    val yRadiusFraction: Double,
    val radialFraction: Double
)

/**
 * Géométrie locale du rendu céleste V2.
 *
 * Le cadran est un compas céleste : l'azimut fixe la direction autour de la
 * montre, tandis que l'altitude réelle rapproche l'astre du centre lorsqu'il
 * monte dans le ciel. Le rayon est volontairement comprimé près du zénith pour
 * conserver lisibles la Terre et les aiguilles, mais l'ordre angulaire réel est
 * respecté. Aucun astre situé sous l'horizon civil n'est inventé à l'écran.
 *
 * La direction de l'éclairage de la Lune est calculée séparément sur la vraie
 * sphère céleste : composante azimutale + composante d'altitude. Le terminateur
 * reste ainsi orienté vers le vrai Soleil même lorsque la différence entre les
 * deux astres est principalement verticale dans le ciel.
 */
object CelestialScreenGeometryV2 {
    const val CIVIL_HORIZON_DEG = -0.833
    const val ZENITH_RADIUS_FRACTION = 0.34

    fun projectOnWatchDome(
        body: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialWatchProjectionV2? {
        if (body.altitudeDeg < CIVIL_HORIZON_DEG) return null

        // Entre l'horizon réfracté (-0,833°) et l'horizon géométrique (0°),
        // l'astre reste posé sur le bord du dôme. De 0° à 90°, le rayon décroît
        // continûment vers le zénith.
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
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? = directionTowardVector(
        from = from,
        targetVector = horizontalUnit(to.azimuthDeg, to.altitudeDeg),
        deviceAzimuthDeg = deviceAzimuthDeg
    )

    fun directionTowardAntiSun(
        moon: CelestialBodyV2,
        sun: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? {
        val sunVector = horizontalUnit(sun.azimuthDeg, sun.altitudeDeg)
        return directionTowardVector(
            from = moon,
            targetVector = doubleArrayOf(-sunVector[0], -sunVector[1], -sunVector[2]),
            deviceAzimuthDeg = deviceAzimuthDeg
        )
    }

    private fun directionTowardVector(
        from: CelestialBodyV2,
        targetVector: DoubleArray,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? {
        val fromVector = horizontalUnit(from.azimuthDeg, from.altitudeDeg)
        val dot = (
            fromVector[0] * targetVector[0] +
                fromVector[1] * targetVector[1] +
                fromVector[2] * targetVector[2]
            ).coerceIn(-1.0, 1.0)

        var tx = targetVector[0] - dot * fromVector[0]
        var ty = targetVector[1] - dot * fromVector[1]
        var tz = targetVector[2] - dot * fromVector[2]
        val tangentLength = sqrt(tx * tx + ty * ty + tz * tz)
        if (tangentLength < 1e-9) return null
        tx /= tangentLength
        ty /= tangentLength
        tz /= tangentLength

        val az = Math.toRadians(from.azimuthDeg)
        val alt = Math.toRadians(from.altitudeDeg)

        // Base tangent locale : azimut croissant (Est) et altitude croissante (zénith).
        val eastAzX = cos(az)
        val eastAzY = -sin(az)
        val eastAzZ = 0.0
        val upAltX = -sin(alt) * sin(az)
        val upAltY = -sin(alt) * cos(az)
        val upAltZ = cos(alt)

        val azComponent = tx * eastAzX + ty * eastAzY + tz * eastAzZ
        val altComponent = tx * upAltX + ty * upAltY + tz * upAltZ

        val theta = Math.toRadians(shortestDelta(deviceAzimuthDeg.toDouble(), from.azimuthDeg))
        val screenAzX = cos(theta)
        val screenAzY = sin(theta)
        val screenAltX = -sin(theta)
        val screenAltY = cos(theta)

        val screenX = azComponent * screenAzX + altComponent * screenAltX
        val screenY = azComponent * screenAzY + altComponent * screenAltY
        val screenLength = sqrt(screenX * screenX + screenY * screenY)
        if (screenLength < 1e-9) return null

        return CelestialScreenDirectionV2(
            x = screenX / screenLength,
            y = screenY / screenLength
        )
    }

    private fun horizontalUnit(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg)
        val alt = Math.toRadians(altitudeDeg)
        val cosAlt = cos(alt)
        return doubleArrayOf(
            cosAlt * sin(az), // Est
            cosAlt * cos(az), // Nord
            sin(alt)          // Haut
        )
    }

    private fun shortestDelta(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0
}
