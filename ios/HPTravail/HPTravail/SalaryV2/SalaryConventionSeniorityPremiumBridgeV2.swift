import Foundation

/// Pont iOS autoritatif entre preuves officielles, stores confirmés et moteur d'ancienneté.
/// Aucune règle KALI n'est appliquée tant que l'absence d'une règle ACCO concurrente
/// n'est pas explicitement prouvée pour l'entreprise et la période.
enum SalaryConventionSeniorityPremiumBridgeV2 {
    struct Snapshot {
        let result: SalaryConventionSeniorityPremiumV2.Result
        let coverage: ConventionMatterCoverageV2.Snapshot
    }

    struct RuntimeSourceSelection {
        let rules: [SalaryConventionSeniorityPremiumV2.Rule]
        let confirmedNoRule: Bool
        let reliable: Bool
        let warnings: [String]
    }

    static func load(
        defaults: UserDefaults = .standard,
        companyId: String,
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        actualMonthlyBaseGross: Double?,
        conventionalMinimumMonthlyGross: Double?,
        professionalStatus: String? = nil
    ) -> Snapshot {
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard let company = SalaryCompanyStoreV2.confirmedCompany(
            companies,
            companyId: companyId
        ) else {
            return blocked(
                idcc: idcc,
                coverage: incompleteCoverage(
                    warning: "Prime d'ancienneté : entreprise absente ou stockage entreprise non fiable."
                ),
                warnings: companies.warnings
            )
        }

        let normalizedIdcc = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        let classification = SalaryConventionClassificationStoreV2.load(
            companyId: companyId,
            defaults: defaults
        )
        let stored = SalaryConventionSeniorityPremiumStoreV2.rules(
            idcc: normalizedIdcc,
            defaults: defaults
        )
        let coverage = SalaryConventionMatterCoverageStoreV2.resolve(
            defaults: defaults,
            idcc: normalizedIdcc,
            matter: .seniorityPremium,
            date: referenceDate,
            classification: classification,
            professionalStatus: professionalStatus
        )
        let knowledge = SalaryPayrollSourceKnowledgeStoreV2.seniorityKnowledge(
            companyId: companyId,
            idcc: normalizedIdcc,
            referenceDate: referenceDate,
            currentSiret: company.siret,
            defaults: defaults
        )
        let runtime = selectOfficialRuntimeSource(
            stored: stored,
            coverage: coverage,
            idcc: normalizedIdcc,
            classification: classification,
            referenceDate: referenceDate,
            accoKnowledge: knowledge.knowledge[.acco] ?? .unknown,
            sourceKnowledgeReliable: knowledge.reliable,
            sourceKnowledgeWarnings: knowledge.warnings
        )

        guard runtime.reliable else {
            return blocked(
                idcc: normalizedIdcc,
                coverage: coverage,
                warnings: runtime.warnings + stored.warnings + coverage.warnings
            )
        }

        if runtime.confirmedNoRule {
            return Snapshot(
                result: SalaryConventionSeniorityPremiumV2.Result(
                    applicable: false,
                    reliable: true,
                    selectedRule: nil,
                    stepYears: nil,
                    rate: nil,
                    monthlyAmount: 0,
                    warnings: unique(runtime.warnings)
                ),
                coverage: coverage
            )
        }

        let inputs = SalaryConventionSeniorityInputStoreV2.read(
            companyId: companyId,
            defaults: defaults
        )
        guard inputs.reliable else {
            return blocked(
                idcc: normalizedIdcc,
                coverage: coverage,
                warnings: inputs.warnings + runtime.warnings + coverage.warnings
            )
        }

        let record = inputs.record
        let seniorityDate = record?.seniorityDateConfirmed == true ? record?.seniorityDate : nil
        let supplement = record?.monthlySupplementConfirmed == true ? record?.monthlySupplement : nil
        let calculated = SalaryConventionSeniorityPremiumV2.calculate(
            rules: runtime.rules,
            idcc: normalizedIdcc,
            classification: classification,
            referenceDate: referenceDate,
            confirmedSeniorityDate: seniorityDate,
            actualMonthlyBaseGross: actualMonthlyBaseGross,
            conventionalMinimumMonthlyGross: conventionalMinimumMonthlyGross,
            confirmedMonthlySupplement: supplement,
            companyApplicabilityConfirmed: false
        )
        let warnings = unique(
            calculated.warnings
            + runtime.warnings
            + stored.warnings
            + coverage.warnings
            + inputs.warnings
        )
        return Snapshot(
            result: SalaryConventionSeniorityPremiumV2.Result(
                applicable: calculated.applicable,
                reliable: calculated.reliable && coverage.reliable,
                selectedRule: calculated.selectedRule,
                stepYears: calculated.stepYears,
                rate: calculated.rate,
                monthlyAmount: calculated.monthlyAmount,
                warnings: warnings
            ),
            coverage: coverage
        )
    }

