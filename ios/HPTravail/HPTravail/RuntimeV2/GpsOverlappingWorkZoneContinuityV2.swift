import Foundation

/// A cross-zone return requires a short callback gap and overlap with every
/// previous WORK region. All must have the entering region's explicit employer:
/// one matching candidate cannot resolve a departure spanning different sites.
/// Neither a common job title nor an implicit employer is proof.
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
              !exitedZoneIds.contains(enteringZone.id) else { return false }
        return hasUnambiguousReturnContext(
            exitedZoneIds: exitedZoneIds,
            enteringZone: enteringZone,
            configuredZones: configuredZones
        )
    }

    /// Same-ID returns keep their existing unlimited gap, but an ID in a mixed
    /// departure is not by itself evidence that its employer/site owns the session.
    static func hasUnambiguousReturnContext(
        exitedZoneIds: [UUID],
        enteringZone: GpsZoneV2,
        configuredZones: [GpsZoneV2]
    ) -> Bool {
        guard GpsZoneConfigurationV2.isValid(configuredZones),
              configuredZones.contains(enteringZone),
              enteringZone.kind == .worksite,
              !exitedZoneIds.isEmpty else { return false }
        let exitedIds = Set(exitedZoneIds)
        let exitedZones = configuredZones.filter { exitedIds.contains($0.id) }
        guard exitedIds.count == exitedZoneIds.count,
              exitedZones.count == exitedIds.count,
              exitedZones.allSatisfy({ $0.kind == .worksite }) else { return false }

        // A single identical configured zone is its own evidence, including
        // existing zones without an employer; do not invent one to accept it.
        if exitedIds == [enteringZone.id] { return true }

        guard let employer = enteringZone.employerId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !employer.isEmpty,
              exitedZones.allSatisfy({
                  $0.employerId?.trimmingCharacters(in: .whitespacesAndNewlines) == employer
              }) else { return false }

        return exitedZones.allSatisfy { exited in
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

/// The runtime and contract tests use this same configuration-aware entry point.
/// A caller cannot substitute a different employer or unconfigured zone for GPS evidence.
enum GpsConfiguredPresenceTransitionV2 {
    static func plan(
        state: GpsPresenceTransitionV2.State,
        configuredZones: [GpsZoneV2],
        zoneId: UUID,
        transition: GpsPresenceTransitionV2.Transition,
        occurredAt: Date
    ) -> GpsPresenceTransitionV2.State {
        guard GpsZoneConfigurationV2.isValid(configuredZones),
              let zone = configuredZones.first(where: { $0.id == zoneId }),
              zone.kind.drivesAutomaticPointage else { return state }

        var sameZoneReturn = false
        var overlappingReturn = false
        if case .enter = transition,
           let sessionId = state.confirmedSessionId,
           let departure = state.pendingEvents.last,
           departure.kind == .departure,
           departure.expectedSessionId == sessionId {
            sameZoneReturn = GpsOverlappingWorkZoneContinuityV2.hasUnambiguousReturnContext(
                exitedZoneIds: departure.zoneIds,
                enteringZone: zone,
                configuredZones: configuredZones
            )
            overlappingReturn = GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
                exitedZoneIds: departure.zoneIds,
                enteringZone: zone,
                configuredZones: configuredZones,
                exitAt: departure.occurredAt,
                entryAt: occurredAt
            )
        }
        var next = GpsPresenceTransitionV2.plan(
            state: state,
            zoneId: zoneId,
            transition: transition,
            occurredAt: occurredAt,
            allowsSameZoneReturn: sameZoneReturn,
            verifiedOverlappingWorksiteReturn: overlappingReturn
        )
        if let sourceZoneId = next.confirmedZoneId,
           let departure = next.pendingEvents.last,
           departure.kind == .departure,
           departure.expectedSessionId == next.confirmedSessionId,
           departure.id != state.pendingEvents.last?.id {
            next.pendingEvents[next.pendingEvents.count - 1] = GpsDepartureTimeEvidenceV2.resolving(
                departure, sourceZoneId: sourceZoneId, configuredZones: configuredZones
            )
        }
        return next
    }
}

/// Exit observations belong to the persisted visit/configuration. The selected
/// arrival zone binds them to the confirmed session, including delayed arrivals.
enum GpsDepartureTimeEvidenceV2 {
    static func observationsAreValid(_ observations: [GpsZoneExitObservationV2]?, zoneIds: Set<UUID>) -> Bool {
        guard let observations else { return true } // Legacy evidence stays unresolved.
        return observations.count <= GpsPresenceTransitionV2.maximumExitObservationCount &&
            observations.allSatisfy {
                zoneIds.contains($0.zoneId) && $0.occurredAt.timeIntervalSinceReferenceDate.isFinite &&
                    ($0.continuingZoneIds.map { continuing in
                        Set(continuing).count == continuing.count && Set(continuing).isSubset(of: zoneIds)
                    } ?? true)
            } && zip(observations, observations.dropFirst()).allSatisfy { pair in
                pair.0.occurredAt <= pair.1.occurredAt
            }
    }

    static func resolving(
        _ event: GpsPendingEventV2,
        sourceZoneId: UUID,
        configuredZones: [GpsZoneV2]
    ) -> GpsPendingEventV2 {
        var resolved = event
        resolved.verifiedDepartureZoneId = nil
        guard let date = departureDate(event, sourceZoneId: sourceZoneId, configuredZones: configuredZones) else {
            return resolved
        }
        resolved.occurredAt = date
        resolved.verifiedDepartureZoneId = sourceZoneId
        return resolved
    }

    static func isVerified(_ event: GpsPendingEventV2, configuredZones: [GpsZoneV2]) -> Bool {
        guard let sourceZoneId = event.verifiedDepartureZoneId else { return false }
        return departureDate(event, sourceZoneId: sourceZoneId, configuredZones: configuredZones) == event.occurredAt
    }

    private static func departureDate(
        _ event: GpsPendingEventV2,
        sourceZoneId: UUID,
        configuredZones: [GpsZoneV2]
    ) -> Date? {
        guard event.kind == .departure, event.expectedSessionId != nil,
              GpsZoneConfigurationV2.isValid(configuredZones),
              Set(event.zoneIds).isSubset(of: GpsZoneConfigurationV2.automaticZoneIds(configuredZones)),
              configuredZones.contains(where: { $0.id == sourceZoneId && $0.kind == .worksite }),
              let observations = event.exitObservations,
              observationsAreValid(observations, zoneIds: Set(event.zoneIds)),
              Set(observations.map(\.zoneId)) == Set(event.zoneIds),
              let sourceIndex = observations.lastIndex(where: { $0.zoneId == sourceZoneId }) else { return nil }

        var latest = observations[sourceIndex].occurredAt
        var frontier = [sourceIndex]
        var visited = Set<Int>()
        while let index = frontier.popLast() {
            guard visited.insert(index).inserted else { continue }
            let observation = observations[index]
            latest = max(latest, observation.occurredAt)
            for continuingId in observation.continuingZoneIds ?? [] {
                guard let continuing = configuredZones.first(where: { $0.id == continuingId }),
                      GpsOverlappingWorkZoneContinuityV2.hasUnambiguousReturnContext(
                          exitedZoneIds: [observation.zoneId], enteringZone: continuing,
                          configuredZones: configuredZones
                      ),
                      let nextIndex = observations.indices.dropFirst(index + 1).first(where: {
                          observations[$0].zoneId == continuingId
                      }) else { continue }
                // Sequence order identifies the episode even when callbacks
                // share a timestamp. Never skip to a later re-entry's exit.
                frontier.append(nextIndex)
            }
        }
        return latest
    }
}
