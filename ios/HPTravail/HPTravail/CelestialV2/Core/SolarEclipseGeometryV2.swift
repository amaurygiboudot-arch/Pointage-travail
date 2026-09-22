import Foundation

enum SolarEclipseStageV2: Equatable, Sendable {
    case none
    case partial
    case annular
    case total
}

struct SolarEclipseV2: Equatable, Sendable {
    let stage: SolarEclipseStageV2
    let angularSeparationDegrees: Double
    let sunAngularRadiusDegrees: Double
    let moonAngularRadiusDegrees: Double
    /// Fraction of the apparent solar-disc surface hidden by the Moon, from 0 to 1.
    let obscuredFraction: Double

    var isEclipse: Bool { stage != .none }
}

enum SolarEclipseGeometryErrorV2: Error, Equatable {
    case invalidBody
    case invalidAngularSeparation
    case invalidSunAngularRadius
    case invalidMoonAngularRadius
}

/// Pure topocentric apparent-disc occultation geometry.
///
/// Symbol sizes used by the UI never participate in this calculation, so a
/// graphical proximity cannot become a false eclipse.
enum SolarEclipseGeometryV2 {
    private static let sunRadiusKilometers = 696_340.0
    private static let moonRadiusKilometers = 1_737.4

    static func evaluate(
        sun: CelestialBodyV2,
        moon: CelestialBodyV2
    ) throws -> SolarEclipseV2 {
        guard sun.azimuthDegrees.isFinite,
              sun.altitudeDegrees.isFinite,
              moon.azimuthDegrees.isFinite,
              moon.altitudeDegrees.isFinite else {
            throw SolarEclipseGeometryErrorV2.invalidBody
        }
        let separation = angularSeparationDegrees(sun, moon)
        let sunRadius = try apparentAngularRadiusDegrees(
            radiusKilometers: sunRadiusKilometers,
            distanceKilometers: sun.distanceKilometers
        )
        let moonRadius = try apparentAngularRadiusDegrees(
            radiusKilometers: moonRadiusKilometers,
            distanceKilometers: moon.distanceKilometers
        )
        return try evaluateDisks(
            angularSeparationDegrees: separation,
            sunAngularRadiusDegrees: sunRadius,
            moonAngularRadiusDegrees: moonRadius
        )
    }

    /// Exposed for deterministic tests and future higher-precision ephemerides.
    static func evaluateDisks(
        angularSeparationDegrees: Double,
        sunAngularRadiusDegrees: Double,
        moonAngularRadiusDegrees: Double
    ) throws -> SolarEclipseV2 {
        guard angularSeparationDegrees.isFinite, angularSeparationDegrees >= 0 else {
            throw SolarEclipseGeometryErrorV2.invalidAngularSeparation
        }
        guard sunAngularRadiusDegrees.isFinite, sunAngularRadiusDegrees > 0 else {
            throw SolarEclipseGeometryErrorV2.invalidSunAngularRadius
        }
        guard moonAngularRadiusDegrees.isFinite, moonAngularRadiusDegrees > 0 else {
            throw SolarEclipseGeometryErrorV2.invalidMoonAngularRadius
        }

        let separation = angularSeparationDegrees
        let sunRadius = sunAngularRadiusDegrees
        let moonRadius = moonAngularRadiusDegrees
        let overlap = circleOverlapArea(
            radius1: sunRadius,
            radius2: moonRadius,
            distance: separation
        )
        let obscured = min(1, max(0, overlap / (.pi * sunRadius * sunRadius)))

        let stage: SolarEclipseStageV2
        if separation >= sunRadius + moonRadius {
            stage = .none
        } else if separation <= abs(moonRadius - sunRadius), moonRadius >= sunRadius {
            stage = .total
        } else if separation <= abs(sunRadius - moonRadius), moonRadius < sunRadius {
            stage = .annular
        } else {
            stage = .partial
        }

        return SolarEclipseV2(
            stage: stage,
            angularSeparationDegrees: separation,
            sunAngularRadiusDegrees: sunRadius,
            moonAngularRadiusDegrees: moonRadius,
            obscuredFraction: obscured
        )
    }

    private static func apparentAngularRadiusDegrees(
        radiusKilometers: Double,
        distanceKilometers: Double
    ) throws -> Double {
        guard distanceKilometers.isFinite, distanceKilometers > radiusKilometers else {
            throw SolarEclipseGeometryErrorV2.invalidBody
        }
        return asin(min(1, max(0, radiusKilometers / distanceKilometers))) * 180 / .pi
    }

    private static func angularSeparationDegrees(
        _ first: CelestialBodyV2,
        _ second: CelestialBodyV2
    ) -> Double {
        let azimuthA = first.azimuthDegrees * .pi / 180
        let altitudeA = first.altitudeDegrees * .pi / 180
        let azimuthB = second.azimuthDegrees * .pi / 180
        let altitudeB = second.altitudeDegrees * .pi / 180
        let cosine = sin(altitudeA) * sin(altitudeB)
            + cos(altitudeA) * cos(altitudeB) * cos(azimuthA - azimuthB)
        return acos(min(1, max(-1, cosine))) * 180 / .pi
    }

    private static func circleOverlapArea(
        radius1: Double,
        radius2: Double,
        distance: Double
    ) -> Double {
        if distance >= radius1 + radius2 { return 0 }
        if distance <= abs(radius1 - radius2) {
            let smallerRadius = min(radius1, radius2)
            return .pi * smallerRadius * smallerRadius
        }

        let safeDistance = max(distance, 1e-12)
        let alpha = acos(min(1, max(
            -1,
            (safeDistance * safeDistance + radius1 * radius1 - radius2 * radius2)
                / (2 * safeDistance * radius1)
        )))
        let beta = acos(min(1, max(
            -1,
            (safeDistance * safeDistance + radius2 * radius2 - radius1 * radius1)
                / (2 * safeDistance * radius2)
        )))
        let triangle = 0.5 * sqrt(max(
            0,
            (-safeDistance + radius1 + radius2)
                * (safeDistance + radius1 - radius2)
                * (safeDistance - radius1 + radius2)
                * (safeDistance + radius1 + radius2)
        ))
        return radius1 * radius1 * alpha + radius2 * radius2 * beta - triangle
    }
}
