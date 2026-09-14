import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryV2ReferenceContractTests: XCTestCase {
    private let month = YearMonthV2(year: 2026, month: 9)!

    private func emptyRecords(reliable: Bool = true) -> CompanyBenefitInKindContractV2.ReadResult {
        .init(records: [], reliable: reliable, warnings: reliable ? [] : ["Stockage incohérent"])
    }

    private func confirmations(_ periods: [YearMonthV2], reliable: Bool = true) -> CompanyBenefitInKindContractV2.ConfirmationReadResult {
        .init(
            confirmations: periods.map { .init(period: $0, source: "Bulletin du mois") },
            reliable: reliable,
            warnings: reliable ? [] : ["Confirmations incohérentes"]
        )
    }

    private func result(
        benefits: CompanyBenefitInKindContractV2.Snapshot,
        cashGross: Double = 2_500,
        extraWarnings: [String] = []
    ) -> SalaryReferenceContractV2 {
        SalaryReferenceContractV2.build(
            cashGross: cashGross,
            benefits: benefits,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050,
            additionalWarnings: extraWarnings
        )
    }

    func testUnconfirmedZeroDoesNotBecomeReliableSocialGross() {
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: emptyRecords(),
            confirmations: confirmations([]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertFalse(benefits.reliable)
        XCTAssertFalse(salary.grossReliable)
        XCTAssertFalse(salary.complete)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(salary))
        XCTAssertNil(SalaryReferenceContractV2.taxable(salary))
        XCTAssertTrue(salary.warnings.contains { $0.hasPrefix("Brut social :") })
    }

    func testPositiveButUnconfirmedListRemainsPartialSubtotal() {
        let record = CompanyBenefitInKindContractV2.Record(
            id: "meal",
            label: "Repas",
            grossValue: 200,
            kind: .monthly,
            effectiveFrom: month,
            effectiveTo: nil,
            paymentMonth: nil
        )
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: .init(records: [record], reliable: true, warnings: []),
            confirmations: confirmations([]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertEqual(salary.gross, 2_700, accuracy: 0.001)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
    }

    func testConfirmedAbsenceAuthorizesCashGrossAsSocialGross() {
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: emptyRecords(),
            confirmations: confirmations([month]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertTrue(benefits.reliable)
        XCTAssertTrue(salary.grossReliable)
        XCTAssertEqual(SalaryReferenceContractV2.socialGross(salary) ?? -1, 2_500, accuracy: 0.001)
    }

    func testConfirmedBenefitsAreIncludedOnceAndDeductedAsBenefitValue() {
        let record = CompanyBenefitInKindContractV2.Record(
            id: "vehicle",
            label: "Véhicule",
            grossValue: 200,
            kind: .monthly,
            effectiveFrom: month,
            effectiveTo: nil,
            paymentMonth: nil
        )
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: .init(records: [record], reliable: true, warnings: []),
            confirmations: confirmations([month]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertEqual(SalaryReferenceContractV2.socialGross(salary) ?? -1, 2_700, accuracy: 0.001)
        XCTAssertEqual(salary.benefitsInKindDeduction, 200, accuracy: 0.001)
    }

    func testConfirmationFromAnotherMonthDoesNotUnlockGross() {
        let previous = YearMonthV2(year: 2026, month: 8)!
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: emptyRecords(),
            confirmations: confirmations([previous]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertFalse(benefits.reliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
    }

    func testCorruptedStorageStaysBlockedEvenWithMonthConfirmation() {
        let records = CompanyBenefitInKindContractV2.decodeRecords("{corrompu")
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: records,
            confirmations: confirmations([month]),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertFalse(records.reliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(salary))
    }

    func testInvalidBenefitValuesNeverBecomeConfirmedGross() {
        for invalid in [Double.nan, Double.infinity, -1.0] {
            let benefits = CompanyBenefitInKindContractV2.Snapshot(
                applied: [],
                totalGross: invalid,
                reliable: true,
                warnings: []
            )
            let salary = result(benefits: benefits)

            XCTAssertFalse(salary.grossReliable)
            XCTAssertFalse(salary.complete)
            XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
        }
    }

    func testNetReferenceCannotBypassBlockedSocialGrossEvenIfMarkedComplete() {
        let salary = SalaryReferenceContractV2(
            gross: 2_500,
            grossReliable: false,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050,
            complete: true,
            warnings: [],
            benefitsInKindDeduction: 0
        )

        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(salary))
        XCTAssertNil(SalaryReferenceContractV2.taxable(salary))
    }

    func testMissingCompanyNeverProvidesFalseSocialGross() {
        let benefits = CompanyBenefitInKindContractV2.resolve(
            records: CompanyBenefitInKindContractV2.companyUnavailableReadResult(),
            confirmations: CompanyBenefitInKindContractV2.companyUnavailableConfirmationResult(),
            period: month
        )
        let salary = result(benefits: benefits)

        XCTAssertFalse(salary.grossReliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(salary))
    }
}
