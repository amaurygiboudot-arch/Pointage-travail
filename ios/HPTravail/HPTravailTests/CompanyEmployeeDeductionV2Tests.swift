import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class CompanyEmployeeDeductionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    private func month(_ year: Int, _ month: Int) -> Resolver.YearMonth {
        Resolver.YearMonth(year: year, month: month)!
    }

    private func record(
        id: String,
        kind: Resolver.Kind,
        amount: Double,
        from: Resolver.YearMonth = Resolver.YearMonth(year: 2026, month: 1)!,
        to: Resolver.YearMonth? = nil,
        source: String? = "Bulletin confirmé"
    ) -> Resolver.Record {
        Resolver.Record(
            id: id,
            kind: kind,
            amount: amount,
            effectiveFrom: from,
            effectiveTo: to,
            source: source
        )
    }

    private func completeDirectRecords(period: Resolver.YearMonth) -> [Resolver.Record] {
        [
            record(id: "mutual", kind: .mutualEmployee, amount: 42, from: period, to: period),
            record(id: "provident", kind: .providentEmployee, amount: 18, from: period, to: period),
            record(id: "transport", kind: .transportEmployee, amount: 0, from: period, to: period),
            record(id: "provident-tax", kind: .employeeProvidentNonDeductible, amount: 4, from: period, to: period)
        ]
    }

    func testResolverUsesOnlyRecordApplicableToRequestedMonth() {
        let january = month(2026, 1)
        let february = month(2026, 2)
        let snapshot = Resolver.resolve(
            records: [
                record(id: "jan", kind: .mutualEmployee, amount: 40, from: january, to: january),
                record(id: "feb", kind: .mutualEmployee, amount: 45, from: february, to: february)
            ],
            period: february
        )

        XCTAssertTrue(snapshot[.mutualEmployee].reliable)
        XCTAssertEqual(snapshot[.mutualEmployee].amount, 45)
        XCTAssertEqual(snapshot[.mutualEmployee].source, "Bulletin confirmé")
    }

    func testGapInDatedRecordsIsNotInterpretedAsZero() {
        let january = month(2026, 1)
        let february = month(2026, 2)
        let snapshot = Resolver.resolve(
            records: [record(id: "jan", kind: .mutualEmployee, amount: 40, from: january, to: january)],
            period: february
        )

        XCTAssertFalse(snapshot[.mutualEmployee].reliable)
        XCTAssertNil(snapshot[.mutualEmployee].amount)
        XCTAssertFalse(snapshot[.mutualEmployee].warnings.isEmpty)
    }

    func testOverlappingPeriodsBlockOnlyConcernedKind() {
        let january = month(2026, 1)
        let snapshot = Resolver.resolve(
            records: [
                record(id: "a", kind: .mutualEmployee, amount: 40, from: january),
                record(id: "b", kind: .mutualEmployee, amount: 41, from: january)
            ],
            period: january
        )

        XCTAssertFalse(snapshot[.mutualEmployee].reliable)
        XCTAssertNil(snapshot[.mutualEmployee].amount)
        XCTAssertTrue(snapshot[.providentEmployee].reliable)
        XCTAssertFalse(snapshot[.providentEmployee].hasDatedRecords)
    }

    func testLegacyUndatedAmountIsDetectedButNeverUsed() {
        let period = month(2026, 1)
        let base = Resolver.resolve(records: [], period: period)
        let resolved = Resolver.withLegacyFallback(
            snapshot: base,
            legacyAmounts: [.mutualEmployee: 40]
        )

        XCTAssertFalse(resolved[.mutualEmployee].reliable)
        XCTAssertNil(resolved[.mutualEmployee].amount)
        XCTAssertFalse(resolved[.mutualEmployee].legacyUsed)
    }

    func testPayrollBridgeRequiresExplicitValueIncludingZeroForEveryCashDeduction() {
        let period = month(2026, 4)
        let snapshot = Resolver.resolve(records: completeDirectRecords(period: period), period: period)
        let result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(snapshot: snapshot, period: period)

        XCTAssertTrue(result.confirmedEmployeeDeductionsComplete)
        XCTAssertEqual(result.deductions.count, 2)
        XCTAssertEqual(result.deductions.reduce(0) { $0 + $1.amount }, 60, accuracy: 0.001)
        XCTAssertFalse(result.deductions.contains { $0.id.contains("employee_provident_non_deductible") })
        XCTAssertFalse(result.traces.isEmpty)
    }

    func testPayrollBridgeDoesNotRequireTaxOnlyProvidentBreakdownForCashNet() {
        let period = month(2026, 4)
        let records = completeDirectRecords(period: period).filter { $0.kind != .employeeProvidentNonDeductible }
        let snapshot = Resolver.resolve(records: records, period: period)
        let result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(snapshot: snapshot, period: period)

        XCTAssertTrue(result.confirmedEmployeeDeductionsComplete)
        XCTAssertEqual(result.deductions.reduce(0) { $0 + $1.amount }, 60, accuracy: 0.001)
    }

    func testPayrollBridgeFailsClosedWhenOneCashDeductionIsMissing() {
        let period = month(2026, 4)
        let records = completeDirectRecords(period: period).filter { $0.kind != .transportEmployee }
        let snapshot = Resolver.resolve(records: records, period: period)
        let result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(snapshot: snapshot, period: period)

        XCTAssertFalse(result.confirmedEmployeeDeductionsComplete)
        XCTAssertTrue(result.warnings.contains { $0.contains("Retenue transport") })
    }
}

