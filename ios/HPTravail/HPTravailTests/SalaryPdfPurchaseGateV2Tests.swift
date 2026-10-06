import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPdfPurchaseGateV2Tests: XCTestCase {
    func testUnsupportedPurchasesBlockPdfGeneration() {
        XCTAssertFalse(SalaryPdfPurchaseGateV2.isAvailable)
        XCTAssertThrowsError(try SalaryPdfPurchaseGateV2.requireVerifiedPurchase()) { error in
            guard case SalaryPdfPurchaseGateV2.AccessError.purchaseUnavailable = error else {
                return XCTFail("Expected an explicit unavailable purchase error")
            }
            XCTAssertEqual(error.localizedDescription, SalaryPdfPurchaseGateV2.unavailableMessage)
        }
    }
}
