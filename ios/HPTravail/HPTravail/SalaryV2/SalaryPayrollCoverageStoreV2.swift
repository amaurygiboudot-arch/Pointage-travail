import CryptoKit
import CoreFoundation
import Foundation

struct SalaryPayrollCoverageAttestationV2: Equatable {
    let id: String
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAtMs: Int64
    let timeZoneId: String
    let sessionFingerprint: String
}

struct SalaryPayrollCoverageReadResultV2: Equatable {
    let attestations: [SalaryPayrollCoverageAttestationV2]
    let reliable: Bool
    let warnings: [String]
}

struct SalaryPayrollCoverageResolutionV2: Equatable {
    let reliable: Bool
    let exhaustive: Bool
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAtMs: Int64
    let sourceId: String
    let warnings: [String]
}

enum SalaryPayrollCoveragePolicyV2 {
    static let missingWarning =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    static let staleWarning =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."
    static let corruptWarning =
        "Preuves B21 : registre d'attestations illisible ou incohérent ; exhaustivité non prouvée."

    private static let minEpochDay: Int64 = -25_567
    private static let maxEpochDay: Int64 = 84_370

    static func fingerprint(
        sessions: [SalarySessionFactV2],
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String
    ) -> String? {
        let employer = normalize(employerId)
        guard !employer.isEmpty,
              (minEpochDay...maxEpochDay).contains(coveredStartEpochDay),
              (minEpochDay...maxEpochDay).contains(coveredEndEpochDay),
              coveredEndEpochDay >= coveredStartEpochDay,
              let zone = TimeZone(identifier: normalize(timeZoneId)),
              let from = localStart(coveredStartEpochDay, zone: zone),
              coveredEndEpochDay < Int64.max,
              let to = localStart(coveredEndEpochDay + 1, zone: zone),
              to > from else { return nil }

        let relevant = sessions.filter { session in
            let sessionEmployer = normalize(session.employerId)
            return (sessionEmployer.isEmpty || sessionEmployer == employer) &&
                touches(session, from: from, to: to)
        }.sorted {
            if normalize($0.id) != normalize($1.id) { return normalize($0.id) < normalize($1.id) }
            if $0.entry != $1.entry { return $0.entry < $1.entry }
            return ($0.exit ?? .distantPast) < ($1.exit ?? .distantPast)
        }

        var canonical = ""
        field("coverage-v1", into: &canonical)
        field(employer, into: &canonical)
        field(coveredStartEpochDay, into: &canonical)
        field(coveredEndEpochDay, into: &canonical)
        field(zone.identifier, into: &canonical)
        for session in relevant {
            field("session", into: &canonical)
            field(normalize(session.id), into: &canonical)
            field(normalize(session.employerId), into: &canonical)
            field(epochMillis(session.entry), into: &canonical)
            field(session.exit.map(epochMillis), into: &canonical)
            for pause in session.pauses.sorted(by: pauseLessThan) {
                field("pause", into: &canonical)
                field(epochMillis(pause.start), into: &canonical)
                field(pause.end.map(epochMillis), into: &canonical)
                field(pause.paid.map(String.init), into: &canonical)
            }
            field("end-session", into: &canonical)
        }
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func structurallyValid(_ item: SalaryPayrollCoverageAttestationV2) -> Bool {
        guard !normalize(item.id).isEmpty,
              !normalize(item.employerId).isEmpty,
              (minEpochDay...maxEpochDay).contains(item.coveredStartEpochDay),
              (minEpochDay...maxEpochDay).contains(item.coveredEndEpochDay),
              item.coveredEndEpochDay >= item.coveredStartEpochDay,
              item.checkedAtMs > 0,
              !normalize(item.sessionFingerprint).isEmpty,
              TimeZone(identifier: normalize(item.timeZoneId)) != nil else { return false }
        return coverageClosedBeforeCheck(
            coveredEndEpochDay: item.coveredEndEpochDay,
            checkedAtMs: item.checkedAtMs,
            timeZoneId: item.timeZoneId
        )
    }

    static func current(
        _ item: SalaryPayrollCoverageAttestationV2,
        sessions: [SalarySessionFactV2],
        nowMs: Int64
    ) -> Bool {
        guard structurallyValid(item), nowMs > 0, item.checkedAtMs <= nowMs,
              let value = fingerprint(
                sessions: sessions,
                employerId: item.employerId,
                coveredStartEpochDay: item.coveredStartEpochDay,
                coveredEndEpochDay: item.coveredEndEpochDay,
                timeZoneId: item.timeZoneId
              ) else { return false }
        return value == item.sessionFingerprint
    }

    static func coverageClosedBeforeCheck(
        coveredEndEpochDay: Int64,
        checkedAtMs: Int64,
        timeZoneId: String
    ) -> Bool {
        guard (minEpochDay...maxEpochDay).contains(coveredEndEpochDay),
              checkedAtMs > 0,
              coveredEndEpochDay < Int64.max,
              let zone = TimeZone(identifier: normalize(timeZoneId)),
              let endExclusive = localStart(coveredEndEpochDay + 1, zone: zone)
        else { return false }
        return epochMillis(endExclusive) <= checkedAtMs
    }

    private static func touches(_ session: SalarySessionFactV2, from: Date, to: Date) -> Bool {
        guard session.entry.timeIntervalSince1970.isFinite else { return true }
        guard let exit = session.exit else { return session.entry < to }
        guard exit.timeIntervalSince1970.isFinite else { return true }
        if exit == session.entry { return session.entry >= from && session.entry < to }
        return min(session.entry, exit) < to && max(session.entry, exit) > from
    }

    private static func localStart(_ epochDay: Int64, zone: TimeZone) -> Date? {
        guard (minEpochDay...(maxEpochDay + 1)).contains(epochDay) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let utcDate = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let c = utc.dateComponents([.year, .month, .day], from: utcDate)
        guard let y = c.year, let m = c.month, let d = c.day else { return nil }
        var local = Calendar(identifier: .gregorian)
        local.timeZone = zone
        guard let value = local.date(from: DateComponents(year: y, month: m, day: d)) else { return nil }
        let actual = local.dateComponents([.year, .month, .day], from: value)
        guard actual.year == y, actual.month == m, actual.day == d else { return nil }
        return local.startOfDay(for: value)
    }

    private static func epochMillis(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func pauseLessThan(_ lhs: PaidPauseFactV2, _ rhs: PaidPauseFactV2) -> Bool {
        if lhs.start != rhs.start { return lhs.start < rhs.start }
        return (lhs.end ?? .distantPast) < (rhs.end ?? .distantPast)
    }

    private static func field(_ value: Any?, into output: inout String) {
        let raw: String
        switch value {
        case nil: raw = "<null>"
        case let value as String?: raw = value ?? "<null>"
        default: raw = String(describing: value!)
        }
        output += "\(raw.utf8.count):\(raw)|"
    }

    private static func normalize(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}

enum SalaryPayrollCoverageStoreV2 {
    private static let key = "salary_payroll_coverage_v2.confirmed_ranges"
    private static let schemaVersion = 1

    static func read(defaults: UserDefaults = .standard) -> SalaryPayrollCoverageReadResultV2 {
        guard let object = defaults.object(forKey: key) else {
            return .init(attestations: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else { return corrupt() }
        return decode(raw)
    }

    @discardableResult
    static func saveConfirmed(
        defaults: UserDefaults = .standard,
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalaryPayrollCoverageAttestationV2? {
        let employer = normalize(employerId)
        let zone = normalize(timeZoneId)
        let checkedAtMs = epochMillis(checkedAt)
        let nowMs = epochMillis(now)
        guard work.reliable, !employer.isEmpty, checkedAtMs > 0, checkedAtMs <= nowMs,
              SalaryPayrollCoveragePolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay: coveredEndEpochDay,
                checkedAtMs: checkedAtMs,
                timeZoneId: zone
              ) else { return nil }
        let stored = read(defaults: defaults)
        guard stored.reliable,
              let fingerprint = SalaryPayrollCoveragePolicyV2.fingerprint(
                sessions: work.sessions,
                employerId: employer,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: zone
              ) else { return nil }

        let item = SalaryPayrollCoverageAttestationV2(
            id: UUID().uuidString,
            employerId: employer,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAtMs: checkedAtMs,
            timeZoneId: zone,
            sessionFingerprint: fingerprint
        )
        let next = stored.attestations.filter {
            !($0.employerId == employer &&
              $0.coveredStartEpochDay == coveredStartEpochDay &&
              $0.coveredEndEpochDay == coveredEndEpochDay &&
              $0.timeZoneId == zone)
        } + [item]
        return write(next, defaults: defaults) ? item : nil
    }

    static func resolve(
        defaults: UserDefaults = .standard,
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalaryPayrollCoverageResolutionV2 {
        guard work.reliable else {
            return .init(reliable: false, exhaustive: false,
                         coveredStartEpochDay: requestedStartEpochDay,
                         coveredEndEpochDay: requestedEndEpochDay,
                         checkedAtMs: max(0, epochMillis(now)), sourceId: "",
                         warnings: [SalarySegmentedPayrollSessionEvidenceBuilderV2.sourceWarning])
        }
        let stored = read(defaults: defaults)
        guard stored.reliable else {
            return .init(reliable: false, exhaustive: false,
                         coveredStartEpochDay: requestedStartEpochDay,
                         coveredEndEpochDay: requestedEndEpochDay,
                         checkedAtMs: max(0, epochMillis(now)), sourceId: "",
                         warnings: stored.warnings)
        }
        return resolve(attestations: stored.attestations, work: work,
                       employerId: employerId,
                       requestedStartEpochDay: requestedStartEpochDay,
                       requestedEndEpochDay: requestedEndEpochDay,
                       timeZoneId: timeZoneId, nowMs: epochMillis(now))
    }

    static func source(
        defaults: UserDefaults = .standard,
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let coverage = resolve(defaults: defaults, work: work, employerId: employerId,
                               requestedStartEpochDay: coveredStartEpochDay,
                               requestedEndEpochDay: coveredEndEpochDay,
                               timeZoneId: timeZoneId, now: now)
        return .init(employerId: normalize(employerId), work: work,
                     sourceId: coverage.sourceId.isEmpty ? "runtime-unattested" : coverage.sourceId,
                     exhaustive: coverage.exhaustive,
                     coveredStartEpochDay: coverage.coveredStartEpochDay,
                     coveredEndEpochDay: coverage.coveredEndEpochDay,
                     checkedAt: Date(timeIntervalSince1970: Double(coverage.checkedAtMs) / 1000),
                     timeZoneId: normalize(timeZoneId),
                     warnings: coverage.warnings)
    }

    static func resolve(
        attestations: [SalaryPayrollCoverageAttestationV2],
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        nowMs: Int64
    ) -> SalaryPayrollCoverageResolutionV2 {
        let employer = normalize(employerId)
        let zone = normalize(timeZoneId)
        guard work.reliable, !employer.isEmpty, !zone.isEmpty, nowMs > 0,
              requestedEndEpochDay >= requestedStartEpochDay,
              attestations.allSatisfy(SalaryPayrollCoveragePolicyV2.structurallyValid)
        else { return corruptResolution(requestedStartEpochDay, requestedEndEpochDay, nowMs) }

        let relevant = attestations.filter {
            $0.employerId == employer && $0.timeZoneId == zone &&
            $0.coveredEndEpochDay >= requestedStartEpochDay &&
            $0.coveredStartEpochDay <= requestedEndEpochDay
        }
        if relevant.contains(where: { $0.checkedAtMs > nowMs }) {
            return corruptResolution(requestedStartEpochDay, requestedEndEpochDay, nowMs)
        }

        let current = relevant.filter {
            SalaryPayrollCoveragePolicyV2.current($0, sessions: work.sessions, nowMs: nowMs)
        }
        let stalePresent = relevant.count != current.count
        var cursor = requestedStartEpochDay
        var used: [SalaryPayrollCoverageAttestationV2] = []
        while cursor <= requestedEndEpochDay {
            guard let candidate = current.filter({
                $0.coveredStartEpochDay <= cursor && $0.coveredEndEpochDay >= cursor
            }).max(by: {
                if $0.coveredEndEpochDay != $1.coveredEndEpochDay {
                    return $0.coveredEndEpochDay < $1.coveredEndEpochDay
                }
                return $0.checkedAtMs < $1.checkedAtMs
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
        if stalePresent { warnings.append(SalaryPayrollCoveragePolicyV2.staleWarning) }
        if !exhaustive { warnings.append(SalaryPayrollCoveragePolicyV2.missingWarning) }
        let checkedAtMs = exhaustive ? (used.map(\.checkedAtMs).max() ?? nowMs) : nowMs
        let sourceId = exhaustive
            ? "coverage-v1:" + Array(Set(used.map(\.id))).sorted().joined(separator: ",")
            : ""
        return .init(reliable: true, exhaustive: exhaustive,
                     coveredStartEpochDay: requestedStartEpochDay,
                     coveredEndEpochDay: requestedEndEpochDay,
                     checkedAtMs: checkedAtMs, sourceId: sourceId,
                     warnings: unique(warnings))
    }

    static func decode(_ raw: String) -> SalaryPayrollCoverageReadResultV2 {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = root["schemaVersion"] as? NSNumber,
              !isBool(version), version.intValue == schemaVersion,
              let items = root["items"] as? [[String: Any]]
        else { return corrupt() }

        var parsed: [SalaryPayrollCoverageAttestationV2] = []
        var ids = Set<String>()
        for object in items {
            guard let item = decodeItem(object),
                  ids.insert(item.id).inserted else { return corrupt() }
            parsed.append(item)
        }
        return .init(attestations: parsed, reliable: true, warnings: [])
    }

    static func encode(_ items: [SalaryPayrollCoverageAttestationV2]) -> String? {
        guard Set(items.map(\.id)).count == items.count,
              items.allSatisfy(SalaryPayrollCoveragePolicyV2.structurallyValid)
        else { return nil }
        let encoded: [[String: Any]] = items.sorted(by: sortItems).map {
            [
                "id": $0.id,
                "employerId": $0.employerId,
                "coveredStartEpochDay": $0.coveredStartEpochDay,
                "coveredEndEpochDay": $0.coveredEndEpochDay,
                "checkedAtMs": $0.checkedAtMs,
                "timeZoneId": $0.timeZoneId,
                "sessionFingerprint": $0.sessionFingerprint
            ]
        }
        let root: [String: Any] = ["schemaVersion": schemaVersion, "items": encoded]
        guard JSONSerialization.isValidJSONObject(root),
              let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return raw
    }

    private static func write(
        _ items: [SalaryPayrollCoverageAttestationV2],
        defaults: UserDefaults
    ) -> Bool {
        guard let raw = encode(items) else { return false }
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }
        return read(defaults: defaults).reliable
    }

    private static func decodeItem(_ object: [String: Any]) -> SalaryPayrollCoverageAttestationV2? {
        guard let id = object["id"] as? String,
              let employerId = object["employerId"] as? String,
              let start = int64(object["coveredStartEpochDay"]),
              let end = int64(object["coveredEndEpochDay"]),
              let checkedAt = int64(object["checkedAtMs"]),
              let zone = object["timeZoneId"] as? String,
              let fingerprint = object["sessionFingerprint"] as? String
        else { return nil }
        let value = SalaryPayrollCoverageAttestationV2(
            id: id, employerId: employerId, coveredStartEpochDay: start,
            coveredEndEpochDay: end, checkedAtMs: checkedAt,
            timeZoneId: zone, sessionFingerprint: fingerprint)
        return SalaryPayrollCoveragePolicyV2.structurallyValid(value) ? value : nil
    }

    private static func sortItems(
        _ lhs: SalaryPayrollCoverageAttestationV2,
        _ rhs: SalaryPayrollCoverageAttestationV2
    ) -> Bool {
        (lhs.employerId, lhs.coveredStartEpochDay, lhs.coveredEndEpochDay, lhs.checkedAtMs, lhs.id) <
        (rhs.employerId, rhs.coveredStartEpochDay, rhs.coveredEndEpochDay, rhs.checkedAtMs, rhs.id)
    }

    private static func int64(_ raw: Any?) -> Int64? {
        guard let n = raw as? NSNumber, !isBool(n),
              n.doubleValue.isFinite, n.doubleValue.rounded() == n.doubleValue
        else { return nil }
        return n.int64Value
    }

    private static func epochMillis(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func normalize(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    private static func corrupt() -> SalaryPayrollCoverageReadResultV2 {
        .init(attestations: [], reliable: false,
              warnings: [SalaryPayrollCoveragePolicyV2.corruptWarning])
    }

    private static func corruptResolution(_ start: Int64, _ end: Int64, _ nowMs: Int64)
        -> SalaryPayrollCoverageResolutionV2 {
        .init(reliable: false, exhaustive: false,
              coveredStartEpochDay: start, coveredEndEpochDay: end,
              checkedAtMs: max(0, nowMs), sourceId: "",
              warnings: [SalaryPayrollCoveragePolicyV2.corruptWarning])
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
