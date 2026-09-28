import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollSessionEvidenceDstV2Tests: XCTestCase {
    func testAmbiguousFallNightBoundaryBlocks() throws { try assertBlocked(month: 10, day: 19) }
    func testNonexistentSpringNightBoundaryBlocks() throws { try assertBlocked(month: 3, day: 23) }

    private func assertBlocked(month: Int, day: Int) throws {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        let civil = try XCTUnwrap(utc.date(from: DateComponents(year: 2026, month: month, day: day)))
        let start = Int64(civil.timeIntervalSince1970 / 86400)
        let end = start + 6
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Paris"))
        let monday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: month, day: day)))
        let sunday = try XCTUnwrap(calendar.date(byAdding: .day, value: 6, to: monday))
        let exit = try XCTUnwrap(calendar.date(bySettingHour: 6, minute: 0, second: 0, of: sunday))
        let nextMonday = try XCTUnwrap(calendar.date(byAdding: .day, value: 7, to: monday))
        let now = try XCTUnwrap(calendar.date(bySettingHour: 12, minute: 0, second: 0, of: nextMonday))
        let contracts = try XCTUnwrap(SalaryEmploymentContractPeriodResolverV2.resolve(
            companyId: "company", periodStartEpochDay: start, periodEndEpochDay: end, sourceReliable: true,
            snapshots: [.init(versionId: "c1", sourceId: "contract-test", effectiveFromEpochDay: start,
                effectiveToEpochDay: nil, contract: ContractV2(id: "c1", employerId: "company", type: .fullTime,
                    contractualWeeklyMinutes: 2100, grossHourlyRate: 10, hireDateEpochDay: 0), checkedAtMs: 1, note: nil)]))
        let rules = SalaryConventionCoverageV2(companyId: "company", idcc: "0292",
            periodStartEpochDay: start, periodEndEpochDay: end, segments: [.init(startEpochDay: start,
                endEpochDay: end, snapshot: .init(idcc: "0292", versionId: "r1", sourceId: "rule-test",
                    effectiveFromEpochDay: start, effectiveToEpochDay: end,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 2100,
                        overtimeTiers: [OvertimeTierV2(fromMinutes: 2100, toMinutes: nil, multiplier: 1.25)],
                        nightMultiplier: 1.25), checkedAtMs: 1, note: nil))],
            sourceReliable: true, fullyCovered: true, warnings: [])
        let source = SalarySegmentedPayrollSessionSourceV2(employerId: "company",
            work: .init(sessions: [.init(id: "s", entry: sunday, exit: exit, employerId: "company", pauses: [])], reliable: true),
            sourceId: "confirmed-snapshot", exhaustive: true, coveredStartEpochDay: start,
            coveredEndEpochDay: end, checkedAt: now, timeZoneId: "Europe/Paris")
        let premiums: [SalarySegmentedPayrollPremiumEvidenceV2] = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts, rules: rules).slices.map {
                .init(slice: $0, sourceId: "rule-snapshot", reliable: true,
                      nightRule: NightPremiumRuleV2(startMinute: 150, endMinute: 360, multiplier: 1.25),
                      holidayScope: .init(jurisdiction: .commonFrance, complete: true, postalCode: nil, warning: nil))
            }
        let proof = SalarySegmentedPayrollSessionEvidenceBuilderV2.build(
            contracts: contracts, rules: rules, source: source, premiums: premiums, now: now)
        XCTAssertFalse(proof.reliable)
        XCTAssertTrue(proof.slices.isEmpty)
        XCTAssertTrue(proof.warnings.contains(SalarySegmentedPayrollSessionEvidenceBuilderV2.calendarWarning))
        let variable = SalarySegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts: contracts, rules: rules, source: source, premiums: premiums, now: now)
        XCTAssertFalse(variable.reliable)
        XCTAssertTrue(variable.pieces.isEmpty)
        XCTAssertTrue(Set(proof.warnings).isSubset(of: Set(variable.warnings)))
    }
}
