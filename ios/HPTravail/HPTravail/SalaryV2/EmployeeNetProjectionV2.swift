import Foundation

/// Assemblage canonique iOS des retenues salariales connues vers un net fiable.
///
/// Le moteur calcule les sous-totaux connus pour permettre l'audit, mais il ne publie jamais
/// un net salarié final lorsqu'une donnée qui peut changer ce net est inconnue. Une absence de
/// donnée n'est jamais assimilée à zéro.
enum EmployeeNetProjectionV2 {
    struct Input {
        let cashGross: Double
        let upstreamGrossReliable: Bool
        let benefits: CompanyBenefitInKindContractV2.Snapshot
        let year: Int
        let ceiling: SocialSecurityCeilingV2.Snapshot
        let alsaceMoselleLocalRegime: Bool?
        let employerProtectionCsgCrdsBaseAmount: Double?
        let professionalStatus: String?
        let protectionCategory: ProtectionCategoryV2.Result
        let companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot
        let period: CompanyEmployeeDeductionResolverV2.YearMonth
    }

    struct Result {
        let cashGross: Double
        let contributionGross: Double
        let grossReliable: Bool
        let statutory: SocialContributionCatalogV2.Estimate
        let complementaryRetirement: ComplementaryRetirementCatalogV2.Estimate
        let companyCashDeductions: CompanyEmployeeDeductionPayrollBridgeV2.Result
        /// Montant calculable à partir des seules données connues. Il reste interne tant que
        /// `netBeforeIncomeTaxComplete` est faux.
        let knownNetBeforeIncomeTax: Double
        let netBeforeIncomeTax: Double?
        let netTaxable: Double?
        let netBeforeIncomeTaxComplete: Bool
        let netTaxableComplete: Bool
        let warnings: [String]
        let traces: [String]
    }

