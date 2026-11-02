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

/// Index HOME evidence once for all journeys. Occupancy uses the last observation in
/// original journal order at/before departure; any ENTER in the inclusive interval also
/// excludes the journey, even when an EXIT shares that timestamp.
private struct GpsHomeExclusionIndexV2 {
    private struct Context {
        var dates: [Date] = []
        var occupied: [Bool] = []
        var entries: [Date] = []
    }
    private var contexts: [String: Context] = [:]

    init(_ observations: [GpsHomeObservationV2]?, account: String) {
        var grouped: [String: [(offset: Int, value: GpsHomeObservationV2)]] = [:]
        for (offset, value) in (observations ?? []).enumerated() where value.account == account &&
            !value.observedAt.timeIntervalSinceReferenceDate.isNaN {
            grouped[value.fingerprint, default: []].append((offset, value))
        }
        for (fingerprint, values) in grouped {
            let ordered = values.sorted {
                $0.value.observedAt == $1.value.observedAt
                    ? $0.offset < $1.offset : $0.value.observedAt < $1.value.observedAt
            }
            var context = Context()
            var latest: [UUID: (offset: Int, entered: Bool)] = [:]
            var activeCount = 0
            var index = 0
            while index < ordered.count {
                let date = ordered[index].value.observedAt
                repeat {
                    let item = ordered[index]
                    let previous = latest[item.value.zoneId]
                    if previous == nil || previous!.offset < item.offset {
                        if previous?.entered == true { activeCount -= 1 }
                        if item.value.entered { activeCount += 1 }
                        latest[item.value.zoneId] = (item.offset, item.value.entered)
                    }
                    if item.value.entered { context.entries.append(date) }
                    index += 1
                } while index < ordered.count && ordered[index].value.observedAt == date
                context.dates.append(date)
                context.occupied.append(activeCount > 0)
            }
            contexts[fingerprint] = context
        }
    }

    func excludes(fingerprint: String, departure: Date, arrival: Date) -> Bool {
        guard let context = contexts[fingerprint] else { return false }
        let afterDeparture = bound(context.dates, date: departure, afterEqual: true)
        if afterDeparture > 0 && context.occupied[afterDeparture - 1] { return true }
        let firstEntry = bound(context.entries, date: departure, afterEqual: false)
        return firstEntry < context.entries.count && context.entries[firstEntry] <= arrival
    }

    private func bound(_ dates: [Date], date: Date, afterEqual: Bool) -> Int {
        var low = 0
        var high = dates.count
        while low < high {
            let middle = low + (high - low) / 2
            if dates[middle] < date || (afterEqual && dates[middle] == date) { low = middle + 1 }
            else { high = middle }
        }
        return low
    }
}

enum GpsSiteJourneyProjectionV2 {
    static func journeys(sessions: [WorkSession], account: String,
                         homeObservations: [GpsHomeObservationV2]?,
                         siteObservations: [GpsSiteObservationV2]? = nil) -> [GpsSiteJourneyV2] {
        if let siteObservations {
            return observedJourneys(siteObservations, account: account, homeObservations: homeObservations)
        }
        guard WorkSessionPersistenceV2.isStructurallyValid(sessions) else { return [] }
        let homeIndex = GpsHomeExclusionIndexV2(homeObservations, account: account)
        let ordered = sessions.sorted { $0.entry < $1.entry }
        return zip(ordered, ordered.dropFirst()).compactMap { source, target in
            guard let exitId = source.gpsExitEventId, let entryId = target.gpsEntryEventId,
                  source.gpsAccountScope == account, target.gpsAccountScope == account,
                  let fingerprint = source.gpsConfigurationFingerprint,
                  fingerprint == target.gpsConfigurationFingerprint,
                  let sourceZone = source.gpsZoneId, let targetZone = target.gpsZoneId,
                  sourceZone != targetZone, let exit = source.exit, target.entry > exit else { return nil }
            return GpsSiteJourneyV2(id: exitId.uuidString + "." + entryId.uuidString,
                fromLabel: source.placeLabel ?? "Site de départ", toLabel: target.placeLabel ?? "Site d’arrivée",
                departure: exit, arrival: target.entry,
                excludedByHome: homeIndex.excludes(fingerprint: fingerprint, departure: exit, arrival: target.entry),
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
        let homeIndex = GpsHomeExclusionIndexV2(homeObservations, account: account)
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
                        result.append(.init(id: source.id.uuidString + "." + observation.id.uuidString,
                            fromLabel: source.zoneLabel, toLabel: observation.zoneLabel,
                            departure: source.observedAt, arrival: observation.observedAt,
                            excludedByHome: homeIndex.excludes(fingerprint: source.fingerprint,
                                departure: source.observedAt, arrival: observation.observedAt),
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
