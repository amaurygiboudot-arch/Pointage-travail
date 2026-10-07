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

    func testSharedVersionTwoLiteralFixture() throws {
        let fixtureV2 = #"{"format":"agkgmg.comfort","version":2,"highContrast":true,"reduceMotion":false,"readerScale":2.25,"nightScheduleEnabled":true,"nightStartMinute":1320,"nightEndMinute":420}"#
        let parsed = try ComfortTransferV2.decode(fixtureV2)
        XCTAssertEqual(parsed.version, 2)
        XCTAssertTrue(parsed.highContrast)
        XCTAssertFalse(parsed.reduceMotion)
        XCTAssertEqual(parsed.readerScale, 2.25)
        XCTAssertEqual(parsed.nightScheduleEnabled, true)
        XCTAssertEqual(parsed.nightStartMinute, 1320)
        XCTAssertEqual(parsed.nightEndMinute, 420)
        XCTAssertEqual(try ComfortTransferV2.decode(parsed.encodedText()), parsed)
    }

    func testNewTransferCarriesScheduleAndPreservesNativePreferences() throws {
        var source = VisualPreferencesV2()
        source.nightScheduleEnabled = true
        source.nightStartMinute = 1439
        source.nightEndMinute = 0
        let exported = try ComfortTransferV2(profile: source)
        XCTAssertEqual(exported.version, 2)
        let decoded = try ComfortTransferV2.decode(exported.encodedText())
        XCTAssertEqual(decoded, exported)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(exported.encodedText().utf8)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["format", "version", "highContrast", "reduceMotion", "readerScale",
                                         "nightScheduleEnabled", "nightStartMinute", "nightEndMinute"])
        var current = VisualPreferencesV2()
        current.context = "economy"
        current.appearance = "light"
        current.textSteps = 5
        var expected = current
        expected.nightScheduleEnabled = true
        expected.nightStartMinute = 1439
        expected.nightEndMinute = 0
        XCTAssertEqual(decoded.applying(to: current), expected)
        XCTAssertTrue(decoded.nightSchedulePreview.contains("23:59"))
        XCTAssertTrue(decoded.nightSchedulePreview.contains("00:00"))
        XCTAssertTrue(decoded.nightSchedulePreview.contains("fuseau horaire local"))
    }

    func testLegacyTransferPreservesLatestScheduleAndReencodesAsLegacy() throws {
        let legacy = try ComfortTransferV2.decode(fixture)
        var current = VisualPreferencesV2()
        current.nightScheduleEnabled = true
        current.nightStartMinute = 90
        current.nightEndMinute = 600
        let result = legacy.applying(to: current)
        XCTAssertEqual(result.nightScheduleEnabled, true)
        XCTAssertEqual(result.nightStartMinute, 90)
        XCTAssertEqual(result.nightEndMinute, 600)
        XCTAssertNil(legacy.nightScheduleEnabled)
        XCTAssertNil(legacy.nightStartMinute)
        XCTAssertNil(legacy.nightEndMinute)
        XCTAssertEqual(try ComfortTransferV2.decode(legacy.encodedText()).version, 1)
        XCTAssertTrue(legacy.nightSchedulePreview.contains("conservée"))
    }

    func testDisabledScheduleStillTransfersAndAllScheduleFieldsAreStrict() throws {
        let valid = try ComfortTransferV2(profile: VisualPreferencesV2()).encodedText()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(valid.utf8)) as? [String: Any])
        let disabled = try ComfortTransferV2.decode(valid)
        XCTAssertEqual(disabled.nightScheduleEnabled, false)
        var enabled = VisualPreferencesV2()
        enabled.nightScheduleEnabled = true
        enabled.nightStartMinute = 90
        enabled.nightEndMinute = 180
        XCTAssertEqual(disabled.applying(to: enabled), VisualPreferencesV2())
        XCTAssertTrue(disabled.nightSchedulePreview.contains("désactivée"))
        let badValues: [String: [Any]] = [
            "nightScheduleEnabled": [1, "false", NSNull()],
            "nightStartMinute": [-1, 1440, 1.5, true, "1320", NSNull(), 420],
            "nightEndMinute": [-1, 1440, 1.5, false, "420", NSNull(), 1320],
            "version": [0, 3, true, 2.5, "2"]
        ]
        for (key, values) in badValues {
            for value in values {
                var bad = object
                bad[key] = value
                let data = try JSONSerialization.data(withJSONObject: bad)
                XCTAssertThrowsError(try ComfortTransferV2.decode(String(decoding: data, as: UTF8.self)), "\(key): \(value)")
                XCTAssertThrowsError(try JSONDecoder().decode(ComfortTransferV2.self, from: data))
            }
        }
        for key in object.keys {
            var bad = object
            bad.removeValue(forKey: key)
            XCTAssertThrowsError(try ComfortTransferV2.decode(String(decoding: JSONSerialization.data(withJSONObject: bad), as: UTF8.self)), key)
        }
        var unknown = object
        unknown["timeZone"] = "Europe/Paris"
        XCTAssertThrowsError(try ComfortTransferV2.decode(String(decoding: JSONSerialization.data(withJSONObject: unknown), as: UTF8.self)))
        var legacyWithSchedule = object
        legacyWithSchedule["version"] = 1
        XCTAssertThrowsError(try ComfortTransferV2.decode(String(decoding: JSONSerialization.data(withJSONObject: legacyWithSchedule), as: UTF8.self)))
        var invalid = VisualPreferencesV2()
        invalid.nightStartMinute = invalid.nightEndMinute
        XCTAssertThrowsError(try ComfortTransferV2(profile: invalid))
        invalid.nightStartMinute = -1
        XCTAssertThrowsError(try ComfortTransferV2(profile: invalid))
        invalid.nightStartMinute = 1440
        XCTAssertThrowsError(try ComfortTransferV2(profile: invalid))
    }

}