final class CompanyEmployeeDeductionStoreV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2
    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "CompanyEmployeeDeductionStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func company(id: String, siret: String) -> SalaryCompanyV2 {
        SalaryCompanyV2(id: id, name: id, siret: siret)
    }

    private func record(id: String, amount: Double) -> Resolver.Record {
        Resolver.Record(
            id: id,
            kind: .mutualEmployee,
            amount: amount,
            effectiveFrom: Resolver.YearMonth(year: 2026, month: 4)!,
            source: "Bulletin avril 2026"
        )
    }

    func testMissingCompanyBlocksReadAndWrite() {
        let read = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "missing")

        XCTAssertFalse(read.reliable)
        XCTAssertFalse(CompanyEmployeeDeductionStoreV2.save(
            defaults: defaults,
            companyId: "missing",
            record: record(id: "r1", amount: 40)
        ))
    }

    func testRecordsAreStrictlySeparatedByCompany() {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            company(id: "a", siret: "12345678901234"),
            defaults: defaults
        ))
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            company(id: "b", siret: "22345678901234"),
            defaults: defaults
        ))
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.save(
            defaults: defaults,
            companyId: "a",
            record: record(id: "a-mutual", amount: 40)
        ))
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.save(
            defaults: defaults,
            companyId: "b",
            record: record(id: "b-mutual", amount: 55)
        ))

        XCTAssertEqual(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a").records.map(\.amount), [40])
        XCTAssertEqual(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "b").records.map(\.amount), [55])
    }

    func testCorruptStoreNeverBecomesReliableEmptyDeductions() {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            company(id: "a", siret: "12345678901234"),
            defaults: defaults
        ))
        defaults.set("{corrompu", forKey: "employee_deductions_v2.a")

        let stored = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        let snapshot = CompanyEmployeeDeductionStoreV2.resolve(
            stored,
            period: Resolver.YearMonth(year: 2026, month: 4)!
        )

        XCTAssertFalse(stored.reliable)
        XCTAssertFalse(snapshot[.mutualEmployee].reliable)
        XCTAssertTrue(snapshot[.mutualEmployee].hasDatedRecords)
        XCTAssertNil(snapshot[.mutualEmployee].amount)
    }
}
