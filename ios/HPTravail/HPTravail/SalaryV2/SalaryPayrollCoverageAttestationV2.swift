import Foundation
import CryptoKit

struct SalaryPayrollCoverageAttestationV2: Codable, Equatable {
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    let sourceId: String
    let sessionFingerprint: String
}

enum SalaryPayrollCoverageAttestationPolicyV2 {
    static let missingWarning =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    static let staleWarning =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."

    static func fingerprint(
        work: SalaryWorkSessionSourceV2,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String
    ) -> String? {
        guard coveredEndEpochDay >= coveredStartEpochDay,
              let timeZone = TimeZone(identifier: timeZoneId),
              let from = localStart(coveredStartEpochDay, timeZone: timeZone),
              coveredEndEpochDay < Int64.max,
              let to = localStart(coveredEndEpochDay + 1, timeZone: timeZone),
              to > from else { return nil }

        let relevant = work.sessions.filter { touches($0, from: from, to: to) }
            .sorted { normalized($0.id) < normalized($1.id) }
        var canonical = ""
        append("v1", to: &canonical)
        append(String(coveredStartEpochDay), to: &canonical)
        append(String(coveredEndEpochDay), to: &canonical)
        append(timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines), to: &canonical)
        for session in relevant {
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
        }
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func isValid(
        _ attestation: SalaryPayrollCoverageAttestationV2,
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> Bool {
        guard now.timeIntervalSince1970.isFinite,
              attestation.checkedAt.timeIntervalSince1970.isFinite,
              attestation.checkedAt <= now,
              normalized(attestation.employerId) == normalized(employerId),
              !normalized(employerId).isEmpty,
              attestation.coveredStartEpochDay == coveredStartEpochDay,
              attestation.coveredEndEpochDay == coveredEndEpochDay,
              normalized(attestation.timeZoneId) == normalized(timeZoneId),
              !normalized(attestation.sourceId).isEmpty,
              !normalized(attestation.sessionFingerprint).isEmpty,
              let current = fingerprint(
                work: work,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId
              ) else { return false }
        return current == attestation.sessionFingerprint
    }

    static func coverageClosedBeforeCheck(
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String
    ) -> Bool {
        guard coveredEndEpochDay < Int64.max,
              let zone = TimeZone(identifier: timeZoneId),
              let end = localStart(coveredEndEpochDay + 1, timeZone: zone) else { return false }
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
        guard (-25567...84370).contains(epochDay) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let utcDate = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        let components = utc.dateComponents([.year, .month, .day], from: utcDate)
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        guard let value = local.date(from: DateComponents(
            year: components.year, month: components.month, day: components.day,
            hour: 0, minute: 0, second: 0
        )) else { return nil }
        return local.startOfDay(for: value)
    }

    private static func pauseOrder(_ lhs: PaidPauseFactV2, _ rhs: PaidPauseFactV2) -> Bool {
        if lhs.start != rhs.start { return lhs.start < rhs.start }
        return (lhs.end ?? .distantFuture) < (rhs.end ?? .distantFuture)
    }

    private static func bits(_ date: Date) -> String {
        String(date.timeIntervalSince1970.bitPattern)
    }

    private static func normalized(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func append(_ value: String, to output: inout String) {
        output += "\(value.utf8.count):\(value)|"
    }
}

enum SalaryPayrollCoverageStoreV2 {
    private static let key = "salary_payroll_coverage_v2"
    private static let schemaVersion = 1

    private struct Envelope: Codable {
        let schemaVersion: Int
        let items: [SalaryPayrollCoverageAttestationV2]
    }

    static func saveConfirmed(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults
    ) -> SalaryPayrollCoverageAttestationV2? {
        guard work.reliable,
              checkedAt <= now,
              SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay: coveredEndEpochDay, checkedAt: checkedAt, timeZoneId: timeZoneId
              ),
              let fingerprint = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: work,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId
              ) else { return nil }
        let employer = employerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employer.isEmpty else { return nil }
        let item = SalaryPayrollCoverageAttestationV2(
            employerId: employer,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAt: checkedAt,
            timeZoneId: timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines),
            sourceId: "coverage:" + UUID().uuidString,
            sessionFingerprint: fingerprint
        )
        var items = read(defaults: defaults).filter {
            !($0.employerId == item.employerId &&
              $0.coveredStartEpochDay == item.coveredStartEpochDay &&
              $0.coveredEndEpochDay == item.coveredEndEpochDay &&
              $0.timeZoneId == item.timeZoneId)
        }
        items.append(item)
        guard let data = try? JSONEncoder().encode(Envelope(schemaVersion: schemaVersion, items: items)) else { return nil }
        defaults.set(data, forKey: key)
        return read(defaults: defaults).contains(item) ? item : nil
    }

    static func source(
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let employer = employerId.trimmingCharacters(in: .whitespacesAndNewlines)
        let item = read(defaults: defaults)
            .filter {
                $0.employerId == employer &&
                $0.coveredStartEpochDay == coveredStartEpochDay &&
                $0.coveredEndEpochDay == coveredEndEpochDay &&
                $0.timeZoneId == timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            .max(by: { $0.checkedAt < $1.checkedAt })
        let valid = work.reliable && item.map {
            SalaryPayrollCoverageAttestationPolicyV2.isValid(
                $0, work: work, employerId: employer,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId, now: now
            )
        } == true
        var warnings: [String] = []
        if item == nil { warnings.append(SalaryPayrollCoverageAttestationPolicyV2.missingWarning) }
        else if !valid { warnings.append(SalaryPayrollCoverageAttestationPolicyV2.staleWarning) }
        return SalarySegmentedPayrollSessionSourceV2(
            employerId: employer,
            work: work,
            sourceId: item?.sourceId ?? "",
            exhaustive: valid,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAt: item?.checkedAt ?? now,
            timeZoneId: timeZoneId,
            warnings: warnings
        )
    }

    static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: key)
    }

    private static func read(defaults: UserDefaults) -> [SalaryPayrollCoverageAttestationV2] {
        guard let data = defaults.data(forKey: key),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == schemaVersion else { return [] }
        return envelope.items
    }
}
