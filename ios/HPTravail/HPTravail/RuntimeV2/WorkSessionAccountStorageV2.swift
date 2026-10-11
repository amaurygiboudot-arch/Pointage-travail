import Foundation

/// Account-scoped journals never claim ownership of historical device-wide data.
/// The old V1/V2 keys remain intact for an explicit, verified recovery procedure.
enum WorkSessionAccountStorageV2 {
    static func authenticationScope(available: Bool, userId: String?) -> String? {
        guard available else { return nil }
        guard let userId else { return "guest" }
        let uid = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty else { return nil }
        return "uid:" + uid
    }

    static func scope(_ account: String?) -> String? {
        guard let account, !account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return account
    }

    static func key(account: String) -> String? {
        guard let account = scope(account) else { return nil }
        return "hp_travail_sessions_account_v2." + Data(account.utf8).base64EncodedString()
    }

    static func read(account: String, defaults: UserDefaults) -> WorkSessionsReadV2 {
        guard let key = key(account: account) else { return .corrupt }
        guard defaults.object(forKey: key) != nil else { return .missing }
        guard let data = defaults.data(forKey: key) else { return .corrupt }
        return WorkSessionPersistenceV2.read(data)
    }

    static func write(_ sessions: [WorkSession], account: String, defaults: UserDefaults) -> Bool {
        guard let key = key(account: account),
              WorkSessionPersistenceV2.isStructurallyValid(sessions),
              let data = try? JSONEncoder().encode(sessions),
              WorkSessionPersistenceV2.read(data) == .valid(sessions) else { return false }
        defaults.set(data, forKey: key)
        return read(account: account, defaults: defaults) == .valid(sessions)
    }
}
