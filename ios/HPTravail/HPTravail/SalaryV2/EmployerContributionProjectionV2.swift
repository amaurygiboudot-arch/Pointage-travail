import Foundation

/// Assemblage patronal partiel. La couverture juridique exhaustive reste hors de ce contrat.
enum EmployerContributionProjectionV2 {
    enum Family: String, CaseIterable, Equatable {
        case statutory, complementaryRetirement, conventionProvident, status, atMp, mobility
        case unemploymentAgs, workforce, healthFamily, apprenticeship

        var label: String {
            switch self {
            case .statutory: return "Cotisations légales patronales connues"
            case .complementaryRetirement: return "Retraite complémentaire patronale"
            case .conventionProvident: return "Prévoyance collective patronale"
            case .status: return "Contributions patronales liées au statut"
            case .atMp: return "Accidents du travail / maladies professionnelles"
            case .mobility: return "Versement mobilité"
            case .unemploymentAgs: return "Chômage et AGS"
            case .workforce: return "FNAL et formation"
            case .healthFamily: return "Maladie et allocations familiales"
            case .apprenticeship: return "Apprentissage"
            }
        }
    }
    enum State: String, Equatable {
        case known = "KNOWN", missing = "MISSING", notApplicable = "NOT_APPLICABLE", invalid = "INVALID"
    }
    struct Context: Equatable {
        let companyId: String?
        let year: Int
        let month: Int
        let contributionGrossCents: Int64?
        let reliable: Bool

        var usable: Bool {
            guard reliable, let companyId,
                  !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  year > 0, (1...12).contains(month), let cents = contributionGrossCents,
                  cents >= 0, PayrollMoneyV2.amount(cents) != nil else { return false }
            return true
        }
    }
    struct Line: Equatable {
        let family: Family
        let state: State
        let amountCents: Int64?
        let source: String?
    }
    struct Reduction: Equatable {
        let state: State
        let amountCents: Int64?
        let source: String?
        static let missing = Reduction(state: .missing, amountCents: nil, source: nil)
    }
    struct Result: Equatable {
        let context: Context
        let lines: [Line]
        let knownSubtotalBeforeReductionsCents: Int64?
        let reductions: Reduction
        let knownSubtotalAfterReductionsCents: Int64?
        let monetaryReliable: Bool
        let warnings: [String]
        var coverageComplete: Bool { false }
        var knownSubtotalBeforeReductions: Double? { knownSubtotalBeforeReductionsCents.flatMap(PayrollMoneyV2.amount) }
        var knownSubtotalAfterReductions: Double? { knownSubtotalAfterReductionsCents.flatMap(PayrollMoneyV2.amount) }

        /// Le consommateur fournit son contexte ; aucune valeur ne traverse une autre entreprise/période.
        func matches(companyId: String?, year: Int, month: Int, gross: Double?) -> Bool {
            guard context.usable, let companyId, let gross, gross >= 0,
                  let cents = PayrollMoneyV2.cents(gross) else { return false }
            return context.companyId == companyId && context.year == year && context.month == month &&
                context.contributionGrossCents == cents
        }
    }

    static func calculate(context: Context, lines supplied: [Line], reductions: Reduction = .missing, additionalWarnings: [String] = []) -> Result {
        let duplicate = Set(supplied.map(\.family)).count != supplied.count
        var invalid = duplicate
        let lines = Family.allCases.map { family -> Line in
            let familyLines = supplied.filter { $0.family == family }
            if familyLines.count > 1 { return Line(family: family, state: .invalid, amountCents: nil, source: nil) }
            guard let value = familyLines.first else {
                return Line(family: family, state: .missing, amountCents: nil, source: nil)
            }
            let state = validatedState(value.state, cents: value.amountCents, source: value.source)
            if state == .invalid { invalid = true }
            return Line(family: family, state: state, amountCents: state == .known ? value.amountCents : nil, source: value.source)
        }
        let known = lines.filter { $0.state == .known }.compactMap(\.amountCents)
        let sum = !invalid && !known.isEmpty ? PayrollMoneyV2.sum(known) : nil
        let before = context.usable ? sum : nil
        let reductionState = validatedState(reductions.state, cents: reductions.amountCents, source: reductions.source)
        let reduction = Reduction(state: reductionState, amountCents: reductionState == .known ? reductions.amountCents : nil, source: reductions.source)
        let reductionCents = reductionState == .notApplicable ? 0 : reduction.amountCents
        let after: Int64?
        if let before, let reductionCents, reductionCents <= before {
            after = PayrollMoneyV2.sum([before, -reductionCents])
        } else { after = nil }
        var warnings = ["Coût employeur complet indisponible : seules les cotisations patronales connues sont présentées ; couverture spécifique de l'entreprise à confirmer."]
        if !context.usable { warnings.append("Cotisations patronales : entreprise, période ou brut social non confirmé ; sous-total indisponible.") }
        if invalid || (!known.isEmpty && sum == nil) { warnings.append("Cotisations patronales : montant invalide, doublon ou somme non représentable ; aucun sous-total n'est inventé.") }
        if lines.contains(where: { $0.state == .missing }) { warnings.append("Cotisations patronales : certaines familles restent à confirmer ; le sous-total connu est partiel.") }
        if reductionState == .missing { warnings.append("Réductions patronales du mois non confirmées ; sous-total après réductions indisponible.") }
        if reductionState == .invalid || (before != nil && reductionCents != nil && reductionCents! > before!) {
            warnings.append("Réductions patronales invalides ou supérieures aux cotisations connues ; aucune soustraction de remplacement.")
        }
        warnings.append(contentsOf: additionalWarnings)
        var seen = Set<String>()
        warnings = warnings.filter { seen.insert($0).inserted }
        return Result(context: context, lines: lines, knownSubtotalBeforeReductionsCents: before,
                      reductions: reduction, knownSubtotalAfterReductionsCents: after,
                      monetaryReliable: before != nil && reductionState != .invalid && (reductionCents == nil || reductionCents! <= before!), warnings: warnings)
    }

    private static func validatedState(_ state: State, cents: Int64?, source: String?) -> State {
        switch state {
        case .missing: return cents == nil ? .missing : .invalid
        case .invalid: return .invalid
        case .known:
            guard let cents, cents >= 0, PayrollMoneyV2.amount(cents) != nil,
                  source?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else { return .invalid }
            return .known
        case .notApplicable:
            guard cents == nil, source?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else { return .invalid }
            return .notApplicable
        }
    }
}
