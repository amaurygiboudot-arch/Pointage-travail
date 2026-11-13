import Foundation
import CryptoKit

/// Identity is checked at use, not only when a screen is presented. Firebase installs
/// the provider and calls authenticationChanged before publishing a new UI identity.
enum SalaryAccountSessionV2 {
    struct Lease: Equatable {
        let scope: String?
        let generation: UInt64
        var isCurrent: Bool { SalaryAccountSessionV2.isCurrent(self) }
        var authenticated: Bool { scope?.hasPrefix("uid:") == true && isCurrent }
    }
    private final class PinnedLease: NSObject {
        let lease: Lease
        init(_ lease: Lease) { self.lease = lease }
    }
    final class Operation {
        private let previous: Any?
        private var closed = false
        fileprivate init(_ lease: Lease) {
            previous = Thread.current.threadDictionary["agkgmg.salary.operation"]
            Thread.current.threadDictionary["agkgmg.salary.operation"] = PinnedLease(lease)
        }
        func close() {
            guard !closed else { return }
            closed = true
            if let previous { Thread.current.threadDictionary["agkgmg.salary.operation"] = previous }
            else { Thread.current.threadDictionary.removeObject(forKey: "agkgmg.salary.operation") }
        }
    }
    static func pin(_ lease: Lease) -> Operation { Operation(lease) }
    static var storageLease: Lease {
        (Thread.current.threadDictionary["agkgmg.salary.operation"] as? PinnedLease)?.lease ?? capture()
    }
    private static let lock = NSRecursiveLock()
    private static var provider: () -> String? = { nil }
    private static var observedScope: String?
    private static var generation: UInt64 = 0

    static func configure(provider: @escaping () -> String?) {
        lock.lock(); defer { lock.unlock() }
        self.provider = provider
        observedScope = provider()
        generation &+= 1
    }
    static func authenticationChanged(deliveredScope: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        // Observe callback identities before they are queued onto the main actor. An
        // A→B→A transition may already be complete by the time the UI runs its callback.
        if let deliveredScope, deliveredScope != observedScope {
            observedScope = deliveredScope
            generation &+= 1
        }
        observeIdentity()
    }
    static func invalidateSession() {
        lock.lock(); defer { lock.unlock() }
        observedScope = provider()
        generation &+= 1
    }
    private static func observeIdentity() {
        let scope = provider()
        if scope != observedScope {
            observedScope = scope
            generation &+= 1
        }
    }
    static func capture() -> Lease {
        lock.lock(); defer { lock.unlock() }
        observeIdentity()
        return Lease(scope: observedScope, generation: generation)
    }
    static func isCurrent(_ lease: Lease) -> Bool {
        lock.lock(); defer { lock.unlock() }
        observeIdentity()
        return lease.scope != nil && lease.scope == observedScope && lease.generation == generation
    }
}

