import Foundation

struct CelestialDialPointV2: Equatable, Sendable {
    /// Normalized horizontal offset from dial centre; east/right is positive.
    let x: Double
    /// Normalized vertical offset from dial centre; south/down is positive.
    let y: Double
}

/// Pure 360-degree topocentric dial projection shared by UI and tests.
enum CelestialDialProjectionV2 {
    static let civilHorizonDegrees = -0.833
    static let protectedZenithRadiusFraction = 0.34

    static func project(
        azimuthDegrees: Double,
        altitudeDegrees: Double,
        trueHeadingDegrees: Double
    ) -> CelestialDialPointV2? {
        guard azimuthDegrees.isFinite,
              altitudeDegrees.isFinite,
              trueHeadingDegrees.isFinite,
              altitudeDegrees >= civilHorizonDegrees else {
            return nil
        }

        let relativeAzimuth = (azimuthDegrees - trueHeadingDegrees) * .pi / 180
        let apparentAltitude = AtmosphericRefractionV2.apparentAltitudeDegrees(
            geometricAltitudeDegrees: altitudeDegrees
        )
        let visibleAltitude = min(90, max(0, apparentAltitude))
        let altitudeFraction = visibleAltitude / 90
        let radius = 1 - (1 - protectedZenithRadiusFraction) * altitudeFraction
        return CelestialDialPointV2(
            x: sin(relativeAzimuth) * radius,
            y: -cos(relativeAzimuth) * radius
        )
    }
}
