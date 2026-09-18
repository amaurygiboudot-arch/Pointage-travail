import Foundation

@MainActor
final class WorkStoreV2: ObservableObject {
    @Published private(set) var sessions: [WorkSession] = []
    @Published private(set) var storageReliable = true

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
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

    func clockIn() {
        guard storageReliable, !isWorking else { return }
        sessions.append(WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: []))
        save()
    }

    func togglePause(paid: Bool? = nil) {
        guard storageReliable else { return }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            guard let resolvedPaid = sessions[index].pauses[pauseIndex].paid ?? paid else { return }
            sessions[index].pauses[pauseIndex].paid = resolvedPaid
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            guard let paid else { return }
            sessions[index].pauses.append(
                PausePeriod(id: UUID(), start: Date(), end: nil, paid: paid)
            )
        }
        save()
    }

    func clockOut() {
        guard storageReliable else { return }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        guard sessions[index].pauses.allSatisfy({ $0.paid != nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        }
        sessions[index].exit = Date()
        save()
    }

    @discardableResult
    func addManualSession(
        entry: Date,
        exit: Date,
        employerId: String?,
        placeLabel: String?
    ) -> Bool {
        guard storageReliable,
              let updated = ManualSessionPolicyV2.appending(
                to: sessions,
                entry: entry,
                exit: exit,
                employerId: employerId,
                placeLabel: placeLabel
              ) else {
            return false
        }
        sessions = updated
        save()
        return storageReliable
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

    private func save() {
        guard storageReliable, persistCanonical(sessions) else {
            storageReliable = false
            return
        }
    }

    private func persistCanonical(_ decoded: [WorkSession]) -> Bool {
        guard let data = try? JSONEncoder().encode(decoded) else { return false }

        defaults.set(data, forKey: WorkSessionStorageV2.primaryKey)
        return WorkSessionPersistenceV2.read(
            defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        ) == .valid(decoded)
    }

    private func load() {
        let primaryData = defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        let legacyData = defaults.data(forKey: WorkSessionStorageV2.legacyKey)
        let paidRepairAlreadyHandled = defaults.bool(forKey: WorkSessionStorageV2.paidRepairMarkerKey)

        let resolution = WorkSessionStorageV2.resolve(
            primaryData: primaryData,
            legacyData: legacyData,
            allowTransitionalPrimaryRepair: !paidRepairAlreadyHandled
        )

        // La compatibilité paid=nil n'est autorisée qu'une seule fois sur une installation existante.
        // Le marqueur est posé même si aucune réparation n'était nécessaire, afin qu'une corruption
        // future ne puisse jamais réactiver ce chemin historique.
        if !paidRepairAlreadyHandled {
            defaults.set(true, forKey: WorkSessionStorageV2.paidRepairMarkerKey)
        }

        switch resolution {
        case .missing:
            sessions = []
            storageReliable = true

        case .valid(let decoded, let origin):
            switch origin {
            case .primary:
                sessions = decoded
                storageReliable = true

            case .legacyMigration, .transitionalPrimaryRepair:
                guard persistCanonical(decoded) else {
                    sessions = []
                    storageReliable = false
                    return
                }

                if origin == .legacyMigration {
                    defaults.removeObject(forKey: WorkSessionStorageV2.legacyKey)
                }

                sessions = decoded
                storageReliable = true
            }

        case .corrupt:
            sessions = []
            storageReliable = false
        }
    }
}
