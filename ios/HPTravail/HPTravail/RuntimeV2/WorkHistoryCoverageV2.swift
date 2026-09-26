import Foundation

struct WorkHistoryCoverageAttestationV2: Codable, Equatable, Identifiable {
    let id: UUID
    let sourceId: String
    let employerId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let confirmedAt: Date
    let timeZoneId: String
    let note: String?
}

struct WorkHistoryCoverageReadV2: Equatable {
    let attestations: [WorkHistoryCoverageAttestationV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

struct WorkHistoryCoverageResultV2: Equatable {
    let employerId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let timeZoneId: String
    let attestations: [WorkHistoryCoverageAttestationV2]
    let fullyCovered: Bool
    let reliable: Bool
    let sourceId: String
    let checkedAt: Date?
    let warnings: [String]
}

/// Registre explicite de complétude du journal de pointage iOS.
/// Une lecture fiable des sessions ne crée jamais une preuve de couverture.
enum WorkHistoryCoverageStoreV2 {
    static let primaryKey = "hp_travail_work_history_coverage_v2"
    static let backupKey = "hp_travail_work_history_coverage_v2_last_known_good"

    static let storageWarning =
        "Couverture des pointages V2 : registre local incohérent ; aucune période complète ne peut être déduite."
    static let coverageWarning =
        "Couverture des pointages V2 : la période demandée n'est pas entièrement attestée."
    static let futureWarning =
        "Couverture des pointages V2 : une attestation future ou prématurée a été refusée."

    static func read(defaults: UserDefaults = .standard) -> WorkHistoryCoverageReadV2 {
        let primary = defaults.data(forKey: primaryKey)
        let backup = defaults.data(forKey: backupKey)
        if primary == nil && backup == nil {
            return .init(attestations: [], reliable: true, repairedFromBackup: false, warnings: [])
        }

        if let primary, let decoded = decode(primary) {
            if backup != primary {
                defaults.set(primary, forKey: backupKey)
            }
            return .init(attestations: decoded, reliable: true, repairedFromBackup: false, warnings: [])
        }

        if let backup, let decoded = decode(backup) {
            defaults.set(backup, forKey: primaryKey)
            guard defaults.data(forKey: primaryKey) == backup else {
                return .init(attestations: decoded, reliable: false, repairedFromBackup: false,
                             warnings: [storageWarning])
            }
            return .init(
                attestations: decoded,
                reliable: true,
                repairedFromBackup: true,
                warnings: ["Couverture des pointages V2 : registre restauré depuis la dernière copie valide."]
            )
        }

        return .init(attestations: [], reliable: false, repairedFromBackup: false,
                     warnings: [storageWarning])
    }

    @discardableResult
    static func saveConfirmed(
        _ attestation: WorkHistoryCoverageAttestationV2,
        defaults: UserDefaults = .standard,
        now: Date = Date()
    ) -> Bool {
        let item = normalized(attestation)
        guard validAttestation(item, now: now) else { return false }
        let stored = read(defaults: defaults)
        guard stored.reliable else { return false }
        let updated = stored.attestations.filter { $0.id != item.id } + [item]
        return write(updated, defaults: defaults)
    }

    static func coverage(
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        defaults: UserDefaults = .standard,
        now: Date = Date()
    ) -> WorkHistoryCoverageResultV2 {
        coverage(
            from: read(defaults: defaults),
            employerId: employerId,
            startEpochDay: startEpochDay,
            endEpochDay: endEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
    }

    static func coverage(
        from stored: WorkHistoryCoverageReadV2,
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> WorkHistoryCoverageResultV2 {
        let employer = employerId.trimmingCharacters(in: .whitespacesAndNewlines)
        let zoneId = timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines)
        var warnings = stored.warnings
        guard stored.reliable, !employer.isEmpty, !zoneId.isEmpty,
              endEpochDay >= startEpochDay, now.timeIntervalSince1970.isFinite,
              TimeZone(identifier: zoneId) != nil else {
            return .init(
                employerId: employer, startEpochDay: startEpochDay, endEpochDay: endEpochDay,
                timeZoneId: zoneId, attestations: [], fullyCovered: false, reliable: false,
                sourceId: "", checkedAt: nil, warnings: unique(warnings + [storageWarning])
            )
        }

        let matching = stored.attestations.filter {
            $0.employerId == employer &&
                $0.timeZoneId == zoneId &&
                $0.endEpochDay >= startEpochDay &&
                $0.startEpochDay <= endEpochDay
        }
        if matching.contains(where: { $0.confirmedAt > now || !validAttestation($0, now: now) }) {
            return .init(
                employerId: employer, startEpochDay: startEpochDay, endEpochDay: endEpochDay,
                timeZoneId: zoneId, attestations: matching, fullyCovered: false, reliable: false,
                sourceId: "", checkedAt: nil, warnings: unique(warnings + [futureWarning])
            )
        }

        let sorted = matching.sorted {
            if $0.startEpochDay == $1.startEpochDay { return $0.endEpochDay > $1.endEpochDay }
            return $0.startEpochDay < $1.startEpochDay
        }
        var cursor = startEpochDay
        var used: [WorkHistoryCoverageAttestationV2] = []
        for item in sorted {
            if item.endEpochDay < cursor { continue }
            if item.startEpochDay > cursor { break }
            used.append(item)
            if item.endEpochDay >= endEpochDay {
                cursor = endEpochDay
                break
            }
            guard item.endEpochDay < Int64.max else { break }
            cursor = item.endEpochDay + 1
        }

        let fullyCovered = !used.isEmpty && cursor >= endEpochDay
        if !fullyCovered { warnings.append(coverageWarning) }
        let ids = used.map { $0.id.uuidString }.sorted().joined(separator: ",")

        return .init(
            employerId: employer,
            startEpochDay: startEpochDay,
            endEpochDay: endEpochDay,
            timeZoneId: zoneId,
            attestations: used,
            fullyCovered: fullyCovered,
            reliable: true,
            sourceId: fullyCovered ? "coverage:\(ids)" : "",
            checkedAt: used.map(\.confirmedAt).max(),
            warnings: unique(warnings)
        )
    }

    @discardableResult
    static func invalidateRange(
        start: Date,
        end: Date,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard start.timeIntervalSince1970.isFinite,
              end.timeIntervalSince1970.isFinite,
              end > start else { return false }
        let stored = read(defaults: defaults)
        guard stored.reliable else { return false }
        let kept = invalidating(stored.attestations, start: start, end: end)
        return kept == stored.attestations || write(kept, defaults: defaults)
    }

    @discardableResult
    static func clearAll(defaults: UserDefaults = .standard) -> Bool {
        write([], defaults: defaults)
    }

    static func invalidating(
        _ attestations: [WorkHistoryCoverageAttestationV2],
        start: Date,
        end: Date
    ) -> [WorkHistoryCoverageAttestationV2] {
        attestations.filter { item in
            guard let zone = TimeZone(identifier: item.timeZoneId),
                  let affectedStart = epochDay(for: start, timeZone: zone),
                  let affectedEnd = epochDay(for: end.addingTimeInterval(-0.001), timeZone: zone) else {
                return false
            }
            return affectedStart > item.endEpochDay || affectedEnd < item.startEpochDay
        }
    }

    static func encode(_ attestations: [WorkHistoryCoverageAttestationV2]) -> Data? {
        guard Set(attestations.map(\.id)).count == attestations.count,
              attestations.allSatisfy(validShape) else { return nil }
        let ordered = attestations.map(normalized).sorted {
            if $0.employerId != $1.employerId { return $0.employerId < $1.employerId }
            if $0.timeZoneId != $1.timeZoneId { return $0.timeZoneId < $1.timeZoneId }
            if $0.startEpochDay != $1.startEpochDay { return $0.startEpochDay < $1.startEpochDay }
            if $0.endEpochDay != $1.endEpochDay { return $0.endEpochDay < $1.endEpochDay }
            return $0.id.uuidString < $1.id.uuidString
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try? encoder.encode(ordered)
    }

    static func decode(_ data: Data) -> [WorkHistoryCoverageAttestationV2]? {
        guard let decoded = try? JSONDecoder().decode([WorkHistoryCoverageAttestationV2].self, from: data),
              Set(decoded.map(\.id)).count == decoded.count,
              decoded.allSatisfy(validShape) else { return nil }
        return decoded.map(normalized)
    }

    private static func write(
        _ attestations: [WorkHistoryCoverageAttestationV2],
        defaults: UserDefaults
    ) -> Bool {
        guard let data = encode(attestations) else { return false }
        defaults.set(data, forKey: primaryKey)
        defaults.set(data, forKey: backupKey)
        return defaults.data(forKey: primaryKey) == data &&
            defaults.data(forKey: backupKey) == data &&
            decode(data) != nil
    }

    private static func validAttestation(
        _ item: WorkHistoryCoverageAttestationV2,
        now: Date
    ) -> Bool {
        guard validShape(item), item.confirmedAt <= now,
              let zone = TimeZone(identifier: item.timeZoneId),
              item.endEpochDay < Int64.max,
              let completeAt = localStart(epochDay: item.endEpochDay + 1, timeZone: zone) else {
            return false
        }
        return item.confirmedAt >= completeAt
    }

    private static func validShape(_ item: WorkHistoryCoverageAttestationV2) -> Bool {
        !item.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !item.employerId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !item.timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            item.endEpochDay >= item.startEpochDay &&
            item.confirmedAt.timeIntervalSince1970.isFinite &&
            item.confirmedAt.timeIntervalSince1970 > 0 &&
            TimeZone(identifier: item.timeZoneId) != nil
    }

    private static func normalized(
        _ item: WorkHistoryCoverageAttestationV2
    ) -> WorkHistoryCoverageAttestationV2 {
        .init(
            id: item.id,
            sourceId: item.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            employerId: item.employerId.trimmingCharacters(in: .whitespacesAndNewlines),
            startEpochDay: item.startEpochDay,
            endEpochDay: item.endEpochDay,
            confirmedAt: item.confirmedAt,
            timeZoneId: item.timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines),
            note: item.note?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }

    private static func localStart(epochDay: Int64, timeZone: TimeZone) -> Date? {
        guard let civil = utcCivilDate(epochDay) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.date(from: DateComponents(
            year: civil.year, month: civil.month, day: civil.day, hour: 0, minute: 0, second: 0
        ))
    }

    private static func epochDay(for date: Date, timeZone: TimeZone) -> Int64? {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        let c = local.dateComponents([.year, .month, .day], from: date)
        guard let year = c.year, let month = c.month, let day = c.day else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let midnight = utc.date(from: DateComponents(year: year, month: month, day: day)) else { return nil }
        return Int64(midnight.timeIntervalSince1970 / 86_400)
    }

    private static func utcCivilDate(_ epochDay: Int64) -> (year: Int, month: Int, day: Int)? {
        let seconds = Double(epochDay) * 86_400
        guard seconds.isFinite else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let c = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: seconds))
        guard let year = c.year, let month = c.month, let day = c.day else { return nil }
        return (year, month, day)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
