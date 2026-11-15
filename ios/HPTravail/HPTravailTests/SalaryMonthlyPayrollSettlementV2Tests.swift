import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryMonthlyPayrollSettlementV2Tests: XCTestCase {
    private typealias Store = SalaryMonthlyPayrollSettlementV2
    private let month = YearMonthV2(year: 2026, month: 8)!
    private var suite = ""
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        suite = "MonthlySettlement." + UUID().uuidString
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: "synthetic-company", name: "Synthetic employer", siret: "12345678901234"), defaults: defaults))
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        SalaryAccountSessionV2.configure { nil }
        super.tearDown()
    }
    private func record(transport: Decimal? = 50, garnishment: Decimal? = 20,
                        addition: Decimal? = 15, deduction: Decimal? = 5,
                        social: Decimal? = 900, paid: Decimal? = 1040,
                        complete: Bool = true, company: String = "synthetic-company",
                        source: String = "Synthetic monthly statement", version: Int = 1) -> Store.Record {
        .init(version: version, companyId: company, period: month, transportReimbursement: transport,
              garnishment: garnishment, otherAddition: addition, otherDeduction: deduction,
              netSocialObserved: social, netPaidObserved: paid, source: source,
              confirmedAt: 1_790_000_000_000, adjustmentsComplete: complete)
    }
    private func result(_ record: Store.Record) -> Store.Result { .init(record: record, reliable: true, warnings: []) }
    private var key: String { Store.prefix + "synthetic-company|\(month)" }

    func testExactCentInputRejectsRoundingAndMalformedTokens() {
        for text in ["-1", "1.005", "NaN", "Infinity", "1e2", "12 EUR", "1.2.3", "1 000", "90071992547409.91"] {
            XCTAssertFalse(Store.parse(text).valid, text)
        }
        XCTAssertEqual(Store.parse(" 12,34 ").value, Decimal(string: "12.34"))
        XCTAssertEqual(Store.parse("0").value, 0)
        XCTAssertTrue(Store.parse("").valid)
        XCTAssertNil(Store.parse("").value)
        XCTAssertNil(Store.cents(.nan))
        XCTAssertNil(Store.cents(Decimal(string: "1.00000000000000000000000001")!))
        XCTAssertNil(Store.cents(Decimal(PayrollMoneyV2.maximumCents) / 100))
        XCTAssertEqual(Store.cents(Decimal(string: "90071992547409.90")!), 9_007_199_254_740_990)
    }
    func testMissingAmountsAreNotZeroAndCompleteRequiresAllFour() {
        XCTAssertFalse(Store.valid(record(transport: nil)))
        XCTAssertTrue(Store.valid(record(transport: nil, complete: false)))
        XCTAssertTrue(Store.valid(record(transport: 0, garnishment: 0, addition: 0, deduction: 0)))
        XCTAssertFalse(Store.valid(record(transport: nil, garnishment: nil, addition: nil, deduction: nil, social: nil, paid: nil, complete: false)))
        XCTAssertFalse(Store.valid(record(company: " ")))
        XCTAssertFalse(Store.valid(record(company: "bad|scope")))
        XCTAssertFalse(Store.valid(record(company: "bad\ncompany")))
        XCTAssertFalse(Store.valid(record(company: "bad\u{0085}company")))
        XCTAssertTrue(Store.validScope(companyId: "early", period: YearMonthV2(year: 1, month: 1)!))
        XCTAssertFalse(Store.valid(record(source: "")))
        XCTAssertFalse(Store.valid(record(source: String(repeating: "x", count: 501))))
        XCTAssertFalse(Store.valid(record(version: 2)))
        XCTAssertFalse(Store.valid(record(source: String(repeating: "😀", count: 251))))
        XCTAssertFalse(Store.valid(record(company: String(repeating: "😀", count: 101))))
        XCTAssertTrue(Store.valid(record(source: String(repeating: "😀", count: 250))))
    }
    func testMonthlyRoundTripAndCompanyMonthIsolation() throws {
        let value = record()
        XCTAssertFalse(Store.save(value, confirmed: false, defaults: defaults))
        XCTAssertTrue(Store.save(value, confirmed: true, defaults: defaults))
        XCTAssertEqual(Store.read(companyId: value.companyId, period: month, defaults: defaults).record, value)
        XCTAssertEqual(Store.decode(try XCTUnwrap(Store.encode(value))), value)
        XCTAssertNil(Store.read(companyId: "another-company", period: month, defaults: defaults).record)
        XCTAssertNil(Store.read(companyId: value.companyId, period: YearMonthV2(year: 2026, month: 9)!, defaults: defaults).record)
        XCTAssertTrue(Store.save(record(transport: nil, complete: false), confirmed: true, defaults: defaults))
        XCTAssertNil(Store.read(companyId: value.companyId, period: month, defaults: defaults).record?.transportReimbursement)
    }
    func testCorruptPayloadIsPreservedUntilExplicitRemoval() {
        defaults.set("corrupt", forKey: key)
        XCTAssertFalse(Store.read(companyId: record().companyId, period: month, defaults: defaults).reliable)
        XCTAssertFalse(Store.save(record(), confirmed: true, defaults: defaults))
        XCTAssertEqual(defaults.string(forKey: key), "corrupt")
        XCTAssertTrue(Store.remove(companyId: record().companyId, period: month, defaults: defaults))
        XCTAssertTrue(Store.save(record(), confirmed: true, defaults: defaults))
    }
    func testStrictJSONRejectsDuplicateUnknownAndPrecisionLoss() throws {
        let good = String(data: try XCTUnwrap(Store.encode(record())), encoding: .utf8)!
        for token in ["1.001", "true", "\"12.34\"", "-0", "90071992547409.91",
                      "1.00000000000000000000000000000000000000001", "1e999", "1e-999", "1e-9999"] {
            let corrupt = good.replacingOccurrences(of: "\"transportReimbursement\":50", with: "\"transportReimbursement\":\(token)")
            XCTAssertNotEqual(corrupt, good)
            XCTAssertNil(Store.decode(Data(corrupt.utf8)), token)
        }
        for tail in [",\"unknown\":1", ",\"source\":\"replacement\"", ",\"\\u0073ource\":\"replacement\""] {
            XCTAssertNil(Store.decode(Data((String(good.dropLast()) + tail + "}").utf8)))
        }
        XCTAssertNil(Store.decode(Data(good.replacingOccurrences(of: "\"netSocialObserved\":900,", with: "").utf8)))
        XCTAssertNil(Store.decode(Data((good + " trailing").utf8)))
        XCTAssertNil(Store.decode(Data(good.replacingOccurrences(of: "\"adjustmentsComplete\":true", with: "\"adjustmentsComplete\":1").utf8)))
        XCTAssertNil(Store.decode(Data(good.replacingOccurrences(of: "\"confirmedAt\":1790000000000", with: "\"confirmedAt\":1.5").utf8)))
    }
    func testStoredContextCannotBeRelabelled() throws {
        defaults.set(try XCTUnwrap(Store.encode(record(company: "other"))), forKey: key)
        XCTAssertFalse(Store.read(companyId: "synthetic-company", period: month, defaults: defaults).reliable)
        XCTAssertFalse(Store.save(record(), confirmed: true, defaults: defaults))
        XCTAssertFalse(Store.read(companyId: "bad|scope", period: month, defaults: defaults).reliable)
    }
    func testConcurrentReplacementCannotBeOverwrittenOrDeletedByOldConfirmation() {
        XCTAssertTrue(Store.save(record(), confirmed: true, defaults: defaults))
        let old = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        XCTAssertTrue(Store.save(record(paid: 1200), confirmed: true, expected: old, defaults: defaults))
        XCTAssertFalse(Store.save(record(paid: 1300), confirmed: true, expected: old, defaults: defaults))
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: old, defaults: defaults))
        XCTAssertEqual(Store.read(companyId: record().companyId, period: month, defaults: defaults).record?.netPaidObserved, 1200)
    }
    func testCorruptDeletionConfirmationIsBoundToExactSource() {
        defaults.set("corrupt-one", forKey: key)
        let old = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        defaults.set("corrupt-two", forKey: key)
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: old, defaults: defaults))
        let current = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        XCTAssertTrue(Store.remove(companyId: record().companyId, period: month, expected: current, defaults: defaults))
    }
    func testCorruptDictionaryRevisionIsStableAcrossReadsAndConstructionOrder() throws {
        let entries: [(String, Any)] = [
            ("alpha", ["one": 1, "two": 2]), ("beta", "value"),
            ("gamma", ["x", "y"]), ("delta", Data([1, 2, 3])),
            ("date", Date(timeIntervalSinceReferenceDate: 12.5))
        ]
        defaults.set(Dictionary(uniqueKeysWithValues: entries), forKey: key)
        let confirmed = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        XCTAssertFalse(confirmed.reliable)
        XCTAssertNotNil(confirmed.revision)
        for _ in 0..<1000 {
            XCTAssertEqual(Store.read(companyId: record().companyId, period: month, defaults: defaults), confirmed)
        }
        for offset in entries.indices {
            let reordered = Array(entries[offset...] + entries[..<offset])
            defaults.set(Dictionary(uniqueKeysWithValues: reordered), forKey: key)
            XCTAssertEqual(Store.read(companyId: record().companyId, period: month, defaults: defaults), confirmed)
        }
        XCTAssertTrue(Store.remove(companyId: record().companyId, period: month, expected: confirmed, defaults: defaults))
    }
    func testChangedNestedCorruptionCannotUseOldDeletionConfirmation() {
        defaults.set(["outer": ["a": 1, "b": 2]], forKey: key)
        let confirmed = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        defaults.set(["outer": ["a": 1, "b": 3]], forKey: key)
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: confirmed, defaults: defaults))
        XCTAssertNotNil(defaults.object(forKey: key))
    }
    private final class InjectedCorruptDefaults: UserDefaults {
        var injectedKey = ""
        var injectedValue: Any?
        var removalCount = 0
        override func object(forKey defaultName: String) -> Any? {
            defaultName == injectedKey ? injectedValue : super.object(forKey: defaultName)
        }
        override func removeObject(forKey defaultName: String) {
            guard defaultName == injectedKey else { super.removeObject(forKey: defaultName); return }
            removalCount += 1
            injectedValue = nil
        }
    }
    func testCorruptRevisionSeparatesScalarTypesAndOrderedValues() throws {
        let injected = try XCTUnwrap(InjectedCorruptDefaults(suiteName: suite))
        injected.injectedKey = key
        let cases: [Any] = [
            NSNumber(value: true), NSNumber(value: 1), "1", Data("1".utf8),
            NSNumber(value: -0.0), NSNumber(value: 0.0),
            Date(timeIntervalSinceReferenceDate: 1), Date(timeIntervalSinceReferenceDate: 2),
            Data([1, 2]), Data([2, 1]), ["a", "b"], ["b", "a"]
        ]
        var revisions = Set<String>()
        for value in cases {
            injected.injectedValue = value
            let current = Store.read(companyId: record().companyId, period: month, defaults: injected)
            XCTAssertFalse(current.reliable)
            XCTAssertTrue(revisions.insert(try XCTUnwrap(current.revision)).inserted)
        }
        injected.injectedValue = NSNumber(value: true)
        let old = Store.read(companyId: record().companyId, period: month, defaults: injected)
        injected.injectedValue = NSNumber(value: 1)
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: old, defaults: injected))
        XCTAssertEqual(injected.removalCount, 0)
    }
    func testUnverifiableCorruptionNeverDeletesEvenWithoutExpectedRevision() throws {
        let injected = try XCTUnwrap(InjectedCorruptDefaults(suiteName: suite))
        injected.injectedKey = key
        var deep: Any = "leaf"
        for _ in 0..<18 { deep = [deep] }
        let cases: [Any] = [
            NSNull(), NSObject(), Data(repeating: 0, count: 1_048_577),
            Array(repeating: "item", count: 4097), deep,
            NSNumber(value: Double.nan), Date(timeIntervalSinceReferenceDate: .infinity)
        ]
        for value in cases {
            injected.injectedValue = value
            let current = Store.read(companyId: record().companyId, period: month, defaults: injected)
            XCTAssertFalse(current.reliable)
            XCTAssertNil(current.revision)
            XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: current, defaults: injected))
            XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, defaults: injected))
            XCTAssertNotNil(injected.injectedValue)
        }
        XCTAssertEqual(injected.removalCount, 0)
        injected.injectedValue = nil
        XCTAssertTrue(Store.remove(companyId: record().companyId, period: month, expected: .missing, defaults: injected))
    }
    func testDeletedCompanyBlocksStaleWritesAndKeepsOrphanEvidenceOpaque() throws {
        XCTAssertTrue(Store.save(record(), confirmed: true, defaults: defaults))
        let bytes = try XCTUnwrap(defaults.data(forKey: key))
        let expected = Store.read(companyId: record().companyId, period: month, defaults: defaults)
        XCTAssertTrue(SalaryCompanyStoreV2.remove(companyId: record().companyId, defaults: defaults))
        XCTAssertFalse(Store.read(companyId: record().companyId, period: month, defaults: defaults).reliable)
        XCTAssertFalse(Store.save(record(paid: 1200), confirmed: true, expected: expected, defaults: defaults))
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, expected: expected, defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: key), bytes)
    }
    func testPresentationPreservesExactCentNearCommonBoundary() {
        XCTAssertEqual(SalaryCalculatedPayslipPresentationV2.money(90_071_992_547_409.90), "90071992547409,90 €")
        XCTAssertEqual(SalaryCalculatedPayslipPresentationV2.money(-1.25, allowNegative: true), "-1,25 €")
    }
    func testProjectionAddsOnlyConfirmedAdjustmentsAndKeepsObservedDistinct() {
        let projected = Store.project(result(record(paid: 1042)), companyId: record().companyId,
                                      period: month, netAfterIncomeTax: 1000, baseReliable: true)
        XCTAssertEqual(projected.projectedPaid, 1040)
        XCTAssertEqual(projected.observedMinusProjected, 2)
        XCTAssertEqual(projected.record?.netSocialObserved, 900)
        XCTAssertEqual(projected.record?.netPaidObserved, 1042)
        XCTAssertEqual(Store.project(result(record(transport: 0, garnishment: 0, addition: 0, deduction: 0)),
                                     companyId: record().companyId, period: month, netAfterIncomeTax: 1000,
                                     baseReliable: true).projectedPaid, 1000)
    }
    func testUnreliableClampedOrMissingBaseDoesNotHideObservedFacts() {
        for base in [nil, 0, 1000] as [Double?] {
            let value = Store.project(result(record()), companyId: record().companyId, period: month,
                                      netAfterIncomeTax: base, baseReliable: false)
            XCTAssertNil(value.projectedPaid)
            XCTAssertNotNil(value.record?.netPaidObserved)
        }
        XCTAssertNil(Store.project(result(record(complete: false)), companyId: record().companyId, period: month,
                                   netAfterIncomeTax: 1000, baseReliable: true).projectedPaid)
        XCTAssertNil(Store.project(result(record()), companyId: record().companyId, period: month,
                                   netAfterIncomeTax: nil, baseReliable: true).projectedPaid)
    }
    func testWrongContextOrCorruptSourceDoesNotLeakObservedAmounts() {
        XCTAssertNil(Store.project(result(record()), companyId: "other", period: month,
                                   netAfterIncomeTax: 1000, baseReliable: true).record)
        XCTAssertNil(Store.project(result(record()), companyId: record().companyId,
                                   period: YearMonthV2(year: 2026, month: 9)!, netAfterIncomeTax: 1000, baseReliable: true).record)
        XCTAssertNil(Store.project(.init(record: record(), reliable: false, warnings: ["broken"]),
                                   companyId: record().companyId, period: month, netAfterIncomeTax: 1000, baseReliable: true).record)
    }
    func testNegativeAndOverflowPaymentRemainUnknown() {
        XCTAssertNil(Store.project(result(record(garnishment: 2000)), companyId: record().companyId,
                                   period: month, netAfterIncomeTax: 1000, baseReliable: true).projectedPaid)
        XCTAssertNil(Store.project(result(record()), companyId: record().companyId,
                                   period: month, netAfterIncomeTax: 90_071_992_547_409.90, baseReliable: true).projectedPaid)
    }
    func testSharedPresentationShowsObservedValuesWhenCalculationBlocked() {
        var snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: nil)
        snapshot.monthlySettlement = result(record())
        let sections = SalaryCalculatedPayslipPresentationV2.sections(snapshot: snapshot, companyId: record().companyId)
        let rows = sections.flatMap(\.rows)
        XCTAssertEqual(rows.first { $0.id == "paid" }?.value, "À confirmer")
        XCTAssertEqual(rows.first { $0.id == "netPaidObserved" }?.value, SalaryCalculatedPayslipPresentationV2.money(1040))
        XCTAssertTrue(rows.first { $0.id == "netSocialObserved" }!.label.contains("constaté"))
        XCTAssertEqual(rows.first { $0.id == "settlement-source" }?.value, record().source)
        let other = SalaryCalculatedPayslipPresentationV2.sections(snapshot: snapshot, companyId: "other").flatMap(\.rows)
        XCTAssertEqual(other.first { $0.id == "netPaidObserved" }?.value, "À confirmer")
    }
    func testAccountChangesInvalidateReadsWritesAndRemovalIncludingABA() {
        defaults.removePersistentDomain(forName: suite)
        var identity: String? = "uid:synthetic-A"
        SalaryAccountSessionV2.configure { identity }
        let a = SalaryAccountStorageV2.guarded(backing: defaults)
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: record().companyId, name: "Synthetic A", siret: "12345678901234"), defaults: a))
        XCTAssertTrue(Store.save(record(), confirmed: true, defaults: a))
        identity = "uid:synthetic-B"
        SalaryAccountSessionV2.authenticationChanged()
        let b = SalaryAccountStorageV2.guarded(backing: defaults)
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: record().companyId, name: "Synthetic B", siret: "12345678901234"), defaults: b))
        XCTAssertNil(Store.read(companyId: record().companyId, period: month, defaults: b).record)
        XCTAssertFalse(Store.read(companyId: record().companyId, period: month, defaults: a).reliable)
        XCTAssertFalse(Store.save(record(paid: 1111), confirmed: true, defaults: a))
        XCTAssertFalse(Store.remove(companyId: record().companyId, period: month, defaults: a))
        XCTAssertTrue(Store.save(record(paid: 2222), confirmed: true, defaults: b))
        identity = "uid:synthetic-A"
        SalaryAccountSessionV2.authenticationChanged()
        let returned = SalaryAccountStorageV2.guarded(backing: defaults)
        XCTAssertEqual(Store.read(companyId: record().companyId, period: month, defaults: returned).record?.netPaidObserved, 1040)
        XCTAssertFalse(Store.read(companyId: record().companyId, period: month, defaults: a).reliable)
        XCTAssertTrue(SalaryAccountStorageV2.isPersonalKey(key))
    }
}