/// A single property-list value contains owner envelopes and legacy attribution receipts.
/// Replacing this value never leaves an imported account without its attribution receipt.
/// UserDefaults read-back/synchronize is checked; this does not promise physical-media durability.
enum SalaryAccountStorageV2 {
    static let warning = "Salaire : compte modifié, identité indisponible ou historique non attribué. Vérifiez le compte et la récupération avant de continuer."
    static let rootKey = "agkgmg.salary.accounts.v1"
    private static let lock = NSRecursiveLock()
    private static let personalPrefixes = [
        "salary_companies_v2.", "salary_employment_contracts_v2.",
        "salary_employment_contract_history_v2.", "income_tax_rates_v2.",
        "salary_company_", "employee_deductions_v2.", "salary_absences_v2.", "salary_employee_social_profile_v2.",
        "salary_convention_classification_v2.", "salary_convention_seniority_input_v2.",
        "salary_segmented_proration_v2.", "salary_confirmed_sickness_cash_v2.",
        "salary_monthly_payroll_settlement_v2.",
        "salary_payslip_observed_v2.", "salary_payroll_source_knowledge_v2."
    ]
    private static func isSharedKey(_ key: String) -> Bool {
        ["salary_convention_rules_v2.", "salary_convention_night_rules_v2.",
         "salary_convention_seniority_premium_v2.", "salary_convention_protection_category_v2.",
         "salary_convention_matter_coverage_v2."].contains { key.hasPrefix($0) }
    }
    private static func ownedDatedRule(_ value: Any?, scope: String?) -> Bool {
        guard let scope, scope.hasPrefix("uid:"), let raw = value as? String,
              let data = raw.data(using: .utf8),
              let fields = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return fields["accountId"] as? String == String(scope.dropFirst(4))
    }
    static func isPersonalKey(_ key: String) -> Bool {
        key == "hp_travail_payroll_coverage_v2" || personalPrefixes.contains { key.hasPrefix($0) }
    }
    static var currentDefaults: UserDefaults { guarded(backing: .standard, lease: SalaryAccountSessionV2.storageLease) }
    /// Nonstandard suites remain explicit dependency-injection domains for existing store tests.
    /// Passing .standard explicitly is never an escape from the production account boundary.
    static func scoped(_ defaults: UserDefaults) -> UserDefaults {
        defaults === UserDefaults.standard ? currentDefaults : defaults
    }
    static func guarded(backing: UserDefaults, lease: SalaryAccountSessionV2.Lease = SalaryAccountSessionV2.capture()) -> UserDefaults {
        GuardedDefaults(backing: backing, lease: lease)
    }
    static func available(_ defaults: UserDefaults) -> Bool {
        if let guarded = scoped(defaults) as? GuardedDefaults { return guarded.available }
        return true
    }
    /// Revalidate after parsing/repair as well as before reading. A payload captured
    /// just before an authentication callback must not escape as a reliable result.
    static func readConsistently<T>(_ defaults: UserDefaults, unavailable: T, _ read: () -> T) -> T {
        guard available(defaults) else { return unavailable }
        let result = read()
        return available(defaults) ? result : unavailable
    }
    static func accountKey(_ scope: String) -> String { digest(Data(scope.utf8)) }
    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func root(_ backing: UserDefaults) -> [String: Any]? {
        guard let raw = backing.object(forKey: rootKey) else {
            return ["version": 1, "accounts": [String: Any](), "claims": [String: String]()]
        }
        guard let data = raw as? Data,
              let value = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              value["version"] as? Int == 1,
              value["accounts"] is [String: Any], value["claims"] is [String: String] else { return nil }
        return value
    }
    private static func legacy(_ backing: UserDefaults) -> [String: Any] {
        backing.dictionaryRepresentation().filter { isPersonalKey($0.key) }
    }
    private static func fingerprint(_ values: [String: Any]) -> String? {
        // XML serialization of NSDictionary is sorted by Foundation, but explicitly encode
        // sorted key/value pairs so a dictionary's enumeration order cannot change a receipt.
        let pairs: [[Any]] = values.keys.sorted().map { [$0, values[$0]!] }
        guard let data = try? PropertyListSerialization.data(fromPropertyList: pairs, format: .binary, options: 0) else { return nil }
        return digest(data)
    }
    private static func envelope(_ root: [String: Any], scope: String) -> [String: Any]? {
        guard let accounts = root["accounts"] as? [String: Any] else { return nil }
        guard let object = accounts[accountKey(scope)] else { return ["owner": scope, "values": [String: Any]()] }
        guard let value = object as? [String: Any], value["owner"] as? String == scope,
              let entries = value["values"] as? [String: Any], entries.keys.allSatisfy(isPersonalKey) else { return nil }
        return value
    }
    private static func legacyPending(_ root: [String: Any], envelope: [String: Any], backing: UserDefaults) -> Bool {
        if envelope["legacyReviewed"] as? Bool == true { return false }
        let old = legacy(backing)
        guard !old.isEmpty else { return false }
        guard let stamp = fingerprint(old), let claims = root["claims"] as? [String: String] else { return true }
        return claims[stamp] == nil
    }
    private static func persist(_ value: [String: Any], backing: UserDefaults, lease: SalaryAccountSessionV2.Lease) -> Bool {
        guard lease.isCurrent,
              let data = try? PropertyListSerialization.data(fromPropertyList: value, format: .binary, options: 0),
              lease.isCurrent else { return false }
        backing.set(data, forKey: rootKey)
        guard backing.synchronize(), lease.isCurrent, backing.data(forKey: rootKey) == data else { return false }
        return true
    }

