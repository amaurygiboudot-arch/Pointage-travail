import CoreFoundation
import Foundation

struct SalarySegmentedProrationSourceV2: Equatable {
    let proration: ConfirmedSegmentedMonthlyProrationV2?
    let reliable: Bool
    let warnings: [String]
}

/// Stockage local iOS d'une base de proratisation mensuelle explicitement confirmée.
///
/// Une absence de donnée ne signifie jamais qu'un prorata peut être inventé. Le store conserve
/// uniquement les minutes planifiées confirmées par segment ; la validation métier contre la
/// timeline contractuelle reste la responsabilité de ConfirmedSegmentedMonthlyProrationCalculatorV2.
enum SalarySegmentedProrationStoreV2 {
    static let missingWarning =
        "Proratisation mensuelle : aucune base planifiée confirmée n'est disponible pour ce mois."
    static let storageWarning =
        "Proratisation mensuelle : stockage local incohérent ; aucun prorata n'est utilisable."

    private static let prefix = "salary_segmented_proration_v2."
    private static let lock = NSLock()
    private static let maxExactJSONInteger = 9_007_199_254_740_991.0

    static func resolve(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> SalarySegmentedProrationSourceV2 {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults) else {
            return SalarySegmentedProrationSourceV2(
                proration: nil,
                reliable: false,
                warnings: ["Proratisation mensuelle : entreprise absente ou stockage des entreprises non fiable."]
            )
        }

        guard let object = defaults.object(forKey: key(companyId, period: period)) else {
            return SalarySegmentedProrationSourceV2(
                proration: nil,
                reliable: false,
                warnings: [missingWarning]
            )
        }
        guard let raw = object as? String,
              let proration = decode(raw),
              valid(proration) else {
            return SalarySegmentedProrationSourceV2(
                proration: nil,
                reliable: false,
                warnings: [storageWarning]
            )
        }

        return SalarySegmentedProrationSourceV2(
            proration: proration,
            reliable: true,
            warnings: []
        )
    }

    @discardableResult
    static func save(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        proration: ConfirmedSegmentedMonthlyProrationV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults),
              valid(proration),
              let raw = encode(proration) else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        defaults.set(raw, forKey: key(companyId, period: period))
        guard defaults.string(forKey: key(companyId, period: period)) == raw else {
            return false
        }
        let reloaded = resolve(companyId: companyId, period: period, defaults: defaults)
        return reloaded.reliable && reloaded.proration == proration
    }

    @discardableResult
    static func remove(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults) else { return false }

        lock.lock()
        defer { lock.unlock() }

        defaults.removeObject(forKey: key(companyId, period: period))
        return defaults.object(forKey: key(companyId, period: period)) == nil
    }

    static func decode(_ raw: String) -> ConfirmedSegmentedMonthlyProrationV2? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sourceRaw = object["sourceId"] as? String,
              let checkedAtMs = strictInt64(object["checkedAtMs"]),
              let methodRaw = object["method"] as? String,
              let method = ConfirmedProrationMethodV2(rawValue: methodRaw),
              let rawSegments = object["segments"] as? [[String: Any]] else {
            return nil
        }

        let sourceId = sourceRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sourceId.isEmpty else { return nil }

        var segments: [ConfirmedProrationSegmentV2] = []
        for object in rawSegments {
            guard let versionRaw = object["versionId"] as? String,
                  let start = strictInt64(object["startEpochDay"]),
                  let end = strictInt64(object["endEpochDay"]),
                  let minutes64 = strictInt64(object["scheduledMinutes"]),
                  let minutes = Int(exactly: minutes64) else {
                return nil
            }
            let versionId = versionRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !versionId.isEmpty else { return nil }
            segments.append(
                ConfirmedProrationSegmentV2(
                    versionId: versionId,
                    startEpochDay: start,
                    endEpochDay: end,
                    scheduledMinutes: minutes
                )
            )
        }

        let result = ConfirmedSegmentedMonthlyProrationV2(
            sourceId: sourceId,
            checkedAtMs: checkedAtMs,
            method: method,
            segments: segments
        )
        return valid(result) ? result : nil
    }

    private static func encode(_ proration: ConfirmedSegmentedMonthlyProrationV2) -> String? {
        let object: [String: Any] = [
            "sourceId": proration.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            "checkedAtMs": proration.checkedAtMs,
            "method": proration.method.rawValue,
            "segments": proration.segments.map {
                [
                    "versionId": $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines),
                    "startEpochDay": $0.startEpochDay,
                    "endEpochDay": $0.endEpochDay,
                    "scheduledMinutes": $0.scheduledMinutes
                ] as [String: Any]
            }
        ]
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func valid(_ proration: ConfirmedSegmentedMonthlyProrationV2) -> Bool {
        guard !proration.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              proration.checkedAtMs >= 0,
              proration.method == .scheduledMinutes,
              !proration.segments.isEmpty else {
            return false
        }

        var keys = Set<SegmentKey>()
        var total: Int64 = 0
        for segment in proration.segments {
            let versionId = segment.versionId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !versionId.isEmpty,
                  segment.endEpochDay >= segment.startEpochDay,
                  segment.scheduledMinutes >= 0 else {
                return false
            }
            let key = SegmentKey(
                versionId: versionId,
                startEpochDay: segment.startEpochDay,
                endEpochDay: segment.endEpochDay
            )
            guard keys.insert(key).inserted else { return false }
            let addition = total.addingReportingOverflow(Int64(segment.scheduledMinutes))
            guard !addition.overflow else { return false }
            total = addition.partialValue
        }
        return total > 0
    }

    private static func strictInt64(_ raw: Any?) -> Int64? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            return nil
        }
        let value = number.doubleValue
        guard value.isFinite,
              value.rounded() == value,
              abs(value) <= maxExactJSONInteger else {
            return nil
        }
        return Int64(value)
    }

    private static func confirmedCompany(_ companyId: String, defaults: UserDefaults) -> Bool {
        guard !companyId.isEmpty else { return false }
        return SalaryCompanyStoreV2.confirmedCompany(
            SalaryCompanyStoreV2.readConfirmed(defaults: defaults),
            companyId: companyId
        ) != nil
    }

    private static func key(_ companyId: String, period: YearMonthV2) -> String {
        prefix + companyId + "." + period.description
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private struct SegmentKey: Hashable {
        let versionId: String
        let startEpochDay: Int64
        let endEpochDay: Int64
    }
}
