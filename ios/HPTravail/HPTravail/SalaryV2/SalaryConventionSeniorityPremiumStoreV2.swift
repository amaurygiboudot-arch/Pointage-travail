import CoreFoundation
import Foundation

/// Stockage iOS fail-closed des règles conventionnelles d'ancienneté confirmées.
///
/// Une liste vide signifie uniquement que le stockage est lisible.
/// Elle ne prouve jamais l'absence d'une règle d'ancienneté.
enum SalaryConventionSeniorityPremiumStoreV2 {
    struct ReadResult {
        let rules: [SalaryConventionSeniorityPremiumV2.Rule]
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "KALI ancienneté : historique local des règles conventionnelles incohérent ; aucune prime d'ancienneté ne peut être déduite de ce stockage."

    private static let key = "salary_convention_seniority_premium_v2.confirmed_rules"
    private static let lock = NSLock()

    static func readConfirmed(
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        guard let object = defaults.object(forKey: key) else {
            return .init(rules: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else { return unreliable() }
        return decodeConfirmed(raw)
    }

    static func rules(
        idcc: String,
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return stored }
        let normalized = SalaryConventionSeniorityIdccV2.normalize(idcc)
        return .init(
            rules: stored.rules.filter {
                SalaryConventionSeniorityIdccV2.normalize($0.idcc) == normalized
            },
            reliable: true,
            warnings: stored.warnings
        )
    }

    @discardableResult
    static func saveConfirmed(
        _ rule: SalaryConventionSeniorityPremiumV2.Rule,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard rule.structurallyValid() else { return false }
        let normalizedIdcc = SalaryConventionSeniorityIdccV2.normalize(rule.idcc)
        guard !normalizedIdcc.isEmpty else { return false }

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }

        let normalized = SalaryConventionSeniorityPremiumV2.Rule(
            idcc: normalizedIdcc,
            ruleId: rule.ruleId.trimmingCharacters(in: .whitespacesAndNewlines),
            effectiveFrom: rule.effectiveFrom,
            effectiveTo: rule.effectiveTo,
            classification: rule.classification,
            basis: rule.basis,
            steps: rule.steps,
            includeConfirmedMonthlySupplement: rule.includeConfirmedMonthlySupplement,
            source: rule.source.trimmingCharacters(in: .whitespacesAndNewlines),
            extensionStatus: rule.extensionStatus,
            extensionEffectiveFrom: rule.extensionEffectiveFrom
        )
        guard normalized.structurallyValid() else { return false }

        var rules = stored.rules.filter {
            !(SalaryConventionSeniorityIdccV2.normalize($0.idcc) == normalizedIdcc &&
              $0.ruleId.trimmingCharacters(in: .whitespacesAndNewlines) == normalized.ruleId)
        }
        rules.append(normalized)
        guard packageValid(rules), let raw = encode(rules) else { return false }

        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }

        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && reloaded.rules.contains {
            SalaryConventionSeniorityIdccV2.normalize($0.idcc) == normalizedIdcc &&
            $0.ruleId == normalized.ruleId
        }
    }

    static func decodeConfirmed(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return unreliable()
        }

        var rules: [SalaryConventionSeniorityPremiumV2.Rule] = []
        var malformed = false
        for object in array {
            guard let rule = decode(object), rule.structurallyValid() else {
                malformed = true
                continue
            }
            rules.append(rule)
        }
        if !packageValid(rules) { malformed = true }

