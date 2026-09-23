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

                    let conventionCoverage = SalaryConventionCoverageResolverV2.resolve(
                        companyId: companyId,
                        period: period,
                        companies: companies,
                        rules: SalaryConventionRuleStoreV2.readConfirmed()
                    )
                    guard let convention = conventionCoverage.singleSnapshotForWholePeriod else {
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

                    // Tant qu'aucune source iOS explicite n'existe pour la catégorie ANI et les
                    // absences non rémunérées, ces deux entrées restent volontairement inconnues.
                    // Le provider peut publier un brut prouvé, mais jamais promouvoir un net
                    // incomplet comme référence fiable.
                    let protectionCategory = ProtectionCategoryV2.Result(
                        aniCategory: .toConfirm,
                        confirmed: false,
                        source: nil,
                        warnings: [
                            "Catégorie ANI : source iOS datée non raccordée ; net salarié à confirmer."
                        ]
                    )

                    return SalaryCanonicalReferenceProviderV2.build(
                        .init(
                            companyId: companyId,
                            companyAddress: company.address,
                            period: period,
                            contract: contract,
                            rules: convention.rules,
                            payrollRulesReliable: conventionCoverage.sourceReliable
                                && conventionCoverage.fullyCovered,
                            sessions: workSource.sessions,
                            workSourceReliable: workSource.reliable,
                            nightRule: nightResolution.reliable ? nightResolution.rule : nil,
                            benefits: benefits,
                            socialProfile: socialProfile,
                            protectionCategory: protectionCategory,
                            companyDeductions: deductions,
                            incomeTaxRate: incomeTax,
                            unpaidAbsenceDays: nil
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
