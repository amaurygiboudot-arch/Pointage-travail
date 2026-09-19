import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerAtMpContributionV2Tests: XCTestCase {
    func testConfirmedRateCalculatesEmployerAmountOnly() {
        let result = EmployerAtMpContributionV2.calculate(
            gross: 2_500,
            confirmedRate: 0.0208
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.rate ?? -1, 0.0208, accuracy: 0.000_001)
        XCTAssertEqual(result.baseGross, 2_500, accuracy: 0.001)
        XCTAssertEqual(result.employerAmount ?? -1, 52, accuracy: 0.001)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testMissingRateNeverInventsEmployerAmount() {
        let result = EmployerAtMpContributionV2.calculate(
            gross: 2_500,
            confirmedRate: nil
        )

        XCTAssertFalse(result.complete)
        XCTAssertNil(result.rate)
        XCTAssertNil(result.employerAmount)
        XCTAssertTrue(result.warnings.contains { $0.contains("coût employeur incomplet") })
    }

    func testInvalidRateNeverInventsEmployerAmount() {
        for rate in [-0.01, 1.01, .infinity, .nan] {
            let result = EmployerAtMpContributionV2.calculate(
                gross: 2_500,
                confirmedRate: rate
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.rate)
            XCTAssertNil(result.employerAmount)
        }
    }

    func testInvalidGrossNeverInventsEmployerAmount() {
        for gross in [-50, .infinity, .nan] {
            let result = EmployerAtMpContributionV2.calculate(
                gross: gross,
                confirmedRate: 0.0208
            )

            XCTAssertFalse(result.complete)
            XCTAssertEqual(result.rate ?? -1, 0.0208, accuracy: 0.000_001)
            XCTAssertEqual(result.baseGross, 0, accuracy: 0.001)
            XCTAssertNil(result.employerAmount)
            XCTAssertTrue(result.warnings.contains { $0.contains("assiette brute invalide") })
        }
    }
}
