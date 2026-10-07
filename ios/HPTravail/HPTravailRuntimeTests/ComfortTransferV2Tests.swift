import Foundation
import XCTest
#if canImport(RuntimeV2Contract)
@testable import RuntimeV2Contract
#endif

final class ComfortTransferV2Tests: XCTestCase {
    private let fixture = #"{"format":"agkgmg.comfort","version":1,"highContrast":true,"reduceMotion":false,"readerScale":2.25}"#

    func testSharedFixtureAndRoundTrip() throws {
        let shared = try ComfortTransferV2.decode(fixture)
        XCTAssertTrue(shared.highContrast)
        XCTAssertFalse(shared.reduceMotion)
        XCTAssertEqual(shared.readerScale, 2.25)
        XCTAssertEqual(try ComfortTransferV2.decode(shared.encodedText()), shared)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(shared.encodedText().utf8)) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["format", "version", "highContrast", "reduceMotion", "readerScale"])
    }

    func testRejectsWrongTypesUnknownMissingAndUnsupportedValues() {
        let invalid = [
            fixture.replacingOccurrences(of: "\"version\":1", with: "\"version\":true"),
            fixture.replacingOccurrences(of: "\"version\":1", with: "\"version\":2"),
            fixture.replacingOccurrences(of: "\"highContrast\":true", with: "\"highContrast\":1"),
            fixture.replacingOccurrences(of: "\"reduceMotion\":false", with: "\"reduceMotion\":\"false\""),
            fixture.replacingOccurrences(of: "2.25", with: "true"),
            fixture.replacingOccurrences(of: "2.25", with: "\"2.25\""),
            fixture.replacingOccurrences(of: "2.25", with: "null"),
            fixture.replacingOccurrences(of: "2.25", with: "4.01"),
            fixture.replacingOccurrences(of: "2.25", with: "0.99"),
            fixture.replacingOccurrences(of: "agkgmg.comfort", with: "other"),
            fixture.replacingOccurrences(of: ",\"readerScale\":2.25", with: ""),
            fixture.replacingOccurrences(of: "}", with: ",\"appearance\":\"dark\"}"),
            "[]", "{}"
        ]
        for text in invalid { XCTAssertThrowsError(try ComfortTransferV2.decode(text), text) }
    }

    func testUtf8LimitAndEquivalentNumericVersionWithEscapedKey() throws {
        let padded = fixture + String(repeating: " ", count: ComfortTransferV2.maximumBytes - fixture.utf8.count)
        XCTAssertNoThrow(try ComfortTransferV2.decode(padded))
        XCTAssertThrowsError(try ComfortTransferV2.decode(padded + " "))
        let escaped = fixture.replacingOccurrences(of: "version", with: #"\u0076ersion"#)
        XCTAssertEqual(try ComfortTransferV2.decode(escaped).version, 1)
        XCTAssertEqual(try ComfortTransferV2.decode(escaped.replacingOccurrences(of: ":1,", with: ":1.0,")), try ComfortTransferV2.decode(fixture))
        XCTAssertEqual(try ComfortTransferV2.decode(fixture.replacingOccurrences(of: ":1,", with: ":1e0,")), try ComfortTransferV2.decode(fixture))
        let unicode = fixture.replacingOccurrences(of: "agkgmg.comfort", with: String(repeating: "é", count: 2_100))
        XCTAssertGreaterThan(unicode.utf8.count, ComfortTransferV2.maximumBytes)
        XCTAssertThrowsError(try ComfortTransferV2.decode(unicode))
    }

    func testApplyingUsesLatestNativeValuesAndChangesOnlySharedFields() throws {
        let pending = try ComfortTransferV2.decode(fixture)
        var latest = VisualPreferencesV2()
        latest.appearance = "dark"
        latest.accent = "blue"
        latest.textSteps = 4
        latest.context = "economy"
        latest.opaqueSurfaces = true
        latest.systemSpelling = true
        latest.reduceMotion = true
        let result = pending.applying(to: latest)
        var expected = latest
        expected.highContrast = true
        expected.reduceMotion = false
        expected.readerScale = 2.25
        XCTAssertEqual(result, expected)
        XCTAssertEqual(latest.readerScale, 1.5)
    }
}
