package com.amaury.pointage.core.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Calcul géométrique pur, sans dépendance Android ni décision de temps de travail. */
object WorkplaceGeometryEngine {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun contains(zone: WorkplaceZone, point: GeoPoint): Boolean {
        if (!zone.enabled) return false
        return when (val geometry = zone.geometry) {
            is WorkplaceGeometry.Circle -> distanceMeters(geometry.center, point) <= geometry.radiusMeters
            is WorkplaceGeometry.Polygon -> pointInPolygon(point, geometry.points)
        }
    }

    fun matchingZoneIds(zones: List<WorkplaceZone>, point: GeoPoint): Set<String> =
        zones.asSequence().filter { contains(it, point) }.map { it.id }.toSet()

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun pointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val crosses = (pi.latitude > point.latitude) != (pj.latitude > point.latitude) &&
                point.longitude < (pj.longitude - pi.longitude) *
                (point.latitude - pi.latitude) / (pj.latitude - pi.latitude) + pi.longitude
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }
}
