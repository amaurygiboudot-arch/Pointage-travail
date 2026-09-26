import Foundation
import CryptoKit
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

/// Source factuelle provenant du journal RuntimeV2.
/// La fiabilité du stockage est transportée séparément : les faits restent visibles pour diagnostic,
/// mais un stockage non fiable interdit toute publication d'un résultat comme confirmé.
struct SalaryWorkSessionSourceV2: Equatable {
    let sessions: [SalarySessionFactV2]
    let reliable: Bool
}

/// Adaptateur unidirectionnel RuntimeV2 -> Salaire V2.
///
/// Aucun rattachement d'entreprise n'est créé ici : `employerId` est recopié tel quel.
/// Une session sans entreprise reste sans entreprise ; une session d'une autre entreprise conserve
/// son identifiant. Le filtrage appartient ensuite à `SalaryPaidWorkAggregatorV2`.
enum SalaryWorkSessionBridgeV2 {
    static func source(
        from sessions: [WorkSession],
        storageReliable: Bool
    ) -> SalaryWorkSessionSourceV2 {
        SalaryWorkSessionSourceV2(
            sessions: sessions.map { session in
                SalarySessionFactV2(
                    id: session.id.uuidString,
                    entry: session.entry,
                    exit: session.exit,
                    employerId: session.employerId,
                    pauses: session.pauses.map { pause in
                        PaidPauseFactV2(
                            start: pause.start,
                            end: pause.end,
                            paid: pause.paid
                        )
                    }
                )
            },
            reliable: storageReliable
        )
    }
}


struct SalaryPayrollCoverageAttestationV2: Codable, Equatable {
    let sourceId: String
    let employerId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    let snapshotId: String
}

/// Preuve d'exhaustivité persistée séparément du journal RuntimeV2.
///
/// Un stockage sain n'est jamais déclaré complet par simple lecture. L'attestation reste valable
/// seulement si l'empreinte des sessions pouvant toucher la plage confirmée n'a pas changé.
enum SalaryRuntimePayrollCoverageV2 {
    static let missingAttestationWarning =
        "Preuves B21 : aucune attestation d'exhaustivité n'est enregistrée pour cette période."
    static let staleAttestationWarning =
        "Preuves B21 : l'attestation d'exhaustivité ne correspond plus au snapshot courant des pointages."
    static let storeWarning =
        "Preuves B21 : le store local des attestations d'exhaustivité est illisible ; couverture bloquée."

    private static let storageKey = "horatrack_salary_payroll_coverage_v1"

    static func confirm(
        sessions: [WorkSession],
        storageReliable: Bool,
        employerId rawEmployerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        checkedAt: Date,
        timeZoneId: String,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let employerId = normalized(rawEmployerId)
        guard storageReliable,
              !employerId.isEmpty,
              coveredEndEpochDay >= coveredStartEpochDay,
              coveredEndEpochDay < Int64.max,
              checkedAt.timeIntervalSince1970.isFinite,
              let timeZone = TimeZone(identifier: timeZoneId),
              let closedAt = localStart(coveredEndEpochDay + 1, timeZone: timeZone),
              closedAt <= checkedAt,
              let snapshotId = snapshotId(
                sessions: sessions,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                timeZoneId: timeZoneId
              ),
              var stored = readStored(defaults: defaults) else {
            return false
        }

        stored.removeAll {
            normalized($0.employerId) == employerId
                && $0.coveredStartEpochDay == coveredStartEpochDay
                && $0.coveredEndEpochDay == coveredEndEpochDay
                && $0.timeZoneId == timeZoneId
        }
        stored.append(
            SalaryPayrollCoverageAttestationV2(
                sourceId: "runtime-coverage:\(UUID().uuidString)",
                employerId: employerId,
                coveredStartEpochDay: coveredStartEpochDay,
                coveredEndEpochDay: coveredEndEpochDay,
                checkedAt: checkedAt,
                timeZoneId: timeZoneId,
                snapshotId: snapshotId
            )
        )
        return writeStored(stored, defaults: defaults)
    }

    static func source(
        sessions: [WorkSession],
        storageReliable: Bool,
        employerId rawEmployerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        defaults: UserDefaults = .standard
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let employerId = normalized(rawEmployerId)
        let stored = readStored(defaults: defaults)
        let attestation = stored?
            .filter {
                normalized($0.employerId) == employerId
                    && $0.coveredStartEpochDay == coveredStartEpochDay
                    && $0.coveredEndEpochDay == coveredEndEpochDay
                    && $0.timeZoneId == timeZoneId
            }
            .max(by: { $0.checkedAt < $1.checkedAt })

        let extraWarnings: [String]
        if stored == nil {
            extraWarnings = [storeWarning]
        } else if attestation == nil {
            extraWarnings = [missingAttestationWarning]
        } else {
            extraWarnings = []
        }

        return sourceFrom(
            sessions: sessions,
            storageReliable: storageReliable,
            attestation: attestation,
            employerId: rawEmployerId,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            timeZoneId: timeZoneId,
            now: now,
            extraWarnings: extraWarnings
        )
    }

