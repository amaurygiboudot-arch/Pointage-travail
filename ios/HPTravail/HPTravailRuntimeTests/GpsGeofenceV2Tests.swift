import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsGeofenceV2Tests: XCTestCase {
    private let first = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    private let second = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    private let now = Date(timeIntervalSinceReferenceDate: 1_000)

    func testConfigurationRejectsCorruptionDuplicatesAndMoreThanTenZones() throws {
        XCTAssertEqual(GpsZoneConfigurationV2.read(nil), .missing)
        XCTAssertEqual(GpsZoneConfigurationV2.read(Data("bad".utf8)), .corrupt)
        let duplicate = [zone(first), zone(first)]
        XCTAssertEqual(
            GpsZoneConfigurationV2.read(try JSONEncoder().encode(duplicate)),
            .corrupt
        )
        let tooMany = (0 ... 10).map { index in
            zone(UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", index))!)
        }
        XCTAssertEqual(
            GpsZoneConfigurationV2.read(try JSONEncoder().encode(tooMany)),
            .corrupt
        )
    }

    func testConfigurationRejectsInvalidCoordinatesAndRadius() throws {
        var invalid = zone(first)
        invalid.latitude = 91
        XCTAssertEqual(GpsZoneConfigurationV2.read(try JSONEncoder().encode([invalid])), .corrupt)
        invalid = zone(first)
        invalid.radius = 49
        XCTAssertEqual(GpsZoneConfigurationV2.read(try JSONEncoder().encode([invalid])), .corrupt)
    }

    func testFingerprintRejectsStaleRegionIdentifier() throws {
        let original = [zone(first)]
        var moved = zone(first)
        moved.latitude += 0.01
        let originalFingerprint = try XCTUnwrap(
            GpsZoneConfigurationV2.fingerprint(enabled: true, zones: original)
        )
        let movedFingerprint = try XCTUnwrap(
            GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [moved])
        )
        let registrationId = UUID()
        let identifier = GpsZoneConfigurationV2.regionIdentifier(
            zoneId: first,
            fingerprint: originalFingerprint,
            registrationId: registrationId
        )

        XCTAssertEqual(
            GpsZoneConfigurationV2.zoneId(
                fromRegionIdentifier: identifier,
                fingerprint: originalFingerprint,
                registrationId: registrationId
            ),
            first
        )
        XCTAssertNil(
            GpsZoneConfigurationV2.zoneId(
                fromRegionIdentifier: identifier,
                fingerprint: movedFingerprint,
                registrationId: registrationId
            )
        )
        XCTAssertNil(
            GpsZoneConfigurationV2.zoneId(
                fromRegionIdentifier: identifier,
                fingerprint: originalFingerprint,
                registrationId: UUID()
            )
        )
    }

    func testOverlappingZonesEmitOneArrivalAndOnlyLastExitEmitsDeparture() {
        let empty = GpsPresenceTransitionV2.State(
            activeZoneIds: [],
            pendingExitZoneIds: [],
            pendingEvents: [],
            confirmedSessionId: nil,
            eventQueueOverflowed: false
        )
        let enteredFirst = GpsPresenceTransitionV2.plan(
            state: empty,
            zoneId: first,
            transition: .enter,
            occurredAt: now
        )
        let enteredBoth = GpsPresenceTransitionV2.plan(
            state: enteredFirst,
            zoneId: second,
            transition: .enter,
            occurredAt: now.addingTimeInterval(1)
        )
        XCTAssertEqual(enteredBoth.pendingEvents.map(\.kind), [.arrival])
        XCTAssertEqual(Set(enteredBoth.pendingEvents.first?.zoneIds ?? []), [first, second])

        let leftFirst = GpsPresenceTransitionV2.plan(
            state: enteredBoth,
            zoneId: first,
            transition: .exit,
            occurredAt: now.addingTimeInterval(60)
        )
        XCTAssertEqual(leftFirst.activeZoneIds, [second])
        XCTAssertEqual(leftFirst.pendingEvents.map(\.kind), [.arrival])

        let leftAll = GpsPresenceTransitionV2.plan(
            state: leftFirst,
            zoneId: second,
            transition: .exit,
            occurredAt: now.addingTimeInterval(120)
        )
        XCTAssertTrue(leftAll.activeZoneIds.isEmpty)
        XCTAssertEqual(leftAll.pendingEvents.map(\.kind), [.arrival, .departure])
        XCTAssertEqual(Set(leftAll.pendingEvents.last?.zoneIds ?? []), [first, second])
    }

    func testDuplicateAndStaleExitDoNotCreateExtraEvents() {
        let empty = GpsPresenceTransitionV2.State(
            activeZoneIds: [],
            pendingExitZoneIds: [],
            pendingEvents: [],
            confirmedSessionId: nil,
            eventQueueOverflowed: false
        )
        XCTAssertEqual(
            GpsPresenceTransitionV2.plan(
                state: empty,
                zoneId: first,
                transition: .exit,
                occurredAt: now
            ),
            empty
        )
    }

    func testRapidReturnCancelsDepartureForConfirmedSession() {
        let sessionId = UUID()
        let inside = GpsPresenceTransitionV2.State(
            activeZoneIds: [first],
            pendingExitZoneIds: [],
            pendingEvents: [],
            confirmedSessionId: sessionId,
            eventQueueOverflowed: false
        )
        let outside = GpsPresenceTransitionV2.plan(
            state: inside,
            zoneId: first,
            transition: .exit,
            occurredAt: now
        )
        XCTAssertEqual(outside.pendingEvents.first?.expectedSessionId, sessionId)

        let returned = GpsPresenceTransitionV2.plan(
            state: outside,
            zoneId: first,
            transition: .enter,
            occurredAt: now.addingTimeInterval(30)
        )
        XCTAssertTrue(returned.pendingEvents.isEmpty)
        XCTAssertEqual(returned.confirmedSessionId, sessionId)
    }

    func testRapidReturnBoundaryCancelsDeparture() {
        let sessionId = UUID()
        let outside = stateOutsideConfirmedSession(sessionId)

        for interval in [30.0, 120.0] {
            let returned = GpsPresenceTransitionV2.plan(
                state: outside,
                zoneId: first,
                transition: .enter,
                occurredAt: now.addingTimeInterval(interval)
            )
            XCTAssertTrue(returned.pendingEvents.isEmpty)
        }
    }

    func testReturnAfterBoundaryCancelsDepartureForStillOpenSession() {
        let sessionId = UUID()
        let returned = GpsPresenceTransitionV2.plan(
            state: stateOutsideConfirmedSession(sessionId),
            zoneId: first,
            transition: .enter,
            occurredAt: now.addingTimeInterval(121)
        )

        XCTAssertTrue(returned.pendingEvents.isEmpty)
        XCTAssertEqual(returned.confirmedSessionId, sessionId)
    }

    func testLaterExitCreatesFreshDepartureAfterReturn() {
        let sessionId = UUID()
        let returned = GpsPresenceTransitionV2.plan(
            state: stateOutsideConfirmedSession(sessionId),
            zoneId: first,
            transition: .enter,
            occurredAt: now.addingTimeInterval(121)
        )
        let leftAgain = GpsPresenceTransitionV2.plan(
            state: returned,
            zoneId: first,
            transition: .exit,
            occurredAt: now.addingTimeInterval(240)
        )

        XCTAssertEqual(leftAgain.pendingEvents.map(\.kind), [.departure])
        XCTAssertEqual(leftAgain.pendingEvents[0].expectedSessionId, sessionId)
        XCTAssertEqual(leftAgain.confirmedSessionId, sessionId)
    }

    func testReturnToDifferentZoneDoesNotCancelPendingDeparture() {
        let sessionId = UUID()
        let returned = GpsPresenceTransitionV2.plan(
            state: stateOutsideConfirmedSession(sessionId),
            zoneId: second,
            transition: .enter,
            occurredAt: now.addingTimeInterval(121)
        )

        XCTAssertEqual(returned.pendingEvents.map(\.kind), [.departure, .arrival])
        XCTAssertEqual(returned.confirmedSessionId, sessionId)
    }

    func testReturnTimestampBeforeDepartureIsRejected() {
        let outside = stateOutsideConfirmedSession(UUID())
        XCTAssertEqual(
            GpsPresenceTransitionV2.plan(
                state: outside,
                zoneId: first,
                transition: .enter,
                occurredAt: now.addingTimeInterval(-1)
            ),
            outside
        )
    }

    func testReconcileRemovesOnlyDeparturesForClosedGpsSession() {
        let staleSession = UUID()
        let otherSession = UUID()
        let staleDeparture = pending(.departure, sessionId: staleSession, at: now)
        let arrival = pending(.arrival, sessionId: nil, at: now.addingTimeInterval(1))
        let otherDeparture = pending(.departure, sessionId: otherSession, at: now.addingTimeInterval(2))
        let state = GpsPresenceTransitionV2.State(
            activeZoneIds: [],
            pendingExitZoneIds: [],
            pendingEvents: [staleDeparture, arrival, otherDeparture],
            confirmedSessionId: staleSession,
            eventQueueOverflowed: false
        )

        let reconciled = GpsPresenceTransitionV2.reconcileSession(
            state: state,
            openSessionId: nil
        )

        XCTAssertNil(reconciled.confirmedSessionId)
        XCTAssertEqual(reconciled.pendingEvents, [arrival, otherDeparture])
    }

    func testReconcileClosedSessionAllowsNextArrival() {
        let reconciled = GpsPresenceTransitionV2.reconcileSession(
            state: stateOutsideConfirmedSession(UUID()),
            openSessionId: nil
        )
        let entered = GpsPresenceTransitionV2.plan(
            state: reconciled,
            zoneId: first,
            transition: .enter,
            occurredAt: now.addingTimeInterval(121)
        )

        XCTAssertEqual(entered.pendingEvents.map(\.kind), [.arrival])
    }

    func testPendingQueueOverflowsFailClosedWithoutGrowing() {
        let events = (0 ..< GpsPresenceTransitionV2.maximumPendingEventCount).map { index in
            pending(index.isMultiple(of: 2) ? .arrival : .departure,
                    sessionId: nil,
                    at: now.addingTimeInterval(Double(index)))
        }
        let state = GpsPresenceTransitionV2.State(
            activeZoneIds: [first],
            pendingExitZoneIds: [],
            pendingEvents: events,
            confirmedSessionId: nil,
            eventQueueOverflowed: false
        )
        let overflowed = GpsPresenceTransitionV2.plan(
            state: state,
            zoneId: first,
            transition: .exit,
            occurredAt: now.addingTimeInterval(100)
        )

        XCTAssertEqual(overflowed.pendingEvents.count, events.count)
        XCTAssertTrue(overflowed.eventQueueOverflowed)
    }

    func testPersistedPendingEventSurvivesRelaunchAndCorruptionFailsClosed() throws {
        let state = GpsPersistedStateV2(
            fingerprint: "fingerprint",
            activeZoneIds: [first],
            pendingExitZoneIds: [second],
            pendingEvents: [
                GpsPendingEventV2(
                    id: UUID(),
                    kind: .arrival,
                    zoneIds: [first],
                    occurredAt: now
                ),
                GpsPendingEventV2(
                    id: UUID(),
                    kind: .departure,
                    zoneIds: [first, second],
                    occurredAt: now.addingTimeInterval(120)
                )
            ],
            confirmedSessionId: UUID(),
            eventQueueOverflowed: false
        )
        let data = try JSONEncoder().encode(state)
        XCTAssertEqual(GpsStateStoreV2.read(data), .valid(state))
        XCTAssertEqual(GpsStateStoreV2.read(Data("bad".utf8)), .corrupt)
    }

    func testStartupStateDistinguishesMissingCorruptAndUsableState() {
        let state = persistedState()

        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .missing,
                expectedFingerprint: state.fingerprint,
                configuredZoneIds: [first, second]
            ),
            .initialize
        )
        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .corrupt,
                expectedFingerprint: state.fingerprint,
                configuredZoneIds: [first, second]
            ),
            .suspend(nil)
        )
        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .valid(state),
                expectedFingerprint: state.fingerprint,
                configuredZoneIds: [first, second]
            ),
            .resume(state)
        )
    }

    func testStartupStateSuspendsOverflowAndSemanticMismatch() {
        var overflowed = persistedState()
        overflowed.eventQueueOverflowed = true
        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .valid(overflowed),
                expectedFingerprint: overflowed.fingerprint,
                configuredZoneIds: [first, second]
            ),
            .suspend(overflowed)
        )

        let state = persistedState()
        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .valid(state),
                expectedFingerprint: "different",
                configuredZoneIds: [first, second]
            ),
            .suspend(state)
        )
        XCTAssertEqual(
            GpsStateValidationV2.startupState(
                read: .valid(state),
                expectedFingerprint: state.fingerprint,
                configuredZoneIds: [first]
            ),
            .suspend(state)
        )
    }

    private func zone(_ id: UUID) -> GpsZoneV2 {
        GpsZoneV2(
            id: id,
            label: "Atelier",
            latitude: 46.7,
            longitude: -1.4,
            radius: 150,
            employerId: nil,
            kind: .worksite
        )
    }

    private func persistedState() -> GpsPersistedStateV2 {
        GpsPersistedStateV2(
            fingerprint: "fingerprint",
            activeZoneIds: [first],
            pendingExitZoneIds: [second],
            pendingEvents: [pending(.arrival, sessionId: nil, at: now)],
            confirmedSessionId: nil,
            eventQueueOverflowed: false
        )
    }

    private func stateOutsideConfirmedSession(_ sessionId: UUID) -> GpsPresenceTransitionV2.State {
        GpsPresenceTransitionV2.State(
            activeZoneIds: [],
            pendingExitZoneIds: [],
            pendingEvents: [pending(.departure, sessionId: sessionId, at: now)],
            confirmedSessionId: sessionId,
            eventQueueOverflowed: false
        )
    }

    private func pending(
        _ kind: GpsPendingKindV2,
        sessionId: UUID?,
        at date: Date
    ) -> GpsPendingEventV2 {
        GpsPendingEventV2(
            id: UUID(),
            kind: kind,
            zoneIds: [first],
            occurredAt: date,
            expectedSessionId: sessionId
        )
    }
}
