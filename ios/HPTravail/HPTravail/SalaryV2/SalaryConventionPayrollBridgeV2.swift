import Foundation

struct SalaryConventionPayrollSnapshotV2: Equatable {
    let coverage: SalaryConventionCoverageV2
    let rules: PayrollRulesV2?
    let warnings: [String]
    let compatibility: SalaryConventionSegmentPayrollCompatibilityResultV2?

    var readyForSingleRulesCalculation: Bool {
        coverage.sourceReliable && coverage.fullyCovered && rules != nil
    }
}

/// Bridge conventionnel autoritatif de l'espace Salaire iOS.
///
/// Une version unique couvrant tout le mois fournit directement ses règles.
/// Plusieurs versions ne peuvent être promues vers un calcul mensuel unique que si leurs
/// paramètres PayrollRulesV2 sont strictement identiques. Toute différence reste bloquante.
enum SalaryConventionPayrollBridgeV2 {
    static func resolve(
        companyId: String,
        period: YearMonthV2,
        companies: SalaryCompanyReadResultV2 = SalaryCompanyStoreV2.readConfirmed(),
        storedRules: SalaryConventionRuleReadResultV2 = SalaryConventionRuleStoreV2.readConfirmed()
    ) -> SalaryConventionPayrollSnapshotV2 {
        let rawCoverage = SalaryConventionCoverageResolverV2.resolve(
            companyId: companyId,
            period: period,
            companies: companies,
            rules: storedRules
        )

        if let single = rawCoverage.singleSnapshotForWholePeriod {
            return SalaryConventionPayrollSnapshotV2(
                coverage: rawCoverage,
                rules: single.rules,
                warnings: rawCoverage.warnings,
                compatibility: SalaryConventionSegmentPayrollCompatibilityV2.resolve(
                    rawCoverage.segments
                )
            )
        }

        let compatibility = rawCoverage.segments.isEmpty
            ? nil
            : SalaryConventionSegmentPayrollCompatibilityV2.resolve(rawCoverage.segments)

        let canPromoteEquivalentVersions =
            rawCoverage.requiresMultipleRuleVersions &&
            compatibility?.compatibleForSingleMonthlyCalculation == true &&
            compatibility?.rules != nil

        let warnings: [String]
        let rules: PayrollRulesV2?
        if canPromoteEquivalentVersions {
            rules = compatibility?.rules
            warnings = unique(
                rawCoverage.warnings.filter {
                    $0 != SalaryConventionCoverageResolverV2.multipleVersionsWarning
                } + (compatibility?.warnings ?? [])
            )
        } else {
            rules = nil
            warnings = unique(
                rawCoverage.warnings + (compatibility?.warnings ?? [])
            )
        }

        let coverage = SalaryConventionCoverageV2(
            companyId: rawCoverage.companyId,
            idcc: rawCoverage.idcc,
            periodStartEpochDay: rawCoverage.periodStartEpochDay,
            periodEndEpochDay: rawCoverage.periodEndEpochDay,
            segments: rawCoverage.segments,
            sourceReliable: rawCoverage.sourceReliable,
            fullyCovered: rawCoverage.fullyCovered,
            warnings: warnings
        )

        return SalaryConventionPayrollSnapshotV2(
            coverage: coverage,
            rules: rules,
            warnings: warnings,
            compatibility: compatibility
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
