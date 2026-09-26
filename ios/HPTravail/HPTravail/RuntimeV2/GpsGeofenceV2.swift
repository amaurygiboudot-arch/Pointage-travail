import CryptoKit
import Foundation

enum GpsZoneKindV2: String, Codable {
    case worksite
}

struct GpsZoneV2: Codable, Equatable, Identifiable {
    let id: UUID
    var label: String
    var latitude: Double
    var longitude: Double
    var radius: Double
    var employerId: String?
    var kind: GpsZoneKindV2
}

enum GpsZonesReadV2: Equatable {
    case missing
    case valid([GpsZoneV2])
    case corrupt
}

enum GpsZoneConfigurationV2 {
    static let maximumZoneCount = 10
    static let zonesKey = "horatrack_gps_zones_v2"
    static let enabledKey = "horatrack_gps_enabled_v2"

    static func read(_ data: Data?) -> GpsZonesReadV2 {
        guard let data else { return .missing }
        guard let zones = try? JSONDecoder().decode([GpsZoneV2].self, from: data),
              isValid(zones) else {
            return .corrupt
        }
        return .valid(zones)
    }

    static func isValid(_ zones: [GpsZoneV2]) -> Bool {
        guard zones.count <= maximumZoneCount,
              Set(zones.map(\.id)).count == zones.count else {
            return false
        }
        return zones.allSatisfy { zone in
            !zone.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && zone.latitude.isFinite
                && (-90 ... 90).contains(zone.latitude)
                && zone.longitude.isFinite
                && (-180 ... 180).contains(zone.longitude)
                && zone.radius.isFinite
                && (50 ... 1_000).contains(zone.radius)
                && zone.kind == .worksite
        }
    }

    static func fingerprint(enabled: Bool, zones: [GpsZoneV2]) -> String? {
        guard enabled, !zones.isEmpty, isValid(zones) else { return nil }
        let canonical = zones.sorted { $0.id.uuidString < $1.id.uuidString }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(canonical) else { return nil }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func regionIdentifier(
        zoneId: UUID,
        fingerprint: String,
        registrationId: UUID
    ) -> String {
        "horatrack.v2.\(fingerprint.prefix(32)).\(registrationId.uuidString).\(zoneId.uuidString)"
    }

    static func zoneId(
        fromRegionIdentifier identifier: String,
        fingerprint: String,
        registrationId: UUID
    ) -> UUID? {
        let prefix = "horatrack.v2.\(fingerprint.prefix(32)).\(registrationId.uuidString)."
        guard identifier.hasPrefix(prefix) else { return nil }
        return UUID(uuidString: String(identifier.dropFirst(prefix.count)))
    }
}

enum GpsPendingKindV2: String, Codable {
    case arrival
    case departure
}

struct GpsPendingEventV2: Codable, Equatable, Identifiable {
    let id: UUID
    var kind: GpsPendingKindV2
    var zoneIds: [UUID]
    var occurredAt: Date
    var expectedSessionId: UUID? = nil
}

enum GpsPresenceTransitionV2 {
    static let maximumPendingEventCount = 32

    enum Transition {
        case enter
        case exit
    }

    struct State: Equatable {
        var activeZoneIds: Set<UUID>
        var pendingExitZoneIds: Set<UUID>
        var pendingEvents: [GpsPendingEventV2]
        var confirmedSessionId: UUID?
        var eventQueueOverflowed: Bool
    }

    static func plan(
        state: State,
        zoneId: UUID,
        transition: Transition,
        occurredAt: Date
    ) -> State {
        var next = state
        switch transition {
        case .enter:
            let wasEmpty = next.activeZoneIds.isEmpty
            next.activeZoneIds.insert(zoneId)
            next.pendingExitZoneIds.remove(zoneId)

            if wasEmpty {
                if let confirmedSessionId = next.confirmedSessionId,
                   let pending = next.pendingEvents.last,
                   pending.kind == .departure,
                   pending.expectedSessionId == confirmedSessionId,
                   pending.zoneIds.contains(zoneId) {
                    guard occurredAt >= pending.occurredAt else { return state }
                    // Tant que la même session reste ouverte, revenir dans une zone qui avait
                    // déclenché la demande de départ invalide cette demande de fin de journée.
                    // La durée d'absence ne transforme pas un ancien EXIT non confirmé en vérité.
                    next.pendingEvents.removeLast()
                } else {
                    appendEvent(GpsPendingEventV2(
                        id: UUID(),
                        kind: .arrival,
                        zoneIds: [zoneId],
                        occurredAt: occurredAt
                    ), to: &next)
                }
            } else if var pending = next.pendingEvents.last, pending.kind == .arrival {
                pending.zoneIds = Array(Set(pending.zoneIds).union(next.activeZoneIds))
                    .sorted { $0.uuidString < $1.uuidString }
                pending.occurredAt = min(pending.occurredAt, occurredAt)
                next.pendingEvents[next.pendingEvents.count - 1] = pending
            }

        case .exit:
            guard next.activeZoneIds.contains(zoneId) else { return state }
            next.activeZoneIds.remove(zoneId)
            next.pendingExitZoneIds.insert(zoneId)

            if next.activeZoneIds.isEmpty {
                appendEvent(GpsPendingEventV2(
                    id: UUID(),
                    kind: .departure,
                    zoneIds: next.pendingExitZoneIds.sorted {
                        $0.uuidString < $1.uuidString
                    },
                    occurredAt: occurredAt,
                    expectedSessionId: next.confirmedSessionId
                ), to: &next)
                next.pendingExitZoneIds.removeAll()
            } else if var pending = next.pendingEvents.last, pending.kind == .arrival {
                pending.zoneIds = pending.zoneIds.filter(next.activeZoneIds.contains)
                if pending.zoneIds.isEmpty {
                    next.pendingEvents.removeLast()
                } else {
                    next.pendingEvents[next.pendingEvents.count - 1] = pending
                }
            }
        }
        return next
    }

