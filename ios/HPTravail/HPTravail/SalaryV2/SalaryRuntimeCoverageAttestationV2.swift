import CryptoKit
import Foundation
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

enum SalaryRuntimeCoverageClaimOriginV2 {
    case userReviewedClosedPeriod
    case localBackupRestore
    case cloudBackupRestore
    case legacyMigration
    case storageRead
}

enum SalaryRuntimeCoverageClaimPolicyV2 {
    static func mayIssue(_ origin: SalaryRuntimeCoverageClaimOriginV2) -> Bool {
        origin == .userReviewedClosedPeriod
    }
}

struct SalaryRuntimeCoverageAttestationV2: Codable, Equatable {
    let sourceId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    let journalDigest: String
}

enum SalaryRuntimeCoverageAttestationPolicyV2 {
    static let warning = "Preuves B21 : attestation exhaustive des pointages absente, périmée ou incohérente."

    static func create(
        sessions: [WorkSession],
        sourceId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        now: Date
    ) -> SalaryRuntimeCoverageAttestationV2? {
        guard !sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              checkedAt <= now, coveredEndEpochDay >= coveredStartEpochDay,
              let bounds = bounds(start: coveredStartEpochDay, end: coveredEndEpochDay, timeZoneId: timeZoneId),
              bounds.1 <= checkedAt,
              let relevant = relevant(sessions, from: bounds.0, to: bounds.1),
              relevant.allSatisfy({ $0.exit != nil }) else { return nil }
        return .init(sourceId: sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
                     coveredStartEpochDay: coveredStartEpochDay,
                     coveredEndEpochDay: coveredEndEpochDay,
                     checkedAt: checkedAt, timeZoneId: timeZoneId, journalDigest: digest(relevant))
    }

    static func validate(
        _ attestation: SalaryRuntimeCoverageAttestationV2,
        sessions: [WorkSession],
        now: Date
    ) -> Bool {
        guard !attestation.sourceId.isEmpty, attestation.checkedAt <= now,
              attestation.coveredEndEpochDay >= attestation.coveredStartEpochDay,
              let bounds = bounds(start: attestation.coveredStartEpochDay,
                                  end: attestation.coveredEndEpochDay,
                                  timeZoneId: attestation.timeZoneId),
              bounds.1 <= attestation.checkedAt,
              let relevant = relevant(sessions, from: bounds.0, to: bounds.1),
              relevant.allSatisfy({ $0.exit != nil }) else { return false }
        return digest(relevant) == attestation.journalDigest
    }

    private static func bounds(start: Int64, end: Int64, timeZoneId: String) -> (Date, Date)? {
        guard let timeZone = TimeZone(identifier: timeZoneId), end < Int64.max else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        func localStart(_ epochDay: Int64) -> Date? {
            var utc = Calendar(identifier: .gregorian)
            utc.timeZone = TimeZone(secondsFromGMT: 0)!
            let base = Date(timeIntervalSince1970: Double(epochDay) * 86400)
            let c = utc.dateComponents([.year, .month, .day], from: base)
            return calendar.date(from: DateComponents(year: c.year, month: c.month, day: c.day,
                                                       hour: 0, minute: 0, second: 0))
        }
        guard let from = localStart(start), let to = localStart(end + 1), to > from else { return nil }
        return (from, to)
    }

    private static func relevant(_ sessions: [WorkSession], from: Date, to: Date) -> [WorkSession]? {
        var result: [WorkSession] = []
        for session in sessions {
            if session.entry < to && (session.exit == nil || session.exit! > from) { result.append(session) }
        }
        return result.sorted { lhs, rhs in
            lhs.entry == rhs.entry ? lhs.id.uuidString < rhs.id.uuidString : lhs.entry < rhs.entry
        }
    }

    static func digest(_ sessions: [WorkSession]) -> String {
        var canonical = ""
        for session in sessions {
            canonical += "\(session.id.uuidString)|\(session.employerId ?? "")|\(session.entry.timeIntervalSince1970)|"
            canonical += "\(session.exit?.timeIntervalSince1970.description ?? "nil")|"
            for pause in session.pauses.sorted(by: {
                $0.start == $1.start ? $0.id.uuidString < $1.id.uuidString : $0.start < $1.start
            }) {
                canonical += "P:\(pause.id.uuidString):\(pause.start.timeIntervalSince1970):"
                canonical += "\(pause.end?.timeIntervalSince1970.description ?? "nil"):\(pause.paid?.description ?? "nil")|"
            }
            canonical += "\n"
        }
        return SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

enum SalaryRuntimeCoverageAttestationStoreV2 {
    static let key = "hp_travail_runtime_coverage_attestation_v1"

    static func confirm(
        defaults: UserDefaults,
        sessions: [WorkSession],
        storageReliable: Bool,
        origin: SalaryRuntimeCoverageClaimOriginV2,
        sourceId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        now: Date = Date()
    ) -> Bool {
        guard SalaryRuntimeCoverageClaimPolicyV2.mayIssue(origin),
              storageReliable,
              let attestation = SalaryRuntimeCoverageAttestationPolicyV2.create(
                sessions: sessions, sourceId: sourceId,
                coveredStartEpochDay: coveredStartEpochDay, coveredEndEpochDay: coveredEndEpochDay,
                checkedAt: checkedAt, timeZoneId: timeZoneId, now: now),
              let data = try? JSONEncoder().encode(attestation) else { return false }
        defaults.set(data, forKey: key)
        return read(defaults: defaults, sessions: sessions, storageReliable: storageReliable, now: now) == attestation
    }

    static func read(
        defaults: UserDefaults,
        sessions: [WorkSession],
        storageReliable: Bool,
        now: Date = Date()
    ) -> SalaryRuntimeCoverageAttestationV2? {
        guard storageReliable,
              let data = defaults.data(forKey: key),
              let attestation = try? JSONDecoder().decode(SalaryRuntimeCoverageAttestationV2.self, from: data),
              SalaryRuntimeCoverageAttestationPolicyV2.validate(attestation, sessions: sessions, now: now)
        else { return nil }
        return attestation
    }

    static func source(
        defaults: UserDefaults,
        sessions: [WorkSession],
        storageReliable: Bool,
        employerId: String,
        requiredStartEpochDay: Int64,
        requiredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2? {
        guard let attestation = read(defaults: defaults, sessions: sessions,
                                     storageReliable: storageReliable, now: now),
              attestation.timeZoneId == timeZoneId,
              attestation.coveredStartEpochDay <= requiredStartEpochDay,
              attestation.coveredEndEpochDay >= requiredEndEpochDay else { return nil }
        return .init(
            employerId: employerId,
            work: SalaryWorkSessionBridgeV2.source(from: sessions, storageReliable: storageReliable),
            sourceId: attestation.sourceId,
            exhaustive: true,
            coveredStartEpochDay: attestation.coveredStartEpochDay,
            coveredEndEpochDay: attestation.coveredEndEpochDay,
            checkedAt: attestation.checkedAt,
            timeZoneId: attestation.timeZoneId
        )
    }
}
