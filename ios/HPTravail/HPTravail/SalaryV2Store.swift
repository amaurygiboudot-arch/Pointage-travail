import Combine
import Foundation

/// Façade d'état de l'onglet Salaire iOS.
///
/// Toute donnée de salaire est résolue pour une entreprise sélectionnée sans ambiguïté.
/// Une seule entreprise confirmée peut être sélectionnée automatiquement ; dès qu'il existe
/// plusieurs entreprises, seul un choix utilisateur explicitement tracé peut être conservé.
struct SalarySegmentedProrationDraftSegmentV2: Identifiable, Equatable {
    let versionId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    var scheduledMinutesText: String

    var id: String {
        "\(versionId)|\(startEpochDay)|\(endEpochDay)"
    }
}

private enum SalarySegmentedProrationDraftBuilderV2 {
    static func make(
        segments: [SalaryEmploymentContractCoverageSegmentV2],
        stored: ConfirmedSegmentedMonthlyProrationV2?
    ) -> [SalarySegmentedProrationDraftSegmentV2] {
        let storedById = Dictionary(
            uniqueKeysWithValues: (stored?.segments ?? []).map {
                ("\($0.versionId.trimmingCharacters(in: .whitespacesAndNewlines))|\($0.startEpochDay)|\($0.endEpochDay)", $0.scheduledMinutes)
            }
        )
        return segments
            .sorted { $0.startEpochDay < $1.startEpochDay }
            .map { segment in
                let versionId = segment.snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines)
                let id = "\(versionId)|\(segment.startEpochDay)|\(segment.endEpochDay)"
                return SalarySegmentedProrationDraftSegmentV2(
                    versionId: versionId,
                    startEpochDay: segment.startEpochDay,
                    endEpochDay: segment.endEpochDay,
                    scheduledMinutesText: storedById[id].map(String.init) ?? ""
                )
            }
    }
}

@MainActor
final class SalaryV2Store: ObservableObject {
    typealias ReferenceProvider = (_ companyId: String, _ period: YearMonthV2) -> SalaryReferenceContractV2?
    typealias SegmentedProvider = (_ companyId: String, _ period: YearMonthV2) -> SalarySegmentedCanonicalOutputV2?
    typealias CompaniesProvider = () -> SalaryCompanyReadResultV2
    typealias ConventionRulesProvider = () -> SalaryConventionRuleReadResultV2
    typealias ContractHistoryProvider = () -> SalaryEmploymentContractHistoryReadResultV2
    typealias WorkSourceProvider = @MainActor () -> SalaryWorkSessionSourceV2

