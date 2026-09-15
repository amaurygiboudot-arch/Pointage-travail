import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class FullTimeStructuralOvertimeV2Tests: XCTestCase {
    private let legalTiers = [
        OvertimeTierV2(fromMinutes: 35 * 60, toMinutes: 43 * 60, multiplier: 1.25),
        OvertimeTierV2(fromMinutes: 43 * 60, toMinutes: nil, multiplier: 1.50)
    ]

    private func fullTimeContract(hours: Int = 39) -> ContractV2 {
        ContractV2(
            id: "full-time",
            employerId: "company-a",
            type: .fullTime,
            contractualWeeklyMinutes: hours * 60,
            grossHourlyRate: 10,
            hireDateEpochDay: nil
        )
    }

    func testContract39hIncludesFourStructuralOvertimeHoursInMonthlyBase() {
        let result = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes: 39 * 60,
            regularWeeklyLimit: 35 * 60,
            paidWeeks: [],
            grossHourlyRate: 10,
            overtimeTiers: legalTiers
        )

        XCTAssertEqual(result.monthlyRegularMinutes / 60.0 * 10.0, 1516.6667, accuracy: 0.01)
        XCTAssertEqual(result.monthlyStructuralOvertimeMinutes, 1040.0, accuracy: 0.01)
        XCTAssertEqual(result.structuralOvertimeGross, 216.6667, accuracy: 0.01)
        XCTAssertEqual(result.monthlyBaseGross, 1733.3334, accuracy: 0.01)
    }

    func testOnlyHoursAbove39hAreAddedAgainAsVariableOvertime() {
        let result = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes: 39 * 60,
            regularWeeklyLimit: 35 * 60,
            paidWeeks: [41 * 60],
            grossHourlyRate: 10,
            overtimeTiers: legalTiers
        )

        XCTAssertEqual(result.variableOvertimeGross, 25.0, accuracy: 0.001)
    }

    func testContract35hKeepsAllOvertimeVariable() {
        let result = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes: 35 * 60,
            regularWeeklyLimit: 35 * 60,
            paidWeeks: [39 * 60],
            grossHourlyRate: 10,
            overtimeTiers: legalTiers
        )

        XCTAssertEqual(result.monthlyBaseGross, 1516.6667, accuracy: 0.01)
        XCTAssertEqual(result.variableOvertimeGross, 50.0, accuracy: 0.001)
    }

    func testMissingTiersUseTenPercentOnlyAsAProvisionalFloor() {
        let result = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes: 39 * 60,
            regularWeeklyLimit: 35 * 60,
            paidWeeks: [],
            grossHourlyRate: 10,
            overtimeTiers: []
        )

        XCTAssertEqual(result.structuralOvertimeGross, 190.6667, accuracy: 0.01)
        XCTAssertTrue(result.provisionalRateUsed)
        XCTAssertTrue(result.warnings.contains { $0.contains("plancher de +10 %") })
        XCTAssertTrue(result.warnings.contains { $0.contains("n'est pas le barème supplétif de +25 % puis +50 %") })
    }

    func testPayrollEngine39hUsesStructuralBaseAndOnlyAddsHoursAboveContract() throws {
        let result = try PayrollEngineV2.calculate(
            contract: fullTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 41 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60, overtimeTiers: legalTiers)
        )

        XCTAssertEqual(result.regularGross, 1516.6667, accuracy: 0.01)
        XCTAssertEqual(result.overtimeGross, 241.6667, accuracy: 0.01)
        XCTAssertEqual(result.grossEstimate, 1758.3334, accuracy: 0.01)
        XCTAssertTrue(result.grossReliable)
    }

    func testPayrollEngineMissingFullTimeTierKeepsEstimateButFailsClosed() throws {
        let result = try PayrollEngineV2.calculate(
            contract: fullTimeContract(),
            weeks: [],
            rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
        )

        XCTAssertEqual(result.overtimeGross, 190.6667, accuracy: 0.01)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("plancher de +10 %") })
    }

    func testPayrollEngineWithoutConfirmedRegularReferenceFailsClosed() throws {
        let result = try PayrollEngineV2.calculate(
            contract: fullTimeContract(),
            weeks: [],
            rules: PayrollRulesV2()
        )

        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("seuil hebdomadaire régulier non confirmé") })
    }
}
