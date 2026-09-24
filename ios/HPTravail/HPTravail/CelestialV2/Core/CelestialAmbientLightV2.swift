import Foundation

enum CelestialAmbientLightQualityV2: Equatable, Sendable {
    case valid
    case stale
    case unavailable
    case invalid
}

struct CelestialAmbientLightStateV2: Equatable, Sendable {
    let lux: Double?
    let quality: CelestialAmbientLightQualityV2
    let measuredAt: Date?

    func age(at date: Date) -> TimeInterval? {
        guard let measuredAt else { return nil }
        return max(0, date.timeIntervalSince(measuredAt))
    }
}

/// iOS does not expose the ambient-light sensor through a public API.
/// The canonical owner therefore reports "unavailable" instead of substituting
/// screen brightness or another unrelated signal.
enum CelestialAmbientLightV2 {
    static var currentState: CelestialAmbientLightStateV2 {
        CelestialAmbientLightStateV2(
            lux: nil,
            quality: .unavailable,
            measuredAt: nil
        )
    }
}