    @Published private(set) var selectedPeriod: YearMonthV2
    @Published private var storedSnapshot: SalaryWorkspaceSnapshotV2
    private(set) var snapshot: SalaryWorkspaceSnapshotV2 {
        get { accountIsCurrent ? storedSnapshot : SalaryWorkspaceResolverV2.resolve(period: selectedPeriod, reference: nil) }
        set { if accountIsCurrent { storedSnapshot = newValue } }
    }
    @Published private var storedCompanies: SalaryCompanyReadResultV2
    private(set) var companies: SalaryCompanyReadResultV2 {
        get { accountIsCurrent ? storedCompanies : SalaryCompanyReadResultV2(companies: [], reliable: false, repairedFromBackup: false, warnings: [SalaryAccountStorageV2.warning]) }
        set { if accountIsCurrent { storedCompanies = newValue } }
    }
    @Published private var storedSelectedCompanyId: String?
    private(set) var selectedCompanyId: String? {
        get { accountIsCurrent ? storedSelectedCompanyId : nil }
        set { if accountIsCurrent { storedSelectedCompanyId = newValue } }
    }
    @Published private var storedPaidWork: SalaryPaidWorkAggregationV2?
    private(set) var paidWork: SalaryPaidWorkAggregationV2? {
        get { accountIsCurrent ? storedPaidWork : nil }
        set { if accountIsCurrent { storedPaidWork = newValue } }
    }
    @Published private var storedContractSegmentPaidWork: SalaryContractSegmentPaidWorkResultV2?
    private(set) var contractSegmentPaidWork: SalaryContractSegmentPaidWorkResultV2? {
        get { accountIsCurrent ? storedContractSegmentPaidWork : nil }
        set { if accountIsCurrent { storedContractSegmentPaidWork = newValue } }
    }
    @Published private var storedConventionCoverage: SalaryConventionCoverageV2?
    private(set) var conventionCoverage: SalaryConventionCoverageV2? {
        get { accountIsCurrent ? storedConventionCoverage : nil }
        set { if accountIsCurrent { storedConventionCoverage = newValue } }
    }
    @Published private var storedContractResolution: SalaryEmploymentContractPayrollSnapshotV2?
    private(set) var contractResolution: SalaryEmploymentContractPayrollSnapshotV2? {
        get { accountIsCurrent ? storedContractResolution : nil }
        set { if accountIsCurrent { storedContractResolution = newValue } }
    }
    @Published private var storedSocialProfile: SalaryEmployeeSocialProfileResolutionV2?
    private(set) var socialProfile: SalaryEmployeeSocialProfileResolutionV2? {
        get { accountIsCurrent ? storedSocialProfile : nil }
        set { if accountIsCurrent { storedSocialProfile = newValue } }
    }
    @Published private var storedConfirmedSicknessCash: SalaryConfirmedSicknessCashV2.Result?
    private(set) var confirmedSicknessCash: SalaryConfirmedSicknessCashV2.Result? {
        get { accountIsCurrent ? storedConfirmedSicknessCash : nil }
        set { if accountIsCurrent { storedConfirmedSicknessCash = newValue } }
    }
    @Published private var storedMonthlySettlement: SalaryMonthlyPayrollSettlementV2.Result?
    private(set) var monthlySettlement: SalaryMonthlyPayrollSettlementV2.Result? {
        get { accountIsCurrent ? storedMonthlySettlement : nil }
        set { if accountIsCurrent { storedMonthlySettlement = newValue } }
    }
    @Published private var storedAbsenceSource: SalaryAbsenceSourceV2?
    private(set) var absenceSource: SalaryAbsenceSourceV2? {
        get { accountIsCurrent ? storedAbsenceSource : nil }
        set { if accountIsCurrent { storedAbsenceSource = newValue } }
    }
    @Published private var storedSegmentedProrationSource: SalarySegmentedProrationSourceV2?
    private(set) var segmentedProrationSource: SalarySegmentedProrationSourceV2? {
        get { accountIsCurrent ? storedSegmentedProrationSource : nil }
        set { if accountIsCurrent { storedSegmentedProrationSource = newValue } }
    }
    @Published private var storedSegmentedMonthlyBase: SegmentedMonthlyBaseResultV2?
    private(set) var segmentedMonthlyBase: SegmentedMonthlyBaseResultV2? {
        get { accountIsCurrent ? storedSegmentedMonthlyBase : nil }
        set { if accountIsCurrent { storedSegmentedMonthlyBase = newValue } }
    }
    @Published private var storedSegmentedPayrollBoundary: SalarySegmentedPayrollBoundaryAssessmentV2?
    private(set) var segmentedPayrollBoundary: SalarySegmentedPayrollBoundaryAssessmentV2? {
        get { accountIsCurrent ? storedSegmentedPayrollBoundary : nil }
        set { if accountIsCurrent { storedSegmentedPayrollBoundary = newValue } }
    }
    @Published private var storedSegmentedWorkedGross: SalarySegmentedWorkedGrossDetailedBridgeResultV2?
    private(set) var segmentedWorkedGross: SalarySegmentedWorkedGrossDetailedBridgeResultV2? {
        get { accountIsCurrent ? storedSegmentedWorkedGross : nil }
        set { if accountIsCurrent { storedSegmentedWorkedGross = newValue } }
    }
    @Published private var storedSegmentedCashGross: SalarySegmentedCashGrossAssemblyResultV2?
    private(set) var segmentedCashGross: SalarySegmentedCashGrossAssemblyResultV2? {
        get { accountIsCurrent ? storedSegmentedCashGross : nil }
        set { if accountIsCurrent { storedSegmentedCashGross = newValue } }
    }
    @Published private var storedSegmentedSocialCeiling: SalarySegmentedSocialSecurityCeilingResultV2?
    private(set) var segmentedSocialCeiling: SalarySegmentedSocialSecurityCeilingResultV2? {
        get { accountIsCurrent ? storedSegmentedSocialCeiling : nil }
        set { if accountIsCurrent { storedSegmentedSocialCeiling = newValue } }
    }
    @Published private var storedSegmentedProrationSourceText: String = ""
    var segmentedProrationSourceText: String {
        get { accountIsCurrent ? storedSegmentedProrationSourceText : "" }
        set { if accountIsCurrent { storedSegmentedProrationSourceText = newValue } }
    }
    @Published private var storedSegmentedProrationDraftSegments: [SalarySegmentedProrationDraftSegmentV2] = []
    private(set) var segmentedProrationDraftSegments: [SalarySegmentedProrationDraftSegmentV2] {
        get { accountIsCurrent ? storedSegmentedProrationDraftSegments : [] }
        set { if accountIsCurrent { storedSegmentedProrationDraftSegments = newValue } }
    }
    @Published private var storedSegmentedProrationFeedback: String?
    private(set) var segmentedProrationFeedback: String? {
        get { accountIsCurrent ? storedSegmentedProrationFeedback : nil }
        set { if accountIsCurrent { storedSegmentedProrationFeedback = newValue } }
    }
    @Published private var storedIncomeTaxRateText: String = ""
    var incomeTaxRateText: String {
        get { accountIsCurrent ? storedIncomeTaxRateText : "" }
        set { if accountIsCurrent { storedIncomeTaxRateText = newValue } }
    }
    @Published private var storedIncomeTaxSource: String = ""
    var incomeTaxSource: String {
        get { accountIsCurrent ? storedIncomeTaxSource : "" }
        set { if accountIsCurrent { storedIncomeTaxSource = newValue } }
    }
    @Published private var storedIncomeTaxFeedback: String?
    private(set) var incomeTaxFeedback: String? {
        get { accountIsCurrent ? storedIncomeTaxFeedback : nil }
        set { if accountIsCurrent { storedIncomeTaxFeedback = newValue } }
    }

    // Saisie contractuelle canonique. Les deux dates sont volontairement indépendantes.
    @Published private var storedContractTypeSelection: String = ""
    var contractTypeSelection: String {
        get { accountIsCurrent ? storedContractTypeSelection : "" }
        set { if accountIsCurrent { storedContractTypeSelection = newValue } }
    }
    @Published private var storedContractHireDateText: String = ""
    var contractHireDateText: String {
        get { accountIsCurrent ? storedContractHireDateText : "" }
        set { if accountIsCurrent { storedContractHireDateText = newValue } }
    }
    @Published private var storedContractEffectiveDateText: String = ""
    var contractEffectiveDateText: String {
        get { accountIsCurrent ? storedContractEffectiveDateText : "" }
        set { if accountIsCurrent { storedContractEffectiveDateText = newValue } }
    }
    @Published private var storedContractWeeklyHoursText: String = ""
    var contractWeeklyHoursText: String {
        get { accountIsCurrent ? storedContractWeeklyHoursText : "" }
        set { if accountIsCurrent { storedContractWeeklyHoursText = newValue } }
    }
    @Published private var storedContractForfaitHoursText: String = ""
    var contractForfaitHoursText: String {
        get { accountIsCurrent ? storedContractForfaitHoursText : "" }
        set { if accountIsCurrent { storedContractForfaitHoursText = newValue } }
    }
    @Published private var storedContractForfaitDaysText: String = ""
    var contractForfaitDaysText: String {
        get { accountIsCurrent ? storedContractForfaitDaysText : "" }
        set { if accountIsCurrent { storedContractForfaitDaysText = newValue } }
    }
    @Published private var storedContractMonthlyGrossText: String = ""
    var contractMonthlyGrossText: String {
        get { accountIsCurrent ? storedContractMonthlyGrossText : "" }
        set { if accountIsCurrent { storedContractMonthlyGrossText = newValue } }
    }
    @Published private var storedContractHourlyRateText: String = ""
    var contractHourlyRateText: String {
        get { accountIsCurrent ? storedContractHourlyRateText : "" }
        set { if accountIsCurrent { storedContractHourlyRateText = newValue } }
    }
    @Published private var storedContractSourceText: String = ""
    var contractSourceText: String {
        get { accountIsCurrent ? storedContractSourceText : "" }
        set { if accountIsCurrent { storedContractSourceText = newValue } }
    }
    @Published private var storedContractFeedback: String?
    private(set) var contractFeedback: String? {
        get { accountIsCurrent ? storedContractFeedback : nil }
        set { if accountIsCurrent { storedContractFeedback = newValue } }
    }

