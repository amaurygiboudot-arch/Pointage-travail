import Combine
import Foundation

/// Façade d'état de l'onglet Salaire iOS.
///
/// Toute donnée de salaire est résolue pour une entreprise sélectionnée sans ambiguïté.
/// Une seule entreprise confirmée peut être sélectionnée automatiquement ; dès qu'il existe
/// plusieurs entreprises, seul un choix utilisateur explicitement tracé peut être conservé.
@MainActor
final class SalaryV2Store: ObservableObject {
    typealias ReferenceProvider = (_ companyId: String, _ period: YearMonthV2) -> SalaryReferenceContractV2?
    typealias CompaniesProvider = () -> SalaryCompanyReadResultV2
    typealias ConventionRulesProvider = () -> SalaryConventionRuleReadResultV2
    typealias WorkSourceProvider = @MainActor () -> SalaryWorkSessionSourceV2

    @Published private(set) var selectedPeriod: YearMonthV2
    @Published private(set) var snapshot: SalaryWorkspaceSnapshotV2
    @Published private(set) var companies: SalaryCompanyReadResultV2
    @Published private(set) var selectedCompanyId: String?
    @Published private(set) var paidWork: SalaryPaidWorkAggregationV2?
    @Published private(set) var conventionCoverage: SalaryConventionCoverageV2?
    @Published var incomeTaxRateText = ""
    @Published var incomeTaxSource = ""
    @Published private(set) var incomeTaxFeedback: String?

    private let referenceProvider: ReferenceProvider
    private let companiesProvider: CompaniesProvider
    private let conventionRulesProvider: ConventionRulesProvider
    private let workSourceProvider: WorkSourceProvider
    private let incomeTaxStore: CompanyIncomeTaxRateStoreV2
    private let calendar: Calendar
    private var selectedCompanyWasExplicit = false

    init(
        referenceProvider: @escaping ReferenceProvider = { _, _ in nil },
        now: Date = Date(),
        calendar: Calendar = .current,
        incomeTaxStore: CompanyIncomeTaxRateStoreV2 = CompanyIncomeTaxRateStoreV2(),
        companiesProvider: @escaping CompaniesProvider = { SalaryCompanyStoreV2.readConfirmed() },
        conventionRulesProvider: @escaping ConventionRulesProvider = { SalaryConventionRuleStoreV2.readConfirmed() },
        workSourceProvider: @escaping WorkSourceProvider = {
            SalaryWorkSessionSourceV2(sessions: [], reliable: false)
        }
    ) {
        let components = calendar.dateComponents([.year, .month], from: now)
        let period = YearMonthV2(
            year: components.year ?? 1970,
            month: components.month ?? 1
        ) ?? YearMonthV2(year: 1970, month: 1)!
        let storedCompanies = companiesProvider()
        let companyId = SalaryCompanySelectionV2.reconcile(
            currentCompanyId: nil,
            selectionWasExplicit: false,
            companies: storedCompanies
        )
        let taxRate = companyId.map { incomeTaxStore.snapshot(companyId: $0, for: period) }
        let reference = companyId.flatMap { referenceProvider($0, period) }
        let paidWork = companyId.map { companyId in
            let source = workSourceProvider()
            return SalaryPaidWorkAggregatorV2.aggregate(
                sessions: source.sessions,
                employerId: companyId,
                period: period,
                sourceReliable: source.reliable,
                calendar: calendar
            )
        }
        let conventionCoverage = companyId.map { companyId in
            SalaryConventionCoverageResolverV2.resolve(
                companyId: companyId,
                period: period,
                companies: storedCompanies,
                rules: conventionRulesProvider()
            )
        }

        self.referenceProvider = referenceProvider
        self.companiesProvider = companiesProvider
        self.conventionRulesProvider = conventionRulesProvider
        self.workSourceProvider = workSourceProvider
        self.incomeTaxStore = incomeTaxStore
        self.calendar = calendar
        self.selectedPeriod = period
        self.companies = storedCompanies
        self.selectedCompanyId = companyId
        self.paidWork = paidWork
        self.conventionCoverage = conventionCoverage
        self.snapshot = SalaryWorkspaceResolverV2.resolve(
            period: period,
            reference: reference,
            incomeTaxRate: taxRate
        )
        self.incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        self.incomeTaxSource = taxRate?.source ?? ""
    }

    var selectedCompany: SalaryCompanyV2? {
        guard let selectedCompanyId else { return nil }
        return SalaryCompanyStoreV2.confirmedCompany(companies, companyId: selectedCompanyId)
    }

    var requiresExplicitCompanySelection: Bool {
        companies.reliable && companies.companies.count > 1 && selectedCompanyId == nil
    }

    var displayWarnings: [String] {
        unique(
            companies.warnings
            + (conventionCoverage?.warnings ?? [])
            + snapshot.warnings
            + (paidWork?.warnings ?? [])
            + (requiresExplicitCompanySelection
               ? ["Salaire V2 : plusieurs entreprises sont confirmées ; choisissez explicitement l'entreprise à analyser."]
               : [])
        )
    }

