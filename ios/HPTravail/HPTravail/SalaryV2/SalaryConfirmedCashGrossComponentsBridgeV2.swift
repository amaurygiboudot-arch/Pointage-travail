import Foundation

/// Adapte les propriétaires iOS de composantes fixes vers le contrat cash segmenté.
/// Aucun montant n'est recalculé ici.
enum SalaryConfirmedCashGrossComponentsBridgeV2 {
    static func load(
        defaults: UserDefaults = .standard,
        companyId: String,
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        period: YearMonthV2,
        actualMonthlyBaseGross: Double?,
        conventionalMinimumMonthlyGross: Double?,
        professionalStatus: String? = nil
    ) -> SalaryConfirmedCashGrossComponentsV2 {
        let seniority = SalaryConventionSeniorityPremiumBridgeV2.load(
            defaults: defaults,
            companyId: companyId,
            idcc: idcc,
            referenceDate: referenceDate,
            actualMonthlyBaseGross: actualMonthlyBaseGross,
            conventionalMinimumMonthlyGross: conventionalMinimumMonthlyGross,
            professionalStatus: professionalStatus
        ).result
        let companyPremiums = CompanyPremiumStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )
        let coverage = CompanyPremiumStoreV2.monthCoverage(
            companyId: companyId,
            period: period,
            defaults: defaults
        )
        return assemble(
            period: period,
            seniority: seniority,
            companyPremiums: companyPremiums,
            coverage: coverage
        )
    }

    static func assemble(
        period: YearMonthV2,
        seniority: SalaryConventionSeniorityPremiumV2.Result,
        companyPremiums: CompanyPremiumContractV2.Snapshot,
        coverage: CompanyPremiumStoreV2.MonthCoverageSnapshot,
        upstreamWarnings: [String] = []
    ) -> SalaryConfirmedCashGrossComponentsV2 {
        let warnings = unique(
            upstreamWarnings
            + seniority.warnings
            + companyPremiums.warnings
            + coverage.warnings
        )

        let seniorityAmount = seniority.monthlyAmount
        let seniorityValid = seniority.reliable
            && seniorityAmount != nil
            && seniorityAmount!.isFinite
            && seniorityAmount! >= 0

        let premiumsValid = companyPremiums.reliable
            && coverage.storageReliable
            && coverage.confirmed
            && !(coverage.source ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty

        var components: [SalaryConfirmedCashGrossComponentV2] = []
        if seniorityValid, let seniorityAmount {
            components.append(
                SalaryConfirmedCashGrossComponentV2(
                    id: "seniority-premium",
                    amount: seniorityAmount,
                    reliable: true,
                    warnings: seniority.warnings
                )
            )
        }

        if premiumsValid {
            components.append(contentsOf: companyPremiums.applied.map { premium in
                SalaryConfirmedCashGrossComponentV2(
                    id: "company-premium:\(premium.id)",
                    amount: premium.grossAmount,
                    reliable: true
                )
            })
        }

        let exhaustive = seniorityValid && premiumsValid
        let senioritySource: String
        if let ruleId = seniority.selectedRule?.ruleId.trimmingCharacters(in: .whitespacesAndNewlines),
           !ruleId.isEmpty {
            senioritySource = ruleId
        } else {
            senioritySource = seniorityValid ? "confirmed-none" : "unconfirmed"
        }
        let premiumSource = coverage.source?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let sourceId = exhaustive
            ? "fixed-cash-v1|period=\(period)|seniority=\(senioritySource)|premiums=\(premiumSource)"
            : ""

        return SalaryConfirmedCashGrossComponentsV2(
            components: components,
            exhaustive: exhaustive,
            sourceId: sourceId,
            warnings: warnings
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
