import CoreFoundation
import Foundation

/// Historique iOS des matières conventionnelles effectivement auditées.
/// Un historique vide reste fiable à lire mais ne prouve jamais l'absence d'un droit.
enum SalaryConventionMatterCoverageStoreV2 {
    struct ReadResult: Equatable {
        let records: [ConventionMatterCoverageV2.Record]
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "Couverture conventionnelle : stockage local des audits officiels incohérent ; aucun droit ni aucune absence de droit ne peut être déduit de cet historique."

    private static let key = "salary_convention_matter_coverage_v2.records"

    static func read(
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        guard let object = defaults.object(forKey: key) else {
            return ReadResult(records: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else {
            return unreliable()
        }
        return decodeRecords(raw)
    }

    static func resolve(
        defaults: UserDefaults = .standard,
        idcc: String,
        matter: ConventionMatterCoverageV2.Matter,
        date: PayrollCivilDateV2,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = nil
    ) -> ConventionMatterCoverageV2.Snapshot {
        let stored = read(defaults: defaults)
        guard stored.reliable else {
            return ConventionMatterCoverageV2.Snapshot(
                state: .incomplete,
                record: nil,
                reliable: false,
                warnings: unique([storageWarning] + stored.warnings)
            )
        }
        return ConventionMatterCoverageV2.resolve(
            records: stored.records,
            idcc: idcc,
            matter: matter,
            date: date,
            classification: classification,
            professionalStatus: professionalStatus
        )
    }

    @discardableResult
    static func save(
        _ record: ConventionMatterCoverageV2.Record,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard record.structurallyValid else { return false }
        let stored = read(defaults: defaults)
        guard stored.reliable else { return false }

        var records = stored.records.filter { !sameIdentity($0, record) }
        records.append(record)
        guard acceptsPackage(records), let raw = encode(records) else {
            return false
        }
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }
        return read(defaults: defaults).reliable
    }

    static func acceptsPackage(
        _ records: [ConventionMatterCoverageV2.Record]
    ) -> Bool {
        guard records.allSatisfy(\.structurallyValid) else { return false }
        for left in records.indices {
            for right in records.indices where right > left {
                if sameIdentity(records[left], records[right]) {
                    return false
                }
            }
        }
        return true
    }

    static func decodeRecords(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return unreliable()
        }

        var records: [ConventionMatterCoverageV2.Record] = []
        var malformed = false
        for object in array {
            guard let record = decode(object), record.structurallyValid else {
                malformed = true
                continue
            }
            records.append(record)
        }
        if !acceptsPackage(records) { malformed = true }

        return ReadResult(
            records: records,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    private static func sameIdentity(
        _ left: ConventionMatterCoverageV2.Record,
        _ right: ConventionMatterCoverageV2.Record
    ) -> Bool {
        normalize(left.idcc) == normalize(right.idcc) &&
        left.matter == right.matter &&
        left.effectiveFrom == right.effectiveFrom &&
        left.effectiveTo == right.effectiveTo &&
        left.classification == right.classification &&
        normalizedStatus(left.professionalStatus) == normalizedStatus(right.professionalStatus)
    }

    private static func encode(
        _ records: [ConventionMatterCoverageV2.Record]
    ) -> String? {
        let array: [[String: Any]] = records.map { record in
            [
                "idcc": normalize(record.idcc),
                "matter": record.matter.rawValue,
                "effectiveFrom": dateString(record.effectiveFrom),
                "effectiveTo": record.effectiveTo.map(dateString) ?? NSNull(),
                "classification": classificationObject(record.classification),
                "professionalStatus": record.professionalStatus ?? NSNull(),
                "state": record.state.rawValue,
                "source": record.source,
                "checkedAtMs": record.checkedAtMs,
                "authorities": record.authorities.map(\.rawValue).sorted()
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func decode(
        _ object: [String: Any]
    ) -> ConventionMatterCoverageV2.Record? {
        guard let idcc = object["idcc"] as? String,
              let matterRaw = object["matter"] as? String,
              let matter = ConventionMatterCoverageV2.Matter(rawValue: matterRaw),
              let fromRaw = object["effectiveFrom"] as? String,
              let effectiveFrom = civilDate(fromRaw),
              let stateRaw = object["state"] as? String,
              let state = ConventionMatterCoverageV2.State(rawValue: stateRaw),
              let source = object["source"] as? String,
              let checkedNumber = object["checkedAtMs"] as? NSNumber,
              !isBool(checkedNumber),
              checkedNumber.doubleValue.rounded() == checkedNumber.doubleValue,
              let classificationObject = object["classification"] as? [String: Any],
              let classification = classification(classificationObject) else {
            return nil
        }

        let effectiveTo: PayrollCivilDateV2?
        if object["effectiveTo"] == nil || object["effectiveTo"] is NSNull {
            effectiveTo = nil
        } else if let raw = object["effectiveTo"] as? String,
                  let parsed = civilDate(raw) {
            effectiveTo = parsed
        } else {
            return nil
        }

        let authorities: Set<ConventionMatterCoverageV2.Authority>
        if object["authorities"] == nil || object["authorities"] is NSNull {
            authorities = []
        } else if let raw = object["authorities"] as? [Any] {
            var parsed = Set<ConventionMatterCoverageV2.Authority>()
            for item in raw {
                guard let value = item as? String,
                      let authority = ConventionMatterCoverageV2.Authority(rawValue: value) else {
                    return nil
                }
                parsed.insert(authority)
            }
            authorities = parsed
        } else {
            return nil
        }

        return ConventionMatterCoverageV2.Record(
            idcc: idcc,
            matter: matter,
            effectiveFrom: effectiveFrom,
            effectiveTo: effectiveTo,
            classification: classification,
            professionalStatus: optionalString(object["professionalStatus"]),
            state: state,
            source: source,
            checkedAtMs: checkedNumber.int64Value,
            authorities: authorities
        )
    }

    private static func classificationObject(
        _ value: ConventionClassificationV2
    ) -> [String: Any] {
        [
            "coefficient": value.coefficient ?? NSNull(),
            "level": value.level ?? NSNull(),
            "echelon": value.echelon ?? NSNull(),
            "position": value.position ?? NSNull(),
            "group": value.group ?? NSNull(),
            "category": value.category ?? NSNull(),
            "employment": value.employment ?? NSNull()
        ]
    }

    private static func classification(
        _ object: [String: Any]
    ) -> ConventionClassificationV2? {
        let coefficient: Int?
        if object["coefficient"] == nil || object["coefficient"] is NSNull {
            coefficient = nil
        } else if let value = object["coefficient"] as? NSNumber,
                  !isBool(value),
                  value.doubleValue.rounded() == value.doubleValue,
                  value.intValue > 0 {
            coefficient = value.intValue
        } else {
            return nil
        }

        return ConventionClassificationV2(
            coefficient: coefficient,
            level: optionalString(object["level"]),
            echelon: optionalString(object["echelon"]),
            position: optionalString(object["position"]),
            group: optionalString(object["group"]),
            category: optionalString(object["category"]),
            employment: optionalString(object["employment"])
        )
    }

    private static func civilDate(_ raw: String) -> PayrollCivilDateV2? {
        let parts = raw.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              let day = Int(parts[2]) else {
            return nil
        }
        return PayrollCivilDateV2(year: year, month: month, day: day)
    }

    private static func dateString(_ value: PayrollCivilDateV2) -> String {
        String(format: "%04d-%02d-%02d", value.year, value.month, value.day)
    }

    private static func optionalString(_ raw: Any?) -> String? {
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func normalizedStatus(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return normalized.isEmpty ? nil : normalized
    }

    private static func normalize(_ value: String) -> String {
        String(value.filter(\.isNumber).drop { $0 == "0" })
    }

    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    private static func unreliable() -> ReadResult {
        ReadResult(records: [], reliable: false, warnings: [storageWarning])
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
