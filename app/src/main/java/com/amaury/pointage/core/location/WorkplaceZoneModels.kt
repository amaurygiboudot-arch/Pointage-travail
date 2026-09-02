package com.amaury.pointage.core.location

/**
 * Domaine GPS du nouveau moteur HoraTrack.
 *
 * Une zone décrit uniquement une présence géographique. Elle ne crée jamais à elle seule
 * une entrée, une sortie ou du temps de travail.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "longitude must be between -180 and 180" }
    }
}

enum class WorkplaceZoneKind {
    SITE,
    PARKING,
    BUILDING,
    WORK_AREA,
    OFFICE,
    WORKSHOP,
    OTHER
}

sealed class WorkplaceGeometry {
    data class Circle(
        val center: GeoPoint,
        val radiusMeters: Double
    ) : WorkplaceGeometry() {
        init {
            require(radiusMeters > 0.0) { "radiusMeters must be > 0" }
        }
    }

    data class Polygon(
        val points: List<GeoPoint>
    ) : WorkplaceGeometry() {
        init {
            require(points.size >= 3) { "a polygon requires at least 3 points" }
            require(points.distinct().size >= 3) { "a polygon requires at least 3 distinct points" }
        }
    }
}

data class WorkplaceZone(
    val id: String,
    val name: String,
    val kind: WorkplaceZoneKind,
    val geometry: WorkplaceGeometry,
    val parentZoneId: String? = null,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "zone id must not be blank" }
        require(name.isNotBlank()) { "zone name must not be blank" }
        require(parentZoneId != id) { "a zone cannot be its own parent" }
    }
}

data class LocationObservation(
    val occurredAtMs: Long,
    val point: GeoPoint,
    val accuracyMeters: Float? = null
) {
    init {
        require(occurredAtMs >= 0L) { "occurredAtMs must be >= 0" }
        require(accuracyMeters == null || accuracyMeters >= 0f) { "accuracyMeters must be >= 0" }
    }
}

data class ZonePresenceFact(
    val zoneId: String,
    val enteredAtMs: Long,
    val exitedAtMs: Long?
) {
    init {
        require(zoneId.isNotBlank()) { "zoneId must not be blank" }
        require(exitedAtMs == null || exitedAtMs >= enteredAtMs) { "exit must be after entry" }
    }
}
