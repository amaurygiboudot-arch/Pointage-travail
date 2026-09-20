import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class ActiveSalaryCompanySelectionV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "ActiveSalaryCompanySelectionV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    private func company(_ id: String, siret: String) -> SalaryCompanyV2 {
        SalaryCompanyV2(id: id, name: "Entreprise \(id)", siret: siret)
    }

    @discardableResult
    private func saveCompany(_ id: String, siret: String) -> Bool {
        SalaryCompanyStoreV2.createOrUpdate(
            company(id, siret: siret),
            defaults: defaults
        )
    }

    func testNoConfirmedCompanyKeepsPointageUnassignedWithoutAmbiguity() {
        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.activeCompany)
        XCTAssertFalse(result.requiresExplicitSelection)
        XCTAssertTrue(result.companies.isEmpty)
    }

    func testSingleConfirmedCompanyIsDeterministicWithoutInventingAnotherChoice() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))

        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.activeCompany?.id, "a")
        XCTAssertFalse(result.requiresExplicitSelection)
    }

    func testMultipleCompaniesRequireExplicitSelection() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))
        XCTAssertTrue(saveCompany("b", siret: "22345678901234"))

        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.activeCompany)
        XCTAssertTrue(result.requiresExplicitSelection)
        XCTAssertTrue(result.warnings.contains { $0.contains("plusieurs entreprises") })
    }

    func testExplicitSelectionAmongMultipleCompaniesPersists() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))
        XCTAssertTrue(saveCompany("b", siret: "22345678901234"))

        XCTAssertTrue(ActiveSalaryCompanySelectionV2.select(companyId: "b", defaults: defaults))
        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertEqual(result.activeCompany?.id, "b")
        XCTAssertFalse(result.requiresExplicitSelection)
        XCTAssertEqual(defaults.string(forKey: ActiveSalaryCompanySelectionV2.storageKey), "b")
    }

    func testUnknownCompanyCannotBecomeActive() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))

        XCTAssertFalse(ActiveSalaryCompanySelectionV2.select(companyId: "missing", defaults: defaults))
        XCTAssertEqual(ActiveSalaryCompanySelectionV2.resolve(defaults: defaults).activeCompany?.id, "a")
    }

    func testStaleSelectionWithMultipleCompaniesDoesNotFallBackToFirstCompany() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))
        XCTAssertTrue(saveCompany("b", siret: "22345678901234"))
        defaults.set("removed", forKey: ActiveSalaryCompanySelectionV2.storageKey)

        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertNil(result.activeCompany)
        XCTAssertTrue(result.requiresExplicitSelection)
        XCTAssertTrue(result.warnings.contains { $0.contains("n'existe plus") })
    }

    func testCorruptCompanyStorageIsNotTreatedAsNoCompany() {
        defaults.set("{corrompu", forKey: "salary_companies_v2.companies")

        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.activeCompany)
        XCTAssertFalse(result.requiresExplicitSelection)
        XCTAssertFalse(result.warnings.isEmpty)
    }

    func testClearRemovesOnlyFutureActiveSelection() {
        XCTAssertTrue(saveCompany("a", siret: "12345678901234"))
        XCTAssertTrue(saveCompany("b", siret: "22345678901234"))
        XCTAssertTrue(ActiveSalaryCompanySelectionV2.select(companyId: "a", defaults: defaults))

        ActiveSalaryCompanySelectionV2.clear(defaults: defaults)
        let result = ActiveSalaryCompanySelectionV2.resolve(defaults: defaults)

        XCTAssertNil(defaults.string(forKey: ActiveSalaryCompanySelectionV2.storageKey))
        XCTAssertNil(result.activeCompany)
        XCTAssertTrue(result.requiresExplicitSelection)
    }
}
