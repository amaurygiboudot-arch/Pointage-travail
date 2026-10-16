import Foundation
import Combine

@MainActor
final class WorkStoreV2: ObservableObject {
    @Published private var loadedSessions: [WorkSession] = []
    @Published private var loadedStorageReliable = false
    private var loadedDiagnosticIssue: DailyDiagnosticV2.SourceIssue = .accountUnavailable
    private var loadedAccount: String?
    private let accountProvider: () -> String?

    // Never expose the previous account while the authentication notification is pending.
    var sessions: [WorkSession] { accountMatches ? loadedSessions : [] }
    var storageReliable: Bool { accountMatches && loadedStorageReliable }
    // The active journal can accept new facts while historical ownership remains unresolved.
    // Payroll must not treat that incomplete history as a verified zero.
    var payrollHistoryReliable: Bool { storageReliable && !hasUnassignedHistory }
    var hasUnassignedHistory: Bool {
        WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults)
    }

    func recoveryPreview() throws -> WorkSessionAccountStorageV2.RecoveryPreview {
        try WorkSessionAccountStorageV2.recoveryPreview(account: accountProvider(), defaults: defaults)
    }

    func recoverHistoricalJournal(_ preview: WorkSessionAccountStorageV2.RecoveryPreview) throws {
        defer { reloadAccount() }
        try WorkSessionAccountStorageV2.recover(preview, accountProvider: accountProvider, defaults: defaults)
    }
    private var accountMatches: Bool {
        loadedAccount != nil && loadedAccount == WorkSessionAccountStorageV2.scope(accountProvider())
    }

    var diagnosticAccount: String? { accountMatches ? loadedAccount : nil }
    var diagnosticHistory: WorkSessionAccountStorageV2.DiagnosticHistory {
        WorkSessionAccountStorageV2.diagnosticHistory(defaults: defaults)
    }
    var diagnosticSourceIssue: DailyDiagnosticV2.SourceIssue {
        accountMatches ? loadedDiagnosticIssue : .accountUnavailable
    }
    private func diagnostic(_ kind: DailyDiagnosticV2.Kind, at date: Date? = nil, sessionId: UUID? = nil,
                            origin: DailyDiagnosticV2.Origin? = nil, rejection: DailyDiagnosticV2.Rejection? = nil) {
        // Diagnostic failures never change the canonical journal or its commit result.
        try? DailyDiagnosticV2.append(kind, account: diagnosticAccount, occurredAt: date, sessionId: sessionId,
                                     origin: origin, rejection: rejection, root: diagnosticRoot)
    }

    private let defaults: UserDefaults
    private let diagnosticRoot: URL

    init(defaults: UserDefaults = .standard, diagnosticRoot: URL = DailyDiagnosticV2.directory,
         accountProvider: @escaping () -> String?) {
        self.defaults = defaults
        self.diagnosticRoot = diagnosticRoot
        self.accountProvider = accountProvider
        reloadAccount()
    }

    var currentSession: WorkSession? {
        guard storageReliable else { return nil }
        return sessions.last(where: { $0.exit == nil })
    }

    var isWorking: Bool { currentSession != nil }
    var isPaused: Bool { currentSession?.pauses.last?.end == nil && currentSession?.pauses.last != nil }
    var currentPauseNeedsQualification: Bool {
        guard storageReliable,
              let pause = currentSession?.pauses.last(where: { $0.end == nil }) else {
            return false
        }
        return pause.paid == nil
    }

    @discardableResult
    func clockIn(
        at entryDate: Date = Date(),
        employerId requestedEmployerId: String? = nil,
        placeLabel: String? = nil
    ) -> Bool {
        diagnostic(.clockInRequested, at: entryDate, sessionId: currentSession?.id)
        guard storageReliable else {
            diagnostic(.clockInRejected, at: entryDate, rejection: .storageUnavailable)
            return false
        }
        guard !isWorking else {
            diagnostic(.clockInRejected, at: entryDate, sessionId: currentSession?.id, rejection: .sessionAlreadyOpen)
            return false
        }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        switch ClockInEmployerResolverV2.resolve(
            requestedEmployerId: requestedEmployerId,
            companies: companies
        ) {
        case .unassigned:
            return appendClockIn(
                entryDate: entryDate,
                employerId: nil,
                placeLabel: placeLabel
            )
        case .employer(let employerId):
            return appendClockIn(
                entryDate: entryDate,
                employerId: employerId,
                placeLabel: placeLabel
            )
        case .rejected:
            diagnostic(.clockInRejected, at: entryDate, rejection: .employerUnresolved)
            return false
        }
    }

    private func appendClockIn(
        entryDate: Date,
        employerId: String?,
        placeLabel: String?
    ) -> Bool {
        guard entryDate.timeIntervalSinceReferenceDate.isFinite,
              entryDate <= Date().addingTimeInterval(300),
              sessions.allSatisfy({ session in
                  guard let exit = session.exit else { return false }
                  return exit <= entryDate
              }) else {
            diagnostic(.clockInRejected, at: entryDate, rejection: .invalidTimeOrOverlap)
            return false
        }
        var updated = sessions
        updated.append(
            WorkSession(
                id: UUID(),
                entry: entryDate,
                exit: nil,
                pauses: [],
                employerId: employerId,
                placeLabel: placeLabel
            )
        )
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            diagnostic(.clockInRejected, at: entryDate, rejection: .persistenceFailed)
            return false
        }
        loadedSessions = updated
        diagnostic(.clockInCommitted, at: entryDate, sessionId: updated.last?.id)
        return true
    }

    @discardableResult
    func togglePause(paid: Bool? = nil, at date: Date = Date()) -> Bool {
        diagnostic(.pauseRequested, at: date, sessionId: currentSession?.id)
        guard storageReliable,
              date <= Date().addingTimeInterval(300),
              let updated = WorkSessionMutationV2.togglingPause(
                  in: sessions,
                  at: date,
                  paid: paid
              ) else {
            return false
        }
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            return false
        }
        loadedSessions = updated
        diagnostic(isPaused ? .pauseStarted : .pauseEnded, at: date, sessionId: currentSession?.id)
        return true
    }

    @discardableResult
    func clockOut(
        at exitDate: Date = Date(),
        expectedSessionId: UUID? = nil,
        origin: DailyDiagnosticV2.Origin = .unknown
    ) -> Bool {
        let observed = (origin == .manual || origin == .widget)
            ? eligibleDeparture(at: exitDate) : nil
        let exitDate = observed ?? exitDate
        diagnostic(.clockOutRequested, at: exitDate, sessionId: currentSession?.id, origin: origin)
        let closingSessionId = currentSession?.id
        guard storageReliable,
              exitDate <= Date().addingTimeInterval(300),
              var updated = WorkSessionMutationV2.closingSession(
                  in: sessions,
                  at: exitDate,
                  expectedSessionId: expectedSessionId
              ) else {
            diagnostic(.clockOutRejected, at: exitDate, sessionId: closingSessionId, origin: origin,
                       rejection: storageReliable ? .invalidTimeOrSession : .storageUnavailable)
            return false
        }
        if let index = updated.firstIndex(where: { $0.id == closingSessionId }) {
            updated[index].closureOrigin = origin.rawValue
            updated[index].departureObservedAt = observed ?? (origin == .automatic ? exitDate : nil)
        }
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            diagnostic(.clockOutRejected, at: exitDate, sessionId: closingSessionId, origin: origin, rejection: .persistenceFailed)
            return false
        }
        loadedSessions = updated
        diagnostic(.clockOutCommitted, at: exitDate, sessionId: closingSessionId, origin: origin)
        return true
    }

    private func departureContext() -> (GpsPersistedStateV2, [GpsZoneV2], String, WorkSession)? {
        guard storageReliable, let account = diagnosticAccount, let session = currentSession,
              defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey),
              case .valid(let zones) = GpsZoneConfigurationV2.read(defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)),
              case .valid(let state) = GpsStateStoreV2.read(defaults.data(forKey: GpsStateStoreV2.scopedKey(account: account))),
              let fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones),
              GpsStateValidationV2.startupState(read: .valid(state), expectedFingerprint: fingerprint,
                configuredZoneIds: GpsZoneConfigurationV2.automaticZoneIds(zones), expectedAccountScope: account) == .resume(state)
        else { return nil }
        return (state, zones, account, session)
    }

    private func eligibleDeparture(at now: Date) -> Date? {
        guard let (state, zones, account, session) = departureContext() else { return nil }
        return DeparturePolicyV2.eligibleObservation(state: state, account: account, session: session, zones: zones, now: now)
    }

    /// Explicit account/company API. No UI activation is shipped before a canonical movement
    /// resolver exists on iOS. Absence of travel evidence is not evidence of no travel.
    func departurePolicy(employerId: String) -> DepartureAccountPolicyV2? {
        guard let account = diagnosticAccount else { return nil }
        return DepartureAccountPolicyStoreV2.read(account: account, employerId: employerId, defaults: defaults)
    }

    @discardableResult
    func setDeparturePolicy(_ policy: DepartureAccountPolicyV2) -> Bool {
        guard policy.account == diagnosticAccount else { return false }
        return DepartureAccountPolicyStoreV2.write(policy, defaults: defaults)
    }

    /// Invoke on a future canonical movement resolver cycle/app wake. Default is fail-closed;
    /// the production UI never supplies fabricated movement clearance.
    @discardableResult
    func resolveAutomaticDeparture(movementAndAmbiguityResolved: Bool = false, now: Date = Date()) -> Bool {
        guard let (state, zones, account, session) = departureContext(),
              let employerId = session.employerId,
              let policy = departurePolicy(employerId: employerId),
              let observed = DeparturePolicyV2.automaticExit(state: state, account: account,
                session: session, zones: zones, policy: policy,
                movementAndAmbiguityResolved: movementAndAmbiguityResolved, now: now) else { return false }
        return clockOut(at: observed, expectedSessionId: session.id, origin: .automatic)
    }

    @discardableResult
    func rollbackClockIn(expectedSessionId: UUID) -> Bool {
        guard storageReliable,
              let index = sessions.lastIndex(where: { $0.exit == nil }),
              sessions[index].id == expectedSessionId,
              sessions[index].pauses.isEmpty else {
            return false
        }
        var updated = sessions
        updated.remove(at: index)
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            return false
        }
        loadedSessions = updated
        diagnostic(.clockInRolledBack, at: nil, sessionId: expectedSessionId)
        return true
    }

    @discardableResult
    func addManualSession(
        entry: Date,
        exit: Date,
        employerId requestedEmployerId: String?,
        placeLabel: String?
    ) -> Bool {
        diagnostic(.manualSessionRequested, at: entry, sessionId: currentSession?.id)
        guard storageReliable else { return false }

        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        let employerId: String?
        switch ClockInEmployerResolverV2.resolve(
            requestedEmployerId: requestedEmployerId,
            companies: companies
        ) {
        case .unassigned:
            employerId = nil
        case .employer(let confirmedEmployerId):
            employerId = confirmedEmployerId
        case .rejected:
            return false
        }

        guard let updated = ManualSessionPolicyV2.appending(
            to: sessions,
            entry: entry,
            exit: exit,
            employerId: employerId,
            placeLabel: placeLabel
        ) else {
            return false
        }
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            return false
        }
        loadedSessions = updated
        diagnostic(.manualSessionAdded, at: entry, sessionId: updated.first(where: { $0.entry == entry && $0.exit == exit })?.id)
        diagnostic(.manualSessionExit, at: exit, sessionId: updated.first(where: { $0.entry == entry && $0.exit == exit })?.id)
        return true
    }

    func paidTimeAssessment(for session: WorkSession, until endDate: Date = Date()) -> PaidTimeAssessmentV2 {
        PaidTimePolicyV2.assess(
            sessionStart: session.entry,
            sessionEnd: session.exit,
            pauses: session.pauses.map {
                PaidPauseFactV2(start: $0.start, end: $0.end, paid: $0.paid)
            },
            until: endDate
        )
    }

    private func persistCanonical(_ decoded: [WorkSession]) -> Bool {
        guard accountMatches, let loadedAccount else { return false }
        let committed = WorkSessionAccountStorageV2.write(decoded, account: loadedAccount, defaults: defaults)
        if !committed { loadedDiagnosticIssue = .writeFailed }
        return committed
    }

    func reloadAccount() {
        loadedSessions = []
        loadedStorageReliable = false
        loadedDiagnosticIssue = .accountUnavailable
        loadedAccount = WorkSessionAccountStorageV2.scope(accountProvider())
        guard let loadedAccount else { return }
        diagnostic(.journalLoadRequested)
        switch WorkSessionAccountStorageV2.read(account: loadedAccount, defaults: defaults) {
        case .missing:
            loadedStorageReliable = true
            loadedDiagnosticIssue = .none
        case .valid(let decoded):
            loadedSessions = decoded
            loadedStorageReliable = true
            loadedDiagnosticIssue = .none
        case .corrupt:
            loadedDiagnosticIssue = .corrupt
        }
    }
}
