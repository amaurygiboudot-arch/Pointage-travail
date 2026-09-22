import Foundation

enum CelestialLocationQualityV2: Equatable, Sendable {
    case valid
    case noPermission
    case unavailable
    case stale
    case inaccurate
}

enum CelestialHeadingQualityV2: Equatable, Sendable {
    case valid
    /// Diagnostic only; never sufficient to orient a sky as exact on iOS.
    case unknownAccuracy
    case inaccurate
    case unreliable
    case stale
    case unavailable
}

/// Pure, platform-independent quality gate used before an iOS location may
/// drive a sky advertised as local and current.
enum CelestialTrackingPolicyV2 {
    static let maximumLocationAge: TimeInterval = 5 * 60
    static let maximumLocationAccuracyMeters = 2_000.0
    static let maximumFutureSkew: TimeInterval = 2 * 60

    static func classify(
        hasPermission: Bool,
        hasLocation: Bool,
        locationAge: TimeInterval?,
        accuracyMeters: Double?
    ) -> CelestialLocationQualityV2 {
        guard hasPermission else { return .noPermission }
        guard hasLocation, let locationAge else { return .unavailable }
        guard locationAge <= maximumLocationAge,
              locationAge >= -maximumFutureSkew else {
            return .stale
        }
        guard let accuracyMeters,
              accuracyMeters.isFinite,
              accuracyMeters >= 0,
              accuracyMeters <= maximumLocationAccuracyMeters else {
            return .inaccurate
        }
        return .valid
    }

    /// A newer but unusable sample must never evict a currently valid sample.
    static func shouldReplaceLocation(
        currentAge: TimeInterval?,
        currentAccuracyMeters: Double?,
        candidateAge: TimeInterval?,
        candidateAccuracyMeters: Double?
    ) -> Bool {
        let current = classify(
            hasPermission: true,
            hasLocation: currentAge != nil,
            locationAge: currentAge,
            accuracyMeters: currentAccuracyMeters
        )
        let candidate = classify(
            hasPermission: true,
            hasLocation: candidateAge != nil,
            locationAge: candidateAge,
            accuracyMeters: candidateAccuracyMeters
        )

        if (current == .valid) != (candidate == .valid) {
            return candidate == .valid
        }
        guard let candidateAge else { return false }
        guard let currentAge else { return true }
        if candidateAge != currentAge { return candidateAge < currentAge }

        let currentAccuracy = usableAccuracy(currentAccuracyMeters)
        let candidateAccuracy = usableAccuracy(candidateAccuracyMeters)
        return candidateAccuracy < currentAccuracy
    }

    private static func usableAccuracy(_ value: Double?) -> Double {
        guard let value, value.isFinite, value >= 0 else { return .infinity }
        return value
    }
}

/// Quality gate for the true-north heading. Core Motion freshness is part of
/// `hasOrientation`: a CLLocation heading alone is not treated as a stable
/// screen/device frame.
enum CelestialHeadingPolicyV2 {
    static let maximumHeadingAccuracyDegrees = 15.0
    static let maximumHeadingAge: TimeInterval = 5

    static func classify(
        hasOrientation: Bool,
        headingAge: TimeInterval?,
        sensorReportedUnreliable: Bool,
        headingAccuracyDegrees: Double?
    ) -> CelestialHeadingQualityV2 {
        guard hasOrientation, let headingAge else { return .unavailable }
        guard headingAge >= 0, headingAge <= maximumHeadingAge else { return .stale }
        guard !sensorReportedUnreliable else { return .unreliable }

        guard let headingAccuracyDegrees else { return .unknownAccuracy }
        guard headingAccuracyDegrees.isFinite, headingAccuracyDegrees >= 0 else {
            return .unreliable
        }
        guard headingAccuracyDegrees <= maximumHeadingAccuracyDegrees else {
            return .inaccurate
        }
        return .valid
    }

    static func isUsable(_ quality: CelestialHeadingQualityV2) -> Bool {
        quality == .valid
    }
}
