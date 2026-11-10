import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

/// Synthetic local domains exercise the same adapter used by .standard in the app.
/// Firebase delivery, SwiftUI, OCR and UIKit still require the separate native checks.
final class SalaryAccountIsolationV2Tests: XCTestCase {
    private final class Identity { var scope: String? = "uid:A" }
    private var identity: Identity!
    private var suite = ""
    private var backing: UserDefaults!
    private let company = SalaryCompanyV2(id: "same-company", name: "Synthetic employer", siret: "12345678901234")
    private let month = YearMonthV2(year: 2026, month: 10)!
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    override func setUpWithError() throws {
        suite = "AGKGMG.salary-account-fix." + UUID().uuidString
        backing = try XCTUnwrap(UserDefaults(suiteName: suite))
        identity = Identity()
        let source = identity!
        SalaryAccountSessionV2.configure { source.scope }
    }
    override func tearDown() {
        backing.removePersistentDomain(forName: suite)
        SalaryAccountSessionV2.configure { nil }
        backing = nil
        identity = nil
        super.tearDown()
    }
    private func domain(_ scope: String? = "uid:A") -> UserDefaults {
        identity.scope = scope
        SalaryAccountSessionV2.authenticationChanged()
        return SalaryAccountStorageV2.guarded(backing: backing)
    }
    private func seedCompany(_ defaults: UserDefaults) {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(company, defaults: defaults))
    }
    private func observation(_ gross: Double) -> SalaryPayslipObservedStoreV2.Record {
        .init(companyId: company.id, period: month, amounts: ["socialGross": gross],
              excerpts: ["socialGross": "Synthetic private source"], confirmedAt: date)
    }
    private func contract() -> SalaryEmploymentContractSnapshotV2 {
        .init(versionId: "contract-v1", sourceId: "synthetic-A", effectiveFromEpochDay: 20_727, effectiveToEpochDay: 20_747,
              contract: ContractV2(id: "contract", employerId: company.id, type: .fullTime,
                  contractualWeeklyMinutes: 2100, grossHourlyRate: 16, hireDateEpochDay: 20_700),
              checkedAtMs: 1_790_000_000_000, note: nil)
    }
    func testSameCompanyAndMonthDoNotSharePASPremiumOrCompanyAcrossAccounts() {
        let a = domain(); seedCompany(a)
        let taxA = CompanyIncomeTaxRateStoreV2(defaults: a)
        XCTAssertTrue(taxA.confirm(companyId: company.id, ratePercent: 7.5, period: month, source: "A source"))
        let premium = CompanyPremiumContractV2.Record(id: "premium", label: "A premium", grossAmount: 123.45,
            kind: .monthly, effectiveFrom: month, effectiveTo: nil, paymentMonth: nil)
        XCTAssertTrue(CompanyPremiumStoreV2.save(companyId: company.id, record: premium, defaults: a))
        XCTAssertTrue(CompanyPremiumStoreV2.confirmMonth(companyId: company.id, period: month, source: "A", defaults: a))
        let b = domain("uid:B")
        XCTAssertTrue(SalaryCompanyStoreV2.readConfirmed(defaults: b).companies.isEmpty)
        seedCompany(b)
        let taxB = CompanyIncomeTaxRateStoreV2(defaults: b)
        XCTAssertNil(taxB.snapshot(companyId: company.id, for: month).ratePercent)
        XCTAssertFalse(CompanyPremiumStoreV2.resolve(companyId: company.id, period: month, defaults: b).reliable)
        XCTAssertTrue(taxB.confirm(companyId: company.id, ratePercent: 12, period: month, source: "B"))
        XCTAssertFalse(taxA.confirm(companyId: company.id, ratePercent: 99, period: month, source: "stale"))
        let returned = domain()
        XCTAssertEqual(CompanyIncomeTaxRateStoreV2(defaults: returned).snapshot(companyId: company.id, for: month).ratePercent, 7.5)
        XCTAssertEqual(CompanyPremiumStoreV2.resolve(companyId: company.id, period: month, defaults: returned).totalGross, 123.45)
        XCTAssertFalse(taxA.snapshot(companyId: company.id, for: month).reliable, "A→B→A never revives an old object")
    }
    func testObservedPayslipAndSicknessSourcesSurviveOtherAccountWritesAndDeletes() {
        let a = domain(); seedCompany(a)
        let observed = observation(2000)
        let sickness = SalaryConfirmedSicknessCashV2.Record(companyId: company.id, period: month,
            directEmployeeNetBeforeTax: 135.25, subrogatedEmployerNetBeforeTax: nil,
            source: "Synthetic A IJSS", confirmedAt: date)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.save(observed, confirmed: true, defaults: a))
        XCTAssertTrue(SalaryConfirmedSicknessCashV2.save(sickness, confirmed: true, defaults: a))
        let b = domain("uid:B"); seedCompany(b)
        XCTAssertNil(SalaryPayslipObservedStoreV2.read(companyId: company.id, period: month, defaults: b).record)
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: company.id, period: month, defaults: b).record)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.save(observation(2500), confirmed: true, defaults: b))
        SalaryConfirmedSicknessCashV2.remove(companyId: company.id, period: month, defaults: b)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.remove(companyId: company.id, period: month, defaults: b))
        let returned = domain()
        XCTAssertEqual(SalaryPayslipObservedStoreV2.read(companyId: company.id, period: month, defaults: returned).record, observed)
        XCTAssertEqual(SalaryConfirmedSicknessCashV2.read(companyId: company.id, period: month, defaults: returned).record, sickness)
        XCTAssertNil(SalaryPayslipObservedStoreV2.read(companyId: "other-company", period: month, defaults: returned).record)
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: company.id, period: YearMonthV2(year: 2026, month: 9)!, defaults: returned).record)
    }
    func testContractClassificationAndOwnerCheckedDatedRulesRemainSeparate() throws {
        let a = domain(); seedCompany(a)
        let snapshot = contract()
        XCTAssertTrue(SalaryEmploymentContractHistoryStoreV2.saveConfirmed(snapshot, defaults: a))
        let classification = ConventionClassificationV2(coefficient: 185, level: "private level")
        XCTAssertTrue(SalaryConventionClassificationStoreV2.save(companyId: company.id, value: classification, defaults: a))
        let owner = try XCTUnwrap(SalaryDatedWorkRuleOwnerAuthorizationV2.ownerForSelf(authenticatedUid: "A", employerId: company.id, contractVersionId: snapshot.versionId))
        let rule = SalaryDatedWorkRuleV2(id: "rule", owner: owner, topic: .timeAccounting,
            effectiveFromEpochDay: 20_727, effectiveToEpochDay: 20_748, sourceId: snapshot.sourceId,
            ruleReference: "contract:" + snapshot.versionId, checkedAtMs: snapshot.checkedAtMs, confirmation: .confirmed)
        XCTAssertTrue(SalaryDatedWorkRuleStoreV2.append(rule, owner: owner, authenticatedAccountId: "A", defaults: a))
        let b = domain("uid:B"); seedCompany(b)
        XCTAssertTrue(SalaryEmploymentContractHistoryStoreV2.readConfirmed(defaults: b).snapshots.isEmpty)
        XCTAssertEqual(SalaryConventionClassificationStoreV2.load(companyId: company.id, defaults: b), ConventionClassificationV2())
        XCTAssertFalse(SalaryDatedWorkRuleStoreV2.read(owner: owner, authenticatedAccountId: "B", defaults: b).reliable)
        // Supplying A's UID to the old explicit API cannot defeat the actual adapter owner.
        XCTAssertTrue(SalaryEmploymentContractHistoryStoreV2.saveConfirmed(snapshot, defaults: b))
        XCTAssertFalse(SalaryDatedWorkRuleStoreV2.read(owner: owner, authenticatedAccountId: "A", defaults: b).reliable)
        XCTAssertNil(b.dictionaryRepresentation()[SalaryDatedWorkRuleStoreV2.ownerStorageKey(owner)])
        let returned = domain()
        XCTAssertEqual(SalaryDatedWorkRuleStoreV2.read(owner: owner, authenticatedAccountId: "A", defaults: returned).records, [rule])
        XCTAssertEqual(SalaryConventionClassificationStoreV2.load(companyId: company.id, defaults: returned), classification)
    }
    func testIdenticalEmptyWorkSourceCannotBorrowAnotherAccountsCoverageAttestation() throws {
        let a = domain(); seedCompany(a)
        let start: Int64 = 20_727, end: Int64 = 20_733
        let checked = Date(timeIntervalSince1970: Double(end + 2) * 86_400)
        let work = SalaryWorkSessionSourceV2(sessions: [], reliable: true)
        let proof = try XCTUnwrap(SalaryPayrollCoverageStoreV2.saveConfirmed(work: work, employerId: company.id,
            coveredStartEpochDay: start, coveredEndEpochDay: end, checkedAt: checked,
            timeZoneId: "UTC", now: checked, defaults: a))
        let b = domain("uid:B"); seedCompany(b)
        let read = SalaryPayrollCoverageStoreV2.read(defaults: b)
        XCTAssertTrue(read.reliable)
        XCTAssertTrue(read.attestations.isEmpty)
        XCTAssertFalse(SalaryPayrollCoverageStoreV2.resolve(attestations: read.attestations, work: work,
            employerId: company.id, requestedStartEpochDay: start, requestedEndEpochDay: end,
            timeZoneId: "UTC", now: checked).exhaustive)
        XCTAssertEqual(SalaryPayrollCoverageStoreV2.read(defaults: domain()).attestations, [proof])
    }
    func testEveryPersonalFamilyAndBackupIsScopedButCommonRulesRemainShared() {
        let keys = ["salary_companies_v2.companies_last_known_good", "salary_employment_contracts_v2.contracts",
            "salary_employment_contract_history_v2.confirmed_timeline_envelope", "income_tax_rates_v2.same-company",
            "salary_company_same_company.company_premiums_v2", "salary_company_same_company.benefits_in_kind_v2",
            "employee_deductions_v2.same-company", "salary_absences_v2.confirmations.same-company",
            "salary_employee_social_profile_v2.same-company", "salary_convention_classification_v2.same-company",
            "salary_convention_seniority_input_v2.records", "salary_segmented_proration_v2.same-company",
            "salary_confirmed_sickness_cash_v2.same-company|2026-10", "salary_payslip_observed_v2.same-company|2026-10",
            "hp_travail_payroll_coverage_v2", "salary_payroll_source_knowledge_v2.proofs"]
        let a = domain()
        for key in keys { a.set("synthetic-private", forKey: key); XCTAssertNil(backing.object(forKey: key)) }
        let commonKey = "salary_convention_rules_v2.confirmed_snapshots"
        a.set("synthetic-public-rule", forKey: commonKey)
        let b = domain("uid:B")
        for key in keys { XCTAssertNil(b.object(forKey: key), key) }
        XCTAssertEqual(b.string(forKey: commonKey), "synthetic-public-rule")
        XCTAssertNil(b.dictionaryRepresentation()[SalaryAccountStorageV2.rootKey])
        XCTAssertTrue(b.dictionaryRepresentation().keys.allSatisfy { !keys.contains($0) })
        let guest = domain("guest")
        for key in keys { XCTAssertNil(guest.object(forKey: key), key) }
        let reopened = UserDefaults(suiteName: suite)!
        _ = domain()
        let recovered = SalaryAccountStorageV2.guarded(backing: reopened)
        for key in keys { XCTAssertEqual(recovered.string(forKey: key), "synthetic-private", key) }
    }
    func testUnavailableIdentityAndCapturedOperationsNeverBecomeReliableEmpty() {
        let a = domain(); seedCompany(a)
        let lease = SalaryAccountSessionV2.capture()
        let unknown = domain(nil)
        for d in [a, unknown] {
            XCTAssertFalse(SalaryCompanyStoreV2.readConfirmed(defaults: d).reliable)
            XCTAssertFalse(SalaryEmploymentContractHistoryStoreV2.readConfirmed(defaults: d).reliable)
            XCTAssertFalse(SalaryPayslipObservedStoreV2.read(companyId: company.id, period: month, defaults: d).reliable)
            XCTAssertFalse(SalaryConfirmedSicknessCashV2.read(companyId: company.id, period: month, defaults: d).reliable)
            XCTAssertFalse(SalaryPayrollCoverageStoreV2.read(defaults: d).reliable)
            XCTAssertFalse(SalaryPayrollSourceKnowledgeStoreV2.read(defaults: d).reliable)
            XCTAssertFalse(CompanyIncomeTaxRateStoreV2(defaults: d).snapshot(companyId: company.id, for: month).reliable)
            XCTAssertFalse(SalaryCompanyStoreV2.createOrUpdate(company, defaults: d))
        }
        _ = domain("uid:B")
        let operation = SalaryAccountSessionV2.pin(lease)
        XCTAssertFalse(SalaryAccountStorageV2.available(SalaryAccountStorageV2.currentDefaults))
        operation.close()
        XCTAssertFalse(SalaryAccountStorageV2.scoped(.standard) === UserDefaults.standard)
    }
    func testInvalidationAndFastDeliveredAccountRoundTripDoNotReviveLeases() {
        _ = domain()
        let first = SalaryAccountSessionV2.capture()
        SalaryAccountSessionV2.invalidateSession()
        XCTAssertFalse(first.isCurrent)
        let second = SalaryAccountSessionV2.capture()
        // Firebase has returned to A before queued UI work runs, but B's callback arrived.
        SalaryAccountSessionV2.authenticationChanged(deliveredScope: "guest")
        XCTAssertFalse(second.isCurrent)
        XCTAssertTrue(SalaryAccountSessionV2.capture().isCurrent)
    }
    func testLegacyRecoveryIsExplicitAtomicIdempotentAndPreservesOriginalBytes() throws {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(company, defaults: backing))
        let originals = backing.dictionaryRepresentation().filter { SalaryAccountStorageV2.isPersonalKey($0.key) }
        let a = domain()
        XCTAssertFalse(SalaryCompanyStoreV2.readConfirmed(defaults: a).reliable)
        let preview = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: backing))
        XCTAssertEqual(preview.entryCount, originals.count)
        XCTAssertThrowsError(try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: false, backing: backing))
        try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: backing)
        try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: backing)
        XCTAssertEqual(SalaryCompanyStoreV2.readConfirmed(defaults: a).companies, [company])
        for (key, value) in originals { XCTAssertEqual(backing.string(forKey: key), value as? String) }
        let b = domain("uid:B")
        XCTAssertTrue(SalaryCompanyStoreV2.readConfirmed(defaults: b).reliable)
        XCTAssertTrue(SalaryCompanyStoreV2.readConfirmed(defaults: b).companies.isEmpty)
        XCTAssertNil(try SalaryAccountStorageV2.recoveryPreview(backing: backing))
        XCTAssertThrowsError(try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: backing))
    }
    func testDecliningLegacyForBDoesNotPreventARecoveryAndChangedSourcesRejectPreview() throws {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(company, defaults: backing))
        _ = domain("uid:B")
        let b = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: backing))
        try SalaryAccountStorageV2.startWithoutLegacy(b, backing: backing)
        let aDomain = domain()
        let a = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: backing))
        backing.set("new legacy value", forKey: "income_tax_rates_v2.changed")
        XCTAssertThrowsError(try SalaryAccountStorageV2.recover(a, ownershipConfirmed: true, backing: backing))
        XCTAssertFalse(SalaryCompanyStoreV2.readConfirmed(defaults: aDomain).reliable)
        let updated = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: backing))
        try SalaryAccountStorageV2.recover(updated, ownershipConfirmed: true, backing: backing)
        XCTAssertEqual(SalaryCompanyStoreV2.readConfirmed(defaults: aDomain).companies, [company])
    }
    func testGuestCannotAttributeLegacyAndPopulatedAccountCannotBeOverwritten() throws {
        let a = domain(); seedCompany(a)
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(company, defaults: backing))
        let preview = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: backing))
        XCTAssertThrowsError(try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: backing))
        _ = domain("guest")
        XCTAssertThrowsError(try SalaryAccountStorageV2.recoveryPreview(backing: backing))
    }
    func testCorruptOrWrongOwnerEnvelopeFailsClosedWithoutErasingOriginal() throws {
        let a = domain(); seedCompany(a)
        let original = try XCTUnwrap(backing.data(forKey: SalaryAccountStorageV2.rootKey))
        var root = try XCTUnwrap(PropertyListSerialization.propertyList(from: original, format: nil) as? [String: Any])
        var accounts = try XCTUnwrap(root["accounts"] as? [String: Any])
        let key = SalaryAccountStorageV2.accountKey("uid:A")
        var entry = try XCTUnwrap(accounts[key] as? [String: Any]); entry["owner"] = "uid:B"
        accounts[key] = entry; root["accounts"] = accounts
        let corrupted = try PropertyListSerialization.data(fromPropertyList: root, format: .binary, options: 0)
        backing.set(corrupted, forKey: SalaryAccountStorageV2.rootKey)
        XCTAssertFalse(SalaryCompanyStoreV2.readConfirmed(defaults: a).reliable)
        XCTAssertFalse(SalaryCompanyStoreV2.createOrUpdate(company, defaults: a))
        XCTAssertEqual(backing.data(forKey: SalaryAccountStorageV2.rootKey), corrupted)
        backing.set(original, forKey: SalaryAccountStorageV2.rootKey)
        XCTAssertEqual(SalaryCompanyStoreV2.readConfirmed(defaults: a).companies, [company])
    }
    func testPrimitiveSettersAndDomainMethodsCannotWriteOutsideCapturedOwner() {
        let a = domain()
        let prefix = "salary_company_primitive."
        a.set(true, forKey: prefix + "bool"); a.set(7, forKey: prefix + "int")
        a.set(2.5, forKey: prefix + "double"); a.set(Float(3.5), forKey: prefix + "float")
        let url = URL(fileURLWithPath: "/synthetic/local-document")
        a.set(url, forKey: prefix + "url")
        XCTAssertEqual(a.url(forKey: prefix + "url"), url)
        XCTAssertTrue(a.bool(forKey: prefix + "bool")); XCTAssertEqual(a.integer(forKey: prefix + "int"), 7)
        XCTAssertEqual(a.double(forKey: prefix + "double"), 2.5)
        for key in ["bool", "int", "double", "float", "url"] { XCTAssertNil(backing.object(forKey: prefix + key)) }
        let b = domain("uid:B")
        a.set(99, forKey: prefix + "int")
        a.setPersistentDomain([prefix + "int": 99], forName: suite)
        a.removePersistentDomain(forName: suite)
        XCTAssertNil(b.object(forKey: prefix + "int"))
        XCTAssertEqual(domain().integer(forKey: prefix + "int"), 7)
    }
    private final class RejectingDefaults: UserDefaults {
        var reject = false
        override func set(_ value: Any?, forKey key: String) {
            if reject && key == SalaryAccountStorageV2.rootKey { return }
            super.set(value, forKey: key)
        }
    }
    func testFailedLegacyPublicationLeavesOriginalsAndCanBeRetried() throws {
        let failing = RejectingDefaults(suiteName: suite)!
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(company, defaults: failing))
        let original = failing.string(forKey: "salary_companies_v2.companies")
        let preview = try XCTUnwrap(SalaryAccountStorageV2.recoveryPreview(backing: failing))
        failing.reject = true
        XCTAssertThrowsError(try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: failing))
        XCTAssertNil(failing.object(forKey: SalaryAccountStorageV2.rootKey))
        XCTAssertEqual(failing.string(forKey: "salary_companies_v2.companies"), original)
        failing.reject = false
        try SalaryAccountStorageV2.recover(preview, ownershipConfirmed: true, backing: failing)
        XCTAssertEqual(SalaryCompanyStoreV2.readConfirmed(defaults: SalaryAccountStorageV2.guarded(backing: failing)).companies, [company])
    }
}
