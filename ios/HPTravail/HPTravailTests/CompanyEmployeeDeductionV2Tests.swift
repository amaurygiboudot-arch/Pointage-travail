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

    private func confirmMonthly(
        kind: Resolver.Kind = .mutualEmployee,
        amount: Double = 42,
        period: Resolver.YearMonth = Resolver.YearMonth(year: 2026, month: 4)!,
        source: String = "Source synthétique confirmée",
        confirmed: Bool = true,
        expected: CompanyEmployeeDeductionStoreV2.ReadResult? = nil
    ) -> Bool {
        CompanyEmployeeDeductionStoreV2.confirmMonth(
            defaults: defaults, companyId: "a", period: period, kind: kind,
            amount: amount, source: source, confirmed: confirmed,
            expected: expected ?? CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        )
    }

    private func seedMonthlyCompany() {
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            company(id: "a", siret: "12345678901234"), defaults: defaults
        ))
    }

    func testMonthlyEntrySupportsAllSixKindsWithoutCopyingToAnotherMonthOrCompany() {
        seedMonthlyCompany()
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            company(id: "b", siret: "22345678901234"), defaults: defaults
        ))
        let april = Resolver.YearMonth(year: 2026, month: 4)!
        for (index, kind) in Resolver.Kind.allCases.enumerated() {
            XCTAssertTrue(confirmMonthly(kind: kind, amount: Double(index)))
        }
        let read = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertEqual(read.records.count, 6)
        XCTAssertTrue(read.records.allSatisfy { $0.effectiveFrom == april && $0.effectiveTo == april })
        let selected = CompanyEmployeeDeductionStoreV2.resolve(read, period: april)
        XCTAssertEqual(selected[.mutualEmployee].amount, 0, "Explicit zero is a confirmed fact")
        let following = CompanyEmployeeDeductionStoreV2.resolve(read, period: Resolver.YearMonth(year: 2026, month: 5)!)
        XCTAssertTrue(Resolver.Kind.allCases.allSatisfy { following[$0].amount == nil })
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "b").records.isEmpty)
    }

    func testMonthlyCorrectionReusesItsRecordAndLeavesUnknownKindsUnknown() {
        seedMonthlyCompany()
        XCTAssertTrue(confirmMonthly())
        let first = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertTrue(confirmMonthly(amount: 45, expected: first))
        let corrected = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertEqual(corrected.records.count, 1)
        XCTAssertEqual(corrected.records.first?.id, first.records.first?.id)
        XCTAssertEqual(corrected.records.first?.amount, 45)
        let resolved = CompanyEmployeeDeductionStoreV2.resolve(corrected, period: Resolver.YearMonth(year: 2026, month: 4)!)
        XCTAssertNil(resolved[.transportEmployee].amount)
    }

    func testMonthlyEntryRejectsStaleDraftWithoutChangingNewerData() {
        seedMonthlyCompany()
        let empty = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertTrue(confirmMonthly(amount: 45, expected: empty))
        let newer = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertFalse(confirmMonthly(amount: 50, expected: empty))
        XCTAssertEqual(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a"), newer)
    }

    func testMonthlyEntryPreservesAnExistingWiderPeriodAndOverlappingRecords() {
        seedMonthlyCompany()
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.save(defaults: defaults, companyId: "a", record: record(id: "wide", amount: 40)))
        let wide = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertFalse(confirmMonthly(expected: wide))
        XCTAssertEqual(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a"), wide)
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.save(defaults: defaults, companyId: "a", record: record(id: "conflict", amount: 41)))
        let conflict = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        XCTAssertFalse(confirmMonthly(expected: conflict))
        XCTAssertEqual(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a"), conflict)
    }

    func testMonthlyEntryRequiresSourceConfirmationAndValidAmount() {
        seedMonthlyCompany()
        XCTAssertFalse(confirmMonthly(source: " "))
        XCTAssertFalse(confirmMonthly(source: String(repeating: "a", count: 501)))
        XCTAssertFalse(confirmMonthly(confirmed: false))
        XCTAssertFalse(confirmMonthly(amount: -1))
        XCTAssertFalse(confirmMonthly(amount: .infinity))
        XCTAssertFalse(confirmMonthly(amount: .nan))
        XCTAssertTrue(CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a").records.isEmpty)
    }

    func testMonthlyEntryNeverOverwritesCorruptOrMissingCompanyStorage() {
        seedMonthlyCompany()
        let expected = CompanyEmployeeDeductionStoreV2.read(defaults: defaults, companyId: "a")
        defaults.set("{corrompu", forKey: "employee_deductions_v2.a")
        XCTAssertFalse(confirmMonthly(expected: expected))
        XCTAssertEqual(defaults.string(forKey: "employee_deductions_v2.a"), "{corrompu")
        XCTAssertFalse(CompanyEmployeeDeductionStoreV2.confirmMonth(
            defaults: defaults, companyId: "missing", period: Resolver.YearMonth(year: 2026, month: 4)!,
            kind: .mutualEmployee, amount: 0, source: "Source synthétique", confirmed: true, expected: expected
        ))
    }
}
