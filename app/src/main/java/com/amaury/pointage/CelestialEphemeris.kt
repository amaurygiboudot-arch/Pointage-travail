package com.amaury.pointage

import com.amaury.pointage.v2.HoraTrackV2

/**
 * Compatibility facade for old UI code.
 *
 * The astronomical source of truth now belongs to HoraTrack V2. This object is
 * deliberately kept only so an old caller cannot silently reactivate the former
 * lunar solver. New code must use HoraTrackV2.celestial directly.
 */
@Deprecated("Use HoraTrackV2.celestial")
object CelestialEphemeris {
    data class Position(
        val azimuth: Double,
        val altitude: Double,
        /** Relative apparent-size factor. 1.0 = mean apparent diameter. */
        val apparentScale: Double = 1.0
    )

    fun sun(
        latitude: Double,
        longitude: Double,
        timeMs: Long = System.currentTimeMillis()
    ): Position {
        val body = HoraTrackV2.celestial.snapshot(latitude, longitude, timeMs).sun
        return Position(body.azimuthDeg, body.altitudeDeg, body.apparentScale)
    }

    fun moon(
        latitude: Double,
        longitude: Double,
        timeMs: Long = System.currentTimeMillis()
    ): Position {
        val body = HoraTrackV2.celestial.snapshot(latitude, longitude, timeMs).moon
        return Position(body.azimuthDeg, body.altitudeDeg, body.apparentScale)
    }
}
