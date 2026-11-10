import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionRecoveryV2Tests: XCTestCase {
    private func session(_ start: TimeInterval = 100, id: UUID = UUID(), open: Bool = false) -> WorkSession {
        WorkSession(id: id, entry: Date(timeIntervalSinceReferenceDate: start),
            exit: open ? nil : Date(timeIntervalSinceReferenceDate: start + 10), pauses: [])
    }
    private func fixture(_ run: (UserDefaults, WorkSession, Data) throws -> Void) throws {
        let name = "recovery-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let old = session()
        let bytes = try JSONEncoder().encode([old])
        defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
        try run(defaults, old, bytes)
    }

    func testExplicitRecoveryPreservesOriginalAndStoresReceiptWithJournal() throws {
        try fixture { defaults, old, bytes in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:B", defaults: defaults), .missing)
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
            let next = self.session(200)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([old, next], account: "uid:A", defaults: defaults))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old, next]))
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:B", defaults: defaults))
        }
    }

    func testGuestAndStaleAccountCannotClaim() throws {
        try fixture { defaults, _, _ in
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "guest", defaults: defaults))
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:B" }, defaults: defaults))
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
        }
    }

    func testChangedDestinationOrSourceRequiresNewConfirmation() throws {
        try fixture { defaults, _, _ in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults))
            let refreshed = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            defaults.set(try JSONEncoder().encode([self.session(300)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(refreshed, accountProvider: { "uid:A" }, defaults: defaults))
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
        }
    }

    func testInterruptedReservationIsExclusiveAndReplayableWithoutDuplicates() throws {
        try fixture { defaults, old, bytes in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            var calls = 0
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: {
                calls += 1
                return calls < 3 ? "uid:A" : "uid:B"
            }, defaults: defaults))
            XCTAssertNotNil(defaults.data(forKey: WorkSessionAccountStorageV2.recoveryKey))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .corrupt)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:B", defaults: defaults))
            let resume = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(resume, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
        }
    }

    func testExactDuplicateDeduplicatesButDivergentIDOrOverlapOrTwoOpenSessionsBlock() throws {
        try fixture { defaults, old, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([old], account: "uid:A", defaults: defaults))
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
        }
        try fixture { defaults, old, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200, id: old.id)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(105)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
            defaults.set(try JSONEncoder().encode([self.session(100, open: true)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200, open: true)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
        }
    }

    func testUnqualifiedOpenPauseIsPreservedAndLaterEditsDoNotRevokeAttribution() throws {
        try fixture { defaults, _, _ in
            var old = self.session(100, open: true)
            old.pauses = [PausePeriod(id: UUID(), start: Date(timeIntervalSinceReferenceDate: 105), end: nil, paid: nil)]
            let bytes = try JSONEncoder().encode([old])
            defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertTrue(WorkSessionAccountStorageV2.write([], account: "uid:A", defaults: defaults))
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
        }
    }

    func testBothV1AndV2MustBeAccountedFor() throws {
        try fixture { defaults, old, _ in
            defaults.set(try JSONEncoder().encode([WorkSession]()), forKey: WorkSessionStorageV2.primaryKey)
            defaults.set(try JSONEncoder().encode([old]), forKey: WorkSessionStorageV2.legacyKey)
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertEqual(preview.historicalCount, 1)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
        }
        try fixture { defaults, old, _ in
            defaults.set(try JSONEncoder().encode([self.session(300, id: old.id)]), forKey: WorkSessionStorageV2.legacyKey)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
        }
    }

    func testSourceChangeAfterCompleteDoesNotBecomeReliableZero() throws {
        try fixture { defaults, _, _ in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            defaults.set(try JSONEncoder().encode([self.session(500)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
        }
    }
}
