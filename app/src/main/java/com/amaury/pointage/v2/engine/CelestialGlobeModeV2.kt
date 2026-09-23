package com.amaury.pointage.v2.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class CelestialGlobeModeV2 {
    LOCAL,
    WORLD;

    companion object {
        const val PREFS = "celestial_settings"
        const val PREF_KEY_GLOBE_MODE = "globe_mode"

        fun fromStored(value: String?): CelestialGlobeModeV2 =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}

data class EarthGlobeSceneV2(
    val viewLatitudeDeg: Double,
    val viewLongitudeDeg: Double,
    val sunLatitudeDeg: Double,
    val sunLongitudeDeg: Double,
    val userLatitudeDeg: Double,
    val userLongitudeDeg: Double
)

/**
 * Résout l'orientation du globe sans inventer la lumière.
 *
 * LOCAL : la position GPS reste au centre.
 * WORLD : le centre de vue est placé à 90° du point subsolaire, donc sur le
 * terminateur réel. La moitié jour et la moitié nuit restent ainsi visibles
 * ensemble pendant que la lumière progresse avec le Soleil réel.
 */
object CelestialGlobeSceneResolverV2 {
    fun resolve(
        snapshot: CelestialSnapshotV2,
        mode: CelestialGlobeModeV2
    ): EarthGlobeSceneV2 {
        val sun = subsolarPoint(
            observerLatitudeDeg = snapshot.latitudeDeg,
            observerLongitudeDeg = snapshot.longitudeDeg,
            sunAzimuthDeg = snapshot.sun.azimuthDeg,
            sunAltitudeDeg = snapshot.sun.altitudeDeg
        )
        val viewLatitude = if (mode == CelestialGlobeModeV2.LOCAL) snapshot.latitudeDeg else 0.0
        val viewLongitude = if (mode == CelestialGlobeModeV2.LOCAL) {
            snapshot.longitudeDeg
        } else {
            normalizeLongitude(sun.longitudeDeg + 90.0)
        }
        return EarthGlobeSceneV2(
            viewLatitudeDeg = viewLatitude,
            viewLongitudeDeg = viewLongitude,
            sunLatitudeDeg = sun.latitudeDeg,
            sunLongitudeDeg = sun.longitudeDeg,
            userLatitudeDeg = snapshot.latitudeDeg,
            userLongitudeDeg = snapshot.longitudeDeg
        )
    }

    internal fun subsolarPoint(
        observerLatitudeDeg: Double,
        observerLongitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double
    ): EarthGeoPointV2 {
        require(observerLatitudeDeg in -90.0..90.0)
        require(observerLongitudeDeg.isFinite())
        require(sunAzimuthDeg.isFinite())
        require(sunAltitudeDeg.isFinite())

        val observerLat = Math.toRadians(observerLatitudeDeg)
        val observerLon = Math.toRadians(observerLongitudeDeg)
        val sunAz = Math.toRadians(sunAzimuthDeg)
        val sunAlt = Math.toRadians(sunAltitudeDeg)

        val sunEast = cos(sunAlt) * sin(sunAz)
        val sunNorth = cos(sunAlt) * cos(sunAz)
        val sunUp = sin(sunAlt)

        val eastX = -sin(observerLon)
        val eastY = cos(observerLon)
        val northX = -sin(observerLat) * cos(observerLon)
        val northY = -sin(observerLat) * sin(observerLon)
        val northZ = cos(observerLat)
        val upX = cos(observerLat) * cos(observerLon)
        val upY = cos(observerLat) * sin(observerLon)
        val upZ = sin(observerLat)

        val worldX = sunEast * eastX + sunNorth * northX + sunUp * upX
        val worldY = sunEast * eastY + sunNorth * northY + sunUp * upY
        val worldZ = sunNorth * northZ + sunUp * upZ

        return EarthGeoPointV2(
            latitudeDeg = Math.toDegrees(asin(worldZ.coerceIn(-1.0, 1.0))),
            longitudeDeg = normalizeLongitude(Math.toDegrees(atan2(worldY, worldX)))
        )
    }

    private fun normalizeLongitude(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0
}
