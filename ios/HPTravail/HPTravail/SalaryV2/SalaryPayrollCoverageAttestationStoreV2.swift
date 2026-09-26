import CryptoKit
import Foundation
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

struct SalaryPayrollCoverageAttestationV2: Codable, Equatable {
    let companyId: String
    let sourceId: String
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAtMs: Int64
    let timeZoneId: String
    let historyFingerprint: String
}

struct SalaryPayrollCoverageAttestationReadV2: Equatable {
    let attestation: SalaryPayrollCoverageAttestationV2?
    let reliable: Bool
    let warnings: [String]
}

enum SalaryPayrollCoverageAttestationStoreV2 {
    static let key = "hp_travail_payroll_coverage_v2"

    static let missingWarning =
        "Couverture des pointages : aucune attestation exhaustive confirmée ne couvre cette période."
    static let staleWarning =
        "Couverture des pointages : l'historique a changé depuis l'attestation ; une nouvelle confirmation est requise."
    static let invalidWarning =
        "Couverture des pointages : attestation enregistrée illisible ou incohérente."
    static let runtimeWarning =
        "Couverture des pointages : stockage RuntimeV2 non fiable ; calcul B21 bloqué."

    @discardableResult
    static func confirm(
        defaults: UserDefaults = .standard,
        sessions: [WorkSession],
        storageReliable: Bool,
        companyId: String,
        coveredStartEpochDay: Int64,
        coveredEndEpochDay: Int64,
        sourceId: String,
        timeZoneId: String,
        checkedAt: Date = Date()
    ) -> Bool {
        let company = normalized(companyId)
        let source = normalized(sourceId)
        guard storageReliable,
              !company.isEmpty,
              !source.isEmpty,
              checkedAt.timeIntervalSince1970.isFinite,
              checkedAt.timeIntervalSince1970 > 0,
              coveredEndEpochDay >= coveredStartEpochDay,
              let timeZone = TimeZone(identifier: timeZoneId),
              let endExclusive = localStart(
                  epochDay: coveredEndEpochDay + 1,
                  timeZone: timeZone
              ),
              endExclusive <= checkedAt else {
            return false
        }

        let checkedAtMsDouble = checkedAt.timeIntervalSince1970 * 1_000
        guard checkedAtMsDouble.isFinite,
              checkedAtMsDouble > 0,
              checkedAtMsDouble <= Double(Int64.max) else {
            return false
        }

        let attestation = SalaryPayrollCoverageAttestationV2(
            companyId: company,
            sourceId: source,
            coveredStartEpochDay: coveredStartEpochDay,
            coveredEndEpochDay: coveredEndEpochDay,
            checkedAtMs: Int64(checkedAtMsDouble.rounded(.down)),
            timeZoneId: timeZone.identifier,
            historyFingerprint: fingerprint(sessions)
        )

        var all = decode(defaults.data(forKey: key)) ?? []
        all.removeAll { normalized($0.companyId) == company }
        all.append(attestation)
        guard validCollection(all, nowMs: Int64.max),
              let data = try? encoder.encode(all.sorted {
                  normalized($0.companyId) < normalized($1.companyId)
              }) else {
            return false
        }
        defaults.set(data, forKey: key)
        return defaults.data(forKey: key) == data
    }

    static func read(
        defaults: UserDefaults = .standard,
        sessions: [WorkSession],
        storageReliable: Bool,
        companyId: String,
        requiredStartEpochDay: Int64,
        requiredEndEpochDay: Int64,
        now: Date = Date()
    ) -> SalaryPayrollCoverageAttestationReadV2 {
        guard storageReliable else {
            return .init(attestation: nil, reliable: false, warnings: [runtimeWarning])
        }
        let company = normalized(companyId)
        let nowMsDouble = now.timeIntervalSince1970 * 1_000
        guard !company.isEmpty,
              requiredEndEpochDay >= requiredStartEpochDay,
              nowMsDouble.isFinite,
              nowMsDouble > 0,
              nowMsDouble <= Double(Int64.max) else {
            return .init(attestation: nil, reliable: false, warnings: [invalidWarning])
        }
        guard let stored = decode(defaults.data(forKey: key)),
              validCollection(stored, nowMs: Int64(nowMsDouble.rounded(.down))) else {
            return .init(attestation: nil, reliable: false, warnings: [invalidWarning])
        }
        guard let attestation = stored.single(where: {
            normalized($0.companyId) == company
        }) else {
            return .init(attestation: nil, reliable: false, warnings: [missingWarning])
        }
        guard requiredStartEpochDay >= attestation.coveredStartEpochDay,
              requiredEndEpochDay <= attestation.coveredEndEpochDay else {
            return .init(attestation: nil, reliable: false, warnings: [missingWarning])
        }
        guard attestation.historyFingerprint == fingerprint(sessions) else {
            return .init(attestation: nil, reliable: false, warnings: [staleWarning])
        }
        return .init(attestation: attestation, reliable: true, warnings: [])
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }

