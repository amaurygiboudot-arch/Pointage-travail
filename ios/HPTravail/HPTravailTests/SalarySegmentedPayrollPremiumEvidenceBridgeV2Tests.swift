import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollPremiumEvidenceBridgeV2Tests: XCTestCase {
    func testNoNightMultiplierDoesNotRequireNightStore() throws {
        let f = try fixture()
        let result = SalarySegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts: f.contracts,
            rules: f.rules,
            nightSnapshots: [],
            nightSourceReliable: false,
            nightWarnings: ["night-store-down"],
            holidayScope: completeScope(),
            now: Date(timeIntervalSince1970: 10)
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.evidence.count, 1)
        XCTAssertNil(result.evidence.first?.nightRule)
    }

    func testMatchingNightSnapshotCoversWholeSlice() throws {
        let f = try fixture(nightMultiplier: 1.25)
        let result = SalarySegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts: f.contracts,
            rules: f.rules,
            nightSnapshots: [night("n1", from: 4, to: nil, multiplier: 1.25)],
            nightSourceReliable: true,
            nightWarnings: [],
            holidayScope: completeScope(),
            now: Date(timeIntervalSince1970: 10)
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.evidence.first?.nightRule?.multiplier, 1.25)
    }

    func testNightRuleChangeInsideSliceBlocks() throws {
        let f = try fixture(nightMultiplier: 1.25)
        let result = SalarySegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts: f.contracts,
            rules: f.rules,
            nightSnapshots: [
                night("n1", from: 4, to: 6, multiplier: 1.25),
                night("n2", from: 7, to: nil, multiplier: 1.25)
            ],
            nightSourceReliable: true,
            nightWarnings: [],
            holidayScope: completeScope(),
            now: Date(timeIntervalSince1970: 10)
        )
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(
            SalarySegmentedPayrollPremiumEvidenceBridgeV2.nightCoverageWarning
        ))
    }

    func testIncompleteHolidayScopeBlocks() throws {
        let f = try fixture()
        let result = SalarySegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts: f.contracts,
            rules: f.rules,
            nightSnapshots: [],
            nightSourceReliable: true,
            nightWarnings: [],
            holidayScope: .init(
                jurisdiction: .addressUnknown,
                complete: false,
                postalCode: nil,
                warning: "scope-incomplete"
            ),
            now: Date(timeIntervalSince1970: 10)
        )
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains("scope-incomplete"))
        XCTAssertTrue(result.warnings.contains(
            SalarySegmentedPayrollSessionEvidenceBuilderV2.holidayWarning
        ))
    }

    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let rules: SalaryConventionCoverageV2
    }

    private func fixture(nightMultiplier: Double? = nil) throws -> Fixture {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 4,
                periodEndEpochDay: 10,
                sourceReliable: true,
                snapshots: [
                    SalaryEmploymentContractSnapshotV2(
                        versionId: "c1",
                        sourceId: "contract-source",
                        effectiveFromEpochDay: 4,
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
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: 4,
            periodEndEpochDay: 10,
            segments: [
                SalaryConventionCoverageSegmentV2(
                    startEpochDay: 4,
                    endEpochDay: 10,
                    snapshot: SalaryConventionRuleSnapshotV2(
                        idcc: "0292",
                        versionId: "r1",
                        sourceId: "rule-source",
                        effectiveFromEpochDay: 4,
                        effectiveToEpochDay: nil,
                        rules: PayrollRulesV2(
                            weeklyRegularMinutes: 2100,
                            nightMultiplier: nightMultiplier
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
        return Fixture(contracts: contracts, rules: rules)
    }

    private func night(
        _ version: String,
        from: Int64,
        to: Int64?,
        multiplier: Double
    ) -> SalaryConventionNightRuleSnapshotV2 {
        SalaryConventionNightRuleSnapshotV2(
            idcc: "0292",
            versionId: version,
            sourceId: "night-(version)",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            rule: NightPremiumRuleV2(
                startMinute: 21 * 60,
                endMinute: 6 * 60,
                multiplier: multiplier
            )!,
            checkedAtMs: 1,
            note: nil
        )
    }

    private func completeScope() -> FrenchPublicHolidayCalendarV2.Scope {
        .init(
            jurisdiction: .commonFrance,
            complete: true,
            postalCode: "75001",
            warning: nil
        )
    }
}
