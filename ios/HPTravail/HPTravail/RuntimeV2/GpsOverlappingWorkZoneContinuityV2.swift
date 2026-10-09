import Foundation

/// A cross-zone return is proven only when two WORK circular regions overlap,
/// both refer to the same explicitly confirmed employer, and the GPS callback
/// gap is short. Neither a common job title nor an implicit employer is proof.
/// Presence evidence never closes a session or creates paid time.
enum GpsOverlappingWorkZoneContinuityV2 {
    static let maximumGap: TimeInterval = 120
    private static let earthRadiusMeters = 6_371_000.0

    static func isProvenSameWorksite(
        exitedZoneIds: [UUID],
        enteringZone: GpsZoneV2,
        configuredZones: [GpsZoneV2],
        exitAt: Date,
        entryAt: Date
    ) -> Bool {
        let elapsed = entryAt.timeIntervalSince(exitAt)
        guard elapsed.isFinite, elapsed >= 0, elapsed <= maximumGap,
              enteringZone.kind == .worksite,
              let employer = enteringZone.employerId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !employer.isEmpty, !exitedZoneIds.isEmpty else { return false }
        return configuredZones.contains { exited in
            guard exitedZoneIds.contains(exited.id),
                  exited.id != enteringZone.id, exited.kind == .worksite,
                  exited.employerId?.trimmingCharacters(in: .whitespacesAndNewlines) == employer,
                  exited.radius.isFinite, enteringZone.radius.isFinite,
                  exited.radius > 0, enteringZone.radius > 0 else {
                return false
            }
            let distance = metersBetween(
                exited.latitude, exited.longitude,
                enteringZone.latitude, enteringZone.longitude
            )
            return distance.isFinite && distance <= exited.radius + enteringZone.radius
        }
    }

    private static func metersBetween(
        _ lat1: Double, _ lon1: Double,
        _ lat2: Double, _ lon2: Double
    ) -> Double {
        let a = lat1 * .pi / 180
        let b = lat2 * .pi / 180
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let h = pow(sin(dLat / 2), 2) +
            cos(a) * cos(b) * pow(sin(dLon / 2), 2)
        return 2 * earthRadiusMeters * atan2(sqrt(max(0, min(1, h))), sqrt(max(0, 1 - h)))
    }
}
