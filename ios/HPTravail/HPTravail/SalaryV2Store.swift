import Combine
import Foundation

/// Façade d'état de l'onglet Salaire iOS.
///
/// Le provider sera raccordé progressivement aux propriétaires V2 canoniques.
/// Tant qu'il ne fournit pas de SalaryReferenceContractV2, l'écran reste
/// explicitement fail-closed et n'affiche aucun montant de remplacement.
final class SalaryV2Store: ObservableObject {
    typealias ReferenceProvider = (YearMonthV2) -> SalaryReferenceContractV2?

    @Published private(set) var selectedPeriod: YearMonthV2
    @Published private(set) var snapshot: SalaryWorkspaceSnapshotV2

    private let referenceProvider: ReferenceProvider

    init(
        referenceProvider: @escaping ReferenceProvider = { _ in nil },
        now: Date = Date(),
        calendar: Calendar = .current
    ) {
        let components = calendar.dateComponents([.year, .month], from: now)
        let period = YearMonthV2(
            year: components.year ?? 1970,
            month: components.month ?? 1
        ) ?? YearMonthV2(year: 1970, month: 1)!

        self.referenceProvider = referenceProvider
        self.selectedPeriod = period
        self.snapshot = SalaryWorkspaceResolverV2.resolve(
            period: period,
            reference: referenceProvider(period)
        )
    }

    func refresh() {
        snapshot = SalaryWorkspaceResolverV2.resolve(
            period: selectedPeriod,
            reference: referenceProvider(selectedPeriod)
        )
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
