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

    func testAnonymizedReadonlyExportBlocksUntilEmployerIsExplicitlyConfirmed() throws {
        let f = try readonlyExportFixture(assignFirstEmployer: false)
        let result = SalarySegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts: f.contracts,
            rules: f.rules,
            prorationSource: f.proration,
            source: f.source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.evidence.warnings.contains(SalaryPaidWorkAggregatorV2.unassignedEmployerWarning))
    }

    func testAnonymizedReadonlyExportReachesB20AfterExplicitEmployerConfirmation() throws {
        let f = try readonlyExportFixture(assignFirstEmployer: true)
        let result = SalarySegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts: f.contracts,
            rules: f.rules,
            prorationSource: f.proration,
            source: f.source,
            premiums: f.premiums,
            now: f.now
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.evidence.contributingSessionIds.count, 6)
        XCTAssertEqual(result.evidence.slices.first?.weeks.map { $0.week.paidMinutes }, [43, 2019])
        XCTAssertEqual(result.evidence.slices.first?.weeks.first?.week.sundayMinutes, 43)
        XCTAssertEqual(try XCTUnwrap(result.variables.pieces.first).variableGross, 0, accuracy: 0)
        XCTAssertEqual(try XCTUnwrap(result.base.baseGross), try XCTUnwrap(result.workedGross), accuracy: 0.0001)
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

    private func readonlyExportFixture(assignFirstEmployer: Bool) throws -> Fixture {
        let start = epochDay(2024, 9, 22)
        let end = epochDay(2024, 9, 27)
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: start,
                periodEndEpochDay: end,
                sourceReliable: true,
                snapshots: [contractSnapshot("real-contract", from: start, to: nil, rate: 10)]
            )
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
                        versionId: "real-rules",
                        sourceId: "anonymized-readonly-export-rules",
                        effectiveFromEpochDay: start,
                        effectiveToEpochDay: end,
                        rules: PayrollRulesV2(
                            weeklyRegularMinutes: 2100,
                            overtimeTiers: [
                                OvertimeTierV2(fromMinutes: 2100, toMinutes: nil, multiplier: 1.25)
                            ]
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
                sourceId: "anonymized-readonly-export-proration",
                checkedAtMs: 1,
                segments: [
                    ConfirmedProrationSegmentV2(
                        versionId: "real-contract",
                        startEpochDay: start,
                        endEpochDay: end,
                        scheduledMinutes: 2100
                    )
                ]
            ),
            reliable: true,
            warnings: []
        )
        let checkedAt = Date(timeIntervalSince1970: Double(epochDay(2024, 9, 30) * 86_400 + 12 * 3_600))
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: "company",
            work: SalaryWorkSessionSourceV2(
                sessions: readonlyExportSessions(assignFirstEmployer: assignFirstEmployer),
                reliable: true
            ),
            sourceId: "anonymized-readonly-export-2026-09-25",
            exhaustive: true,
            coveredStartEpochDay: epochDay(2024, 9, 16),
            coveredEndEpochDay: epochDay(2024, 9, 29),
            checkedAt: checkedAt,
            timeZoneId: "Europe/Paris"
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        let premiums = timeline.slices.map {
            SalarySegmentedPayrollPremiumEvidenceV2(
                slice: $0,
                sourceId: "anonymized-readonly-export-premiums",
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
            now: checkedAt
        )
    }

    private func readonlyExportSessions(assignFirstEmployer: Bool) -> [SalarySessionFactV2] {
        let employer = "company"
        return [
            .init(
                id: "real-1",
                entry: date(ms: 1_726_986_600_000),
                exit: date(ms: 1_726_989_188_965),
                employerId: assignFirstEmployer ? employer : nil,
                pauses: []
            ),
            .init(
                id: "real-2",
                entry: date(ms: 1_727_157_600_000),
                exit: date(ms: 1_727_196_631_158),
                employerId: employer,
                pauses: []
            ),
            .init(
                id: "real-3",
                entry: date(ms: 1_727_197_200_000),
                exit: date(ms: 1_727_240_098_278),
                employerId: employer,
                pauses: [
                    PaidPauseFactV2(
                        start: date(ms: 1_727_197_200_000),
                        end: date(ms: 1_727_240_098_278),
                        paid: false
                    )
                ]
            ),
            .init(
                id: "real-4",
                entry: date(ms: 1_727_244_000_000),
                exit: date(ms: 1_727_250_403_814),
                employerId: employer,
                pauses: []
            ),
            .init(
                id: "real-5",
                entry: date(ms: 1_727_330_400_000),
                exit: date(ms: 1_727_367_104_494),
                employerId: employer,
                pauses: []
            ),
            .init(
                id: "real-6",
                entry: date(ms: 1_727_416_800_000),
                exit: date(ms: 1_727_455_928_092),
                employerId: employer,
                pauses: []
            )
        ]
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

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let value = utc.date(from: DateComponents(year: year, month: month, day: day))!
        return Int64(floor(value.timeIntervalSince1970 / 86_400))
    }

    private func date(ms: Int64) -> Date {
        Date(timeIntervalSince1970: Double(ms) / 1_000.0)
    }

    private func date(day: Int64, hour: Int) -> Date {
        Date(timeIntervalSince1970: Double(day * 86_400 + Int64(hour * 3_600)))
    }
}
