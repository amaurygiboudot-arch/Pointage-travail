import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsAutomaticPointageV2Tests: XCTestCase {
    private let account = "uid:gps-test"
    private let employer = "company"
    private let zoneId = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private var zone: GpsZoneV2 {
        .init(id: zoneId, label: "Atelier", latitude: 48, longitude: 2,
              radius: 150, employerId: employer, kind: .worksite)
    }
    private var emptyState: GpsPersistedStateV2 {
        .init(fingerprint: GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [zone])!,
              activeZoneIds: [], pendingExitZoneIds: [], pendingEvents: [],
              confirmedSessionId: nil, eventQueueOverflowed: false, accountScope: account)
    }

    /// Reproduce the production sequence: plan the region transition, persist its evidence,
    /// then let the canonical pointage planner consume the first event.
    private func callback(_ state: GpsPersistedStateV2,
                          _ transition: GpsPresenceTransitionV2.Transition,
                          at date: Date, sessions: [WorkSession] = []) -> GpsPersistedStateV2 {
        let next = GpsConfiguredPresenceTransitionV2.plan(
            state: .init(activeZoneIds: state.activeZoneIds, pendingExitZoneIds: state.pendingExitZoneIds,
                         pendingEvents: state.pendingEvents, confirmedSessionId: state.confirmedSessionId,
                         eventQueueOverflowed: state.eventQueueOverflowed, confirmedZoneId: state.confirmedZoneId,
                         pendingExitObservations: state.pendingExitObservations),
            configuredZones: [zone], zoneId: zoneId, transition: transition,
            occurredAt: date, recordsSiteFacts: true
        )
        var persisted = GpsPersistedStateV2(
            fingerprint: state.fingerprint, activeZoneIds: next.activeZoneIds,
            pendingExitZoneIds: next.pendingExitZoneIds, pendingEvents: next.pendingEvents,
            confirmedSessionId: next.confirmedSessionId, eventQueueOverflowed: next.eventQueueOverflowed,
            confirmedZoneId: next.confirmedZoneId, pendingExitObservations: next.pendingExitObservations,
            accountScope: account
        )
        for index in persisted.pendingEvents.indices where persisted.pendingEvents[index].kind == .departure {
            persisted.pendingEvents[index].departureEvidenceVersion = 1
            if let session = sessions.first(where: {
                $0.id == persisted.pendingEvents[index].expectedSessionId && $0.exit == nil
            }) {
                persisted.pendingEvents[index].expectedSessionEntry = session.entry
            }
        }
        return persisted
    }

    private func plan(_ state: GpsPersistedStateV2, sessions: [WorkSession] = []) -> GpsAutomaticPointageV2.Commit? {
        GpsAutomaticPointageV2.plan(state: state, zones: [zone], account: account,
            sessions: sessions, confirmedEmployerIds: [employer], now: start.addingTimeInterval(86_400))
    }

    func testArrivalAndDepartureWriteExactFactsWithoutPaidQualification() throws {
        let arrival = try XCTUnwrap(plan(callback(emptyState, .enter, at: start)))
        let session = try XCTUnwrap(arrival.sessions.first)
        XCTAssertEqual(session.id, arrival.event.id)
        XCTAssertEqual(session.entry, start)
        XCTAssertNil(session.exit)
        XCTAssertEqual(session.gpsEntryEventId, arrival.event.id)
        XCTAssertEqual(session.gpsZoneId, zoneId)
        XCTAssertEqual(session.gpsAccountScope, account)
        XCTAssertEqual(session.segments, [.init(start: start, end: nil, kind: .toConfirm, confirmed: false)])
        XCTAssertTrue(arrival.state.pendingEvents.isEmpty)

        let leftAt = start.addingTimeInterval(8 * 3_600 + 37)
        let outside = callback(arrival.state, .exit, at: leftAt, sessions: arrival.sessions)
        let departure = try XCTUnwrap(plan(outside, sessions: arrival.sessions))
        let closed = try XCTUnwrap(departure.sessions.first)
        XCTAssertEqual(closed.entry, start)
        XCTAssertEqual(closed.exit, leftAt)
        XCTAssertEqual(closed.departureObservedAt, leftAt)
        XCTAssertEqual(closed.gpsExitEventId, departure.event.id)
        XCTAssertEqual(closed.closureOrigin, "AUTOMATIC")
        XCTAssertEqual(closed.segments, [.init(start: start, end: leftAt, kind: .toConfirm, confirmed: false)])
        XCTAssertTrue(WorkSessionPersistenceV2.isStructurallyValid(departure.sessions))
        XCTAssertTrue(departure.state.pendingEvents.isEmpty)
    }

    func testArrivalReplayAfterReceiptFailureDoesNotDuplicateOrEraseSession() throws {
        let observed = callback(emptyState, .enter, at: start)
        let committed = try XCTUnwrap(plan(observed))
        // Canonical write succeeded; the GPS acknowledgement was lost.
        let persisted = try JSONDecoder().decode([WorkSession].self,
            from: JSONEncoder().encode(committed.sessions))
        let replayed = try XCTUnwrap(plan(observed, sessions: persisted))
        XCTAssertEqual(replayed.sessions, committed.sessions)
        XCTAssertEqual(replayed.state, committed.state)
        XCTAssertEqual(replayed.sessions.count, 1)
        XCTAssertNil(plan(replayed.state, sessions: replayed.sessions))
    }

    func testDepartureReplayAfterReceiptFailureKeepsExactCanonicalFacts() throws {
        let arrival = try XCTUnwrap(plan(callback(emptyState, .enter, at: start)))
        let outside = callback(arrival.state, .exit, at: start.addingTimeInterval(3_601), sessions: arrival.sessions)
        let committed = try XCTUnwrap(plan(outside, sessions: arrival.sessions))
        let replayed = try XCTUnwrap(plan(outside, sessions: committed.sessions))
        XCTAssertEqual(replayed.sessions, committed.sessions)
        XCTAssertEqual(replayed.state, committed.state)
        XCTAssertNil(plan(replayed.state, sessions: replayed.sessions))
    }

    func testOldEventsCannotGainAutomaticAuthorityAfterUpgrade() {
        var legacy = callback(emptyState, .enter, at: start)
        legacy.pendingEvents[0].siteFactVersion = nil
        XCTAssertNil(plan(legacy))
        legacy.pendingEvents[0].siteFactVersion = 2
        XCTAssertNil(plan(legacy))
    }

    func testAccountConfigurationZoneAndEmployerMismatchRefuseArrival() {
        let observed = callback(emptyState, .enter, at: start)
        var wrong = observed
        wrong.accountScope = "uid:other"
        XCTAssertNil(plan(wrong))
        wrong = observed
        wrong.fingerprint = "stale-configuration"
        XCTAssertNil(plan(wrong))
        wrong = observed
        wrong.pendingEvents[0].zoneIds = [UUID()]
        XCTAssertNil(plan(wrong))
        XCTAssertNil(GpsAutomaticPointageV2.plan(state: observed, zones: [zone], account: account,
            sessions: [], confirmedEmployerIds: [], now: start))
    }

    func testFutureArrivalAndOverflowDoNotCreateSessions() {
        let future = callback(emptyState, .enter, at: start.addingTimeInterval(1))
        XCTAssertNil(GpsAutomaticPointageV2.plan(state: future, zones: [zone], account: account,
            sessions: [], confirmedEmployerIds: [employer], now: start))
        var overflow = callback(emptyState, .enter, at: start)
        overflow.eventQueueOverflowed = true
        XCTAssertNil(plan(overflow))
    }

    func testExistingManualEntryIsKeptWithoutDuplicateOrTimeRewrite() throws {
        let manual = WorkSession(id: UUID(), entry: start.addingTimeInterval(-60), exit: nil,
                                 pauses: [], employerId: employer)
        let observed = callback(emptyState, .enter, at: start)
        let associated = try XCTUnwrap(plan(observed, sessions: [manual]))
        XCTAssertEqual(associated.sessions, [manual])
        XCTAssertEqual(associated.sessionId, manual.id)
        XCTAssertEqual(associated.state.confirmedSessionId, manual.id)
        XCTAssertNil(associated.sessions[0].segments)

        var other = manual
        other.employerId = "another-company"
        XCTAssertNil(plan(observed, sessions: [other]))
    }

    func testManualEntryClosedByGpsReceivesUnqualifiedSegment() throws {
        let manual = WorkSession(id: UUID(), entry: start.addingTimeInterval(-60), exit: nil,
                                 pauses: [], employerId: employer)
        let associated = try XCTUnwrap(plan(callback(emptyState, .enter, at: start), sessions: [manual]))
        let leftAt = start.addingTimeInterval(3_600)
        let outside = callback(associated.state, .exit, at: leftAt, sessions: associated.sessions)
        let departed = try XCTUnwrap(plan(outside, sessions: associated.sessions))
        XCTAssertEqual(departed.sessions[0].entry, manual.entry)
        XCTAssertEqual(departed.sessions[0].exit, leftAt)
        XCTAssertEqual(departed.sessions[0].segments,
                       [.init(start: manual.entry, end: leftAt, kind: .toConfirm, confirmed: false)])
    }

    func testDeferredArrivalExitAndReturnPreserveEachSiteFactInOrder() throws {
        let outsideAt = start.addingTimeInterval(3_600)
        let returnAt = outsideAt.addingTimeInterval(30)
        let entered = callback(emptyState, .enter, at: start)
        let exited = callback(entered, .exit, at: outsideAt)
        let returned = callback(exited, .enter, at: returnAt)
        XCTAssertEqual(returned.pendingEvents.map(\.kind), [.arrival, .departure, .arrival])
        let transitionState = GpsPresenceTransitionV2.State(
            activeZoneIds: returned.activeZoneIds, pendingExitZoneIds: returned.pendingExitZoneIds,
            pendingEvents: returned.pendingEvents, confirmedSessionId: returned.confirmedSessionId,
            eventQueueOverflowed: false, confirmedZoneId: returned.confirmedZoneId
        )
        XCTAssertEqual(GpsPresenceTransitionV2.arbitrateDepartures(state: transitionState).pendingEvents,
                       returned.pendingEvents)

        let first = try XCTUnwrap(plan(returned))
        let second = try XCTUnwrap(plan(first.state, sessions: first.sessions))
        let third = try XCTUnwrap(plan(second.state, sessions: second.sessions))
        XCTAssertEqual(third.sessions.count, 2)
        XCTAssertEqual(third.sessions[0].entry, start)
        XCTAssertEqual(third.sessions[0].exit, outsideAt)
        XCTAssertEqual(third.sessions[1].entry, returnAt)
        XCTAssertNil(third.sessions[1].exit)
        XCTAssertTrue(third.state.pendingEvents.isEmpty)
        XCTAssertTrue(WorkSessionPersistenceV2.isStructurallyValid(third.sessions))
    }

    func testDuplicateRegionCallbackDoesNotCreateAnotherObservationOrSession() throws {
        let entered = callback(emptyState, .enter, at: start)
        let duplicate = callback(entered, .enter, at: start.addingTimeInterval(10))
        XCTAssertEqual(duplicate.pendingEvents, entered.pendingEvents)
        let committed = try XCTUnwrap(plan(duplicate))
        let again = callback(committed.state, .enter, at: start.addingTimeInterval(20), sessions: committed.sessions)
        XCTAssertTrue(again.pendingEvents.isEmpty)
        XCTAssertNil(plan(again, sessions: committed.sessions))
    }

    func testMismatchedSessionIdentityOrEntryCannotBeClosed() throws {
        let arrival = try XCTUnwrap(plan(callback(emptyState, .enter, at: start)))
        let outside = callback(arrival.state, .exit, at: start.addingTimeInterval(3_600), sessions: arrival.sessions)
        var wrong = outside
        wrong.pendingEvents[0].expectedSessionId = UUID()
        XCTAssertNil(plan(wrong, sessions: arrival.sessions))
        wrong = outside
        wrong.pendingEvents[0].expectedSessionEntry = start.addingTimeInterval(1)
        XCTAssertNil(plan(wrong, sessions: arrival.sessions))
        wrong = outside
        wrong.pendingEvents[0].departureEvidenceVersion = nil
        XCTAssertNil(plan(wrong, sessions: arrival.sessions))
    }

    func testAutomaticSessionProvenanceMismatchCannotBeClosed() throws {
        let arrival = try XCTUnwrap(plan(callback(emptyState, .enter, at: start)))
        let outside = callback(arrival.state, .exit, at: start.addingTimeInterval(3_600), sessions: arrival.sessions)
        var wrong = arrival.sessions
        wrong[0].gpsAccountScope = "uid:other"
        XCTAssertNil(plan(outside, sessions: wrong))
        wrong = arrival.sessions
        wrong[0].gpsConfigurationFingerprint = "old-configuration"
        XCTAssertNil(plan(outside, sessions: wrong))
        wrong = arrival.sessions
        wrong[0].gpsZoneId = UUID()
        XCTAssertNil(plan(outside, sessions: wrong))
    }

    func testAmbiguousArrivalRequiresExplicitConfiguredZoneSelection() throws {
        let otherZone = GpsZoneV2(id: UUID(), label: "Autre atelier", latitude: 48, longitude: 2,
            radius: 100, employerId: "another-company", kind: .worksite)
        let zones = [zone, otherZone]
        var state = callback(emptyState, .enter, at: start)
        state.fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones)!
        state.pendingEvents[0].zoneIds.append(otherZone.id)
        func selected(_ value: GpsPersistedStateV2) -> GpsAutomaticPointageV2.Commit? {
            GpsAutomaticPointageV2.plan(state: value, zones: zones, account: account,
                sessions: [], confirmedEmployerIds: [employer, "another-company"], now: start)
        }
        XCTAssertNil(selected(state))
        state.pendingEvents[0].selectedArrivalZoneId = otherZone.id
        let committed = try XCTUnwrap(selected(state))
        XCTAssertEqual(committed.sessions.first?.employerId, "another-company")
        XCTAssertEqual(committed.sessions.first?.gpsZoneId, otherZone.id)
        XCTAssertEqual(committed.sessions.first?.segments?.first?.kind, .toConfirm)
        state.pendingEvents[0].selectedArrivalZoneId = UUID()
        XCTAssertNil(selected(state))
    }
}
