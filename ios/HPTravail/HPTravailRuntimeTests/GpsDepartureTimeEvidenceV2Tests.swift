import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsDepartureTimeEvidenceV2Tests: XCTestCase {
    private let a = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    private let b = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    private let sessionId = UUID()
    private let exitAt = Date(timeIntervalSince1970: 1_800_000_000)

    func testSelectedZoneExitSurvivesRestartAndDifferentEmployerRemainingSevenMinutes() throws {
        let zones = [zone(a), zone(b, employer: "employer-b")]
        let partial = transition(inside(), zones: zones, id: a, kind: .exit, at: exitAt)
        XCTAssertTrue(partial.pendingEvents.isEmpty)
        let reloaded = try roundTrip(partial, zones: zones)
        let outside = transition(reloaded, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        let departure = try XCTUnwrap(outside.pendingEvents.first)
        XCTAssertEqual(departure.occurredAt, exitAt)
        XCTAssertEqual(departure.expectedSessionId, sessionId)
        XCTAssertEqual(departure.verifiedDepartureZoneId, a)
        XCTAssertTrue(GpsDepartureTimeEvidenceV2.isVerified(departure, configuredZones: zones))
        let original = [WorkSession(id: sessionId, entry: exitAt.addingTimeInterval(-8 * 3600), exit: nil, pauses: [])]
        let closed = try XCTUnwrap(WorkSessionMutationV2.closingSession(
            in: original, at: departure.occurredAt, expectedSessionId: departure.expectedSessionId
        ))
        XCTAssertEqual(closed[0].exit, exitAt)
    }

    func testOnlyProvenEquivalentRemainingWorkZoneExtendsSelectedSession() throws {
        for (other, expected) in [
            (zone(b), exitAt.addingTimeInterval(420)),
            (zone(b, latitude: 47.0), exitAt),
            (zone(b, employer: nil), exitAt)
        ] {
            let zones = [zone(a), other]
            let leftA = transition(inside(), zones: zones, id: a, kind: .exit, at: exitAt)
            let outside = transition(leftA, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
            let departure = try XCTUnwrap(outside.pendingEvents.first)
            XCTAssertEqual(departure.occurredAt, expected)
            XCTAssertTrue(GpsDepartureTimeEvidenceV2.isVerified(departure, configuredZones: zones))
        }
    }

    func testEquivalentContinuationSurvivesAnUnrelatedWorkZoneStillActive() throws {
        let c = UUID()
        let zones = [zone(a), zone(b), zone(c, employer: "employer-c")]
        var state = inside()
        state.activeZoneIds.insert(c)
        state = transition(state, zones: zones, id: a, kind: .exit, at: exitAt)
        state = try roundTrip(state, zones: zones)
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        state = transition(state, zones: zones, id: c, kind: .exit, at: exitAt.addingTimeInterval(600))
        XCTAssertEqual(state.pendingEvents.first?.occurredAt, exitAt.addingTimeInterval(420))
    }

    func testEquivalentZoneEnteringAfterSourceExitCannotBridgeUnrelatedPresence() {
        let c = UUID()
        let zones = [zone(a), zone(b), zone(c, employer: "employer-c")]
        var state = inside()
        state.activeZoneIds = [a, c]
        state = transition(state, zones: zones, id: a, kind: .exit, at: exitAt)
        state = transition(state, zones: zones, id: b, kind: .enter, at: exitAt.addingTimeInterval(60))
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        state = transition(state, zones: zones, id: c, kind: .exit, at: exitAt.addingTimeInterval(600))
        XCTAssertEqual(state.pendingEvents.first?.occurredAt, exitAt)
    }

    func testLaterReentryOfContinuationZoneKeepsItsFirstConnectedExit() throws {
        let c = UUID()
        let zones = [zone(a), zone(b), zone(c, employer: "employer-c")]
        var state = inside()
        state.activeZoneIds.insert(c)
        state = transition(state, zones: zones, id: a, kind: .exit, at: exitAt)
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        state = transition(state, zones: zones, id: b, kind: .enter, at: exitAt.addingTimeInterval(480))
        state = try roundTrip(state, zones: zones)
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(540))
        state = transition(state, zones: zones, id: c, kind: .exit, at: exitAt.addingTimeInterval(600))
        XCTAssertEqual(state.pendingEvents.first?.occurredAt, exitAt.addingTimeInterval(420))
    }

    func testConnectedWorkZoneChainCanContinueBeyondDirectSourceOverlap() {
        let c = UUID()
        let zones = [zone(a), zone(b, latitude: 46.7015), zone(c, latitude: 46.703)]
        var state = transition(inside(), zones: zones, id: a, kind: .exit, at: exitAt)
        state = transition(state, zones: zones, id: c, kind: .enter, at: exitAt.addingTimeInterval(60))
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        state = transition(state, zones: zones, id: c, kind: .exit, at: exitAt.addingTimeInterval(600))
        XCTAssertEqual(state.pendingEvents.first?.occurredAt, exitAt.addingTimeInterval(600))
    }

    func testEqualCallbackTimestampsUseEpisodeOrderToFindTheFollowingExit() {
        let c = UUID()
        let zones = [zone(a), zone(b), zone(c, employer: "employer-c")]
        var state = inside()
        state.activeZoneIds.insert(c)
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt)
        state = transition(state, zones: zones, id: b, kind: .enter, at: exitAt)
        state = transition(state, zones: zones, id: a, kind: .exit, at: exitAt)
        state = transition(state, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        state = transition(state, zones: zones, id: c, kind: .exit, at: exitAt.addingTimeInterval(600))
        XCTAssertEqual(state.pendingEvents.first?.occurredAt, exitAt.addingTimeInterval(420))
    }

    func testDelayedArrivalSelectsOriginalZoneAndResolvesAlreadyQueuedDeparture() throws {
        let zones = [zone(a), zone(b, employer: "employer-b")]
        let empty = GpsPresenceTransitionV2.State(activeZoneIds: [], pendingExitZoneIds: [],
            pendingEvents: [], confirmedSessionId: nil, eventQueueOverflowed: false)
        let first = transition(empty, zones: zones, id: a, kind: .enter, at: exitAt.addingTimeInterval(-3600))
        let both = transition(first, zones: zones, id: b, kind: .enter, at: exitAt.addingTimeInterval(-30))
        let leftA = transition(both, zones: zones, id: a, kind: .exit, at: exitAt)
        let outside = transition(leftA, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        var state = persisted(outside, zones: zones)
        let arrival = state.pendingEvents.removeFirst()
        XCTAssertTrue(arrival.zoneIds.contains(a))
        XCTAssertFalse(GpsDepartureTimeEvidenceV2.isVerified(state.pendingEvents[0], configuredZones: zones))
        XCTAssertTrue(GpsVisitConfirmationV2.arrival(state: &state, event: arrival,
            sessionId: sessionId, zoneId: a, configuredZones: zones))
        XCTAssertEqual(state.pendingEvents[0].occurredAt, exitAt)
        XCTAssertEqual(state.pendingEvents[0].verifiedDepartureZoneId, a)
        XCTAssertEqual(state.pendingEvents[0].expectedSessionId, sessionId)
    }

    func testReturnToSourceWhileAnotherZoneActiveReplacesItsOldExitObservation() throws {
        let zones = [zone(a), zone(b, employer: "employer-b")]
        let leftA = transition(inside(), zones: zones, id: a, kind: .exit, at: exitAt)
        let returned = transition(leftA, zones: zones, id: a, kind: .enter, at: exitAt.addingTimeInterval(60))
        XCTAssertEqual(returned.pendingExitObservations?.count, 1)
        let leftAgain = transition(returned, zones: zones, id: a, kind: .exit, at: exitAt.addingTimeInterval(120))
        let outside = transition(leftAgain, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        XCTAssertEqual(outside.pendingEvents.first?.occurredAt, exitAt.addingTimeInterval(120))
    }

    func testLegacyMissingEvidenceRemainsReadableButCannotValidateAnInventedExit() throws {
        let zones = [zone(a), zone(b)]
        var legacy = inside()
        legacy.confirmedZoneId = nil
        let leftA = transition(legacy, zones: zones, id: a, kind: .exit, at: exitAt)
        let outside = transition(leftA, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        XCTAssertFalse(GpsDepartureTimeEvidenceV2.isVerified(try XCTUnwrap(outside.pendingEvents.first), configuredZones: zones))
        let legacyReturn = transition(outside, zones: zones, id: a, kind: .enter,
            at: exitAt.addingTimeInterval(8 * 3600))
        XCTAssertNil(legacyReturn.confirmedZoneId)

        let encoded = try JSONEncoder().encode(persisted(inside(), zones: zones))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object.removeValue(forKey: "confirmedZoneId")
        object.removeValue(forKey: "pendingExitObservations")
        guard case .valid(let oldState) = GpsStateStoreV2.read(try JSONSerialization.data(withJSONObject: object)) else {
            return XCTFail("Existing installations must still decode their GPS state")
        }
        XCTAssertNil(oldState.confirmedZoneId)
        XCTAssertNil(oldState.pendingExitObservations)

        let missingObservation = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [a, b],
            occurredAt: exitAt.addingTimeInterval(420), expectedSessionId: sessionId,
            exitObservations: [.init(zoneId: b, occurredAt: exitAt.addingTimeInterval(420))])
        let unresolved = GpsDepartureTimeEvidenceV2.resolving(missingObservation,
            sourceZoneId: a, configuredZones: zones)
        XCTAssertNil(unresolved.verifiedDepartureZoneId)
        XCTAssertFalse(GpsDepartureTimeEvidenceV2.isVerified(unresolved, configuredZones: zones))
    }

    func testSessionOrConfigurationChangeDoesNotReuseOldBinding() throws {
        let zones = [zone(a), zone(b)]
        let partial = transition(inside(), zones: zones, id: a, kind: .exit, at: exitAt)
        let changed = GpsPresenceTransitionV2.reconcileSession(state: partial, openSessionId: UUID())
        XCTAssertNil(changed.confirmedZoneId)
        let outside = transition(changed, zones: zones, id: b, kind: .exit, at: exitAt.addingTimeInterval(420))
        XCTAssertFalse(GpsDepartureTimeEvidenceV2.isVerified(try XCTUnwrap(outside.pendingEvents.first), configuredZones: zones))
        let stored = persisted(partial, zones: zones)
        XCTAssertEqual(GpsStateValidationV2.startupState(read: .valid(stored),
            expectedFingerprint: "changed-configuration", configuredZoneIds: [a, b]), .suspend(stored))
    }

    private func zone(_ id: UUID, latitude: Double = 46.7, employer: String? = "employer-a") -> GpsZoneV2 {
        .init(id: id, label: "Atelier", latitude: latitude, longitude: -1.4,
              radius: 120, employerId: employer, kind: .worksite)
    }

    private func inside() -> GpsPresenceTransitionV2.State {
        .init(activeZoneIds: [a, b], pendingExitZoneIds: [], pendingEvents: [],
              confirmedSessionId: sessionId, eventQueueOverflowed: false, confirmedZoneId: a)
    }

    private func transition(_ state: GpsPresenceTransitionV2.State, zones: [GpsZoneV2], id: UUID,
                            kind: GpsPresenceTransitionV2.Transition, at: Date) -> GpsPresenceTransitionV2.State {
        GpsConfiguredPresenceTransitionV2.plan(state: state, configuredZones: zones,
            zoneId: id, transition: kind, occurredAt: at)
    }

    private func persisted(_ state: GpsPresenceTransitionV2.State, zones: [GpsZoneV2]) -> GpsPersistedStateV2 {
        .init(fingerprint: GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones)!,
              activeZoneIds: state.activeZoneIds, pendingExitZoneIds: state.pendingExitZoneIds,
              pendingEvents: state.pendingEvents, confirmedSessionId: state.confirmedSessionId,
              eventQueueOverflowed: state.eventQueueOverflowed, confirmedZoneId: state.confirmedZoneId,
              pendingExitObservations: state.pendingExitObservations)
    }

    private func roundTrip(_ state: GpsPresenceTransitionV2.State, zones: [GpsZoneV2]) throws -> GpsPresenceTransitionV2.State {
        let stored = persisted(state, zones: zones)
        guard case .valid(let restored) = GpsStateStoreV2.read(try JSONEncoder().encode(stored)) else {
            XCTFail("GPS state must survive restart")
            return state
        }
        XCTAssertEqual(GpsStateValidationV2.startupState(read: .valid(restored),
            expectedFingerprint: stored.fingerprint, configuredZoneIds: Set(zones.map(\.id))), .resume(restored))
        return .init(activeZoneIds: restored.activeZoneIds, pendingExitZoneIds: restored.pendingExitZoneIds,
            pendingEvents: restored.pendingEvents, confirmedSessionId: restored.confirmedSessionId,
            eventQueueOverflowed: restored.eventQueueOverflowed, confirmedZoneId: restored.confirmedZoneId,
            pendingExitObservations: restored.pendingExitObservations)
    }
}
