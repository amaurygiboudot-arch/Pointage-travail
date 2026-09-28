import CryptoKit
import Foundation

struct SalarySegmentedPayrollCoverageAttestationV2: Codable, Equatable {
    let id: String
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let confirmedAt: Date
    let timeZoneId: String
    let factFingerprint: String
}

struct SalarySegmentedPayrollCoverageReadV2: Equatable {
    let attestations: [SalarySegmentedPayrollCoverageAttestationV2]
    let reliable: Bool
    let warnings: [String]
}

enum SalarySegmentedPayrollCoverageStoreV2 {
    static let key = "horatrack_v2_payroll_coverage"
    static let storeWarning =
        "Couverture paie V2 illisible ou incohérente : aucune période n'est considérée exhaustive."
    static let missingWarning =
        "Couverture paie V2 non attestée pour toute la période demandée."
    static let staleWarning =
        "Couverture paie V2 invalidée par une modification des pointages de la période."

    private struct Envelope: Codable {
        let schemaVersion: Int
        let items: [SalarySegmentedPayrollCoverageAttestationV2]
    }

    static func read(defaults: UserDefaults = .standard) -> SalarySegmentedPayrollCoverageReadV2 {
        guard let data = defaults.data(forKey: key) else {
            return .init(attestations: [], reliable: true, warnings: [])
        }
        return decode(data)
    }

    /// À appeler seulement après une confirmation explicite de l'exhaustivité.
    @discardableResult
    static func confirm(
        defaults: UserDefaults = .standard,
        work: SalaryWorkSessionSourceV2,
        employerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        confirmedAt: Date = Date(),
        timeZoneId: String,
        id: String
    ) -> Bool {
        let employer = normalized(employerId)
        guard work.reliable, !employer.isEmpty, !normalized(id).isEmpty,
              confirmedAt.timeIntervalSince1970.isFinite,
              let bounds = rangeBounds(
                startEpochDay: coveredStartEpochDay,
                endEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId
              ),
              bounds.to <= confirmedAt,
              let fingerprint = fingerprint(
                sessions: work.sessions,
                startEpochDay: coveredStartEpochDay,
                endEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId,
                openEnd: confirmedAt
              ) else {
            return false
        }

        let current = read(defaults: defaults)
        guard current.reliable else { return false }
        let item = SalarySegmentedPayrollCoverageAttestationV2(
            id: normalized(id),
            employerId: employer,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            confirmedAt: confirmedAt,
            timeZoneId: timeZoneId,
            factFingerprint: fingerprint
        )
        let updated = (current.attestations.filter { $0.id != item.id } + [item]).sorted {
            ($0.coveredStartEpochDay, $0.coveredEndEpochDay, $0.id) <
                ($1.coveredStartEpochDay, $1.coveredEndEpochDay, $1.id)
        }
        guard let data = encode(updated) else { return false }
        defaults.set(data, forKey: key)
        return read(defaults: defaults).attestations == updated
    }

