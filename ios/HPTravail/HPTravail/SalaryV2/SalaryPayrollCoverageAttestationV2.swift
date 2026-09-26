import Foundation
import CryptoKit

struct SalaryPayrollCoverageAttestationV2: Codable, Equatable {
    let id: UUID
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    let sessionFingerprint: String
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

enum SalaryPayrollCoverageAttestationPolicyV2 {
    static let missingWarning =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    static let staleWarning =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."
    static let corruptWarning =
        "Preuves B21 : registre d'attestations illisible ou incohérent ; exhaustivité non prouvée."

    private static let minimumEpochDay: Int64 = -25_567
    private static let maximumEpochDay: Int64 = 84_370

    static func fingerprint(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String
    ) -> String? {
        let employer = normalized(employerId)
        guard !employer.isEmpty,
              (minimumEpochDay...maximumEpochDay).contains(coveredStartEpochDay),
              (minimumEpochDay...maximumEpochDay).contains(coveredEndEpochDay),
              coveredEndEpochDay >= coveredStartEpochDay,
              let timeZone = TimeZone(identifier: normalized(timeZoneId)),
              let from = localStart(coveredStartEpochDay, timeZone: timeZone),
              coveredEndEpochDay < Int64.max,
              let to = localStart(coveredEndEpochDay + 1, timeZone: timeZone),
              to > from else { return nil }

        let relevant = work.sessions
            .filter { session in
                let sessionEmployer = normalized(session.employerId)
                return (sessionEmployer.isEmpty || sessionEmployer == employer) &&
                    touches(session, from: from, to: to)
            }
            .sorted {
                if normalized($0.id) != normalized($1.id) {
                    return normalized($0.id) < normalized($1.id)
                }
                if $0.entry != $1.entry { return $0.entry < $1.entry }
                return ($0.exit ?? .distantPast) < ($1.exit ?? .distantPast)
            }

        var canonical = ""
        append("coverage-v1", to: &canonical)
        append(employer, to: &canonical)
        append(String(coveredStartEpochDay), to: &canonical)
        append(String(coveredEndEpochDay), to: &canonical)
        append(timeZone.identifier, to: &canonical)

        for session in relevant {
            append("session", to: &canonical)
            append(normalized(session.id), to: &canonical)
            append(normalized(session.employerId), to: &canonical)
            append(bits(session.entry), to: &canonical)
            append(session.exit.map(bits) ?? "", to: &canonical)

            for pause in session.pauses.sorted(by: pauseOrder) {
                append("pause", to: &canonical)
                append(bits(pause.start), to: &canonical)
                append(pause.end.map(bits) ?? "", to: &canonical)
                append(pause.paid.map { String($0) } ?? "", to: &canonical)
            }
            append("end-session", to: &canonical)
        }

        let digest = SHA256.hash(data: Data(canonical.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func isStructurallyValid(_ item: SalaryPayrollCoverageAttestationV2) -> Bool {
        guard !normalized(item.employerId).isEmpty,
              (minimumEpochDay...maximumEpochDay).contains(item.coveredStartEpochDay),
              (minimumEpochDay...maximumEpochDay).contains(item.coveredEndEpochDay),
              item.coveredEndEpochDay >= item.coveredStartEpochDay,
              item.checkedAt.timeIntervalSince1970.isFinite,
              item.checkedAt.timeIntervalSince1970 > 0,
              !normalized(item.sessionFingerprint).isEmpty,
              TimeZone(identifier: normalized(item.timeZoneId)) != nil
        else { return false }

        return coverageClosedBeforeCheck(
            coveredEndEpochDay: item.coveredEndEpochDay,
            checkedAt: item.checkedAt,
            timeZoneId: item.timeZoneId
        )
    }

    static func isCurrent(
        _ item: SalaryPayrollCoverageAttestationV2,
        work: SalaryWorkSessionSourceV2,
        now: Date
    ) -> Bool {
        guard isStructurallyValid(item),
              now.timeIntervalSince1970.isFinite,
              item.checkedAt <= now,
              let current = fingerprint(
                work: work,
                employerId: item.employerId,
                coveredStartEpochDay: item.coveredStartEpochDay,
                coveredEndEpochDay: item.coveredEndEpochDay,
                timeZoneId: item.timeZoneId
              ) else { return false }
        return current == item.sessionFingerprint
    }

    static func coverageClosedBeforeCheck(
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String
    ) -> Bool {
        guard (minimumEpochDay...maximumEpochDay).contains(coveredEndEpochDay),
              checkedAt.timeIntervalSince1970.isFinite,
              checkedAt.timeIntervalSince1970 > 0,
              let timeZone = TimeZone(identifier: normalized(timeZoneId)),
              coveredEndEpochDay < Int64.max,
              let end = localStart(coveredEndEpochDay + 1, timeZone: timeZone)
        else { return false }
        return end <= checkedAt
    }

    private static func touches(_ session: SalarySessionFactV2, from: Date, to: Date) -> Bool {
        guard session.entry.timeIntervalSince1970.isFinite else { return true }
        guard let exit = session.exit else { return session.entry < to }
        guard exit.timeIntervalSince1970.isFinite else { return true }
        if exit == session.entry { return session.entry >= from && session.entry < to }
        return min(session.entry, exit) < to && max(session.entry, exit) > from
    }

    private static func localStart(_ epochDay: Int64, timeZone: TimeZone) -> Date? {
        guard (minimumEpochDay...maximumEpochDay).contains(epochDay) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let reference = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let civil = utc.dateComponents([.year, .month, .day], from: reference)
        guard let year = civil.year, let month = civil.month, let day = civil.day else { return nil }

        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        local.locale = Locale(identifier: "en_US_POSIX")
        guard let value = local.date(from: DateComponents(
            year: year, month: month, day: day, hour: 0, minute: 0, second: 0
        )) else { return nil }
        let actual = local.dateComponents([.year, .month, .day], from: value)
        guard actual.year == year, actual.month == month, actual.day == day else { return nil }
        return local.startOfDay(for: value)
    }

    private static func pauseOrder(_ lhs: PaidPauseFactV2, _ rhs: PaidPauseFactV2) -> Bool {
        if lhs.start != rhs.start { return lhs.start < rhs.start }
        return (lhs.end ?? .distantPast) < (rhs.end ?? .distantPast)
    }

    private static func bits(_ date: Date) -> String {
        String(date.timeIntervalSince1970.bitPattern)
    }

    private static func normalized(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func append(_ value: String, to output: inout String) {
        output += "\(value.utf8.count):\(value)|"
    }
}

enum SalaryPayrollCoverageStoreV2 {
    static let storageKey = "hp_travail_payroll_coverage_v2"
    private static let schemaVersion = 1

    private struct Envelope: Codable {
        let schemaVersion: Int
        let items: [SalaryPayrollCoverageAttestationV2]
    }

    struct ReadResult: Equatable {
        let attestations: [SalaryPayrollCoverageAttestationV2]
        let reliable: Bool
        let warnings: [String]
    }

    @discardableResult
    static func saveConfirmed(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        now: Date = Date(),
        defaults: UserDefaults = .standard
    ) -> SalaryPayrollCoverageAttestationV2? {
        let employer = normalized(employerId)
        let zone = normalized(timeZoneId)
        guard work.reliable,
              !employer.isEmpty,
              checkedAt <= now,
              SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay: coveredEndEpochDay,
                checkedAt: checkedAt,
                timeZoneId: zone
              ),
              let fingerprint = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: work,
                employerId: employer,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: zone
              ) else { return nil }

        let stored = read(defaults: defaults)
        guard stored.reliable else { return nil }

        let item = SalaryPayrollCoverageAttestationV2(
            id: UUID(),
            employerId: employer,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAt: checkedAt,
            timeZoneId: zone,
            sessionFingerprint: fingerprint
        )
        let next = stored.attestations.filter {
            !($0.employerId == employer &&
              $0.coveredStartEpochDay == coveredStartEpochDay &&
              $0.coveredEndEpochDay == coveredEndEpochDay &&
              $0.timeZoneId == zone)
        } + [item]
        guard let data = encode(next) else { return nil }
        defaults.set(data, forKey: storageKey)
        let verified = read(defaults: defaults)
        return verified.reliable && verified.attestations.contains(item) ? item : nil
    }

    static func read(defaults: UserDefaults = .standard) -> ReadResult {
        guard let data = defaults.data(forKey: storageKey) else {
            return .init(attestations: [], reliable: true, warnings: [])
        }
        return decode(data)
    }

    static func resolve(
        attestations: [SalaryPayrollCoverageAttestationV2],
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> SalaryPayrollCoverageResolutionV2 {
        let employer = normalized(employerId)
        let zone = normalized(timeZoneId)
        guard work.reliable,
              !employer.isEmpty,
              !zone.isEmpty,
              now.timeIntervalSince1970.isFinite,
              requestedEndEpochDay >= requestedStartEpochDay,
              attestations.allSatisfy(SalaryPayrollCoverageAttestationPolicyV2.isStructurallyValid)
        else {
            return corruptResolution(
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: work.reliable
                    ? [SalaryPayrollCoverageAttestationPolicyV2.corruptWarning]
                    : [SalaryPaidWorkAggregatorV2.sourceWarning]
            )
        }

        let relevant = attestations.filter {
            $0.employerId == employer &&
                $0.timeZoneId == zone &&
                $0.coveredEndEpochDay >= requestedStartEpochDay &&
                $0.coveredStartEpochDay <= requestedEndEpochDay
        }
        if relevant.contains(where: { $0.checkedAt > now }) {
            return corruptResolution(
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: [SalaryPayrollCoverageAttestationPolicyV2.corruptWarning]
            )
        }

        let current = relevant.filter {
            SalaryPayrollCoverageAttestationPolicyV2.isCurrent($0, work: work, now: now)
        }
        let stalePresent = relevant.count != current.count

        var cursor = requestedStartEpochDay
        var used: [SalaryPayrollCoverageAttestationV2] = []
        while cursor <= requestedEndEpochDay {
            let candidates = current.filter {
                $0.coveredStartEpochDay <= cursor && $0.coveredEndEpochDay >= cursor
            }
            guard let candidate = candidates.max(by: {
                if $0.coveredEndEpochDay != $1.coveredEndEpochDay {
                    return $0.coveredEndEpochDay < $1.coveredEndEpochDay
                }
                return $0.checkedAt < $1.checkedAt
            }) else { break }
            used.append(candidate)
            if candidate.coveredEndEpochDay == Int64.max {
                cursor = Int64.max
                break
            }
            cursor = candidate.coveredEndEpochDay + 1
        }

        let exhaustive = cursor > requestedEndEpochDay
        var warnings: [String] = []
        if stalePresent { warnings.append(SalaryPayrollCoverageAttestationPolicyV2.staleWarning) }
        if !exhaustive { warnings.append(SalaryPayrollCoverageAttestationPolicyV2.missingWarning) }

        let checkedAt = exhaustive ? (used.map(\.checkedAt).max() ?? now) : now
        let sourceId = exhaustive
            ? "coverage-v1:" + Array(Set(used.map { $0.id.uuidString })).sorted().joined(separator: ",")
            : ""

        return .init(
            reliable: true,
            exhaustive: exhaustive,
            coveredStartEpochDay: requestedStartEpochDay,
            coveredEndEpochDay: requestedEndEpochDay,
            checkedAt: checkedAt,
            sourceId: sourceId,
            warnings: unique(warnings)
        )
    }

    static func resolve(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults = .standard
    ) -> SalaryPayrollCoverageResolutionV2 {
        let stored = read(defaults: defaults)
        guard stored.reliable else {
            return corruptResolution(
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: stored.warnings
            )
        }
        return resolve(
            attestations: stored.attestations,
            work: work,
            employerId: employerId,
            requestedStartEpochDay: requestedStartEpochDay,
            requestedEndEpochDay: requestedEndEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
    }

    static func source(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults = .standard
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let coverage = resolve(
            work: work,
            employerId: employerId,
            requestedStartEpochDay: coveredStartEpochDay,
            requestedEndEpochDay: coveredEndEpochDay,
            timeZoneId: timeZoneId,
            now: now,
            defaults: defaults
        )
        return SalarySegmentedPayrollSessionSourceV2(
            employerId: normalized(employerId),
            work: work,
            sourceId: coverage.sourceId.isEmpty ? "runtime-unattested" : coverage.sourceId,
            exhaustive: coverage.reliable && coverage.exhaustive,
            coveredStartEpochDay: coverage.coveredStartEpochDay,
            coveredEndEpochDay: coverage.coveredEndEpochDay,
            checkedAt: coverage.checkedAt,
            timeZoneId: normalized(timeZoneId),
            warnings: unique(coverage.warnings)
        )
    }

    static func decode(_ data: Data) -> ReadResult {
        guard let payload = try? JSONDecoder().decode(Envelope.self, from: data),
              payload.schemaVersion == schemaVersion,
              Set(payload.items.map(\.id)).count == payload.items.count,
              payload.items.allSatisfy(SalaryPayrollCoverageAttestationPolicyV2.isStructurallyValid)
        else {
            return .init(
                attestations: [],
                reliable: false,
                warnings: [SalaryPayrollCoverageAttestationPolicyV2.corruptWarning]
            )
        }
        return .init(attestations: sorted(payload.items), reliable: true, warnings: [])
    }

    static func encode(_ items: [SalaryPayrollCoverageAttestationV2]) -> Data? {
        guard Set(items.map(\.id)).count == items.count,
              items.allSatisfy(SalaryPayrollCoverageAttestationPolicyV2.isStructurallyValid)
        else { return nil }
        return try? JSONEncoder().encode(
            Envelope(schemaVersion: schemaVersion, items: sorted(items))
        )
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: storageKey)
    }

    private static func corruptResolution(
        start: Int64,
        end: Int64,
        now: Date,
        warnings: [String]
    ) -> SalaryPayrollCoverageResolutionV2 {
        .init(
            reliable: false,
            exhaustive: false,
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            checkedAt: now,
            sourceId: "",
            warnings: unique(warnings)
        )
    }

    private static func sorted(
        _ items: [SalaryPayrollCoverageAttestationV2]
    ) -> [SalaryPayrollCoverageAttestationV2] {
        items.sorted {
            if $0.employerId != $1.employerId { return $0.employerId < $1.employerId }
            if $0.coveredStartEpochDay != $1.coveredStartEpochDay {
                return $0.coveredStartEpochDay < $1.coveredStartEpochDay
            }
            if $0.coveredEndEpochDay != $1.coveredEndEpochDay {
                return $0.coveredEndEpochDay < $1.coveredEndEpochDay
            }
            if $0.checkedAt != $1.checkedAt { return $0.checkedAt < $1.checkedAt }
            return $0.id.uuidString < $1.id.uuidString
        }
    }

    private static func normalized(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
