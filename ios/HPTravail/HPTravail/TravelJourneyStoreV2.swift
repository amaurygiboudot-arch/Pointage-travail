import Foundation
import FirebaseAuth
import FirebaseCore

/// Separate travel evidence; never modifies WorkStoreV2 or salary calculations.
enum TravelJourneyStoreV2 {
    private static let lock = NSRecursiveLock()

    static func currentAccount() -> String? {
        guard FirebaseApp.app() != nil else { return nil }
        return WorkSessionAccountStorageV2.authenticationScope(
            available: true, userId: Auth.auth().currentUser?.uid
        )
    }

    private static func key(_ account: String) -> String? {
        guard account == "guest" || (account.hasPrefix("uid:") && account.count > 4) else { return nil }
        return "hp_travel_journeys_v2." + Data(account.utf8).base64EncodedString()
    }

    static func read(account: String, defaults: UserDefaults = .standard) -> [TravelJourneyV2]? {
        lock.lock(); defer { lock.unlock() }
        guard let key = key(account) else { return nil }
        guard let object = defaults.object(forKey: key) else { return [] }
        guard let data = object as? Data else { return nil }
        return TravelJourneyCodecV2.decode(account: account, data: data)
    }

    private static func save(_ trips: [TravelJourneyV2], account: String,
                             defaults: UserDefaults) -> Bool {
        guard let key = key(account),
              let data = TravelJourneyCodecV2.encode(account: account, trips: trips) else { return false }
        defaults.set(data, forKey: key)
        return read(account: account, defaults: defaults) == trips
    }

    @discardableResult
    static func begin(account: String, at date: Date, employerId: String?,
                      defaults: UserDefaults = .standard) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let existing = read(account: account, defaults: defaults),
              date.timeIntervalSince1970 > 0, existing.count < 10_000,
              !existing.contains(where: { $0.endedAt == nil }) else { return nil }
        let id = UUID().uuidString
        let employer = employerId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trip = TravelJourneyV2(
            id: id, startedAt: date, endedAt: nil,
            employerId: employer?.isEmpty == false ? employer : nil,
            departure: nil, arrival: nil
        )
        return save(existing + [trip], account: account, defaults: defaults) ? id : nil
    }

    @discardableResult
    static func finish(account: String, at date: Date,
                       defaults: UserDefaults = .standard) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard var trips = read(account: account, defaults: defaults),
              let index = trips.firstIndex(where: { $0.endedAt == nil }),
              date > trips[index].startedAt else { return nil }
        let id = trips[index].id
        trips[index].endedAt = date
        return save(trips, account: account, defaults: defaults) ? id : nil
    }

    static func attachFix(account: String, id: String, departure: Bool,
                          fix: TravelFixV2, defaults: UserDefaults = .standard) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard var trips = read(account: account, defaults: defaults),
              let index = trips.firstIndex(where: { $0.id == id }),
              fix.isUsable else { return false }
        let actionAt: Date
        if departure {
            guard trips[index].departure == nil else { return false }
            actionAt = trips[index].startedAt
        } else {
            guard trips[index].arrival == nil,
                  let arrivedAt = trips[index].endedAt else { return false }
            actionAt = arrivedAt
        }
        guard abs(fix.observedAt.timeIntervalSince(actionAt)) <= 180 else { return false }
        if departure { trips[index].departure = fix }
        else { trips[index].arrival = fix }
        return save(trips, account: account, defaults: defaults)
    }
}
