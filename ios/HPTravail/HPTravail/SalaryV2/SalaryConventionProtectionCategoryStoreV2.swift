import CoreFoundation
import Foundation

/// Cache local iOS des règles ANI dont les preuves KALI et, le cas échéant, APEC
/// ont déjà été structurées. Un cache vide ne prouve jamais une absence de règle.
enum SalaryConventionProtectionCategoryStoreV2 {
    struct ReadResult: Equatable {
        let rules: [ConventionProtectionCategoryV2.Rule]
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "KALI catégorie ANI : stockage local des preuves KALI/APEC incohérent ; aucune catégorie de prévoyance ne peut être déduite de ce stockage."

    private static let key = "salary_convention_protection_category_v2.verified_rules"

    static func readVerified(
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        guard let object = defaults.object(forKey: key) else {
            return ReadResult(rules: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else {
            return unreliable()
        }
        return decodeVerified(raw)
    }

    @discardableResult
    static func saveVerified(
        _ rule: ConventionProtectionCategoryV2.Rule,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard acceptsVerifiedRule(rule) else { return false }
        let stored = readVerified(defaults: defaults)
        guard stored.reliable else { return false }

        let normalizedIdcc = normalize(rule.idcc)
        var rules = stored.rules.filter {
            !(normalize($0.idcc) == normalizedIdcc && $0.ruleId == rule.ruleId)
        }
        rules.append(rule)
        guard acceptsVerifiedPackage(rules),
              let raw = encode(rules) else {
            return false
        }
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }
        let reloaded = readVerified(defaults: defaults)
        return reloaded.reliable && reloaded.rules.contains(rule)
    }

    static func rules(
        idcc: String,
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        let stored = readVerified(defaults: defaults)
        guard stored.reliable else { return stored }
        let normalizedIdcc = normalize(idcc)
        return ReadResult(
            rules: stored.rules.filter { normalize($0.idcc) == normalizedIdcc },
            reliable: true,
            warnings: stored.warnings
        )
    }

    static func acceptsVerifiedRule(
        _ rule: ConventionProtectionCategoryV2.Rule
    ) -> Bool {
        rule.structurallyValid &&
        (rule.conventionScopeKey?.range(
            of: #"^KALITEXT\d+$"#,
            options: .regularExpression
        ) != nil) &&
        rule.aniCategory != .toConfirm &&
        rule.aniCategory != .noConventionOverride
    }

    static func acceptsVerifiedPackage(
        _ rules: [ConventionProtectionCategoryV2.Rule]
    ) -> Bool {
        guard rules.allSatisfy(acceptsVerifiedRule) else { return false }
        let keys = rules.map { "\(normalize($0.idcc))\u{0}\($0.ruleId)" }
        return Set(keys).count == keys.count
    }

    static func decodeVerified(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return unreliable()
        }

        var rules: [ConventionProtectionCategoryV2.Rule] = []
        var malformed = false
        for object in array {
            guard let rule = decode(object), acceptsVerifiedRule(rule) else {
                malformed = true
                continue
            }
            rules.append(rule)
        }
        if !acceptsVerifiedPackage(rules) { malformed = true }

        return ReadResult(
            rules: rules,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    private static func encode(
        _ rules: [ConventionProtectionCategoryV2.Rule]
    ) -> String? {
        let array: [[String: Any]] = rules.map { rule in
            [
                "idcc": normalize(rule.idcc),
                "ruleId": rule.ruleId,
                "effectiveFrom": dateString(rule.effectiveFrom),
                "effectiveTo": json(rule.effectiveTo.map(dateString)),
                "classification": classificationObject(rule.classification),
                "professionalStatus": json(rule.professionalStatus),
                "aniCategory": categoryRaw(rule.aniCategory),
                "source": rule.source,
                "extensionStatus": rule.extensionStatus.rawValue,
                "extensionEffectiveFrom": json(rule.extensionEffectiveFrom.map(dateString)),
                "conventionScopeKey": json(rule.conventionScopeKey),
                "approvalStatus": rule.approvalStatus.rawValue,
                "approvalEffectiveFrom": json(rule.approvalEffectiveFrom.map(dateString)),
                "approvalSource": json(rule.approvalSource),
                "approvalScopeKey": json(rule.approvalScopeKey),
                "approvalClassification": rule.approvalClassification
                    .map(classificationObject) ?? NSNull(),
                "approvalAniCategory": rule.approvalAniCategory
                    .map(categoryRaw) ?? NSNull()
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
    ) -> ConventionProtectionCategoryV2.Rule? {
        guard let idcc = object["idcc"] as? String,
              let ruleId = object["ruleId"] as? String,
              let effectiveFromRaw = object["effectiveFrom"] as? String,
              let effectiveFrom = civilDate(effectiveFromRaw),
              let classificationObject = object["classification"] as? [String: Any],
              let baseClassification = classification(classificationObject),
              let aniRaw = object["aniCategory"] as? String,
              let aniCategory = category(aniRaw),
              let source = object["source"] as? String,
              let extensionRaw = object["extensionStatus"] as? String,
              let extensionStatus = ConventionExtensionStatusV2(rawValue: extensionRaw),
              let approvalRaw = object["approvalStatus"] as? String,
              let approvalStatus = ConventionProtectionCategoryV2.ApprovalStatus(
                rawValue: approvalRaw
              ) else {
            return nil
        }

        let effectiveTo = optionalDate(object["effectiveTo"])
        let extensionEffectiveFrom = optionalDate(object["extensionEffectiveFrom"])
        let approvalEffectiveFrom = optionalDate(object["approvalEffectiveFrom"])
        guard effectiveTo.valid,
              extensionEffectiveFrom.valid,
              approvalEffectiveFrom.valid else {
            return nil
        }

        let approvalClassification: ConventionClassificationV2?
        if object["approvalClassification"] == nil || object["approvalClassification"] is NSNull {
            approvalClassification = nil
        } else if let raw = object["approvalClassification"] as? [String: Any],
                  let parsed = classification(raw) {
            approvalClassification = parsed
        } else {
            return nil
        }

        let approvalAniCategory: ProtectionCategoryV2.AniCategory?
        if object["approvalAniCategory"] == nil || object["approvalAniCategory"] is NSNull {
            approvalAniCategory = nil
        } else if let raw = object["approvalAniCategory"] as? String,
                  let parsed = category(raw) {
            approvalAniCategory = parsed
        } else {
            return nil
        }

        return ConventionProtectionCategoryV2.Rule(
            idcc: idcc,
            ruleId: ruleId,
            effectiveFrom: effectiveFrom,
            effectiveTo: effectiveTo.value,
            classification: baseClassification,
            professionalStatus: optionalString(object["professionalStatus"]),
            aniCategory: aniCategory,
            source: source,
            extensionStatus: extensionStatus,
            extensionEffectiveFrom: extensionEffectiveFrom.value,
            conventionScopeKey: optionalString(object["conventionScopeKey"]),
            approvalStatus: approvalStatus,
            approvalEffectiveFrom: approvalEffectiveFrom.value,
            approvalSource: optionalString(object["approvalSource"]),
            approvalScopeKey: optionalString(object["approvalScopeKey"]),
            approvalClassification: approvalClassification,
            approvalAniCategory: approvalAniCategory
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

    private static func category(
        _ raw: String
    ) -> ProtectionCategoryV2.AniCategory? {
        switch raw {
        case "ARTICLE_2_1": return .article2_1
        case "ARTICLE_2_2": return .article2_2
        case "EXTENSION_ELIGIBLE": return .extensionEligible
        case "OUTSIDE_2_1_2_2": return .outside2_1_2_2
        case "TO_CONFIRM": return .toConfirm
        case "NO_CONVENTION_OVERRIDE": return .noConventionOverride
        default: return nil
        }
    }

    private static func categoryRaw(
        _ value: ProtectionCategoryV2.AniCategory
    ) -> String {
        switch value {
        case .article2_1: return "ARTICLE_2_1"
        case .article2_2: return "ARTICLE_2_2"
        case .extensionEligible: return "EXTENSION_ELIGIBLE"
        case .outside2_1_2_2: return "OUTSIDE_2_1_2_2"
        case .toConfirm: return "TO_CONFIRM"
        case .noConventionOverride: return "NO_CONVENTION_OVERRIDE"
        }
    }

    private static func optionalDate(
        _ raw: Any?
    ) -> (valid: Bool, value: PayrollCivilDateV2?) {
        if raw == nil || raw is NSNull { return (true, nil) }
        guard let value = raw as? String,
              let date = civilDate(value) else {
            return (false, nil)
        }
        return (true, date)
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

    private static func normalize(_ value: String) -> String {
        let digits = value.filter(\.isNumber)
        return String(digits.drop { $0 == "0" })
    }

    private static func json(_ value: String?) -> Any {
        value ?? NSNull()
    }

    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    private static func unreliable() -> ReadResult {
        ReadResult(rules: [], reliable: false, warnings: [storageWarning])
    }
}
