import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractVersionInputV2Tests: XCTestCase {
    func testEffectiveDateDistinctFromHireDateIsAccepted() {
        let input = SalaryEmploymentContractVersionInputV2(
            contract: hourlyContract(hireDate: 100),
            effectiveFromEpochDay: 250,
            sourceId: "avenant-confirmed",
            checkedAtMs: 1,
            note: nil
        )

        let result = SalaryEmploymentContractVersionInputValidatorV2.validate(input)

        XCTAssertTrue(result.ready)
        XCTAssertTrue(result.warnings.isEmpty)
        XCTAssertEqual(input.contract.hireDateEpochDay, 100)
        XCTAssertEqual(input.effectiveFromEpochDay, 250)
    }

    func testEffectiveDateBeforeHireDateIsRejectedWithoutCorrection() {
        let input = SalaryEmploymentContractVersionInputV2(
            contract: hourlyContract(hireDate: 100),
            effectiveFromEpochDay: 99,
            sourceId: "user-confirmed",
            checkedAtMs: 1,
            note: nil
        )

        let result = SalaryEmploymentContractVersionInputValidatorV2.validate(input)

        XCTAssertFalse(result.ready)
        XCTAssertEqual(
            result.warnings,
            [SalaryEmploymentContractVersionInputValidatorV2.effectBeforeHireWarning]
        )
        XCTAssertEqual(input.effectiveFromEpochDay, 99)
    }

    func testMissingHireDateDoesNotInventOne() {
        let input = SalaryEmploymentContractVersionInputV2(
            contract: hourlyContract(hireDate: nil),
            effectiveFromEpochDay: 500,
            sourceId: "user-confirmed",
            checkedAtMs: 1,
            note: nil
        )

        let result = SalaryEmploymentContractVersionInputValidatorV2.validate(input)

        XCTAssertTrue(result.ready)
        XCTAssertNil(input.contract.hireDateEpochDay)
        XCTAssertEqual(input.effectiveFromEpochDay, 500)
    }

    func testLegacyGenericForfaitIsRejected() {
        let contract = ContractV2(
            id: "contract-a",
            employerId: "company-a",
            type: .forfait,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 14.0,
            hireDateEpochDay: 100
        )
        let result = SalaryEmploymentContractVersionInputValidatorV2.validate(
            SalaryEmploymentContractVersionInputV2(
                contract: contract,
                effectiveFromEpochDay: 100,
                sourceId: "user-confirmed",
                checkedAtMs: 1,
                note: nil
            )
        )

        XCTAssertFalse(result.ready)
        XCTAssertTrue(result.warnings.contains(SalaryEmploymentContractVersionInputValidatorV2.legacyForfaitWarning))
    }

    func testForfaitDaysAbove218IsRejectedAsContractContent() {
        let contract = ContractV2(
            id: "contract-a",
            employerId: "company-a",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: 100,
            forfaitHoursPeriod: nil,
            forfaitHours: nil,
            forfaitAnnualDays: 219,
            monthlyGrossSalary: 3_000
        )
        let result = SalaryEmploymentContractVersionInputValidatorV2.validate(
            SalaryEmploymentContractVersionInputV2(
                contract: contract,
                effectiveFromEpochDay: 100,
                sourceId: "user-confirmed",
                checkedAtMs: 1,
                note: nil
            )
        )

        XCTAssertFalse(result.ready)
        XCTAssertTrue(result.warnings.contains(SalaryEmploymentContractVersionInputValidatorV2.invalidTermsWarning))
    }

    private func hourlyContract(hireDate: Int64?) -> ContractV2 {
        ContractV2(
            id: "contract-a",
            employerId: "company-a",
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 14.0,
            hireDateEpochDay: hireDate
        )
    }
}
