import Foundation
import XCTest
@testable import SalaryV2Contract

final class SalaryPayrollCoverageAttestationV2Tests: XCTestCase {
    private let zoneId = "Europe/Paris"
    private let monday = epochDay(2026, 9, 21)
    private var sunday: Int64 { monday + 6 }

    func testReadableStoreWithoutAttestationNeverProvesEmptyWeek() {
        let result = resolve([])

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCoverageAttestationStoreV2.missingWarning))
    }

    func testAdjacentAttestationsCoverWholeWeek() {
        let first = attestation("00000000-0000-0000-0000-000000000001",
                                start: monday, end: monday + 2,
                                confirmedAt: confirmedAt(monday + 3))
        let second = attestation("00000000-0000-0000-0000-000000000002",
                                 start: monday + 3, end: sunday,
                                 confirmedAt: confirmedAt(sunday + 1))

        let result = resolve([second, first])

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(result.coveredStartEpochDay, monday)
        XCTAssertEqual(result.coveredEndEpochDay, sunday)
        XCTAssertTrue(result.sourceId.contains(first.id.uuidString))
        XCTAssertTrue(result.sourceId.contains(second.id.uuidString))
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testOneDayGapStaysNonExhaustive() {
        let first = attestation("00000000-0000-0000-0000-000000000003",
                                start: monday, end: monday + 1,
                                confirmedAt: confirmedAt(monday + 2))
        let second = attestation("00000000-0000-0000-0000-000000000004",
                                 start: monday + 3, end: sunday,
                                 confirmedAt: confirmedAt(sunday + 1))

        let result = resolve([first, second])

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
    }

    func testOtherEmployerNeverCoversRequestedPeriod() {
        let foreign = attestation(
            "00000000-0000-0000-0000-000000000005",
            start: monday,
            end: sunday,
            confirmedAt: confirmedAt(sunday + 1),
            employer: "company-b"
        )

        let result = resolve([foreign])

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
    }

    func testFutureAttestationMakesSourceUnreliable() {
        let item = attestation(
            "00000000-0000-0000-0000-000000000006",
            start: monday,
            end: sunday,
            confirmedAt: confirmedAt(sunday + 1)
        )
        let result = SalaryPayrollCoverageAttestationStoreV2.resolve(
            attestations: [item],
            employerId: "company-a",
            requestedStartEpochDay: monday,
            requestedEndEpochDay: sunday,
            timeZoneId: zoneId,
            now: item.confirmedAt.addingTimeInterval(-1)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCoverageAttestationStoreV2.corruptWarning))
    }

    func testWeekCannotBeCertifiedBeforeCivilEnd() {
        let calendar = localCalendar()
        let sundayNoon = calendar.date(
            from: DateComponents(year: 2026, month: 9, day: 27, hour: 12)
        )!
        let item = attestation(
            "00000000-0000-0000-0000-000000000007",
            start: monday,
            end: sunday,
            confirmedAt: sundayNoon
        )

        let result = SalaryPayrollCoverageAttestationStoreV2.resolve(
            attestations: [item],
            employerId: "company-a",
            requestedStartEpochDay: monday,
            requestedEndEpochDay: sunday,
            timeZoneId: zoneId,
            now: confirmedAt(sunday + 1)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.exhaustive)
    }

    func testCodecRejectsDuplicateIds() {
        let id = "00000000-0000-0000-0000-000000000008"
        let first = attestation(id, start: monday, end: monday + 1,
                                confirmedAt: confirmedAt(monday + 2))
        let second = attestation(id, start: monday + 2, end: sunday,
                                 confirmedAt: confirmedAt(sunday + 1))

        XCTAssertNil(SalaryPayrollCoverageAttestationStoreV2.encode([first, second]))
    }

    func testUserDefaultsPersistenceRoundTrip() {
        let suite = "SalaryPayrollCoverageAttestationV2Tests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer {
            defaults.removePersistentDomain(forName: suite)
        }

        XCTAssertTrue(
            SalaryPayrollCoverageAttestationStoreV2.confirm(
                employerId: "company-a",
                startEpochDay: monday,
                endEpochDay: sunday,
                timeZoneId: zoneId,
                confirmedAt: confirmedAt(sunday + 1),
                defaults: defaults
            )
        )

        let read = SalaryPayrollCoverageAttestationStoreV2.read(defaults: defaults)
        XCTAssertTrue(read.reliable)
        XCTAssertEqual(read.attestations.count, 1)

        let resolution = SalaryPayrollCoverageAttestationStoreV2.resolve(
            employerId: "company-a",
            requestedStartEpochDay: monday,
            requestedEndEpochDay: sunday,
            timeZoneId: zoneId,
            now: confirmedAt(sunday + 2),
            defaults: defaults
        )
        XCTAssertTrue(resolution.reliable)
        XCTAssertTrue(resolution.exhaustive)
    }

    private func resolve(_ items: [SalaryPayrollCoverageAttestationV2])
        -> SalaryPayrollCoverageResolutionV2 {
        SalaryPayrollCoverageAttestationStoreV2.resolve(
            attestations: items,
            employerId: "company-a",
            requestedStartEpochDay: monday,
            requestedEndEpochDay: sunday,
            timeZoneId: zoneId,
            now: confirmedAt(sunday + 2)
        )
    }

    private func attestation(
        _ id: String,
        start: Int64,
        end: Int64,
        confirmedAt: Date,
        employer: String = "company-a"
    ) -> SalaryPayrollCoverageAttestationV2 {
        SalaryPayrollCoverageAttestationV2(
            id: UUID(uuidString: id)!,
            employerId: employer,
            startEpochDay: start,
            endEpochDay: end,
            confirmedAt: confirmedAt,
            timeZoneId: zoneId
        )
    }

    private func confirmedAt(_ day: Int64) -> Date {
        let civil = civilDate(day)
        let calendar = localCalendar()
        return calendar.date(
            from: DateComponents(year: civil.0, month: civil.1, day: civil.2)
        )!
    }

    private func localCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zoneId)!
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return calendar
    }

    private func civilDate(_ day: Int64) -> (Int, Int, Int) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = Date(timeIntervalSince1970: Double(day) * 86400)
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return (parts.year!, parts.month!, parts.day!)
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(
            from: DateComponents(year: year, month: month, day: day)
        )!
        return Int64(date.timeIntervalSince1970 / 86400)
    }
}
