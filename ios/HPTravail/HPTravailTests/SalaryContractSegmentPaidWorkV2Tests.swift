import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryContractSegmentPaidWorkV2Tests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        return value
    }

    func testSessionAcrossContractChangeIsSplitAtMidnight() throws {
        let changeDay = epochDay(2026, 9, 15)
        let result = SalaryContractSegmentPaidWorkAllocatorV2.allocate(
            sessions: [session(start: date(2026, 9, 14, 22), end: date(2026, 9, 15, 6))],
            segments: [
                segment(version: "v1", start: epochDay(2026, 9, 1), end: changeDay - 1, rate: 13),
                segment(version: "v2", start: changeDay, end: epochDay(2026, 9, 30), rate: 14)
            ],
            employerId: "company",
            period: try XCTUnwrap(YearMonthV2(year: 2026, month: 9)),
            sourceReliable: true,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.segments.count, 2)
        XCTAssertEqual(result.segments[0].paidMinutes, 120)
        XCTAssertEqual(result.segments[1].paidMinutes, 360)
        XCTAssertEqual(result.totalPaidMinutes, 480)
        XCTAssertEqual(result.segments[0].contract.grossHourlyRate, 13)
        XCTAssertEqual(result.segments[1].contract.grossHourlyRate, 14)
    }

    func testUnpaidPauseAcrossBoundaryIsDeductedOnEachSide() throws {
        let changeDay = epochDay(2026, 9, 15)
        let pause = PaidPauseFactV2(
            start: date(2026, 9, 14, 23),
            end: date(2026, 9, 15, 1),
            paid: false
        )
        let result = SalaryContractSegmentPaidWorkAllocatorV2.allocate(
            sessions: [session(start: date(2026, 9, 14, 22), end: date(2026, 9, 15, 6), pauses: [pause])],
            segments: [
                segment(version: "v1", start: epochDay(2026, 9, 1), end: changeDay - 1, rate: 13),
                segment(version: "v2", start: changeDay, end: epochDay(2026, 9, 30), rate: 14)
            ],
            employerId: "company",
            period: try XCTUnwrap(YearMonthV2(year: 2026, month: 9)),
            sourceReliable: true,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.segments[0].paidMinutes, 60)
        XCTAssertEqual(result.segments[1].paidMinutes, 300)
        XCTAssertEqual(result.totalPaidMinutes, 360)
    }

    func testUnresolvedPauseKeepsResultFailClosed() throws {
        let pause = PaidPauseFactV2(
            start: date(2026, 9, 8, 12),
            end: date(2026, 9, 8, 13),
            paid: nil
        )
        let result = SalaryContractSegmentPaidWorkAllocatorV2.allocate(
            sessions: [session(start: date(2026, 9, 8, 8), end: date(2026, 9, 8, 16), pauses: [pause])],
            segments: [segment(
                version: "v1",
                start: epochDay(2026, 9, 1),
                end: epochDay(2026, 9, 30),
                rate: 13
            )],
            employerId: "company",
            period: try XCTUnwrap(YearMonthV2(year: 2026, month: 9)),
            sourceReliable: true,
            calendar: calendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.warnings.isEmpty)
    }

    func testUnreliableRuntimeSourceContaminatesSegments() throws {
        let result = SalaryContractSegmentPaidWorkAllocatorV2.allocate(
            sessions: [session(start: date(2026, 9, 8, 8), end: date(2026, 9, 8, 16))],
            segments: [segment(
                version: "v1",
                start: epochDay(2026, 9, 1),
                end: epochDay(2026, 9, 30),
                rate: 13
            )],
            employerId: "company",
            period: try XCTUnwrap(YearMonthV2(year: 2026, month: 9)),
            sourceReliable: false,
            calendar: calendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalaryContractSegmentPaidWorkAllocatorV2.unreliableSourceWarning))
    }

    private func segment(
        version: String,
        start: Int64,
        end: Int64,
        rate: Double
    ) -> SalaryEmploymentContractCoverageSegmentV2 {
        SalaryEmploymentContractCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryEmploymentContractSnapshotV2(
                versionId: version,
                sourceId: "contract-\(version)",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                contract: ContractV2(
                    id: "contract-\(version)",
                    employerId: "company",
                    type: .fullTime,
                    contractualWeeklyMinutes: 35 * 60,
                    grossHourlyRate: rate,
                    hireDateEpochDay: epochDay(2020, 1, 1)
                ),
                checkedAtMs: 1,
                note: nil
            )
        )
    }

    private func session(
        start: Date,
        end: Date,
        pauses: [PaidPauseFactV2] = []
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: "session-\(start.timeIntervalSince1970)",
            entry: start,
            exit: end,
            employerId: "company",
            pauses: pauses
        )
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int) -> Date {
        calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: year,
            month: month,
            day: day,
            hour: hour
        ))!
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let value = utc.date(from: DateComponents(
            calendar: utc,
            timeZone: utc.timeZone,
            year: year,
            month: month,
            day: day,
            hour: 0
        ))!
        return Int64(floor(value.timeIntervalSince1970 / 86_400.0))
    }
}
