import Foundation

/// Couche nationale iOS de retraite complémentaire salariale et patronale, versionnée.
///
/// Les taux 2026 sont ceux du régime Agirc-Arrco. Les catégories conventionnelles éventuelles
/// sont injectées via `ProtectionCategoryV2` afin de ne jamais coder une convention particulière
/// dans le moteur national.
enum ComplementaryRetirementCatalogV2 {
    struct Line: Equatable {
        let id: String
        let label: String
        let baseAmount: Double
        let employeeRate: Double
        let employeeAmount: Double
        let source: String
        let employerRate: Double
        let employerAmount: Double
    }

    struct Estimate: Equatable {
        let lines: [Line]
        let employeeDeductions: Double
        let warnings: [String]
        let employerContributions: Double
    }

    private static let source = "Agirc-Arrco — barèmes applicables au 01/01/2026"

    static func estimate(
        gross: Double,
        year: Int,
        professionalStatus: String? = nil,
        ceiling: SocialSecurityCeilingV2.Snapshot? = nil,
        protectionCategory: ProtectionCategoryV2.Result? = nil
    ) -> Estimate {
        let safeGross = gross.isFinite ? max(0, gross) : 0
        guard let fullMonthlyPass = SocialSecurityCeilingV2.fullMonthly(year: year) else {
            return Estimate(
                lines: [],
                employeeDeductions: 0,
                warnings: ["Agirc-Arrco : barème non intégré pour \(year)"],
                employerContributions: 0
            )
        }

        let normalizedStatus = professionalStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let category = protectionCategory?.aniCategory
        let categoryControlsApec = protectionCategory?.conventionControlsAni == true
        let apecApplicable: Bool

        if !categoryControlsApec {
            apecApplicable = normalizedStatus == "CADRE"
        } else if protectionCategory?.confirmed != true {
            apecApplicable = false
        } else {
            apecApplicable = category == .article2_1 || category == .article2_2
        }

        let applicableMonthlyPass = ceiling?.applicableMonthly ?? fullMonthlyPass
        let fourTimesApplicable = ceiling?.fourTimesApplicable ?? fullMonthlyPass * 4
        let eightTimesApplicable = ceiling?.eightTimesApplicable ?? fullMonthlyPass * 8

        let tranche1 = min(safeGross, applicableMonthlyPass)
        let tranche2 = max(0, min(safeGross, eightTimesApplicable) - applicableMonthlyPass)
        var lines: [Line] = []

        func append(
            id: String,
            label: String,
            baseAmount: Double,
            employeeRate: Double,
            employerRate: Double
        ) {
            guard baseAmount > 0 else { return }
            lines.append(
                Line(
                    id: id,
                    label: label,
                    baseAmount: baseAmount,
                    employeeRate: employeeRate,
                    employeeAmount: baseAmount * employeeRate,
                    source: source,
                    employerRate: employerRate,
                    employerAmount: baseAmount * employerRate
                )
            )
        }

        append(
            id: "agirc_t1",
            label: "Agirc-Arrco tranche 1",
            baseAmount: tranche1,
            employeeRate: 0.0315,
            employerRate: 0.0472
        )
        append(
            id: "agirc_t2",
            label: "Agirc-Arrco tranche 2",
            baseAmount: tranche2,
            employeeRate: 0.0864,
            employerRate: 0.1295
        )
        append(
            id: "ceg_t1",
            label: "CEG tranche 1",
            baseAmount: tranche1,
            employeeRate: 0.0086,
            employerRate: 0.0129
        )
        append(
            id: "ceg_t2",
            label: "CEG tranche 2",
            baseAmount: tranche2,
            employeeRate: 0.0108,
            employerRate: 0.0162
        )

        if safeGross > applicableMonthlyPass {
            append(
                id: "cet",
                label: "CET",
                baseAmount: min(safeGross, eightTimesApplicable),
                employeeRate: 0.0014,
                employerRate: 0.0021
            )
        }

        if apecApplicable && safeGross > 0 {
            append(
                id: "apec",
                label: "APEC cadre / assimilé cadre",
                baseAmount: min(safeGross, fourTimesApplicable),
                employeeRate: 0.00024,
                employerRate: 0.00036
            )
        }

        var warnings = [
            "Les répartitions conventionnelles ou d'entreprise supérieures ou dérogatoires restent à confirmer lorsqu'elles existent."
        ]

        if categoryControlsApec && protectionCategory?.confirmed != true {
            warnings.append("Catégorie ANI 2.1/2.2 à confirmer : APEC non appliquée automatiquement.")
        } else if category == .extensionEligible {
            warnings.append(
                "Extension régime cadres possible : APEC non appliquée automatiquement car cette catégorie reste hors ANI 2.1/2.2."
            )
        } else if !categoryControlsApec,
                  normalizedStatus != "CADRE",
                  normalizedStatus != "NON_CADRE" {
            warnings.append("Statut professionnel à préciser : APEC non appliquée tant que le statut cadre n'est pas confirmé.")
        }

        if let protectionCategory {
            warnings.append(contentsOf: protectionCategory.warnings)
        }
        if let ceiling {
            warnings.append(contentsOf: ceiling.warnings)
        }

        return Estimate(
            lines: lines,
            employeeDeductions: lines.reduce(0) { $0 + $1.employeeAmount },
            warnings: unique(warnings),
            employerContributions: lines.reduce(0) { $0 + $1.employerAmount }
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
