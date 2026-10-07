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
        first.readerScale = 3
        try VisualPreferencesRepositoryV2.write(first, accountID: "A", defaults: defaults)
        XCTAssertFalse(try VisualPreferencesRepositoryV2.read(accountID: "B", defaults: defaults).highContrast)
        XCTAssertEqual(try VisualPreferencesRepositoryV2.read(accountID: "B", defaults: defaults).readerScale, 1.5)
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

    func testReaderZoomClampsPinchAndRejectsNonFiniteFactors() {
        XCTAssertEqual(ReaderZoomPolicyV2.clamped(0.1), 1)
        XCTAssertEqual(ReaderZoomPolicyV2.clamped(9), 4)
        XCTAssertEqual(ReaderZoomPolicyV2.clamped(.nan), 1)
        XCTAssertEqual(ReaderZoomPolicyV2.pinched(base: 2, magnification: 1.5), 3)
        XCTAssertEqual(ReaderZoomPolicyV2.pinched(base: 3, magnification: 0.1), 1)
        XCTAssertEqual(ReaderZoomPolicyV2.pinched(base: 3, magnification: .greatestFiniteMagnitude), 4)
        XCTAssertEqual(ReaderZoomPolicyV2.pinched(base: 2, magnification: .nan), 2)
        XCTAssertEqual(ReaderZoomPolicyV2.pinched(base: 2, magnification: -1), 2)
    }

    func testOldExportMigratesZoomButMalformedZoomDoesNot() throws {
        let data = try JSONEncoder().encode(VisualPreferencesV2())
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object.removeValue(forKey: "readerScale")
        XCTAssertEqual(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)).readerScale, 1.5)
        object["readerScale"] = "large"
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)))
        object["readerScale"] = NSNull()
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)))
        object["readerScale"] = 4.1
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)))
    }

    func testActualFileImportBoundAndValidation() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        var value = VisualPreferencesV2()
        value.readerScale = 2.75
        try JSONEncoder().encode(value).write(to: url)
        XCTAssertEqual(try VisualPreferencesV2.readImport(from: url), value)
        let oversized = Data(repeating: 32, count: VisualPreferencesV2.maximumImportBytes + 1)
        try oversized.write(to: url)
        XCTAssertThrowsError(try VisualPreferencesV2.readImport(from: url))
        XCTAssertThrowsError(try VisualPreferencesV2.decode(oversized))
        try Data("{}".utf8).write(to: url)
        XCTAssertThrowsError(try VisualPreferencesV2.readImport(from: url))
    }


    func testUnknownNativeProfileFieldsRejectedWithoutDroppingThemSilently() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(VisualPreferencesV2())) as? [String: Any])
        object["salary"] = 42
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)))
        object.removeValue(forKey: "salary")
        object["futureSetting"] = true
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: object)))
    }

}
