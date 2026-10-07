import Foundation
import XCTest
#if canImport(RuntimeV2Contract)
@testable import RuntimeV2Contract
#endif

final class VisualPreferencesV2Tests: XCTestCase {
    func testRoundTripAndUnknownSchemaRejected() throws {
        var value = VisualPreferencesV2()
        value.textSteps = 5
        value.context = "night"
        let data = try JSONEncoder().encode(value)
        XCTAssertEqual(try VisualPreferencesV2.decode(data), value)
        value.version = 2
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONEncoder().encode(value)))
        XCTAssertThrowsError(try VisualPreferencesV2.decode(Data("{}".utf8)))
        value.version = 1
        value.textSteps = -1
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONEncoder().encode(value)))
    }

    func testSystemAccessibilityIsNeverReducedAndSizeClamps() {
        var value = VisualPreferencesV2()
        value.textSteps = 3
        XCTAssertEqual(value.textSizeIndex(systemIndex: 10, maximumIndex: 11), 11)
        XCTAssertEqual(value.textSizeIndex(systemIndex: 3, maximumIndex: 11), 6)
        value.textSteps = 0
        XCTAssertEqual(value.textSizeIndex(systemIndex: 11, maximumIndex: 11), 11)
    }

    func testContextsRestoreBaseWithoutMutatingIt() {
        var value = VisualPreferencesV2()
        value.appearance = "light"
        value.context = "night"
        XCTAssertEqual(value.effectiveDarkMode, true)
        value.context = "economy"
        XCTAssertTrue(value.effectiveReducedMotion)
        XCTAssertEqual(value.effectiveDarkMode, false)
        value.context = "standard"
        XCTAssertFalse(value.effectiveReducedMotion)
        XCTAssertEqual(value.appearance, "light")
    }

    func testAccountIsolationLegacyMigrationAndFailedWritePreservesValue() throws {
        let suite = "VisualPreferencesV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("blue", forKey: "hp_theme")
        let visitor = try VisualPreferencesRepositoryV2.read(accountID: nil, defaults: defaults)
        XCTAssertEqual(visitor.accent, "blue")
        XCTAssertNil(defaults.object(forKey: "hp_theme"))
        XCTAssertEqual(try VisualPreferencesRepositoryV2.read(accountID: nil, defaults: defaults), visitor)
        XCTAssertEqual(try VisualPreferencesRepositoryV2.read(accountID: "A", defaults: defaults).accent, "signature")
        var first = VisualPreferencesV2()
        first.highContrast = true
        try VisualPreferencesRepositoryV2.write(first, accountID: "A", defaults: defaults)
        XCTAssertFalse(try VisualPreferencesRepositoryV2.read(accountID: "B", defaults: defaults).highContrast)
        var invalid = first
        invalid.appearance = "unknown"
        XCTAssertThrowsError(try VisualPreferencesRepositoryV2.write(invalid, accountID: "A", defaults: defaults))
        XCTAssertEqual(try VisualPreferencesRepositoryV2.read(accountID: "A", defaults: defaults), first)
    }

    func testCorruptStoredProfileNotSilentlyOverwritten() throws {
        let suite = "VisualPreferencesV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let key = VisualPreferencesRepositoryV2.key(accountID: "A")
        let broken = Data("broken".utf8)
        defaults.set(broken, forKey: key)
        XCTAssertThrowsError(try VisualPreferencesRepositoryV2.read(accountID: "A", defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: key), broken)
    }
}
