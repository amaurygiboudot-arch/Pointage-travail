import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryCompanySelectionV2Tests: XCTestCase {
    private func stored(_ ids: [String], reliable: Bool = true) -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: ids.map { SalaryCompanyV2(id: $0, name: "Entreprise \($0)", siret: "") },
            reliable: reliable,
            repairedFromBackup: false,
            warnings: reliable ? [] : [SalaryCompanyStoreV2.storageWarning]
        )
    }

    func testNoCompanyMeansNoSelection() {
        XCTAssertNil(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: nil,
                selectionWasExplicit: false,
                companies: stored([])
            )
        )
    }

    func testSingleCompanyIsAutoSelectedBecauseChoiceIsUnambiguous() {
        XCTAssertEqual(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: nil,
                selectionWasExplicit: false,
                companies: stored(["company-a"])
            ),
            "company-a"
        )
    }

    func testMultipleCompaniesNeverCreateImplicitSelection() {
        XCTAssertNil(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: nil,
                selectionWasExplicit: false,
                companies: stored(["company-a", "company-b"])
            )
        )
    }

    func testAutoSelectionFromSingleCompanyIsClearedWhenSecondCompanyAppears() {
        XCTAssertNil(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: "company-a",
                selectionWasExplicit: false,
                companies: stored(["company-a", "company-b"])
            )
        )
    }

    func testExplicitSelectionIsPreservedWhileStillConfirmed() {
        XCTAssertEqual(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: "company-b",
                selectionWasExplicit: true,
                companies: stored(["company-a", "company-b"])
            ),
            "company-b"
        )
    }

    func testRemovedOrUnknownExplicitSelectionIsCleared() {
        XCTAssertNil(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: "company-c",
                selectionWasExplicit: true,
                companies: stored(["company-a", "company-b"])
            )
        )
    }

    func testUnreliableCompanyStoreClearsAnySelection() {
        XCTAssertNil(
            SalaryCompanySelectionV2.reconcile(
                currentCompanyId: "company-a",
                selectionWasExplicit: true,
                companies: stored(["company-a"], reliable: false)
            )
        )
    }

    func testExplicitSelectionRejectsUnknownCompany() {
        XCTAssertNil(
            SalaryCompanySelectionV2.explicitSelection(
                requestedCompanyId: "company-c",
                companies: stored(["company-a", "company-b"])
            )
        )
    }

    func testExplicitSelectionAcceptsOnlyExactConfirmedCompany() {
        XCTAssertEqual(
            SalaryCompanySelectionV2.explicitSelection(
                requestedCompanyId: " company-b ",
                companies: stored(["company-a", "company-b"])
            ),
            "company-b"
        )
    }

    func testMutationTargetIsPreservedOnlyWhenEmployerDoesNotChange() {
        XCTAssertEqual(
            SalaryCompanySelectionV2.stableMutationTarget(
                beforeReconciliation: "company-a",
                afterReconciliation: "company-a"
            ),
            "company-a"
        )
    }

    func testMutationTargetRejectsAutomaticSwitchToDifferentEmployer() {
        XCTAssertNil(
            SalaryCompanySelectionV2.stableMutationTarget(
                beforeReconciliation: "company-a",
                afterReconciliation: "company-b"
            )
        )
    }

    func testMutationTargetRejectsSelectionCreatedOnlyDuringReconciliation() {
        XCTAssertNil(
            SalaryCompanySelectionV2.stableMutationTarget(
                beforeReconciliation: nil,
                afterReconciliation: "company-b"
            )
        )
    }
}