    // Profil social salarié daté : aucune valeur n'est présumée.
    @Published private var storedSocialProfessionalStatusSelection: String = ""
    var socialProfessionalStatusSelection: String {
        get { accountIsCurrent ? storedSocialProfessionalStatusSelection : "" }
        set { if accountIsCurrent { storedSocialProfessionalStatusSelection = newValue } }
    }
    @Published private var storedSocialAlsaceMoselleSelection: String = ""
    var socialAlsaceMoselleSelection: String {
        get { accountIsCurrent ? storedSocialAlsaceMoselleSelection : "" }
        set { if accountIsCurrent { storedSocialAlsaceMoselleSelection = newValue } }
    }
    @Published private var storedSocialEffectiveDateText: String = ""
    var socialEffectiveDateText: String {
        get { accountIsCurrent ? storedSocialEffectiveDateText : "" }
        set { if accountIsCurrent { storedSocialEffectiveDateText = newValue } }
    }
    @Published private var storedSocialSourceText: String = ""
    var socialSourceText: String {
        get { accountIsCurrent ? storedSocialSourceText : "" }
        set { if accountIsCurrent { storedSocialSourceText = newValue } }
    }
    @Published private var storedSocialProfileFeedback: String?
    private(set) var socialProfileFeedback: String? {
        get { accountIsCurrent ? storedSocialProfileFeedback : nil }
        set { if accountIsCurrent { storedSocialProfileFeedback = newValue } }
    }

    // Classification conventionnelle exacte : champs indépendants, aucun rapprochement approximatif.
    @Published private var storedClassificationCoefficientText: String = ""
    var classificationCoefficientText: String {
        get { accountIsCurrent ? storedClassificationCoefficientText : "" }
        set { if accountIsCurrent { storedClassificationCoefficientText = newValue } }
    }
    @Published private var storedClassificationLevelText: String = ""
    var classificationLevelText: String {
        get { accountIsCurrent ? storedClassificationLevelText : "" }
        set { if accountIsCurrent { storedClassificationLevelText = newValue } }
    }
    @Published private var storedClassificationEchelonText: String = ""
    var classificationEchelonText: String {
        get { accountIsCurrent ? storedClassificationEchelonText : "" }
        set { if accountIsCurrent { storedClassificationEchelonText = newValue } }
    }
    @Published private var storedClassificationPositionText: String = ""
    var classificationPositionText: String {
        get { accountIsCurrent ? storedClassificationPositionText : "" }
        set { if accountIsCurrent { storedClassificationPositionText = newValue } }
    }
    @Published private var storedClassificationGroupText: String = ""
    var classificationGroupText: String {
        get { accountIsCurrent ? storedClassificationGroupText : "" }
        set { if accountIsCurrent { storedClassificationGroupText = newValue } }
    }
    @Published private var storedClassificationCategoryText: String = ""
    var classificationCategoryText: String {
        get { accountIsCurrent ? storedClassificationCategoryText : "" }
        set { if accountIsCurrent { storedClassificationCategoryText = newValue } }
    }
    @Published private var storedClassificationEmploymentText: String = ""
    var classificationEmploymentText: String {
        get { accountIsCurrent ? storedClassificationEmploymentText : "" }
        set { if accountIsCurrent { storedClassificationEmploymentText = newValue } }
    }
    @Published private var storedClassificationFeedback: String?
    private(set) var classificationFeedback: String? {
        get { accountIsCurrent ? storedClassificationFeedback : nil }
        set { if accountIsCurrent { storedClassificationFeedback = newValue } }
    }

    // Absences V2 : saisie factuelle + confirmation d'exhaustivité du mois.
    @Published private var storedAbsenceTypeSelection: String = SalaryAbsencePayrollImpactV2.typeUnpaid
    var absenceTypeSelection: String {
        get { accountIsCurrent ? storedAbsenceTypeSelection : "" }
        set { if accountIsCurrent { storedAbsenceTypeSelection = newValue } }
    }
    @Published private var storedAbsenceStartDateText: String = ""
    var absenceStartDateText: String {
        get { accountIsCurrent ? storedAbsenceStartDateText : "" }
        set { if accountIsCurrent { storedAbsenceStartDateText = newValue } }
    }
    @Published private var storedAbsenceEndDateText: String = ""
    var absenceEndDateText: String {
        get { accountIsCurrent ? storedAbsenceEndDateText : "" }
        set { if accountIsCurrent { storedAbsenceEndDateText = newValue } }
    }
    @Published private var storedAbsenceTreatmentSelection: String = SalaryAbsenceTreatmentV2.unpaid.rawValue
    var absenceTreatmentSelection: String {
        get { accountIsCurrent ? storedAbsenceTreatmentSelection : "" }
        set { if accountIsCurrent { storedAbsenceTreatmentSelection = newValue } }
    }
    @Published private var storedAbsenceFullDay: Bool = true
    var absenceFullDay: Bool {
        get { accountIsCurrent ? storedAbsenceFullDay : false }
        set { if accountIsCurrent { storedAbsenceFullDay = newValue } }
    }
    @Published private var storedAbsenceMonthSourceText: String = ""
    var absenceMonthSourceText: String {
        get { accountIsCurrent ? storedAbsenceMonthSourceText : "" }
        set { if accountIsCurrent { storedAbsenceMonthSourceText = newValue } }
    }
    @Published private var storedAbsenceFeedback: String?
    private(set) var absenceFeedback: String? {
        get { accountIsCurrent ? storedAbsenceFeedback : nil }
        set { if accountIsCurrent { storedAbsenceFeedback = newValue } }
    }

    let accountLease: SalaryAccountSessionV2.Lease
    var accountIsCurrent: Bool { accountLease.isCurrent }