    static func calculate(_ input: Input) -> Result {
        let validCashGross = input.cashGross.isFinite && input.cashGross >= 0
        let safeCashGross = validCashGross ? input.cashGross : 0
        let validBenefits = input.benefits.totalGross.isFinite && input.benefits.totalGross >= 0
        let benefitsGross = validBenefits ? input.benefits.totalGross : 0
        let contributionGross = safeCashGross + benefitsGross
        let grossReliable = input.upstreamGrossReliable &&
            validCashGross &&
            input.benefits.reliable &&
            validBenefits &&
            contributionGross.isFinite

        let statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: contributionGross,
            year: input.year,
            ceiling: input.ceiling,
            alsaceMoselleLocalRegime: input.alsaceMoselleLocalRegime,
            employerProtectionCsgCrdsBaseAmount: input.employerProtectionCsgCrdsBaseAmount
        )
        let retirement = ComplementaryRetirementCatalogV2.estimate(
            gross: contributionGross,
            year: input.year,
            professionalStatus: input.professionalStatus,
            ceiling: input.ceiling,
            protectionCategory: input.protectionCategory
        )
        let direct = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            snapshot: input.companyDeductions,
            period: input.period
        )

        let directTotal = direct.deductions.reduce(0) { $0 + $1.amount }
        let knownBeforeTax = max(
            0,
            safeCashGross - statutory.employeeDeductions - retirement.employeeDeductions - directTotal
        )

        let normalizedStatus = input.professionalStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let statusComplete = normalizedStatus == "CADRE" || normalizedStatus == "NON_CADRE"
        let aniComplete = !input.protectionCategory.conventionControlsAni || input.protectionCategory.confirmed
        let supportedNationalTables = SocialSecurityCeilingV2.fullMonthly(year: input.year) != nil &&
            !SocialContributionCatalogV2.employeeRules(year: input.year).isEmpty
        let statutoryInputsComplete = input.alsaceMoselleLocalRegime != nil &&
            confirmedNonNegative(input.employerProtectionCsgCrdsBaseAmount) != nil

        var blockers: [String] = []
        if !supportedNationalTables { blockers.append("barèmes nationaux non intégrés pour \(input.year)") }
        if !input.upstreamGrossReliable { blockers.append("brut salarial amont incomplet") }
        if !validCashGross { blockers.append("brut en espèces invalide") }
        if !input.benefits.reliable || !validBenefits {
            blockers.append("avantages en nature du mois non confirmés")
        }
        if !input.ceiling.complete { blockers.append("plafond de Sécurité sociale incomplet") }
        if input.alsaceMoselleLocalRegime == nil { blockers.append("affiliation Alsace-Moselle à confirmer") }
        if confirmedNonNegative(input.employerProtectionCsgCrdsBaseAmount) == nil {
            blockers.append("part employeur de protection complémentaire soumise à CSG/CRDS à confirmer")
        }
        if !direct.confirmedEmployeeDeductionsComplete {
            blockers.append("mutuelle/prévoyance/transport salariés à confirmer")
        }
        if !statusComplete { blockers.append("statut professionnel cadre/non-cadre à confirmer") }
        if !aniComplete { blockers.append("catégorie ANI conventionnelle à confirmer") }

        let beforeTaxComplete = supportedNationalTables &&
            grossReliable &&
            input.ceiling.complete &&
            statutoryInputsComplete &&
            direct.confirmedEmployeeDeductionsComplete &&
            statusComplete &&
            aniComplete

        let employerProtectionTaxable = confirmedSnapshotAmount(
            input.companyDeductions[.employerProtectionTaxable]
        )
        let employeeProvidentNonDeductible = confirmedSnapshotAmount(
            input.companyDeductions[.employeeProvidentNonDeductible]
        )
        let taxInputsComplete = employerProtectionTaxable != nil && employeeProvidentNonDeductible != nil

        let nonDeductibleCsgCrds = statutory.lines
            .filter { $0.id == "csg_taxable" || $0.id == "crds" }
            .reduce(0) { $0 + $1.employeeAmount }

        let taxable: Double?
        if beforeTaxComplete,
           let employerProtectionTaxable,
           let employeeProvidentNonDeductible {
            taxable = max(
                0,
                knownBeforeTax +
                    benefitsGross +
                    nonDeductibleCsgCrds +
                    employerProtectionTaxable +
                    employeeProvidentNonDeductible
            )
        } else {
            taxable = nil
        }

        var warnings = input.benefits.warnings +
            input.ceiling.warnings +
            statutory.warnings +
            retirement.warnings +
            direct.warnings +
            input.protectionCategory.warnings

        if !beforeTaxComplete {
            warnings.append(
                "Net avant impôt incomplet : \(unique(blockers).joined(separator: " ; ")). Les sous-totaux connus restent calculables mais aucun net salarié final n'est affiché."
            )
        }
        if beforeTaxComplete && !taxInputsComplete {
            var taxableBlockers: [String] = []
            if employerProtectionTaxable == nil {
                taxableBlockers.append("part employeur mutuelle/prévoyance réintégrable au net imposable")
            }
            if employeeProvidentNonDeductible == nil {
                taxableBlockers.append("part salariale de prévoyance non déductible")
            }
            warnings.append(
                "Net imposable incomplet : \(taxableBlockers.joined(separator: " ; ")) à confirmer. Le net avant impôt reste utilisable."
            )
        }

        let traces = unique(
            direct.traces + [
                "Brut social : brut en espèces + avantages en nature confirmés.",
                "Net avant impôt : brut en espèces - cotisations légales - retraite complémentaire - retenues entreprise réellement prélevées.",
                "Part salariale de prévoyance non déductible : réintégrée au net imposable sans seconde retenue sur le net en espèces."
            ]
        )

        return Result(
            cashGross: safeCashGross,
            contributionGross: contributionGross,
            grossReliable: grossReliable,
            statutory: statutory,
            complementaryRetirement: retirement,
            companyCashDeductions: direct,
            knownNetBeforeIncomeTax: knownBeforeTax,
            netBeforeIncomeTax: beforeTaxComplete ? knownBeforeTax : nil,
            netTaxable: taxable,
            netBeforeIncomeTaxComplete: beforeTaxComplete,
            netTaxableComplete: beforeTaxComplete && taxInputsComplete,
            warnings: unique(warnings),
            traces: traces
        )
    }

    private static func confirmedNonNegative(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value >= 0 else { return nil }
        return value
    }

    private static func confirmedSnapshotAmount(
        _ value: CompanyEmployeeDeductionResolverV2.Value
    ) -> Double? {
        guard value.reliable, let amount = value.amount else { return nil }
        return confirmedNonNegative(amount)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
