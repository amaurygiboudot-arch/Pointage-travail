import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossProductionV2Tests: XCTestCase {
    func testTwoProvenContractSegmentsReachB20WithExplicitZeroVariables() throws {
        let f = try fixture()
        let result = SalarySegmentedWorkedGrossProductionV2.calculate(
            contracts: f.contracts,
            rules: f.rules,
            prorationSource: f.proration,
            source: f.source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 2_275.0, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(result.variableGross), 0.0, accuracy: 0.0)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 2_275.0, accuracy: 0.0001)
    }

    func testMissingCoverageBlocksWholeB20AndKeepsB21Warning() throws {
        var f = try fixture()
        let s = f.source
        f.source = SalarySegmentedPayrollSessionSourceV2(
            employerId: s.employerId,
            work: s.work,
            sourceId: s.sourceId,
            exhaustive: false,
            coveredStartEpochDay: s.coveredStartEpochDay,
            coveredEndEpochDay: s.coveredEndEpochDay,
            checkedAt: s.checkedAt,
            timeZoneId: s.timeZoneId,
            warnings: s.warnings
        )
        let result = SalarySegmentedWorkedGrossProductionV2.calculate(
            contracts: f.contracts,
            rules: f.rules,
            prorationSource: f.proration,
            source: f.source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedPayrollSessionEvidenceBuilderV2.sourceWarning))
        XCTAssertTrue(result.warnings.contains(SalarySegmentedWorkedGrossAssemblerV2.variableReliabilityWarning))
    }

    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let rules: SalaryConventionCoverageV2
        let proration: SalarySegmentedProrationSourceV2
        var source: SalarySegmentedPayrollSessionSourceV2
        let premiums: [SalarySegmentedPayrollPremiumEvidenceV2]
        let now: Date
    }

    private func fixture() throws -> Fixture {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 4,
                periodEndEpochDay: 17,
                sourceReliable: true,
                snapshots: [
                    contractSnapshot("c1", from: 4, to: 10, rate: 10),
                    contractSnapshot("c2", from: 11, to: nil, rate: 20)
                ]
            )
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: 4,
            periodEndEpochDay: 17,
            segments: [
                SalaryConventionCoverageSegmentV2(
                    startEpochDay: 4,
                    endEpochDay: 17,
                    snapshot: SalaryConventionRuleSnapshotV2(
                        idcc: "0292",
                        versionId: "r1",
                        sourceId: "rules",
                        effectiveFromEpochDay: 4,
                        effectiveToEpochDay: 17,
                        rules: PayrollRulesV2(
                            weeklyRegularMinutes: 2100,
                            overtimeTiers: [OvertimeTierV2(fromMinutes: 2100, toMinutes: nil, multiplier: 1.25)]
                        ),
                        checkedAtMs: 1,
                        note: nil
                    )
                )
            ],
            sourceReliable: true,
            fullyCovered: true,
            warnings: []
        )
        let proration = SalarySegmentedProrationSourceV2(
            proration: ConfirmedSegmentedMonthlyProrationV2(
                sourceId: "planning-confirme",
                checkedAtMs: 1,
                segments: [
                    ConfirmedProrationSegmentV2(versionId: "c1", startEpochDay: 4, endEpochDay: 10, scheduledMinutes: 2100),
                    ConfirmedProrationSegmentV2(versionId: "c2", startEpochDay: 11, endEpochDay: 17, scheduledMinutes: 2100)
                ]
            ),
            reliable: true,
            warnings: []
        )
        let now = date(day: 18, hour: 12)
        let work = SalaryWorkSessionSourceV2(sessions: [], reliable: true)
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: "company",
            work: work,
            sourceId: "coverage-test",
            exhaustive: true,
            coveredStartEpochDay: 4,
            coveredEndEpochDay: 17,
            checkedAt: now,
            timeZoneId: "UTC"
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        let premiums = timeline.slices.map {
            SalarySegmentedPayrollPremiumEvidenceV2(
                slice: $0,
                sourceId: "rules-test",
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
        return Fixture(
            contracts: contracts,
            rules: rules,
            proration: proration,
            source: source,
            premiums: premiums,
            now: now
        )
    }

    private func contractSnapshot(
        _ id: String,
        from: Int64,
        to: Int64?,
        rate: Double
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: id,
            sourceId: "contract-test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: id,
                employerId: "company",
                type: .fullTime,
                contractualWeeklyMinutes: 2100,
                grossHourlyRate: rate,
                hireDateEpochDay: 0
            ),
            checkedAtMs: 1,
            note: nil
        )
    }

    private func date(day: Int64, hour: Int) -> Date {
        Date(timeIntervalSince1970: Double(day * 86_400 + Int64(hour * 3_600)))
    }
}
