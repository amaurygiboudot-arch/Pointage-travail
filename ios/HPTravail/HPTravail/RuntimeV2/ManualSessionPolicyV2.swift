import Foundation

struct ManualSessionRangeV2: Equatable {
    let entry: Date
    let exit: Date
}

/** Règles pures de saisie manuelle partagées par l'interface et les tests iOS. */
enum ManualSessionPolicyV2 {
    static func normalizedRange(
        day: Date,
        startTime: Date,
        endTime: Date,
        calendar: Calendar = .current
    ) -> ManualSessionRangeV2? {
        func time(on day: Date, from value: Date) -> Date? {
            let clock = calendar.dateComponents([.hour, .minute], from: value)
            return calendar.date(
                bySettingHour: clock.hour ?? 0,
                minute: clock.minute ?? 0,
                second: 0,
                of: day
            )
        }

        guard let entry = time(on: day, from: startTime),
              let rawExit = time(on: day, from: endTime),
              rawExit != entry else {
            return nil
        }
        let exit = rawExit > entry
            ? rawExit
            : calendar.date(byAdding: .day, value: 1, to: rawExit)
        guard let exit, exit > entry else { return nil }
        return ManualSessionRangeV2(entry: entry, exit: exit)
    }

    static func appending(
        to sessions: [WorkSession],
        entry: Date,
        exit: Date,
        employerId: String?,
        placeLabel: String?
    ) -> [WorkSession]? {
        guard entry.timeIntervalSince1970 > 0, exit > entry else { return nil }
        let employerCandidate = employerId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let employer = employerCandidate?.isEmpty == false ? employerCandidate : nil
        let placeCandidate = placeLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let place = placeCandidate?.isEmpty == false ? placeCandidate : nil
        guard !sessions.contains(where: {
            $0.entry == entry && $0.exit == exit && $0.employerId == employer
        }) else {
            return nil
        }

        let updated = sessions + [WorkSession(
            id: UUID(),
            entry: entry,
            exit: exit,
            pauses: [],
            employerId: employer,
            placeLabel: place
        )]
        return WorkSessionPersistenceV2.isStructurallyValid(updated) ? updated : nil
    }
}
