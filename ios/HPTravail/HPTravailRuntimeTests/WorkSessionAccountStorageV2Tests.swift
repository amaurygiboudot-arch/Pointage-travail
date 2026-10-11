import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionAccountStorageV2Tests: XCTestCase {
    func testGuestIsAvailableOnlyWhenAuthenticationStateIsKnownAndHasSeparateNamespace() throws {
        XCTAssertNil(WorkSessionAccountStorageV2.authenticationScope(available: false, userId: nil))
        XCTAssertNil(WorkSessionAccountStorageV2.authenticationScope(available: false, userId: "A"))
        XCTAssertNil(WorkSessionAccountStorageV2.authenticationScope(available: true, userId: "  "))
        let guest = try XCTUnwrap(WorkSessionAccountStorageV2.authenticationScope(available: true, userId: nil))
        let user = try XCTUnwrap(WorkSessionAccountStorageV2.authenticationScope(available: true, userId: "guest"))
        XCTAssertEqual(guest, "guest")
        XCTAssertEqual(user, "uid:guest")
        XCTAssertNotEqual(WorkSessionAccountStorageV2.key(account: guest), WorkSessionAccountStorageV2.key(account: user))
        let suite = "guest-tests-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: [])
        XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: guest, defaults: defaults))
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: user, defaults: defaults), .missing)
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: guest, defaults: defaults), .valid([session]))
    }

    func testSwitchingAccountsAndRelaunchPreservesIsolatedOpenSessions() throws {
        let suite = "account-tests-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: [])
        XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: "A", defaults: defaults))
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "B", defaults: defaults), .missing)
        XCTAssertTrue(WorkSessionAccountStorageV2.write([], account: "B", defaults: defaults))
        let restarted = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "A", defaults: restarted), .valid([session]))
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "B", defaults: restarted), .valid([]))
    }

    func testInvalidMutationCannotReplaceExistingAccountJournal() throws {
        let suite = "invalid-tests-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: [])
        XCTAssertTrue(WorkSessionAccountStorageV2.write([session], account: "A", defaults: defaults))
        XCTAssertFalse(WorkSessionAccountStorageV2.write([session, session], account: "A", defaults: defaults))
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "A", defaults: defaults), .valid([session]))
    }

    func testHistoricalDataIsNeitherAttributedNorModified() throws {
        let suite = "legacy-tests-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let historical = Data("historical bytes".utf8)
        defaults.set(historical, forKey: WorkSessionStorageV2.primaryKey)
        defaults.set(historical, forKey: WorkSessionStorageV2.legacyKey)
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "A", defaults: defaults), .missing)
        XCTAssertTrue(WorkSessionAccountStorageV2.write([], account: "A", defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), historical)
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.legacyKey), historical)
    }

    func testCorruptAndWrongTypedAccountDataFailClosedAndEmptyAccountCannotWrite() throws {
        let suite = "corrupt-tests-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let key = try XCTUnwrap(WorkSessionAccountStorageV2.key(account: "A"))
        defaults.set("wrong type", forKey: key)
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "A", defaults: defaults), .corrupt)
        defaults.set(Data("broken".utf8), forKey: key)
        XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "A", defaults: defaults), .corrupt)
        XCTAssertFalse(WorkSessionAccountStorageV2.write([], account: "  ", defaults: defaults))
        XCTAssertNil(WorkSessionAccountStorageV2.scope(nil))
        XCTAssertNotEqual(WorkSessionAccountStorageV2.key(account: "a/b"), WorkSessionAccountStorageV2.key(account: "a_b"))
    }
}
