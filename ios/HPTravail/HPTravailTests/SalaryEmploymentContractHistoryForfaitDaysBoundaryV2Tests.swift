import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractHistoryForfaitDaysBoundaryV2Tests: XCTestCase {
    func test218DaysIsHistorisableBut219IsRejected() {
        XCTAssertNotNil(SalaryEmploymentContractHistoryV2([snapshot(days: 218)]))
        XCTAssertNil(SalaryEmploymentContractHistoryV2([snapshot(days: 219)]))
    }

    private func snapshot(days: Double) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: "days-\(days)",
            sourceId: "user-confirmed",
            effectiveFromEpochDay: 100,
            effectiveToEpochDay: nil,
            contract: ContractV2(
                id: "contract-days-\(days)",
                employerId: "company-a",
                type: .forfaitDays,
                contractualWeeklyMinutes: nil,
                grossHourlyRate: nil,
                hireDateEpochDay: nil,
                forfaitHoursPeriod: nil,
                forfaitHours: nil,
                forfaitAnnualDays: days,
                monthlyGrossSalary: 4200
            ),
            checkedAtMs: 1,
            note: nil
        )
    }
}
