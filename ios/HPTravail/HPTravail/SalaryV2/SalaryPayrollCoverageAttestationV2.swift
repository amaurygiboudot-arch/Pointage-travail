import Foundation

struct SalaryPayrollCoverageAttestationV2: Codable, Equatable {
    let id: UUID
    let employerId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let confirmedAt: Date
    let timeZoneId: String
}

struct SalaryPayrollCoverageResolutionV2: Equatable {
    let reliable: Bool
    let exhaustive: Bool
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let sourceId: String
    let warnings: [String]
}

/// Preuve persistée séparée du journal RuntimeV2.
/// Une lecture saine des sessions ne certifie jamais qu'une journée vide vaut 0 h.
enum SalaryPayrollCoverageAttestationStoreV2 {
    static let storageKey = "hp_travail_payroll_coverage_v2"
    static let corruptWarning =
        "Couverture paie V2 illisible ou incohérente : aucune semaine vide ne peut être considérée comme confirmée."
    static let missingWarning =
        "Couverture paie V2 incomplète : la période doit être explicitement confirmée avant le calcul B21."

    private static let minimumEpochDay: Int64 = -25567
    private static let maximumEpochDay: Int64 = 84370
    private static let schema = 1

    struct Payload: Codable, Equatable {
        let schema: Int
        let attestations: [SalaryPayrollCoverageAttestationV2]
    }

    struct ReadResult: Equatable {
        let attestations: [SalaryPayrollCoverageAttestationV2]
        let reliable: Bool
        let warnings: [String]
    }

    @discardableResult
    static func confirm(
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        confirmedAt: Date = Date(),
        defaults: UserDefaults = .standard
    ) -> Bool {
        let employer = normalized(employerId)
        guard !employer.isEmpty,
              confirmedAt.timeIntervalSince1970.isFinite,
              confirmedAt.timeIntervalSince1970 > 0,
              (minimumEpochDay...maximumEpochDay).contains(startEpochDay),
              (minimumEpochDay...maximumEpochDay).contains(endEpochDay),
              endEpochDay >= startEpochDay,
              let timeZone = TimeZone(identifier: timeZoneId),
              intervalFinished(endEpochDay: endEpochDay, timeZone: timeZone, confirmedAt: confirmedAt)
        else { return false }

        let current = read(defaults: defaults)
        guard current.reliable else { return false }
        let next = current.attestations + [
            SalaryPayrollCoverageAttestationV2(
                id: UUID(),
                employerId: employer,
                startEpochDay: startEpochDay,
                endEpochDay: endEpochDay,
                confirmedAt: confirmedAt,
                timeZoneId: timeZone.identifier
            )
        ]
        guard let data = encode(next) else { return false }
        defaults.set(data, forKey: storageKey)
        return decode(defaults.data(forKey: storageKey))
            == ReadResult(attestations: next, reliable: true, warnings: [])
    }

    static func read(defaults: UserDefaults = .standard) -> ReadResult {
        guard let data = defaults.data(forKey: storageKey) else {
            return .init(attestations: [], reliable: true, warnings: [])
        }
        return decode(data)
    }

