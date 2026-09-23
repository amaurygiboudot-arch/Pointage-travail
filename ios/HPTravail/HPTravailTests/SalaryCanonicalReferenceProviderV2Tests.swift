import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryCanonicalReferenceProviderV2Tests: XCTestCase {
    private let companyId = "company-a"
    private let period = YearMonthV2(year: 2026, month: 9)!

    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        value.firstWeekday = 2
        value.minimumDaysInFirstWeek = 4
        return value
    }

    func testCanonicalProviderPublishesGrossButKeepsUnknownNetFailClosed() throws {
        let reference = try XCTUnwrap(
            SalaryCanonicalReferenceProviderV2.build(
                input(
                    protectionCategory: .init(
                        aniCategory: .toConfirm,
                        confirmed: false,
                        source: nil,
                        warnings: ["Catégorie ANI à confirmer"]
                    ),
                    deductions: deductions(complete: false),
                    unpaidAbsenceDays: nil
                )
            )
        )

        XCTAssertTrue(reference.grossReliable)
        XCTAssertNotNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertFalse(reference.complete)
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
    }

    func testUnreliablePointageNeverPublishesReliableGross() throws {
        let reference = try XCTUnwrap(
            SalaryCanonicalReferenceProviderV2.build(
                input(
                    workSourceReliable: false,
                    protectionCategory: ProtectionCategoryV2.noConventionOverride(),
                    deductions: deductions(complete: true),
                    unpaidAbsenceDays: 0
                )
            )
        )

        XCTAssertFalse(reference.grossReliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertFalse(reference.complete)
    }

    func testExplicitCompleteInputsCanPublishCanonicalBeforeTaxNet() throws {
        let reference = try XCTUnwrap(
            SalaryCanonicalReferenceProviderV2.build(
                input(
                    protectionCategory: ProtectionCategoryV2.noConventionOverride(),
                    deductions: deductions(complete: true),
                    unpaidAbsenceDays: 0
                )
            )
        )

        XCTAssertTrue(reference.grossReliable)
        XCTAssertTrue(reference.complete)
        XCTAssertNotNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertNotNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
    }

    func testEmployerMismatchIsRejectedBeforeCalculation() {
        var wrongContract = contract()
        wrongContract = ContractV2(
            id: wrongContract.id,
            employerId: "other-company",
            type: wrongContract.type,
            contractualWeeklyMinutes: wrongContract.contractualWeeklyMinutes,
            grossHourlyRate: wrongContract.grossHourlyRate,
            hireDateEpochDay: wrongContract.hireDateEpochDay
        )

        let result = SalaryCanonicalReferenceProviderV2.build(
            input(
                contract: wrongContract,
                protectionCategory: ProtectionCategoryV2.noConventionOverride(),
                deductions: deductions(complete: true),
                unpaidAbsenceDays: 0
            )
        )

        XCTAssertNil(result)
    }

    private func input(
        contract: ContractV2? = nil,
        workSourceReliable: Bool = true,
        protectionCategory: ProtectionCategoryV2.Result,
        deductions: CompanyEmployeeDeductionResolverV2.Snapshot,
        unpaidAbsenceDays: Int?
    ) -> SalaryCanonicalReferenceProviderV2.Input {
        .init(
            companyId: companyId,
            companyAddress: "10 rue Exemple, 85000 La Roche-sur-Yon",
            period: period,
            contract: contract ?? self.contract(),
            rules: PayrollRulesV2(
                weeklyRegularMinutes: 35 * 60,
                overtimeTiers: [
                    OvertimeTierV2(
                        fromMinutes: 35 * 60,
                        toMinutes: 43 * 60,
                        multiplier: 1.25
                    ),
                    OvertimeTierV2(
                        fromMinutes: 43 * 60,
                        toMinutes: nil,
                        multiplier: 1.50
                    )
                ]
            ),
            payrollRulesReliable: true,
            sessions: [
                SalarySessionFactV2(
                    id: "session-1",
                    entry: localDate(2026, 9, 7, 8, 0),
                    exit: localDate(2026, 9, 7, 16, 0),
                    employerId: companyId,
                    pauses: []
                )
            ],
            workSourceReliable: workSourceReliable,
            nightRule: nil,
            benefits: CompanyBenefitInKindContractV2.Snapshot(
                applied: [],
                totalGross: 0,
                reliable: true,
                warnings: []
            ),
            socialProfile: SalaryEmployeeSocialProfileResolutionV2(
                professionalStatus: .nonCadre,
                alsaceMoselleLocalRegime: false,
                reliable: true,
                warnings: []
            ),
            protectionCategory: protectionCategory,
            companyDeductions: deductions,
            incomeTaxRate: nil,
            unpaidAbsenceDays: unpaidAbsenceDays,
            calendar: calendar
        )
    }

    private func contract() -> ContractV2 {
        ContractV2(
            id: "contract-a",
            employerId: companyId,
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 14,
            hireDateEpochDay: 18_000
        )
    }

    private func deductions(
        complete: Bool
    ) -> CompanyEmployeeDeductionResolverV2.Snapshot {
        let deductionPeriod = CompanyEmployeeDeductionResolverV2.YearMonth(
            year: period.year,
            month: period.month
        )!
        guard complete else {
            return CompanyEmployeeDeductionResolverV2.resolve(
                records: [],
                period: deductionPeriod
            )
        }

        let records = CompanyEmployeeDeductionResolverV2.Kind.allCases.enumerated().map {
            index,
            kind in
            CompanyEmployeeDeductionResolverV2.Record(
                id: "deduction-\(index)",
                kind: kind,
                amount: 0,
                effectiveFrom: deductionPeriod,
                effectiveTo: deductionPeriod,
                source: "Bulletin confirmé"
            )
        }
        return CompanyEmployeeDeductionResolverV2.resolve(
            records: records,
            period: deductionPeriod
        )
    }

    private func localDate(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int,
        _ minute: Int
    ) -> Date {
        calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: year,
                month: month,
                day: day,
                hour: hour,
                minute: minute
            )
        )!
    }
}
