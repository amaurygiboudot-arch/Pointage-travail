package com.amaury.pointage.v2.engine

data class CelestialPanoramaCoordinateV2(
    val x01: Double,
    val y01: Double
)

/**
 * Géométrie pure du panorama Céleste 360°.
 *
 * x01 parcourt l'azimut complet [0, 360°) et y01 place le zénith à 0,
 * l'horizon à 1. Le cap du téléphone ne modifie jamais les coordonnées
 * astronomiques : il ne fait que translater cette texture de référence.
 */
object CelestialPanoramaGeometryV2 {
    fun normalized(position: LocalStarPositionV2): CelestialPanoramaCoordinateV2? {
        val altitude = position.apparentAltitudeDeg
        if (!altitude.isFinite() || altitude !in 0.0..90.0) return null
        val azimuth = normalizeDegrees(position.azimuthDeg)
        return CelestialPanoramaCoordinateV2(
            x01 = azimuth / 360.0,
            y01 = 1.0 - altitude / 90.0
        )
    }

    fun baseLeftFraction(centerAzimuthDeg: Double): Double =
        0.5 - normalizeDegrees(centerAzimuthDeg) / 360.0

    fun screenX01(azimuthDeg: Double, centerAzimuthDeg: Double): Double {
        val delta = signedDegrees(normalizeDegrees(azimuthDeg) - normalizeDegrees(centerAzimuthDeg))
        return (0.5 + delta / 360.0).coerceIn(0.0, 1.0)
    }

    private fun normalizeDegrees(value: Double): Double =
        ((value % 360.0) + 360.0) % 360.0

    private fun signedDegrees(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0
}
