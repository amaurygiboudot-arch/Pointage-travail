import CoreFoundation
import Foundation

struct SalaryConventionRuleSnapshotV2: Equatable {
    let idcc: String
    let versionId: String
    let sourceId: String
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let rules: PayrollRulesV2
    let checkedAtMs: Int64
    let note: String?

    func applies(to epochDay: Int64) -> Bool {
        guard epochDay >= effectiveFromEpochDay else { return false }
        guard let end = effectiveToEpochDay else { return true }
        return epochDay <= end
    }
}

struct SalaryConventionRuleReadResultV2: Equatable {
    let snapshots: [SalaryConventionRuleSnapshotV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

/// Historique déterministe des règles conventionnelles confirmées.
/// Aucune règle plus récente n'est utilisée comme fallback pour une période historique.
struct SalaryConventionRuleHistoryV2 {
    private let versions: [String: [SalaryConventionRuleSnapshotV2]]

    init?(_ snapshots: [SalaryConventionRuleSnapshotV2]) {
        guard SalaryConventionRuleStoreV2.historyIsStructurallyValid(snapshots) else {
            return nil
        }
        versions = Dictionary(grouping: snapshots) {
            SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc)
        }.mapValues { items in
            items.sorted { $0.effectiveFromEpochDay > $1.effectiveFromEpochDay }
        }
    }

    func applicable(idcc: String?, epochDay: Int64) -> SalaryConventionRuleSnapshotV2? {
        guard let idcc else { return nil }
        let normalized = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        guard !normalized.isEmpty else { return nil }
        return versions[normalized]?.first { $0.applies(to: epochDay) }
    }

    func allVersions(idcc: String?) -> [SalaryConventionRuleSnapshotV2] {
        guard let idcc else { return [] }
        let normalized = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        guard !normalized.isEmpty else { return [] }
        return versions[normalized] ?? []
    }
}

/// Stockage iOS fail-closed des snapshots conventionnels confirmés.
///
/// Le schéma reprend `V2ConventionRuleStore` Android. Une observation officielle sans date
/// d'effet n'est pas enregistrée ici comme règle applicable. Un stockage illisible n'est jamais
/// transformé en historique vide fiable.
enum SalaryConventionRuleStoreV2 {
    static let storageWarning =
        "KALI heures supplémentaires : historique local des règles conventionnelles incohérent ; aucune règle ni absence ne peut être déduite de ce stockage."
    static let repairedWarning =
        "KALI heures supplémentaires : historique local restauré depuis la dernière copie valide."

    private static let primaryKey = "salary_convention_rules_v2.confirmed_snapshots"
    private static let backupKey = "salary_convention_rules_v2.confirmed_snapshots_last_known_good"
    private static let lock = NSLock()
    private static let maxExactJSONInteger = 9_007_199_254_740_991.0

    static func readConfirmed(defaults: UserDefaults = .standard) -> SalaryConventionRuleReadResultV2 {
        let primaryObject = defaults.object(forKey: primaryKey)
        let backupObject = defaults.object(forKey: backupKey)

        if primaryObject == nil {
            guard let backupObject else {
                return SalaryConventionRuleReadResultV2(
                    snapshots: [],
                    reliable: true,
                    repairedFromBackup: false,
                    warnings: []
                )
            }
            guard let backupRaw = backupObject as? String else { return unreliableResult() }
            let backup = decodeConfirmed(backupRaw)
            guard backup.reliable else { return unreliableResult(snapshots: backup.snapshots) }
            guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
                return SalaryConventionRuleReadResultV2(
                    snapshots: backup.snapshots,
                    reliable: false,
                    repairedFromBackup: false,
                    warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
                )
            }
            return SalaryConventionRuleReadResultV2(
                snapshots: backup.snapshots,
                reliable: true,
                repairedFromBackup: true,
                warnings: [repairedWarning]
            )
        }

        let primary = (primaryObject as? String).map(decodeConfirmed) ?? unreliableResult()
        if primary.reliable, let raw = primaryObject as? String {
            if defaults.string(forKey: backupKey) != raw {
                _ = writeVerified(raw, forKey: backupKey, defaults: defaults)
            }
            return primary
        }

