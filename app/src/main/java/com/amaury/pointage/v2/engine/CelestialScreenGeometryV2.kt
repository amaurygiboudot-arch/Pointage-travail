package com.amaury.pointage.v2.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direction 2D normalisée dans l'écran Android : +X droite, +Y bas. */
data class CelestialScreenDirectionV2(val x: Double, val y: Double)

/**
 * Géométrie locale utilisée par le rendu céleste.
 *
 * Le cadran historique conserve pour l'instant la position des astres sur son
 * anneau d'azimut. En revanche, la direction locale de l'éclairage de la Lune
 * est calculée sur la vraie sphère céleste : composante azimutale tangente à
 * l'anneau et composante d'altitude dirigée vers/depuis le zénith.
 *
 * Cela permet d'orienter le terminateur vers le vrai Soleil même lorsque la
 * différence Soleil/Lune est surtout verticale dans le ciel, cas que la simple
 * ligne entre deux points de l'anneau ne peut pas représenter correctement.
 */
object CelestialScreenGeometryV2 {

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
