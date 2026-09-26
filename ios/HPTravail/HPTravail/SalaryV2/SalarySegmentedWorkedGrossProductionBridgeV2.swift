import Foundation

struct SalarySegmentedWorkedGrossDetailedBridgeResultV2 {
    let worked: SalarySegmentedWorkedGrossProductionResultV2?
    let reliable: Bool
    let warnings: [String]
}

/// Pont production entre les stores confirmés et la chaîne B21 -> B20.
///
/// Les preuves juridiques de primes sont fournies explicitement par la couche d'arbitrage.
/// L'absence d'une règle n'est jamais transformée ici en preuve d'absence.
enum SalarySegmentedWorkedGrossProductionBridgeV2 {
    static func calculateFromStores(
        defaults: UserDefaults = .standard,
        companyId: String,
        companyAddress: String,
        period: YearMonthV2,
        timeZoneId: String,
        work: SalaryWorkSessionSourceV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        now: Date
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        let detailed = calculateDetailedFromStores(
            defaults: defaults,
            companyId: companyId,
            companyAddress: companyAddress,
            period: period,
            timeZoneId: timeZoneId,
            work: work,
            contracts: contracts,
            rules: rules,
            now: now
        )
        return detailed.worked?.assembly ?? blocked(detailed.warnings)
    }

    static func calculateDetailedFromStores(
        defaults: UserDefaults = .standard,
        companyId: String,
        companyAddress: String,
        period: YearMonthV2,
        timeZoneId: String,
        work: SalaryWorkSessionSourceV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        now: Date
    ) -> SalarySegmentedWorkedGrossDetailedBridgeResultV2 {
        let night = SalaryConventionNightRuleStoreV2.readConfirmed(defaults: defaults)
        let premiumContext = SalarySegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts: contracts,
            rules: rules,
            nightSnapshots: night.snapshots,
            nightSourceReliable: night.reliable,
            nightWarnings: night.warnings,
            holidayScope: FrenchPublicHolidayCalendarV2.scopeForAddress(companyAddress),
            now: now
        )
        guard premiumContext.reliable else { return detailedBlocked(premiumContext.warnings) }
        return calculateDetailed(
            defaults: defaults,
            companyId: companyId,
            period: period,
            timeZoneId: timeZoneId,
            work: work,
            contracts: contracts,
            rules: rules,
            premiums: premiumContext.evidence,
            now: now
        )
    }

    static func calculate(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: YearMonthV2,
        timeZoneId: String,
        work: SalaryWorkSessionSourceV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        let detailed = calculateDetailed(
            defaults: defaults,
            companyId: companyId,
            period: period,
            timeZoneId: timeZoneId,
            work: work,
            contracts: contracts,
            rules: rules,
            premiums: premiums,
            now: now
        )
        return detailed.worked?.assembly ?? blocked(detailed.warnings)
    }

    static func calculateDetailed(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: YearMonthV2,
        timeZoneId: String,
        work: SalaryWorkSessionSourceV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossDetailedBridgeResultV2 {
        guard let bounds = coverageBounds(
            start: contracts.periodStartEpochDay,
            end: contracts.periodEndEpochDay
        ) else {
            return detailedBlocked("Brut segmenté : bornes de couverture hebdomadaire invalides.")
        }

        let proration = SalarySegmentedProrationStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )
        let source = SalaryPayrollCoverageStoreV2.source(
            work: work,
            employerId: companyId,
            coveredStartEpochDay: bounds.start,
            coveredEndEpochDay: bounds.end,
            timeZoneId: timeZoneId,
            now: now,
            defaults: defaults
        )
        let worked = SalarySegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts: contracts,
            rules: rules,
            prorationSource: proration,
            source: source,
            premiums: premiums,
            now: now
        )
        return .init(
            worked: worked,
            reliable: worked.reliable,
            warnings: worked.warnings
        )
    }

    static func coverageBounds(
        start: Int64,
        end: Int64
    ) -> (start: Int64, end: Int64)? {
        guard end >= start else { return nil }
        let firstMonday = start - ((start % 7 + 10) % 7)
        let lastMonday = end - ((end % 7 + 10) % 7)
        let addition = lastMonday.addingReportingOverflow(6)
        guard !addition.overflow else { return nil }
        return (firstMonday, addition.partialValue)
    }

    private static func detailedBlocked(
        _ warning: String
    ) -> SalarySegmentedWorkedGrossDetailedBridgeResultV2 {
        detailedBlocked([warning])
    }

    private static func detailedBlocked(
        _ warnings: [String]
    ) -> SalarySegmentedWorkedGrossDetailedBridgeResultV2 {
        .init(worked: nil, reliable: false, warnings: Array(Set(warnings)).sorted())
    }

    private static func blocked(
        _ warning: String
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        blocked([warning])
    }

    private static func blocked(
        _ warnings: [String]
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: nil,
            variableGross: nil,
            workedGross: nil,
            reliable: false,
            warnings: Array(Set(warnings)).sorted()
        )
    }
}
