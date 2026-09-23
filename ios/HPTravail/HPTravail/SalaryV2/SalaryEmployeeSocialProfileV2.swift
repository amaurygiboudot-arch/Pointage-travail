import CoreFoundation
import Foundation

enum SalaryProfessionalStatusV2: String, Equatable {
    case cadre = "CADRE"
    case nonCadre = "NON_CADRE"
}

/// Version datée des informations salarié qui modifient les cotisations du net.
///
/// Ces valeurs ne sont jamais déduites de l'adresse, du métier, du contrat ou de la convention.
/// Elles doivent être confirmées explicitement pour l'entreprise et la période concernées.
struct SalaryEmployeeSocialProfileSnapshotV2: Equatable {
    let companyId: String
    let versionId: String
    let sourceId: String
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let professionalStatus: SalaryProfessionalStatusV2
    let alsaceMoselleLocalRegime: Bool
    let checkedAtMs: Int64

    func applies(to epochDay: Int64) -> Bool {
        guard epochDay >= effectiveFromEpochDay else { return false }
        guard let end = effectiveToEpochDay else { return true }
        return epochDay <= end
    }
}

struct SalaryEmployeeSocialProfileReadResultV2: Equatable {
    let snapshots: [SalaryEmployeeSocialProfileSnapshotV2]
    let reliable: Bool
    let warnings: [String]
}

struct SalaryEmployeeSocialProfileResolutionV2: Equatable {
    let professionalStatus: SalaryProfessionalStatusV2?
    let alsaceMoselleLocalRegime: Bool?
    let reliable: Bool
    let warnings: [String]
}

/// Source iOS fail-closed du statut cadre/non-cadre et de l'affiliation au régime local.
///
/// Un stockage réellement absent est lisible mais ne prouve aucune valeur. Pour publier un net,
/// la période complète doit être couverte par des versions confirmées et toutes les versions
/// rencontrées pendant le mois doivent porter les mêmes valeurs. Un changement réel en cours de
/// mois est donc conservé dans l'historique mais bloque le net mensuel jusqu'à une segmentation sûre.
enum SalaryEmployeeSocialProfileStoreV2 {
    static let storageWarning =
        "Profil social salarié : stockage local incohérent ; statut professionnel et régime local restent à confirmer."
    static let companyWarning =
        "Profil social salarié : entreprise absente ou stockage des entreprises non fiable."
    static let missingWarning =
        "Profil social salarié : statut cadre/non-cadre et affiliation Alsace-Moselle doivent être confirmés pour toute la période."
    static let coverageWarning =
        "Profil social salarié : l'historique confirmé ne couvre pas toute la période ; net salarié bloqué."
    static let changedDuringPeriodWarning =
        "Profil social salarié : le statut professionnel ou le régime local change pendant la période ; net mensuel bloqué jusqu'à une segmentation sûre."

    private static let keyPrefix = "salary_employee_social_profile_v2."
    private static let lock = NSLock()
    private static let maxExactJSONInteger = 9_007_199_254_740_991.0

    static func readConfirmed(
        defaults: UserDefaults = .standard,
        companyId rawCompanyId: String
    ) -> SalaryEmployeeSocialProfileReadResultV2 {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(defaults: defaults, companyId: companyId) else {
            return SalaryEmployeeSocialProfileReadResultV2(
                snapshots: [],
                reliable: false,
                warnings: [companyWarning]
            )
        }

        guard let object = defaults.object(forKey: storageKey(companyId)) else {
            return SalaryEmployeeSocialProfileReadResultV2(
                snapshots: [],
                reliable: true,
                warnings: []
            )
        }
        guard let raw = object as? String else {
            return unreliableResult()
        }
        let decoded = decode(raw)
        guard decoded.reliable,
              decoded.snapshots.allSatisfy({ normalized($0.companyId) == companyId }) else {
            return unreliableResult(snapshots: decoded.snapshots)
        }
        return decoded
    }

    @discardableResult
    static func saveConfirmed(
        _ snapshot: SalaryEmployeeSocialProfileSnapshotV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let candidate = normalizedSnapshot(snapshot)
        guard valid(candidate),
              confirmedCompany(defaults: defaults, companyId: candidate.companyId) else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults, companyId: candidate.companyId)
        guard stored.reliable else { return false }

        var snapshots = stored.snapshots.filter { $0.versionId != candidate.versionId }
        snapshots.append(candidate)
        guard historyIsStructurallyValid(snapshots),
              let raw = encode(snapshots) else {
            return false
        }

