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
    typealias ContractHistoryProvider = () -> SalaryEmploymentContractHistoryReadResultV2
    typealias WorkSourceProvider = @MainActor () -> SalaryWorkSessionSourceV2

    @Published private(set) var selectedPeriod: YearMonthV2
    @Published private(set) var snapshot: SalaryWorkspaceSnapshotV2
    @Published private(set) var companies: SalaryCompanyReadResultV2
    @Published private(set) var selectedCompanyId: String?
    @Published private(set) var paidWork: SalaryPaidWorkAggregationV2?
    @Published private(set) var conventionCoverage: SalaryConventionCoverageV2?
    @Published private(set) var contractResolution: SalaryEmploymentContractPayrollSnapshotV2?
    @Published var incomeTaxRateText = ""
    @Published var incomeTaxSource = ""
    @Published private(set) var incomeTaxFeedback: String?

    // Saisie contractuelle canonique. Les deux dates sont volontairement indépendantes.
    @Published var contractTypeSelection = ""
    @Published var contractHireDateText = ""
    @Published var contractEffectiveDateText = ""
    @Published var contractWeeklyHoursText = ""
    @Published var contractForfaitHoursText = ""
    @Published var contractForfaitDaysText = ""
    @Published var contractMonthlyGrossText = ""
    @Published var contractHourlyRateText = ""
    @Published var contractSourceText = ""
    @Published private(set) var contractFeedback: String?

    private let referenceProvider: ReferenceProvider
    private let companiesProvider: CompaniesProvider
    private let conventionRulesProvider: ConventionRulesProvider
    private let contractHistoryProvider: ContractHistoryProvider
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
        contractHistoryProvider: @escaping ContractHistoryProvider = {
            SalaryEmploymentContractHistoryStoreV2.readConfirmed()
        },
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
        let contractResolution = companyId.map { companyId in
            SalaryEmploymentContractPayrollBridgeV2.resolve(
                companyId: companyId,
                period: period,
                stored: contractHistoryProvider()
            )
        }

        self.referenceProvider = referenceProvider
        self.companiesProvider = companiesProvider
        self.conventionRulesProvider = conventionRulesProvider
        self.contractHistoryProvider = contractHistoryProvider
        self.workSourceProvider = workSourceProvider
        self.incomeTaxStore = incomeTaxStore
        self.calendar = calendar
        self.selectedPeriod = period
        self.companies = storedCompanies
        self.selectedCompanyId = companyId
        self.paidWork = paidWork
        self.conventionCoverage = conventionCoverage
        self.contractResolution = contractResolution
        self.snapshot = SalaryWorkspaceResolverV2.resolve(
            period: period,
            reference: reference,
            incomeTaxRate: taxRate
        )
        self.incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        self.incomeTaxSource = taxRate?.source ?? ""
        hydrateContractForm(from: contractResolution?.resolution?.coverage?.singleSnapshotForWholePeriod)
    }

    var selectedCompany: SalaryCompanyV2? {
        guard let selectedCompanyId else { return nil }
        return SalaryCompanyStoreV2.confirmedCompany(companies, companyId: selectedCompanyId)
    }

    var requiresExplicitCompanySelection: Bool {
        companies.reliable && companies.companies.count > 1 && selectedCompanyId == nil
    }

    var displayWarnings: [String] {
        let companyWarnings = companies.warnings
        let conventionWarnings = conventionCoverage?.warnings ?? []
        let contractWarnings = contractResolution?.warnings ?? []
        let workspaceWarnings = snapshot.warnings
        let workWarnings = paidWork?.warnings ?? []
        let selectionWarnings: [String] = requiresExplicitCompanySelection
            ? ["Salaire V2 : plusieurs entreprises sont confirmées ; choisissez explicitement l'entreprise à analyser."]
            : []

        return unique(
            companyWarnings
            + conventionWarnings
            + contractWarnings
            + workspaceWarnings
            + workWarnings
            + selectionWarnings
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
            contractFeedback = nil
            recompute()
            return true
        }

        guard let confirmed = SalaryCompanySelectionV2.explicitSelection(
            requestedCompanyId: requestedCompanyId,
            companies: companies
        ) else {
            selectedCompanyId = nil
            selectedCompanyWasExplicit = false
            contractFeedback = nil
            recompute()
            return false
        }

        selectedCompanyId = confirmed
        selectedCompanyWasExplicit = companies.companies.count > 1
        incomeTaxFeedback = nil
        contractFeedback = nil
        recompute()
        return true
    }

    @discardableResult
    func confirmEmploymentContract() -> Bool {
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            contractFeedback = "Confirmation impossible : l’entreprise analysée a changé. Vérifiez la sélection avant de confirmer le contrat."
            return false
        }

        guard let type = contractType(from: contractTypeSelection) else {
            contractFeedback = "Contrat : choisissez un type de contrat précis."
            return false
        }
        guard let hireDate = epochDay(from: contractHireDateText) else {
            contractFeedback = "Contrat : date d’entrée invalide — JJ/MM/AAAA."
            return false
        }
        guard let effectiveDate = epochDay(from: contractEffectiveDateText) else {
            contractFeedback = "Contrat : date d’effet invalide — JJ/MM/AAAA."
            return false
        }
        let source = contractSourceText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else {
            contractFeedback = "Contrat : indiquez la source qui confirme cette version."
            return false
        }

        var weeklyMinutes: Int?
        var hourlyRate: Double?
        var forfaitHours: Double?
        var forfaitDays: Double?
        var monthlyGross: Double?
        var forfaitHoursPeriod: ForfaitHoursPeriodV2?

        switch type {
        case .fullTime, .partTime, .other:
            weeklyMinutes = positiveMinutesFromHours(contractWeeklyHoursText)
            hourlyRate = positiveDecimal(contractHourlyRateText)
            guard weeklyMinutes != nil, hourlyRate != nil else {
                contractFeedback = "Contrat : vérifiez la durée hebdomadaire et le taux horaire brut."
                return false
            }
        case .forfaitHours:
            forfaitHours = positiveDecimal(contractForfaitHoursText)
            monthlyGross = positiveDecimal(contractMonthlyGrossText)
            forfaitHoursPeriod = .year
            guard forfaitHours != nil, monthlyGross != nil else {
                contractFeedback = "Contrat : vérifiez les heures annuelles du forfait et le salaire brut mensuel."
                return false
            }
        case .forfaitDays:
            forfaitDays = positiveDecimal(contractForfaitDaysText)
            monthlyGross = positiveDecimal(contractMonthlyGrossText)
            guard let days = forfaitDays, days <= 218, monthlyGross != nil else {
                contractFeedback = "Contrat : vérifiez les jours annuels du forfait (218 maximum standard) et le salaire brut mensuel."
                return false
            }
        case .forfait:
            contractFeedback = SalaryEmploymentContractVersionInputValidatorV2.legacyForfaitWarning
            return false
        }

        let contract = ContractV2(
            id: "contract_\(companyId)",
            employerId: companyId,
            type: type,
            contractualWeeklyMinutes: weeklyMinutes,
            grossHourlyRate: hourlyRate,
            hireDateEpochDay: hireDate,
            forfaitHoursPeriod: forfaitHoursPeriod,
            forfaitHours: forfaitHours,
            forfaitAnnualDays: forfaitDays,
            monthlyGrossSalary: monthlyGross
        )
        let input = SalaryEmploymentContractVersionInputV2(
            contract: contract,
            effectiveFromEpochDay: effectiveDate,
            sourceId: source,
            checkedAtMs: Int64((Date().timeIntervalSince1970 * 1_000).rounded()),
            note: nil
        )
        let result = SalaryEmploymentContractVersionConfirmationV2.confirm(input)
        guard result.saved else {
            contractFeedback = result.warnings.joined(separator: "\n")
            return false
        }

        contractFeedback = "Version contractuelle datée confirmée pour cette entreprise."
        refresh()
        return true
    }

    @discardableResult
    func confirmIncomeTaxRate() -> Bool {
        let normalized = incomeTaxRateText.replacingOccurrences(of: ",", with: ".")
        let targetBeforeReconciliation = selectedCompanyId

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
        contractFeedback = nil
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
            contractResolution = SalaryEmploymentContractPayrollBridgeV2.resolve(
                companyId: companyId,
                period: selectedPeriod,
                stored: contractHistoryProvider()
            )
        } else {
            paidWork = nil
            conventionCoverage = nil
            contractResolution = nil
        }

        snapshot = SalaryWorkspaceResolverV2.resolve(
            period: selectedPeriod,
            reference: reference,
            incomeTaxRate: taxRate
        )
        incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        incomeTaxSource = taxRate?.source ?? ""
        hydrateContractForm(from: contractResolution?.resolution?.coverage?.singleSnapshotForWholePeriod)
    }

    private func hydrateContractForm(from stored: SalaryEmploymentContractSnapshotV2?) {
        guard let stored else {
            contractTypeSelection = ""
            contractHireDateText = ""
            contractEffectiveDateText = ""
            contractWeeklyHoursText = ""
            contractForfaitHoursText = ""
            contractForfaitDaysText = ""
            contractMonthlyGrossText = ""
            contractHourlyRateText = ""
            contractSourceText = ""
            return
        }
        let contract = stored.contract
        contractTypeSelection = contract.type.rawValue
        contractHireDateText = contract.hireDateEpochDay.map(dateText) ?? ""
        contractEffectiveDateText = dateText(stored.effectiveFromEpochDay)
        contractWeeklyHoursText = contract.contractualWeeklyMinutes.map { decimalText(Double($0) / 60.0) } ?? ""
        contractForfaitHoursText = contract.forfaitHours.map(decimalText) ?? ""
        contractForfaitDaysText = contract.forfaitAnnualDays.map(decimalText) ?? ""
        contractMonthlyGrossText = contract.monthlyGrossSalary.map(decimalText) ?? ""
        contractHourlyRateText = contract.grossHourlyRate.map(decimalText) ?? ""
        contractSourceText = stored.sourceId
    }

    private func contractType(from raw: String) -> ContractTypeV2? {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "FULL_TIME": return .fullTime
        case "PART_TIME": return .partTime
        case "FORFAIT_HEURES", "FORFAIT_HOURS": return .forfaitHours
        case "FORFAIT_JOURS", "FORFAIT_DAYS": return .forfaitDays
        case "OTHER": return .other
        default: return nil
        }
    }

    private func positiveDecimal(_ raw: String) -> Double? {
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard let value = Double(normalized), value.isFinite, value > 0 else { return nil }
        return value
    }

    private func positiveMinutesFromHours(_ raw: String) -> Int? {
        guard let hours = positiveDecimal(raw) else { return nil }
        let minutes = hours * 60.0
        guard minutes.isFinite, minutes > 0, minutes <= Double(Int.max) else { return nil }
        let rounded = minutes.rounded()
        guard rounded > 0, rounded <= Double(Int.max) else { return nil }
        return Int(rounded)
    }

    private func epochDay(from raw: String) -> Int64? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "dd/MM/yyyy"
        formatter.isLenient = false
        guard let date = formatter.date(from: text), formatter.string(from: date) == text else { return nil }
        return Int64(floor(date.timeIntervalSince1970 / 86_400.0))
    }

    private func dateText(_ epochDay: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86_400.0)
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "dd/MM/yyyy"
        return formatter.string(from: date)
    }

    private func decimalText(_ value: Double) -> String {
        let text = String(format: "%.4f", locale: Locale(identifier: "fr_FR"), value)
        return text.replacingOccurrences(of: #"[0]+$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"[,]$"#, with: "", options: .regularExpression)
    }

    private func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
