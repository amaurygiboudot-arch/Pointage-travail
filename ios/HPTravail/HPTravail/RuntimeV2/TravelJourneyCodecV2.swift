import Foundation

/// A GPS observation is a location sample, not proof of mileage or paid work.
struct TravelFixV2: Codable, Equatable {
    let latitude: Double
    let longitude: Double
    let accuracyMeters: Double
    let observedAt: Date

    var isUsable: Bool {
        latitude.isFinite && longitude.isFinite && accuracyMeters.isFinite &&
        (-90...90).contains(latitude) && (-180...180).contains(longitude) &&
        (0...150).contains(accuracyMeters) && observedAt.timeIntervalSince1970 > 0
    }
}

struct TravelJourneyV2: Codable, Equatable, Identifiable {
    let id: String
    let startedAt: Date
    var endedAt: Date?
    let employerId: String?
    var departure: TravelFixV2?
    var arrival: TravelFixV2?
}

enum TravelJourneyCodecV2 {
    private struct Envelope: Codable {
        let version: Int
        let owner: String
        let journeys: [TravelJourneyV2]
    }

    static func isValid(_ trips: [TravelJourneyV2]) -> Bool {
        guard trips.count <= 10_000,
              Set(trips.map(\.id)).count == trips.count,
              trips.filter({ $0.endedAt == nil }).count <= 1 else { return false }
        return trips.allSatisfy { trip in
            guard !trip.id.isEmpty, trip.startedAt.timeIntervalSince1970 > 0 else { return false }
            if let end = trip.endedAt, end <= trip.startedAt { return false }
            if let fix = trip.departure {
                guard fix.isUsable && abs(fix.observedAt.timeIntervalSince(trip.startedAt)) <= 180 else { return false }
            }
            if let fix = trip.arrival {
                guard let end = trip.endedAt,
                      fix.isUsable && abs(fix.observedAt.timeIntervalSince(end)) <= 180 else { return false }
            }
            return true
        }
    }

    static func encode(account: String, trips: [TravelJourneyV2]) -> Data? {
        guard !account.isEmpty, isValid(trips) else { return nil }
        return try? JSONEncoder().encode(Envelope(version: 1, owner: account, journeys: trips))
    }

    static func decode(account: String, data: Data) -> [TravelJourneyV2]? {
        guard !account.isEmpty,
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.version == 1 && envelope.owner == account &&
              isValid(envelope.journeys) else { return nil }
        return envelope.journeys
    }
}
