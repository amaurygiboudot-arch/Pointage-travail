import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionSeniorityInputStoreV2Tests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suite = "SalaryConventionSeniorityInputStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        defaults = nil
        suite = nil
        super.tearDown()
    }

    private func date(_ y: Int, _ m: Int, _ d: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: y, month: m, day: d)!
    }

    func testMissingInputsRemainUnknownAndReadable() {
        let result = SalaryConventionSeniorityInputStoreV2.read(
            companyId: "company",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.record)
    }

    func testConfirmedSeniorityDateIsDistinctFromHireDateAndPersists() {
        XCTAssertTrue(
            SalaryConventionSeniorityInputStoreV2.confirmSeniorityDate(
                companyId: " company ",
                date: date(2020, 6, 15),
                source: "user-confirmed",
                checkedAtMs: 100,
                defaults: defaults
            )
        )
        let result = SalaryConventionSeniorityInputStoreV2.read(
            companyId: "company",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.record?.seniorityDate, date(2020, 6, 15))
        XCTAssertEqual(result.record?.seniorityDateConfirmed, true)
        XCTAssertFalse(result.record?.monthlySupplementConfirmed ?? true)
        XCTAssertNil(result.record?.monthlySupplement)
    }

    func testExplicitZeroSupplementIsPreservedAsConfirmation() {
        XCTAssertTrue(
            SalaryConventionSeniorityInputStoreV2.confirmMonthlySupplement(
                companyId: "company",
                amount: 0,
                source: "user-confirmed",
                checkedAtMs: 101,
                defaults: defaults
            )
        )
        let result = SalaryConventionSeniorityInputStoreV2.read(
            companyId: "company",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.record?.monthlySupplement, 0)
        XCTAssertEqual(result.record?.monthlySupplementConfirmed, true)
    }

    func testNegativeSupplementIsRejected() {
        XCTAssertFalse(
            SalaryConventionSeniorityInputStoreV2.confirmMonthlySupplement(
                companyId: "company",
                amount: -1,
                source: "user-confirmed",
                checkedAtMs: 101,
                defaults: defaults
            )
        )
    }

    func testCorruptStorageFailsClosed() {
        defaults.set(
            "{not-json",
            forKey: "salary_convention_seniority_input_v2.records"
        )
        let result = SalaryConventionSeniorityInputStoreV2.read(
            companyId: "company",
            defaults: defaults
        )
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.record)
        XCTAssertTrue(result.warnings.contains(SalaryConventionSeniorityInputStoreV2.storageWarning))
    }
}