        guard let backupRaw = backupObject as? String else { return primary }
        let backup = decodeConfirmed(backupRaw)
        guard backup.reliable else { return primary }
        guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
            return SalaryConventionRuleReadResultV2(
                snapshots: backup.snapshots,
                reliable: false,
                repairedFromBackup: false,
                warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
            )
        }
        return SalaryConventionRuleReadResultV2(
            snapshots: backup.snapshots,
            reliable: true,
            repairedFromBackup: true,
            warnings: [repairedWarning]
        )
    }

    static func history(from stored: SalaryConventionRuleReadResultV2) -> SalaryConventionRuleHistoryV2? {
        guard stored.reliable else { return nil }
        return SalaryConventionRuleHistoryV2(stored.snapshots)
    }

    @discardableResult
    static func saveConfirmed(
        _ snapshot: SalaryConventionRuleSnapshotV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard validSnapshotPayload(snapshot) else { return false }
        let candidate = normalizedSnapshot(snapshot)

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }

        var current = stored.snapshots.filter {
            !(normalizeIdcc($0.idcc) == candidate.idcc &&
              $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines) == candidate.versionId)
        }
        current.append(candidate)
        guard historyIsStructurallyValid(current), let raw = encodeConfirmed(current) else {
            return false
        }
        guard writeVerified(raw, forKey: primaryKey, defaults: defaults),
              writeVerified(raw, forKey: backupKey, defaults: defaults) else {
            return false
        }
        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && reloaded.snapshots.contains(candidate)
    }

    static func decodeConfirmed(_ raw: String) -> SalaryConventionRuleReadResultV2 {
        guard let data = raw.data(using: .utf8) else { return unreliableResult() }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [Any] else {
                return unreliableResult()
            }

            var snapshots: [SalaryConventionRuleSnapshotV2] = []
            var malformed = false
            for item in array {
                guard let object = item as? [String: Any],
                      let snapshot = decodeSnapshot(object),
                      validSnapshotPayload(snapshot) else {
                    malformed = true
                    continue
                }
                snapshots.append(normalizedSnapshot(snapshot))
            }
            if !historyIsStructurallyValid(snapshots) { malformed = true }

            return SalaryConventionRuleReadResultV2(
                snapshots: snapshots,
                reliable: !malformed,
                repairedFromBackup: false,
                warnings: malformed ? [storageWarning] : []
            )
        } catch {
            return unreliableResult()
        }
    }

    static func historyIsStructurallyValid(_ snapshots: [SalaryConventionRuleSnapshotV2]) -> Bool {
        guard snapshots.allSatisfy(validSnapshotPayload) else { return false }

        let versionKeys = snapshots.map {
            let version = $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines)
            return "\(normalizeIdcc($0.idcc))\u{0}\(version)"
        }
        guard Set(versionKeys).count == versionKeys.count else { return false }

        let grouped = Dictionary(grouping: snapshots) { normalizeIdcc($0.idcc) }
        for items in grouped.values {
            let ascending = items.sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
            guard ascending.count > 1 else { continue }
            for index in 1..<ascending.count {
                let previous = ascending[index - 1]
                let current = ascending[index]
                guard let previousEnd = previous.effectiveToEpochDay,
                      previousEnd < current.effectiveFromEpochDay else {
                    return false
                }
            }
        }
        return true
    }

    static func normalizeIdcc(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if trimmed.count >= 4 { return trimmed }
        return String(repeating: "0", count: 4 - trimmed.count) + trimmed
    }

    private static func validSnapshotPayload(_ snapshot: SalaryConventionRuleSnapshotV2) -> Bool {
        guard !normalizeIdcc(snapshot.idcc).isEmpty,
              !snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              snapshot.checkedAtMs >= 0 else {
            return false
        }
        if let end = snapshot.effectiveToEpochDay, end < snapshot.effectiveFromEpochDay {
            return false
        }
        if let weekly = snapshot.rules.weeklyRegularMinutes, weekly <= 0 { return false }

        let multipliers = [
            snapshot.rules.nightMultiplier,
            snapshot.rules.saturdayMultiplier,
            snapshot.rules.sundayMultiplier,
            snapshot.rules.publicHolidayMultiplier
        ].compactMap { $0 }
        guard multipliers.allSatisfy({ $0.isFinite && $0 >= 1.0 }) else { return false }

        return snapshot.rules.overtimeTiers.allSatisfy { tier in
            guard tier.fromMinutes >= 0,
                  tier.multiplier.isFinite,
                  tier.multiplier >= 1.0 else {
                return false
            }
            if let to = tier.toMinutes, to <= tier.fromMinutes { return false }
            return true
        }
    }

    private static func normalizedSnapshot(_ snapshot: SalaryConventionRuleSnapshotV2) -> SalaryConventionRuleSnapshotV2 {
        SalaryConventionRuleSnapshotV2(
            idcc: normalizeIdcc(snapshot.idcc),
            versionId: snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            sourceId: snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            effectiveFromEpochDay: snapshot.effectiveFromEpochDay,
            effectiveToEpochDay: snapshot.effectiveToEpochDay,
            rules: snapshot.rules,
            checkedAtMs: snapshot.checkedAtMs,
            note: snapshot.note?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }

    private static func encodeConfirmed(_ snapshots: [SalaryConventionRuleSnapshotV2]) -> String? {
        let sorted = snapshots.sorted {
            let left = normalizeIdcc($0.idcc)
            let right = normalizeIdcc($1.idcc)
            return left == right
                ? $0.effectiveFromEpochDay < $1.effectiveFromEpochDay
                : left < right
        }
        let array: [[String: Any]] = sorted.map { snapshot in
            let tiers = snapshot.rules.overtimeTiers.map { tier -> [String: Any] in
                [
                    "fromMinutes": tier.fromMinutes,
                    "toMinutes": jsonValue(tier.toMinutes),
                    "multiplier": tier.multiplier
                ]
            }
            return [
                "idcc": normalizeIdcc(snapshot.idcc),
                "versionId": snapshot.versionId,
                "sourceId": snapshot.sourceId,
                "effectiveFromEpochDay": snapshot.effectiveFromEpochDay,
                "effectiveToEpochDay": jsonValue(snapshot.effectiveToEpochDay),
                "checkedAtMs": snapshot.checkedAtMs,
                "note": jsonValue(snapshot.note),
                "rules": [
                    "weeklyRegularMinutes": jsonValue(snapshot.rules.weeklyRegularMinutes),
                    "nightMultiplier": jsonValue(snapshot.rules.nightMultiplier),
                    "saturdayMultiplier": jsonValue(snapshot.rules.saturdayMultiplier),
                    "sundayMultiplier": jsonValue(snapshot.rules.sundayMultiplier),
                    "publicHolidayMultiplier": jsonValue(snapshot.rules.publicHolidayMultiplier),
                    "overtimeTiers": tiers
                ]
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func decodeSnapshot(_ object: [String: Any]) -> SalaryConventionRuleSnapshotV2? {
        guard let idccRaw = object["idcc"] as? String,
              let versionRaw = object["versionId"] as? String,
              let sourceRaw = object["sourceId"] as? String,
              let effectiveFrom = strictInt64(object["effectiveFromEpochDay"]),
              let checkedAt = strictInt64(object["checkedAtMs"]),
              let rulesObject = object["rules"] as? [String: Any],
              let effectiveTo = optionalInt64(object["effectiveToEpochDay"]),
              let note = optionalString(object["note"]),
              let weekly = optionalInt(rulesObject["weeklyRegularMinutes"]),
              let night = optionalDouble(rulesObject["nightMultiplier"]),
              let saturday = optionalDouble(rulesObject["saturdayMultiplier"]),
              let sunday = optionalDouble(rulesObject["sundayMultiplier"]),
              let publicHoliday = optionalDouble(rulesObject["publicHolidayMultiplier"]),
              let tiers = decodeTiers(rulesObject["overtimeTiers"]) else {
            return nil
        }

        return SalaryConventionRuleSnapshotV2(
            idcc: idccRaw,
            versionId: versionRaw,
            sourceId: sourceRaw,
            effectiveFromEpochDay: effectiveFrom,
            effectiveToEpochDay: effectiveTo,
            rules: PayrollRulesV2(
                weeklyRegularMinutes: weekly,
                overtimeTiers: tiers,
                nightMultiplier: night,
                saturdayMultiplier: saturday,
                sundayMultiplier: sunday,
                publicHolidayMultiplier: publicHoliday
            ),
            checkedAtMs: checkedAt,
            note: note
        )
    }

    private static func decodeTiers(_ raw: Any?) -> [OvertimeTierV2]? {
        if raw == nil || raw is NSNull { return [] }
        guard let array = raw as? [Any] else { return nil }
        var tiers: [OvertimeTierV2] = []
        for item in array {
            guard let object = item as? [String: Any],
                  let from = strictInt(object["fromMinutes"]),
                  let to = optionalInt(object["toMinutes"]),
                  let multiplier = strictDouble(object["multiplier"]) else {
                return nil
            }
            tiers.append(OvertimeTierV2(fromMinutes: from, toMinutes: to, multiplier: multiplier))
        }
        return tiers
    }

    private static func strictInt(_ raw: Any?) -> Int? {
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            return nil
        }
        let value = number.doubleValue
        guard value.isFinite,
              value.rounded() == value,
              abs(value) <= maxExactJSONInteger else {
            return nil
        }
        let int64 = Int64(value)
        return Int(exactly: int64)
    }

    private static func strictInt64(_ raw: Any?) -> Int64? {
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            return nil
        }
        let value = number.doubleValue
        guard value.isFinite,
              value.rounded() == value,
              abs(value) <= maxExactJSONInteger else {
            return nil
        }
        return Int64(value)
    }

    private static func strictDouble(_ raw: Any?) -> Double? {
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            return nil
        }
        let value = number.doubleValue
        return value.isFinite ? value : nil
    }

    private static func isBooleanNumber(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func optionalInt(_ raw: Any?) -> Int?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = strictInt(raw) else { return nil }
        return .some(value)
    }

    private static func optionalInt64(_ raw: Any?) -> Int64?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = strictInt64(raw) else { return nil }
        return .some(value)
    }

    private static func optionalDouble(_ raw: Any?) -> Double?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = strictDouble(raw) else { return nil }
        return .some(value)
    }

    private static func optionalString(_ raw: Any?) -> String?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return .some(trimmed.isEmpty ? nil : trimmed)
    }

    private static func jsonValue<T>(_ value: T?) -> Any {
        if let value { return value }
        return NSNull()
    }

    private static func writeVerified(_ value: String, forKey key: String, defaults: UserDefaults) -> Bool {
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value
    }

    private static func unreliableResult(
        snapshots: [SalaryConventionRuleSnapshotV2] = []
    ) -> SalaryConventionRuleReadResultV2 {
        SalaryConventionRuleReadResultV2(
            snapshots: snapshots,
            reliable: false,
            repairedFromBackup: false,
            warnings: [storageWarning]
        )
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
