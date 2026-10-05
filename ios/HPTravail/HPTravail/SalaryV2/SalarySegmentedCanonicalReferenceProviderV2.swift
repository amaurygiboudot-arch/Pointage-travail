import Foundation

/// Provider de production pour les mois dont la paie doit être calculée par tranches datées.
///
/// Cette couche orchestre uniquement les propriétaires V2 existants : contrats et règles datés,
/// preuves de temps, B21/B20, composantes fixes, plafond social et projection nette canonique.
/// Elle ne recrée aucun taux, barème, droit ou formule de présentation.
enum SalarySegmentedCanonicalReferenceProviderV2 {
    static func build(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: YearMonthV2,
        work: SalaryWorkSessionSourceV2,
        calendar: Calendar = .current,
        now: Date = Date()
    ) -> SalarySegmentedCanonicalOutputV2? {
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard let company = SalaryCompanyStoreV2.confirmedCompany(
            companies,
            companyId: companyId
        ) else {
            return nil
        }

        let convention = SalaryConventionPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            companies: companies,
            storedRules: SalaryConventionRuleStoreV2.readConfirmed(defaults: defaults)
        )
        let contract = SalaryEmploymentContractPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            stored: SalaryEmploymentContractHistoryStoreV2.readConfirmed(defaults: defaults)
        )
        guard let contracts = contract.resolution else { return nil }

        let workedBridge = SalarySegmentedWorkedGrossProductionBridgeV2.calculateDetailedFromStores(
            defaults: defaults,
            companyId: company.id,
            companyAddress: company.address,
            period: period,
            timeZoneId: calendar.timeZone.identifier,
            work: work,
            contracts: contracts,
            rules: convention.coverage,
            now: now
        )
        guard let worked = workedBridge.worked else { return nil }

        let socialProfile = SalaryEmployeeSocialProfileStoreV2.resolve(
            defaults: defaults,
            companyId: company.id,
            period: period
        )
        let professionalStatus = socialProfile.reliable
            ? socialProfile.professionalStatus?.rawValue
            : nil

        let fixed = SalarySegmentedFixedCashComponentsBridgeV2.load(
            defaults: defaults,
            companyId: company.id,
            idcc: company.idcc,
            period: period,
            actualMonthlyBaseGross: worked.base.reliable ? worked.base.baseGross : nil,
            professionalStatus: professionalStatus
        )

        let absenceSource = SalaryAbsenceStoreV2.resolve(
            companyId: company.id,
            period: period,
            defaults: defaults
        )
        let absenceImpact = SalaryAbsencePayrollImpactV2.forMonth(
            absences: absenceSource.absences,
            period: period,
            acceptedEmployerIds: [company.id],
            workSessions: work.sessions,
            absenceSourceReliable: absenceSource.reliable,
            workSourceReliable: work.reliable,
            calendar: calendar
        )
        let ceilingResult = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period,
            contracts: contracts,
            complementaryMinutes: SalarySegmentedSocialSecurityCeilingV2.confirmedComplementaryMinutes(
                from: worked.variables
            ),
            unpaidAbsenceDays: absenceImpact.requiresPayrollReview
                ? nil : absenceImpact.unpaidFullCalendarDays
        )
        guard let ceiling = ceilingResult.ceiling else { return nil }

        let benefits = CompanyBenefitInKindStoreV2.resolve(
            companyId: company.id,
            period: period,
            defaults: defaults
        )
        guard let deductionPeriod = CompanyEmployeeDeductionResolverV2.YearMonth(
            year: period.year,
            month: period.month
        ) else {
            return nil
        }
        let deductions = CompanyEmployeeDeductionStoreV2.resolve(
            defaults: defaults,
            companyId: company.id,
            period: deductionPeriod
        )
        let incomeTax = CompanyIncomeTaxRateStoreV2(defaults: defaults).snapshot(
            companyId: company.id,
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

        let classification = SalaryConventionClassificationStoreV2.load(
            companyId: company.id,
            defaults: defaults
        )
        let legalProfile = SalaryConventionLegalProfileV2(
            companyId: company.id,
            idcc: company.idcc,
            professionalStatus: professionalStatus,
            classification: classification
        )
        let protectionRules = SalaryConventionProtectionCategoryStoreV2.rules(
            idcc: company.idcc,
            defaults: defaults
        )
        let protectionCoverage = SalaryConventionMatterCoverageStoreV2.resolve(
            defaults: defaults,
            idcc: company.idcc,
            matter: .providentCategory,
            date: protectionReferenceDate,
            classification: classification,
            professionalStatus: professionalStatus
        )
        let protection: SalaryVerifiedProtectionCategoryProviderV2.Snapshot
        if protectionRules.reliable {
            protection = SalaryVerifiedProtectionCategoryProviderV2.resolve(
                profile: legalProfile,
                referenceDate: protectionReferenceDate,
                rules: protectionRules.rules,
                coverage: protectionCoverage
            )
        } else {
            let warning = protectionRules.warnings.isEmpty
                ? "Catégorie ANI vérifiée : cache KALI/APEC local incohérent ; aucun classement n'est déduit."
                : protectionRules.warnings.joined(separator: " ; ")
            protection = SalaryVerifiedProtectionCategoryProviderV2.Snapshot(
                category: ProtectionCategoryV2.Result(
                    aniCategory: .toConfirm,
                    confirmed: false,
                    warnings: [warning]
                ),
                reliable: false,
                warnings: [warning]
            )
        }

        let netContext = SalarySegmentedNetProjectionContextV2(
            benefits: benefits,
            year: period.year,
            ceiling: ceiling,
            alsaceMoselleLocalRegime: socialProfile.reliable
                ? socialProfile.alsaceMoselleLocalRegime : nil,
            professionalStatus: professionalStatus,
            protectionCategory: protection.category,
            companyDeductions: deductions,
            period: deductionPeriod,
            incomeTaxRate: incomeTax
        )
        return SalarySegmentedCanonicalProductionV2.calculate(
            worked: worked,
            fixed: fixed,
            netContext: netContext
        ).output
    }
}
