import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
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
    func testLegacyOutOfRangeRecordIsPreservedButCannotBeUsedOrOverwritten() throws {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let period = YearMonthV2(year: 2026, month: 9)!
        let key = "salary_payslip_observed_v2.one|\(period)"
        let old = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 1e17, "netTaxable": 2000], excerpts: [:], confirmedAt: Date())
        let raw = try JSONEncoder().encode(old)
        defaults.set(raw, forKey: key)
        let result = SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults)
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.record)
        XCTAssertEqual(defaults.data(forKey: key), raw)
        let replacement = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 2500], excerpts: [:], confirmedAt: Date())
        XCTAssertFalse(SalaryPayslipObservedStoreV2.save(replacement, confirmed: true, defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: key), raw)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.read(companyId: "two", period: period, defaults: defaults).reliable)
    }

    func testOutOfRangeWriteCannotMutateAnExistingValidRecord() throws {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let period = YearMonthV2(year: 2026, month: 9)!
        let valid = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 2500], excerpts: [:], confirmedAt: Date())
        XCTAssertTrue(SalaryPayslipObservedStoreV2.save(valid, confirmed: true, defaults: defaults))
        let key = "salary_payslip_observed_v2.one|\(period)"
        let raw = defaults.data(forKey: key)
        let invalid = SalaryPayslipObservedStoreV2.Record(companyId: "one", period: period,
            amounts: ["socialGross": 1e17, "netTaxable": 2000], excerpts: [:], confirmedAt: Date())
        XCTAssertFalse(SalaryPayslipObservedStoreV2.save(invalid, confirmed: true, defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: key), raw)
        XCTAssertEqual(SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults).record, valid)
    }

    func testRawStoredUnrepresentableCentIsNotLostThroughDoubleDecoding() throws {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let period = YearMonthV2(year: 2026, month: 9)!
        let key = "salary_payslip_observed_v2.one|\(period)"
        for token in ["90071992547409.91", "90071992547409.92", "100000000000000000"] {
            let raw = "{\"companyId\":\"one\",\"period\":{\"year\":2026,\"month\":9},\"amounts\":{\"socialGross\":\(token)},\"excerpts\":{},\"confirmedAt\":1000}".data(using: .utf8)!
            // JSONDecoder's Double representation alone cannot diagnose the lost cent.
            XCTAssertNoThrow(try JSONDecoder().decode(SalaryPayslipObservedStoreV2.Record.self, from: raw))
            defaults.set(raw, forKey: key)
            let result = SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults)
            XCTAssertFalse(result.reliable, token)
            XCTAssertNil(result.record)
            XCTAssertEqual(defaults.data(forKey: key), raw)
        }
        let valid = "{\"companyId\":\"one\",\"period\":{\"year\":2026,\"month\":9},\"amounts\":{\"socialGross\":90071992547409.90},\"excerpts\":{},\"confirmedAt\":1000}".data(using: .utf8)!
        defaults.set(valid, forKey: key)
        XCTAssertTrue(SalaryPayslipObservedStoreV2.read(companyId: "one", period: period, defaults: defaults).reliable)
        XCTAssertEqual(defaults.data(forKey: key), valid)
    }

}
