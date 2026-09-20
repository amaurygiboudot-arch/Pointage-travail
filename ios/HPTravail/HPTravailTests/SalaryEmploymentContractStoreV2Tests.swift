import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractStoreV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "SalaryEmploymentContractStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    private func confirmCompany(_ id: String = "company-a", siret: String = "12345678901234") {
        XCTAssertTrue(
            SalaryCompanyStoreV2.createOrUpdate(
                SalaryCompanyV2(id: id, name: "Entreprise \(id)", siret: siret),
                defaults: defaults
            )
        )
    }

    private func hourlyContract(
        type: ContractTypeV2 = .fullTime,
        companyId: String = "company-a",
        weeklyMinutes: Int = 35 * 60,
        rate: Double = 14.25
    ) -> ContractV2 {
        ContractV2(
            id: "contract-\(companyId)",
            employerId: companyId,
            type: type,
            contractualWeeklyMinutes: weeklyMinutes,
            grossHourlyRate: rate,
            hireDateEpochDay: 20_000,
            payrollCutoffDay: 25
        )
    }

    func testMissingStoreIsReliableAndEmpty() {
        let result = SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.contracts.isEmpty)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testAllHourlyContractFamiliesRoundTripWithoutProfessionAssumption() {
        let types: [ContractTypeV2] = [.fullTime, .partTime, .forfait, .other]
        for (index, type) in types.enumerated() {
            let companyId = "company-\(index)"
            confirmCompany(companyId, siret: "\(index + 1)2345678901234")
            let contract = hourlyContract(type: type, companyId: companyId, weeklyMinutes: (20 + index) * 60)

            XCTAssertTrue(SalaryEmploymentContractStoreV2.save(contract, defaults: defaults))
            let stored = SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults)
            XCTAssertEqual(
                SalaryEmploymentContractStoreV2.confirmedContract(stored, companyId: companyId, defaults: defaults),
                contract
            )
        }
    }

    func testForfaitHoursRoundTripUsesMonthlyGrossAndExplicitPeriod() {
        confirmCompany()
        let contract = ContractV2(
            id: "contract-company-a",
            employerId: "company-a",
            type: .forfaitHours,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: 20_000,
            forfaitHoursPeriod: .year,
            forfaitHours: 1_607,
            monthlyGrossSalary: 3_200
        )

        XCTAssertTrue(SalaryEmploymentContractStoreV2.save(contract, defaults: defaults))
        let stored = SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults)
        XCTAssertEqual(
            SalaryEmploymentContractStoreV2.confirmedContract(stored, companyId: "company-a", defaults: defaults),
            contract
        )
    }

    func testForfaitDaysRoundTripUsesMonthlyGrossAndAnnualDays() {
        confirmCompany()
        let contract = ContractV2(
            id: "contract-company-a",
            employerId: "company-a",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: 20_000,
            forfaitAnnualDays: 215,
            monthlyGrossSalary: 4_000
        )

        XCTAssertTrue(SalaryEmploymentContractStoreV2.save(contract, defaults: defaults))
        XCTAssertTrue(SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults).reliable)
    }

    func testUnknownCompanyCannotReceiveConfirmedContract() {
        let contract = hourlyContract(companyId: "missing-company")
        XCTAssertFalse(SalaryEmploymentContractStoreV2.save(contract, defaults: defaults))
    }

    func testInvalidHourlyValuesAndContradictoryForfaitFieldsAreRejected() {
        confirmCompany()
        let invalidRate = hourlyContract(rate: .nan)
        XCTAssertFalse(SalaryEmploymentContractStoreV2.save(invalidRate, defaults: defaults))

        let contradictory = ContractV2(
            id: "contract-company-a",
            employerId: "company-a",
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 14,
            hireDateEpochDay: 20_000,
            forfaitAnnualDays: 218
        )
        XCTAssertFalse(SalaryEmploymentContractStoreV2.save(contradictory, defaults: defaults))
    }

    func testInvalidForfaitValuesAreRejected() {
        confirmCompany()
        let invalidHours = ContractV2(
            id: "contract-company-a",
            employerId: "company-a",
            type: .forfaitHours,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitHoursPeriod: .year,
            forfaitHours: .infinity,
            monthlyGrossSalary: 3_000
        )
        XCTAssertFalse(SalaryEmploymentContractStoreV2.save(invalidHours, defaults: defaults))

        let invalidDays = ContractV2(
            id: "contract-company-a",
            employerId: "company-a",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitAnnualDays: 219,
            monthlyGrossSalary: 3_000
        )
        XCTAssertFalse(SalaryEmploymentContractStoreV2.save(invalidDays, defaults: defaults))
    }

    func testOneCurrentContractPerCompanyIsReplacedAtomically() {
        confirmCompany()
        XCTAssertTrue(SalaryEmploymentContractStoreV2.save(hourlyContract(), defaults: defaults))
        let replacement = hourlyContract(type: .partTime, weeklyMinutes: 28 * 60, rate: 15)
        XCTAssertTrue(SalaryEmploymentContractStoreV2.save(replacement, defaults: defaults))

        let stored = SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults)
        XCTAssertEqual(stored.contracts.count, 1)
        XCTAssertEqual(stored.contracts.first, replacement)
    }

    func testCorruptPrimaryIsRestoredFromLastKnownGoodCopy() {
        confirmCompany()
        let contract = hourlyContract()
        XCTAssertTrue(SalaryEmploymentContractStoreV2.save(contract, defaults: defaults))

        defaults.set("{corrompu", forKey: "salary_employment_contracts_v2.contracts")
        let restored = SalaryEmploymentContractStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(restored.reliable)
        XCTAssertTrue(restored.repairedFromBackup)
        XCTAssertEqual(restored.contracts, [contract])
        XCTAssertTrue(restored.warnings.contains(SalaryEmploymentContractStoreV2.repairedWarning))
    }

    func testMalformedJsonIsNeverAConfirmedEmptyContractList() {
        let decoded = SalaryEmploymentContractStoreV2.decodeContracts("not-json")
        XCTAssertFalse(decoded.reliable)
        XCTAssertTrue(decoded.contracts.isEmpty)
    }
}
