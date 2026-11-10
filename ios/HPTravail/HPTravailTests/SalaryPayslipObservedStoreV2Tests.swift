import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#else
@testable import HPTravail
#endif

final class SalaryPayslipObservedStoreV2Tests: XCTestCase {
    func testConfirmationScopeAndDelete() {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let period = YearMonthV2(year: 2026, month: 9)!
        let record = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 2000], excerpts: ["socialGross": "Total brut 2000,00"], confirmedAt: Date())
        XCTAssertFalse(SalaryPayslipObservedStoreV2.save(record, confirmed: false, defaults: defaults))
        XCTAssertTrue(SalaryPayslipObservedStoreV2.save(record, confirmed: true, defaults: defaults))
        XCTAssertEqual(SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults).record, record)
        XCTAssertNil(SalaryPayslipObservedStoreV2.read(companyId: "two", period: period, defaults: defaults).record)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.remove(companyId: "one", period: period, defaults: defaults))
        XCTAssertNil(SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults).record)
    }
    func testInvalidAndCorruptStorageRemainBlocked() {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let period = YearMonthV2(year: 2026, month: 9)!
        let record = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 2000], excerpts: [:], confirmedAt: Date())
        defaults.set("corrupt", forKey: "salary_payslip_observed_v2.one|\(period)")
        XCTAssertFalse(SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults).reliable)
        XCTAssertFalse(SalaryPayslipObservedStoreV2.save(record, confirmed: true, defaults: defaults))
        XCTAssertFalse(SalaryPayslipObservedStoreV2.validScope(companyId: "one|two", period: period))
        XCTAssertFalse(SalaryPayslipObservedStoreV2.valid(.init(companyId: "one", period: period,
            amounts: ["socialGross": .infinity], excerpts: [:], confirmedAt: Date())))
        XCTAssertFalse(SalaryPayslipObservedStoreV2.valid(.init(companyId: "one", period: period,
            amounts: ["socialGross": 2000], excerpts: ["netTaxable": "unlinked"], confirmedAt: Date())))
        XCTAssertFalse(SalaryPayslipObservedStoreV2.valid(.init(companyId: "one", period: period,
            amounts: ["socialGross": 2000], excerpts: ["socialGross": String(repeating: "x", count: 301)], confirmedAt: Date())))
        XCTAssertFalse(SalaryPayslipObservedStoreV2.valid(.init(companyId: "one", period: period,
            amounts: ["invented": 2000], excerpts: [:], confirmedAt: Date())))
    }
}
