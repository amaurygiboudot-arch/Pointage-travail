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
}
