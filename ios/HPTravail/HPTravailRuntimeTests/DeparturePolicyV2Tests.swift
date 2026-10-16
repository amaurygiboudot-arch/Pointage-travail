import Foundation
import XCTest
@testable import RuntimeV2Contract

final class DeparturePolicyV2Tests: XCTestCase {
    private let account = "uid:test-account"
    private let observed = Date(timeIntervalSince1970: 1_800_000_000)
    private let zoneId = UUID()
    private let sessionId = UUID()
    private var zone: GpsZoneV2 {
        .init(id: zoneId, label: "Test", latitude: 0, longitude: 0, radius: 100, employerId: "company", kind: .worksite)
    }
    private var session: WorkSession {
        .init(id: sessionId, entry: observed.addingTimeInterval(-3600), exit: nil, pauses: [], employerId: "company")
    }
    private var state: GpsPersistedStateV2 {
        let event = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zoneId], occurredAt: observed,
            expectedSessionId: sessionId, exitObservations: [.init(zoneId: zoneId, occurredAt: observed, continuingZoneIds: [])],
            verifiedDepartureZoneId: zoneId, departureEvidenceVersion: 1, expectedSessionEntry: session.entry)
        return .init(fingerprint: GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [zone])!,
            activeZoneIds: [], pendingExitZoneIds: [], pendingEvents: [event], confirmedSessionId: sessionId,
            eventQueueOverflowed: false, confirmedZoneId: zoneId, accountScope: account)
    }
    private var enabledPolicy: DepartureAccountPolicyV2 {
        .init(account: account, employerId: "company", enabled: true, expectedSessionId: sessionId,
              expectedEntry: session.entry, expectedEnd: observed.addingTimeInterval(-60))
    }
    private func eligible(_ value: GpsPersistedStateV2, session current: WorkSession? = nil, now: Date? = nil) -> Date? {
        DeparturePolicyV2.eligibleObservation(state: value, account: account, session: current ?? session,
            zones: [zone], now: now ?? observed.addingTimeInterval(300))
    }
    private func automatic(_ policy: DepartureAccountPolicyV2, resolved: Bool = true) -> Date? {
        DeparturePolicyV2.automaticExit(state: state, account: account, session: session, zones: [zone],
            policy: policy, movementAndAmbiguityResolved: resolved, now: observed.addingTimeInterval(300))
    }

    func testManualUsesObservedTimeOnlyAfterExistingReturnWindow() {
        XCTAssertNil(eligible(state, now: observed.addingTimeInterval(119)))
        XCTAssertEqual(eligible(state, now: observed.addingTimeInterval(120)), observed)
        XCTAssertEqual(eligible(state), observed) // e.g. click five minutes later
    }
    func testAutomaticRequiresExplicitPolicyExpectedEndAndResolvedMovement() {
        XCTAssertEqual(automatic(enabledPolicy), observed)
        var policy = enabledPolicy
        policy.enabled = false
        XCTAssertNil(automatic(policy))
        policy.enabled = true; policy.expectedEnd = nil
        XCTAssertNil(automatic(policy))
        policy.expectedEnd = observed.addingTimeInterval(1)
        XCTAssertNil(automatic(policy))
        XCTAssertNil(automatic(enabledPolicy, resolved: false))
    }
    func testAbsoluteExpectedEndWorksAcrossMidnightWithoutDailyDurationAssumption() {
        var policy = enabledPolicy
        policy.expectedEnd = session.entry.addingTimeInterval(1800)
        XCTAssertEqual(automatic(policy), observed)
        policy.expectedEntry = session.entry.addingTimeInterval(-86400)
        XCTAssertNil(automatic(policy))
    }
    func testLegacyPendingCannotAcquireClosingAuthority() {
        var legacy = state
        legacy.pendingEvents[0].departureEvidenceVersion = nil
        XCTAssertNil(eligible(legacy))
        legacy.pendingEvents[0].departureEvidenceVersion = 1
        legacy.pendingEvents[0].expectedSessionEntry = nil
        XCTAssertNil(eligible(legacy))
    }
    func testChangedAccountSessionArrivalOrConfigurationRejectsEvidence() {
        var value = state; value.accountScope = "uid:other"
        XCTAssertNil(eligible(value))
        value = state; value.pendingEvents[0].expectedSessionId = UUID()
        XCTAssertNil(eligible(value))
        value = state; value.pendingEvents[0].expectedSessionEntry = observed
        XCTAssertNil(eligible(value))
        value = state; value.fingerprint = "changed"
        XCTAssertNil(eligible(value))
    }
    func testActiveZoneOpenPauseAmbiguousQueueAndClosedSessionRejectEvidence() {
        var value = state; value.activeZoneIds = [zoneId]
        XCTAssertNil(eligible(value))
        value = state; value.pendingExitZoneIds = [zoneId]
        XCTAssertNil(eligible(value))
        value = state; value.pendingEvents.append(value.pendingEvents[0])
        XCTAssertNil(eligible(value))
        var current = session
        current.pauses = [.init(id: UUID(), start: observed.addingTimeInterval(-30), end: nil, paid: true)]
        XCTAssertNil(eligible(state, session: current))
        current = session; current.exit = observed
        XCTAssertNil(eligible(state, session: current))
    }
    func testOtherEmployerRejectsEvenWithVerifiedTime() {
        var current = session; current.employerId = "other-company"
        XCTAssertNil(eligible(state, session: current))
        var policy = enabledPolicy
        policy = .init(account: account, employerId: "other-company", enabled: true,
            expectedSessionId: sessionId, expectedEntry: session.entry, expectedEnd: observed.addingTimeInterval(-60))
        XCTAssertNil(automatic(policy))
    }

    func testMissingOrBlankEmployerNeverQualifiesAnObservation() {
        for employer: String? in [nil, "", "   "] {
            var current = session
            current.employerId = employer
            var unassignedZone = zone
            unassignedZone.employerId = employer
            var value = state
            value.fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [unassignedZone]) ?? "invalid"
            XCTAssertNil(DeparturePolicyV2.eligibleObservation(state: value, account: account,
                session: current, zones: [unassignedZone], now: observed.addingTimeInterval(300)))
        }
    }
    func testReturnInvalidatesEvenAfterWindow() {
        for gap: TimeInterval in [30, 600] {
            let initial = state
            let next = GpsConfiguredPresenceTransitionV2.plan(state: .init(activeZoneIds: [], pendingExitZoneIds: [],
                pendingEvents: initial.pendingEvents, confirmedSessionId: sessionId, eventQueueOverflowed: false,
                confirmedZoneId: zoneId), configuredZones: [zone], zoneId: zoneId,
                transition: .enter, occurredAt: observed.addingTimeInterval(gap))
            XCTAssertFalse(next.pendingEvents.contains { $0.kind == .departure })
            XCTAssertEqual(next.activeZoneIds, [zoneId])
        }
    }

    func testDifferentSiteSameCompanyRemainsAmbiguous() {
        var other = zone
        other = GpsZoneV2(id: UUID(), label: "Other", latitude: 1, longitude: 1,
            radius: 100, employerId: "company", kind: .worksite)
        let zones = [zone, other]
        var value = state
        value.fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones)!
        value.pendingEvents[0].zoneIds.append(other.id)
        value.pendingEvents[0].exitObservations?.append(.init(zoneId: other.id,
            occurredAt: observed.addingTimeInterval(30), continuingZoneIds: []))
        XCTAssertNil(DeparturePolicyV2.eligibleObservation(state: value, account: account,
            session: session, zones: zones, now: observed.addingTimeInterval(300)))
    }

    func testNextSiteArrivalRemainsAccessibleBeforeAndAfterManualClosure() throws {
        for employer in ["company", "other-company"] {
            let other = GpsZoneV2(id: UUID(), label: "Other", latitude: 1, longitude: 1,
                radius: 100, employerId: employer, kind: .worksite)
            let zones = [zone, other]
            let inside = GpsPresenceTransitionV2.State(activeZoneIds: [zoneId], pendingExitZoneIds: [],
                pendingEvents: [], confirmedSessionId: sessionId, eventQueueOverflowed: false, confirmedZoneId: zoneId)
            let outside = GpsConfiguredPresenceTransitionV2.plan(state: inside, configuredZones: zones,
                zoneId: zoneId, transition: .exit, occurredAt: observed)
            let arrivalAt = observed.addingTimeInterval(60)
            let moved = GpsConfiguredPresenceTransitionV2.plan(state: outside, configuredZones: zones,
                zoneId: other.id, transition: .enter, occurredAt: arrivalAt)
            XCTAssertNil(moved.confirmedSessionId)
            XCTAssertEqual(moved.pendingEvents.map(\.kind), [.departure, .arrival])
            // Same canonical arbitration called by LocationManager before persistence/UI.
            let arbitrated = GpsPresenceTransitionV2.arbitrateDepartures(state: moved)
            XCTAssertEqual(arbitrated.pendingEvents.map(\.kind), [.arrival])
            XCTAssertEqual(arbitrated.pendingEvents.first?.occurredAt, arrivalAt)
            XCTAssertEqual(arbitrated.pendingEvents.first?.zoneIds, [other.id])
            XCTAssertEqual(GpsPresenceTransitionV2.reconcileSession(state: moved, openSessionId: sessionId), arbitrated)
            let closed = GpsPresenceTransitionV2.reconcileSession(state: moved, openSessionId: nil)
            XCTAssertEqual(closed, arbitrated)
            XCTAssertEqual(GpsPresenceTransitionV2.reconcileSession(state: closed, openSessionId: nil), closed)
            // No closure is inferred; the old session can only be closed manually at its click.
            var persisted = state
            persisted.fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones)!
            persisted.pendingEvents = arbitrated.pendingEvents
            persisted.activeZoneIds = arbitrated.activeZoneIds
            persisted.confirmedSessionId = arbitrated.confirmedSessionId
            persisted.confirmedZoneId = arbitrated.confirmedZoneId
            XCTAssertNil(DeparturePolicyV2.eligibleObservation(state: persisted, account: account,
                session: session, zones: zones, now: observed.addingTimeInterval(300)))
        }
    }

    func testReconcilePurgesBoundDepartureEvenWhenConfirmedIdentityAlreadyCleared() {
        let original = state
        let orphaned = GpsPresenceTransitionV2.State(activeZoneIds: [], pendingExitZoneIds: [],
            pendingEvents: original.pendingEvents, confirmedSessionId: nil, eventQueueOverflowed: false)
        let reconciled = GpsPresenceTransitionV2.reconcileSession(state: orphaned, openSessionId: nil)
        XCTAssertTrue(reconciled.pendingEvents.isEmpty)
        XCTAssertNil(reconciled.confirmedSessionId)
    }
    func testRestartRoundTripAndDuplicateCannotCloseTwice() throws {
        let data = try JSONEncoder().encode(state)
        guard case .valid(let restored) = GpsStateStoreV2.read(data) else { return XCTFail("decode") }
        XCTAssertEqual(eligible(restored), observed)
        var closed = session; closed.exit = observed
        XCTAssertNil(eligible(restored, session: closed))
    }
    func testPoliciesAreAccountAndCompanyScopedDefaultOffAndCorruptionFailsClosed() throws {
        let suite = "departure-policy-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(DepartureAccountPolicyStoreV2.read(account: account, employerId: "company", defaults: defaults)?.enabled, false)
        XCTAssertTrue(DepartureAccountPolicyStoreV2.write(enabledPolicy, defaults: defaults))
        XCTAssertEqual(DepartureAccountPolicyStoreV2.read(account: "uid:B", employerId: "company", defaults: defaults)?.enabled, false)
        XCTAssertEqual(DepartureAccountPolicyStoreV2.read(account: account, employerId: "other", defaults: defaults)?.enabled, false)
        XCTAssertEqual(DepartureAccountPolicyStoreV2.read(account: account, employerId: "company", defaults: defaults), enabledPolicy)
        defaults.set(Data("broken".utf8), forKey: DepartureAccountPolicyStoreV2.key(account: account, employerId: "company"))
        XCTAssertNil(DepartureAccountPolicyStoreV2.read(account: account, employerId: "company", defaults: defaults))
    }
}
