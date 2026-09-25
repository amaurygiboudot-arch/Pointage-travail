import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollRuntimeSourceV2Tests: XCTestCase {
    func testPartialPayrollWeekRequiresWholeMondayToSundayCoverage() throws {
        let fixture = try makeFixture(start: 6, end: 8)

        let required = SalarySegmentedPayrollRuntimeSourceV2.requirement(
            contracts: fixture.contracts,
            rules: fixture.rules
        )

        XCTAssertTrue(required.reliable)
        XCTAssertEqual(required.startEpochDay, 4)
        XCTAssertEqual(required.endEpochDay, 10)
    }

    func testMultipleWeeksExpandOnlyToOuterWeekBoundaries() throws {
        let fixture = try makeFixture(start: 6, end: 19)

        let required = SalarySegmentedPayrollRuntimeSourceV2.requirement(
            contracts: fixture.contracts,
            rules: fixture.rules
        )

        XCTAssertTrue(required.reliable)
        XCTAssertEqual(required.startEpochDay, 4)
        XCTAssertEqual(required.endEpochDay, 24)
    }

    func testSliceOnlyAttestationCannotCertifyFullWeekButFullWeekCan() throws {
        let fixture = try makeFixture(start: 6, end: 8)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "coverage-runtime-\(UUID().uuidString)"))
        defer { defaults.removePersistentDomain(forName: defaultsSuiteName(defaults)) }

        let work = SalaryWorkSessionSourceV2(
            sessions: [
                SalarySessionFactV2(
                    id: "s",
                    entry: date(6, 8),
                    exit: date(6, 16),
                    employerId: "company",
                    pauses: []
                )
            ],
            reliable: true
        )
        let now = date(12, 12)

        XCTAssertTrue(
            SalarySegmentedPayrollCoverageStoreV2.confirm(
                defaults: defaults,
                work: work,
                employerId: "company",
                coveredStartEpochDay: 6,
                coveredEndEpochDay: 8,
                confirmedAt: now,
                timeZoneId: "UTC",
                id: "slice-only"
            )
        )

        let incomplete = SalarySegmentedPayrollRuntimeSourceV2.source(
            work: work,
            defaults: defaults,
            contracts: fixture.contracts,
            rules: fixture.rules,
            timeZoneId: "UTC",
            now: now
        )
        XCTAssertFalse(incomplete.exhaustive)
        XCTAssertEqual(incomplete.coveredStartEpochDay, 4)
        XCTAssertEqual(incomplete.coveredEndEpochDay, 10)

        XCTAssertTrue(
            SalarySegmentedPayrollCoverageStoreV2.confirm(
                defaults: defaults,
                work: work,
                employerId: "company",
                coveredStartEpochDay: 4,
                coveredEndEpochDay: 10,
                confirmedAt: now,
                timeZoneId: "UTC",
                id: "whole-week"
            )
        )

        let complete = SalarySegmentedPayrollRuntimeSourceV2.source(
            work: work,
            defaults: defaults,
            contracts: fixture.contracts,
            rules: fixture.rules,
            timeZoneId: "UTC",
            now: now
        )
        XCTAssertTrue(complete.exhaustive)
        XCTAssertEqual(complete.coveredStartEpochDay, 4)
        XCTAssertEqual(complete.coveredEndEpochDay, 10)
    }

    func testUnreliableTimelineBlocksBeforeCoverageStore() throws {
        let fixture = try makeFixture(start: 6, end: 8)
        let badRules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: 6,
            periodEndEpochDay: 8,
            segments: [],
            sourceReliable: false,
            fullyCovered: false,
            warnings: ["rules-corrupt"]
        )

        let required = SalarySegmentedPayrollRuntimeSourceV2.requirement(
            contracts: fixture.contracts,
            rules: badRules
        )

        XCTAssertFalse(required.reliable)
        XCTAssertTrue(required.warnings.contains(SalarySegmentedPayrollRuntimeSourceV2.requirementWarning))
    }

    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let rules: SalaryConventionCoverageV2
    }

    private func makeFixture(start: Int64, end: Int64) throws -> Fixture {
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
        return Fixture(contracts: contracts, rules: rules)
    }

    private func date(_ epochDay: Int64, _ hour: Int) -> Date {
        Date(timeIntervalSince1970: Double(epochDay) * 86_400 + Double(hour * 3_600))
    }

    private func defaultsSuiteName(_ defaults: UserDefaults) -> String {
        // Les suites de test sont isolées par UUID ; ce nom n'est pas utilisé par l'application.
        defaults.volatileDomainNames.first(where: { $0.hasPrefix("coverage-runtime-") }) ?? ""
    }
}
