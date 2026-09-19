import Combine
import Foundation

/// Façade d'état de l'onglet Salaire iOS.
///
/// Le provider sera raccordé progressivement aux propriétaires V2 canoniques.
/// Tant qu'il ne fournit pas de SalaryReferenceContractV2, l'écran reste
/// explicitement fail-closed et n'affiche aucun montant de remplacement.
final class SalaryV2Store: ObservableObject {
    typealias ReferenceProvider = (YearMonthV2) -> SalaryReferenceContractV2?
    typealias CompanyIdProvider = () -> String?

    @Published private(set) var selectedPeriod: YearMonthV2
    @Published private(set) var snapshot: SalaryWorkspaceSnapshotV2
    @Published var incomeTaxRateText = ""
    @Published var incomeTaxSource = ""
    @Published private(set) var incomeTaxFeedback: String?

    private let referenceProvider: ReferenceProvider
    private let companyIdProvider: CompanyIdProvider
    private let incomeTaxStore: CompanyIncomeTaxRateStoreV2

    init(
        referenceProvider: @escaping ReferenceProvider = { _ in nil },
        now: Date = Date(),
        calendar: Calendar = .current,
        incomeTaxStore: CompanyIncomeTaxRateStoreV2 = CompanyIncomeTaxRateStoreV2(),
        companyIdProvider: @escaping CompanyIdProvider = {
            let stored = SalaryCompanyStoreV2.readConfirmed()
            return stored.reliable && stored.companies.count == 1 ? stored.companies[0].id : nil
        }
    ) {
        let components = calendar.dateComponents([.year, .month], from: now)
        let period = YearMonthV2(
            year: components.year ?? 1970,
            month: components.month ?? 1
        ) ?? YearMonthV2(year: 1970, month: 1)!

        self.referenceProvider = referenceProvider
        self.companyIdProvider = companyIdProvider
        self.incomeTaxStore = incomeTaxStore
        self.selectedPeriod = period
        let taxRate = companyIdProvider().map { incomeTaxStore.snapshot(companyId: $0, for: period) }
        self.snapshot = SalaryWorkspaceResolverV2.resolve(
            period: period,
            reference: referenceProvider(period),
            incomeTaxRate: taxRate
        )
        self.incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        self.incomeTaxSource = taxRate?.source ?? ""
    }

    func refresh() {
        let taxRate = companyIdProvider().map { incomeTaxStore.snapshot(companyId: $0, for: selectedPeriod) }
        snapshot = SalaryWorkspaceResolverV2.resolve(
            period: selectedPeriod,
            reference: referenceProvider(selectedPeriod),
            incomeTaxRate: taxRate
        )
        incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        incomeTaxSource = taxRate?.source ?? ""
    }

    @discardableResult
    func confirmIncomeTaxRate() -> Bool {
        let normalized = incomeTaxRateText.replacingOccurrences(of: ",", with: ".")
        guard let companyId = companyIdProvider(),
              let rate = Double(normalized),
              incomeTaxStore.confirm(companyId: companyId, ratePercent: rate, period: selectedPeriod, source: incomeTaxSource) else {
            incomeTaxFeedback = "Confirmation impossible : vérifiez le taux, la source et l’entreprise confirmée."
            return false
        }
        incomeTaxFeedback = "Taux PAS confirmé pour le mois affiché."
        refresh()
        return true
    }

    @discardableResult
    func removeIncomeTaxRate() -> Bool {
        guard let companyId = companyIdProvider(),
              incomeTaxStore.remove(companyId: companyId, period: selectedPeriod) else {
            incomeTaxFeedback = "Impossible de retirer le taux PAS. Vérifiez l’entreprise confirmée et le stockage local."
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
}