    static func source(
        work: SalaryWorkSessionSourceV2,
        defaults: UserDefaults = .standard,
        employerId: String,
        requiredStartEpochDay: Int64,
        requiredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        resolveSource(
            work: work,
            coverage: read(defaults: defaults),
            employerId: employerId,
            requiredStartEpochDay: requiredStartEpochDay,
            requiredEndEpochDay: requiredEndEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
    }

    static func resolveSource(
        work: SalaryWorkSessionSourceV2,
        coverage: SalarySegmentedPayrollCoverageReadV2,
        employerId: String,
        requiredStartEpochDay: Int64,
        requiredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        var warnings = coverage.warnings
        let employer = normalized(employerId)
        let rangeValid = rangeBounds(
            startEpochDay: requiredStartEpochDay,
            endEpochDay: requiredEndEpochDay,
            timeZoneId: timeZoneId
        ) != nil

        guard work.reliable, coverage.reliable, !employer.isEmpty, rangeValid,
              now.timeIntervalSince1970.isFinite else {
            return .init(
                employerId: employer,
                work: work,
                sourceId: "coverage-unavailable",
                exhaustive: false,
                coveredStartEpochDay: requiredStartEpochDay,
                coveredEndEpochDay: requiredEndEpochDay,
                checkedAt: now,
                timeZoneId: timeZoneId,
                warnings: unique(warnings)
            )
        }

        var staleFound = false
        let valid = coverage.attestations.filter { attestation in
            guard normalized(attestation.employerId) == employer,
                  attestation.timeZoneId == timeZoneId,
                  attestation.confirmedAt.timeIntervalSince1970.isFinite,
                  attestation.confirmedAt <= now,
                  let bounds = rangeBounds(
                    startEpochDay: attestation.coveredStartEpochDay,
                    endEpochDay: attestation.coveredEndEpochDay,
                    timeZoneId: attestation.timeZoneId
                  ),
                  bounds.to <= attestation.confirmedAt else {
                return false
            }
            let current = fingerprint(
                sessions: work.sessions,
                startEpochDay: attestation.coveredStartEpochDay,
                endEpochDay: attestation.coveredEndEpochDay,
                timeZoneId: attestation.timeZoneId,
                openEnd: now
            )
            guard current == attestation.factFingerprint else {
                staleFound = true
                return false
            }
            return true
        }.sorted {
            ($0.coveredStartEpochDay, $0.coveredEndEpochDay, $0.id) <
                ($1.coveredStartEpochDay, $1.coveredEndEpochDay, $1.id)
        }

        let selected = cover(
            requiredStart: requiredStartEpochDay,
            requiredEnd: requiredEndEpochDay,
            candidates: valid
        )
        let exhaustive = selected != nil
        if !exhaustive {
            warnings.append(staleFound ? staleWarning : missingWarning)
        }
        let used = selected ?? []
        let sourceId: String
        if used.isEmpty {
            sourceId = "coverage-unavailable"
        } else {
            let raw = used.sorted { $0.id < $1.id }.map {
                "\($0.id):\($0.factFingerprint):\(millis($0.confirmedAt) ?? -1)"
            }.joined(separator: "|")
            sourceId = "coverage-" + sha256(raw).prefix(24)
        }

        return .init(
            employerId: employer,
            work: work,
            sourceId: String(sourceId),
            exhaustive: exhaustive,
            coveredStartEpochDay: used.map(\.coveredStartEpochDay).min() ?? requiredStartEpochDay,
            coveredEndEpochDay: used.map(\.coveredEndEpochDay).max() ?? requiredEndEpochDay,
            checkedAt: used.map(\.confirmedAt).max() ?? now,
            timeZoneId: timeZoneId,
            warnings: unique(warnings)
        )
    }

    static func fingerprint(
        sessions: [SalarySessionFactV2],
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        openEnd: Date
    ) -> String? {
        guard openEnd.timeIntervalSince1970.isFinite,
              let bounds = rangeBounds(
                startEpochDay: startEpochDay,
                endEpochDay: endEpochDay,
                timeZoneId: timeZoneId
              ) else {
            return nil
        }

        let relevant = sessions.filter { session in
            guard session.entry.timeIntervalSince1970.isFinite else { return true }
            let end = session.exit ?? openEnd
            guard end.timeIntervalSince1970.isFinite else { return true }
            return session.entry < bounds.to && end > bounds.from
        }

        var lines: [String] = []
        for session in relevant.sorted(by: { $0.id < $1.id }) {
            guard let entryMs = millis(session.entry),
                  let exit = session.exit,
                  let exitMs = millis(exit),
                  exit > session.entry else {
                return nil
            }
            var pauseParts: [String] = []
            for pause in session.pauses {
                guard let startMs = millis(pause.start),
                      let end = pause.end,
                      let endMs = millis(end),
                      end > pause.start,
                      let paid = pause.paid else {
                    return nil
                }
                pauseParts.append("\(startMs),\(endMs),\(paid)")
            }
            lines.append([
                field(session.id),
                field(session.employerId),
                String(entryMs),
                String(exitMs),
                pauseParts.joined(separator: ";")
            ].joined(separator: "|"))
        }
        return sha256(lines.joined(separator: "\n"))
    }

    static func decode(_ data: Data) -> SalarySegmentedPayrollCoverageReadV2 {
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data),
              envelope.schemaVersion == 1 else {
            return corrupt()
        }
        var ids = Set<String>()
        for item in envelope.items {
            guard !normalized(item.id).isEmpty,
                  ids.insert(item.id).inserted,
                  !normalized(item.employerId).isEmpty,
                  item.confirmedAt.timeIntervalSince1970.isFinite,
                  item.factFingerprint.range(
                    of: "^[0-9a-f]{64}$",
                    options: .regularExpression
                  ) != nil,
                  rangeBounds(
                    startEpochDay: item.coveredStartEpochDay,
                    endEpochDay: item.coveredEndEpochDay,
                    timeZoneId: item.timeZoneId
                  ) != nil else {
                return corrupt()
            }
        }
        return .init(attestations: envelope.items, reliable: true, warnings: [])
    }

