import Foundation

struct CelestialPanoramaCoordinateV2: Equatable, Sendable {
    let x01: Double
    let y01: Double
}

/// Géométrie pure du panorama Céleste 360°.
///
/// x01 parcourt l'azimut complet [0, 360°) et y01 place le zénith à 0,
/// l'horizon à 1. L'orientation du téléphone ne réécrit jamais la vérité
/// astronomique : elle déplace uniquement le point de vue dans la texture.
enum CelestialPanoramaGeometryV2 {
    static func normalized(position: LocalStarPositionV2) -> CelestialPanoramaCoordinateV2? {
        let altitude = position.apparentAltitudeDegrees
        guard altitude.isFinite, (0...90).contains(altitude) else { return nil }
        return CelestialPanoramaCoordinateV2(
            x01: normalizedFraction(position.azimuthDegrees / 360),
            y01: 1 - altitude / 90
        )
    }

    static func headingFraction(centerAzimuthDegrees: Double) -> Double {
        normalizedFraction(centerAzimuthDegrees / 360)
    }

    static func screenFraction(skyX01: Double, heading: Double) -> Double {
        var delta = skyX01 - heading
        delta = delta.truncatingRemainder(dividingBy: 1)
        if delta >= 0.5 { delta -= 1 }
        if delta < -0.5 { delta += 1 }
        return 0.5 + delta
    }

    private static func normalizedFraction(_ value: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: 1)
        return remainder >= 0 ? remainder : remainder + 1
    }
}
