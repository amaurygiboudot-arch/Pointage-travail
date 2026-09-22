import Foundation

/// Standard atmospheric refraction used only for apparent on-screen altitude.
///
/// The canonical ephemerides remain geometric for phase, eclipse and day/night
/// calculations. This NOAA-style piecewise approximation assumes an average
/// atmosphere and deliberately does not extrapolate below -1 degree.
enum AtmosphericRefractionV2 {
    /// Standard apparent sunrise/sunset: 34′ refraction plus 16′ solar radius.
    static let standardSolarDiskHorizonDegrees = -50.0 / 60.0

    /// Mean refraction adopted at the horizon: 34 arc minutes.
    static let standardHorizonRefractionDegrees = 34.0 / 60.0

    static func correctionDegrees(geometricAltitudeDegrees: Double) -> Double {
        guard geometricAltitudeDegrees.isFinite else { return 0 }
        let altitude = geometricAltitudeDegrees
        guard altitude < 85, altitude >= -1 else { return 0 }

        let correctionArcSeconds: Double
        if altitude > 5 {
            let tangent = tan(altitude * .pi / 180)
            guard abs(tangent) >= 1e-12 else { return 0 }
            let inverse = 1 / tangent
            correctionArcSeconds = 58.1 * inverse
                - 0.07 * pow(inverse, 3)
                + 0.000086 * pow(inverse, 5)
        } else if altitude >= -0.575 {
            correctionArcSeconds = 1_735
                - 518.2 * altitude
                + 103.4 * pow(altitude, 2)
                - 12.79 * pow(altitude, 3)
                + 0.711 * pow(altitude, 4)
        } else {
            let tangent = tan(altitude * .pi / 180)
            guard abs(tangent) >= 1e-12 else { return 0 }
            correctionArcSeconds = -20.774 / tangent
        }

        return max(0, correctionArcSeconds / 3_600)
    }

    static func apparentAltitudeDegrees(geometricAltitudeDegrees: Double) -> Double {
        geometricAltitudeDegrees + correctionDegrees(
            geometricAltitudeDegrees: geometricAltitudeDegrees
        )
    }
}
