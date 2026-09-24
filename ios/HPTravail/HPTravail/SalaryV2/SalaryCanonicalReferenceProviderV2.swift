import Foundation

/// Provider canonique iOS pour une période où contrat et règles de paie sont uniques et
/// entièrement identifiés.
///
/// Cette couche ne lit aucun écran et ne recalcule aucun montant de présentation. Elle enchaîne
/// uniquement les moteurs V2 canoniques : faits de pointage -> PayrollEngineV2 ->
/// EmployeeNetProjectionV2 -> SalaryReferenceContractV2.
///
/// Les mois segmentés (plusieurs contrats/règles) restent volontairement hors de ce lot et doivent
/// passer par la timeline/proratisation dédiée. Une donnée inconnue n'est jamais remplacée par zéro.
enum SalaryCanonicalReferenceProviderV2 {
    struct Input {
        let companyId: String
        let companyAddress: String
        let period: YearMonthV2
        let contract: ContractV2
        let rules: PayrollRulesV2
        let payrollRulesReliable: Bool
        let sessions: [SalarySessionFactV2]
        let workSourceReliable: Bool
        let absences: [SalaryAbsenceFactV2]
        let absenceSourceReliable: Bool
        let nightRule: NightPremiumRuleV2?
        let benefits: CompanyBenefitInKindContractV2.Snapshot
        let socialProfile: SalaryEmployeeSocialProfileResolutionV2
        let protectionCategory: ProtectionCategoryV2.Result
        let companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot
        let incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?
        let calendar: Calendar
        let now: Date

        init(
            companyId: String,
            companyAddress: String,
            period: YearMonthV2,
            contract: ContractV2,
            rules: PayrollRulesV2,
            payrollRulesReliable: Bool,
            sessions: [SalarySessionFactV2],
            workSourceReliable: Bool,
            absences: [SalaryAbsenceFactV2],
            absenceSourceReliable: Bool,
            nightRule: NightPremiumRuleV2?,
            benefits: CompanyBenefitInKindContractV2.Snapshot,
            socialProfile: SalaryEmployeeSocialProfileResolutionV2,
            protectionCategory: ProtectionCategoryV2.Result,
            companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot,
            incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?,
            calendar: Calendar = .current,
            now: Date = Date()
        ) {
            self.companyId = companyId
            self.companyAddress = companyAddress
            self.period = period
            self.contract = contract
            self.rules = rules
            self.payrollRulesReliable = payrollRulesReliable
            self.sessions = sessions
            self.workSourceReliable = workSourceReliable
            self.absences = absences
            self.absenceSourceReliable = absenceSourceReliable
            self.nightRule = nightRule
            self.benefits = benefits
            self.socialProfile = socialProfile
            self.protectionCategory = protectionCategory
            self.companyDeductions = companyDeductions
            self.incomeTaxRate = incomeTaxRate
            self.calendar = calendar
            self.now = now
        }
    }

    static func build(_ input: Input) -> SalaryReferenceContractV2? {
        let companyId = input.companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let contractEmployerId = input.contract.employerId
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !companyId.isEmpty, contractEmployerId == companyId else {
            return nil
        }

        let absenceImpact = SalaryAbsencePayrollImpactV2.forMonth(
            absences: input.absences,
            period: input.period,
            acceptedEmployerIds: [companyId],
            workSessions: input.sessions,
            absenceSourceReliable: input.absenceSourceReliable,
            workSourceReliable: input.workSourceReliable,
            calendar: input.calendar
        )

        let weeklyBoundaryThreshold: Int? = {
            switch input.contract.type {
            case .partTime:
                return input.contract.contractualWeeklyMinutes
            case .fullTime:
                return input.contract.contractualWeeklyMinutes
                    ?? input.rules.weeklyRegularMinutes
            case .other:
                return input.rules.weeklyRegularMinutes
                    ?? input.contract.contractualWeeklyMinutes
            case .forfaitHours, .forfaitDays, .forfait:
                return nil
            }
        }()
        let weeklyBoundary = weeklyBoundaryThreshold.map {
            SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
                sessions: input.sessions,
                employerId: companyId,
                period: input.period,
                weeklyThresholdMinutes: $0,
                sourceReliable: input.workSourceReliable,
                calendar: input.calendar,
                now: input.now
            )
        }

        let holidayScope = FrenchPublicHolidayCalendarV2.scopeForAddress(input.companyAddress)
        let payrollWeeks = SalaryPayrollWeekEvidenceBuilderV2.build(
            sessions: input.sessions,
            employerId: companyId,
            period: input.period,
            rules: input.rules,
            payrollRulesReliable: input.payrollRulesReliable
                && absenceImpact.unpaidFullCalendarDays != nil
                && !absenceImpact.requiresPayrollReview,
            nightRule: input.nightRule,
            publicHolidayScope: holidayScope,
            sourceReliable: input.workSourceReliable,
            calendar: input.calendar
        )

        let payrollEvidence = PayrollInputEvidenceV2(
            paidTimeReliable: payrollWeeks.evidence.paidTimeReliable
                && (weeklyBoundary?.reliable ?? true),
            premiumTimeBreakdownReliable: payrollWeeks.evidence.premiumTimeBreakdownReliable,
            payrollRulesReliable: payrollWeeks.evidence.payrollRulesReliable
        )

        let payroll: PayrollResultV2
        do {
            payroll = try PayrollEngineV2.calculate(
                contract: input.contract,
                weeks: payrollWeeks.weeks,
                rules: input.rules,
                evidence: payrollEvidence
            )
        } catch {
            return nil
        }

        let ceiling = SocialSecurityCeilingV2.calculate(
            .init(
                period: input.period,
                contractType: input.contract.type,
                contractualWeeklyMinutes: input.contract.contractualWeeklyMinutes,
                complementaryMinutes: payroll.complementaryMinutes,
                entryDate: civilDate(epochDay: input.contract.hireDateEpochDay),
                exitDate: nil,
                unpaidAbsenceDays: absenceImpact.unpaidFullCalendarDays,
                forfaitAnnualDays: input.contract.forfaitAnnualDays
            )
        )

        guard let deductionPeriod = CompanyEmployeeDeductionResolverV2.YearMonth(
            year: input.period.year,
            month: input.period.month
        ) else {
            return nil
        }

        let projection = EmployeeNetProjectionV2.calculate(
            .init(
                cashGross: payroll.grossEstimate,
                upstreamGrossReliable: payroll.grossReliable,
                benefits: input.benefits,
                year: input.period.year,
                ceiling: ceiling,
                alsaceMoselleLocalRegime: input.socialProfile.reliable
                    ? input.socialProfile.alsaceMoselleLocalRegime
                    : nil,
                professionalStatus: input.socialProfile.reliable
                    ? input.socialProfile.professionalStatus?.rawValue
                    : nil,
                protectionCategory: input.protectionCategory,
                companyDeductions: input.companyDeductions,
                period: deductionPeriod,
                incomeTaxRate: input.incomeTaxRate
            )
        )

        return SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: input.benefits,
            projection: projection,
            additionalWarnings: weeklyBoundary?.warnings ?? []
        )
    }

    private static func civilDate(epochDay: Int64?) -> PayrollCivilDateV2? {
        guard let epochDay else { return nil }
        let seconds = Double(epochDay) * 86_400.0
        guard seconds.isFinite else { return nil }

        let date = Date(timeIntervalSince1970: seconds)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year,
              let month = components.month,
              let day = components.day else {
            return nil
        }
        return PayrollCivilDateV2(year: year, month: month, day: day)
    }
}
