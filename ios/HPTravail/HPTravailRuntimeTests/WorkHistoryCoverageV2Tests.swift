import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkHistoryCoverageV2Tests: XCTestCase {
    private let zoneId = "Europe/Paris"

    func testTwoContiguousAttestationsCoverWholePeriod() {
        let stored = WorkHistoryCoverageReadV2(
            attestations: [
                attestation(id: 1, start: 10, end: 16, confirmedAt: completeAt(16)),
                attestation(id: 2, start: 17, end: 23, confirmedAt: completeAt(23))
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
        let result = WorkHistoryCoverageStoreV2.coverage(
            from: stored,
            employerId: "company-a",
            startEpochDay: 10,
            endEpochDay: 23,
            timeZoneId: zoneId,
            now: completeAt(23).addingTimeInterval(1)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.fullyCovered)
        XCTAssertEqual(result.attestations.count, 2)
        XCTAssertEqual(result.checkedAt, completeAt(23))
    }

    func testGapNeverBecomesImplicitZero() {
        let stored = WorkHistoryCoverageReadV2(
            attestations: [
                attestation(id: 1, start: 10, end: 15, confirmedAt: completeAt(15)),
                attestation(id: 2, start: 17, end: 23, confirmedAt: completeAt(23))
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
        let result = WorkHistoryCoverageStoreV2.coverage(
            from: stored,
            employerId: "company-a",
            startEpochDay: 10,
            endEpochDay: 23,
            timeZoneId: zoneId,
            now: completeAt(23).addingTimeInterval(1)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.fullyCovered)
        XCTAssertTrue(result.warnings.contains(WorkHistoryCoverageStoreV2.coverageWarning))
    }

    func testOtherEmployerOrTimezoneDoesNotCover() {
        let stored = WorkHistoryCoverageReadV2(
            attestations: [attestation(id: 1, start: 10, end: 23, confirmedAt: completeAt(23))],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
        XCTAssertFalse(
            WorkHistoryCoverageStoreV2.coverage(
                from: stored, employerId: "company-b", startEpochDay: 10, endEpochDay: 23,
                timeZoneId: zoneId, now: completeAt(23).addingTimeInterval(1)
            ).fullyCovered
        )
        XCTAssertFalse(
            WorkHistoryCoverageStoreV2.coverage(
                from: stored, employerId: "company-a", startEpochDay: 10, endEpochDay: 23,
                timeZoneId: "UTC", now: completeAt(23).addingTimeInterval(1)
            ).fullyCovered
        )
    }

    func testFutureAttestationFailsClosed() {
        let future = attestation(
            id: 1, start: 10, end: 23,
            confirmedAt: completeAt(23).addingTimeInterval(60)
        )
        let result = WorkHistoryCoverageStoreV2.coverage(
            from: .init(attestations: [future], reliable: true, repairedFromBackup: false, warnings: []),
            employerId: "company-a",
            startEpochDay: 10,
            endEpochDay: 23,
            timeZoneId: zoneId,
            now: completeAt(23)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.fullyCovered)
        XCTAssertTrue(result.warnings.contains(WorkHistoryCoverageStoreV2.futureWarning))
    }

    func testPointageCorrectionInvalidatesOnlyTouchedAttestations() {
        let first = attestation(id: 1, start: 10, end: 16, confirmedAt: completeAt(16))
        let second = attestation(id: 2, start: 17, end: 23, confirmedAt: completeAt(23))
        let kept = WorkHistoryCoverageStoreV2.invalidating(
            [first, second],
            start: localStart(epochDay: 12),
            end: localStart(epochDay: 13)
        )

        XCTAssertEqual(kept, [second])
    }

    func testEncodeDecodePreservesProvenance() throws {
        let values = [
            attestation(
                id: 1, start: 10, end: 16, confirmedAt: completeAt(16),
                sourceId: "user-confirmed"
            )
        ]
        let data = try XCTUnwrap(WorkHistoryCoverageStoreV2.encode(values))
        XCTAssertEqual(WorkHistoryCoverageStoreV2.decode(data), values)
    }

    private func attestation(
        id: Int,
        start: Int64,
        end: Int64,
        confirmedAt: Date,
        sourceId: String = "user"
    ) -> WorkHistoryCoverageAttestationV2 {
        WorkHistoryCoverageAttestationV2(
            id: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", id))!,
            sourceId: sourceId,
            employerId: "company-a",
            startEpochDay: start,
            endEpochDay: end,
            confirmedAt: confirmedAt,
            timeZoneId: zoneId,
            note: nil
        )
    }

    private func completeAt(_ endEpochDay: Int64) -> Date {
        localStart(epochDay: endEpochDay + 1)
    }

    private func localStart(epochDay: Int64) -> Date {
        let utc = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        var utcCalendar = Calendar(identifier: .gregorian)
        utcCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let c = utcCalendar.dateComponents([.year, .month, .day], from: utc)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zoneId)!
        return calendar.date(from: DateComponents(year: c.year, month: c.month, day: c.day))!
    }
}