    static func reconcileSession(state: State, openSessionId: UUID?) -> State {
        guard state.confirmedSessionId != openSessionId else { return state }
        var next = state
        let staleSessionId = next.confirmedSessionId
        next.confirmedSessionId = nil
        if let staleSessionId {
            next.pendingEvents.removeAll {
                $0.kind == .departure && $0.expectedSessionId == staleSessionId
            }
        }
        return next
    }

    private static func appendEvent(_ event: GpsPendingEventV2, to state: inout State) {
        guard state.pendingEvents.count < maximumPendingEventCount else {
            state.eventQueueOverflowed = true
            return
        }
        state.pendingEvents.append(event)
    }
}

struct GpsPersistedStateV2: Codable, Equatable {
    var fingerprint: String
    var activeZoneIds: Set<UUID>
    var pendingExitZoneIds: Set<UUID>
    var pendingEvents: [GpsPendingEventV2]
    var confirmedSessionId: UUID?
    var eventQueueOverflowed: Bool
}

enum GpsStateReadV2: Equatable {
    case missing
    case valid(GpsPersistedStateV2)
    case corrupt
}

enum GpsStartupStateV2: Equatable {
    case initialize
    case resume(GpsPersistedStateV2)
    case suspend(GpsPersistedStateV2?)
}

enum GpsStateValidationV2 {
    static func startupState(
        read: GpsStateReadV2,
        expectedFingerprint: String,
        configuredZoneIds: Set<UUID>
    ) -> GpsStartupStateV2 {
        switch read {
        case .missing:
            return .initialize
        case .corrupt:
            return .suspend(nil)
        case .valid(let state):
            guard state.fingerprint == expectedFingerprint,
                  state.activeZoneIds.isSubset(of: configuredZoneIds),
                  state.pendingExitZoneIds.isSubset(of: configuredZoneIds),
                  state.activeZoneIds.isDisjoint(with: state.pendingExitZoneIds),
                  state.pendingEvents.allSatisfy({ event in
                      Set(event.zoneIds).isSubset(of: configuredZoneIds)
                  }),
                  zip(state.pendingEvents, state.pendingEvents.dropFirst()).allSatisfy({ pair in
                      pair.0.occurredAt <= pair.1.occurredAt
                  }),
                  !state.eventQueueOverflowed else {
                return .suspend(state)
            }
            return .resume(state)
        }
    }
}

enum GpsStateStoreV2 {
    static let key = "horatrack_gps_state_v2"

    static func read(_ data: Data?) -> GpsStateReadV2 {
        guard let data else { return .missing }
        guard let state = try? JSONDecoder().decode(GpsPersistedStateV2.self, from: data),
              !state.fingerprint.isEmpty,
              state.pendingEvents.count <= GpsPresenceTransitionV2.maximumPendingEventCount,
              Set(state.pendingEvents.map(\.id)).count == state.pendingEvents.count,
              state.pendingEvents.allSatisfy({ event in
                  !event.zoneIds.isEmpty && Set(event.zoneIds).count == event.zoneIds.count
              }) else {
            return .corrupt
        }
        return .valid(state)
    }

    static func write(_ state: GpsPersistedStateV2, defaults: UserDefaults) -> Bool {
        guard let data = try? JSONEncoder().encode(state) else { return false }
        defaults.set(data, forKey: key)
        return read(defaults.data(forKey: key)) == .valid(state)
    }

    static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: key)
    }
}