    private let referenceProvider: ReferenceProvider
    private let segmentedProvider: SegmentedProvider
    private let companiesProvider: CompaniesProvider
    private let conventionRulesProvider: ConventionRulesProvider
    private let contractHistoryProvider: ContractHistoryProvider
    private let workSourceProvider: WorkSourceProvider
    private let incomeTaxStore: CompanyIncomeTaxRateStoreV2
    private let calendar: Calendar
    private var selectedCompanyWasExplicit = false

    init(
        referenceProvider: @escaping ReferenceProvider = { _, _ in nil },
        segmentedProvider: @escaping SegmentedProvider = { _, _ in nil },
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
        let lease = SalaryAccountSessionV2.capture()
        self.accountLease = lease
        let operation = SalaryAccountSessionV2.pin(lease)
        defer { operation.close() }
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
        let workSource = companyId.map { _ in workSourceProvider() }
        let paidWork: SalaryPaidWorkAggregationV2? = {
            guard let companyId, let source = workSource else { return nil }
            return SalaryPaidWorkAggregatorV2.aggregate(
                sessions: source.sessions,
                employerId: companyId,
                period: period,
                sourceReliable: source.reliable,
                calendar: calendar
            )
        }()
        let conventionCoverage = companyId.map { companyId in
            SalaryConventionPayrollBridgeV2.resolve(
                companyId: companyId,
                period: period,
                companies: storedCompanies,
                storedRules: conventionRulesProvider()
            ).coverage
        }
        let contractResolution = companyId.map { companyId in
            SalaryEmploymentContractPayrollBridgeV2.resolve(
                companyId: companyId,
                period: period,
                stored: contractHistoryProvider()
            )
        }
        let socialProfile = companyId.map { companyId in
            SalaryEmployeeSocialProfileStoreV2.resolve(
                companyId: companyId,
                period: period
            )
        }
        let absenceSource = companyId.map { companyId in
            SalaryAbsenceStoreV2.resolve(companyId: companyId, period: period)
        }
        let segmentedProrationSource = companyId.map { companyId in
            SalarySegmentedProrationStoreV2.resolve(companyId: companyId, period: period)
        }
        let segmentedMonthlyBase = SalarySegmentedMonthlyBaseProductionV2.resolve(
            contractSnapshot: contractResolution,
            conventionCoverage: conventionCoverage,
            prorationSource: segmentedProrationSource
        )
        let segmentedPayrollBoundary: SalarySegmentedPayrollBoundaryAssessmentV2? = {
            guard let contracts = contractResolution?.resolution,
                  let rules = conventionCoverage else {
                return nil
            }
            return SalarySegmentedPayrollBoundaryV2.assess(
                contracts: contracts,
                rules: rules
            )
        }()
        let segmentedProrationDraftSegments = SalarySegmentedProrationDraftBuilderV2.make(
            segments: contractResolution?.resolution?.calculationSegments ?? [],
            stored: segmentedProrationSource?.proration
        )
        let contractSegmentPaidWork: SalaryContractSegmentPaidWorkResultV2? = {
            guard let companyId,
                  let source = workSource,
                  let segments = contractResolution?.resolution?.calculationSegments,
                  !segments.isEmpty else {
                return nil
            }
            return SalaryContractSegmentPaidWorkAllocatorV2.allocate(
                sessions: source.sessions,
                segments: segments,
                employerId: companyId,
                period: period,
                sourceReliable: source.reliable,
                calendar: calendar
            )
        }()

        self.referenceProvider = referenceProvider
        self.segmentedProvider = segmentedProvider
        self.companiesProvider = companiesProvider
        self.conventionRulesProvider = conventionRulesProvider
        self.contractHistoryProvider = contractHistoryProvider
        self.workSourceProvider = workSourceProvider
        self.incomeTaxStore = incomeTaxStore
        self.calendar = calendar
        self.selectedPeriod = period
        self.storedCompanies = storedCompanies
        self.storedSelectedCompanyId = companyId
        self.storedPaidWork = paidWork
        self.storedContractSegmentPaidWork = contractSegmentPaidWork
        self.storedConventionCoverage = conventionCoverage
        self.storedContractResolution = contractResolution
        self.storedSocialProfile = socialProfile
        self.storedAbsenceSource = absenceSource
        self.storedSegmentedProrationSource = segmentedProrationSource
        self.storedSegmentedMonthlyBase = segmentedMonthlyBase
        self.storedSegmentedPayrollBoundary = segmentedPayrollBoundary
        self.storedSegmentedProrationSourceText = segmentedProrationSource?.proration?.sourceId ?? ""
        self.storedSegmentedProrationDraftSegments = segmentedProrationDraftSegments
        let needsSegmentedSource =
            !(segmentedPayrollBoundary?.transitionEpochDays.isEmpty ?? true) ||
            ((contractResolution?.resolution?.calculationSegments.count ?? 0) > 1 &&
             contractResolution?.readyForSingleContractCalculation != true)
        let reference = needsSegmentedSource
            ? nil : companyId.flatMap { referenceProvider($0, period) }
        let segmentedWorkedGross = needsSegmentedSource
            ? Self.resolveSegmentedWorkedGross(
                company: companyId.flatMap {
                    SalaryCompanyStoreV2.confirmedCompany(storedCompanies, companyId: $0)
                },
                period: period,
                contracts: contractResolution?.resolution,
                rules: conventionCoverage,
                work: workSource,
                calendar: calendar,
                now: now
            )
            : nil
        self.storedSegmentedWorkedGross = segmentedWorkedGross
        self.storedSegmentedCashGross = Self.resolveSegmentedCashGross(
            company: companyId.flatMap {
                SalaryCompanyStoreV2.confirmedCompany(storedCompanies, companyId: $0)
            },
            period: period,
            worked: segmentedWorkedGross,
            socialProfile: socialProfile
        )
        self.storedSegmentedSocialCeiling = needsSegmentedSource
            ? Self.resolveSegmentedCeiling(
                companyId: companyId,
                period: period,
                contracts: contractResolution?.resolution,
                absences: absenceSource,
                work: workSource,
                variableSource: segmentedWorkedGross?.worked?.variables,
                calendar: calendar
            )
            : nil
        self.storedSnapshot = SalaryWorkspaceResolverV2.resolve(
            period: period,
            requiresSegmentedSource: needsSegmentedSource,
            segmented: needsSegmentedSource ? companyId.flatMap { segmentedProvider($0, period) } : nil,
            reference: reference,
            incomeTaxRate: taxRate,
            expectedCompanyId: companyId
        )
        self.storedIncomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        self.storedIncomeTaxSource = taxRate?.source ?? ""
        hydrateContractForm(from: contractResolution?.resolution?.coverage?.singleSnapshotForWholePeriod)
        hydrateConventionClassification()
    }