    static func encode(_ items: [SalarySegmentedPayrollCoverageAttestationV2]) -> Data? {
        try? JSONEncoder().encode(Envelope(schemaVersion: 1, items: items))
    }

    private static func cover(
        requiredStart: Int64,
        requiredEnd: Int64,
        candidates: [SalarySegmentedPayrollCoverageAttestationV2]
    ) -> [SalarySegmentedPayrollCoverageAttestationV2]? {
        guard requiredEnd >= requiredStart else { return nil }
        var cursor = requiredStart
        var selected: [SalarySegmentedPayrollCoverageAttestationV2] = []
        while cursor <= requiredEnd {
            guard let next = candidates
                .filter({ $0.coveredStartEpochDay <= cursor && $0.coveredEndEpochDay >= cursor })
                .max(by: {
                    if $0.coveredEndEpochDay == $1.coveredEndEpochDay {
                        return $0.confirmedAt < $1.confirmedAt
                    }
                    return $0.coveredEndEpochDay < $1.coveredEndEpochDay
                }) else {
                return nil
            }
            selected.append(next)
            if next.coveredEndEpochDay >= requiredEnd {
                var seen = Set<String>()
                return selected.filter { seen.insert($0.id).inserted }
            }
            guard next.coveredEndEpochDay < Int64.max else { return nil }
            cursor = next.coveredEndEpochDay + 1
        }
        return selected
    }

    private static func rangeBounds(
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String
    ) -> (from: Date, to: Date)? {
        guard endEpochDay >= startEpochDay,
              let timeZone = TimeZone(identifier: timeZoneId),
              let start = localStart(epochDay: startEpochDay, timeZone: timeZone),
              endEpochDay < Int64.max,
              let end = localStart(epochDay: endEpochDay + 1, timeZone: timeZone),
              end > start else {
            return nil
        }
        return (start, end)
    }

    private static func localStart(epochDay: Int64, timeZone: TimeZone) -> Date? {
        guard (-25567 ... 84370).contains(epochDay) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let instant = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let parts = utc.dateComponents([.year, .month, .day], from: instant)
        guard let year = parts.year, let month = parts.month, let day = parts.day else { return nil }
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        local.locale = Locale(identifier: "en_US_POSIX")
        guard let date = local.date(from: DateComponents(year: year, month: month, day: day)) else { return nil }
        let actual = local.dateComponents([.year, .month, .day], from: date)
        guard actual.year == year, actual.month == month, actual.day == day else { return nil }
        return local.startOfDay(for: date)
    }

    private static func millis(_ date: Date) -> Int64? {
        let seconds = date.timeIntervalSince1970
        guard seconds.isFinite else { return nil }
        let value = seconds * 1_000
        guard value >= Double(Int64.min), value <= Double(Int64.max) else { return nil }
        return Int64(value.rounded())
    }

    private static func field(_ value: String?) -> String {
        guard let value else { return "<null>" }
        return value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "|", with: "\\|")
            .replacingOccurrences(of: ";", with: "\\;")
    }

    private static func normalized(_ value: String?) -> String {
        value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private static func corrupt() -> SalarySegmentedPayrollCoverageReadV2 {
        .init(attestations: [], reliable: false, warnings: [storeWarning])
    }
}
