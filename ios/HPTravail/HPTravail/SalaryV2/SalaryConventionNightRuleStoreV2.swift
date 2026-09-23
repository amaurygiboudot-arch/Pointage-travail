import CoreFoundation
import Foundation

struct SalaryConventionNightRuleSnapshotV2: Equatable {
    let idcc: String
    let versionId: String
    let sourceId: String
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let rule: NightPremiumRuleV2
    let checkedAtMs: Int64
    let note: String?

    func applies(to epochDay: Int64) -> Bool {
        guard epochDay >= effectiveFromEpochDay else { return false }
        guard let end = effectiveToEpochDay else { return true }
        return epochDay <= end
    }
}

struct SalaryConventionNightRuleReadResultV2: Equatable {
    let snapshots: [SalaryConventionNightRuleSnapshotV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

struct SalaryConventionNightRulePeriodResolutionV2: Equatable {
    let rule: NightPremiumRuleV2?
    let reliable: Bool
    let warnings: [String]
    let sourceIds: [String]
}

/// Historique déterministe des plages conventionnelles de nuit confirmées.
///
/// Une version plus récente n'est jamais utilisée comme fallback pour une période historique.
struct SalaryConventionNightRuleHistoryV2 {
    private let versions: [String: [SalaryConventionNightRuleSnapshotV2]]

    init?(_ snapshots: [SalaryConventionNightRuleSnapshotV2]) {
        guard SalaryConventionNightRuleStoreV2.historyIsStructurallyValid(snapshots) else {
            return nil
        }
        versions = Dictionary(grouping: snapshots) {
            SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc)
        }.mapValues { items in
            items.sorted { $0.effectiveFromEpochDay > $1.effectiveFromEpochDay }
        }
    }

    func applicable(
        idcc: String?,
        epochDay: Int64
    ) -> SalaryConventionNightRuleSnapshotV2? {
        guard let idcc else { return nil }
        let normalized = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        guard !normalized.isEmpty else { return nil }
        return versions[normalized]?.first { $0.applies(to: epochDay) }
    }

    func allVersions(idcc: String?) -> [SalaryConventionNightRuleSnapshotV2] {
        guard let idcc else { return [] }
        let normalized = SalaryConventionRuleStoreV2.normalizeIdcc(idcc)
        guard !normalized.isEmpty else { return [] }
        return versions[normalized] ?? []
    }
}

/// Stockage iOS fail-closed des plages de nuit conventionnelles confirmées.
///
/// Il reprend le contrat Android `V2ConventionNightRuleStore`, tout en conservant une copie
/// locale connue valide. Une liste vide signifie seulement « stockage lisible », jamais
/// « aucune règle de nuit n'existe ».
enum SalaryConventionNightRuleStoreV2 {
    static let storageWarning =
        "KALI nuit : historique local des règles conventionnelles incohérent ; aucune plage de nuit ni absence de règle ne peut être déduite."
    static let repairedWarning =
        "KALI nuit : historique local restauré depuis la dernière copie valide."
    static let missingWarning =
        "KALI nuit : aucune plage horaire officielle structurée ne couvre toute la période ; la ventilation de nuit reste à confirmer."
    static let coverageWarning =
        "KALI nuit : l'historique confirmé ne couvre pas toute la période ; la ventilation de nuit reste bloquée."
    static let changedDuringPeriodWarning =
        "KALI nuit : la plage horaire ou le multiplicateur change pendant la période ; le calcul mensuel unique reste bloqué jusqu'à segmentation."

    private static let primaryKey = "salary_convention_night_rules_v2.confirmed_snapshots"
    private static let backupKey = "salary_convention_night_rules_v2.confirmed_snapshots_last_known_good"
    private static let lock = NSLock()
    private static let maxExactJSONInteger = 9_007_199_254_740_991.0

