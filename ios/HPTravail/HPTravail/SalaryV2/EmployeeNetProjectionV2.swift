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
        let professionalStatus: String?
        let protectionCategory: ProtectionCategoryV2.Result
        let companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot
        let period: CompanyEmployeeDeductionResolverV2.YearMonth
        let incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?
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
        let incomeTax: Double?
        let netAfterIncomeTax: Double?
        let netBeforeIncomeTaxComplete: Bool
        let netTaxableComplete: Bool
        let statutoryEmployerContributions: Double
        let complementaryRetirementEmployer: Double
        let knownEmployerContributions: Double
        let knownEmployerCost: Double?
        let employerCostComplete: Bool
        let employerCostWarnings: [String]
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

        let employerProtectionCsgCrdsBase = confirmedSnapshotAmount(
            input.companyDeductions[.employerProtectionCsgCrdsBase]
        )
        let statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: contributionGross,
            year: input.year,
            ceiling: input.ceiling,
            alsaceMoselleLocalRegime: input.alsaceMoselleLocalRegime,
            employerProtectionCsgCrdsBaseAmount: employerProtectionCsgCrdsBase
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

        // Les lignes de paie sont des montants monétaires : on arrondit chaque ligne au centime
        // avant d'assembler les totaux, plutôt que d'arrondir uniquement le résultat final.
        let statutoryTotal = statutory.lines.reduce(0) { $0 + roundedCurrency($1.employeeAmount) }
        let retirementTotal = retirement.lines.reduce(0) { $0 + roundedCurrency($1.employeeAmount) }
        let directTotal = direct.deductions.reduce(0) { $0 + roundedCurrency($1.amount) }

        // Parité Android : ces montants patronaux sont des sous-totaux connus,
        // jamais un coût employeur complet. On arrondit ligne par ligne au centime.
        let statutoryEmployerTotal = statutory.lines.reduce(0) {
            $0 + roundedCurrency($1.employerAmount)
        }
        let retirementEmployerTotal = retirement.lines.reduce(0) {
            $0 + roundedCurrency($1.employerAmount)
        }
        let knownEmployerContributions = roundedCurrency(
            statutoryEmployerTotal + retirementEmployerTotal
        )
        let knownEmployerCost = grossReliable
            ? roundedCurrency(contributionGross + knownEmployerContributions)
            : nil
        let employerCostWarnings = unique([
            "Coût employeur total : seules les cotisations patronales nationales déjà intégrées et la retraite complémentaire sont incluses dans ce sous-total ; AT/MP, mobilité, chômage/AGS, FNAL, formation, maladie/famille, apprentissage, prévoyance et réductions restent à raccorder avant tout total complet."
        ] + statutory.warnings + retirement.warnings)

        let rawBeforeTax = safeCashGross - statutoryTotal - retirementTotal - directTotal
        let knownBeforeTax = max(0, rawBeforeTax)

        let normalizedStatus = input.professionalStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let statusComplete = normalizedStatus == "CADRE" || normalizedStatus == "NON_CADRE"
        let aniComplete = !input.protectionCategory.conventionControlsAni || input.protectionCategory.confirmed
        let supportedNationalTables = SocialSecurityCeilingV2.fullMonthly(year: input.year) != nil &&
            !SocialContributionCatalogV2.employeeRules(year: input.year).isEmpty
        let statutoryInputsComplete = input.alsaceMoselleLocalRegime != nil &&
            employerProtectionCsgCrdsBase != nil

        var blockers: [String] = []
        if !supportedNationalTables { blockers.append("barèmes nationaux non intégrés pour \(input.year)") }
        if !input.upstreamGrossReliable { blockers.append("brut salarial amont incomplet") }
        if !validCashGross { blockers.append("brut en espèces invalide") }
        if !input.benefits.reliable || !validBenefits {
            blockers.append("avantages en nature du mois non confirmés")
        }
        if !input.ceiling.complete { blockers.append("plafond de Sécurité sociale incomplet") }
        if input.alsaceMoselleLocalRegime == nil { blockers.append("affiliation Alsace-Moselle à confirmer") }
        if employerProtectionCsgCrdsBase == nil {
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
                rawBeforeTax +
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

        let taxRate = input.incomeTaxRate
        let incomeTax: Double?
        let netAfterIncomeTax: Double?
        if let taxable,
           taxable.isFinite,
           let rateSnapshot = taxRate,
           rateSnapshot.reliable,
           let rate = rateSnapshot.rate,
           rate.isFinite,
           rate >= 0,
           rate <= 1,
           beforeTaxComplete {
            let calculatedTax = roundedCurrency(taxable * rate)
            if calculatedTax.isFinite {
                incomeTax = calculatedTax
                netAfterIncomeTax = max(0, knownBeforeTax - calculatedTax)
            } else {
                incomeTax = nil
                netAfterIncomeTax = nil
            }
        } else {
            incomeTax = nil
            netAfterIncomeTax = nil
        }
        let usableTaxRate = taxRate.flatMap { snapshot -> Double? in
            guard snapshot.reliable,
                  let rate = snapshot.rate,
                  rate.isFinite,
                  rate >= 0,
                  rate <= 1 else { return nil }
            return rate
        }
        if taxable != nil && usableTaxRate == nil {
            warnings.append("PAS : taux personnel daté et confirmé indisponible ou invalide ; aucun net après impôt n'est affiché.")
        }
        warnings.append(contentsOf: taxRate?.warnings ?? [])

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
            incomeTax: incomeTax,
            netAfterIncomeTax: netAfterIncomeTax,
            netBeforeIncomeTaxComplete: beforeTaxComplete,
            netTaxableComplete: beforeTaxComplete && taxInputsComplete,
            statutoryEmployerContributions: statutoryEmployerTotal,
            complementaryRetirementEmployer: retirementEmployerTotal,
            knownEmployerContributions: knownEmployerContributions,
            knownEmployerCost: knownEmployerCost,
            employerCostComplete: false,
            employerCostWarnings: employerCostWarnings,
            warnings: unique(warnings),
            traces: traces
        )
    }

    private static func roundedCurrency(_ value: Double) -> Double {
        (value * 100).rounded() / 100
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
