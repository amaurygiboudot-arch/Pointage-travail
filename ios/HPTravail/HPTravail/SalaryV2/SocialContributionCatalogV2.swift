import Foundation

/// Couche légale nationale iOS des retenues salariales de base.
///
/// Les taux sont datés. HoraTrack ne remplace jamais la paie par un pourcentage
/// global brut -> net et ne transforme jamais une donnée inconnue en zéro confirmé.
enum SocialContributionCatalogV2 {
    enum Base: Equatable {
        case gross
        case csgCrds2026
        case grossCappedMonthlyPass
    }

    struct Rule: Equatable {
        let id: String
        let label: String
        let employeeRate: Double
        let base: Base
        let validFromYear: Int
        let validToYear: Int?
        let source: String
        let employerRate: Double
    }

    struct Line: Equatable {
        let id: String
        let label: String
        let baseAmount: Double
        let rate: Double
        let employeeAmount: Double
        let source: String
        let employerRate: Double
        let employerAmount: Double
    }

    struct Estimate: Equatable {
        let gross: Double
        let employeeDeductions: Double
        let netBeforeIncomeTax: Double
        let lines: [Line]
        let warnings: [String]
        let employerContributions: Double
    }

    private static let rules2026: [Rule] = [
        Rule(
            id: "old_age_uncapped",
            label: "Assurance vieillesse déplafonnée",
            employeeRate: 0.0040,
            base: .gross,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — taux secteur privé 2026",
            employerRate: 0.0211
        ),
        Rule(
            id: "old_age_capped",
            label: "Assurance vieillesse plafonnée",
            employeeRate: 0.0690,
            base: .grossCappedMonthlyPass,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — taux secteur privé 2026",
            employerRate: 0.0855
        ),
        Rule(
            id: "csa_employer",
            label: "Contribution solidarité autonomie",
            employeeRate: 0,
            base: .gross,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — taux secteur privé 2026",
            employerRate: 0.0030
        ),
        Rule(
            id: "social_dialogue_employer",
            label: "Contribution au dialogue social",
            employeeRate: 0,
            base: .gross,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — taux secteur privé 2026",
            employerRate: 0.00016
        ),
        Rule(
            id: "csg_deductible",
            label: "CSG déductible",
            employeeRate: 0.0680,
            base: .csgCrds2026,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — CSG/CRDS revenus d'activité 2026",
            employerRate: 0
        ),
        Rule(
            id: "csg_taxable",
            label: "CSG imposable",
            employeeRate: 0.0240,
            base: .csgCrds2026,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — CSG/CRDS revenus d'activité 2026",
            employerRate: 0
        ),
        Rule(
            id: "crds",
            label: "CRDS",
            employeeRate: 0.0050,
            base: .csgCrds2026,
            validFromYear: 2026,
            validToYear: 2026,
            source: "Urssaf — CSG/CRDS revenus d'activité 2026",
            employerRate: 0
        )
    ]

    private static let alsaceMoselle2026 = Rule(
        id: "alsace_moselle_local_health",
        label: "Régime local Alsace-Moselle — cotisation maladie supplémentaire",
        employeeRate: 0.0130,
        base: .gross,
        validFromYear: 2026,
        validToYear: 2026,
        source: "Urssaf / Régime Local Alsace-Moselle — taux 2026",
        employerRate: 0
    )

    static func employeeRules(year: Int) -> [Rule] {
        year == 2026 ? rules2026 : []
    }