    static func sourceFrom(
        sessions: [WorkSession],
        storageReliable: Bool,
        attestation: SalaryPayrollCoverageAttestationV2?,
        employerId rawEmployerId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String,
        now: Date,
        extraWarnings: [String] = []
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let employerId = normalized(rawEmployerId)
        let fingerprint = snapshotId(
            sessions: sessions,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            timeZoneId: timeZoneId
        )
        let attestationValid =
            storageReliable
                && !employerId.isEmpty
                && now.timeIntervalSince1970.isFinite
                && attestation.map {
                    normalized($0.employerId) == employerId
                        && $0.coveredStartEpochDay == coveredStartEpochDay
                        && $0.coveredEndEpochDay == coveredEndEpochDay
                        && $0.timeZoneId == timeZoneId
                        && $0.checkedAt.timeIntervalSince1970.isFinite
                        && $0.checkedAt.timeIntervalSince1970 > 0
                        && $0.checkedAt <= now
                        && fingerprint != nil
                        && $0.snapshotId == fingerprint
                } == true

        var warnings = extraWarnings
        if let attestation, !attestationValid {
            _ = attestation
            warnings.append(staleAttestationWarning)
        } else if attestation == nil, extraWarnings.isEmpty {
            warnings.append(missingAttestationWarning)
        }

        let attestationSourceId = normalized(attestation?.sourceId)

        return SalarySegmentedPayrollSessionSourceV2(
            employerId: employerId,
            work: SalaryWorkSessionBridgeV2.source(
                from: sessions,
                storageReliable: storageReliable
            ),
            sourceId: attestationSourceId.isEmpty
                ? "runtime-v2-unattested"
                : attestationSourceId,
            exhaustive: attestationValid,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAt: attestation?.checkedAt ?? now,
            timeZoneId: timeZoneId,
            warnings: unique(warnings)
        )
    }

    static func snapshotId(
        sessions: [WorkSession],
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        timeZoneId: String
    ) -> String? {
        guard coveredEndEpochDay >= coveredStartEpochDay,
              coveredEndEpochDay < Int64.max,
              let timeZone = TimeZone(identifier: timeZoneId),
              let from = localStart(coveredStartEpochDay, timeZone: timeZone),
              let to = localStart(coveredEndEpochDay + 1, timeZone: timeZone),
              to > from else {
            return nil
        }

        let relevant = sessions
            .filter { touches($0, from: from, to: to) }
            .sorted {
                if $0.entry != $1.entry { return $0.entry < $1.entry }
                return $0.id.uuidString < $1.id.uuidString
            }

        var canonical = ""
        append(&canonical, "coverage-v1")
        append(&canonical, coveredStartEpochDay)
        append(&canonical, coveredEndEpochDay)
        append(&canonical, timeZoneId)
        append(&canonical, Int64(relevant.count))
        for session in relevant {
            append(&canonical, session.id.uuidString)
            append(&canonical, session.employerId)
            append(&canonical, session.entry.timeIntervalSince1970.bitPattern)
            append(&canonical, session.exit?.timeIntervalSince1970.bitPattern)
            append(&canonical, session.placeLabel)
            append(&canonical, Int64(session.pauses.count))
            for pause in session.pauses {
                append(&canonical, pause.id.uuidString)
                append(&canonical, pause.start.timeIntervalSince1970.bitPattern)
                append(&canonical, pause.end?.timeIntervalSince1970.bitPattern)
                append(&canonical, pause.paid)
            }
        }

        let digest = SHA256.hash(data: Data(canonical.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return "sha256:\(digest)"
    }

    private static func touches(_ session: WorkSession, from: Date, to: Date) -> Bool {
        if let exit = session.exit {
            return session.entry < to && exit > from
        }
        return session.entry < to
    }

    private static func localStart(_ epochDay: Int64, timeZone: TimeZone) -> Date? {
        guard (-25_567...84_370).contains(epochDay) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let utcDate = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let components = utc.dateComponents([.year, .month, .day], from: utcDate)

        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        guard let year = components.year,
              let month = components.month,
              let day = components.day,
              let value = local.date(from: DateComponents(
                  calendar: local,
                  timeZone: timeZone,
                  year: year,
                  month: month,
                  day: day,
                  hour: 0,
                  minute: 0,
                  second: 0
              )) else {
            return nil
        }
        let actual = local.dateComponents([.year, .month, .day], from: value)
        guard actual.year == year, actual.month == month, actual.day == day else { return nil }
        return local.startOfDay(for: value)
    }

    private static func append(_ output: inout String, _ value: String?) {
        guard let value else {
            output += "N;"
            return
        }
        output += "S\(value.utf8.count):\(value);"
    }

    private static func append(_ output: inout String, _ value: Int64?) {
        guard let value else {
            output += "N;"
            return
        }
        output += "L\(value);"
    }

    private static func append(_ output: inout String, _ value: UInt64?) {
        guard let value else {
            output += "N;"
            return
        }
        output += "U\(value);"
    }

    private static func append(_ output: inout String, _ value: Bool?) {
        guard let value else {
            output += "N;"
            return
        }
        output += value ? "B1;" : "B0;"
    }

    private static func readStored(
        defaults: UserDefaults
    ) -> [SalaryPayrollCoverageAttestationV2]? {
        guard let data = defaults.data(forKey: storageKey) else { return [] }
        return try? JSONDecoder().decode([SalaryPayrollCoverageAttestationV2].self, from: data)
    }

    private static func writeStored(
        _ values: [SalaryPayrollCoverageAttestationV2],
        defaults: UserDefaults
    ) -> Bool {
        guard let data = try? JSONEncoder().encode(values) else { return false }
        defaults.set(data, forKey: storageKey)
        return defaults.data(forKey: storageKey) == data
    }

    private static func normalized(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