    static func selectOfficialRuntimeSource(
        stored: SalaryConventionSeniorityPremiumStoreV2.ReadResult,
        coverage: ConventionMatterCoverageV2.Snapshot,
        idcc: String,
        classification: ConventionClassificationV2,
        referenceDate: PayrollCivilDateV2,
        accoKnowledge: SalaryPayrollSourceKnowledgeV2.Knowledge,
        sourceKnowledgeReliable: Bool = true,
        sourceKnowledgeWarnings: [String] = []
    ) -> RuntimeSourceSelection {
        let normalizedIdcc = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)

        guard sourceKnowledgeReliable else {
            return .init(
                rules: [],
                confirmedNoRule: false,
                reliable: false,
                warnings: sourceKnowledgeWarnings.isEmpty
                    ? ["Prime d'ancienneté : historique des preuves ACCO non fiable ; arbitrage entreprise/branche impossible."]
                    : sourceKnowledgeWarnings
            )
        }

        guard accoKnowledge == .confirmedAbsence else {
            return .init(
                rules: [],
                confirmedNoRule: false,
                reliable: false,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalizedIdcc) : l'absence d'une règle d'entreprise concurrente n'est pas prouvée par ACCO ; le barème KALI reste à confirmer."
                ]
            )
        }

        guard stored.reliable else {
            return .init(rules: [], confirmedNoRule: false, reliable: false, warnings: stored.warnings)
        }

        let kaliConfirmed = coverage.reliable
            && coverage.record != nil
            && coverage.record!.authorities.contains(.kali)
        guard kaliConfirmed else {
            return .init(
                rules: [],
                confirmedNoRule: false,
                reliable: false,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalizedIdcc) : couverture KALI officielle absente ou non confirmée pour ce profil et cette période."
                ]
            )
        }

        if coverage.state == .confirmedNoRule {
            return .init(rules: [], confirmedNoRule: true, reliable: true, warnings: [])
        }
        guard coverage.state == .confirmedRules else {
            return .init(
                rules: [],
                confirmedNoRule: false,
                reliable: false,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalizedIdcc) : l'audit KALI n'a pas confirmé de règle exploitable."
                ]
            )
        }

        let matching = stored.rules.filter {
            $0.structurallyValid()
                && SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc) == normalizedIdcc
                && $0.active(on: referenceDate)
                && classification.matches($0.classification)
        }
        guard !matching.isEmpty else {
            return .init(
                rules: [],
                confirmedNoRule: false,
                reliable: false,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalizedIdcc) : la couverture KALI annonce des règles confirmées mais aucune règle officielle stockée ne correspond au profil et à la période."
                ]
            )
        }

        return .init(rules: matching, confirmedNoRule: false, reliable: true, warnings: [])
    }

    private static func blocked(
        idcc: String,
        coverage: ConventionMatterCoverageV2.Snapshot,
        warnings: [String]
    ) -> Snapshot {
        let normalized = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        let merged = unique(
            warnings + [
                "Prime d'ancienneté IDCC \(normalized) : aucune règle monétaire n'est appliquée tant que KALI et la priorité ACCO ne sont pas suffisamment prouvés pour ce profil et cette période."
            ]
        )
        return Snapshot(
            result: SalaryConventionSeniorityPremiumV2.Result(
                applicable: false,
                reliable: false,
                selectedRule: nil,
                stepYears: nil,
                rate: nil,
                monthlyAmount: nil,
                warnings: merged
            ),
            coverage: ConventionMatterCoverageV2.Snapshot(
                state: coverage.state,
                record: coverage.record,
                reliable: false,
                warnings: merged
            )
        )
    }

    private static func incompleteCoverage(
        warning: String
    ) -> ConventionMatterCoverageV2.Snapshot {
        ConventionMatterCoverageV2.Snapshot(
            state: .incomplete,
            record: nil,
            reliable: false,
            warnings: [warning]
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