    struct RecoveryPreview: Equatable {
        let lease: SalaryAccountSessionV2.Lease
        let fingerprint: String
        let entryCount: Int
    }
    enum RecoveryError: LocalizedError {
        case unavailable, sourceChanged, destinationNotEmpty, alreadyClaimed, writeFailed
        var errorDescription: String? {
            switch self {
            case .unavailable: return warning
            case .sourceChanged: return "L’ancien historique a changé. Recommencez la vérification."
            case .destinationNotEmpty: return "Ce compte contient déjà des données salariales. Aucun remplacement automatique n’est autorisé."
            case .alreadyClaimed: return "Cet historique a déjà été attribué à un autre compte. Les originaux restent conservés."
            case .writeFailed: return "La récupération n’a pas pu être vérifiée. Les originaux restent conservés ; réessayez avec le même compte."
            }
        }
    }
    static func recoveryPreview(backing: UserDefaults = .standard) throws -> RecoveryPreview? {
        lock.lock(); defer { lock.unlock() }
        let lease = SalaryAccountSessionV2.capture()
        guard lease.authenticated, let scope = lease.scope, let root = root(backing),
              let envelope = envelope(root, scope: scope) else { throw RecoveryError.unavailable }
        guard legacyPending(root, envelope: envelope, backing: backing) else { return nil }
        let values = legacy(backing)
        guard let stamp = fingerprint(values), lease.isCurrent else { throw RecoveryError.unavailable }
        return RecoveryPreview(lease: lease, fingerprint: stamp, entryCount: values.count)
    }
    static func recover(_ preview: RecoveryPreview, ownershipConfirmed: Bool, backing: UserDefaults = .standard) throws {
        lock.lock(); defer { lock.unlock() }
        guard ownershipConfirmed, preview.lease.authenticated, let scope = preview.lease.scope,
              var root = root(backing), var envelope = envelope(root, scope: scope),
              var accounts = root["accounts"] as? [String: Any], var claims = root["claims"] as? [String: String] else { throw RecoveryError.unavailable }
        if let previous = claims[preview.fingerprint] {
            guard previous == scope, envelope["recoveryFingerprint"] as? String == preview.fingerprint else { throw RecoveryError.alreadyClaimed }
            guard backing.synchronize(), preview.lease.isCurrent else { throw RecoveryError.writeFailed }
            return
        }
        let old = legacy(backing)
        guard !old.isEmpty, fingerprint(old) == preview.fingerprint, old.count == preview.entryCount else { throw RecoveryError.sourceChanged }
        guard let entries = envelope["values"] as? [String: Any], entries.isEmpty else { throw RecoveryError.destinationNotEmpty }
        envelope["values"] = old
        envelope["legacyReviewed"] = true
        envelope["recoveryFingerprint"] = preview.fingerprint
        envelope["recoveredAt"] = Date()
        accounts[accountKey(scope)] = envelope
        claims[preview.fingerprint] = scope
        root["accounts"] = accounts
        root["claims"] = claims
        guard persist(root, backing: backing, lease: preview.lease) else { throw RecoveryError.writeFailed }
    }
    static func startWithoutLegacy(_ preview: RecoveryPreview, backing: UserDefaults = .standard) throws {
        lock.lock(); defer { lock.unlock() }
        guard preview.lease.authenticated, let scope = preview.lease.scope,
              var root = root(backing), var envelope = envelope(root, scope: scope),
              var accounts = root["accounts"] as? [String: Any] else { throw RecoveryError.unavailable }
        guard fingerprint(legacy(backing)) == preview.fingerprint else { throw RecoveryError.sourceChanged }
        envelope["legacyReviewed"] = true
        accounts[accountKey(scope)] = envelope
        root["accounts"] = accounts
        guard persist(root, backing: backing, lease: preview.lease) else { throw RecoveryError.writeFailed }
    }