    static func estimateEmployeeDeductions(
        gross: Double,
        year: Int,
        ceiling: SocialSecurityCeilingV2.Snapshot? = nil,
        alsaceMoselleLocalRegime: Bool? = nil,
        employerProtectionCsgCrdsBaseAmount: Double? = nil
    ) -> Estimate {
        let safeGross = gross.isFinite ? max(0, gross) : 0
        let rules = employeeRules(year: year)
        guard !rules.isEmpty else {
            return Estimate(
                gross: safeGross,
                employeeDeductions: 0,
                netBeforeIncomeTax: safeGross,
                lines: [],
                warnings: ["Cotisations salariales : barème non intégré pour \(year)"],
                employerContributions: 0
            )
        }

        let validEmployerProtection: Double?
        if let amount = employerProtectionCsgCrdsBaseAmount, amount.isFinite, amount >= 0 {
            validEmployerProtection = amount
        } else {
            validEmployerProtection = nil
        }

        let monthlyPass = ceiling?.applicableMonthly
            ?? SocialSecurityCeilingV2.fullMonthly(year: year)
            ?? Double.infinity

        var lines = rules.map { rule -> Line in
            let baseAmount: Double
            switch rule.base {
            case .gross:
                baseAmount = safeGross
            case .csgCrds2026:
                baseAmount = csgCrdsBase2026(
                    gross: safeGross,
                    applicableMonthlyPass: monthlyPass,
                    employerProtectionCsgCrdsBaseAmount: validEmployerProtection ?? 0
                )
            case .grossCappedMonthlyPass:
                baseAmount = min(safeGross, monthlyPass)
            }

            return Line(
                id: rule.id,
                label: rule.label,
                baseAmount: baseAmount,
                rate: rule.employeeRate,
                employeeAmount: baseAmount * rule.employeeRate,
                source: rule.source,
                employerRate: rule.employerRate,
                employerAmount: baseAmount * rule.employerRate
            )
        }

        if year == 2026, alsaceMoselleLocalRegime == true, safeGross > 0 {
            lines.append(
                Line(
                    id: alsaceMoselle2026.id,
                    label: alsaceMoselle2026.label,
                    baseAmount: safeGross,
                    rate: alsaceMoselle2026.employeeRate,
                    employeeAmount: safeGross * alsaceMoselle2026.employeeRate,
                    source: alsaceMoselle2026.source,
                    employerRate: 0,
                    employerAmount: 0
                )
            )
        }

        let employeeTotal = lines.reduce(0) { $0 + $1.employeeAmount }
        let employerTotal = lines.reduce(0) { $0 + $1.employerAmount }
        var warnings = [
            "Couche légale iOS : ce net est volontairement partiel tant que les couches complémentaires ne sont pas raccordées.",
            "Les parts patronales vieillesse, CSA et dialogue social 2026 sont exposées séparément ; les autres cotisations patronales légales restent hors de ce sous-total."
        ]

        switch employerProtectionCsgCrdsBaseAmount {
        case nil:
            warnings.append(
                "Assiette CSG/CRDS : part employeur de protection sociale complémentaire à confirmer, même si elle est nulle ; le sous-total courant reste calculé sur le brut connu uniquement."
            )
        case let amount? where !amount.isFinite || amount < 0:
            warnings.append(
                "Assiette CSG/CRDS : part employeur de protection sociale complémentaire invalide ; aucune valeur n'est inventée."
            )
        default:
            warnings.append(
                "Assiette CSG/CRDS : part employeur de protection sociale complémentaire confirmée ajoutée après l'abattement applicable au salaire."
            )
        }

        warnings.append(
            "Retraite complémentaire, CEG/CET, mutuelle/prévoyance, convention et retenues propres à l'entreprise sont traitées dans les couches suivantes."
        )

        if year == 2026, alsaceMoselleLocalRegime == nil {
            warnings.append(
                "Régime local Alsace-Moselle : affiliation à confirmer ; aucune cotisation locale n'est inventée."
            )
        }
        if year == 2026, alsaceMoselleLocalRegime == true {
            warnings.append(
                "Régime local Alsace-Moselle confirmé : cotisation salariale maladie supplémentaire de 1,30 % appliquée au brut déplafonné."
            )
        }
        if let ceiling {
            warnings.append(contentsOf: ceiling.warnings)
        }

        return Estimate(
            gross: safeGross,
            employeeDeductions: employeeTotal,
            netBeforeIncomeTax: max(0, safeGross - employeeTotal),
            lines: lines,
            warnings: unique(warnings),
            employerContributions: employerTotal
        )
    }

    private static func csgCrdsBase2026(
        gross: Double,
        applicableMonthlyPass: Double,
        employerProtectionCsgCrdsBaseAmount: Double
    ) -> Double {
        let cap = max(0, applicableMonthlyPass * 4)
        let abatedPart = min(gross, cap)
        let excess = max(0, gross - cap)
        return abatedPart * 0.9825 + excess + employerProtectionCsgCrdsBaseAmount
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