    var selectedCompany: SalaryCompanyV2? {
        guard let selectedCompanyId else { return nil }
        return SalaryCompanyStoreV2.confirmedCompany(companies, companyId: selectedCompanyId)
    }

    var requiresExplicitCompanySelection: Bool {
        companies.reliable && companies.companies.count > 1 && selectedCompanyId == nil
    }

    var requiresSegmentedProration: Bool {
        let segments = contractResolution?.resolution?.calculationSegments ?? []
        return segments.count > 1 && contractResolution?.readyForSingleContractCalculation != true
    }

    var hasMaterialSegmentedPayrollTransition: Bool {
        !(segmentedPayrollBoundary?.transitionEpochDays.isEmpty ?? true)
    }

    var displayWarnings: [String] {
        guard accountIsCurrent else { return [SalaryAccountStorageV2.warning] }
        let companyWarnings = companies.warnings
        let conventionWarnings = conventionCoverage?.warnings ?? []
        let contractWarnings = contractResolution?.warnings ?? []
        let socialProfileWarnings = socialProfile?.warnings ?? []
        let absenceWarnings = absenceSource?.warnings ?? []
        let segmentedProrationWarnings = requiresSegmentedProration
            ? (segmentedProrationSource?.warnings ?? [])
            : []
        let segmentedBaseWarnings = requiresSegmentedProration
            ? (segmentedMonthlyBase?.warnings ?? [])
            : []
        let segmentedBoundaryWarnings = hasMaterialSegmentedPayrollTransition
            ? (segmentedPayrollBoundary?.warnings ?? [])
            : []
        let segmentedWorkedWarnings = segmentedWorkedGross?.warnings ?? []
        let segmentedCashWarnings = segmentedCashGross?.warnings ?? []
        let segmentedCeilingWarnings = segmentedSocialCeiling?.warnings ?? []
        let segmentedWorkWarnings = contractSegmentPaidWork?.warnings ?? []
        let workspaceWarnings = snapshot.warnings
        let workWarnings = paidWork?.warnings ?? []
        let selectionWarnings: [String] = requiresExplicitCompanySelection
            ? ["Salaire V2 : plusieurs entreprises sont confirmées ; choisissez explicitement l'entreprise à analyser."]
            : []

        return unique(
            companyWarnings
            + conventionWarnings
            + contractWarnings
            + socialProfileWarnings
            + absenceWarnings
            + segmentedProrationWarnings
            + segmentedBaseWarnings
            + segmentedBoundaryWarnings
            + segmentedWorkedWarnings
            + segmentedCashWarnings
            + segmentedCeilingWarnings
            + segmentedWorkWarnings
            + workspaceWarnings
            + workWarnings
            + selectionWarnings
        )
    }

    var payrollCoverageBounds: (start: Int64, end: Int64)? {
        guard let month = SalaryConventionCoverageResolverV2.monthEpochDayRange(selectedPeriod) else { return nil }
        return SalarySegmentedWorkedGrossProductionBridgeV2.coverageBounds(start: month.start, end: month.end)
    }

    var payrollCoverageTimeZoneId: String { calendar.timeZone.identifier }

