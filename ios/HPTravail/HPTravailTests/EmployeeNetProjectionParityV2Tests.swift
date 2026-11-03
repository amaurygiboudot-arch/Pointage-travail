import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

/// Même corpus décimal indépendant que les tests Android, chargé comme ressource sur simulateur.
final class EmployeeNetProjectionParityV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    private struct Corpus: Decodable {
        let schemaVersion: Int
        let caseCount: Int
        let cases: [Fixture]
    }

    private struct Fixture: Decodable {
        let id: String
        let year: Int
        let month: Int
        let contractType: String
        let weeklyMinutes: Int
        let complementaryMinutes: Int?
        let professionalStatus: String?
        let alsaceMoselle: Bool?
        var cashGross: Double
        var benefitsGross: Double
        let benefitsReliable: Bool
        let upstreamReliable: Bool
        var mutual: Double?
        let provident: Double?
        let transport: Double?
        var employerTaxable: Double?
        let employeeNonDeductible: Double?
        let employerCsgBase: Double?
        let rate: Double?
        let expected: Expected
    }

    private struct Expected: Decodable {
        let beforeCents: Int64?
        let taxableCents: Int64?
        let pasCents: Int64?
        let afterCents: Int64?
        let complete: Bool
    }

    func testSharedCorpusHasExpectedVersionAndUniqueCases() throws {
        let corpus = try loadCorpus()
        XCTAssertEqual(corpus.schemaVersion, 1)
        XCTAssertEqual(corpus.caseCount, 36)
        XCTAssertEqual(corpus.cases.count, corpus.caseCount)
        XCTAssertEqual(Set(corpus.cases.map(\.id)).count, corpus.caseCount)
    }

    func testSharedDecimalOracleThroughCanonicalProjection() throws {
        for fixture in try loadCorpus().cases {
            assertProjection(EmployeeNetProjectionV2.calculate(input(fixture)), fixture)
        }
    }

    func testSharedDecimalOracleThroughSegmentedBridge() throws {
        for fixture in try loadCorpus().cases {
            let source = input(fixture)
            let result = SalarySegmentedCashGrossNetProjectionV2.project(
                cash: .init(workedGross: fixture.cashGross, additionalCashGross: 0,
                            cashGross: fixture.cashGross, reliable: fixture.upstreamReliable, warnings: []),
                context: .init(benefits: source.benefits, year: source.year, ceiling: source.ceiling,
                               alsaceMoselleLocalRegime: source.alsaceMoselleLocalRegime,
                               professionalStatus: source.professionalStatus,
                               protectionCategory: source.protectionCategory,
                               companyDeductions: source.companyDeductions, period: source.period,
                               incomeTaxRate: source.incomeTaxRate)
            )
            XCTAssertEqual(result.netBeforeIncomeTaxComplete, fixture.expected.complete, fixture.id)
            if fixture.upstreamReliable {
                assertProjection(try XCTUnwrap(result.projection, fixture.id), fixture)
            } else {
                XCTAssertNil(result.projection, fixture.id)
            }
        }
    }

    func testSharedDecimalOracleSurvivesMonthlyReferenceAssembly() throws {
        for fixture in try loadCorpus().cases {
            let reference = monthlyReference(fixture)
            assertAmount(SalaryReferenceContractV2.beforeIncomeTax(reference), fixture.expected.beforeCents, fixture.id)
            assertAmount(SalaryReferenceContractV2.taxable(reference), fixture.expected.taxableCents, fixture.id)
        }
    }

    func testSharedDecimalOracleReachesMonthlyWorkspaceWithoutLocalFallback() throws {
        for fixture in try loadCorpus().cases {
            let source = input(fixture)
            let workspace = SalaryWorkspaceResolverV2.resolve(
                period: YearMonthV2(year: fixture.year, month: fixture.month)!,
                reference: monthlyReference(fixture), incomeTaxRate: source.incomeTaxRate
            )
            assertAmount(workspace.netBeforeIncomeTax, fixture.expected.beforeCents, fixture.id + " workspace before")
            assertAmount(workspace.netTaxable, fixture.expected.taxableCents, fixture.id + " workspace taxable")
            assertAmount(workspace.incomeTax, fixture.expected.pasCents, fixture.id + " workspace PAS")
            assertAmount(workspace.netAfterIncomeTax, fixture.expected.afterCents, fixture.id + " workspace after")
        }
    }

    func testMonthlyWorkspaceUsesCurrentConfirmedRateAndNeverReusesAnOlderPas() throws {
        let fixture = try XCTUnwrap(loadCorpus().cases.first { $0.id == "historical_3000" })
        let reference = monthlyReference(fixture)
        var rates: [CompanyIncomeTaxRateResolverV2.Snapshot?] = [nil]
        rates.append(.init(rate: 0.032, ratePercent: 3.2, source: "Période non couverte",
                           hasDatedRecords: true, reliable: false, warnings: ["Taux du mois à confirmer"]))
        for invalid in [Double.nan, .infinity, -.infinity, -0.1, 1.1] {
            rates.append(.init(rate: invalid, ratePercent: invalid * 100, source: "Snapshot invalide",
                               hasDatedRecords: true, reliable: true, warnings: []))
        }
        for rate in rates {
            let workspace = SalaryWorkspaceResolverV2.resolve(
                period: YearMonthV2(year: fixture.year, month: fixture.month)!,
                reference: reference, incomeTaxRate: rate
            )
            assertAmount(workspace.netBeforeIncomeTax, 231362, "current rate unavailable")
            XCTAssertNil(workspace.incomeTax)
            XCTAssertNil(workspace.netAfterIncomeTax)
            XCTAssertTrue(workspace.warnings.contains { $0.contains("taux personnel") })
        }
    }

    func testMonthlyReferencePreservesDebtAndUnknownCollectionDiagnostics() throws {
        let fixture = try XCTUnwrap(loadCorpus().cases.first { $0.id == "benefits_without_cash" })
        let reference = monthlyReference(fixture)
        XCTAssertTrue(reference.complete)
        assertAmount(SalaryReferenceContractV2.beforeIncomeTax(reference), 0, fixture.id)
        assertAmount(SalaryReferenceContractV2.taxable(reference), 77127, fixture.id)
        XCTAssertTrue(reference.warnings.contains { $0.contains("régularisation") })
        XCTAssertTrue(reference.warnings.contains { $0.contains("employeur") && $0.contains("PAS") })
    }

    func testMonthlyReferenceKeepsKnownBeforeTaxAndFiscalIncompletenessDiagnostics() throws {
        let corpus = try loadCorpus()
        for (id, fragment) in [
            ("transport_tax_treatment_unknown", "transport"),
            ("unknown_employer_taxable", "Net imposable incomplet"),
            ("negative_rnf_unsupported", "régularisation fiscale")
        ] {
            let fixture = try XCTUnwrap(corpus.cases.first { $0.id == id })
            let reference = monthlyReference(fixture)
            XCTAssertTrue(reference.complete, id)
            assertAmount(SalaryReferenceContractV2.beforeIncomeTax(reference), fixture.expected.beforeCents, id)
            XCTAssertNil(SalaryReferenceContractV2.taxable(reference), id)
            XCTAssertTrue(reference.warnings.contains { $0.contains(fragment) }, id)
        }
    }

    func testPublishedDeductionsMatchExactCentAmountsUsedByNet() throws {
        let fixture = try XCTUnwrap(loadCorpus().cases.first { $0.id == "historical_3000" })
        let result = EmployeeNetProjectionV2.calculate(input(fixture))
        XCTAssertEqual(result.statutory.employeeDeductions, 506.08, accuracy: 0.000000001)
        XCTAssertEqual(result.complementaryRetirement.employeeDeductions, 120.30, accuracy: 0.000000001)
        XCTAssertEqual(result.companyCashDeductions.deductions.map(\.amount), [42, 18])
        XCTAssertEqual(result.statutory.lines.reduce(0) { $0 + $1.employeeAmount }, 506.08, accuracy: 0.000000001)
        XCTAssertEqual(result.complementaryRetirement.lines.reduce(0) { $0 + $1.employeeAmount }, 120.30, accuracy: 0.000000001)
        XCTAssertEqual(result.netBeforeIncomeTax!, 3000 - 506.08 - 120.30 - 60, accuracy: 0.000000001)
    }

    func testNonFiniteGrossAndBenefitInputsNeverPublishCompleteNet() throws {
        let ordinary = try XCTUnwrap(loadCorpus().cases.first)
        for invalid in [Double.nan, .infinity, -.infinity, -1, Double.greatestFiniteMagnitude] {
            var fixture = ordinary
            fixture.cashGross = invalid
            assertBlocked(EmployeeNetProjectionV2.calculate(input(fixture)))
            fixture = ordinary
            fixture.benefitsGross = invalid
            assertBlocked(EmployeeNetProjectionV2.calculate(input(fixture)))
        }
    }

    func testFiniteButUnrepresentableMonetaryAssemblyNeverPublishesZeroAsComplete() throws {
        var fixture = try XCTUnwrap(loadCorpus().cases.first)
        fixture.cashGross = 90071992547409.9
        fixture.benefitsGross = 1
        assertBlocked(EmployeeNetProjectionV2.calculate(input(fixture)))
        fixture = try XCTUnwrap(loadCorpus().cases.first)
        fixture.mutual = Double.greatestFiniteMagnitude
        assertBlocked(EmployeeNetProjectionV2.calculate(input(fixture)))
    }

    func testInvalidFiscalReintegrationKeepsBeforeTaxButBlocksFiscal() throws {
        let ordinary = try XCTUnwrap(loadCorpus().cases.first)
        for invalid in [Double.nan, .infinity, -1, Double.greatestFiniteMagnitude] {
            var fixture = ordinary
            fixture.employerTaxable = invalid
            let result = EmployeeNetProjectionV2.calculate(input(fixture))
            XCTAssertTrue(result.netBeforeIncomeTaxComplete)
            assertAmount(result.netBeforeIncomeTax, 231362, "fiscal invalid")
            XCTAssertFalse(result.netTaxableComplete)
            XCTAssertNil(result.netTaxable)
            XCTAssertNil(result.incomeTax)
            XCTAssertNil(result.netAfterIncomeTax)
        }
    }

    func testUnsupportedFiscalCasesExplainWhyOnlyBeforeTaxIsAvailable() throws {
        let corpus = try loadCorpus()
        for (id, fragment) in [
            ("negative_rnf_unsupported", "régularisation"),
            ("transport_tax_treatment_unknown", "transport"),
            ("benefits_without_cash", "employeur")
        ] {
            let fixture = try XCTUnwrap(corpus.cases.first { $0.id == id })
            let result = EmployeeNetProjectionV2.calculate(input(fixture))
            XCTAssertTrue(result.netBeforeIncomeTaxComplete, id)
            XCTAssertTrue(result.warnings.contains { $0.contains(fragment) }, id)
            XCTAssertNil(result.incomeTax, id)
            XCTAssertNil(result.netAfterIncomeTax, id)
        }
    }

    private func loadCorpus() throws -> Corpus {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: EmployeeNetProjectionParityV2Tests.self)
        #endif
        let url = try XCTUnwrap(bundle.url(forResource: "net-projection-v2", withExtension: "json"))
        return try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: url))
    }

    private func input(_ fixture: Fixture) -> EmployeeNetProjectionV2.Input {
        let period = Resolver.YearMonth(year: fixture.year, month: fixture.month)!
        let entries: [(Resolver.Kind, Double?)] = [
            (.mutualEmployee, fixture.mutual), (.providentEmployee, fixture.provident),
            (.transportEmployee, fixture.transport), (.employerProtectionTaxable, fixture.employerTaxable),
            (.employeeProvidentNonDeductible, fixture.employeeNonDeductible),
            (.employerProtectionCsgCrdsBase, fixture.employerCsgBase)
        ]
        let records = entries.compactMap { kind, amount -> Resolver.Record? in
            guard let amount else { return nil }
            return .init(id: kind.rawValue, kind: kind, amount: amount,
                         effectiveFrom: period, effectiveTo: period, source: "Fixture synthétique confirmée")
        }
        return .init(
            cashGross: fixture.cashGross, upstreamGrossReliable: fixture.upstreamReliable,
            benefits: .init(applied: [], totalGross: fixture.benefitsGross, reliable: fixture.benefitsReliable, warnings: []),
            year: fixture.year,
            ceiling: SocialSecurityCeilingV2.calculate(.init(
                period: YearMonthV2(year: fixture.year, month: fixture.month)!,
                contractType: fixture.contractType == "PART_TIME" ? .partTime : .fullTime,
                contractualWeeklyMinutes: fixture.weeklyMinutes,
                complementaryMinutes: fixture.complementaryMinutes,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
            )),
            alsaceMoselleLocalRegime: fixture.alsaceMoselle, professionalStatus: fixture.professionalStatus,
            protectionCategory: ProtectionCategoryV2.noConventionOverride(),
            companyDeductions: Resolver.resolve(records: records, period: period), period: period,
            incomeTaxRate: fixture.rate.map {
                .init(rate: $0, ratePercent: $0 * 100, source: "Fixture synthétique confirmée",
                      hasDatedRecords: true, reliable: true, warnings: [])
            }
        )
    }

    private func monthlyReference(_ fixture: Fixture) -> SalaryReferenceContractV2 {
        let source = input(fixture)
        let projection = EmployeeNetProjectionV2.calculate(source)
        let payroll = PayrollResultV2(
            regularGross: fixture.cashGross, overtimeGross: 0, premiumsGross: 0, fixedPremiumsGross: 0,
            baskets: 0, grossEstimate: fixture.cashGross, deductions: 0,
            netBeforeUnknownContributions: fixture.cashGross, complementaryMinutes: fixture.complementaryMinutes ?? 0,
            grossReliable: fixture.upstreamReliable, traces: []
        )
        return SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll, benefits: source.benefits, projection: projection
        )
    }

    private func assertProjection(_ result: EmployeeNetProjectionV2.Result, _ fixture: Fixture) {
        XCTAssertEqual(result.netBeforeIncomeTaxComplete, fixture.expected.complete, fixture.id)
        XCTAssertEqual(result.netTaxableComplete, fixture.expected.taxableCents != nil, fixture.id)
        assertAmount(result.netBeforeIncomeTax, fixture.expected.beforeCents, fixture.id + " before")
        assertAmount(result.netTaxable, fixture.expected.taxableCents, fixture.id + " taxable")
        assertAmount(result.incomeTax, fixture.expected.pasCents, fixture.id + " PAS")
        assertAmount(result.netAfterIncomeTax, fixture.expected.afterCents, fixture.id + " after")
    }

    private func assertAmount(_ actual: Double?, _ expectedCents: Int64?, _ message: String) {
        if let expectedCents {
            guard let actual else { XCTFail("\(message): montant absent"); return }
            XCTAssertEqual(actual, Double(expectedCents) / 100, accuracy: 0.000000001, message)
        } else {
            XCTAssertNil(actual, message)
        }
    }

    private func assertBlocked(_ result: EmployeeNetProjectionV2.Result) {
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertFalse(result.netTaxableComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertNil(result.incomeTax)
        XCTAssertNil(result.netAfterIncomeTax)
    }
}
