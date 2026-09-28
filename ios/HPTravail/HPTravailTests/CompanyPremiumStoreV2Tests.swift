import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class CompanyPremiumStoreV2Tests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!
    private let september = YearMonthV2(year: 2026, month: 9)!
    private let october = YearMonthV2(year: 2026, month: 10)!
    private let companyId = "company-a"

    override func setUp() {
        super.setUp()
        suiteName = "CompanyPremiumStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        XCTAssertTrue(
            SalaryCompanyStoreV2.createOrUpdate(
                SalaryCompanyV2(id: companyId, name: "Entreprise A", siret: "12345678901234"),
                defaults: defaults
            )
        )
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }
    private func monthly(_ amount: Double = 100) -> CompanyPremiumContractV2.Record {
        .init(
            id: "monthly",
            label: "Prime mensuelle",
            grossAmount: amount,
            kind: .monthly,
            effectiveFrom: september,
            effectiveTo: nil,
            paymentMonth: nil
        )
    }

    private func oneOff(_ amount: Double = 250) -> CompanyPremiumContractV2.Record {
        .init(
            id: "one-off",
            label: "Prime ponctuelle",
            grossAmount: amount,
            kind: .oneOff,
            effectiveFrom: nil,
            effectiveTo: nil,
            paymentMonth: october
        )
    }

    func testUnconfirmedEmptyListNeverBecomesReliableZero() {
        let snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId,
            period: september,
            defaults: defaults
        )
        XCTAssertFalse(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
    }
    func testExplicitMonthConfirmationMakesEmptyListReliableZero() {
        XCTAssertTrue(
            CompanyPremiumStoreV2.confirmMonth(
                companyId: companyId,
                period: september,
                source: "Bulletin du mois",
                defaults: defaults
            )
        )
        let snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId,
            period: september,
            defaults: defaults
        )
        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
    }

    func testMonthlyAndOneOffPremiumsApplyOnlyToTheirPeriods() {
        XCTAssertTrue(CompanyPremiumStoreV2.save(companyId: companyId, record: monthly(), defaults: defaults))
        XCTAssertTrue(CompanyPremiumStoreV2.save(companyId: companyId, record: oneOff(), defaults: defaults))
        XCTAssertTrue(CompanyPremiumStoreV2.confirmMonth(
            companyId: companyId, period: september, source: "Septembre", defaults: defaults
        ))
        var snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId, period: september, defaults: defaults
        )
        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 100, accuracy: 0.001)
        XCTAssertTrue(CompanyPremiumStoreV2.confirmMonth(
            companyId: companyId, period: october, source: "Octobre", defaults: defaults
        ))
        snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId, period: october, defaults: defaults
        )
        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 350, accuracy: 0.001)
    }

    func testMutationInvalidatesPreviousConfirmations() {
        XCTAssertTrue(CompanyPremiumStoreV2.confirmMonth(
            companyId: companyId, period: september, source: "Avant mutation", defaults: defaults
        ))
        XCTAssertTrue(CompanyPremiumStoreV2.save(companyId: companyId, record: monthly(), defaults: defaults))

        let coverage = CompanyPremiumStoreV2.monthCoverage(
            companyId: companyId, period: september, defaults: defaults
        )
        let snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId, period: september, defaults: defaults
        )
        XCTAssertFalse(coverage.confirmed)
        XCTAssertTrue(coverage.storageReliable)
        XCTAssertFalse(snapshot.reliable)
    }
    func testMissingCompanyAndCorruptStorageStayBlocked() {
        XCTAssertFalse(CompanyPremiumStoreV2.save(
            companyId: "missing", record: monthly(), defaults: defaults
        ))
        XCTAssertFalse(CompanyPremiumStoreV2.resolve(
            companyId: "missing", period: september, defaults: defaults
        ).reliable)

        defaults.set("{corrompu", forKey: "salary_company_company-a.company_premiums_v2")
        defaults.set(
            "[{\"period\":\"2026-09\",\"source\":\"Bulletin\"}]",
            forKey: "salary_company_company-a.company_premiums_month_coverage_v2"
        )
        let snapshot = CompanyPremiumStoreV2.resolve(
            companyId: companyId, period: september, defaults: defaults
        )
        XCTAssertFalse(snapshot.reliable)
        XCTAssertEqual(snapshot.totalGross, 0, accuracy: 0.001)
    }
}
