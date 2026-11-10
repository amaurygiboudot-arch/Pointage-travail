#if !SWIFT_PACKAGE
import Foundation
import XCTest

/// Exercises the real ObservableObject in the Xcode test target, including stale auth callbacks.
@MainActor
final class WorkStoreAccountIsolationV2Tests: XCTestCase {
    func testManualAndWidgetUseScopedObservationAndPersistProvenanceWithoutDiagnostics() throws {
        for origin: DailyDiagnosticV2.Origin in [.manual, .widget] {
            let suite = "workstore-departure-\(UUID())"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            let now = Date()
            let observed = now.addingTimeInterval(-300)
            let zone = GpsZoneV2(id: UUID(), label: "Test", latitude: 0, longitude: 0,
                radius: 100, employerId: "company", kind: .worksite)
            let session = WorkSession(id: UUID(), entry: now.addingTimeInterval(-3600),
                exit: nil, pauses: [], employerId: "company")
            XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: "uid:A", defaults: defaults))
            defaults.set(true, forKey: GpsZoneConfigurationV2.enabledKey)
            defaults.set(try JSONEncoder().encode([zone]), forKey: GpsZoneConfigurationV2.zonesKey)
            let event = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zone.id], occurredAt: observed,
                expectedSessionId: session.id, exitObservations: [.init(zoneId: zone.id, occurredAt: observed, continuingZoneIds: [])],
                verifiedDepartureZoneId: zone.id, departureEvidenceVersion: 1, expectedSessionEntry: session.entry)
            let state = GpsPersistedStateV2(fingerprint: GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [zone])!,
                activeZoneIds: [], pendingExitZoneIds: [], pendingEvents: [event], confirmedSessionId: session.id,
                eventQueueOverflowed: false, confirmedZoneId: zone.id, accountScope: "uid:A")
            XCTAssertTrue(GpsStateStoreV2.write(state, defaults: defaults, storageKey: GpsStateStoreV2.scopedKey(account: "uid:A")))
            let store = WorkStoreV2(defaults: defaults, accountProvider: { "uid:A" })
            XCTAssertFalse(store.resolveAutomaticDeparture(now: now))
            XCTAssertTrue(store.clockOut(at: now, origin: origin))
            XCTAssertEqual(store.sessions.first?.exit, observed)
            XCTAssertEqual(store.sessions.first?.closureOrigin, origin.rawValue)
            XCTAssertEqual(store.sessions.first?.departureObservedAt, observed)
            store.reloadAccount()
            XCTAssertEqual(store.sessions.first?.exit, observed)
            XCTAssertFalse(store.clockOut(at: now, origin: origin))
        }
    }

    func testDiagnosticReportsActualRuntimeRejectionAndGPSClosureWithoutChangingFactTime() throws {
        let suite = "workstore-diagnostic-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: root)
        }
        let now = Date()
        let session = WorkSession(id: UUID(), entry: now.addingTimeInterval(-600), exit: nil, pauses: [])
        XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: "uid:A", defaults: defaults))
        defaults.set(Data("corrupt historical journal".utf8), forKey: WorkSessionStorageV2.primaryKey)
        try DailyDiagnosticV2.setEnabled(true, account: "uid:A", root: root)
        let store = WorkStoreV2(defaults: defaults, diagnosticRoot: root, accountProvider: { "uid:A" })
        XCTAssertEqual(store.diagnosticHistory, .corrupt)
        XCTAssertEqual(store.diagnosticSourceIssue, .none)
        XCTAssertEqual(store.currentSession, session)
        XCTAssertFalse(store.payrollHistoryReliable)
        XCTAssertFalse(store.clockIn(at: now))
        let observed = now.addingTimeInterval(-60)
        XCTAssertTrue(store.clockOut(at: observed, expectedSessionId: session.id, origin: .gpsConfirmation))
        XCTAssertEqual(store.sessions.first?.exit, observed)
        let events = try DailyDiagnosticV2.read(account: "uid:A", root: root).events
        XCTAssertEqual(events.first { $0.kind == .clockInRejected }?.rejection, .sessionAlreadyOpen)
        let closure = try XCTUnwrap(events.first { $0.kind == .clockOutCommitted })
        XCTAssertEqual(closure.origin, .gpsConfirmation)
        XCTAssertEqual(closure.occurredAt, observed)
        XCTAssertGreaterThan(closure.recordedAt, observed)
        XCTAssertFalse(store.payrollHistoryReliable)
    }

    func testAuthSwitchImmediatelyHidesSessionsAndRejectsStaleMutationThenRestoresOwner() throws {
        let suite = "workstore-auth-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var account: String? = "uid:A"
        let session = WorkSession(id: UUID(), entry: Date().addingTimeInterval(-600), exit: nil, pauses: [])
        XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: "uid:A", defaults: defaults))
        let store = WorkStoreV2(defaults: defaults, accountProvider: { account })
        XCTAssertEqual(store.currentSession?.id, session.id)
        account = "uid:B" // Before the UI auth notification calls reloadAccount.
        XCTAssertTrue(store.sessions.isEmpty)
        XCTAssertFalse(store.storageReliable)
        XCTAssertFalse(store.clockOut())
        store.reloadAccount()
        XCTAssertTrue(store.storageReliable)
        XCTAssertTrue(store.sessions.isEmpty)
        account = nil
        store.reloadAccount()
        XCTAssertFalse(store.storageReliable)
        account = "uid:A"
        store.reloadAccount()
        XCTAssertEqual(store.currentSession?.id, session.id)
        XCTAssertTrue(store.clockOut())
    }

    func testUnassignedHistoryBlocksPayrollButAllowsNewGuestJournalAndPreservesOriginal() throws {
        let suite = "workstore-history-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let original = Data("unassigned historical bytes".utf8)
        defaults.set(original, forKey: WorkSessionStorageV2.primaryKey)
        let store = WorkStoreV2(defaults: defaults, accountProvider: { "guest" })
        XCTAssertTrue(store.hasUnassignedHistory)
        XCTAssertTrue(store.storageReliable)
        XCTAssertFalse(store.payrollHistoryReliable)
        XCTAssertTrue(store.clockIn(at: Date().addingTimeInterval(-60)))
        XCTAssertEqual(store.sessions.count, 1)
        XCTAssertFalse(store.payrollHistoryReliable)
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), original)
        let restarted = WorkStoreV2(defaults: defaults, accountProvider: { "guest" })
        XCTAssertEqual(restarted.sessions, store.sessions)
        XCTAssertFalse(restarted.payrollHistoryReliable)
    }
    func testExplicitRecoveryRestoresPayrollAndSurvivesStoreRecreation() throws {
        let suite = "workstore-recovery-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let historical = WorkSession(id: UUID(), entry: Date().addingTimeInterval(-3600), exit: Date().addingTimeInterval(-1800), pauses: [])
        let bytes = try JSONEncoder().encode([historical])
        defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
        var account: String? = "uid:A"
        let store = WorkStoreV2(defaults: defaults, accountProvider: { account })
        XCTAssertFalse(store.payrollHistoryReliable)
        let preview = try store.recoveryPreview()
        try store.recoverHistoricalJournal(preview)
        XCTAssertTrue(store.payrollHistoryReliable)
        XCTAssertEqual(store.sessions, [historical])
        let restarted = WorkStoreV2(defaults: defaults, accountProvider: { account })
        XCTAssertTrue(restarted.payrollHistoryReliable)
        XCTAssertEqual(restarted.sessions, [historical])
        account = "uid:B"
        store.reloadAccount()
        XCTAssertTrue(store.sessions.isEmpty)
        XCTAssertTrue(store.payrollHistoryReliable)
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
    }

}
#endif
