import Foundation

/// Prouve les composantes fixes d'un mois segmenté sans choisir arbitrairement
/// une seule version de la règle d'ancienneté. Les primes d'entreprise restent
/// gouvernées par leur confirmation mensuelle exhaustive existante.
enum SalarySegmentedFixedCashComponentsBridgeV2 {
    static let seniorityTransitionWarning =
        "Brut fixe segmenté : règle, palier ou montant d'ancienneté différent pendant le mois ; proratisation à confirmer."
    static let sourceWarning =
        "Brut fixe segmenté : entreprise, convention ou mois non confirmé."

    static func load(
        defaults: UserDefaults = .standard,
        companyId: String,
        idcc: String,
        period: YearMonthV2,
        actualMonthlyBaseGross: Double?,
        professionalStatus: String?
    ) -> SalaryConfirmedCashGrossComponentsV2 {
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard let company = SalaryCompanyStoreV2.confirmedCompany(
            companies, companyId: companyId
        ),
        SalaryConventionSeniorityIdccV2.normalize(company.idcc) ==
            SalaryConventionSeniorityIdccV2.normalize(idcc),
        !SalaryConventionSeniorityIdccV2.normalize(idcc).isEmpty,
        let count = PayrollCivilDateV2.daysInMonth(year: period.year, month: period.month),
        SalaryConventionCoverageResolverV2.monthEpochDayRange(period) != nil else {
            return blocked([sourceWarning] + companies.warnings)
        }

        let daily = (1...count).compactMap { day -> SalaryConventionSeniorityPremiumV2.Result? in
            guard let date = PayrollCivilDateV2(
                year: period.year, month: period.month, day: day
            ) else { return nil }
            return SalaryConventionSeniorityPremiumBridgeV2.load(
                defaults: defaults,
                companyId: company.id,
                idcc: company.idcc,
                referenceDate: date,
                actualMonthlyBaseGross: actualMonthlyBaseGross,
                conventionalMinimumMonthlyGross: nil,
                professionalStatus: professionalStatus
            ).result
        }
        let premiums = CompanyPremiumStoreV2.resolve(
            companyId: company.id, period: period, defaults: defaults
        )
        let coverage = CompanyPremiumStoreV2.monthCoverage(
            companyId: company.id, period: period, defaults: defaults
        )
        return assemble(
            period: period,
            dailySeniority: daily,
            companyPremiums: premiums,
            coverage: coverage
        )
    }

    static func assemble(
        period: YearMonthV2,
        dailySeniority: [SalaryConventionSeniorityPremiumV2.Result],
        companyPremiums: CompanyPremiumContractV2.Snapshot,
        coverage: CompanyPremiumStoreV2.MonthCoverageSnapshot
    ) -> SalaryConfirmedCashGrossComponentsV2 {
        guard let count = PayrollCivilDateV2.daysInMonth(
            year: period.year, month: period.month
        ), dailySeniority.count == count,
        let first = dailySeniority.first,
        first.reliable,
        let amount = first.monthlyAmount,
        amount.isFinite, amount >= 0,
        dailySeniority.allSatisfy({ item in
            item.reliable && item.applicable == first.applicable &&
                item.selectedRule == first.selectedRule &&
                item.stepYears == first.stepYears &&
                item.rate == first.rate &&
                item.monthlyAmount == amount
        }) else {
            return blocked(
                dailySeniority.flatMap(\.warnings) + [seniorityTransitionWarning]
                    + companyPremiums.warnings + coverage.warnings
            )
        }
        return SalaryConfirmedCashGrossComponentsBridgeV2.assemble(
            period: period,
            seniority: first,
            companyPremiums: companyPremiums,
            coverage: coverage,
            upstreamWarnings: dailySeniority.flatMap(\.warnings)
        )
    }

    private static func blocked(_ warnings: [String]) -> SalaryConfirmedCashGrossComponentsV2 {
        .init(
            components: [],
            exhaustive: false,
            sourceId: "",
            warnings: Array(Set(warnings)).sorted()
        )
    }
}
