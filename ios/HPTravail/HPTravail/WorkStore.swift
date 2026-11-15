import Foundation
import Combine

/// One read-only view of the GPS journals, shared by all history sections.
struct GpsHistorySnapshotV2 {
    let storageReliable: Bool
    let observationHistoryReliable: Bool
    let journeyHistoryReliable: Bool
    let observations: [GpsSiteObservationV2]
    let historicalObservations: [GpsLegacyObservationV2]
    let journeys: [GpsSiteJourneyV2]

    static let unavailable = GpsHistorySnapshotV2(storageReliable: false,
        observationHistoryReliable: false, journeyHistoryReliable: false,
        observations: [], historicalObservations: [], journeys: [])
}

private enum GpsHistoryJournalSourceV2: Equatable {
    case missing
    case data(Data)
    case unreadable

    init(_ object: Any?) {
        if let data = object as? Data { self = .data(data) }
        else { self = object == nil ? .missing : .unreadable }
    }
}

private struct GpsHistorySourcesV2: Equatable {
    let account: String
    let storageReliable: Bool
    let archive: GpsHistoryJournalSourceV2
    let state: GpsHistoryJournalSourceV2
    let home: GpsHistoryJournalSourceV2
}

@MainActor
final class WorkStoreV2: ObservableObject {
    @Published private var loadedSessions: [WorkSession] = []
    @Published private var loadedStorageReliable = false
    private var loadedDiagnosticIssue: DailyDiagnosticV2.SourceIssue = .accountUnavailable
    private var loadedAccount: String?
    private let accountProvider: () -> String?
    private var cachedGpsHistory: (sources: GpsHistorySourcesV2, snapshot: GpsHistorySnapshotV2)?