    func refresh() {
        synchronizeCompanySelectionWithLatestStore()
        recompute()
    }

    /// Sélection volontaire d'une entreprise confirmée.
    /// `nil` efface le choix ; un identifiant inconnu ou un stockage non fiable est rejeté.
    @discardableResult
    func selectCompany(_ requestedCompanyId: String?) -> Bool {
        companies = companiesProvider()

        guard let requestedCompanyId else {
            selectedCompanyId = nil
            selectedCompanyWasExplicit = false
            recompute()
            return true
        }

        guard let confirmed = SalaryCompanySelectionV2.explicitSelection(
            requestedCompanyId: requestedCompanyId,
            companies: companies
        ) else {
            selectedCompanyId = nil
            selectedCompanyWasExplicit = false
            recompute()
            return false
        }

        selectedCompanyId = confirmed
        // Un choix parmi plusieurs entreprises est réellement explicite. Avec une seule entreprise,
        // on ne transforme pas ce contexte non ambigu en préférence réutilisable si une 2e apparaît.
        selectedCompanyWasExplicit = companies.companies.count > 1
        incomeTaxFeedback = nil
        recompute()
        return true
    }

    @discardableResult
    func confirmIncomeTaxRate() -> Bool {
        let normalized = incomeTaxRateText.replacingOccurrences(of: ",", with: ".")
        let targetBeforeReconciliation = selectedCompanyId

        // Le magasin d'entreprises peut avoir changé depuis l'affichage de l'écran.
        // La mutation reste liée à sa cible d'origine : si la réconciliation sélectionne une autre
        // entreprise, aucune écriture n'est autorisée avec les champs saisis pour l'ancienne cible.
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            incomeTaxFeedback = "Confirmation impossible : l’entreprise analysée a changé. Vérifiez la sélection avant de confirmer le taux."
            return false
        }

        guard let rate = Double(normalized),
              incomeTaxStore.confirm(
                  companyId: companyId,
                  ratePercent: rate,
                  period: selectedPeriod,
                  source: incomeTaxSource
              ) else {
            incomeTaxFeedback = "Confirmation impossible : vérifiez le taux, la source et l’entreprise sélectionnée."
            return false
        }
        incomeTaxFeedback = "Taux PAS confirmé pour cette entreprise et le mois affiché."
        refresh()
        return true
    }

    @discardableResult
    func removeIncomeTaxRate() -> Bool {
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            incomeTaxFeedback = "Impossible de retirer le taux PAS : l’entreprise analysée a changé. Vérifiez la sélection."
            return false
        }

        guard incomeTaxStore.remove(companyId: companyId, period: selectedPeriod) else {
            incomeTaxFeedback = "Impossible de retirer le taux PAS. Vérifiez l’entreprise sélectionnée et le stockage local."
            return false
        }
        incomeTaxRateText = ""
        incomeTaxSource = ""
        incomeTaxFeedback = "Taux PAS retiré : le calcul après impôt est bloqué jusqu’à une nouvelle confirmation."
        refresh()
        return true
    }

    func moveMonth(by delta: Int) {
        guard delta != 0 else { return }
        let zeroBased = selectedPeriod.year * 12 + selectedPeriod.month - 1 + delta
        guard zeroBased >= 0 else { return }
        let next = YearMonthV2(year: zeroBased / 12, month: zeroBased % 12 + 1)
        guard let next else { return }
        selectedPeriod = next
        refresh()
    }

    private func synchronizeCompanySelectionWithLatestStore() {
        let previousWasExplicit = selectedCompanyWasExplicit
        companies = companiesProvider()
        let reconciled = SalaryCompanySelectionV2.reconcile(
            currentCompanyId: selectedCompanyId,
            selectionWasExplicit: previousWasExplicit,
            companies: companies
        )
        selectedCompanyId = reconciled
        selectedCompanyWasExplicit = companies.reliable
            && companies.companies.count > 1
            && previousWasExplicit
            && reconciled != nil
    }

    private func recompute() {
        let companyId = selectedCompanyId
        let taxRate = companyId.map { incomeTaxStore.snapshot(companyId: $0, for: selectedPeriod) }
        let reference = companyId.flatMap { referenceProvider($0, selectedPeriod) }

        if let companyId {
            let source = workSourceProvider()
            paidWork = SalaryPaidWorkAggregatorV2.aggregate(
                sessions: source.sessions,
                employerId: companyId,
                period: selectedPeriod,
                sourceReliable: source.reliable,
                calendar: calendar
            )
            conventionCoverage = SalaryConventionCoverageResolverV2.resolve(
                companyId: companyId,
                period: selectedPeriod,
                companies: companies,
                rules: conventionRulesProvider()
            )
        } else {
            paidWork = nil
            conventionCoverage = nil
        }

        snapshot = SalaryWorkspaceResolverV2.resolve(
            period: selectedPeriod,
            reference: reference,
            incomeTaxRate: taxRate
        )
        incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        incomeTaxSource = taxRate?.source ?? ""
    }

    private func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