        return .init(
            rules: rules,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    private static func packageValid(
        _ rules: [SalaryConventionSeniorityPremiumV2.Rule]
    ) -> Bool {
        guard rules.allSatisfy({ $0.structurallyValid() }) else { return false }
        let keys = rules.map {
            "\(SalaryConventionSeniorityIdccV2.normalize($0.idcc))\u{0}\($0.ruleId.trimmingCharacters(in: .whitespacesAndNewlines))"
        }
        return Set(keys).count == keys.count
    }

    private static func encode(
        _ rules: [SalaryConventionSeniorityPremiumV2.Rule]
    ) -> String? {
        let sorted = rules.sorted {
            let left = SalaryConventionSeniorityIdccV2.normalize($0.idcc)
            let right = SalaryConventionSeniorityIdccV2.normalize($1.idcc)
            if left != right { return left < right }
            if $0.effectiveFrom != $1.effectiveFrom { return $0.effectiveFrom < $1.effectiveFrom }
            return $0.ruleId < $1.ruleId
        }

        let array: [[String: Any]] = sorted.map { rule in
            [
                "idcc": SalaryConventionSeniorityIdccV2.normalize(rule.idcc),
                "ruleId": rule.ruleId,
                "effectiveFrom": dateString(rule.effectiveFrom),
                "effectiveTo": json(rule.effectiveTo.map(dateString)),
                "classification": classificationObject(rule.classification),
                "basis": rule.basis.rawValue,
                "steps": rule.steps.map { step in
                    [
                        "years": step.years,
                        "rate": json(step.rate),
                        "fixedMonthlyAmount": json(step.fixedMonthlyAmount)
                    ]
                },
                "includeConfirmedMonthlySupplement": rule.includeConfirmedMonthlySupplement,
                "source": rule.source,
                "extensionStatus": rule.extensionStatus.rawValue,
                "extensionEffectiveFrom": json(rule.extensionEffectiveFrom.map(dateString))
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
    ) -> SalaryConventionSeniorityPremiumV2.Rule? {
        guard let idcc = object["idcc"] as? String,
              let ruleId = object["ruleId"] as? String,
              let effectiveFromRaw = object["effectiveFrom"] as? String,
              let effectiveFrom = civilDate(effectiveFromRaw),
              let classificationRaw = object["classification"] as? [String: Any],
              let classification = classification(classificationRaw),
              let basisRaw = object["basis"] as? String,
              let basis = SalaryConventionSeniorityPremiumV2.Basis(rawValue: basisRaw),
              let stepsRaw = object["steps"] as? [[String: Any]],
              let source = object["source"] as? String,
              let extensionRaw = object["extensionStatus"] as? String,
              let extensionStatus = ConventionExtensionStatusV2(rawValue: extensionRaw),
              let includeSupplement = strictBool(object["includeConfirmedMonthlySupplement"]) else {
            return nil
        }

        let effectiveTo = optionalDate(object["effectiveTo"])
        let extensionEffectiveFrom = optionalDate(object["extensionEffectiveFrom"])
        guard effectiveTo.valid, extensionEffectiveFrom.valid else { return nil }

        var steps: [SalaryConventionSeniorityPremiumV2.Step] = []
        for raw in stepsRaw {
            guard let years = strictInt(raw["years"]),
                  let rate = optionalDouble(raw["rate"]),
                  let fixed = optionalDouble(raw["fixedMonthlyAmount"]) else {
                return nil
            }
            steps.append(.init(years: years, rate: rate, fixedMonthlyAmount: fixed))
        }

        return .init(
            idcc: idcc,
            ruleId: ruleId,
            effectiveFrom: effectiveFrom,
            effectiveTo: effectiveTo.value,
            classification: classification,
            basis: basis,
            steps: steps,
            includeConfirmedMonthlySupplement: includeSupplement,
            source: source,
            extensionStatus: extensionStatus,
            extensionEffectiveFrom: extensionEffectiveFrom.value
        )
    }

    private static func classificationObject(
        _ value: ConventionClassificationV2
    ) -> [String: Any] {
        [
            "coefficient": value.coefficient ?? NSNull(),
            "level": json(value.level),
            "echelon": json(value.echelon),
            "position": json(value.position),
            "group": json(value.group),
            "category": json(value.category),
            "employment": json(value.employment)
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
        return .init(
            coefficient: coefficient,
            level: optionalString(object["level"]),
            echelon: optionalString(object["echelon"]),
            position: optionalString(object["position"]),
            group: optionalString(object["group"]),
            category: optionalString(object["category"]),
            employment: optionalString(object["employment"])
        )
    }

    private static func optionalDate(
        _ raw: Any?
    ) -> (valid: Bool, value: PayrollCivilDateV2?) {
        if raw == nil || raw is NSNull { return (true, nil) }
        guard let value = raw as? String, let date = civilDate(value) else {
            return (false, nil)
        }
        return (true, date)
    }

    private static func civilDate(_ raw: String) -> PayrollCivilDateV2? {
        let parts = raw.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              let day = Int(parts[2]) else { return nil }
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

    private static func optionalDouble(_ raw: Any?) -> Double?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let number = raw as? NSNumber, !isBool(number) else { return nil }
        let value = number.doubleValue
        guard value.isFinite else { return nil }
        return .some(value)
    }

    private static func strictInt(_ raw: Any?) -> Int? {
        guard let number = raw as? NSNumber, !isBool(number) else { return nil }
        let value = number.doubleValue
        guard value.isFinite, value.rounded() == value,
              value >= Double(Int.min), value <= Double(Int.max) else {
            return nil
        }
        return Int(value)
    }

    private static func strictBool(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber, isBool(number) else { return nil }
        return number.boolValue
    }

    private static func json(_ value: Any?) -> Any {
        value ?? NSNull()
    }

    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    private static func unreliable() -> ReadResult {
        .init(rules: [], reliable: false, warnings: [storageWarning])
    }
}
