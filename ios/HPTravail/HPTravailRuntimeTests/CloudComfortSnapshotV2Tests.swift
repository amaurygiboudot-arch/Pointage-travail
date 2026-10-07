import Foundation
import XCTest
#if canImport(RuntimeV2Contract)
@testable import RuntimeV2Contract
#endif

final class CloudComfortSnapshotV2Tests: XCTestCase {
    private func fields(_ revision: Int = 3) throws -> [String: Any] {
        ["schemaVersion": 1, "revision": revision,
         "payload": try ComfortTransferV2(profile: VisualPreferencesV2()).encodedText(),
         "deleted": false, "updatedAt": Date()]
    }

    func testLiveAndTombstoneContract() throws {
        let source = try fields()
        let parsed = try CloudComfortSnapshotV2.parse(source, timestampIsValid: true)
        XCTAssertEqual(parsed.revision, 3)
        XCTAssertFalse(parsed.deleted)
        XCTAssertEqual(parsed.transfer?.version, 2)
        var deleted = source
        deleted["deleted"] = true
        XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(deleted, timestampIsValid: true))
        deleted["payload"] = ""
        let tombstone = try CloudComfortSnapshotV2.parse(deleted, timestampIsValid: true)
        XCTAssertTrue(tombstone.deleted)
        XCTAssertNil(tombstone.transfer)
        XCTAssertEqual(tombstone.revision, 3)
    }

    func testVersionTwoCloudPayloadRoundTrip() throws {
        var source = try fields()
        source["payload"] = #"{"format":"agkgmg.comfort","version":2,"highContrast":true,"reduceMotion":false,"readerScale":2.25,"nightScheduleEnabled":true,"nightStartMinute":1320,"nightEndMinute":420}"#
        let transfer = try XCTUnwrap(CloudComfortSnapshotV2.parse(source, timestampIsValid: true).transfer)
        source["payload"] = try transfer.encodedText()
        let restored = try CloudComfortSnapshotV2.parse(source, timestampIsValid: true)
        XCTAssertEqual(restored.transfer, transfer)
        XCTAssertEqual(restored.transfer?.nightStartMinute, 1320)
        XCTAssertEqual(restored.transfer?.nightEndMinute, 420)
        XCTAssertEqual(restored.transfer?.nightScheduleEnabled, true)
    }

    func testLegacyPayloadRemainsReadableInSameCloudEnvelope() throws {
        var source = try fields()
        source["payload"] = #"{"format":"agkgmg.comfort","version":1,"highContrast":true,"reduceMotion":false,"readerScale":2}"#
        let parsed = try CloudComfortSnapshotV2.parse(source, timestampIsValid: true)
        let transfer = try XCTUnwrap(parsed.transfer)
        XCTAssertEqual(transfer.version, 1)
        var local = VisualPreferencesV2()
        local.nightScheduleEnabled = true
        local.nightStartMinute = 600
        local.nightEndMinute = 720
        let result = transfer.applying(to: local)
        XCTAssertTrue(result.nightScheduleEnabled)
        XCTAssertEqual(result.nightStartMinute, 600)
        XCTAssertEqual(result.nightEndMinute, 720)
    }

    func testStrictFieldsTypesBoundsAndTimestamp() throws {
        let good = try fields()
        for replacement in [true, "3", 0, -1, 3.5, 1_000_000_001] as [Any] {
            var bad = good; bad["revision"] = replacement
            XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(bad, timestampIsValid: true))
        }
        for replacement in [1, "false", NSNull()] as [Any] {
            var bad = good; bad["deleted"] = replacement
            XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(bad, timestampIsValid: true))
        }
        for key in good.keys {
            var bad = good; bad.removeValue(forKey: key)
            XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(bad, timestampIsValid: true))
        }
        var unknown = good; unknown["salary"] = 42
        XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(unknown, timestampIsValid: true))
        XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(good, timestampIsValid: false))
        var corrupt = good; corrupt["payload"] = "{}"
        XCTAssertThrowsError(try CloudComfortSnapshotV2.parse(corrupt, timestampIsValid: true))
    }

    func testRevisionCompareAndSetPreventsStaleResurrection() throws {
        XCTAssertEqual(try CloudComfortSnapshotV2.nextRevision(expected: 0, actual: nil), 1)
        XCTAssertEqual(try CloudComfortSnapshotV2.nextRevision(expected: 7, actual: 7), 8)
        XCTAssertThrowsError(try CloudComfortSnapshotV2.nextRevision(expected: 6, actual: 7))
        XCTAssertThrowsError(try CloudComfortSnapshotV2.nextRevision(expected: 0, actual: 7))
        XCTAssertThrowsError(try CloudComfortSnapshotV2.nextRevision(expected: 7, actual: nil))
        XCTAssertThrowsError(try CloudComfortSnapshotV2.nextRevision(expected: -1, actual: -1))
        XCTAssertThrowsError(try CloudComfortSnapshotV2.nextRevision(expected: 1_000_000_000, actual: 1_000_000_000))
    }
}
