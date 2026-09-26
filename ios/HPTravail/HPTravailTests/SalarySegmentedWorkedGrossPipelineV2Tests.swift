import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossPipelineV2Tests: XCTestCase {
    func testSessionsReachB20WithoutLosingB21Warnings() throws {
        let fixture = try makeFixture(warnings: ["trace-runtime"])
        let result = SalarySegmentedWorkedGrossPipelineV2.calculate(
            contracts: fixture.contracts,
            rules: fixture.rules,
            source: fixture.source,
            premiums: fixture.premiums,
            base: fixture.base,
            now: fixture.now
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 1_500, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(result.variableGross), 62.5, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 1_562.5, accuracy: 0.0001)
        XCTAssertTrue(result.warnings.contains("trace-runtime"))
    }

    func testMissingExhaustiveCoverageBlocksTheWholeChain() throws {
        var fixture = try makeFixture(warnings: ["coverage-missing"])
        let source = fixture.source
        fixture.source = SalarySegmentedPayrollSessionSourceV2(
            employerId: source.employerId,
            work: source.work,
            sourceId: source.sourceId,
            exhaustive: false,
            coveredStartEpochDay: source.coveredStartEpochDay,
            coveredEndEpochDay: source.coveredEndEpochDay,
            checkedAt: source.checkedAt,
            timeZoneId: source.timeZoneId,
            warnings: source.warnings
        )

        let result = SalarySegmentedWorkedGrossPipelineV2.calculate(
            contracts: fixture.contracts,
            rules: fixture.rules,
            source: fixture.source,
            premiums: fixture.premiums,
            base: fixture.base,
            now: fixture.now
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertNil(result.variableGross)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.warnings.contains("coverage-missing"))
        XCTAssertTrue(result.warnings.contains(SalarySegmentedPayrollSessionEvidenceBuilderV2.sourceWarning))
        XCTAssertTrue(result.warnings.contains(SalarySegmentedWorkedGrossAssemblerV2.variableReliabilityWarning))
    }

    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let rules: SalaryConventionCoverageV2
        var source: SalarySegmentedPayrollSessionSourceV2
        let premiums: [SalarySegmentedPayrollPremiumEvidenceV2]
        let base: SegmentedMonthlyBaseResultV2
        let now: Date
    }

    private func makeFixture(warnings: [String] = []) throws -> Fixture {
        let start: Int64 = 4
        let end: Int64 = 10
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: start,
                periodEndEpochDay: end,
                sourceReliable: true,
                snapshots: [
                    SalaryEmploymentContractSnapshotV2(
                        versionId: "c1",
                        sourceId: "contract-test",
                        effectiveFromEpochDay: start,
                        effectiveToEpochDay: nil,
                        contract: ContractV2(
                            id: "c1",
                            employerId: "company",
                            type: .fullTime,
                            contractualWeeklyMinutes: 2_100,
                            grossHourlyRate: 10,
                            hireDateEpochDay: 0
                        ),
                        checkedAtMs: 1,
                        note: nil
                    )
                ]
            )
        )
        let payrollRules = PayrollRulesV2(
            weeklyRegularMinutes: 2_100,
            overtimeTiers: [
                OvertimeTierV2(fromMinutes: 2_100, toMinutes: nil, multiplier: 1.25)
            ]
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            segments: [
                SalaryConventionCoverageSegmentV2(
                    startEpochDay: start,
                    endEpochDay: end,
                    snapshot: SalaryConventionRuleSnapshotV2(
                        idcc: "0292",
                        versionId: "r1",
                        sourceId: "rule-test",
                        effectiveFromEpochDay: start,
                        effectiveToEpochDay: end,
                        rules: payrollRules,
                        checkedAtMs: 1,
                        note: nil
                    )
                )
            ],
            sourceReliable: true,
            fullyCovered: true,
            warnings: []
        )
        let sessions = (4...8).map { day in
            SalarySessionFactV2(
                id: "s\(day)",
                entry: date(Int64(day), 8),
                exit: date(Int64(day), 16),
                employerId: "company",
                pauses: []
            )
        }
        let now = date(11, 12)
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: "company",
            work: SalaryWorkSessionSourceV2(sessions: sessions, reliable: true),
            sourceId: "runtime-test",
            exhaustive: true,
            coveredStartEpochDay: 4,
            coveredEndEpochDay: 10,
            checkedAt: now,
            timeZoneId: "UTC",
            warnings: warnings
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        let premiums = timeline.slices.map {
            SalarySegmentedPayrollPremiumEvidenceV2(
                slice: $0,
                sourceId: "rules-test",
                reliable: true,
                nightRule: nil,
                holidayScope: FrenchPublicHolidayCalendarV2.Scope(
                    jurisdiction: .commonFrance,
                    complete: true,
                    postalCode: nil,
                    warning: nil
                )
            )
        }
        let base = SegmentedMonthlyBaseResultV2(
            pieces: [
                SegmentedMonthlyBasePieceV2(
                    versionId: "c1",
                    startEpochDay: start,
                    endEpochDay: end,
                    scheduledMinutes: 2_100,
                    factor: 1.0,
                    fullMonthBaseGross: 1_500,
                    proratedBaseGross: 1_500
                )
            ],
            baseGross: 1_500,
            reliable: true,
            warnings: []
        )
        return Fixture(
            contracts: contracts,
            rules: rules,
            source: source,
            premiums: premiums,
            base: base,
            now: now
        )
    }

    private func date(_ epochDay: Int64, _ hour: Int) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var parts = utc.dateComponents(
            [.year, .month, .day],
            from: Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        )
        parts.hour = hour
        parts.minute = 0
        parts.second = 0
        return utc.date(from: parts)!
    }
}