    static func fingerprint(_ sessions: [WorkSession]) -> String {
        let canonical = sessions
            .map(CanonicalSession.init)
            .sorted {
                if $0.id != $1.id { return $0.id < $1.id }
                return $0.entryBits < $1.entryBits
            }
        guard let data = try? encoder.encode(canonical) else { return "" }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ data: Data?) -> [SalaryPayrollCoverageAttestationV2]? {
        guard let data else { return [] }
        return try? decoder.decode([SalaryPayrollCoverageAttestationV2].self, from: data)
    }

    private static func validCollection(
        _ attestations: [SalaryPayrollCoverageAttestationV2],
        nowMs: Int64
    ) -> Bool {
        guard Set(attestations.map { normalized($0.companyId) }).count == attestations.count else {
            return false
        }
        return attestations.allSatisfy { valid($0, nowMs: nowMs) }
    }

    private static func valid(
        _ attestation: SalaryPayrollCoverageAttestationV2,
        nowMs: Int64
    ) -> Bool {
        guard !normalized(attestation.companyId).isEmpty,
              !normalized(attestation.sourceId).isEmpty,
              attestation.coveredEndEpochDay >= attestation.coveredStartEpochDay,
              attestation.coveredEndEpochDay < Int64.max,
              attestation.checkedAtMs > 0,
              attestation.checkedAtMs <= nowMs,
              attestation.historyFingerprint.range(
                  of: "^[0-9a-f]{64}$",
                  options: .regularExpression
              ) != nil,
              let timeZone = TimeZone(identifier: attestation.timeZoneId),
              let endExclusive = localStart(
                  epochDay: attestation.coveredEndEpochDay + 1,
                  timeZone: timeZone
              ) else {
            return false
        }
        return endExclusive.timeIntervalSince1970 * 1_000 <= Double(attestation.checkedAtMs)
    }

    private static func localStart(epochDay: Int64, timeZone: TimeZone) -> Date? {
        let seconds = Double(epochDay) * 86_400
        guard seconds.isFinite else { return nil }
        let utcDate = Date(timeIntervalSince1970: seconds)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = utc.dateComponents([.year, .month, .day], from: utcDate)
        guard let year = components.year,
              let month = components.month,
              let day = components.day else {
            return nil
        }
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        return local.date(from: DateComponents(year: year, month: month, day: day))
    }

    private static func normalized(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static let encoder: JSONEncoder = {
        let value = JSONEncoder()
        value.outputFormatting = [.sortedKeys]
        return value
    }()

    private static let decoder = JSONDecoder()

    private struct CanonicalPause: Codable {
        let id: String
        let startBits: UInt64
        let endBits: UInt64?
        let paid: Bool?

        init(_ pause: PausePeriod) {
            id = pause.id.uuidString
            startBits = pause.start.timeIntervalSince1970.bitPattern
            endBits = pause.end?.timeIntervalSince1970.bitPattern
            paid = pause.paid
        }
    }

    private struct CanonicalSession: Codable {
        let id: String
        let entryBits: UInt64
        let exitBits: UInt64?
        let employerId: String?
        let placeLabel: String?
        let pauses: [CanonicalPause]

        init(_ session: WorkSession) {
            id = session.id.uuidString
            entryBits = session.entry.timeIntervalSince1970.bitPattern
            exitBits = session.exit?.timeIntervalSince1970.bitPattern
            employerId = session.employerId
            placeLabel = session.placeLabel
            pauses = session.pauses
                .map(CanonicalPause.init)
                .sorted {
                    if $0.id != $1.id { return $0.id < $1.id }
                    return $0.startBits < $1.startBits
                }
        }
    }
}

enum SalarySegmentedPayrollSessionSourceFactoryV2 {
    static func fromStores(
        defaults: UserDefaults = .standard,
        sessions: [WorkSession],
        storageReliable: Bool,
        companyId: String,
        requiredStartEpochDay: Int64,
        requiredEndEpochDay: Int64,
        requestedTimeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let work = SalaryWorkSessionBridgeV2.source(
            from: sessions,
            storageReliable: storageReliable
        )
        let coverage = SalaryPayrollCoverageAttestationStoreV2.read(
            defaults: defaults,
            sessions: sessions,
            storageReliable: storageReliable,
            companyId: companyId,
            requiredStartEpochDay: requiredStartEpochDay,
            requiredEndEpochDay: requiredEndEpochDay,
            now: now
        )
        let attestation = coverage.attestation
        let checkedAt = attestation.map {
            Date(timeIntervalSince1970: Double($0.checkedAtMs) / 1_000)
        } ?? now
        return SalarySegmentedPayrollSessionSourceV2(
            employerId: companyId.trimmingCharacters(in: .whitespacesAndNewlines),
            work: work,
            sourceId: attestation?.sourceId ?? "",
            exhaustive: coverage.reliable,
            coveredStartEpochDay: attestation?.coveredStartEpochDay ?? 0,
            coveredEndEpochDay: attestation?.coveredEndEpochDay ?? -1,
            checkedAt: checkedAt,
            timeZoneId: attestation?.timeZoneId ?? requestedTimeZoneId,
            warnings: coverage.warnings
        )
    }
}

private extension Array {
    func single(where predicate: (Element) -> Bool) -> Element? {
        var match: Element?
        for element in self where predicate(element) {
            guard match == nil else { return nil }
            match = element
        }
        return match
    }
}