    static func resolve(
        attestations: [SalaryPayrollCoverageAttestationV2],
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> SalaryPayrollCoverageResolutionV2 {
        let employer = normalized(employerId)
        guard !employer.isEmpty,
              now.timeIntervalSince1970.isFinite,
              now.timeIntervalSince1970 > 0,
              (minimumEpochDay...maximumEpochDay).contains(requestedStartEpochDay),
              (minimumEpochDay...maximumEpochDay).contains(requestedEndEpochDay),
              requestedEndEpochDay >= requestedStartEpochDay,
              let timeZone = TimeZone(identifier: timeZoneId),
              attestations.allSatisfy({ isValid($0, now: now) })
        else {
            return .init(
                reliable: false,
                exhaustive: false,
                coveredStartEpochDay: requestedStartEpochDay,
                coveredEndEpochDay: requestedEndEpochDay,
                checkedAt: now,
                sourceId: "",
                warnings: [corruptWarning]
            )
        }

        let matching = attestations
            .filter {
                normalized($0.employerId) == employer &&
                    $0.timeZoneId == timeZone.identifier
            }
            .sorted {
                if $0.startEpochDay != $1.startEpochDay {
                    return $0.startEpochDay < $1.startEpochDay
                }
                if $0.endEpochDay != $1.endEpochDay {
                    return $0.endEpochDay < $1.endEpochDay
                }
                if $0.confirmedAt != $1.confirmedAt {
                    return $0.confirmedAt < $1.confirmedAt
                }
                return $0.id.uuidString < $1.id.uuidString
            }

        var cursor = requestedStartEpochDay
        var checkedAt = Date(timeIntervalSince1970: 0)
        var used: [SalaryPayrollCoverageAttestationV2] = []

        for item in matching {
            if item.endEpochDay < cursor { continue }
            if item.startEpochDay > cursor { break }
            if item.startEpochDay <= cursor && item.endEpochDay >= cursor {
                used.append(item)
                if item.confirmedAt > checkedAt { checkedAt = item.confirmedAt }
                cursor = item.endEpochDay + 1
                if cursor > requestedEndEpochDay { break }
            }
        }

        let exhaustive = cursor > requestedEndEpochDay
        let sourceId = exhaustive
            ? "coverage-v" + String(schema) + ":" +
                used.map(\.id.uuidString).sorted().joined(separator: ",")
            : ""

        return .init(
            reliable: true,
            exhaustive: exhaustive,
            coveredStartEpochDay: requestedStartEpochDay,
            coveredEndEpochDay: requestedEndEpochDay,
            checkedAt: exhaustive ? checkedAt : now,
            sourceId: sourceId,
            warnings: exhaustive ? [] : [missingWarning]
        )
    }

    static func resolve(
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults = .standard
    ) -> SalaryPayrollCoverageResolutionV2 {
        let stored = read(defaults: defaults)
        guard stored.reliable else {
            return .init(
                reliable: false,
                exhaustive: false,
                coveredStartEpochDay: requestedStartEpochDay,
                coveredEndEpochDay: requestedEndEpochDay,
                checkedAt: now,
                sourceId: "",
                warnings: stored.warnings
            )
        }
        return resolve(
            attestations: stored.attestations,
            employerId: employerId,
            requestedStartEpochDay: requestedStartEpochDay,
            requestedEndEpochDay: requestedEndEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
    }

    static func sessionSource(
        employerId: String,
        work: SalaryWorkSessionSourceV2,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults = .standard
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let coverage = resolve(
            employerId: employerId,
            requestedStartEpochDay: requestedStartEpochDay,
            requestedEndEpochDay: requestedEndEpochDay,
            timeZoneId: timeZoneId,
            now: now,
            defaults: defaults
        )
        let sourceId = coverage.sourceId.isEmpty
            ? "runtime-v2:" + String(now.timeIntervalSince1970)
            : "runtime-v2:" + String(now.timeIntervalSince1970) + ":" + coverage.sourceId

        return SalarySegmentedPayrollSessionSourceV2(
            employerId: normalized(employerId),
            work: work,
            sourceId: sourceId,
            exhaustive: coverage.exhaustive,
            coveredStartEpochDay: coverage.coveredStartEpochDay,
            coveredEndEpochDay: coverage.coveredEndEpochDay,
            checkedAt: coverage.checkedAt,
            timeZoneId: timeZoneId,
            warnings: coverage.warnings
        )
    }

    static func decode(_ data: Data) -> ReadResult {
        guard let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.schema == schema,
              Set(payload.attestations.map(\.id)).count == payload.attestations.count,
              payload.attestations.allSatisfy(isValidStructure)
        else {
            return .init(attestations: [], reliable: false, warnings: [corruptWarning])
        }
        return .init(attestations: payload.attestations, reliable: true, warnings: [])
    }

    static func encode(_ attestations: [SalaryPayrollCoverageAttestationV2]) -> Data? {
        guard Set(attestations.map(\.id)).count == attestations.count,
              attestations.allSatisfy(isValidStructure)
        else { return nil }
        return try? JSONEncoder().encode(Payload(schema: schema, attestations: attestations))
    }

    private static func isValid(
        _ item: SalaryPayrollCoverageAttestationV2,
        now: Date
    ) -> Bool {
        guard isValidStructure(item),
              item.confirmedAt <= now,
              let zone = TimeZone(identifier: item.timeZoneId)
        else { return false }
        return intervalFinished(
            endEpochDay: item.endEpochDay,
            timeZone: zone,
            confirmedAt: item.confirmedAt
        )
    }

    private static func isValidStructure(
        _ item: SalaryPayrollCoverageAttestationV2
    ) -> Bool {
        !normalized(item.employerId).isEmpty &&
            (minimumEpochDay...maximumEpochDay).contains(item.startEpochDay) &&
            (minimumEpochDay...maximumEpochDay).contains(item.endEpochDay) &&
            item.endEpochDay >= item.startEpochDay &&
            item.confirmedAt.timeIntervalSince1970.isFinite &&
            item.confirmedAt.timeIntervalSince1970 > 0 &&
            TimeZone(identifier: item.timeZoneId) != nil
    }

    private static func intervalFinished(
        endEpochDay: Int64,
        timeZone: TimeZone,
        confirmedAt: Date
    ) -> Bool {
        guard let endStart = localStart(endEpochDay + 1, timeZone: timeZone) else {
            return false
        }
        return endStart <= confirmedAt
    }

    private static func localStart(
        _ epochDay: Int64,
        timeZone: TimeZone
    ) -> Date? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let reference = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        let civil = utc.dateComponents([.year, .month, .day], from: reference)
        guard let year = civil.year, let month = civil.month, let day = civil.day else {
            return nil
        }
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        local.locale = Locale(identifier: "en_US_POSIX")
        guard let value = local.date(
            from: DateComponents(year: year, month: month, day: day, hour: 0, minute: 0, second: 0)
        ) else { return nil }
        let actual = local.dateComponents([.year, .month, .day], from: value)
        guard actual.year == year, actual.month == month, actual.day == day else {
            return nil
        }
        return local.startOfDay(for: value)
    }

    private static func normalized(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}
