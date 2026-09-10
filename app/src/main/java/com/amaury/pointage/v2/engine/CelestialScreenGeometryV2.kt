package com.amaury.pointage.v2.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direction 2D normalisée dans l'écran Android : +X droite, +Y bas. */
data class CelestialScreenDirectionV2(val x: Double, val y: Double)

/**
 * Repère physique de l'écran exprimé dans le monde local Est / Nord vrai / Zénith.
 *
 * `stabilizedHeadingDeg` permet au tracker Android d'injecter le cap déjà filtré.
 * Les consommateurs historiques qui passent encore par le frame utilisent ainsi
 * exactement le même cap que la carte 360°, au lieu de recalculer un azimut brut.
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
    val normalUp: Double,
    val stabilizedHeadingDeg: Double? = null
)

/** Position d'un astre dans le dôme compact de l'horloge. */
data class CelestialWatchProjectionV2(
    val xRadiusFraction: Double,
    val yRadiusFraction: Double,
    val radialFraction: Double
)

/**
 * Géométrie du rendu céleste V2.
 *
 * Référentiel canonique HoraTrack : carte topocentrique 360° centrée sur la Terre.
 * La Terre représente l'observateur. L'azimut place Soleil/Lune autour du cadran,
 * l'altitude apparente règle leur distance au centre et le cap réel du téléphone
 * fait tourner la carte. Un astre ne disparaît donc jamais parce qu'il est
 * « derrière l'écran » : cette notion appartient à un viseur AR, pas à une carte
 * du ciel 360°.
 */
object CelestialScreenGeometryV2 {
    /**
     * Seuil pratique de visibilité d'un disque Soleil/Lune : centre géométrique
     * à 50 minutes d'arc sous l'horizon, valeur standard combinant environ 34'
     * de réfraction à l'horizon et 16' de demi-diamètre apparent.
     *
     * Ce n'est pas un « horizon civil » : le crépuscule civil est une notion
     * différente. Le seuil lunaire exact varie légèrement avec son diamètre.
     */
    const val STANDARD_DISK_HORIZON_DEG = -50.0 / 60.0

    /** Ancien nom conservé temporairement pour compatibilité des appelants/tests. */
    @Deprecated("Use STANDARD_DISK_HORIZON_DEG")
    const val CIVIL_HORIZON_DEG = STANDARD_DISK_HORIZON_DEG

    const val ZENITH_RADIUS_FRACTION = 0.34
    private const val HEADING_EPSILON = 1e-6

