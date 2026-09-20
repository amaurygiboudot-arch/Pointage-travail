import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class ClockInEmployerResolverV2Tests: XCTestCase {
    private func companies(
        reliable: Bool = true,
        ids: [String]
    ) -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: ids.map { SalaryCompanyV2(id: $0, name: $0, siret: "") },
            reliable: reliable,
            repairedFromBackup: false,
            warnings: reliable ? [] : [SalaryCompanyStoreV2.storageWarning]
        )
    }

    func testNilEmployerRemainsExplicitlyUnassignedEvenIfCompanyStoreIsUnavailable() {
        XCTAssertEqual(
            ClockInEmployerResolverV2.resolve(
                requestedEmployerId: nil,
                companies: companies(reliable: false, ids: ["company-a"])
            ),
            .unassigned
        )
    }

    func testConfirmedEmployerIsAcceptedAndTrimmed() {
        XCTAssertEqual(
            ClockInEmployerResolverV2.resolve(
                requestedEmployerId: "  company-a  ",
                companies: companies(ids: ["company-a", "company-b"])
            ),
            .employer("company-a")
        )
    }

    func testUnknownEmployerIsRejected() {
        XCTAssertEqual(
            ClockInEmployerResolverV2.resolve(
                requestedEmployerId: "company-z",
                companies: companies(ids: ["company-a"])
            ),
            .rejected
        )
    }

    func testEmployerCannotBeAcceptedFromUnreliableCompanyStore() {
        XCTAssertEqual(
            ClockInEmployerResolverV2.resolve(
                requestedEmployerId: "company-a",
                companies: companies(reliable: false, ids: ["company-a"])
            ),
            .rejected
        )
    }

    func testBlankEmployerIdIsRejectedInsteadOfBecomingUnassignedSilently() {
        XCTAssertEqual(
            ClockInEmployerResolverV2.resolve(
                requestedEmployerId: "   ",
                companies: companies(ids: ["company-a"])
            ),
            .rejected
        )
    }
}
