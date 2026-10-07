import Foundation
import XCTest
#if canImport(RuntimeV2Contract)
@testable import RuntimeV2Contract
#endif

final class NightScheduleV2Tests: XCTestCase {
    func testOvernightStartInclusiveEndExclusiveAndDisabled() {
        var value = VisualPreferencesV2()
        value.appearance = "light"
        XCTAssertEqual(value.darkMode(minuteOfDay: 1380), false)
        value.nightScheduleEnabled = true
        for minute in [1320, 1439, 0, 419] {
            XCTAssertEqual(value.resolvedContext(minuteOfDay: minute), "night")
            XCTAssertEqual(value.darkMode(minuteOfDay: minute), true)
        }
        for minute in [420, 700, 1319] { XCTAssertEqual(value.resolvedContext(minuteOfDay: minute), "standard") }
        XCTAssertEqual(value.appearance, "light")
    }

    func testDaytimeRangeManualContextWinsAndBaseRestores() {
        var value = VisualPreferencesV2()
        value.nightScheduleEnabled = true
        value.nightStartMinute = 600
        value.nightEndMinute = 720
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 599), "standard")
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 600), "night")
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 719), "night")
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 720), "standard")
        value.context = "economy"
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 650), "economy")
        value.context = "night"
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 800), "night")
        value.context = "standard"
        XCTAssertEqual(value.resolvedContext(minuteOfDay: 800), "standard")
    }

    func testInvalidScheduleRejectedEvenWhenDisabledAndLegacyDefaults() throws {
        var value = VisualPreferencesV2()
        value.nightStartMinute = value.nightEndMinute
        XCTAssertFalse(value.valid)
        value.nightStartMinute = -1
        XCTAssertFalse(value.valid)
        value.nightStartMinute = 1440
        XCTAssertFalse(value.valid)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(VisualPreferencesV2())) as? [String: Any])
        for key in ["nightScheduleEnabled", "nightStartMinute", "nightEndMinute"] { json.removeValue(forKey: key) }
        let decoded = try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: json))
        XCTAssertFalse(decoded.nightScheduleEnabled)
        XCTAssertEqual(decoded.nightStartMinute, 1320)
        XCTAssertEqual(decoded.nightEndMinute, 420)
        json["nightScheduleEnabled"] = 1
        XCTAssertThrowsError(try VisualPreferencesV2.decode(JSONSerialization.data(withJSONObject: json)))
    }

    func testLegacySharedTransferDoesNotCarryOrOverwriteSchedule() throws {
        var local = VisualPreferencesV2()
        local.nightScheduleEnabled = true
        local.nightStartMinute = 1230
        local.nightEndMinute = 480
        let shared = try ComfortTransferV2.decode(#"{"format":"agkgmg.comfort","version":1,"highContrast":false,"reduceMotion":false,"readerScale":1.5}"#)
        let result = shared.applying(to: local)
        XCTAssertEqual(result.nightScheduleEnabled, local.nightScheduleEnabled)
        XCTAssertEqual(result.nightStartMinute, local.nightStartMinute)
        XCTAssertEqual(result.nightEndMinute, local.nightEndMinute)
        XCTAssertFalse(try shared.encodedText().contains("nightSchedule"))
    }
}
