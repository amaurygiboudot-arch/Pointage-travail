import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossProductionBridgeV2Tests: XCTestCase {
    func testCoverageExpandsPeriodToWholeIsoWeeks() {
        let start = epochDay(2026, 9, 1)
        let end = epochDay(2026, 9, 30)

        let bounds = SalarySegmentedWorkedGrossProductionBridgeV2.coverageBounds(
            start: start,
            end: end
        )

        XCTAssertEqual(bounds?.start, epochDay(2026, 8, 31))
        XCTAssertEqual(bounds?.end, epochDay(2026, 10, 4))
    }

    func testInvertedPeriodIsRejected() {
        XCTAssertNil(
            SalarySegmentedWorkedGrossProductionBridgeV2.coverageBounds(
                start: 10,
                end: 9
            )
        )
    }

    func testDifferentCompanyIsRejectedBeforeReadingPayrollSources() {
        let period = YearMonthV2(year: 2026, month: 9)!
        let result = SalarySegmentedWorkedGrossProductionBridgeV2.calculateDetailed(
            companyId: "company-a",
            period: period,
            timeZoneId: "Europe/Paris",
            work: .init(sessions: [], reliable: true),
            contracts: contracts(companyId: "company-b", month: 9),
            rules: rules(companyId: "company-a", month: 9),
            premiums: [],
            now: Date()
        )

        XCTAssertNil(result.worked)
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedWorkedGrossProductionBridgeV2.sourceMismatchWarning))
    }

    func testDifferentContractMonthIsRejectedBeforeReadingPayrollSources() {
        let result = SalarySegmentedWorkedGrossProductionBridgeV2.calculateDetailed(
            companyId: "company-a",
            period: YearMonthV2(year: 2026, month: 9)!,
            timeZoneId: "Europe/Paris",
            work: .init(sessions: [], reliable: true),
            contracts: contracts(companyId: "company-a", month: 8),
            rules: rules(companyId: "company-a", month: 9),
            premiums: [],
            now: Date()
        )

        XCTAssertNil(result.worked)
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedWorkedGrossProductionBridgeV2.sourceMismatchWarning))
    }

    private func contracts(companyId: String, month: Int) -> SalaryEmploymentContractPeriodResolutionV2 {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(
            YearMonthV2(year: 2026, month: month)!
        )!
        return .init(
            companyId: companyId,
            periodStartEpochDay: range.start,
            periodEndEpochDay: range.end,
            sourceReliable: true,
            coverage: nil,
            contract: nil,
            warnings: []
        )
    }

    private func rules(companyId: String, month: Int) -> SalaryConventionCoverageV2 {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(
            YearMonthV2(year: 2026, month: month)!
        )!
        return .init(
            companyId: companyId,
            idcc: "292",
            periodStartEpochDay: range.start,
            periodEndEpochDay: range.end,
            segments: [],
            sourceReliable: true,
            fullyCovered: false,
            warnings: []
        )
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(
            from: DateComponents(year: year, month: month, day: day)
        )!
        return Int64(floor(date.timeIntervalSince1970 / 86_400))
    }
}