        defaults.set(raw, forKey: storageKey(candidate.companyId))
        guard defaults.string(forKey: storageKey(candidate.companyId)) == raw else {
            return false
        }
        let reloaded = readConfirmed(defaults: defaults, companyId: candidate.companyId)
        return reloaded.reliable && reloaded.snapshots.contains(candidate)
    }

    static func resolve(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: YearMonthV2
    ) -> SalaryEmployeeSocialProfileResolutionV2 {
        resolve(
            readConfirmed(defaults: defaults, companyId: companyId),
            companyId: companyId,
            period: period
        )
    }

    static func resolve(
        _ stored: SalaryEmployeeSocialProfileReadResultV2,
        companyId rawCompanyId: String,
        period: YearMonthV2
    ) -> SalaryEmployeeSocialProfileResolutionV2 {
        let companyId = normalized(rawCompanyId)
        guard stored.reliable else {
            return blocked(stored.warnings.isEmpty ? [storageWarning] : stored.warnings)
        }
        guard !companyId.isEmpty,
              let range = monthRange(period) else {
            return blocked([missingWarning])
        }

        let candidates = stored.snapshots
            .filter { normalized($0.companyId) == companyId }
            .filter { snapshot in
                let end = snapshot.effectiveToEpochDay ?? Int64.max
                return snapshot.effectiveFromEpochDay <= range.end && end >= range.start
            }
            .sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }

        guard !candidates.isEmpty else {
            return blocked([missingWarning])
        }
        guard coversEveryDay(candidates, start: range.start, end: range.end) else {
            return blocked(unique(stored.warnings + [coverageWarning]))
        }

        let statuses = Set(candidates.map(\.professionalStatus.rawValue))
        let localRegimes = Set(candidates.map(\.alsaceMoselleLocalRegime))
        guard statuses.count == 1, localRegimes.count == 1,
              let first = candidates.first else {
            return blocked(unique(stored.warnings + [changedDuringPeriodWarning]))
        }

        return SalaryEmployeeSocialProfileResolutionV2(
            professionalStatus: first.professionalStatus,
            alsaceMoselleLocalRegime: first.alsaceMoselleLocalRegime,
            reliable: true,
            warnings: unique(stored.warnings)
        )
    }

    static func decode(_ raw: String) -> SalaryEmployeeSocialProfileReadResultV2 {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [Any] else {
            return unreliableResult()
        }

        var snapshots: [SalaryEmployeeSocialProfileSnapshotV2] = []
        var malformed = false
        for item in array {
            guard let object = item as? [String: Any],
                  let snapshot = decodeSnapshot(object),
                  valid(snapshot) else {
                malformed = true
                continue
            }
            snapshots.append(normalizedSnapshot(snapshot))
        }
        if !historyIsStructurallyValid(snapshots) {
            malformed = true
        }

        return SalaryEmployeeSocialProfileReadResultV2(
            snapshots: snapshots,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    static func historyIsStructurallyValid(
        _ snapshots: [SalaryEmployeeSocialProfileSnapshotV2]
    ) -> Bool {
        guard snapshots.allSatisfy(valid) else { return false }

        let versionKeys = snapshots.map { "\(normalized($0.companyId))\u{0}\($0.versionId)" }
        guard Set(versionKeys).count == versionKeys.count else { return false }

        let grouped = Dictionary(grouping: snapshots) { normalized($0.companyId) }
        for items in grouped.values {
            let sorted = items.sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
            for index in sorted.indices.dropFirst() {
                let previous = sorted[index - 1]
                let current = sorted[index]
                guard let previousEnd = previous.effectiveToEpochDay,
                      previousEnd < current.effectiveFromEpochDay else {
                    return false
                }
            }
        }
        return true
    }

    private static func coversEveryDay(
        _ snapshots: [SalaryEmployeeSocialProfileSnapshotV2],
        start: Int64,
        end: Int64
    ) -> Bool {
        var cursor = start
        for snapshot in snapshots {
            let sliceStart = max(snapshot.effectiveFromEpochDay, start)
            let sliceEnd = min(snapshot.effectiveToEpochDay ?? Int64.max, end)
            guard sliceEnd >= sliceStart else { continue }
            guard sliceStart <= cursor else { return false }
            if sliceEnd >= end { return true }
            guard sliceEnd < Int64.max else { return true }
            cursor = sliceEnd + 1
        }
        return false
    }

    private static func monthRange(_ period: YearMonthV2) -> (start: Int64, end: Int64)? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let startDate = calendar.date(
            from: DateComponents(year: period.year, month: period.month, day: 1)
        ),
        let nextMonth = calendar.date(byAdding: .month, value: 1, to: startDate) else {
            return nil
        }
        let start = Int64(floor(startDate.timeIntervalSince1970 / 86_400.0))
        let next = Int64(floor(nextMonth.timeIntervalSince1970 / 86_400.0))
        guard next > start else { return nil }
        return (start, next - 1)
    }

    private static func valid(_ snapshot: SalaryEmployeeSocialProfileSnapshotV2) -> Bool {
        guard !normalized(snapshot.companyId).isEmpty,
              !snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              snapshot.checkedAtMs >= 0 else {
            return false
        }
        if let end = snapshot.effectiveToEpochDay, end < snapshot.effectiveFromEpochDay {
            return false
        }
        return true
    }

    private static func normalizedSnapshot(
        _ snapshot: SalaryEmployeeSocialProfileSnapshotV2
    ) -> SalaryEmployeeSocialProfileSnapshotV2 {
        SalaryEmployeeSocialProfileSnapshotV2(
            companyId: normalized(snapshot.companyId),
            versionId: snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            sourceId: snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            effectiveFromEpochDay: snapshot.effectiveFromEpochDay,
            effectiveToEpochDay: snapshot.effectiveToEpochDay,
            professionalStatus: snapshot.professionalStatus,
            alsaceMoselleLocalRegime: snapshot.alsaceMoselleLocalRegime,
            checkedAtMs: snapshot.checkedAtMs
        )
    }

    private static func encode(
        _ snapshots: [SalaryEmployeeSocialProfileSnapshotV2]
    ) -> String? {
        let sorted = snapshots.sorted {
            if normalized($0.companyId) != normalized($1.companyId) {
                return normalized($0.companyId) < normalized($1.companyId)
            }
            return $0.effectiveFromEpochDay < $1.effectiveFromEpochDay
        }
        let array: [[String: Any]] = sorted.map { snapshot in
            [
                "companyId": snapshot.companyId,
                "versionId": snapshot.versionId,
                "sourceId": snapshot.sourceId,
                "effectiveFromEpochDay": snapshot.effectiveFromEpochDay,
                "effectiveToEpochDay": snapshot.effectiveToEpochDay.map { $0 as Any } ?? NSNull(),
                "professionalStatus": snapshot.professionalStatus.rawValue,
                "alsaceMoselleLocalRegime": snapshot.alsaceMoselleLocalRegime,
                "checkedAtMs": snapshot.checkedAtMs
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
    ) -> SalaryEmployeeSocialProfileSnapshotV2? {
        guard let companyRaw = object["companyId"] as? String,
              let versionRaw = object["versionId"] as? String,
              let sourceRaw = object["sourceId"] as? String,
              let from = strictInt64(object["effectiveFromEpochDay"]),
              let to = optionalInt64(object["effectiveToEpochDay"]),
              let statusRaw = object["professionalStatus"] as? String,
              let status = SalaryProfessionalStatusV2(rawValue: statusRaw),
              let localRegime = strictBool(object["alsaceMoselleLocalRegime"]),
              let checkedAt = strictInt64(object["checkedAtMs"]) else {
            return nil
        }
        return SalaryEmployeeSocialProfileSnapshotV2(
            companyId: companyRaw,
            versionId: versionRaw,
            sourceId: sourceRaw,
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            professionalStatus: status,
            alsaceMoselleLocalRegime: localRegime,
            checkedAtMs: checkedAt
        )
    }

    private static func strictBool(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else {
            return nil
        }
        return number.boolValue
    }

    private static func strictInt64(_ raw: Any?) -> Int64? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
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

    private static func optionalInt64(_ raw: Any?) -> Int64?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = strictInt64(raw) else { return nil }
        return .some(value)
    }

    private static func confirmedCompany(defaults: UserDefaults, companyId: String) -> Bool {
        guard !companyId.isEmpty else { return false }
        let stored = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        return SalaryCompanyStoreV2.confirmedCompany(stored, companyId: companyId) != nil
    }

    private static func storageKey(_ companyId: String) -> String {
        keyPrefix + companyId
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func blocked(_ warnings: [String]) -> SalaryEmployeeSocialProfileResolutionV2 {
        SalaryEmployeeSocialProfileResolutionV2(
            professionalStatus: nil,
            alsaceMoselleLocalRegime: nil,
            reliable: false,
            warnings: unique(warnings)
        )
    }

    private static func unreliableResult(
        snapshots: [SalaryEmployeeSocialProfileSnapshotV2] = []
    ) -> SalaryEmployeeSocialProfileReadResultV2 {
        SalaryEmployeeSocialProfileReadResultV2(
            snapshots: snapshots,
            reliable: false,
            warnings: [storageWarning]
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
