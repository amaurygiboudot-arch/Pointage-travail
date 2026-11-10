import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployeeNetProjectionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    private func salaryPeriod(_ year: Int = 2026, _ month: Int = 4) -> YearMonthV2 {
        YearMonthV2(year: year, month: month)!
    }

    private func deductionPeriod(_ year: Int = 2026, _ month: Int = 4) -> Resolver.YearMonth {
        Resolver.YearMonth(year: year, month: month)!
    }

    private func fullCeiling(_ year: Int = 2026) -> SocialSecurityCeilingV2.Snapshot {
        SocialSecurityCeilingV2.calculate(
            .init(
                period: salaryPeriod(year, 4),
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
            )
        )
    }

    private func benefits(total: Double = 0, reliable: Bool = true) -> CompanyBenefitInKindContractV2.Snapshot {
        CompanyBenefitInKindContractV2.Snapshot(
            applied: total > 0
                ? [.init(id: "benefit", label: "Avantage confirmé", grossValue: total)]
                : [],
            totalGross: total,
            reliable: reliable,
            warnings: reliable ? [] : ["Avantages en nature non confirmés"]
        )
    }

    private func record(
        id: String,
        kind: Resolver.Kind,
        amount: Double,
        period: Resolver.YearMonth
    ) -> Resolver.Record {
        Resolver.Record(
            id: id,
            kind: kind,
            amount: amount,
            effectiveFrom: period,
            effectiveTo: period,
            source: "Bulletin confirmé"
        )
    }

    private func deductions(
        period: Resolver.YearMonth,
        omitted: Set<Resolver.Kind> = []
    ) -> Resolver.Snapshot {
        let all: [(String, Resolver.Kind, Double)] = [
            ("mutual", .mutualEmployee, 42),
            ("provident", .providentEmployee, 18),
            ("transport", .transportEmployee, 0),
            ("employer-taxable", .employerProtectionTaxable, 8),
            ("employer-csg", .employerProtectionCsgCrdsBase, 12),
            ("provident-nondeductible", .employeeProvidentNonDeductible, 4)
        ]
        return Resolver.resolve(
            records: all.compactMap { item in
                let (id, kind, amount) = item
                return omitted.contains(kind)
                    ? nil
                    : record(id: id, kind: kind, amount: amount, period: period)
            },
            period: period
        )
    }

    private func input(
        cashGross: Double = 3_000,
        upstreamReliable: Bool = true,
        benefits: CompanyBenefitInKindContractV2.Snapshot? = nil,
        year: Int = 2026,
        ceiling: SocialSecurityCeilingV2.Snapshot? = nil,
        alsaceMoselle: Bool? = false,
        professionalStatus: String? = "NON_CADRE",
        protectionCategory: ProtectionCategoryV2.Result = ProtectionCategoryV2.noConventionOverride(),
        omittedDeductions: Set<Resolver.Kind> = [],
        incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot? = nil
    ) -> EmployeeNetProjectionV2.Input {
        let period = deductionPeriod(year, 4)
        return .init(
            cashGross: cashGross,
            upstreamGrossReliable: upstreamReliable,
            benefits: benefits ?? self.benefits(),
            year: year,
            ceiling: ceiling ?? fullCeiling(year),
            alsaceMoselleLocalRegime: alsaceMoselle,
            professionalStatus: professionalStatus,
            protectionCategory: protectionCategory,
            companyDeductions: deductions(period: period, omitted: omittedDeductions),
            period: period,
            incomeTaxRate: incomeTaxRate
        )
    }

    private func confirmedForfaitPayroll(
        monthlyGross: Double = 3_000,
        evidence: PayrollInputEvidenceV2 = .fullyConfirmed
    ) throws -> PayrollResultV2 {
        try PayrollEngineV2.calculate(
            contract: ContractV2(
                id: "contract-a",
                employerId: "company-a",
                type: .forfaitDays,
                contractualWeeklyMinutes: nil,
                grossHourlyRate: nil,
                hireDateEpochDay: 18_000,
                forfaitAnnualDays: 218,
                monthlyGrossSalary: monthlyGross
            ),
            weeks: [],
            rules: PayrollRulesV2(),
            evidence: evidence
        )
    }

    func testEmployerProjectionUsesSameGrossAndDoesNotChangeEmployeeNet() throws {
        let original = input()
        let withoutOwner = EmployeeNetProjectionV2.calculate(original)
        var owned = original
        owned.companyId = "company-a"
        let result = EmployeeNetProjectionV2.calculate(owned)
        let employer = try XCTUnwrap(result.employerContributions)
        XCTAssertEqual(employer.context.companyId, "company-a")
        XCTAssertEqual(employer.context.year, owned.period.year)
        XCTAssertEqual(employer.context.month, owned.period.month)
        XCTAssertEqual(employer.context.contributionGrossCents, PayrollMoneyV2.cents(result.contributionGross))
        XCTAssertNotNil(employer.knownSubtotalBeforeReductionsCents)
        XCTAssertNil(employer.knownSubtotalAfterReductionsCents)
        XCTAssertFalse(employer.coverageComplete)
        XCTAssertEqual(employer.lines.filter { $0.state == .known }.map(\.family), [.statutory, .complementaryRetirement])
        XCTAssertEqual(employer.lines.filter { $0.state == .missing }.count, 8)
        XCTAssertNil(withoutOwner.employerContributions?.knownSubtotalBeforeReductionsCents)
        XCTAssertEqual(result.netBeforeIncomeTax, withoutOwner.netBeforeIncomeTax)
        XCTAssertEqual(result.netTaxable, withoutOwner.netTaxable)
        XCTAssertEqual(result.incomeTax, withoutOwner.incomeTax)
        XCTAssertEqual(result.netAfterIncomeTax, withoutOwner.netAfterIncomeTax)
        XCTAssertEqual(result.warnings, withoutOwner.warnings)
        XCTAssertEqual(result.netBeforeIncomeTaxComplete, withoutOwner.netBeforeIncomeTaxComplete)
    }

    func testUnknownApecEligibilityKeepsProvedRetirementLinesAndDiagnostics() throws {
        let unknownAni = ProtectionCategoryV2.Result(aniCategory: .toConfirm, confirmed: false, source: "Fixture ANI inconnue")
        for original in [input(cashGross: 2500, professionalStatus: "CADRE", protectionCategory: unknownAni),
                         input(cashGross: 2500, professionalStatus: nil)] {
            let unowned = EmployeeNetProjectionV2.calculate(original)
            var owned = original
            owned.companyId = "company-a"
            let result = EmployeeNetProjectionV2.calculate(owned)
            let employer = try XCTUnwrap(result.employerContributions)
            let retirement = try XCTUnwrap(employer.lines.first { $0.family == .complementaryRetirement })
            XCTAssertEqual(retirement.state, .known)
            XCTAssertEqual(retirement.amountCents, 15025) // T1 118 + CEG 32.25, synthetic existing-catalog fixture.
            XCTAssertFalse(result.complementaryRetirement.lines.contains { $0.id == "apec" })
            XCTAssertTrue(employer.warnings.contains { $0.contains("APEC") })
            XCTAssertTrue(employer.warnings.contains { $0.contains("partielle") })
            XCTAssertFalse(employer.coverageComplete)
            XCTAssertEqual(result.netBeforeIncomeTax, unowned.netBeforeIncomeTax)
            XCTAssertEqual(result.netTaxable, unowned.netTaxable)
            XCTAssertEqual(result.incomeTax, unowned.incomeTax)
            XCTAssertEqual(result.netAfterIncomeTax, unowned.netAfterIncomeTax)
            XCTAssertEqual(result.netBeforeIncomeTaxComplete, unowned.netBeforeIncomeTaxComplete)
            XCTAssertEqual(result.warnings, unowned.warnings)
        }
        var known = input(cashGross: 2500, professionalStatus: "CADRE",
                          protectionCategory: .init(aniCategory: .article2_1, confirmed: true, source: "Fixture ANI confirmée"))
        known.companyId = "company-a"
        let result = EmployeeNetProjectionV2.calculate(known)
        XCTAssertEqual(result.employerContributions?.lines.first { $0.family == .complementaryRetirement }?.amountCents, 15115)
        XCTAssertEqual(result.complementaryRetirement.lines.filter { $0.id == "agirc_t1" }.count, 1)
        XCTAssertEqual(result.complementaryRetirement.lines.filter { $0.id == "ceg_t1" }.count, 1)
        XCTAssertEqual(result.complementaryRetirement.lines.filter { $0.id == "apec" }.count, 1)
        XCTAssertFalse(result.employerContributions?.warnings.contains { $0.contains("APEC non appliquée") } ?? true)
    }

    func testEmployerReferenceAndWorkspaceRejectAnotherCompanyOrMonth() throws {
        var owned = input()
        owned.companyId = "company-a"
        let projection = EmployeeNetProjectionV2.calculate(owned)
        let payroll = try confirmedForfaitPayroll()
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 4))
        let reference = SalaryReferenceContractV2.buildFromPayroll(payroll: payroll, benefits: owned.benefits,
            projection: projection, expectedCompanyId: "company-a", expectedPeriod: period)
        XCTAssertNotNil(reference.employerContributions)
        let workspace = SalaryWorkspaceResolverV2.resolve(period: period, reference: reference, expectedCompanyId: "company-a")
        XCTAssertNotNil(workspace.employerContributions)
        let mismatch = SalaryWorkspaceResolverV2.resolve(period: period, reference: reference, expectedCompanyId: "company-b")
        XCTAssertNil(mismatch.employerContributions)
        XCTAssertFalse(mismatch.employerWarnings.isEmpty)
        XCTAssertEqual(mismatch.warnings, workspace.warnings)
        XCTAssertEqual(mismatch.netBeforeIncomeTax, workspace.netBeforeIncomeTax)
        XCTAssertNil(SalaryWorkspaceResolverV2.resolve(period: YearMonthV2(year: 2026, month: 5)!, reference: reference,
                                                       expectedCompanyId: "company-a").employerContributions)
        let wrongReference = SalaryReferenceContractV2.buildFromPayroll(payroll: payroll, benefits: owned.benefits,
            projection: projection, expectedCompanyId: "company-b", expectedPeriod: period)
        XCTAssertNil(wrongReference.employerContributions)
        XCTAssertFalse(wrongReference.employerWarnings.isEmpty)
        XCTAssertEqual(wrongReference.warnings, reference.warnings)
        XCTAssertEqual(wrongReference.complete, reference.complete)
    }

    func testCompleteInputsPublishBeforeTaxAndTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(benefits: benefits(total: 100))
        )

        XCTAssertTrue(result.grossReliable)
        XCTAssertTrue(result.netBeforeIncomeTaxComplete)
        XCTAssertTrue(result.netTaxableComplete)
        XCTAssertEqual(result.contributionGross, 3_100, accuracy: 0.001)
        XCTAssertNotNil(result.netBeforeIncomeTax)
        XCTAssertNotNil(result.netTaxable)
    }

    func testCeilingFromAnotherMonthBlocksCanonicalNet() {
        let mayCeiling = SocialSecurityCeilingV2.calculate(
            .init(
                period: salaryPeriod(2026, 5),
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
            )
        )
        let result = EmployeeNetProjectionV2.calculate(input(ceiling: mayCeiling))

        XCTAssertTrue(result.grossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains { $0.contains("plafond SS ou année de barème hors période de paie") })
    }

    func testBenefitsIncreaseContributionGrossButNotCashGross() {
        let withoutBenefit = EmployeeNetProjectionV2.calculate(input())
        let withBenefit = EmployeeNetProjectionV2.calculate(input(benefits: benefits(total: 100)))

        XCTAssertEqual(withoutBenefit.cashGross, 3_000, accuracy: 0.001)
        XCTAssertEqual(withBenefit.cashGross, 3_000, accuracy: 0.001)
        XCTAssertEqual(withBenefit.contributionGross - withoutBenefit.contributionGross, 100, accuracy: 0.001)
        XCTAssertGreaterThan(withBenefit.statutory.employeeDeductions, withoutBenefit.statutory.employeeDeductions)
        XCTAssertGreaterThan(withBenefit.complementaryRetirement.employeeDeductions, withoutBenefit.complementaryRetirement.employeeDeductions)
    }

    func testNonDeductibleProvidentShareIsNotSubtractedTwiceFromCashNet() {
        let result = EmployeeNetProjectionV2.calculate(input())
        XCTAssertEqual(result.companyCashDeductions.deductions.reduce(0) { $0 + $1.amount }, 60, accuracy: 0.001)
        XCTAssertEqual(result.knownNetBeforeIncomeTax, 2313.62, accuracy: 0.0001)
        XCTAssertEqual(result.netBeforeIncomeTax ?? -1, 2313.62, accuracy: 0.0001)
    }

    func testNonDeductibleProvidentShareIsReintegratedOnlyIntoTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(input())
        XCTAssertEqual(result.netTaxable ?? -1, 2411.45, accuracy: 0.0001)
    }

    func testMissingTaxOnlyDataKeepsBeforeTaxNetButBlocksTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.employeeProvidentNonDeductible])
        )

        XCTAssertTrue(result.netBeforeIncomeTaxComplete)
        XCTAssertNotNil(result.netBeforeIncomeTax)
        XCTAssertFalse(result.netTaxableComplete)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains { $0.contains("Net imposable incomplet") })
    }

    func testMissingCsgProtectionBaseBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.employerProtectionCsgCrdsBase])
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("CSG/CRDS") })
    }

    func testMissingCashDeductionBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.transportEmployee])
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("transport") })
    }

    func testUnknownProfessionalStatusBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(input(professionalStatus: nil))

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("statut professionnel") })
    }

    func testUnconfirmedAniCategoryBlocksBeforeTaxNet() {
        let category = ProtectionCategoryV2.Result(
            aniCategory: .article2_1,
            confirmed: false,
            source: "Classification à confirmer"
        )
        let result = EmployeeNetProjectionV2.calculate(
            input(professionalStatus: "CADRE", protectionCategory: category)
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("catégorie ANI") })
    }

    func testUnreliableBenefitsBlockAllFinalNetPublication() {
        let result = EmployeeNetProjectionV2.calculate(
            input(benefits: benefits(total: 0, reliable: false))
        )

        XCTAssertFalse(result.grossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
    }


    func testCanonicalNetUsesCentRoundedContributionLines() {
        let result = EmployeeNetProjectionV2.calculate(input())
        XCTAssertEqual(result.statutory.employeeDeductions, 506.08, accuracy: 0.0001)
        XCTAssertEqual(result.complementaryRetirement.employeeDeductions, 120.30, accuracy: 0.0001)
        XCTAssertEqual(result.knownNetBeforeIncomeTax, 2313.62, accuracy: 0.0001)
        XCTAssertEqual(result.netBeforeIncomeTax ?? -1, 2313.62, accuracy: 0.0001)
    }

    func testTaxableNetKeepsNegativeUnclampedPreTaxBalance() {
        let result = EmployeeNetProjectionV2.calculate(
            input(cashGross: 0, benefits: benefits(total: 1_000))
        )
        XCTAssertEqual(result.knownNetBeforeIncomeTax, 0, accuracy: 0.0001)
        XCTAssertEqual(result.netTaxable ?? -1, 771.27, accuracy: 0.0001)
    }

    func testConfirmedIncomeTaxRatePublishesTaxAndNetAfterTax() {
        let rate = CompanyIncomeTaxRateResolverV2.Snapshot(
            rate: 0.032,
            ratePercent: 3.2,
            source: "Bulletin confirmé",
            hasDatedRecords: true,
            reliable: true,
            warnings: []
        )
        let result = EmployeeNetProjectionV2.calculate(input(incomeTaxRate: rate))
        XCTAssertEqual(result.incomeTax ?? -1, 77.17, accuracy: 0.0001)
        XCTAssertEqual(result.netAfterIncomeTax ?? -1, 2236.45, accuracy: 0.0001)
    }

    func testConfirmedIncomeTaxRateReachesPresentationAdapter() {
        let rate = CompanyIncomeTaxRateResolverV2.Snapshot(
            rate: 0.032,
            ratePercent: 3.2,
            source: "Bulletin confirmé",
            hasDatedRecords: true,
            reliable: true,
            warnings: []
        )
        let projection = EmployeeNetProjectionV2.calculate(input(incomeTaxRate: rate))
        let presentation = SalaryNetPresentationV2.fromProjection(projection)

        XCTAssertNotNil(projection.netAfterIncomeTax)
        XCTAssertEqual(presentation.secondaryLabel, "Net après impôt")
        XCTAssertNotNil(presentation.secondaryAmount)
        XCTAssertEqual(
            presentation.secondaryAmount!,
            projection.netAfterIncomeTax!,
            accuracy: 0.0001
        )
    }

    func testMissingIncomeTaxRateFailsClosedAfterTaxOnly() {
        let result = EmployeeNetProjectionV2.calculate(input())

        XCTAssertNotNil(result.netBeforeIncomeTax)
        XCTAssertNotNil(result.netTaxable)
        XCTAssertNil(result.incomeTax)
        XCTAssertNil(result.netAfterIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("taux personnel daté") })
    }

    func testInvalidIncomeTaxRateFailsClosedWithWarning() {
        let rate = CompanyIncomeTaxRateResolverV2.Snapshot(
            rate: 1.2,
            ratePercent: 120,
            source: "Snapshot invalide",
            hasDatedRecords: true,
            reliable: true,
            warnings: []
        )
        let result = EmployeeNetProjectionV2.calculate(input(incomeTaxRate: rate))

        XCTAssertNil(result.incomeTax)
        XCTAssertNil(result.netAfterIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("indisponible ou invalide") })
    }

    func testUnsupportedYearDoesNotInventFinalNet() {
        let result = EmployeeNetProjectionV2.calculate(input(year: 2027))

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains { $0.contains("barèmes nationaux non intégrés") })
    }
    func testCanonicalReferenceBridgeUsesPayrollThenEmployeeProjection() throws {
        let payroll = try confirmedForfaitPayroll()
        let monthBenefits = benefits()
        let projection = EmployeeNetProjectionV2.calculate(
            input(
                cashGross: payroll.grossEstimate,
                upstreamReliable: payroll.grossReliable,
                benefits: monthBenefits
            )
        )

        let reference = SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: monthBenefits,
            projection: projection
        )

        XCTAssertTrue(payroll.grossReliable)
        XCTAssertTrue(projection.grossReliable)
        XCTAssertTrue(projection.netBeforeIncomeTaxComplete)
        XCTAssertTrue(reference.grossReliable)
        XCTAssertTrue(reference.complete)
        XCTAssertEqual(
            reference.netBeforeIncomeTax,
            projection.netBeforeIncomeTax ?? -1,
            accuracy: 0.0001
        )
    }

    func testCanonicalReferenceBridgeBlocksMismatchedGrossChain() throws {
        let payroll = try confirmedForfaitPayroll()
        let monthBenefits = benefits()
        let projection = EmployeeNetProjectionV2.calculate(
            input(
                cashGross: payroll.grossEstimate - 100,
                upstreamReliable: true,
                benefits: monthBenefits
            )
        )

        let reference = SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: monthBenefits,
            projection: projection
        )

        XCTAssertFalse(reference.grossReliable)
        XCTAssertFalse(reference.complete)
        XCTAssertTrue(reference.warnings.contains { $0.contains("ne correspond pas au brut canonique") })
    }

    func testCanonicalReferenceBridgeKeepsGrossButBlocksIncompleteNet() throws {
        let payroll = try confirmedForfaitPayroll()
        let monthBenefits = benefits()
        let projection = EmployeeNetProjectionV2.calculate(
            input(
                cashGross: payroll.grossEstimate,
                upstreamReliable: payroll.grossReliable,
                benefits: monthBenefits,
                professionalStatus: nil
            )
        )

        let reference = SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: monthBenefits,
            projection: projection
        )

        XCTAssertTrue(reference.grossReliable)
        XCTAssertFalse(reference.complete)
        XCTAssertNotNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
        XCTAssertTrue(reference.warnings.contains { $0.contains("projection canonique incomplète") })
    }

}
