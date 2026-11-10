import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif
final class SalaryPayslipImportParserV2Tests: XCTestCase {
    func testFrenchAmountsAndProvenance() {
        let result = SalaryPayslipImportParserV2.parse(pages: ["Total brut 2 500,12\nNet à payer avant impôt 1 950,00\nNet imposable 2 010,20\nPrélèvement à la source 40,00\nNet à payer 1 910,00"])
        XCTAssertEqual(result.count, 5)
        XCTAssertEqual(result.first?.amount, Decimal(string: "2500.12"))
        XCTAssertTrue(result.first?.source.contains("Page 1, ligne 1") == true)
    }
    func testRejectsAmbiguousRowsDuplicatesCumulsAndNegativeValues() {
        let result = SalaryPayslipImportParserV2.parse(pages: ["Cumul total brut 20 000,00\nTotal brut 2 500,00\nTotal brut 2 600,00\nNet imposable -200,00\nPrélèvement à la source 2 000,00 5,00% 100,00\nNet à payer 1 900,00 20 000,00"])
        XCTAssertTrue(result.isEmpty)
    }
    func testHourlyBaseAndNonTaxBeforeWithholdingsAreNotTotals() {
        XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: ["Salaire brut horaire 13,63\nSalaire brut de base 2 000,00\nNet à payer avant retenue 1 900,00"]).isEmpty)
    }
    func testInvalidRecognizedLineBlocksOtherwiseValidCandidate() {
        XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: ["Total brut 2 500,00\nTotal brut -100,00\nNet imposable 2 000,00\nNet imposable 2 000,00 100,00"]).isEmpty)
    }
    func testAlternateNegativeNotationBlocksFieldIncludingValidRow() {
        for notation in ["−100,00", "﹣100,00", "－100,00", "(100,00)", "- 100,00", "–100,00", "—100,00", "-\u{00a0}100,00"] {
            XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: ["Total brut 2 500,00\nTotal brut \(notation)"]).isEmpty, notation)
        }
    }
    func testDoesNotInventMissingFieldsOrReadNextLine() {
        XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: ["Total brut\n2 500,00\nNet social 1 900,00"]).isEmpty)
    }
    func testUnrepresentableRecognizedAmountRejectsWholeDraftInsteadOfPartialSuccess() {
        for amount in ["100000000000000000,00", "90071992547409,91", "90071992547409,92"] {
            let pages = ["Total brut \(amount)\nNet imposable 2000,00"]
            XCTAssertTrue(SalaryPayslipImportParserV2.parseChecked(pages: pages).hasUnrepresentableAmounts)
            XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: pages).isEmpty)
        }
        XCTAssertFalse(SalaryPayslipImportParserV2.parseChecked(pages: ["Total brut 90071992547409,90"]).hasUnrepresentableAmounts)
        XCTAssertEqual(SalaryPayslipImportParserV2.parse(pages: ["Total brut 90071992547409,90"]).count, 1)
    }

    func testUnrelatedAnnualOrAmbiguousNumbersDoNotBecomeMonetaryBoundaryErrors() {
        let result = SalaryPayslipImportParserV2.parseChecked(pages: [
            "Cumul total brut 100000000000000000,00\nRéférence 100000000000000000,00\nTotal brut 2500,00\nPrélèvement à la source 100000000000000000,00 5,00% 100,00"
        ])
        XCTAssertFalse(result.hasUnrepresentableAmounts)
        XCTAssertEqual(result.proposals.map(\.field), [.gross])
    }

    func testMalformedWholeNumericTokensRejectEntireImport() {
        for token in ["12 34,56", "1000 000,00", "100000000000000000 000,00", "12\u{00a0}34,56", "12\u{202f}34,56", "123,45,67", "1.234,56", "123,456", ".123,45", ",123,45", ".50", "+ 123,45", "123,45.", "++123,45", "12\t34,56", "1e17 000,00", "1,00e17", "2500,00 ; 1e17 000,00", "1/123,45", "/123,45", "123,45/", "123,45/67", "1'234,56", "1’234,56", "1_234,56", "1:234,56", "-+123,45", "- +123,45", "123,45-", "123,45−", "2500,00 ; 1/123,45"] {
            let pages = ["Total brut 2500,00\nTotal brut \(token)\nNet imposable 2000,00"]
            XCTAssertTrue(SalaryPayslipImportParserV2.parseChecked(pages: pages).hasUnrepresentableAmounts, token)
            XCTAssertTrue(SalaryPayslipImportParserV2.parse(pages: pages).isEmpty, token)
        }
    }

    func testCompleteGroupedTokensAndAmbiguousColumnsKeepTheirMeaning() {
        for token in ["1234,56", "1 234,56", "1\u{00a0}234,56", "1\u{202f}234,56", "+1 234,56", "1 234.56"] {
            let result = SalaryPayslipImportParserV2.parseChecked(pages: ["Total brut \(token) €"])
            XCTAssertFalse(result.hasUnrepresentableAmounts, token)
            XCTAssertEqual(result.proposals.first?.amount, Decimal(string: "1234.56"), token)
        }
        XCTAssertEqual(SalaryPayslipImportParserV2.parse(pages: ["Total brut : 1234,56 €"]).first?.amount, Decimal(string: "1234.56"))
        for row in ["Net à payer 1 900,00 20 000,00", "Net à payer 1900,00 20000,00", "Cumul total brut 12 34,56", "Prélèvement à la source 12 34,56 5,00% 100,00"] {
            let result = SalaryPayslipImportParserV2.parseChecked(pages: ["Total brut 2500,00\n" + row])
            XCTAssertFalse(result.hasUnrepresentableAmounts, row)
            XCTAssertEqual(result.proposals.map(\.field), [.gross], row)
        }
    }

}
