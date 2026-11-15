import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class PartTimeComplementaryHoursV2Tests: XCTestCase {
    private func partTimeContract(rate: Double = 10) -> ContractV2 {
        ContractV2(
            id: "part-time",
            employerId: "company-a",
            type: .partTime,
            contractualWeeklyMinutes: 20 * 60,
            grossHourlyRate: rate,
            hireDateEpochDay: nil
        )
    }

    func testConfirmedScheduleRequiresDatedCompleteContractMatchedProof() throws {
        func schedule(source: String = "official-test-source", minutes: Int = 1200,
                      end: Int64 = 200, tiers: [OvertimeTierV2]? = nil) -> ConfirmedComplementaryScheduleV2 {
            ConfirmedComplementaryScheduleV2(sourceId: source, contractualMinutes: minutes,
                effectiveFromEpochDay: 100, effectiveToEpochDay: end,
                tiers: tiers ?? [OvertimeTierV2(fromMinutes: 1200, toMinutes: 1260, multiplier: 1.2),
                                 OvertimeTierV2(fromMinutes: 1260, toMinutes: 1320, multiplier: 1.3)])
        }
        let proven = try PartTimeComplementaryHoursV2.calculateWeek(contractualMinutes: 1200,
            paidMinutes: 1320, grossHourlyRate: 10, confirmedSchedule: schedule(), referenceEpochDay: 150)
        XCTAssertEqual(proven.grossToAdd, 25, accuracy: 0.001)
        XCTAssertTrue(proven.confirmedScheduleUsed)
        for candidate in [schedule(source: " "), schedule(minutes: 1100), schedule(end: 149),
            schedule(tiers: [OvertimeTierV2(fromMinutes: 1210, toMinutes: 1320, multiplier: 1.2)]),
            schedule(tiers: [OvertimeTierV2(fromMinutes: 1200, toMinutes: 1300, multiplier: 1.2),
                             OvertimeTierV2(fromMinutes: 1250, toMinutes: 1320, multiplier: 1.3)])] {
            let fallback = try PartTimeComplementaryHoursV2.calculateWeek(contractualMinutes: 1200,
                paidMinutes: 1320, grossHourlyRate: 10, confirmedSchedule: candidate, referenceEpochDay: 150)
            XCTAssertFalse(fallback.confirmedScheduleUsed)
            XCTAssertEqual(fallback.grossToAdd, 22, accuracy: 0.001)
        }
    }

    func testNoComplementaryHoursKeepsGrossReliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 20 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60),
            evidence: .fullyConfirmed
        )

        XCTAssertEqual(result.complementaryMinutes, 0)
        XCTAssertTrue(result.grossReliable)
        XCTAssertEqual(result.regularGross, 20.0 * 52.0 / 12.0 * 10.0, accuracy: 0.001)
    }

    func testComplementaryFallbackIsCalculatedButMakesGrossUnreliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 22 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60),
            evidence: .fullyConfirmed
        )

        XCTAssertEqual(result.complementaryMinutes, 120)
        XCTAssertEqual(result.overtimeGross, 22.0, accuracy: 0.001)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("barème supplétif") })
    }

    func testSecondComplementaryTierUsesTwentyFivePercent() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 24 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60),
            evidence: .fullyConfirmed
        )

        XCTAssertEqual(result.complementaryMinutes, 240)
        XCTAssertEqual(result.overtimeGross, 47.0, accuracy: 0.001)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("dépasse 1/10") })
    }

    func testDirectComplementaryCalculatorRejectsNegativePaidMinutes() {
        XCTAssertThrowsError(
            try PartTimeComplementaryHoursV2.calculateWeek(
                contractualMinutes: 20 * 60,
                paidMinutes: -1,
                grossHourlyRate: 10
            )
        ) { error in
            XCTAssertEqual(error as? PayrollEngineErrorV2, .invalidPaidMinutes)
        }
    }

    func testPayrollReliabilityCannotBeRehabilitatedByConfirmedBenefits() throws {
        let payroll = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 22 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60),
            evidence: .fullyConfirmed
        )
        let benefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: [],
            totalGross: 0,
            reliable: true,
            warnings: []
        )

        let reference = SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: benefits,
            netBeforeIncomeTax: 800,
            netTaxable: 850
        )

        XCTAssertFalse(reference.grossReliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
    }
}
