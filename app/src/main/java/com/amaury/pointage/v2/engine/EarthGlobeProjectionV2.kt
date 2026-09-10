package com.amaury.pointage.v2.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EarthGlobePointV2(
    val x: Double,
    val y: Double,
    val depth: Double
) {
    val visible: Boolean get() = depth >= 0.0
}

data class EarthGeoPointV2(
    val latitudeDeg: Double,
    val longitudeDeg: Double
)

/**
 * Projection orthographique d'un globe terrestre centré sur la position GPS.
 *
 * Le point de l'observateur est toujours au centre du disque. Le Nord local reste
 * vers le haut du globe : changer de pays fait tourner la sphère, pas le téléphone.
 */
object EarthGlobeProjectionV2 {
    fun project(
        latitudeDeg: Double,
        longitudeDeg: Double,
        observerLatitudeDeg: Double,
        observerLongitudeDeg: Double
    ): EarthGlobePointV2 {
        require(latitudeDeg in -90.0..90.0)
        require(observerLatitudeDeg in -90.0..90.0)

        val lat = Math.toRadians(latitudeDeg)
        val lonDelta = Math.toRadians(shortestLongitudeDelta(observerLongitudeDeg, longitudeDeg))
        val observerLat = Math.toRadians(observerLatitudeDeg)

        val cosLat = cos(lat)
        val xEast = cosLat * sin(lonDelta)
        val yNorth =
            cos(observerLat) * sin(lat) -
                sin(observerLat) * cosLat * cos(lonDelta)
        val depth =
            sin(observerLat) * sin(lat) +
                cos(observerLat) * cosLat * cos(lonDelta)

        return EarthGlobePointV2(
            x = xEast,
            y = -yNorth,
            depth = depth
        )
    }

    /**
     * Inverse de la projection orthographique pour un point du disque unité.
     * x = droite Est, y = bas écran.
     */
    fun unproject(
        x: Double,
        y: Double,
        observerLatitudeDeg: Double,
        observerLongitudeDeg: Double
    ): EarthGeoPointV2? {
        require(observerLatitudeDeg in -90.0..90.0)

        val yNorth = -y
        val radiusSquared = x * x + yNorth * yNorth
        if (radiusSquared > 1.0) return null

        val depth = sqrt((1.0 - radiusSquared).coerceAtLeast(0.0))
        val observerLat = Math.toRadians(observerLatitudeDeg)
        val observerLon = Math.toRadians(observerLongitudeDeg)

        val eastX = -sin(observerLon)
        val eastY = cos(observerLon)
        val northX = -sin(observerLat) * cos(observerLon)
        val northY = -sin(observerLat) * sin(observerLon)
        val northZ = cos(observerLat)
        val upX = cos(observerLat) * cos(observerLon)
        val upY = cos(observerLat) * sin(observerLon)
        val upZ = sin(observerLat)

        val worldX = x * eastX + yNorth * northX + depth * upX
        val worldY = x * eastY + yNorth * northY + depth * upY
        val worldZ = yNorth * northZ + depth * upZ

        val latitude = Math.toDegrees(asin(worldZ.coerceIn(-1.0, 1.0)))
        val longitude = normalizeLongitude(Math.toDegrees(atan2(worldY, worldX)))

        return EarthGeoPointV2(latitude, longitude)
    }

    private fun shortestLongitudeDelta(fromDeg: Double, toDeg: Double): Double =
        ((toDeg - fromDeg + 540.0) % 360.0) - 180.0

    private fun normalizeLongitude(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0
}
