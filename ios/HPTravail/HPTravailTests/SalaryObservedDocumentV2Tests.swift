import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryObservedDocumentV2Tests: XCTestCase {
    private let period = YearMonthV2(year: 2026, month: 8)!
    private func document() -> SalaryObservedDocumentV2.Document {
        .init(sourcePeriod: period, source: "Synthetic bulletin, paid the following month", confirmedAt: Date(),
              lines: [.init(label: "Base", base: "140", employeeRate: "12.3456", employeeAmount: "1728.38"),
                      .init(label: "Contribution", base: "1728.38", employerBase: "1800", employeeAmount: "-50.12", employerAmount: "100.24"),
                      .init(label: "Contribution", employerAmount: "-125.4"),
                      .init(label: "Total", employeeAmount: "1678.26")])
    }
    private func withStore(_ action: (UserDefaults) throws -> Void) rethrows {
        let name = UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name); SalaryAccountSessionV2.configure { nil } }
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: "company", name: "Synthetic", siret: "12345678901234"), defaults: defaults))
        try action(defaults)
    }
    func testExactSignedCellsAndPeriodWithoutPaymentDateInference() {
        XCTAssertEqual(SalaryObservedDocumentV2.cell(" -12,34 ", money: true), "-12.34")
        XCTAssertEqual(SalaryObservedDocumentV2.cell("0", money: true), "0")
        XCTAssertEqual(SalaryObservedDocumentV2.cell("1.2345", money: false), "1.2345")
        for value in ["1.005", "1e2", "NaN", "Infinity", "1000000000.01", "--1", "1 000"] {
            XCTAssertNil(SalaryObservedDocumentV2.cell(value, money: true), value)
        }
        XCTAssertNil(SalaryObservedDocumentV2.cell("1.23456", money: false))
        XCTAssertEqual(SalaryObservedDocumentV2.period("08/2026"), period)
        for text in ["8/2026", "13/2026", "08/0000", "2026-08", "09/2026 paid August"] { XCTAssertNil(SalaryObservedDocumentV2.period(text)) }
        XCTAssertTrue(SalaryObservedDocumentV2.valid(document(), period: period))
        XCTAssertFalse(SalaryObservedDocumentV2.valid(document(), period: YearMonthV2(year: 2026, month: 9)!))
    }
    func testControlsCannotOverflowPDFRows() {
        for token in ["\n", "\r", "\u{0085}", "\u{2028}", "\u{2029}"] {
            var value = document(); value.source = "Source" + String(repeating: token, count: 150)
            XCTAssertFalse(SalaryObservedDocumentV2.valid(value, period: period))
            value = document(); value.lines[0].label = "Row" + token
            XCTAssertFalse(SalaryObservedDocumentV2.valid(value, period: period))
        }
        var value = document(); value.source = String(repeating: "x", count: 500); value.lines[0].label = String(repeating: "x", count: 160)
        XCTAssertTrue(SalaryObservedDocumentV2.valid(value, period: period))
    }
    func testPresentationPreservesOrderDuplicateLabelsUnknownCellsAndNeverSumsTotals() {
        let rows = SalaryObservedDocumentV2.rows(document())
        XCTAssertEqual(rows.map(\.label), ["Base", "Contribution", "Contribution", "Total"])
        XCTAssertTrue(rows[1].value.contains("Base employeur : 1800"))
        XCTAssertTrue(rows[2].value.contains("Montant salarié (€) : Non renseigné"))
        XCTAssertTrue(rows[3].value.contains("1678,26"))
        XCTAssertEqual(rows.count, 4)
    }
    func testDetailWithoutHoursOrEngineAndStaleAggregateRequiresReconfirmation() {
        withStore { defaults in
            let empty = SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults)
            XCTAssertTrue(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "company", period: period, confirmed: true, expected: empty, defaults: defaults))
            let first = SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults)
            XCTAssertTrue(SalaryObservedDocumentV2.current(first.record!))
            XCTAssertTrue(first.record!.amounts.isEmpty)
            let imported = SalaryPayslipObservedStoreV2.Record(companyId: "company", period: period, amounts: ["socialGross": 1800], excerpts: ["socialGross": "Synthetic total"], confirmedAt: Date())
            XCTAssertTrue(SalaryPayslipObservedStoreV2.save(imported, confirmed: true, defaults: defaults))
            let changed = SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults)
            XCTAssertNotNil(changed.record!.detailedDocument)
            XCTAssertFalse(SalaryObservedDocumentV2.current(changed.record!))
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "company", period: period, confirmed: true, expected: first, defaults: defaults))
            XCTAssertTrue(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "company", period: period, confirmed: true, expected: changed, defaults: defaults))
            let confirmed = SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults)
            XCTAssertTrue(SalaryObservedDocumentV2.current(confirmed.record!))
            XCTAssertEqual(confirmed.record!.amounts, imported.amounts)
            XCTAssertTrue(SalaryPayslipObservedStoreV2.saveDetail(nil, companyId: "company", period: period, confirmed: true, expected: confirmed, defaults: defaults))
            XCTAssertEqual(SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults).record!.amounts, imported.amounts)
        }
    }
    func testBoundedValidationCorruptStorageAndMonthMismatchPreserved() {
        withStore { defaults in
            let expected = SalaryPayslipObservedStoreV2.read(companyId: "company", period: period, defaults: defaults)
            var invalid = document(); invalid.lines = Array(repeating: invalid.lines[0], count: 151)
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(invalid, companyId: "company", period: period, confirmed: true, expected: expected, defaults: defaults))
            invalid = document(); invalid.lines[0].employeeAmount = "1.000"
            XCTAssertFalse(SalaryObservedDocumentV2.valid(invalid, period: period))
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "missing", period: period, confirmed: true, expected: expected, defaults: defaults))
            let next = YearMonthV2(year: 2026, month: 9)!
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "company", period: next, confirmed: true, expected: expected, defaults: defaults))
            defaults.set("corrupt", forKey: "salary_payslip_observed_v2.company|\(period)")
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "company", period: period, confirmed: true, expected: expected, defaults: defaults))
            XCTAssertFalse(SalaryPayslipObservedStoreV2.remove(companyId: "company", period: period, defaults: defaults))
            XCTAssertEqual(defaults.string(forKey: "salary_payslip_observed_v2.company|\(period)"), "corrupt")
        }
    }
    func testAccountChangeWithIdenticalRecordCannotReuseExpectedRevision() {
        let name = UUID().uuidString
        let backing = UserDefaults(suiteName: name)!
        defer { backing.removePersistentDomain(forName: name); SalaryAccountSessionV2.configure { nil } }
        do {
            var identity = "uid:first"
            SalaryAccountSessionV2.configure { identity }
            let firstDefaults = SalaryAccountStorageV2.guarded(backing: backing)
            XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: "same", name: "Synthetic", siret: "12345678901234"), defaults: firstDefaults))
            let expected = SalaryPayslipObservedStoreV2.read(companyId: "same", period: period, defaults: firstDefaults)
            identity = "uid:second"
            SalaryAccountSessionV2.authenticationChanged()
            let secondDefaults = SalaryAccountStorageV2.guarded(backing: backing)
            XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(.init(id: "same", name: "Synthetic", siret: "12345678901234"), defaults: secondDefaults))
            XCTAssertFalse(SalaryPayslipObservedStoreV2.saveDetail(document(), companyId: "same", period: period, confirmed: true, expected: expected, defaults: secondDefaults))
            XCTAssertNil(SalaryPayslipObservedStoreV2.read(companyId: "same", period: period, defaults: secondDefaults).record)
        }
    }
    func testSettlementSourcePeriodCannotWriteAnotherMonth() {
        withStore { defaults in
            let record = SalaryMonthlyPayrollSettlementV2.Record(companyId: "company", period: period, transportReimbursement: 10, garnishment: nil, otherAddition: nil, otherDeduction: nil, netSocialObserved: nil, netPaidObserved: nil, source: "Synthetic statement paid next month", confirmedAt: 1_790_000_000_000, adjustmentsComplete: false)
            let expected = SalaryMonthlyPayrollSettlementV2.read(companyId: "company", period: period, defaults: defaults)
            XCTAssertFalse(SalaryMonthlyPayrollSettlementV2.saveFromSource(record, sourcePeriod: nil, confirmed: true, expected: expected, defaults: defaults))
            XCTAssertFalse(SalaryMonthlyPayrollSettlementV2.saveFromSource(record, sourcePeriod: YearMonthV2(year: 2026, month: 9), confirmed: true, expected: expected, defaults: defaults))
            XCTAssertNil(SalaryMonthlyPayrollSettlementV2.read(companyId: "company", period: period, defaults: defaults).record)
            XCTAssertTrue(SalaryMonthlyPayrollSettlementV2.saveFromSource(record, sourcePeriod: period, confirmed: true, expected: expected, defaults: defaults))
        }
    }
}