    var canConfirmPayrollCoverage: Bool {
        guard selectedCompany != nil, let bounds = payrollCoverageBounds,
              workSourceProvider().reliable else { return false }
        return SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: bounds.end, checkedAt: Date(), timeZoneId: payrollCoverageTimeZoneId
        )
    }

    /// Appelé uniquement après confirmation explicite de l'utilisateur dans l'écran Salaire.
    @discardableResult
    func confirmPayrollCoverage() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let requestedCompany = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard selectedCompanyId == requestedCompany,
              let company = selectedCompany, let bounds = payrollCoverageBounds,
              canConfirmPayrollCoverage else { return false }
        let now = Date()
        let saved = SalaryPayrollCoverageStoreV2.saveConfirmed(
            work: workSourceProvider(), employerId: company.id,
            coveredStartEpochDay: bounds.start, coveredEndEpochDay: bounds.end,
            checkedAt: now, timeZoneId: payrollCoverageTimeZoneId, now: now
        )
        guard saved != nil else { return false }
        refresh()
        return true
    }

    func refresh() {
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        synchronizeCompanySelectionWithLatestStore()
        recompute()
    }

    /// Sélection volontaire d'une entreprise confirmée.
    /// `nil` efface le choix ; un identifiant inconnu ou un stockage non fiable est rejeté.
    @discardableResult
    func selectCompany(_ requestedCompanyId: String?) -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        companies = companiesProvider()

        guard let requestedCompanyId else {
            selectedCompanyId = nil
            selectedCompanyWasExplicit = false
            contractFeedback = nil
            socialProfileFeedback = nil
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
            socialProfileFeedback = nil
            recompute()
            return false
        }

        selectedCompanyId = confirmed
        selectedCompanyWasExplicit = companies.companies.count > 1
        incomeTaxFeedback = nil
        contractFeedback = nil
        socialProfileFeedback = nil
        classificationFeedback = nil
        absenceFeedback = nil
        segmentedProrationFeedback = nil
        recompute()
        return true
    }

    @discardableResult
    func confirmEmploymentContract() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
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
    func confirmSocialProfile() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            socialProfileFeedback = "Confirmation impossible : l’entreprise analysée a changé. Vérifiez la sélection."
            return false
        }

        guard let status = SalaryProfessionalStatusV2(
            rawValue: socialProfessionalStatusSelection
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased()
        ) else {
            socialProfileFeedback = "Profil social : choisissez explicitement Cadre ou Non-cadre."
            return false
        }

        let localRegime: Bool
        switch socialAlsaceMoselleSelection
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() {
        case "YES":
            localRegime = true
        case "NO":
            localRegime = false
        default:
            socialProfileFeedback = "Profil social : confirmez explicitement l’affiliation au régime local Alsace-Moselle."
            return false
        }

        guard let effectiveDate = epochDay(from: socialEffectiveDateText) else {
            socialProfileFeedback = "Profil social : date d’effet invalide — JJ/MM/AAAA."
            return false
        }
        let source = socialSourceText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else {
            socialProfileFeedback = "Profil social : indiquez la source qui confirme cette version."
            return false
        }

        let saved = SalaryEmployeeSocialProfileStoreV2.upsertEffectiveVersion(
            companyId: companyId,
            effectiveFromEpochDay: effectiveDate,
            professionalStatus: status,
            alsaceMoselleLocalRegime: localRegime,
            sourceId: source,
            checkedAtMs: Int64((Date().timeIntervalSince1970 * 1_000).rounded())
        )
        guard saved else {
            socialProfileFeedback = "Profil social : enregistrement refusé ; vérifiez l’historique et les données confirmées."
            return false
        }

        socialProfileFeedback = "Version sociale datée confirmée pour cette entreprise."
        refresh()
        return true
    }

    @discardableResult
    func confirmConventionClassification() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            classificationFeedback = "Classification : l’entreprise analysée a changé. Vérifiez la sélection."
            return false
        }

        let coefficientRaw = classificationCoefficientText
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let coefficient: Int?
        if coefficientRaw.isEmpty {
            coefficient = nil
        } else if let value = Int(coefficientRaw), value > 0 {
            coefficient = value
        } else {
            classificationFeedback = "Classification : coefficient invalide."
            return false
        }

        func value(_ raw: String) -> String? {
        guard accountIsCurrent else { return nil }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }

        let classification = ConventionClassificationV2(
            coefficient: coefficient,
            level: value(classificationLevelText),
            echelon: value(classificationEchelonText),
            position: value(classificationPositionText),
            group: value(classificationGroupText),
            category: value(classificationCategoryText),
            employment: value(classificationEmploymentText)
        )
        guard !classification.isEmpty else {
            classificationFeedback = "Classification : renseignez au moins un critère exact."
            return false
        }

        guard SalaryConventionClassificationStoreV2.save(
            companyId: companyId,
            value: classification
        ) else {
            classificationFeedback = "Classification : enregistrement refusé ou stockage non fiable."
            return false
        }

        classificationFeedback = "Classification conventionnelle enregistrée pour cette entreprise."
        refresh()
        return true
    }

    @discardableResult
    func saveAbsence() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            absenceFeedback = "Absence : l’entreprise analysée a changé. Vérifiez la sélection."
            return false
        }

        guard let start = localCivilDate(from: absenceStartDateText),
              let endInclusive = localCivilDate(from: absenceEndDateText),
              let endExclusive = calendar.date(byAdding: .day, value: 1, to: endInclusive),
              endExclusive > start else {
            absenceFeedback = "Absence : vérifiez les dates de début et de fin — JJ/MM/AAAA."
            return false
        }
        guard let treatment = SalaryAbsenceTreatmentV2(
            rawValue: absenceTreatmentSelection
        ) else {
            absenceFeedback = "Absence : traitement salarial à confirmer."
            return false
        }
        let type = absenceTypeSelection.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !type.isEmpty else {
            absenceFeedback = "Absence : type à confirmer."
            return false
        }

        let absence = SalaryAbsenceFactV2(
            id: UUID().uuidString,
            employerId: companyId,
            type: type,
            start: start,
            end: endExclusive,
            salaryTreatment: treatment,
            fullDay: absenceFullDay,
            status: .confirmed
        )
        guard SalaryAbsenceStoreV2.save(
            companyId: companyId,
            absence: absence
        ) else {
            absenceFeedback = "Absence : enregistrement refusé ou stockage non fiable."
            return false
        }

        absenceStartDateText = ""
        absenceEndDateText = ""
        absenceFeedback = "Absence enregistrée. Reconfirmez ensuite l’exhaustivité du mois."
        refresh()
        return true
    }

    @discardableResult
    func removeAbsence(_ id: String) -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        guard let companyId = selectedCompanyId,
              SalaryAbsenceStoreV2.remove(
                companyId: companyId,
                absenceId: id
              ) else {
            absenceFeedback = "Absence : suppression impossible."
            return false
        }
        absenceFeedback = "Absence supprimée. Reconfirmez l’exhaustivité du mois."
        refresh()
        return true
    }

    @discardableResult
    func confirmAbsenceMonthCoverage() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let source = absenceMonthSourceText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let companyId = selectedCompanyId, !source.isEmpty else {
            absenceFeedback = "Absences : indiquez la source de confirmation du mois."
            return false
        }
        guard SalaryAbsenceStoreV2.confirmMonth(
            companyId: companyId,
            period: selectedPeriod,
            source: source
        ) else {
            absenceFeedback = "Absences : confirmation mensuelle impossible."
            return false
        }
        absenceFeedback = "Liste des absences confirmée exhaustive pour ce mois."
        refresh()
        return true
    }

    func updateSegmentedProrationMinutes(segmentId: String, text: String) {
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        guard let index = segmentedProrationDraftSegments.firstIndex(where: { $0.id == segmentId }) else {
            return
        }
        segmentedProrationDraftSegments[index].scheduledMinutesText = text
        segmentedProrationFeedback = nil
    }

    @discardableResult
    func confirmSegmentedProration() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let targetBeforeReconciliation = selectedCompanyId
        synchronizeCompanySelectionWithLatestStore()
        guard let companyId = SalaryCompanySelectionV2.stableMutationTarget(
            beforeReconciliation: targetBeforeReconciliation,
            afterReconciliation: selectedCompanyId
        ) else {
            recompute()
            segmentedProrationFeedback = "Proratisation : l’entreprise analysée a changé. Vérifiez la sélection."
            return false
        }
        guard requiresSegmentedProration,
              let segments = contractResolution?.resolution?.calculationSegments,
              segments.count > 1 else {
            segmentedProrationFeedback = "Proratisation : aucune segmentation contractuelle bloquante n’est à confirmer pour ce mois."
            return false
        }

        let source = segmentedProrationSourceText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else {
            segmentedProrationFeedback = "Proratisation : indiquez la source qui confirme les minutes planifiées."
            return false
        }

        let expectedIds = Set(
            segments.map {
                let versionId = $0.snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines)
                return "\(versionId)|\($0.startEpochDay)|\($0.endEpochDay)"
            }
        )
        guard expectedIds.count == segments.count,
              Set(segmentedProrationDraftSegments.map(\.id)) == expectedIds else {
            segmentedProrationFeedback = "Proratisation : les segments affichés ne correspondent plus au contrat résolu. Actualisez avant de confirmer."
            return false
        }

        var confirmedSegments: [ConfirmedProrationSegmentV2] = []
        for draft in segmentedProrationDraftSegments {
            let raw = draft.scheduledMinutesText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let minutes = Int(raw), minutes >= 0 else {
                segmentedProrationFeedback = "Proratisation : chaque segment doit avoir un nombre entier de minutes planifiées, positif ou nul."
                return false
            }
            confirmedSegments.append(
                ConfirmedProrationSegmentV2(
                    versionId: draft.versionId,
                    startEpochDay: draft.startEpochDay,
                    endEpochDay: draft.endEpochDay,
                    scheduledMinutes: minutes
                )
            )
        }

        let proration = ConfirmedSegmentedMonthlyProrationV2(
            sourceId: source,
            checkedAtMs: Int64((Date().timeIntervalSince1970 * 1_000).rounded()),
            segments: confirmedSegments
        )
        guard SalarySegmentedProrationStoreV2.save(
            companyId: companyId,
            period: selectedPeriod,
            proration: proration
        ) else {
            segmentedProrationFeedback = "Proratisation : confirmation refusée. Vérifiez les segments et le total de minutes."
            return false
        }

        segmentedProrationFeedback = "Minutes planifiées confirmées pour les segments de ce mois. Aucun montant n’est encore calculé par cette confirmation."
        refresh()
        return true
    }

    @discardableResult
    func removeSegmentedProration() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        guard let companyId = selectedCompanyId,
              SalarySegmentedProrationStoreV2.remove(
                companyId: companyId,
                period: selectedPeriod
              ) else {
            segmentedProrationFeedback = "Proratisation : suppression impossible."
            return false
        }
        segmentedProrationFeedback = "Proratisation retirée : le mois redevient inconnu tant qu’une nouvelle base n’est pas confirmée."
        refresh()
        return true
    }

    @discardableResult
    func confirmIncomeTaxRate() -> Bool {
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
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
        guard accountIsCurrent else { return false }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
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
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        guard delta != 0 else { return }
        let zeroBased = selectedPeriod.year * 12 + selectedPeriod.month - 1 + delta
        guard zeroBased >= 0 else { return }
        let next = YearMonthV2(year: zeroBased / 12, month: zeroBased % 12 + 1)
        guard let next else { return }
        selectedPeriod = next
        contractFeedback = nil
        socialProfileFeedback = nil
        absenceFeedback = nil
        segmentedProrationFeedback = nil
        refresh()
    }

    private func synchronizeCompanySelectionWithLatestStore() {
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
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
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        let companyId = selectedCompanyId
        let taxRate = companyId.map { incomeTaxStore.snapshot(companyId: $0, for: selectedPeriod) }

        if let companyId {
            confirmedSicknessCash = SalaryConfirmedSicknessCashV2.read(companyId: companyId, period: selectedPeriod)
            monthlySettlement = SalaryMonthlyPayrollSettlementV2.read(companyId: companyId, period: selectedPeriod)
            let source = workSourceProvider()
            paidWork = SalaryPaidWorkAggregatorV2.aggregate(
                sessions: source.sessions,
                employerId: companyId,
                period: selectedPeriod,
                sourceReliable: source.reliable,
                calendar: calendar
            )
            conventionCoverage = SalaryConventionPayrollBridgeV2.resolve(
                companyId: companyId,
                period: selectedPeriod,
                companies: companies,
                storedRules: conventionRulesProvider()
            ).coverage
            contractResolution = SalaryEmploymentContractPayrollBridgeV2.resolve(
                companyId: companyId,
                period: selectedPeriod,
                stored: contractHistoryProvider()
            )
            socialProfile = SalaryEmployeeSocialProfileStoreV2.resolve(
                companyId: companyId,
                period: selectedPeriod
            )
            absenceSource = SalaryAbsenceStoreV2.resolve(
                companyId: companyId,
                period: selectedPeriod
            )
            segmentedProrationSource = SalarySegmentedProrationStoreV2.resolve(
                companyId: companyId,
                period: selectedPeriod
            )
            segmentedMonthlyBase = SalarySegmentedMonthlyBaseProductionV2.resolve(
                contractSnapshot: contractResolution,
                conventionCoverage: conventionCoverage,
                prorationSource: segmentedProrationSource
            )
            if let contracts = contractResolution?.resolution,
               let rules = conventionCoverage {
                segmentedPayrollBoundary = SalarySegmentedPayrollBoundaryV2.assess(
                    contracts: contracts,
                    rules: rules
                )
            } else {
                segmentedPayrollBoundary = nil
            }
            let needsSegmentedSource = hasMaterialSegmentedPayrollTransition || requiresSegmentedProration
            segmentedWorkedGross = needsSegmentedSource
                ? Self.resolveSegmentedWorkedGross(
                    company: SalaryCompanyStoreV2.confirmedCompany(companies, companyId: companyId),
                    period: selectedPeriod,
                    contracts: contractResolution?.resolution,
                    rules: conventionCoverage,
                    work: source,
                    calendar: calendar,
                    now: Date()
                )
                : nil
            segmentedCashGross = Self.resolveSegmentedCashGross(
                company: SalaryCompanyStoreV2.confirmedCompany(companies, companyId: companyId),
                period: selectedPeriod,
                worked: segmentedWorkedGross,
                socialProfile: socialProfile
            )
            segmentedSocialCeiling = needsSegmentedSource
                ? Self.resolveSegmentedCeiling(
                    companyId: companyId,
                    period: selectedPeriod,
                    contracts: contractResolution?.resolution,
                    absences: absenceSource,
                    work: source,
                    variableSource: segmentedWorkedGross?.worked?.variables,
                    calendar: calendar
                )
                : nil
            segmentedProrationSourceText = segmentedProrationSource?.proration?.sourceId ?? ""
            segmentedProrationDraftSegments = SalarySegmentedProrationDraftBuilderV2.make(
                segments: contractResolution?.resolution?.calculationSegments ?? [],
                stored: segmentedProrationSource?.proration
            )
            if let segments = contractResolution?.resolution?.calculationSegments, !segments.isEmpty {
                contractSegmentPaidWork = SalaryContractSegmentPaidWorkAllocatorV2.allocate(
                    sessions: source.sessions,
                    segments: segments,
                    employerId: companyId,
                    period: selectedPeriod,
                    sourceReliable: source.reliable,
                    calendar: calendar
                )
            } else {
                contractSegmentPaidWork = nil
            }
        } else {
            paidWork = nil
            contractSegmentPaidWork = nil
            conventionCoverage = nil
            contractResolution = nil
            socialProfile = nil
            absenceSource = nil
            confirmedSicknessCash = nil
            monthlySettlement = nil
            segmentedProrationSource = nil
            segmentedMonthlyBase = nil
            segmentedPayrollBoundary = nil
            segmentedWorkedGross = nil
            segmentedCashGross = nil
            segmentedSocialCeiling = nil
            segmentedProrationSourceText = ""
            segmentedProrationDraftSegments = []
        }

        let needsSegmentedSource = hasMaterialSegmentedPayrollTransition || requiresSegmentedProration
        let reference = needsSegmentedSource
            ? nil : companyId.flatMap { referenceProvider($0, selectedPeriod) }
        snapshot = SalaryWorkspaceResolverV2.resolve(
            period: selectedPeriod,
            requiresSegmentedSource: needsSegmentedSource,
            segmented: needsSegmentedSource ? companyId.flatMap { segmentedProvider($0, selectedPeriod) } : nil,
            reference: reference,
            incomeTaxRate: taxRate,
            expectedCompanyId: companyId
        )
        snapshot.monthlySettlement = monthlySettlement
        incomeTaxRateText = taxRate?.ratePercent.map { String(format: "%.2f", $0) } ?? ""
        incomeTaxSource = taxRate?.source ?? ""
        hydrateContractForm(from: contractResolution?.resolution?.coverage?.singleSnapshotForWholePeriod)
        hydrateConventionClassification()
    }

    private static func resolveSegmentedWorkedGross(
        company: SalaryCompanyV2?,
        period: YearMonthV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2?,
        rules: SalaryConventionCoverageV2?,
        work: SalaryWorkSessionSourceV2?,
        calendar: Calendar,
        now: Date
    ) -> SalarySegmentedWorkedGrossDetailedBridgeResultV2? {
        guard let company, let contracts, let rules, let work else { return nil }
        return SalarySegmentedWorkedGrossProductionBridgeV2.calculateDetailedFromStores(
            companyId: company.id,
            companyAddress: company.address,
            period: period,
            timeZoneId: calendar.timeZone.identifier,
            work: work,
            contracts: contracts,
            rules: rules,
            now: now
        )
    }

    private static func resolveSegmentedCashGross(
        company: SalaryCompanyV2?,
        period: YearMonthV2,
        worked: SalarySegmentedWorkedGrossDetailedBridgeResultV2?,
        socialProfile: SalaryEmployeeSocialProfileResolutionV2?
    ) -> SalarySegmentedCashGrossAssemblyResultV2? {
        guard let company,
              let worked,
              worked.reliable,
              let result = worked.worked,
              result.base.reliable,
              let baseGross = result.base.baseGross else { return nil }
        let fixed = SalarySegmentedFixedCashComponentsBridgeV2.load(
            companyId: company.id,
            idcc: company.idcc,
            period: period,
            actualMonthlyBaseGross: baseGross,
            professionalStatus: socialProfile?.reliable == true
                ? socialProfile?.professionalStatus?.rawValue : nil
        )
        return SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: result,
            fixed: fixed
        )
    }

    private static func resolveSegmentedCeiling(
        companyId: String?,
        period: YearMonthV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2?,
        absences: SalaryAbsenceSourceV2?,
        work: SalaryWorkSessionSourceV2?,
        variableSource: SalarySegmentedWorkedVariableGrossSourceResultV2?,
        calendar: Calendar
    ) -> SalarySegmentedSocialSecurityCeilingResultV2? {
        guard let companyId, let contracts, let absences, let work else { return nil }
        let impact = SalaryAbsencePayrollImpactV2.forMonth(
            absences: absences.absences,
            period: period,
            acceptedEmployerIds: [companyId],
            workSessions: work.sessions,
            absenceSourceReliable: absences.reliable,
            workSourceReliable: work.reliable,
            calendar: calendar
        )
        let ceiling = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period,
            contracts: contracts,
            complementaryMinutes: SalarySegmentedSocialSecurityCeilingV2.confirmedComplementaryMinutes(
                from: variableSource
            ),
            unpaidAbsenceDays: impact.requiresPayrollReview
                ? nil : impact.unpaidFullCalendarDays
        )
        return SalarySegmentedSocialSecurityCeilingResultV2(
            ceiling: ceiling.ceiling,
            warnings: Array(Set(impact.warnings + ceiling.warnings)).sorted()
        )
    }

    private func localCivilDate(from raw: String) -> Date? {
        let parts = raw.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let day = Int(parts[0]),
              let month = Int(parts[1]),
              let year = Int(parts[2]),
              let civil = PayrollCivilDateV2(year: year, month: month, day: day) else {
            return nil
        }
        return calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: civil.year,
                month: civil.month,
                day: civil.day,
                hour: 0,
                minute: 0,
                second: 0
            )
        )
    }

    private func hydrateConventionClassification() {
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
        guard let companyId = selectedCompanyId else {
            classificationCoefficientText = ""
            classificationLevelText = ""
            classificationEchelonText = ""
            classificationPositionText = ""
            classificationGroupText = ""
            classificationCategoryText = ""
            classificationEmploymentText = ""
            return
        }
        let value = SalaryConventionClassificationStoreV2.load(companyId: companyId)
        classificationCoefficientText = value.coefficient.map(String.init) ?? ""
        classificationLevelText = value.level ?? ""
        classificationEchelonText = value.echelon ?? ""
        classificationPositionText = value.position ?? ""
        classificationGroupText = value.group ?? ""
        classificationCategoryText = value.category ?? ""
        classificationEmploymentText = value.employment ?? ""
    }

    private func hydrateContractForm(from stored: SalaryEmploymentContractSnapshotV2?) {
        guard accountIsCurrent else { return  }
        let operation = SalaryAccountSessionV2.pin(accountLease)
        defer { operation.close() }
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