    static func readConfirmed(
        defaults: UserDefaults = .standard
    ) -> SalaryConventionNightRuleReadResultV2 {
        let primaryObject = defaults.object(forKey: primaryKey)
        let backupObject = defaults.object(forKey: backupKey)

        if primaryObject == nil {
            guard let backupObject else {
                return SalaryConventionNightRuleReadResultV2(
                    snapshots: [],
                    reliable: true,
                    repairedFromBackup: false,
                    warnings: []
                )
            }
            guard let backupRaw = backupObject as? String else {
                return unreliableResult()
            }
            let backup = decodeConfirmed(backupRaw)
            guard backup.reliable else {
                return unreliableResult(snapshots: backup.snapshots)
            }
            guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
                return SalaryConventionNightRuleReadResultV2(
                    snapshots: backup.snapshots,
                    reliable: false,
                    repairedFromBackup: false,
                    warnings: [
                        storageWarning,
                        "KALI nuit : copie valide trouvée mais restauration locale impossible."
                    ]
                )
            }
            return SalaryConventionNightRuleReadResultV2(
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
            return SalaryConventionNightRuleReadResultV2(
                snapshots: backup.snapshots,
                reliable: false,
                repairedFromBackup: false,
                warnings: [
                    storageWarning,
                    "KALI nuit : copie valide trouvée mais restauration locale impossible."
                ]
            )
        }
        return SalaryConventionNightRuleReadResultV2(
            snapshots: backup.snapshots,
            reliable: true,
            repairedFromBackup: true,
            warnings: [repairedWarning]
        )
    }

    static func history(
        from stored: SalaryConventionNightRuleReadResultV2
    ) -> SalaryConventionNightRuleHistoryV2? {
        guard stored.reliable else { return nil }
        return SalaryConventionNightRuleHistoryV2(stored.snapshots)
    }

    @discardableResult
    static func saveConfirmed(
        _ snapshot: SalaryConventionNightRuleSnapshotV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard validSnapshotPayload(snapshot) else { return false }
        let candidate = normalizedSnapshot(snapshot)

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }

