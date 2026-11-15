import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif
final class SalaryPayslipProfileDraftParserV2Tests: XCTestCase {
    func testOnlyExplicitGrossHourlyRateWithSource() {
        let draft = SalaryPayslipProfileDraftParserV2.hourlyRate(pages: ["Taux horaire brut : 13,6300 €/h"])
        XCTAssertEqual(draft?.grossHourlyRate, Decimal(string: "13.6300"))
        XCTAssertTrue(draft?.source.contains("Page 1, ligne 1") == true)
    }
    func testNoMonthlyOrNetRateInference() {
        for text in ["Salaire mensuel brut 2 000,00", "Salaire de base 151,67 13,63 2 067,26", "Taux horaire net 11,00", "Taux horaire 13,63"] {
            XCTAssertNil(SalaryPayslipProfileDraftParserV2.hourlyRate(pages: [text]))
        }
    }
    func testAmbiguityNegativeAndCumulsInvalidate() {
        for bad in ["Taux horaire brut - 13,63", "Taux horaire brut −13,63", "Taux horaire brut (13,63)", "Taux horaire brut 0,00", "Cumul taux horaire brut 13,63", "Taux horaire brut 13,63 14,00", "Taux horaire brut 13,63"] {
            XCTAssertNil(SalaryPayslipProfileDraftParserV2.hourlyRate(pages: ["Taux horaire brut 13,63\n" + bad]))
        }
    }
    func testBoundedInputFailsClosed() {
        XCTAssertNil(SalaryPayslipProfileDraftParserV2.hourlyRate(pages: Array(repeating: "", count: 11)))
        XCTAssertNil(SalaryPayslipProfileDraftParserV2.hourlyRate(pages: [String(repeating: "x", count: 30_001)]))
    }
}
