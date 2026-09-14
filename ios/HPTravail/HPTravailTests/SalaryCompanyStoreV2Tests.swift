import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryCompanyStoreV2Tests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "SalaryCompanyStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func company(
        id: String = "company-a",
        siret: String = "12345678901234"
    ) -> SalaryCompanyV2 {
        SalaryCompanyV2(
            id: id,
            name: "Entreprise A",
            siret: siret,
            address: "Vendée",
            conventionName: "Convention test",
            idcc: "0000"
        )
    }

    func testMissingStoreMeansReliableEmptyCompanyList() {
        let result = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.companies.isEmpty)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testInvalidSiretIsRejectedBeforePersistence() {
        XCTAssertFalse(
            SalaryCompanyStoreV2.createOrUpdate(
                company(siret: "123"),
                defaults: defaults
            )
        )
        XCTAssertTrue(SalaryCompanyStoreV2.readConfirmed(defaults: defaults).companies.isEmpty)
    }

    func testExistingCompanyCanBeConfirmedAfterVerifiedWrite() {
        let expected = company()
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(expected, defaults: defaults))

        let stored = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(stored.reliable)
        XCTAssertEqual(SalaryCompanyStoreV2.confirmedCompany(stored, companyId: expected.id), expected)
    }

    func testUpdateCannotRecreateMissingCompany() {
        XCTAssertFalse(SalaryCompanyStoreV2.upsertExisting(company(), defaults: defaults))
        XCTAssertTrue(SalaryCompanyStoreV2.readConfirmed(defaults: defaults).companies.isEmpty)
    }

    func testCorruptPrimaryIsRestoredFromLastKnownGoodCopy() {
        let expected = company()
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(expected, defaults: defaults))
        defaults.set("{corrompu", forKey: "salary_companies_v2.companies")

        let repaired = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(repaired.reliable)
        XCTAssertTrue(repaired.repairedFromBackup)
        XCTAssertEqual(repaired.companies, [expected])
        XCTAssertTrue(repaired.warnings.contains(SalaryCompanyStoreV2.repairedWarning))
    }

    func testCorruptPrimaryAndBackupNeverBecomeEmptyReliableStore() {
        defaults.set("{corrompu", forKey: "salary_companies_v2.companies")
        defaults.set("{corrompu", forKey: "salary_companies_v2.companies_last_known_good")

        let result = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.companies.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryCompanyStoreV2.storageWarning))
    }

    func testDuplicateCompanyIdsMakeDecodedStoreUnreliable() {
        let raw = """
        [
          {"id":"same","name":"A","siret":"","address":"","conventionName":"","idcc":""},
          {"id":"same","name":"B","siret":"","address":"","conventionName":"","idcc":""}
        ]
        """

        let result = SalaryCompanyStoreV2.decodeCompanies(raw)

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.companies.count, 2)
    }
}