    /**
     * Projection canonique de l'horloge : ciel visible complet sur 360°.
     * - direction du cap = 12 h ;
     * - horizon = bord externe ;
     * - altitude graphique = altitude apparente corrigée de la réfraction ;
     * - zénith = rayon interne compact afin de préserver la Terre centrale ;
     * - sous le seuil standard du disque au lever/coucher = non rendu.
     */
    fun projectEarthCenteredSky(
        body: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialWatchProjectionV2? {
        if (body.altitudeDeg < STANDARD_DISK_HORIZON_DEG) return null

        val apparentAltitude = AtmosphericRefractionV2
            .apparentAltitudeDeg(body.altitudeDeg)
            .coerceIn(0.0, 90.0)
        val altitudeFraction = apparentAltitude / 90.0
        val radialFraction = 1.0 - altitudeFraction * (1.0 - ZENITH_RADIUS_FRACTION)
        val theta = Math.toRadians(shortestDelta(deviceAzimuthDeg.toDouble(), body.azimuthDeg))

        return CelestialWatchProjectionV2(
            xRadiusFraction = sin(theta) * radialFraction,
            yRadiusFraction = -cos(theta) * radialFraction,
            radialFraction = radialFraction
        )
    }

    /**
     * Compatibilité avec le lot 3D précédent.
     *
     * Le nom historique est conservé pour ne pas casser les appelants, mais la
     * sémantique est maintenant celle de l'horloge 360° : le repère 3D sert à
     * transporter le cap vrai stabilisé, pas à découper le ciel en hémisphère
     * avant/arrière.
     */
    fun projectInDeviceSky(
        body: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialWatchProjectionV2? = projectEarthCenteredSky(
        body = body,
        deviceAzimuthDeg = headingFromFrame(frame).toFloat()
    )

    /** Compatibilité de l'ancien nom du dôme. */
    fun projectOnWatchDome(
        body: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialWatchProjectionV2? = projectEarthCenteredSky(body, deviceAzimuthDeg)

    /** Direction du limbe éclairé dans la carte topocentrique 360°. */
    fun directionToward(
        from: CelestialBodyV2,
        to: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? = directionTowardEarthCentered(
        from = from,
        targetVector = horizontalUnit(to.azimuthDeg, to.altitudeDeg),
        deviceAzimuthDeg = deviceAzimuthDeg
    )

    /** Direction de l'axe d'ombre terrestre vers le vrai anti-Soleil. */
    fun directionTowardAntiSun(
        moon: CelestialBodyV2,
        sun: CelestialBodyV2,
        deviceAzimuthDeg: Float
    ): CelestialScreenDirectionV2? {
        val sunVector = horizontalUnit(sun.azimuthDeg, sun.altitudeDeg)
        return directionTowardEarthCentered(
            from = moon,
            targetVector = doubleArrayOf(-sunVector[0], -sunVector[1], -sunVector[2]),
            deviceAzimuthDeg = deviceAzimuthDeg
        )
    }

    /** Compatibilité : direction 3D précédente convertie dans la carte 360°. */
    fun directionToward(
        from: CelestialBodyV2,
        to: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialScreenDirectionV2? = directionToward(
        from = from,
        to = to,
        deviceAzimuthDeg = headingFromFrame(frame).toFloat()
    )

    /** Compatibilité : anti-Soleil 3D précédent converti dans la carte 360°. */
    fun directionTowardAntiSun(
        moon: CelestialBodyV2,
        sun: CelestialBodyV2,
        frame: CelestialDeviceFrameV2
    ): CelestialScreenDirectionV2? = directionTowardAntiSun(
        moon = moon,
        sun = sun,
        deviceAzimuthDeg = headingFromFrame(frame).toFloat()
    )

    /**
     * Retourne d'abord le cap filtré fourni par CelestialTrackerV2.
     *
     * Le calcul géométrique depuis les axes du frame n'est plus qu'un secours pour
     * les tests ou anciens appelants qui construisent un frame sans cap stabilisé.
     * Cela évite qu'un rendu repasse silencieusement sur l'azimut brut alors que le
     * tracker possède déjà une version filtrée et corrigée vers le Nord vrai.
     */
    fun headingFromFrame(frame: CelestialDeviceFrameV2): Double {
        frame.stabilizedHeadingDeg
            ?.takeIf { it.isFinite() }
            ?.let { return normalizeDegrees(it) }

        val rightHorizontal = sqrt(
            frame.rightEast * frame.rightEast + frame.rightNorth * frame.rightNorth
        )
        if (rightHorizontal > HEADING_EPSILON) {
            // Up × Right = direction horizontale correspondant au haut du cadran.
            val east = -frame.rightNorth / rightHorizontal
            val north = frame.rightEast / rightHorizontal
            return normalizeDegrees(Math.toDegrees(atan2(east, north)))
        }

        val topHorizontal = sqrt(frame.topEast * frame.topEast + frame.topNorth * frame.topNorth)
        val normalHorizontal = sqrt(
            frame.normalEast * frame.normalEast + frame.normalNorth * frame.normalNorth
        )

        val east: Double
        val north: Double
        if (topHorizontal >= normalHorizontal && topHorizontal > HEADING_EPSILON) {
            east = frame.topEast / topHorizontal
            north = frame.topNorth / topHorizontal
        } else if (normalHorizontal > HEADING_EPSILON) {
            east = frame.normalEast / normalHorizontal
            north = frame.normalNorth / normalHorizontal
        } else {
            return 0.0
        }

        return normalizeDegrees(Math.toDegrees(atan2(east, north)))
    }

    private fun directionTowardEarthCentered(
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

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
}
