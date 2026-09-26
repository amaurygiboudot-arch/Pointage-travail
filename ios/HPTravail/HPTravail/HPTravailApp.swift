import SwiftUI
import GoogleSignIn

@main
@MainActor
struct HPTravailApp: App {
    @StateObject private var store: WorkStoreV2
    @StateObject private var salaryStore: SalaryV2Store
    @StateObject private var locationManager = LocationManager()
    @StateObject private var authManager = AuthManager()

    init() {
        let workStore = WorkStoreV2()
        _store = StateObject(wrappedValue: workStore)
        _salaryStore = StateObject(
            wrappedValue: SalaryV2Store(
                referenceProvider: { companyId, period in
                    let companies = SalaryCompanyStoreV2.readConfirmed()
                    guard let company = SalaryCompanyStoreV2.confirmedCompany(
                        companies,
                        companyId: companyId
                    ) else {
                        return nil
                    }

                    let conventionPayroll = SalaryConventionPayrollBridgeV2.resolve(
                        companyId: companyId,
                        period: period,
                        companies: companies,
                        storedRules: SalaryConventionRuleStoreV2.readConfirmed()
                    )
                    let conventionCoverage = conventionPayroll.coverage
                    guard conventionPayroll.readyForSingleRulesCalculation,
                          let conventionRules = conventionPayroll.rules else {
                        return nil
                    }

                    let contractResolution = SalaryEmploymentContractPayrollBridgeV2.resolve(
                        companyId: companyId,
                        period: period
                    )
                    guard contractResolution.readyForSingleContractCalculation,
                          let contract = contractResolution.contract else {
                        return nil
                    }

                    let workSource = SalaryWorkSessionBridgeV2.source(
                        from: workStore.sessions,
                        storageReliable: workStore.storageReliable
                    )
                    let absenceSource = SalaryAbsenceStoreV2.resolve(
                        companyId: companyId,
                        period: period
                    )
                    let socialProfile = SalaryEmployeeSocialProfileStoreV2.resolve(
                        companyId: companyId,
                        period: period
                    )
                    let nightResolution = SalaryConventionNightRuleStoreV2.resolve(
                        idcc: company.idcc,
                        period: period
                    )
                    let benefits = CompanyBenefitInKindStoreV2.resolve(
                        companyId: companyId,
                        period: period
                    )
                    guard let deductionPeriod = CompanyEmployeeDeductionResolverV2.YearMonth(
                        year: period.year,
                        month: period.month
                    ) else {
                        return nil
                    }
                    let deductions = CompanyEmployeeDeductionStoreV2.resolve(
                        companyId: companyId,
                        period: deductionPeriod
                    )
                    let incomeTax = CompanyIncomeTaxRateStoreV2().snapshot(
                        companyId: companyId,
                        for: period
                    )

                    guard let lastDay = PayrollCivilDateV2.daysInMonth(
                        year: period.year,
                        month: period.month
                    ),
                    let protectionReferenceDate = PayrollCivilDateV2(
                        year: period.year,
                        month: period.month,
                        day: lastDay
                    ) else {
                        return nil
                    }
                    let professionalStatus = socialProfile.reliable
                        ? socialProfile.professionalStatus?.rawValue
                        : nil
                    let classification = SalaryConventionClassificationStoreV2.load(
                        companyId: companyId
                    )
                    let legalProfile = SalaryConventionLegalProfileV2(
                        companyId: companyId,
                        idcc: company.idcc,
                        professionalStatus: professionalStatus,
                        classification: classification
                    )
                    let protectionRules = SalaryConventionProtectionCategoryStoreV2.rules(
                        idcc: company.idcc
                    )
                    let protectionCoverage = SalaryConventionMatterCoverageStoreV2.resolve(
                        idcc: company.idcc,
                        matter: .providentCategory,
                        date: protectionReferenceDate,
                        classification: classification,
                        professionalStatus: professionalStatus
                    )
                    let verifiedProtection: SalaryVerifiedProtectionCategoryProviderV2.Snapshot
                    if protectionRules.reliable {
                        verifiedProtection = SalaryVerifiedProtectionCategoryProviderV2.resolve(
                            profile: legalProfile,
                            referenceDate: protectionReferenceDate,
                            rules: protectionRules.rules,
                            coverage: protectionCoverage
                        )
                    } else {
                        let warning = protectionRules.warnings.isEmpty
                            ? "Catégorie ANI vérifiée : cache KALI/APEC local incohérent ; aucun classement n'est déduit."
                            : protectionRules.warnings.joined(separator: " ; ")
                        verifiedProtection = SalaryVerifiedProtectionCategoryProviderV2.Snapshot(
                            category: ProtectionCategoryV2.Result(
                                aniCategory: .toConfirm,
                                confirmed: false,
                                warnings: [warning]
                            ),
                            reliable: false,
                            warnings: [warning]
                        )
                    }

                    return SalaryCanonicalReferenceProviderV2.build(
                        .init(
                            companyId: companyId,
                            companyAddress: company.address,
                            period: period,
                            contract: contract,
                            rules: conventionRules,
                            payrollRulesReliable: conventionCoverage.sourceReliable
                                && conventionCoverage.fullyCovered,
                            sessions: workSource.sessions,
                            workSourceReliable: workSource.reliable,
                            absences: absenceSource.absences,
                            absenceSourceReliable: absenceSource.reliable,
                            nightRule: nightResolution.reliable ? nightResolution.rule : nil,
                            benefits: benefits,
                            socialProfile: socialProfile,
                            protectionCategory: verifiedProtection.category,
                            companyDeductions: deductions,
                            incomeTaxRate: incomeTax
                        )
                    )
                },
                workSourceProvider: {
                    SalaryWorkSessionBridgeV2.source(
                        from: workStore.sessions,
                        storageReliable: workStore.storageReliable
                    )
                }
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(salaryStore)
                .environmentObject(locationManager)
                .environmentObject(authManager)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
