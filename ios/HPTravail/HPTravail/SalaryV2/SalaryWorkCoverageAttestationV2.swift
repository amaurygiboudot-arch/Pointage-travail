import CryptoKit
import Foundation
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

struct SalaryWorkCoverageAttestationV2: Codable, Equatable {
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    let sourceId: String
    let historyFingerprint: String
}

enum SalaryWorkCoverageStoreV2 {
    static let storageKey = "horatrack_salary_work_coverage_v2"
    static let missingWarning =
        "Couverture B21 : aucune attestation exhaustive compatible avec l'historique courant."
    static let corruptWarning =
        "Couverture B21 : registre d'attestations illisible ; exhaustivité non prouvée."

    struct Resolution: Equatable {
        let exhaustive: Bool
        let storeReliable: Bool
        let coveredStartEpochDay: Int64
        let coveredEndEpochDay: Int64
        let checkedAt: Date
        let sourceId: String
        let warnings: [String]
    }

    static func confirmCoverage(
        defaults: UserDefaults,
        sessions: [WorkSession],
        storageReliable: Bool,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        sourceId: String,
        now: Date = Date()
    ) -> Bool {
        let employer = normalized(employerId)
        let source = normalized(sourceId)
        guard storageReliable,
              !employer.isEmpty,
              !source.isEmpty,
              coveredEndEpochDay >= coveredStartEpochDay,
              checkedAt.timeIntervalSince1970.isFinite,
              now.timeIntervalSince1970.isFinite,
              checkedAt > .distantPast,
              checkedAt <= now,
              let timeZone = TimeZone(identifier: timeZoneId),
              coverageWindowClosed(endEpochDay: coveredEndEpochDay, checkedAt: checkedAt, timeZone: timeZone)
        else { return false }

        let read = readStored(defaults: defaults)
        guard read.reliable else { return false }
        let attestation = SalaryWorkCoverageAttestationV2(
            employerId: employer,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAt: checkedAt,
            timeZoneId: timeZone.identifier,
            sourceId: source,
            historyFingerprint: fingerprint(sessions)
        )
        let retained = upsertAttestation(read.attestations, replacement: attestation)
        guard let data = try? JSONEncoder().encode(retained) else { return false }
        defaults.set(data, forKey: storageKey)
        let verified = readStored(defaults: defaults)
        return verified.reliable && verified.attestations == retained
    }

