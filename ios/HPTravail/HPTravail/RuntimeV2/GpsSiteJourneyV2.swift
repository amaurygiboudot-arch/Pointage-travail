import Foundation

/// HOME observations are contextual exclusions, never work-session producers.
struct GpsHomeObservationV2: Codable, Equatable, Identifiable {
    let id: UUID
    let account: String
    let fingerprint: String
    let zoneId: UUID
    let entered: Bool
    let observedAt: Date
}

enum GpsHomeObservationStoreV2 {
    static func key(account: String) -> String {
        "horatrack_home_observations_v2." + Data(account.utf8).base64EncodedString()
    }

    static func read(account: String, defaults: UserDefaults) -> [GpsHomeObservationV2]? {
        guard let object = defaults.object(forKey: key(account: account)) else { return [] }
        guard let data = object as? Data,
              let values = try? JSONDecoder().decode([GpsHomeObservationV2].self, from: data),
              Set(values.map(\.id)).count == values.count,
              values.allSatisfy({ $0.account == account && !$0.fingerprint.isEmpty &&
                $0.observedAt.timeIntervalSinceReferenceDate.isFinite }),
              zip(values, values.dropFirst()).allSatisfy({ pair in pair.0.observedAt <= pair.1.observedAt }) else { return nil }
        return values
    }

    @discardableResult
    static func append(account: String, fingerprint: String, zoneId: UUID, entered: Bool,
                       at date: Date, defaults: UserDefaults) -> Bool {
        guard !account.isEmpty, !fingerprint.isEmpty, date.timeIntervalSinceReferenceDate.isFinite,
              var values = read(account: account, defaults: defaults),
              values.last.map({ $0.observedAt <= date }) ?? true else { return false }
        if let prior = values.last(where: { $0.zoneId == zoneId && $0.fingerprint == fingerprint }),
           prior.entered == entered { return true }
        values.append(.init(id: UUID(), account: account, fingerprint: fingerprint,
            zoneId: zoneId, entered: entered, observedAt: date))
        guard let data = try? JSONEncoder().encode(values) else { return false }
        defaults.set(data, forKey: key(account: account))
        return read(account: account, defaults: defaults) == values
    }
}

/// Derived from the canonical pointages, not a second clock. Durations are callback-to-callback
/// intervals; no distance or professional/payroll status is inferred from absent GPS evidence.
struct GpsSiteJourneyV2: Equatable, Identifiable {
    let id: String
    let fromLabel: String
    let toLabel: String
    let departure: Date
    let arrival: Date
    let excludedByHome: Bool
    let homeExclusionConfigured: Bool
    let homeEvidenceReadable: Bool
    var observedDuration: TimeInterval { arrival.timeIntervalSince(departure) }
}

enum GpsSiteJourneyProjectionV2 {
    static func journeys(sessions: [WorkSession], account: String,
                         homeObservations: [GpsHomeObservationV2]?,
                         siteObservations: [GpsSiteObservationV2]? = nil) -> [GpsSiteJourneyV2] {
        if let siteObservations {
            return observedJourneys(siteObservations, account: account, homeObservations: homeObservations)
        }
        guard WorkSessionPersistenceV2.isStructurallyValid(sessions) else { return [] }
        let ordered = sessions.sorted { $0.entry < $1.entry }
        return zip(ordered, ordered.dropFirst()).compactMap { source, target in
            guard let exitId = source.gpsExitEventId, let entryId = target.gpsEntryEventId,
                  source.gpsAccountScope == account, target.gpsAccountScope == account,
                  let fingerprint = source.gpsConfigurationFingerprint,
                  fingerprint == target.gpsConfigurationFingerprint,
                  let sourceZone = source.gpsZoneId, let targetZone = target.gpsZoneId,
                  sourceZone != targetZone, let exit = source.exit, target.entry > exit else { return nil }
            let relevant = (homeObservations ?? []).filter {
                $0.account == account && $0.fingerprint == fingerprint && $0.observedAt <= target.entry
            }
            let insideAtDeparture = Set(relevant.map(\.zoneId)).contains { zone in
                relevant.last(where: { $0.zoneId == zone && $0.observedAt <= exit })?.entered == true
            }
            let entersHome = relevant.contains { $0.entered && $0.observedAt >= exit }
            return GpsSiteJourneyV2(id: exitId.uuidString + "." + entryId.uuidString,
                fromLabel: source.placeLabel ?? "Site de départ", toLabel: target.placeLabel ?? "Site d’arrivée",
                departure: exit, arrival: target.entry, excludedByHome: insideAtDeparture || entersHome,
                homeExclusionConfigured: source.gpsHomeExclusionConfigured == true && target.gpsHomeExclusionConfigured == true,
                homeEvidenceReadable: homeObservations != nil)
        }
    }

    private static func observedJourneys(_ values: [GpsSiteObservationV2], account: String,
                                         homeObservations: [GpsHomeObservationV2]?) -> [GpsSiteJourneyV2] {
        guard values.allSatisfy({ $0.account == account && !$0.fingerprint.isEmpty &&
                $0.observedAt.timeIntervalSinceReferenceDate.isFinite }),
              Set(values.map(\.id)).count == values.count else { return [] }
        let ordered = values.sorted { $0.observedAt < $1.observedAt }
        var activeWork = Set<UUID>()
        var pendingDeparture: GpsSiteObservationV2?
        var context: String?
        var result: [GpsSiteJourneyV2] = []
        var index = 0
        while index < ordered.count {
            let date = ordered[index].observedAt
            var end = index + 1
            while end < ordered.count && ordered[end].observedAt == date { end += 1 }
            let batch = Array(ordered[index ..< end])
            for observation in batch {
                guard let continuity = observation.continuityId, !continuity.isEmpty else {
                    activeWork.removeAll(); pendingDeparture = nil; context = nil
                    continue
                }
                let nextContext = observation.fingerprint + "." + continuity
                if context != nextContext {
                    activeWork.removeAll(); pendingDeparture = nil; context = nextContext
                }
                if batch.count > 1 { pendingDeparture = nil }
                if observation.entered {
                    if batch.count == 1, activeWork.isEmpty, let source = pendingDeparture,
                       source.zoneId != observation.zoneId, observation.observedAt > source.observedAt {
                        let relevant = (homeObservations ?? []).filter {
                            $0.account == account && $0.fingerprint == source.fingerprint &&
                                $0.observedAt <= observation.observedAt
                        }
                        let inside = Set(relevant.map(\.zoneId)).contains { zone in
                            relevant.last(where: { $0.zoneId == zone && $0.observedAt <= source.observedAt })?.entered == true
                        }
                        let enters = relevant.contains { $0.entered && $0.observedAt >= source.observedAt }
                        result.append(.init(id: source.id.uuidString + "." + observation.id.uuidString,
                            fromLabel: source.zoneLabel, toLabel: observation.zoneLabel,
                            departure: source.observedAt, arrival: observation.observedAt,
                            excludedByHome: inside || enters,
                            homeExclusionConfigured: source.homeExclusionConfigured && observation.homeExclusionConfigured,
                            homeEvidenceReadable: homeObservations != nil))
                    }
                    activeWork.insert(observation.zoneId)
                    pendingDeparture = nil
                } else {
                    let departureWasKnown = activeWork.remove(observation.zoneId) != nil
                    pendingDeparture = batch.count == 1 && departureWasKnown && activeWork.isEmpty
                        ? observation : nil
                }
            }
            if batch.count > 1 { pendingDeparture = nil }
            index = end
        }
        return result
    }
}
