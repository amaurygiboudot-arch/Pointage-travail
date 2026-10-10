import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsOverlappingWorkZoneContinuityV2Tests: XCTestCase {
    private let first = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    private let second = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testConfiguredCallbackCancelsOverlapDepartureAndNextExitKeepsItsOwnTime() throws {
        let sessionId = UUID()
        let zones = [zone(first), zone(second, latitude: 46.70035)]
        let outside = exit(state: inside(sessionId), zones: zones)
        let oldDeparture = try XCTUnwrap(outside.pendingEvents.first)
        XCTAssertEqual(oldDeparture.expectedSessionId, sessionId)

        // Persist/reload as happens when iOS resumes the application between callbacks.
        let persisted = GpsPersistedStateV2(
            fingerprint: try XCTUnwrap(GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones)),
            activeZoneIds: outside.activeZoneIds,
            pendingExitZoneIds: outside.pendingExitZoneIds,
            pendingEvents: outside.pendingEvents,
            confirmedSessionId: outside.confirmedSessionId,
            eventQueueOverflowed: outside.eventQueueOverflowed,
            confirmedZoneId: outside.confirmedZoneId,
            pendingExitObservations: outside.pendingExitObservations
        )
        let data = try JSONEncoder().encode(persisted)
        guard case .valid(let restored) = GpsStateStoreV2.read(data) else {
            return XCTFail("GPS state must remain readable after departure")
        }
        XCTAssertEqual(GpsStateValidationV2.startupState(
            read: .valid(restored), expectedFingerprint: persisted.fingerprint,
            configuredZoneIds: [first, second]
        ), .resume(restored))
        let returned = enter(state: GpsPresenceTransitionV2.State(
            activeZoneIds: restored.activeZoneIds, pendingExitZoneIds: restored.pendingExitZoneIds,
            pendingEvents: restored.pendingEvents, confirmedSessionId: restored.confirmedSessionId,
            eventQueueOverflowed: restored.eventQueueOverflowed,
            confirmedZoneId: restored.confirmedZoneId,
            pendingExitObservations: restored.pendingExitObservations
        ), zones: zones, after: 30)
        XCTAssertEqual(returned.activeZoneIds, [second])
        XCTAssertEqual(returned.confirmedSessionId, sessionId)
        XCTAssertTrue(returned.pendingEvents.isEmpty)
        XCTAssertFalse(GpsEventConfirmationV2.isCurrent(oldDeparture, pendingEvents: returned.pendingEvents))

        let leftAgain = GpsConfiguredPresenceTransitionV2.plan(
            state: returned, configuredZones: zones, zoneId: second,
            transition: .exit, occurredAt: now.addingTimeInterval(600)
        )
        let newDeparture = try XCTUnwrap(leftAgain.pendingEvents.first)
        XCTAssertEqual(leftAgain.pendingEvents.count, 1)
        XCTAssertEqual(newDeparture.expectedSessionId, sessionId)
        XCTAssertEqual(newDeparture.occurredAt, now.addingTimeInterval(600))
        XCTAssertFalse(GpsEventConfirmationV2.isCurrent(oldDeparture, pendingEvents: leftAgain.pendingEvents))
        XCTAssertTrue(GpsEventConfirmationV2.isCurrent(newDeparture, pendingEvents: leftAgain.pendingEvents))
    }

    func testConfiguredReturnAcceptsBoundaryButKeepsLateAndDifferentEmployerVisitsSeparate() {
        let sessionId = UUID()
        let a = zone(first)
        let b = zone(second, latitude: 46.70035)
        let outside = exit(state: inside(sessionId), zones: [a, b])
        XCTAssertTrue(enter(state: outside, zones: [a, b], after: 120).pendingEvents.isEmpty)

        for (candidate, interval) in [
            (b, 121.0),
            (zone(second, employerId: "employer-b"), 30.0),
            (zone(second, employerId: nil), 30.0),
            (zone(second, latitude: 46.72), 30.0)
        ] {
            let returned = enter(state: outside, zones: [a, candidate], after: interval)
            XCTAssertEqual(returned.pendingEvents.map(\.kind), [.departure, .arrival])
            XCTAssertEqual(returned.pendingEvents[0], outside.pendingEvents[0])
            XCTAssertNil(returned.confirmedSessionId)
        }
    }

    func testConfiguredReturnNeverCancelsAnotherSessionsDepartureOrReordersTime() {
        let zones = [zone(first), zone(second)]
        var outside = exit(state: inside(UUID()), zones: zones)
        XCTAssertEqual(enter(state: outside, zones: zones, after: -1), outside)
        outside.confirmedSessionId = UUID()
        let returned = enter(state: outside, zones: zones, after: 30)
        XCTAssertEqual(returned.pendingEvents.map(\.kind), [.departure, .arrival])
        XCTAssertEqual(returned.pendingEvents[0], outside.pendingEvents[0])
    }

    func testMixedEmployerDepartureCannotTransferConfirmedSessionToMatchingCandidate() throws {
        let sessionId = UUID()
        let third = UUID()
        let a = zone(first, employerId: "employer-a")
        let b = zone(second, employerId: "employer-b")
        let c = zone(third, latitude: 46.70035, employerId: "employer-b")
        let zones = [a, b, c]
        // Session S was confirmed in A. An overlapping B from another employer
        // also becomes active before the GPS callbacks report leaving both.
        let both = enter(state: inside(sessionId), zones: zones, after: -30)
        XCTAssertEqual(both.activeZoneIds, [first, second])
        let leftA = exit(state: both, zones: zones)
        let outside = GpsConfiguredPresenceTransitionV2.plan(
            state: leftA, configuredZones: zones, zoneId: second,
            transition: .exit, occurredAt: now.addingTimeInterval(10)
        )
        let departure = try XCTUnwrap(outside.pendingEvents.first)
        XCTAssertEqual(Set(departure.zoneIds), [first, second])
        XCTAssertEqual(departure.expectedSessionId, sessionId)
        XCTAssertEqual(departure.occurredAt, now)
        XCTAssertTrue(GpsDepartureTimeEvidenceV2.isVerified(departure, configuredZones: zones))

        for returningId in [second, third] {
            let returned = GpsConfiguredPresenceTransitionV2.plan(
                state: outside, configuredZones: zones, zoneId: returningId,
                transition: .enter, occurredAt: now.addingTimeInterval(30)
            )
            XCTAssertEqual(returned.pendingEvents.map(\.kind), [.departure, .arrival])
            XCTAssertEqual(returned.pendingEvents.first, departure)
            XCTAssertNil(returned.confirmedSessionId)
        }
    }

    func testEveryDepartureCandidateMustBeKnownWorksiteForTheSameEmployer() {
        let third = UUID()
        let unknown = UUID()
        let a = zone(first)
        let b = zone(second)
        let c = zone(third, latitude: 46.70035)
        XCTAssertTrue(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [first, second], enteringZone: c, configuredZones: [a, b, c],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
        // A valid matching candidate must not hide an unresolved zone ID.
        XCTAssertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [first, unknown], enteringZone: c, configuredZones: [a, b, c],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
        var unresolved = exit(state: inside(UUID()), zones: [a, b, c])
        unresolved.pendingEvents[0].zoneIds.append(unknown)
        let sameIdReturn = GpsConfiguredPresenceTransitionV2.plan(
            state: unresolved, configuredZones: [a, b, c], zoneId: first,
            transition: .enter, occurredAt: now.addingTimeInterval(30)
        )
        XCTAssertEqual(sameIdReturn.pendingEvents.map(\.kind), [.departure, .arrival])
        XCTAssertEqual(sameIdReturn.pendingEvents.first, unresolved.pendingEvents.first)
        var context = b
        context.kind = .parking
        XCTAssertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [first, second], enteringZone: c, configuredZones: [a, context, c],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
        context = b
        context.employerId = nil
        XCTAssertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [first, second], enteringZone: c, configuredZones: [a, context, c],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
    }

    func testDistantSameEmployerCandidateCannotBeHiddenByAnOverlappingCandidate() throws {
        let sessionId = UUID()
        let third = UUID()
        let a = zone(first, latitude: 47.0)
        let b = zone(second)
        let c = zone(third, latitude: 46.70035)
        let zones = [a, b, c]
        var active = inside(sessionId)
        active.activeZoneIds.insert(second)
        let leftA = exit(state: active, zones: zones)
        let outside = GpsConfiguredPresenceTransitionV2.plan(
            state: leftA, configuredZones: zones, zoneId: second,
            transition: .exit, occurredAt: now.addingTimeInterval(10)
        )
        let departure = try XCTUnwrap(outside.pendingEvents.first)
        XCTAssertEqual(Set(departure.zoneIds), [first, second])
        XCTAssertEqual(departure.occurredAt, now)
        XCTAssertTrue(GpsDepartureTimeEvidenceV2.isVerified(departure, configuredZones: zones))
        for returningId in [second, third] {
            let returned = GpsConfiguredPresenceTransitionV2.plan(
                state: outside, configuredZones: zones, zoneId: returningId,
                transition: .enter, occurredAt: now.addingTimeInterval(30)
            )
            XCTAssertEqual(returned.pendingEvents.map(\.kind), [.departure, .arrival])
            XCTAssertEqual(returned.pendingEvents.first, departure)
            XCTAssertNil(returned.confirmedSessionId)
        }
    }

    func testSingleSameZoneLongReturnStillCancelsDepartureWithoutInventingEmployer() {
        let sessionId = UUID()
        let zones = [zone(first, employerId: nil)]
        let outside = exit(state: inside(sessionId), zones: zones)
        let returned = GpsConfiguredPresenceTransitionV2.plan(
            state: outside, configuredZones: zones, zoneId: first,
            transition: .enter, occurredAt: now.addingTimeInterval(8 * 3_600)
        )
        XCTAssertTrue(returned.pendingEvents.isEmpty)
        XCTAssertEqual(returned.confirmedSessionId, sessionId)
    }

    func testParkingAndPauseNeverDelayWorksiteDeparture() {
        let sessionId = UUID()
        let work = zone(first)
        for kind in [GpsZoneKindV2.parking, .breakZone, .other] {
            var context = zone(second)
            context.kind = kind
            let zones = [work, context]
            let outside = exit(state: inside(sessionId), zones: zones)
            XCTAssertEqual(enter(state: outside, zones: zones, after: 7 * 60), outside)
            let afterContextExit = GpsConfiguredPresenceTransitionV2.plan(
                state: outside, configuredZones: zones, zoneId: second,
                transition: .exit, occurredAt: now.addingTimeInterval(7 * 60)
            )
            XCTAssertEqual(afterContextExit, outside)
            XCTAssertEqual(afterContextExit.pendingEvents.first?.occurredAt, now)
        }
    }

    func testContinuityRequiresConfiguredValidZonesAndExplicitEmployer() {
        let a = zone(first)
        let b = zone(second, latitude: 46.70035)
        XCTAssertTrue(proven(a, b, after: 0))
        XCTAssertTrue(proven(a, b, after: 120))
        XCTAssertFalse(proven(a, b, after: 120.001))
        XCTAssertFalse(proven(a, b, after: -1))
        XCTAssertFalse(proven(a, a, after: 30))
        XCTAssertFalse(proven(zone(first, employerId: nil), zone(second, employerId: nil), after: 30))

        var invalid = b
        invalid.latitude = 91
        XCTAssertFalse(proven(a, invalid, after: 30))
        invalid = b
        invalid.radius = 49
        XCTAssertFalse(proven(a, invalid, after: 30))
        XCTAssertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [first], enteringZone: b, configuredZones: [a],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
        XCTAssertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [UUID()], enteringZone: b, configuredZones: [a, b],
            exitAt: now, entryAt: now.addingTimeInterval(30)
        ))
    }

    func testChangedOrQueuedEventCannotBeConfirmedThroughStaleDialog() {
        let arrival = GpsPendingEventV2(id: UUID(), kind: .arrival, zoneIds: [first], occurredAt: now)
        XCTAssertTrue(GpsEventConfirmationV2.isCurrent(arrival, pendingEvents: [arrival]))
        var changed = arrival
        changed.zoneIds = [second]
        XCTAssertFalse(GpsEventConfirmationV2.isCurrent(arrival, pendingEvents: [changed]))
        let departure = GpsPendingEventV2(
            id: UUID(), kind: .departure, zoneIds: [first], occurredAt: now,
            expectedSessionId: UUID()
        )
        XCTAssertFalse(GpsEventConfirmationV2.isCurrent(departure, pendingEvents: [arrival, departure]))
        changed = departure
        changed.expectedSessionId = UUID()
        XCTAssertFalse(GpsEventConfirmationV2.isCurrent(departure, pendingEvents: [changed]))
    }

    private func zone(_ id: UUID, latitude: Double = 46.7, employerId: String? = "employer-a") -> GpsZoneV2 {
        GpsZoneV2(id: id, label: "Atelier", latitude: latitude, longitude: -1.4,
                  radius: 120, employerId: employerId, kind: .worksite)
    }

    private func inside(_ sessionId: UUID) -> GpsPresenceTransitionV2.State {
        .init(activeZoneIds: [first], pendingExitZoneIds: [], pendingEvents: [],
              confirmedSessionId: sessionId, eventQueueOverflowed: false, confirmedZoneId: first)
    }

    private func exit(state: GpsPresenceTransitionV2.State, zones: [GpsZoneV2]) -> GpsPresenceTransitionV2.State {
        GpsConfiguredPresenceTransitionV2.plan(state: state, configuredZones: zones,
            zoneId: first, transition: .exit, occurredAt: now)
    }

    private func enter(state: GpsPresenceTransitionV2.State, zones: [GpsZoneV2], after interval: TimeInterval) -> GpsPresenceTransitionV2.State {
        GpsConfiguredPresenceTransitionV2.plan(state: state, configuredZones: zones,
            zoneId: second, transition: .enter, occurredAt: now.addingTimeInterval(interval))
    }

    private func proven(_ a: GpsZoneV2, _ b: GpsZoneV2, after interval: TimeInterval) -> Bool {
        GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            exitedZoneIds: [a.id], enteringZone: b,
            configuredZones: a.id == b.id ? [a] : [a, b],
            exitAt: now, entryAt: now.addingTimeInterval(interval)
        )
    }
}