    private final class GuardedDefaults: UserDefaults {
        let backing: UserDefaults
        let lease: SalaryAccountSessionV2.Lease
        private var writeFailed = false
        init(backing: UserDefaults, lease: SalaryAccountSessionV2.Lease) {
            self.backing = backing; self.lease = lease
            super.init(suiteName: nil)!
        }
        var available: Bool {
            lock.lock(); defer { lock.unlock() }
            guard !writeFailed, lease.isCurrent, let scope = lease.scope, let root = root(backing),
                  let envelope = envelope(root, scope: scope), !legacyPending(root, envelope: envelope, backing: backing) else { return false }
            return lease.isCurrent
        }
        override func object(forKey key: String) -> Any? {
            lock.lock(); defer { lock.unlock() }
            guard !writeFailed, lease.isCurrent else { return NSNull() }
            if !isPersonalKey(key) {
                let value = backing.object(forKey: key)
                if isSharedKey(key) { return value }
                if key.hasPrefix("dated_work_applicability_v2.owner:") {
                    return value == nil || ownedDatedRule(value, scope: lease.scope) ? value : NSNull()
                }
                return nil
            }
            guard let scope = lease.scope, let root = root(backing), let envelope = envelope(root, scope: scope),
                  !legacyPending(root, envelope: envelope, backing: backing), let values = envelope["values"] as? [String: Any] else { return NSNull() }
            let value = values[key]
            return lease.isCurrent ? value : NSNull()
        }
        override func string(forKey key: String) -> String? {
            let value = object(forKey: key)
            return value is NSNull ? "\0account-unavailable" : value as? String
        }
        override func data(forKey key: String) -> Data? {
            let value = object(forKey: key)
            return value is NSNull ? Data([0xff]) : value as? Data
        }
        override func array(forKey key: String) -> [Any]? { object(forKey: key) as? [Any] }
        override func dictionary(forKey key: String) -> [String: Any]? { object(forKey: key) as? [String: Any] }
        override func stringArray(forKey key: String) -> [String]? { object(forKey: key) as? [String] }
        override func bool(forKey key: String) -> Bool { object(forKey: key) as? Bool ?? false }
        override func integer(forKey key: String) -> Int { object(forKey: key) as? Int ?? 0 }
        override func double(forKey key: String) -> Double { object(forKey: key) as? Double ?? 0 }
        override func float(forKey key: String) -> Float { object(forKey: key) as? Float ?? 0 }
        override func url(forKey key: String) -> URL? {
            if let url = object(forKey: key) as? URL { return url }
            return (object(forKey: key) as? String).flatMap(URL.init(string:))
        }
        override func set(_ value: Any?, forKey key: String) {
            lock.lock(); defer { lock.unlock() }
            guard !writeFailed, lease.isCurrent, key != rootKey else { return }
            guard isPersonalKey(key) else {
                if isSharedKey(key) || (key.hasPrefix("dated_work_applicability_v2.owner:") && ownedDatedRule(value, scope: lease.scope)) {
                    backing.set(value, forKey: key)
                }
                return
            }
            guard let scope = lease.scope, var root = root(backing), var envelope = envelope(root, scope: scope),
                  !legacyPending(root, envelope: envelope, backing: backing),
                  var values = envelope["values"] as? [String: Any], var accounts = root["accounts"] as? [String: Any] else { return }
            values[key] = value
            envelope["values"] = values
            accounts[accountKey(scope)] = envelope
            root["accounts"] = accounts
            writeFailed = !persist(root, backing: backing, lease: lease)
        }
        override func set(_ value: Bool, forKey key: String) { set(value as Any, forKey: key) }
        override func set(_ value: Int, forKey key: String) { set(value as Any, forKey: key) }
        override func set(_ value: Float, forKey key: String) { set(value as Any, forKey: key) }
        override func set(_ value: Double, forKey key: String) { set(value as Any, forKey: key) }
        override func set(_ url: URL?, forKey key: String) { set(url?.absoluteString as Any?, forKey: key) }
        override func removeObject(forKey key: String) { set(nil, forKey: key) }
        override func dictionaryRepresentation() -> [String: Any] {
            lock.lock(); defer { lock.unlock() }
            guard available, let scope = lease.scope, let root = root(backing), let envelope = envelope(root, scope: scope),
                  let values = envelope["values"] as? [String: Any] else { return [:] }
            var result = backing.dictionaryRepresentation().filter {
                isSharedKey($0.key) || ($0.key.hasPrefix("dated_work_applicability_v2.owner:") && ownedDatedRule($0.value, scope: lease.scope))
            }
            result.merge(values) { _, scoped in scoped }
            return lease.isCurrent ? result : [:]
        }
        override func persistentDomain(forName domainName: String) -> [String: Any]? { dictionaryRepresentation() }
        override func volatileDomain(forName domainName: String) -> [String: Any] { dictionaryRepresentation() }
        override func setPersistentDomain(_ domain: [String: Any], forName domainName: String) { /* Only per-key owner-checked writes. */ }
        override func removePersistentDomain(forName domainName: String) { /* Never clear another account. */ }
        override func setVolatileDomain(_ domain: [String: Any], forName domainName: String) {}
        override func removeVolatileDomain(forName domainName: String) {}
        override func register(defaults registrationDictionary: [String: Any]) {}
        override func addSuite(named suiteName: String) {}
        override func removeSuite(named suiteName: String) {}
        override func synchronize() -> Bool { available && backing.synchronize() && lease.isCurrent }
    }
}
