import Foundation
import Combine

@MainActor
final class WorkStoreV2: ObservableObject {
    @Published private var loadedSessions: [WorkSession] = []
    @Published private var loadedStorageReliable = false
    private var loadedAccount: String?
    private let accountProvider: () -> String?

    // Never expose the previous account while the authentication notification is pending.
    var sessions: [WorkSession] { accountMatches ? loadedSessions : [] }
    var storageReliable: Bool { accountMatches && loadedStorageReliable }
    // The active journal can accept new facts while historical ownership remains unresolved.
    // Payroll must not treat that incomplete history as a verified zero.
    var payrollHistoryReliable: Bool { storageReliable && !hasUnassignedHistory }
    var hasUnassignedHistory: Bool {
        defaults.object(forKey: WorkSessionStorageV2.primaryKey) != nil ||
            defaults.object(forKey: WorkSessionStorageV2.legacyKey) != nil
    }
    private var accountMatches: Bool {
        loadedAccount != nil && loadedAccount == WorkSessionAccountStorageV2.scope(accountProvider())
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard, accountProvider: @escaping () -> String?) {
        self.defaults = defaults
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
        guard storageReliable, !isWorking else { return false }
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
            return false
        }
        loadedSessions = updated
        return true
    }

    @discardableResult
    func togglePause(paid: Bool? = nil, at date: Date = Date()) -> Bool {
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
        return true
    }

    @discardableResult
    func clockOut(
        at exitDate: Date = Date(),
        expectedSessionId: UUID? = nil
    ) -> Bool {
        guard storageReliable,
              exitDate <= Date().addingTimeInterval(300),
              let updated = WorkSessionMutationV2.closingSession(
                  in: sessions,
                  at: exitDate,
                  expectedSessionId: expectedSessionId
              ) else {
            return false
        }
        guard persistCanonical(updated) else {
            loadedStorageReliable = false
            return false
        }
        loadedSessions = updated
        return true
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
        return true
    }

    @discardableResult
    func addManualSession(
        entry: Date,
        exit: Date,
        employerId requestedEmployerId: String?,
        placeLabel: String?
    ) -> Bool {
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
        return WorkSessionAccountStorageV2.write(decoded, account: loadedAccount, defaults: defaults)
    }

    func reloadAccount() {
        loadedSessions = []
        loadedStorageReliable = false
        loadedAccount = WorkSessionAccountStorageV2.scope(accountProvider())
        guard let loadedAccount else { return }
        switch WorkSessionAccountStorageV2.read(account: loadedAccount, defaults: defaults) {
        case .missing:
            loadedStorageReliable = true
        case .valid(let decoded):
            loadedSessions = decoded
            loadedStorageReliable = true
        case .corrupt:
            break
        }
    }
}
