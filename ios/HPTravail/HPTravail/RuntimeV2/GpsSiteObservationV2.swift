import Foundation

/// Ordinary process relaunch keeps the monitoring generation. Only a known interruption
/// rotates it; the observations themselves additionally bind account and configuration.
enum GpsObservationContinuityV2 {
    static let key = "horatrack_gps_observation_continuity_v2"

    static func current(defaults: UserDefaults) -> String? {
        if let value = defaults.string(forKey: key), UUID(uuidString: value) != nil { return value }
        return rotate(defaults: defaults)
    }

    static func rotate(defaults: UserDefaults) -> String? {
        let value = UUID().uuidString
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value ? value : nil
    }
}

/// Every accepted WORK boundary is retained even if a manual session already exists or
/// overlapping regions mean that no additional work session should be opened.
struct GpsSiteObservationV2: Codable, Equatable, Identifiable {
    let id: UUID
    let account: String
    let fingerprint: String
    let zoneId: UUID
    let zoneLabel: String
    let employerId: String?
    let entered: Bool
    let observedAt: Date
    var homeExclusionConfigured = false
    var continuityId: String? = nil
}

enum GpsSiteObservationLogV2 {
    static func appending(to existing: [GpsSiteObservationV2], account: String, fingerprint: String,
                          zone: GpsZoneV2, entered: Bool, at date: Date,
                          homeExclusionConfigured: Bool = false,
                          continuityId: String? = nil) -> [GpsSiteObservationV2]? {
        guard !account.isEmpty, !fingerprint.isEmpty, zone.kind == .worksite,
              date.timeIntervalSinceReferenceDate.isFinite,
              existing.allSatisfy({ $0.account == account && $0.fingerprint == fingerprint }),
              existing.last.map({ $0.observedAt <= date }) ?? true else { return nil }
        if let previous = existing.last(where: { $0.zoneId == zone.id }), previous.entered == entered,
           previous.continuityId == continuityId {
            return existing
        }
        return existing + [.init(id: UUID(), account: account, fingerprint: fingerprint,
            zoneId: zone.id, zoneLabel: zone.label, employerId: zone.employerId,
            entered: entered, observedAt: date, homeExclusionConfigured: homeExclusionConfigured,
            continuityId: continuityId)]
    }
}

struct GpsLegacyObservationV2: Codable, Equatable, Identifiable {
    let account: String
    let fingerprint: String
    let event: GpsPendingEventV2
    var id: String { fingerprint + "." + event.id.uuidString }
}

struct GpsObservationArchiveV2: Codable, Equatable {
    var version = 1
    var observations: [GpsSiteObservationV2] = []
    var legacy: [GpsLegacyObservationV2] = []
}

/// Retiring a configuration or old confirmation prompt archives its evidence first.
/// This is never permission to create or close a historical work session.
enum GpsObservationArchiveStoreV2 {
    static func key(account: String) -> String {
        "horatrack_gps_observation_archive_v2." + Data(account.utf8).base64EncodedString()
    }

    static func read(account: String, defaults: UserDefaults) -> GpsObservationArchiveV2? {
        guard let object = defaults.object(forKey: key(account: account)) else { return .init() }
        guard let data = object as? Data,
              let archive = try? JSONDecoder().decode(GpsObservationArchiveV2.self, from: data),
              archive.version == 1,
              Set(archive.observations.map(\.id)).count == archive.observations.count,
              Set(archive.legacy.map(\.id)).count == archive.legacy.count,
              archive.observations.allSatisfy({ $0.account == account && !$0.fingerprint.isEmpty &&
                $0.observedAt.timeIntervalSinceReferenceDate.isFinite }),
              archive.legacy.allSatisfy({ $0.account == account && !$0.fingerprint.isEmpty &&
                $0.event.siteFactVersion == nil && $0.event.occurredAt.timeIntervalSinceReferenceDate.isFinite })
        else { return nil }
        return archive
    }

    @discardableResult
    static func preserve(_ state: GpsPersistedStateV2, account: String, defaults: UserDefaults) -> Bool {
        guard state.accountScope == account, !state.fingerprint.isEmpty,
              var archive = read(account: account, defaults: defaults) else { return false }
        var observationsById = Dictionary(uniqueKeysWithValues: archive.observations.map { ($0.id, $0) })
        var legacyById = Dictionary(uniqueKeysWithValues: archive.legacy.map { ($0.id, $0) })
        var changed = false
        for observation in state.observations ?? [] {
            guard observation.account == account, observation.fingerprint == state.fingerprint,
                  observation.observedAt.timeIntervalSinceReferenceDate.isFinite else { return false }
            if let existing = observationsById[observation.id] {
                guard existing == observation else { return false }
            } else {
                archive.observations.append(observation)
                observationsById[observation.id] = observation
                changed = true
            }
        }
        for event in state.pendingEvents where event.siteFactVersion == nil {
            guard event.occurredAt.timeIntervalSinceReferenceDate.isFinite else { return false }
            let item = GpsLegacyObservationV2(account: account, fingerprint: state.fingerprint, event: event)
            if let existing = legacyById[item.id] {
                guard existing == item else { return false }
            } else {
                archive.legacy.append(item)
                legacyById[item.id] = item
                changed = true
            }
        }
        guard changed else { return true }
        guard let bytes = try? JSONEncoder().encode(archive) else { return false }
        defaults.set(bytes, forKey: key(account: account))
        return read(account: account, defaults: defaults) == archive
    }
}
