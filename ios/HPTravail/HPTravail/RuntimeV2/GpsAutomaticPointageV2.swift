import Foundation

/// A persisted region observation records site presence, never a payroll qualification.
/// The caller commits `sessions` first and acknowledges `state` only after verified storage.
/// Event IDs stored with the canonical session make a crash between those writes replayable.
enum GpsAutomaticPointageV2 {
    struct Commit {
        let sessions: [WorkSession]
        let state: GpsPersistedStateV2
        let event: GpsPendingEventV2
        let sessionId: UUID
    }

    static func plan(
        state: GpsPersistedStateV2,
        zones: [GpsZoneV2],
        account: String,
        sessions: [WorkSession],
        confirmedEmployerIds: Set<String>,
        now: Date
    ) -> Commit? {
        guard !account.isEmpty, state.accountScope == account,
              let fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones),
              GpsStateValidationV2.startupState(read: .valid(state), expectedFingerprint: fingerprint,
                configuredZoneIds: GpsZoneConfigurationV2.automaticZoneIds(zones),
                expectedAccountScope: account) == .resume(state),
              !state.eventQueueOverflowed,
              WorkSessionPersistenceV2.isStructurallyValid(sessions),
              let event = state.pendingEvents.first, event.siteFactVersion == 1,
              event.occurredAt.timeIntervalSinceReferenceDate.isFinite, event.occurredAt <= now else { return nil }

        var updated = sessions
        var acknowledged = state
        acknowledged.pendingEvents.removeFirst()
        let sessionId: UUID

        switch event.kind {
        case .arrival:
            // Several competing worksites require selection; never pick an arbitrary employer.
            let candidates = event.selectedArrivalZoneId.map { [$0] } ?? event.zoneIds
            guard candidates.count == 1, let zoneId = candidates.first, event.zoneIds.contains(zoneId),
                  let zone = zones.first(where: { $0.id == zoneId && $0.kind == .worksite }),
                  let employer = zone.employerId, confirmedEmployerIds.contains(employer) else { return nil }
            if let existing = sessions.first(where: { $0.id == event.id }) {
                guard existing.gpsEntryEventId == event.id, existing.entry == event.occurredAt,
                      existing.employerId == employer, existing.gpsZoneId == zoneId,
                      existing.gpsConfigurationFingerprint == fingerprint,
                      existing.gpsAccountScope == account else { return nil }
                sessionId = existing.id
            } else if let open = sessions.first(where: { $0.exit == nil }) {
                // A manual entry is retained and attached to the observed worksite without
                // changing its declared time. Another automatic visit is not interchangeable.
                guard open.gpsEntryEventId == nil, open.employerId == employer,
                      open.entry <= event.occurredAt else { return nil }
                sessionId = open.id
            } else {
                guard sessions.allSatisfy({ $0.exit.map { $0 <= event.occurredAt } ?? false }) else { return nil }
                var created = WorkSession(id: event.id, entry: event.occurredAt, exit: nil,
                    pauses: [], employerId: employer, placeLabel: zone.label)
                created.segments = [.init(start: event.occurredAt, end: nil, kind: .toConfirm, confirmed: false)]
                created.gpsEntryEventId = event.id
                created.gpsZoneId = zoneId
                created.gpsConfigurationFingerprint = fingerprint
                created.gpsAccountScope = account
                created.gpsHomeExclusionConfigured = zones.contains { $0.kind == .home }
                updated.append(created)
                sessionId = created.id
            }
            guard GpsVisitConfirmationV2.arrival(state: &acknowledged, event: event,
                sessionId: sessionId, zoneId: zoneId, configuredZones: zones),
                  let session = updated.first(where: { $0.id == sessionId }) else { return nil }
            for index in acknowledged.pendingEvents.indices where
                acknowledged.pendingEvents[index].expectedSessionId == sessionId &&
                acknowledged.pendingEvents[index].departureEvidenceVersion == 1 {
                acknowledged.pendingEvents[index].expectedSessionEntry = session.entry
            }

        case .departure:
            guard event.departureEvidenceVersion == 1,
                  let expectedId = event.expectedSessionId,
                  let index = sessions.firstIndex(where: { $0.id == expectedId }),
                  sessions[index].entry == event.expectedSessionEntry,
                  event.occurredAt > sessions[index].entry,
                  GpsDepartureTimeEvidenceV2.isVerified(event, configuredZones: zones),
                  let source = zones.first(where: { $0.id == event.verifiedDepartureZoneId }),
                  let employer = source.employerId, confirmedEmployerIds.contains(employer),
                  sessions[index].employerId == employer,
                  event.zoneIds.allSatisfy({ id in zones.contains { $0.id == id && $0.employerId == employer } }),
                  GpsOverlappingWorkZoneContinuityV2.hasUnambiguousReturnContext(
                    exitedZoneIds: event.zoneIds, enteringZone: source, configuredZones: zones)
            else { return nil }
            sessionId = expectedId
            if sessions[index].gpsEntryEventId != nil {
                guard sessions[index].gpsAccountScope == account,
                      sessions[index].gpsConfigurationFingerprint == fingerprint,
                      sessions[index].gpsZoneId == source.id else { return nil }
            }
            if let exit = sessions[index].exit {
                // Canonical commit succeeded before a missing GPS receipt: acknowledge only.
                guard exit == event.occurredAt, sessions[index].gpsExitEventId == event.id,
                      sessions[index].closureOrigin == "AUTOMATIC",
                      sessions[index].departureObservedAt == event.occurredAt else { return nil }
            } else {
                // Do not overwrite already qualified segments or unresolved pause facts.
                guard sessions[index].pauses.allSatisfy({ $0.paid != nil }),
                      sessions[index].segments.map({ $0.allSatisfy {
                        $0.kind == .toConfirm && !$0.confirmed
                      } }) ?? true else { return nil }
                if updated[index].segments == nil {
                    updated[index].segments = [.init(start: updated[index].entry, end: nil,
                        kind: .toConfirm, confirmed: false)]
                }
                guard let closed = WorkSessionMutationV2.closingSession(in: updated,
                    at: event.occurredAt, expectedSessionId: sessionId) else { return nil }
                updated = closed
                updated[index].gpsExitEventId = event.id
                updated[index].closureOrigin = "AUTOMATIC"
                updated[index].departureObservedAt = event.occurredAt
                updated[index].gpsZoneId = source.id
                updated[index].gpsConfigurationFingerprint = fingerprint
                updated[index].gpsAccountScope = account
                updated[index].gpsHomeExclusionConfigured = zones.contains { $0.kind == .home }
            }
            guard GpsVisitConfirmationV2.departure(state: &acknowledged,
                event: event, sessionId: sessionId) else { return nil }
        }
        guard WorkSessionPersistenceV2.isStructurallyValid(updated) else { return nil }
        return Commit(sessions: updated, state: acknowledged, event: event, sessionId: sessionId)
    }
}
