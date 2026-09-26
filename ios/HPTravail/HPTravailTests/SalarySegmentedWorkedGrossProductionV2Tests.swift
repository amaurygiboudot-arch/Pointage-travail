import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossProductionV2Tests: XCTestCase {
    func testReliableB21SourceReachesB20AndKeepsGlobalWarning() throws {
        let f = try fixture()
        var source = f.source
        source.warnings = ["trace-source"]

        let result = SalarySegmentedWorkedGrossProductionV2.calculateFromSource(
            contracts: f.contracts,
            rules: f.rules,
            base: f.base,
            source: source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(
            try XCTUnwrap(result.variables.pieces.first).variableGross,
            62.5,
            accuracy: 0.0001
        )
        XCTAssertEqual(
            try XCTUnwrap(result.worked.workedGross),
            1_062.5,
            accuracy: 0.0001
        )
        XCTAssertTrue(result.warnings.contains("trace-source"))
        XCTAssertTrue(result.worked.warnings.contains("trace-source"))
    }

    func testNonExhaustiveCoverageBlocksB21AndB20() throws {
        let f = try fixture()
        let original = f.source
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: original.employerId,
            work: original.work,
            sourceId: original.sourceId,
            exhaustive: false,
            coveredStartEpochDay: original.coveredStartEpochDay,
            coveredEndEpochDay: original.coveredEndEpochDay,
            checkedAt: original.checkedAt,
            timeZoneId: original.timeZoneId,
            warnings: original.warnings
        )

        let result = SalarySegmentedWorkedGrossProductionV2.calculateFromSource(
            contracts: f.contracts,
            rules: f.rules,
            base: f.base,
            source: source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.variables.reliable)
        XCTAssertNil(result.worked.workedGross)
        XCTAssertFalse(result.warnings.isEmpty)
    }

    func testPeriodBoundsExpandToWholeISOWeeks() {
        let first = SalarySegmentedWorkedGrossProductionV2.requiredCoverageBounds(
            start: 6,
            end: 8
        )
        XCTAssertEqual(first?.start, 4)
        XCTAssertEqual(first?.end, 10)

        let epoch = SalarySegmentedWorkedGrossProductionV2.requiredCoverageBounds(
            start: 0,
            end: 0
        )
        XCTAssertEqual(epoch?.start, -3)
        XCTAssertEqual(epoch?.end, 3)
    }

    func testExtremeBoundsFailClosedWithoutOverflow() {
        XCTAssertNil(
            SalarySegmentedWorkedGrossProductionV2.requiredCoverageBounds(
                start: Int64.max,
                end: Int64.max
            )
        )
        XCTAssertNil(
            SalarySegmentedWorkedGrossProductionV2.requiredCoverageBounds(
                start: Int64.min,
                end: Int64.min
            )
        )
    }

    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let rules: SalaryConventionCoverageV2
        let base: SegmentedMonthlyBaseResultV2
        let source: SalarySegmentedPayrollSessionSourceV2
        let premiums: [SalarySegmentedPayrollPremiumEvidenceV2]
        let now: Date
    }

    private func fixture() throws -> Fixture {
        let start: Int64 = 4
        let end: Int64 = 10
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: start,
                periodEndEpochDay: end,
                sourceReliable: true,
                snapshots: [
                    .init(
                        versionId: "c1",
                        sourceId: "contract-test",
                        effectiveFromEpochDay: start,
                        effectiveToEpochDay: nil,
                        contract: ContractV2(
                            id: "c1",
                            employerId: "company",
                            type: .fullTime,
                            contractualWeeklyMinutes: 2100,
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
            weeklyRegularMinutes: 2100,
            overtimeTiers: [
                OvertimeTierV2(
                    fromMinutes: 2100,
                    toMinutes: nil,
                    multiplier: 1.25
                )
            ]
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            segments: [
                .init(
                    startEpochDay: start,
                    endEpochDay: end,
                    snapshot: .init(
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
            work: .init(sessions: sessions, reliable: true),
            sourceId: "coverage-test",
            exhaustive: true,
            coveredStartEpochDay: 4,
            coveredEndEpochDay: 10,
            checkedAt: now,
            timeZoneId: "UTC",
            warnings: []
        )
        let premiums = SalaryPayrollCalculationTimelineV2
            .align(contracts: contracts, rules: rules)
            .slices
            .map {
                SalarySegmentedPayrollPremiumEvidenceV2(
                    slice: $0,
                    sourceId: "premium-test",
                    reliable: true,
                    nightRule: nil,
                    holidayScope: .init(
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
                    scheduledMinutes: 2100,
                    factor: 1,
                    fullMonthBaseGross: 1_000,
                    proratedBaseGross: 1_000
                )
            ],
            baseGross: 1_000,
            reliable: true,
            warnings: []
        )
        return Fixture(
            contracts: contracts,
            rules: rules,
            base: base,
            source: source,
            premiums: premiums,
            now: now
        )
    }

    private func date(_ day: Int64, _ hour: Int) -> Date {
        Date(timeIntervalSince1970: Double(day) * 86_400 + Double(hour) * 3_600)
    }
}