        var current = stored.snapshots.filter {
            !(SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc) == candidate.idcc
              && $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines) == candidate.versionId)
        }
        current.append(candidate)

        guard historyIsStructurallyValid(current),
              let raw = encodeConfirmed(current),
              writeVerified(raw, forKey: primaryKey, defaults: defaults),
              writeVerified(raw, forKey: backupKey, defaults: defaults) else {
            return false
        }

        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && reloaded.snapshots.contains(candidate)
    }

    static func resolve(
        defaults: UserDefaults = .standard,
        idcc: String?,
        period: YearMonthV2
    ) -> SalaryConventionNightRulePeriodResolutionV2 {
        resolve(
            stored: readConfirmed(defaults: defaults),
            idcc: idcc,
            period: period
        )
    }

    static func resolve(
        stored: SalaryConventionNightRuleReadResultV2,
        idcc: String?,
        period: YearMonthV2
    ) -> SalaryConventionNightRulePeriodResolutionV2 {
        guard stored.reliable else {
            return blocked(stored.warnings.isEmpty ? [storageWarning] : stored.warnings)
        }

        let normalizedIdcc = SalaryConventionRuleStoreV2.normalizeIdcc(idcc ?? "")
        guard !normalizedIdcc.isEmpty,
              let range = monthEpochDayRange(period) else {
            return blocked([missingWarning])
        }

        let candidates = stored.snapshots
            .filter { SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc) == normalizedIdcc }
            .filter { snapshot in
                let end = snapshot.effectiveToEpochDay ?? Int64.max
                return snapshot.effectiveFromEpochDay <= range.end && end >= range.start
            }
            .sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }

        guard !candidates.isEmpty else {
            return blocked(unique(stored.warnings + [missingWarning]))
        }
        guard coversEveryDay(candidates, start: range.start, end: range.end) else {
            return blocked(unique(stored.warnings + [coverageWarning]))
        }

        let distinctRules = Set(candidates.map {
            RuleFingerprint(
                startMinute: $0.rule.startMinute,
                endMinute: $0.rule.endMinute,
                multiplierBits: $0.rule.multiplier.bitPattern
            )
        })
        guard distinctRules.count == 1, let first = candidates.first else {
            return SalaryConventionNightRulePeriodResolutionV2(
                rule: nil,
                reliable: false,
                warnings: unique(stored.warnings + [changedDuringPeriodWarning]),
                sourceIds: unique(candidates.map(\.sourceId))
            )
        }

        return SalaryConventionNightRulePeriodResolutionV2(
            rule: first.rule,
            reliable: true,
            warnings: unique(stored.warnings),
            sourceIds: unique(candidates.map(\.sourceId))
        )
    }

    static func decodeConfirmed(
        _ raw: String
    ) -> SalaryConventionNightRuleReadResultV2 {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [Any] else {
            return unreliableResult()
        }

        var snapshots: [SalaryConventionNightRuleSnapshotV2] = []
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

        if !historyIsStructurallyValid(snapshots) {
            malformed = true
        }

        return SalaryConventionNightRuleReadResultV2(
            snapshots: snapshots,
            reliable: !malformed,
            repairedFromBackup: false,
            warnings: malformed ? [storageWarning] : []
        )
    }

    static func historyIsStructurallyValid(
        _ snapshots: [SalaryConventionNightRuleSnapshotV2]
    ) -> Bool {
        guard snapshots.allSatisfy(validSnapshotPayload) else { return false }

        let keys = snapshots.map {
            "\(SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc))\u{0}\($0.versionId.trimmingCharacters(in: .whitespacesAndNewlines))"
        }
        guard Set(keys).count == keys.count else { return false }

        let grouped = Dictionary(grouping: snapshots) {
            SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc)
        }
        for items in grouped.values {
            let ascending = items.sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
            for index in ascending.indices.dropFirst() {
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

    private static func validSnapshotPayload(
        _ snapshot: SalaryConventionNightRuleSnapshotV2
    ) -> Bool {
        guard !SalaryConventionRuleStoreV2.normalizeIdcc(snapshot.idcc).isEmpty,
              !snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              snapshot.checkedAtMs >= 0,
              (0..<(24 * 60)).contains(snapshot.rule.startMinute),
              (0..<(24 * 60)).contains(snapshot.rule.endMinute),
              snapshot.rule.startMinute != snapshot.rule.endMinute,
              snapshot.rule.multiplier.isFinite,
              snapshot.rule.multiplier >= 1.0 else {
            return false
        }
        if let end = snapshot.effectiveToEpochDay,
           end < snapshot.effectiveFromEpochDay {
            return false
        }
        return true
    }

    private static func normalizedSnapshot(
        _ snapshot: SalaryConventionNightRuleSnapshotV2
    ) -> SalaryConventionNightRuleSnapshotV2 {
        SalaryConventionNightRuleSnapshotV2(
            idcc: SalaryConventionRuleStoreV2.normalizeIdcc(snapshot.idcc),
            versionId: snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            sourceId: snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            effectiveFromEpochDay: snapshot.effectiveFromEpochDay,
            effectiveToEpochDay: snapshot.effectiveToEpochDay,
            rule: snapshot.rule,
            checkedAtMs: snapshot.checkedAtMs,
            note: normalizedOptionalString(snapshot.note)
        )
    }

    private static func encodeConfirmed(
        _ snapshots: [SalaryConventionNightRuleSnapshotV2]
    ) -> String? {
        let sorted = snapshots.sorted {
            let left = SalaryConventionRuleStoreV2.normalizeIdcc($0.idcc)
            let right = SalaryConventionRuleStoreV2.normalizeIdcc($1.idcc)
            return left == right
                ? $0.effectiveFromEpochDay < $1.effectiveFromEpochDay
                : left < right
        }

        let array: [[String: Any]] = sorted.map { snapshot in
            [
                "idcc": SalaryConventionRuleStoreV2.normalizeIdcc(snapshot.idcc),
                "versionId": snapshot.versionId,
                "sourceId": snapshot.sourceId,
                "effectiveFromEpochDay": snapshot.effectiveFromEpochDay,
                "effectiveToEpochDay": jsonValue(snapshot.effectiveToEpochDay),
                "checkedAtMs": snapshot.checkedAtMs,
                "note": jsonValue(snapshot.note),
                "rule": [
                    "startMinute": snapshot.rule.startMinute,
                    "endMinute": snapshot.rule.endMinute,
                    "multiplier": snapshot.rule.multiplier
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

    private static func decodeSnapshot(
        _ object: [String: Any]
    ) -> SalaryConventionNightRuleSnapshotV2? {
        guard let idcc = object["idcc"] as? String,
              let versionId = object["versionId"] as? String,
              let sourceId = object["sourceId"] as? String,
              let effectiveFrom = strictInt64(object["effectiveFromEpochDay"]),
              let effectiveTo = optionalInt64(object["effectiveToEpochDay"]),
              let checkedAt = strictInt64(object["checkedAtMs"]),
              let note = optionalString(object["note"]),
              let ruleObject = object["rule"] as? [String: Any],
              let startMinute = strictInt(ruleObject["startMinute"]),
              let endMinute = strictInt(ruleObject["endMinute"]),
              let multiplier = strictDouble(ruleObject["multiplier"]),
              let rule = NightPremiumRuleV2(
                  startMinute: startMinute,
                  endMinute: endMinute,
                  multiplier: multiplier
              ) else {
            return nil
        }

        return SalaryConventionNightRuleSnapshotV2(
            idcc: idcc,
            versionId: versionId,
            sourceId: sourceId,
            effectiveFromEpochDay: effectiveFrom,
            effectiveToEpochDay: effectiveTo,
            rule: rule,
            checkedAtMs: checkedAt,
            note: note
        )
    }

    private static func coversEveryDay(
        _ snapshots: [SalaryConventionNightRuleSnapshotV2],
        start: Int64,
        end: Int64
    ) -> Bool {
        var cursor = start
        for snapshot in snapshots {
            let sliceStart = max(start, snapshot.effectiveFromEpochDay)
            let sliceEnd = min(end, snapshot.effectiveToEpochDay ?? Int64.max)
            guard sliceEnd >= sliceStart else { continue }
            guard sliceStart <= cursor else { return false }
            if sliceEnd >= end { return true }
            guard sliceEnd < Int64.max else { return true }
            cursor = sliceEnd + 1
        }
        return false
    }

    private static func monthEpochDayRange(
        _ period: YearMonthV2
    ) -> (start: Int64, end: Int64)? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let startDate = calendar.date(
            from: DateComponents(
                year: period.year,
                month: period.month,
                day: 1
            )
        ),
        let nextMonth = calendar.date(
            byAdding: .month,
            value: 1,
            to: startDate
        ) else {
            return nil
        }

        let start = Int64(floor(startDate.timeIntervalSince1970 / 86_400.0))
        let next = Int64(floor(nextMonth.timeIntervalSince1970 / 86_400.0))
        guard next > start else { return nil }
        return (start, next - 1)
    }

    private static func strictInt(
        _ raw: Any?
    ) -> Int? {
        guard let value = strictInt64(raw) else { return nil }
        return Int(exactly: value)
    }

    private static func strictInt64(
        _ raw: Any?
    ) -> Int64? {
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

    private static func strictDouble(
        _ raw: Any?
    ) -> Double? {
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            return nil
        }
        let value = number.doubleValue
        return value.isFinite ? value : nil
    }

    private static func optionalInt64(
        _ raw: Any?
    ) -> Int64?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = strictInt64(raw) else { return nil }
        return .some(value)
    }

    private static func optionalString(
        _ raw: Any?
    ) -> String?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = raw as? String else { return nil }
        return .some(normalizedOptionalString(value))
    }

    private static func normalizedOptionalString(
        _ value: String?
    ) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func isBooleanNumber(
        _ number: NSNumber
    ) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func jsonValue<T>(
        _ value: T?
    ) -> Any {
        value ?? NSNull()
    }

    private static func writeVerified(
        _ value: String,
        forKey key: String,
        defaults: UserDefaults
    ) -> Bool {
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value
    }

    private static func blocked(
        _ warnings: [String]
    ) -> SalaryConventionNightRulePeriodResolutionV2 {
        SalaryConventionNightRulePeriodResolutionV2(
            rule: nil,
            reliable: false,
            warnings: unique(warnings),
            sourceIds: []
        )
    }

    private static func unreliableResult(
        snapshots: [SalaryConventionNightRuleSnapshotV2] = []
    ) -> SalaryConventionNightRuleReadResultV2 {
        SalaryConventionNightRuleReadResultV2(
            snapshots: snapshots,
            reliable: false,
            repairedFromBackup: false,
            warnings: [storageWarning]
        )
    }

    private static func unique(
        _ values: [String]
    ) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private struct RuleFingerprint: Hashable {
        let startMinute: Int
        let endMinute: Int
        let multiplierBits: UInt64
    }
}
