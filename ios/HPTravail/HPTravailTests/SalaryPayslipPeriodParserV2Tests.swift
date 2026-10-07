import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif
final class SalaryPayslipPeriodParserV2Tests: XCTestCase {
    func testExplicitMonthlyPeriodWithProvenance() {
        let period = SalaryPayslipPeriodParserV2.parse(pages: ["Période de paie : 09/2026"])
        XCTAssertEqual(period?.year, 2026)
        XCTAssertEqual(period?.month, 9)
        XCTAssertTrue(period?.source.contains("Page 1, ligne 1") == true)
    }
    func testPaymentAndBareDatesNeverBecomePeriod() {
        XCTAssertNil(SalaryPayslipPeriodParserV2.parse(pages: ["Date de paiement : 10/2026\n09/2026\nDate : 30/09/2026"]))
    }
    func testConflictingOrMalformedLabelInvalidatesValidPeriod() {
        for bad in ["Mois : 10/2026", "Période : 13/2026", "Période : septembre 2026", "Mois : 01/09/2026", "Mois : 09/2026 cumul"] {
            XCTAssertNil(SalaryPayslipPeriodParserV2.parse(pages: ["Période : 09/2026\n" + bad]))
        }
    }
    func testRepeatedSamePeriodIsNotConflict() {
        XCTAssertEqual(SalaryPayslipPeriodParserV2.parse(pages: ["Mois : 9/2026", "Période du bulletin : 09/2026"])?.month, 9)
    }
}