#if canImport(UIKit) && canImport(PDFKit)
import PDFKit

extension SalaryObservedDocumentV2Tests {
    func testNativeObservedPDFRendersAllRowsWithoutAnyEstimate() throws {
        let month = YearMonthV2(year: 2026, month: 8)!
        let company = SalaryCompanyV2(id: "pdf-company", name: "Synthetic PDF employer", siret: "12345678901234")
        let amounts: [String: Double] = ["socialGross": 2000]
        let lines = (0..<150).map { index in
            SalaryObservedDocumentV2.Line(label: "Synthetic source row \(index)", base: "140", employerBase: "160", employeeRate: "2.3456", employeeAmount: "-12.34", employerAmount: "0")
        }
        let document = SalaryObservedDocumentV2.Document(sourcePeriod: month, source: String(repeating: "Source ", count: 60), confirmedAt: Date(), lines: lines, aggregateFingerprint: SalaryObservedDocumentV2.fingerprint(amounts: amounts, excerpts: [:]))
        let record = SalaryPayslipObservedStoreV2.Record(companyId: company.id, period: month, amounts: amounts, excerpts: [:], confirmedAt: Date(), detailedDocument: document)
        let data = SalaryV2PdfExporter.renderObserved(record: record, company: company)
        let pdf = try XCTUnwrap(PDFDocument(data: data))
        let text = (0..<pdf.pageCount).compactMap { pdf.page(at: $0)?.string }.joined(separator: "\n")
        XCTAssertGreaterThan(pdf.pageCount, 1)
        XCTAssertTrue(text.contains("BULLETIN CONSTATÉ"))
        XCTAssertTrue(text.contains("Synthetic source row 0"))
        XCTAssertTrue(text.contains("Synthetic source row 149"))
        XCTAssertTrue(text.contains("-12,34"))
        XCTAssertTrue(text.contains("Non renseigné"))
        XCTAssertFalse(text.contains("ESTIMATION DE SALAIRE"))
    }
}
#endif
