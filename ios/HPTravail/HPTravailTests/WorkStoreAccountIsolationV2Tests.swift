#if !SWIFT_PACKAGE
import Foundation
import XCTest

/// Exercises the real ObservableObject in the Xcode test target, including stale auth callbacks.
@MainActor
final class WorkStoreAccountIsolationV2Tests: XCTestCase {
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
