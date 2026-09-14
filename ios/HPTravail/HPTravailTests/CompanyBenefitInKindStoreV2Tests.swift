import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class CompanyBenefitInKindStoreV2Tests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!
    private let month = YearMonthV2(year: 2026, month: 9)!
    private let companyId = "company-a"

    override func setUp() {
        super.setUp()
        suiteName = "CompanyBenefitInKindStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        XCTAssertTrue(
            SalaryCompanyStoreV2.createOrUpdate(
                SalaryCompanyV2(
                    id: companyId,
                    name: "Entreprise A",
                    siret: "12345678901234"
                ),
                defaults: defaults
            )
        )
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func monthlyRecord(value: Double = 200) -> CompanyBenefitInKindContractV2.Record {
        .init(
            id: "vehicle",
            label: "Véhicule",
            grossValue: value,
            kind: .monthly,
            effectiveFrom: month,
            effectiveTo: nil,
            paymentMonth: nil
        )
    }

    func testUnconfirmedEmptyListNeverBecomesReliableZero() {
        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: companyId,
            period: month,
            defaults: defaults
        )

        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
        XCTAssertFalse(snapshot.reliable)
    }

    func testExplicitMonthConfirmationMakesEmptyListReliableZero() {
        XCTAssertTrue(
            CompanyBenefitInKindStoreV2.confirmMonth(
                companyId: companyId,
                period: month,
                source: "Bulletin du mois",
                defaults: defaults
            )
        )

        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: companyId,
            period: month,
            defaults: defaults
        )

        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
    }

    func testConfirmedBenefitIsResolvedForExactMonth() {
        XCTAssertTrue(
            CompanyBenefitInKindStoreV2.save(
                companyId: companyId,
                record: monthlyRecord(),
                defaults: defaults
            )
        )
        XCTAssertTrue(
            CompanyBenefitInKindStoreV2.confirmMonth(
                companyId: companyId,
                period: month,
                source: "Bulletin du mois",
                defaults: defaults
            )
        )

        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: companyId,
            period: month,
            defaults: defaults
        )

        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 200, accuracy: 0.001)
    }

    func testRecordMutationInvalidatesPreviousMonthConfirmation() {
        XCTAssertTrue(
            CompanyBenefitInKindStoreV2.confirmMonth(
                companyId: companyId,
                period: month,
                source: "Bulletin du mois",
                defaults: defaults
            )
        )
        XCTAssertTrue(
            CompanyBenefitInKindStoreV2.save(
                companyId: companyId,
                record: monthlyRecord(),
                defaults: defaults
            )
        )

        let coverage = CompanyBenefitInKindStoreV2.monthCoverage(
            companyId: companyId,
            period: month,
            defaults: defaults
        )
        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: companyId,
            period: month,
            defaults: defaults
        )

        XCTAssertFalse(coverage.confirmed)
        XCTAssertTrue(coverage.storageReliable)
        XCTAssertFalse(snapshot.reliable)
    }

    func testMissingCompanyBlocksReadAndMutation() {
        XCTAssertFalse(
            CompanyBenefitInKindStoreV2.save(
                companyId: "missing",
                record: monthlyRecord(),
                defaults: defaults
            )
        )
        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: "missing",
            period: month,
            defaults: defaults
        )
        XCTAssertFalse(snapshot.reliable)
    }

    func testCorruptRecordsStayBlockedEvenWhenCoverageLooksConfirmed() {
        defaults.set(
            "{corrompu",
            forKey: "salary_company_company-a.benefits_in_kind_v2"
        )
        defaults.set(
            "[{\"period\":\"2026-09\",\"source\":\"Bulletin\"}]",
            forKey: "salary_company_company-a.benefits_in_kind_month_coverage_v2"
        )

        let snapshot = CompanyBenefitInKindStoreV2.resolve(
            companyId: companyId,
            period: month,
            defaults: defaults
        )

        XCTAssertFalse(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
    }
}
