import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#else
@testable import HPTravail
#endif

final class SalaryConfirmedSicknessCashV2Tests: XCTestCase {
    func testInvalidConfirmationTimeYearAndUnknownAreRejected() {
        func record(year: Int = 2026, time: TimeInterval = 1, direct: Decimal? = 100) -> SalaryConfirmedSicknessCashV2.Record {
            .init(companyId: "a", period: YearMonthV2(year: year, month: 10)!, directEmployeeNetBeforeTax: direct,
                  subrogatedEmployerNetBeforeTax: nil, source: "CPAM 42", confirmedAt: Date(timeIntervalSince1970: time))
        }
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.valid(record(time: .infinity)))
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.valid(record(time: .nan)))
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.valid(record(year: 0)))
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.valid(record(year: 10000)))
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.valid(record(direct: nil)))
        XCTAssertTrue(SalaryConfirmedSicknessCashV2.valid(record(direct: 0)))
    }
    func testMonthlyEvidenceReplacementIsolationAndCorruptionGuard() {
        let suite = "SalaryConfirmedSicknessCashV2Tests.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let month = YearMonthV2(year: 2026, month: 10)!
        let record = SalaryConfirmedSicknessCashV2.Record(companyId: "a", period: month,
            directEmployeeNetBeforeTax: 100, subrogatedEmployerNetBeforeTax: nil,
            source: "Décompte CPAM référence 42", confirmedAt: Date())
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.save(record, confirmed: false, defaults: defaults))
        XCTAssertTrue(SalaryConfirmedSicknessCashV2.save(record, confirmed: true, defaults: defaults))
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: "b", period: month, defaults: defaults).record)
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: "a", period: YearMonthV2(year: 2026, month: 9)!, defaults: defaults).record)
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: "a", period: month, defaults: defaults).record?.subrogatedEmployerNetBeforeTax)
        let replacement = SalaryConfirmedSicknessCashV2.Record(companyId: "a", period: month,
            directEmployeeNetBeforeTax: nil, subrogatedEmployerNetBeforeTax: 200,
            source: "Décompte subrogation référence 43", confirmedAt: Date())
        XCTAssertTrue(SalaryConfirmedSicknessCashV2.save(replacement, confirmed: true, defaults: defaults))
        XCTAssertNil(SalaryConfirmedSicknessCashV2.read(companyId: "a", period: month, defaults: defaults).record?.directEmployeeNetBeforeTax)
        defaults.set("corrupt", forKey: "salary_confirmed_sickness_cash_v2.a|2026-10")
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.read(companyId: "a", period: month, defaults: defaults).reliable)
        XCTAssertFalse(SalaryConfirmedSicknessCashV2.save(record, confirmed: true, defaults: defaults))
        SalaryConfirmedSicknessCashV2.remove(companyId: "a", period: month, defaults: defaults)
        XCTAssertTrue(SalaryConfirmedSicknessCashV2.read(companyId: "a", period: month, defaults: defaults).reliable)
    }
}
