import Foundation

/// GPS is presence evidence, never a payroll classification or an instruction to close.
enum DeparturePolicyV2 {
    static func eligibleObservation(state: GpsPersistedStateV2, account: String,
                                    session: WorkSession, zones: [GpsZoneV2], now: Date) -> Date? {
        guard state.accountScope == account, session.exit == nil,
              let employer = session.employerId,
              !employer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              state.fingerprint == GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones),
              !state.eventQueueOverflowed, state.activeZoneIds.isEmpty,
              state.pendingExitZoneIds.isEmpty, state.pendingEvents.count == 1,
              state.confirmedSessionId == session.id,
              let event = state.pendingEvents.first, event.kind == .departure,
              event.departureEvidenceVersion == 1, event.expectedSessionId == session.id,
              event.expectedSessionEntry == session.entry,
              event.occurredAt > session.entry, event.occurredAt <= now,
              now.timeIntervalSince(event.exitObservations?.last?.occurredAt ?? event.occurredAt)
                >= GpsOverlappingWorkZoneContinuityV2.maximumGap,
              GpsDepartureTimeEvidenceV2.isVerified(event, configuredZones: zones),
              let source = zones.first(where: { $0.id == event.verifiedDepartureZoneId }),
              source.employerId == employer,
              // A different company/visit in the same exit episode is unresolved evidence.
              event.zoneIds.allSatisfy({ id in zones.contains { $0.id == id && $0.employerId == employer } }),
              GpsOverlappingWorkZoneContinuityV2.hasUnambiguousReturnContext(
                exitedZoneIds: event.zoneIds, enteringZone: source, configuredZones: zones),
              session.pauses.allSatisfy({ $0.end != nil && $0.paid != nil && $0.end! <= event.occurredAt })
        else { return nil }
        return event.occurredAt
    }

    static func automaticExit(state: GpsPersistedStateV2, account: String, session: WorkSession,
                              zones: [GpsZoneV2], policy: DepartureAccountPolicyV2,
                              movementAndAmbiguityResolved: Bool, now: Date) -> Date? {
        guard policy.enabled, policy.account == account, policy.employerId == session.employerId,
              policy.expectedSessionId == session.id, policy.expectedEntry == session.entry,
              let expectedEnd = policy.expectedEnd, expectedEnd > session.entry,
              movementAndAmbiguityResolved,
              let observed = eligibleObservation(state: state, account: account, session: session, zones: zones, now: now),
              observed >= expectedEnd else { return nil }
        return observed
    }
}

/// Minimum configuration API: explicit opt-in, an absolute expected end tied to an arrival,
/// and an exact account/company namespace. No implicit eight-hour day or midnight heuristic.
struct DepartureAccountPolicyV2: Codable, Equatable {
    let account: String
    let employerId: String
    var enabled = false
    var expectedSessionId: UUID? = nil
    var expectedEntry: Date? = nil
    var expectedEnd: Date? = nil
}

enum DepartureAccountPolicyStoreV2 {
    static func key(account: String, employerId: String) -> String {
        "horatrack_departure_policy_v2." + Data(account.utf8).base64EncodedString()
            + "." + Data(employerId.utf8).base64EncodedString()
    }
    static func read(account: String, employerId: String, defaults: UserDefaults) -> DepartureAccountPolicyV2? {
        let key = key(account: account, employerId: employerId)
        guard defaults.object(forKey: key) != nil else {
            return DepartureAccountPolicyV2(account: account, employerId: employerId)
        }
        guard let data = defaults.data(forKey: key),
              let policy = try? JSONDecoder().decode(DepartureAccountPolicyV2.self, from: data),
              policy.account == account, policy.employerId == employerId else { return nil }
        return policy
    }
    static func write(_ policy: DepartureAccountPolicyV2, defaults: UserDefaults) -> Bool {
        guard !policy.account.isEmpty, !policy.employerId.isEmpty,
              policy.expectedEnd.map({ $0.timeIntervalSinceReferenceDate.isFinite }) ?? true,
              let data = try? JSONEncoder().encode(policy) else { return false }
        defaults.set(data, forKey: key(account: policy.account, employerId: policy.employerId))
        return read(account: policy.account, employerId: policy.employerId, defaults: defaults) == policy
    }
}