    static func resolve(
        defaults: UserDefaults,
        sessions: [WorkSession],
        storageReliable: Bool,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> Resolution {
        guard storageReliable,
              requestedEndEpochDay >= requestedStartEpochDay,
              now.timeIntervalSince1970.isFinite,
              let timeZone = TimeZone(identifier: timeZoneId)
        else {
            return missing(
                storeReliable: false,
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: [corruptWarning]
            )
        }
        let read = readStored(defaults: defaults)
        guard read.reliable else {
            return missing(
                storeReliable: false,
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: [corruptWarning]
            )
        }
        guard let match = selectMatching(
            attestations: read.attestations,
            historyFingerprint: fingerprint(sessions),
            employerId: employerId,
            requestedStartEpochDay: requestedStartEpochDay,
            requestedEndEpochDay: requestedEndEpochDay,
            timeZoneId: timeZone.identifier,
            now: now
        ) else {
            return missing(
                storeReliable: true,
                start: requestedStartEpochDay,
                end: requestedEndEpochDay,
                now: now,
                warnings: [missingWarning]
            )
        }
        return Resolution(
            exhaustive: true,
            storeReliable: true,
            coveredStartEpochDay: match.coveredStartEpochDay,
            coveredEndEpochDay: match.coveredEndEpochDay,
            checkedAt: match.checkedAt,
            sourceId: match.sourceId,
            warnings: []
        )
    }

    static func upsertAttestation(
        _ attestations: [SalaryWorkCoverageAttestationV2],
        replacement: SalaryWorkCoverageAttestationV2
    ) -> [SalaryWorkCoverageAttestationV2] {
        (attestations.filter {
            !($0.employerId == replacement.employerId
              && $0.sourceId == replacement.sourceId
              && $0.timeZoneId == replacement.timeZoneId
              && $0.coveredStartEpochDay == replacement.coveredStartEpochDay
              && $0.coveredEndEpochDay == replacement.coveredEndEpochDay)
        } + [replacement]).sorted { $0.checkedAt < $1.checkedAt }
    }

    static func validStoredAttestation(_ attestation: SalaryWorkCoverageAttestationV2) -> Bool {
        guard let timeZone = TimeZone(identifier: attestation.timeZoneId) else { return false }
        return !normalized(attestation.employerId).isEmpty
            && !normalized(attestation.sourceId).isEmpty
            && !normalized(attestation.historyFingerprint).isEmpty
            && attestation.coveredEndEpochDay >= attestation.coveredStartEpochDay
            && attestation.checkedAt.timeIntervalSince1970.isFinite
            && attestation.checkedAt > .distantPast
            && coverageWindowClosed(
                endEpochDay: attestation.coveredEndEpochDay,
                checkedAt: attestation.checkedAt,
                timeZone: timeZone
            )
    }

    static func selectMatching(
        attestations: [SalaryWorkCoverageAttestationV2],
        historyFingerprint: String,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> SalaryWorkCoverageAttestationV2? {
        let employer = normalized(employerId)
        let zone = normalized(timeZoneId)
        guard !employer.isEmpty,
              !zone.isEmpty,
              !historyFingerprint.isEmpty,
              requestedEndEpochDay >= requestedStartEpochDay,
              now.timeIntervalSince1970.isFinite else { return nil }
        return attestations
            .filter {
                $0.employerId == employer
                    && $0.timeZoneId == zone
                    && $0.historyFingerprint == historyFingerprint
                    && !normalized($0.sourceId).isEmpty
                    && $0.coveredStartEpochDay <= requestedStartEpochDay
                    && $0.coveredEndEpochDay >= requestedEndEpochDay
                    && $0.checkedAt.timeIntervalSince1970.isFinite
                    && $0.checkedAt <= now
            }
            .max { $0.checkedAt < $1.checkedAt }
    }

    static func fingerprint(_ sessions: [WorkSession]) -> String {
        var canonical = ""
        func field(_ value: String?) {
            let raw = value ?? "<null>"
            canonical += "\(raw.utf8.count):\(raw)|"
        }
        for session in sessions.sorted(by: {
            if $0.id.uuidString != $1.id.uuidString { return $0.id.uuidString < $1.id.uuidString }
            return $0.entry < $1.entry
        }) {
            field("session")
            field(session.id.uuidString)
            field(bits(session.entry))
            field(session.exit.map(bits))
            field(session.employerId)
            field(session.placeLabel)
            for pause in session.pauses.sorted(by: {
                if $0.start != $1.start { return $0.start < $1.start }
                return $0.id.uuidString < $1.id.uuidString
            }) {
                field("pause")
                field(pause.id.uuidString)
                field(bits(pause.start))
                field(pause.end.map(bits))
                field(pause.paid.map(String.init))
            }
            field("end-session")
        }
        return SHA256.hash(data: Data(canonical.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private struct StoredRead {
        let attestations: [SalaryWorkCoverageAttestationV2]
        let reliable: Bool
    }

    private static func readStored(defaults: UserDefaults) -> StoredRead {
        guard let data = defaults.data(forKey: storageKey) else {
            return .init(attestations: [], reliable: true)
        }
        guard let decoded = try? JSONDecoder().decode([SalaryWorkCoverageAttestationV2].self, from: data),
              decoded.allSatisfy(validStoredAttestation)
        else {
            return .init(attestations: [], reliable: false)
        }
        let identities = decoded.map {
            [$0.employerId, $0.timeZoneId, $0.sourceId,
             String($0.coveredStartEpochDay), String($0.coveredEndEpochDay)]
        }
        guard Set(identities).count == identities.count else {
            return .init(attestations: [], reliable: false)
        }
        return .init(attestations: decoded, reliable: true)
    }

    private static func coverageWindowClosed(
        endEpochDay: Int64,
        checkedAt: Date,
        timeZone: TimeZone
    ) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        guard let endStart = localStart(endEpochDay, calendar: calendar),
              let endExclusive = calendar.date(byAdding: .day, value: 1, to: endStart) else {
            return false
        }
        return checkedAt >= endExclusive
    }

    private static func localStart(_ epochDay: Int64, calendar: Calendar) -> Date? {
        let seconds = Double(epochDay) * 86_400
        guard seconds.isFinite else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: seconds))
        guard let year = components.year, let month = components.month, let day = components.day,
              let value = calendar.date(from: DateComponents(year: year, month: month, day: day))
        else { return nil }
        let actual = calendar.dateComponents([.year, .month, .day], from: value)
        guard actual.year == year, actual.month == month, actual.day == day else { return nil }
        return calendar.startOfDay(for: value)
    }

    private static func bits(_ date: Date) -> String {
        String(date.timeIntervalSince1970.bitPattern, radix: 16)
    }

    private static func normalized(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func missing(
        storeReliable: Bool,
        start: Int64,
        end: Int64,
        now: Date,
        warnings: [String]
    ) -> Resolution {
        Resolution(
            exhaustive: false,
            storeReliable: storeReliable,
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            checkedAt: now,
            sourceId: "runtime-unattested",
            warnings: Array(Set(warnings))
        )
    }
}

enum SalarySegmentedPayrollSessionSourceFactoryV2 {
    static func make(
        sessions: [WorkSession],
        storageReliable: Bool,
        defaults: UserDefaults = .standard,
        employerId: String,
        requestedStartEpochDay: Int64,
        requestedEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let work = SalaryWorkSessionBridgeV2.source(
            from: sessions,
            storageReliable: storageReliable
        )
        let coverage = SalaryWorkCoverageStoreV2.resolve(
            defaults: defaults,
            sessions: sessions,
            storageReliable: storageReliable,
            employerId: employerId,
            requestedStartEpochDay: requestedStartEpochDay,
            requestedEndEpochDay: requestedEndEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
        return SalarySegmentedPayrollSessionSourceV2(
            employerId: employerId.trimmingCharacters(in: .whitespacesAndNewlines),
            work: work,
            sourceId: coverage.sourceId,
            exhaustive: coverage.exhaustive,
            coveredStartEpochDay: coverage.coveredStartEpochDay,
            coveredEndEpochDay: coverage.coveredEndEpochDay,
            checkedAt: coverage.checkedAt,
            timeZoneId: timeZoneId,
            warnings: Array(Set(coverage.warnings))
        )
    }
}