    // Never expose the previous account while the authentication notification is pending.
    var sessions: [WorkSession] { accountMatches ? loadedSessions : [] }
    var storageReliable: Bool { accountMatches && loadedStorageReliable }
    // The active journal can accept new facts while historical ownership remains unresolved.
    // Payroll must not treat that incomplete history as a verified zero.
    var payrollHistoryReliable: Bool { storageReliable && !hasUnassignedHistory }
    var hasUnassignedHistory: Bool {
        WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults)
    }

    private var recoveryDiagnosticGeneration: UInt64 = 0
    private var observedDiagnosticAuthGeneration: UInt64?
    private var observedDiagnosticAccount: String?
    private let authenticationGenerationProvider: () -> UInt64

    private func recoveryDiagnosticContext() -> (String?, UInt64) {
        let account = accountProvider(), generation = authenticationGenerationProvider()
        if generation != observedDiagnosticAuthGeneration || account != observedDiagnosticAccount {
            recoveryDiagnosticGeneration &+= 1
            observedDiagnosticAuthGeneration = generation
            observedDiagnosticAccount = account
        }
        return (account, recoveryDiagnosticGeneration)
    }

    func beginRecoveryDiagnostic() throws -> WorkSessionAccountStorageV2.DiagnosticConsent {
        let context = recoveryDiagnosticContext()
        return try WorkSessionAccountStorageV2.diagnosticConsent(account: context.0, generation: context.1, defaults: defaults)
    }

    func recoveryDiagnosticIsCurrent(_ consent: WorkSessionAccountStorageV2.DiagnosticConsent) -> Bool {
        let context = recoveryDiagnosticContext()
        return WorkSessionAccountStorageV2.diagnosticIsCurrent(consent, account: context.0, generation: context.1, defaults: defaults)
    }

    func consultRecoveryDiagnostic(_ consent: WorkSessionAccountStorageV2.DiagnosticConsent,
                                   ownershipDeclared: Bool) throws -> WorkSessionAccountStorageV2.RecoveryDiagnostic {
        try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: ownershipDeclared,
            contextProvider: recoveryDiagnosticContext, defaults: defaults)
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
         authenticationGenerationProvider: @escaping () -> UInt64 = { SalaryAccountSessionV2.capture().generation },
         accountProvider: @escaping () -> String?) {
        self.defaults = defaults
        self.diagnosticRoot = diagnosticRoot
        self.accountProvider = accountProvider
        self.authenticationGenerationProvider = authenticationGenerationProvider
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

    /// Legacy qualified-departure policy. Factual site exits use processAutomaticGpsPointage
    /// and unqualified segments; they do not assert that professional travel has ended.
    func departurePolicy(employerId: String) -> DepartureAccountPolicyV2? {
        guard let account = diagnosticAccount else { return nil }
        return DepartureAccountPolicyStoreV2.read(account: account, employerId: employerId, defaults: defaults)
    }

    @discardableResult
    func setDeparturePolicy(_ policy: DepartureAccountPolicyV2) -> Bool {
        guard policy.account == diagnosticAccount else { return false }
        return DepartureAccountPolicyStoreV2.write(policy, defaults: defaults)
    }

    /// Qualified closure remains fail-closed without a canonical movement resolver.
    /// It is distinct from the unqualified presence facts consumed by the GPS coordinator.
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
            until: endDate,
            segments: SalaryWorkSessionBridgeV2.paidSegments(for: session)
        )
    }

    var gpsHistorySnapshot: GpsHistorySnapshotV2 {
        guard let account = diagnosticAccount else {
            cachedGpsHistory = nil
            return .unavailable
        }
        let sources = gpsHistorySources(account: account)
        if let cachedGpsHistory, cachedGpsHistory.sources == sources,
           diagnosticAccount == account { return cachedGpsHistory.snapshot }

        // Each journal is decoded at most once after its exact bytes change. Missing
        // and unreadable values are distinct, including a non-Data GPS state value.
        let archive = GpsObservationArchiveStoreV2.read(account: account, defaults: defaults)
        let home = GpsHomeObservationStoreV2.read(account: account, defaults: defaults)
        let stateRead: GpsStateReadV2
        switch sources.state {
        case .missing: stateRead = .missing
        case .unreadable: stateRead = .corrupt
        case .data(let data): stateRead = GpsStateStoreV2.read(data)
        }
        let current: [GpsSiteObservationV2]
        let stateReliable: Bool
        switch stateRead {
        case .missing:
            current = []
            stateReliable = true
        case .corrupt:
            current = []
            stateReliable = false
        case .valid(let state):
            current = state.observations ?? []
            stateReliable = state.accountScope == account && Set(current.map(\.id)).count == current.count &&
                current.allSatisfy { $0.account == account && $0.fingerprint == state.fingerprint &&
                    $0.observedAt.timeIntervalSinceReferenceDate.isFinite }
        }
        let observationsReliable = archive != nil && stateReliable
        let journeysReliable = observationsReliable && home != nil
        var unique = [UUID: GpsSiteObservationV2]()
        if observationsReliable {
            for value in (archive?.observations ?? []) + current where value.account == account {
                unique[value.id] = value
            }
        }
        let observations = unique.values.sorted { $0.observedAt < $1.observedAt }
        // Explicit raw observations select the projection without the session fallback.
        let journeys = sources.storageReliable && journeysReliable
            ? GpsSiteJourneyProjectionV2.journeys(sessions: [], account: account,
                homeObservations: home, siteObservations: observations).filter { !$0.excludedByHome }
            : []
        let snapshot = GpsHistorySnapshotV2(storageReliable: sources.storageReliable,
            observationHistoryReliable: observationsReliable, journeyHistoryReliable: journeysReliable,
            observations: observations, historicalObservations: archive?.legacy ?? [], journeys: journeys)
        // A concurrent external defaults write must not cache a mixture of revisions.
        guard diagnosticAccount == account, gpsHistorySources(account: account) == sources else {
            cachedGpsHistory = nil
            return .unavailable
        }
        cachedGpsHistory = (sources, snapshot)
        return snapshot
    }

    private func gpsHistorySources(account: String) -> GpsHistorySourcesV2 {
        .init(account: account, storageReliable: storageReliable,
            archive: .init(defaults.object(forKey: GpsObservationArchiveStoreV2.key(account: account))),
            state: .init(defaults.object(forKey: GpsStateStoreV2.scopedKey(account: account))),
            home: .init(defaults.object(forKey: GpsHomeObservationStoreV2.key(account: account))))
    }

    var observedSiteJourneys: [GpsSiteJourneyV2] { gpsHistorySnapshot.journeys }
    var observedSiteBoundaries: [GpsSiteObservationV2] { gpsHistorySnapshot.observations }
    var historicalGpsObservations: [GpsLegacyObservationV2] { gpsHistorySnapshot.historicalObservations }
    var gpsObservationHistoryReliable: Bool { gpsHistorySnapshot.observationHistoryReliable }
    var gpsJourneyHistoryReliable: Bool { gpsHistorySnapshot.journeyHistoryReliable }

    /// Runs without a view. The durable GPS queue is the outbox; the session journal owns
    /// pointage. A failed acknowledgement leaves the already committed session intact.
    @discardableResult
    func processAutomaticGpsPointage(now: Date = Date()) -> Bool {
        reloadAccount()
        guard storageReliable, let account = diagnosticAccount else { return false }
        guard defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey) else { return true }
        guard case .valid(let zones) = GpsZoneConfigurationV2.read(
            defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)) else { return false }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard companies.reliable else { return false }
        let key = GpsStateStoreV2.scopedKey(account: account)
        for _ in 0 ..< GpsPresenceTransitionV2.maximumPendingEventCount {
            guard accountMatches, diagnosticAccount == account,
                  case .valid(let state) = GpsStateStoreV2.read(defaults.data(forKey: key)) else { return false }
            guard let event = state.pendingEvents.first else { return true }
            // Retire old prompts into a visible archive before removing their queue entry.
            // No old observation gains permission to create or close a session.
            if event.siteFactVersion == nil {
                guard GpsObservationArchiveStoreV2.preserve(state, account: account, defaults: defaults),
                      diagnosticAccount == account,
                      GpsStateStoreV2.read(defaults.data(forKey: key)) == .valid(state) else { return false }
                var retired = state
                retired.pendingEvents.removeFirst()
                guard GpsStateStoreV2.write(retired, defaults: defaults, storageKey: key) else { return false }
                continue
            }
            guard event.siteFactVersion == 1 else { return false }
            guard let commit = GpsAutomaticPointageV2.plan(state: state, zones: zones,
                account: account, sessions: sessions,
                confirmedEmployerIds: Set(companies.companies.map(\.id)), now: now) else { return false }
            guard defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey),
                  GpsZoneConfigurationV2.read(defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)) == .valid(zones),
                  GpsStateStoreV2.read(defaults.data(forKey: key)) == .valid(state),
                  diagnosticAccount == account else { return false }
            if commit.sessions != sessions {
                guard persistCanonical(commit.sessions) else {
                    loadedStorageReliable = false
                    return false
                }
                loadedSessions = commit.sessions
                diagnostic(event.kind == .arrival ? .clockInCommitted : .clockOutCommitted,
                    at: event.occurredAt, sessionId: commit.sessionId, origin: .automatic)
            }
            guard diagnosticAccount == account,
                  GpsStateStoreV2.read(defaults.data(forKey: key)) == .valid(state),
                  GpsStateStoreV2.write(commit.state, defaults: defaults, storageKey: key) else { return false }
        }
        if case .valid(let state) = GpsStateStoreV2.read(defaults.data(forKey: key)) {
            return diagnosticAccount == account && state.pendingEvents.isEmpty
        }
        return false
    }

    /// Explicit classification of the exact facts displayed to the person. Dates, GPS
    /// provenance and pauses are preserved; stale UI/account snapshots cannot rewrite them.
    @discardableResult
    func qualifyGpsSession(_ expected: WorkSession, account: String, as kind: WorkSegmentKindV2) -> Bool {
        reloadAccount()
        guard kind == .work || kind == .nonWork,
              storageReliable, diagnosticAccount == account,
              let index = sessions.firstIndex(where: { $0 == expected }), expected.exit != nil,
              let segments = expected.segments, !segments.isEmpty,
              segments.allSatisfy({ $0.kind == .toConfirm && !$0.confirmed && $0.end != nil }) else { return false }
        var updated = sessions
        updated[index].segments = segments.map {
            WorkSegmentV2(start: $0.start, end: $0.end, kind: kind, confirmed: true)
        }
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            return false
        }
        loadedSessions = updated
        return true
    }

    @discardableResult
    func selectGpsArrival(_ event: GpsPendingEventV2, zoneId: UUID, account: String) -> Bool {
        reloadAccount()
        guard storageReliable, diagnosticAccount == account, event.kind == .arrival,
              event.siteFactVersion == 1, event.zoneIds.contains(zoneId),
              defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey),
              case .valid(let zones) = GpsZoneConfigurationV2.read(defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)),
              zones.contains(where: { $0.id == zoneId && $0.kind == .worksite }),
              var state = departureIndependentGpsState(account: account, zones: zones),
              state.pendingEvents.first == event else { return false }
        state.pendingEvents[0].selectedArrivalZoneId = zoneId
        guard diagnosticAccount == account,
              GpsStateStoreV2.write(state, defaults: defaults, storageKey: GpsStateStoreV2.scopedKey(account: account)) else { return false }
        return processAutomaticGpsPointage()
    }

    private func departureIndependentGpsState(account: String, zones: [GpsZoneV2]) -> GpsPersistedStateV2? {
        guard case .valid(let state) = GpsStateStoreV2.read(defaults.data(forKey: GpsStateStoreV2.scopedKey(account: account))),
              let fingerprint = GpsZoneConfigurationV2.fingerprint(enabled: true, zones: zones),
              GpsStateValidationV2.startupState(read: .valid(state), expectedFingerprint: fingerprint,
                configuredZoneIds: GpsZoneConfigurationV2.automaticZoneIds(zones),
                expectedAccountScope: account) == .resume(state) else { return nil }
        return state
    }

    private func persistCanonical(_ decoded: [WorkSession]) -> Bool {
        guard accountMatches, let loadedAccount else { return false }
        let committed = WorkSessionAccountStorageV2.write(decoded, account: loadedAccount, defaults: defaults)
        if !committed { loadedDiagnosticIssue = .writeFailed }
        return committed
    }

    func reloadAccount() {
        recoveryDiagnosticGeneration &+= 1
        let nextAccount = WorkSessionAccountStorageV2.scope(accountProvider())
        if nextAccount != loadedAccount { cachedGpsHistory = nil }
        loadedSessions = []
        loadedStorageReliable = false
        loadedDiagnosticIssue = .accountUnavailable
        loadedAccount = nextAccount
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
