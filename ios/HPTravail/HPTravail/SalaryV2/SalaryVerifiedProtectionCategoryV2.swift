import Foundation

/// Classification conventionnelle générique.
/// Aucun rapprochement approximatif n'est effectué entre coefficient, niveau, échelon, groupe, etc.
struct ConventionClassificationV2: Codable, Equatable {
    let coefficient: Int?
    let level: String?
    let echelon: String?
    let position: String?
    let group: String?
    let category: String?
    let employment: String?

    init(
        coefficient: Int? = nil,
        level: String? = nil,
        echelon: String? = nil,
        position: String? = nil,
        group: String? = nil,
        category: String? = nil,
        employment: String? = nil
    ) {
        self.coefficient = coefficient
        self.level = level
        self.echelon = echelon
        self.position = position
        self.group = group
        self.category = category
        self.employment = employment
    }

    var isEmpty: Bool {
        coefficient == nil &&
        normalized(level) == nil &&
        normalized(echelon) == nil &&
        normalized(position) == nil &&
        normalized(group) == nil &&
        normalized(category) == nil &&
        normalized(employment) == nil
    }

    func matches(_ selector: ConventionClassificationV2) -> Bool {
        if let wanted = selector.coefficient, coefficient != wanted { return false }
        if let wanted = normalized(selector.level), normalized(level) != wanted { return false }
        if let wanted = normalized(selector.echelon), normalized(echelon) != wanted { return false }
        if let wanted = normalized(selector.position), normalized(position) != wanted { return false }
        if let wanted = normalized(selector.group), normalized(group) != wanted { return false }
        if let wanted = normalized(selector.category), normalized(category) != wanted { return false }
        if let wanted = normalized(selector.employment), normalized(employment) != wanted { return false }
        return true
    }

    var specificity: Int {
        [
            coefficient.map(String.init),
            normalized(level),
            normalized(echelon),
            normalized(position),
            normalized(group),
            normalized(category),
            normalized(employment)
        ].compactMap { $0 }.count
    }

    var label: String {
        var parts: [String] = []
        if let coefficient { parts.append("coefficient \(coefficient)") }
        if let value = normalized(level) { parts.append("niveau \(value)") }
        if let value = normalized(echelon) { parts.append("échelon \(value)") }
        if let value = normalized(position) { parts.append("position \(value)") }
        if let value = normalized(group) { parts.append("groupe \(value)") }
        if let value = normalized(category) { parts.append("catégorie \(value)") }
        if let value = normalized(employment) { parts.append("emploi \(value)") }
        return parts.isEmpty ? "classification non précisée" : parts.joined(separator: ", ")
    }

    private func normalized(_ value: String?) -> String? {
        Self.normalizeText(value)
    }

    private static func normalizeText(_ value: String?) -> String? {
        guard let value else { return nil }
        let collapsed = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .uppercased(with: Locale(identifier: "fr_FR"))
        return collapsed.isEmpty ? nil : collapsed
    }
}

enum ConventionExtensionStatusV2: String, Codable, Equatable {
    case extended = "EXTENDED"
    case notExtended = "NOT_EXTENDED"
    case unknown = "UNKNOWN"
}

enum ConventionMatterCoverageV2 {
    enum Matter: String, Codable, Equatable {
        case minimumSalary = "MINIMUM_SALARY"
        case seniorityPremium = "SENIORITY_PREMIUM"
        case sicknessMaintenance = "SICKNESS_MAINTENANCE"
        case provident = "PROVIDENT"
        case providentCategory = "PROVIDENT_CATEGORY"
        case providentContribution = "PROVIDENT_CONTRIBUTION"
        case providentBenefits = "PROVIDENT_BENEFITS"
        case overtime = "OVERTIME"
        case night = "NIGHT"
        case saturday = "SATURDAY"
        case sunday = "SUNDAY"
        case publicHoliday = "PUBLIC_HOLIDAY"
        case mealBasket = "MEAL_BASKET"
        case otherPremium = "OTHER_PREMIUM"
    }

    enum Authority: String, Codable, Hashable {
        case kali = "KALI"
        case apec = "APEC"
        case acco = "ACCO"
        case national = "NATIONAL"
    }

    enum State: String, Codable, Equatable {
        case confirmedRules = "CONFIRMED_RULES"
        case confirmedNoRule = "CONFIRMED_NO_RULE"
        case incomplete = "INCOMPLETE"
    }

    struct Record: Equatable {
        let idcc: String
        let matter: Matter
        let effectiveFrom: PayrollCivilDateV2
        let effectiveTo: PayrollCivilDateV2?
        let classification: ConventionClassificationV2
        let professionalStatus: String?
        let state: State
        let source: String
        let checkedAtMs: Int64
        let authorities: Set<Authority>

        var structurallyValid: Bool {
            !normalizeIdcc(idcc).isEmpty &&
            !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            checkedAtMs > 0 &&
            (effectiveTo == nil || effectiveTo! >= effectiveFrom)
        }

        func active(on date: PayrollCivilDateV2) -> Bool {
            date >= effectiveFrom && (effectiveTo == nil || date <= effectiveTo!)
        }

        func statusMatches(_ value: String?) -> Bool {
            guard let expected = normalizedStatus(professionalStatus) else { return true }
            return normalizedStatus(value) == expected
        }
    }

    struct Snapshot: Equatable {
        let state: State
        let record: Record?
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(
        records: [Record],
        idcc: String,
        matter: Matter,
        date: PayrollCivilDateV2,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = nil
    ) -> Snapshot {
        let normalized = normalizeIdcc(idcc)
        let matching = records.filter {
            $0.structurallyValid &&
            normalizeIdcc($0.idcc) == normalized &&
            $0.matter == matter &&
            $0.active(on: date) &&
            classification.matches($0.classification) &&
            $0.statusMatches(professionalStatus)
        }

        guard !matching.isEmpty else {
            return Snapshot(
                state: .incomplete,
                record: nil,
                reliable: false,
                warnings: [
                    "Convention IDCC \(normalized) — analyse officielle non confirmée pour cette période ; aucun droit n'est supposé absent."
                ]
            )
        }

        let latestDate = matching.map(\.effectiveFrom).max()!
        let latest = matching.filter { $0.effectiveFrom == latestDate }
        let maxSpecificity = latest.map {
            $0.classification.specificity + (normalizedStatus($0.professionalStatus) == nil ? 0 : 1)
        }.max()!
        let best = latest.filter {
            $0.classification.specificity + (normalizedStatus($0.professionalStatus) == nil ? 0 : 1)
                == maxSpecificity
        }
        let states = Set(best.map(\.state.rawValue))
        guard states.count == 1 else {
            return Snapshot(
                state: .incomplete,
                record: nil,
                reliable: false,
                warnings: [
                    "Convention IDCC \(normalized) : états de couverture contradictoires sur la même période ; aucun droit n'est supposé absent."
                ]
            )
        }

        let selected = best.max { $0.checkedAtMs < $1.checkedAtMs }!
        return Snapshot(
            state: selected.state,
            record: selected,
            reliable: selected.state != .incomplete,
            warnings: selected.state == .incomplete
                ? ["Convention IDCC \(normalized) : analyse officielle incomplète ; aucun droit n'est supposé absent."]
                : []
        )
    }
}

struct SalaryConventionLegalProfileV2: Equatable {
    let companyId: String
    let idcc: String
    let professionalStatus: String?
    let classification: ConventionClassificationV2
}

enum ConventionProtectionCategoryV2 {
    enum ApprovalStatus: String, Equatable {
        case apecApproved = "APEC_APPROVED"
        case apecRequiredUnverified = "APEC_REQUIRED_UNVERIFIED"
    }

    struct Rule: Equatable {
        let idcc: String
        let ruleId: String
        let effectiveFrom: PayrollCivilDateV2
        let effectiveTo: PayrollCivilDateV2?
        let classification: ConventionClassificationV2
        let professionalStatus: String?
        let aniCategory: ProtectionCategoryV2.AniCategory
        let source: String
        let extensionStatus: ConventionExtensionStatusV2
        let extensionEffectiveFrom: PayrollCivilDateV2?
        let conventionScopeKey: String?
        let approvalStatus: ApprovalStatus
        let approvalEffectiveFrom: PayrollCivilDateV2?
        let approvalSource: String?
        let approvalScopeKey: String?
        let approvalClassification: ConventionClassificationV2?
        let approvalAniCategory: ProtectionCategoryV2.AniCategory?

        init(
            idcc: String,
            ruleId: String,
            effectiveFrom: PayrollCivilDateV2,
            effectiveTo: PayrollCivilDateV2? = nil,
            classification: ConventionClassificationV2,
            professionalStatus: String?,
            aniCategory: ProtectionCategoryV2.AniCategory,
            source: String,
            extensionStatus: ConventionExtensionStatusV2,
            extensionEffectiveFrom: PayrollCivilDateV2? = nil,
            conventionScopeKey: String? = nil,
            approvalStatus: ApprovalStatus = .apecRequiredUnverified,
            approvalEffectiveFrom: PayrollCivilDateV2? = nil,
            approvalSource: String? = nil,
            approvalScopeKey: String? = nil,
            approvalClassification: ConventionClassificationV2? = nil,
            approvalAniCategory: ProtectionCategoryV2.AniCategory? = nil
        ) {
            self.idcc = idcc
            self.ruleId = ruleId
            self.effectiveFrom = effectiveFrom
            self.effectiveTo = effectiveTo
            self.classification = classification
            self.professionalStatus = professionalStatus
            self.aniCategory = aniCategory
            self.source = source
            self.extensionStatus = extensionStatus
            self.extensionEffectiveFrom = extensionEffectiveFrom
            self.conventionScopeKey = conventionScopeKey
            self.approvalStatus = approvalStatus
            self.approvalEffectiveFrom = approvalEffectiveFrom
            self.approvalSource = approvalSource
            self.approvalScopeKey = approvalScopeKey
            self.approvalClassification = approvalClassification
            self.approvalAniCategory = approvalAniCategory
        }

        var structurallyValid: Bool {
            guard let status = normalizedStatus(professionalStatus),
                  status == "CADRE" || status == "NON_CADRE" else {
                return false
            }

            let approvalProofValid: Bool
            switch approvalStatus {
            case .apecApproved:
                guard let kaliScope = trimmed(conventionScopeKey),
                      let apecScope = trimmed(approvalScopeKey),
                      let approvalClassification,
                      !approvalClassification.isEmpty,
                      approvalEffectiveFrom != nil,
                      trimmed(approvalSource) != nil,
                      kaliScope == apecScope,
                      classification.matches(approvalClassification),
                      approvalClassification.matches(classification),
                      approvalAniCategory == aniCategory else {
                    return false
                }
                approvalProofValid = true

            case .apecRequiredUnverified:
                approvalProofValid =
                    approvalEffectiveFrom == nil &&
                    trimmed(approvalSource) == nil &&
                    trimmed(approvalScopeKey) == nil &&
                    approvalClassification == nil &&
                    approvalAniCategory == nil
            }

            return !normalizeIdcc(idcc).isEmpty &&
                !ruleId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                !classification.isEmpty &&
                categoryMatchesStatus(aniCategory, status: status) &&
                aniCategory != .toConfirm &&
                aniCategory != .noConventionOverride &&
                !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                (effectiveTo == nil || effectiveTo! >= effectiveFrom) &&
                (extensionStatus != .extended || extensionEffectiveFrom != nil) &&
                approvalProofValid
        }

        func active(on date: PayrollCivilDateV2) -> Bool {
            date >= effectiveFrom && (effectiveTo == nil || date <= effectiveTo!)
        }

        func statusMatches(_ value: String?) -> Bool {
            normalizedStatus(professionalStatus) == normalizedStatus(value)
        }

        func extensionApplicable(on date: PayrollCivilDateV2) -> Bool {
            extensionStatus == .extended &&
                extensionEffectiveFrom.map { date >= $0 } == true
        }

        func approvalApplicable(on date: PayrollCivilDateV2) -> Bool {
            approvalStatus == .apecApproved &&
                structurallyValid &&
                approvalEffectiveFrom.map { date >= $0 } == true
        }
    }

    struct Resolution: Equatable {
        let category: ProtectionCategoryV2.Result
        let selectedRule: Rule?
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        rules: [Rule],
        coverage: ConventionMatterCoverageV2.Snapshot? = nil
    ) -> Resolution {
        let normalized = normalizeIdcc(idcc)
        let matching = rules.filter {
            $0.structurallyValid &&
            normalizeIdcc($0.idcc) == normalized &&
            $0.active(on: referenceDate) &&
            classification.matches($0.classification) &&
            $0.classification.matches(classification) &&
            $0.statusMatches(professionalStatus)
        }

        if matching.isEmpty {
            if coverageConfirmsNoRule(
                coverage,
                idcc: normalized,
                referenceDate: referenceDate,
                classification: classification,
                professionalStatus: professionalStatus
            ) {
                return Resolution(
                    category: ProtectionCategoryV2.noConventionOverride(),
                    selectedRule: nil,
                    reliable: true,
                    warnings: []
                )
            }
            return unresolved("catégorie conventionnelle ANI non confirmée pour cette classification")
        }

        let latestDate = matching.map(\.effectiveFrom).max()!
        let latest = matching.filter { $0.effectiveFrom == latestDate }
        let maxSpecificity = latest.map { $0.classification.specificity + 1 }.max()!
        let best = latest.filter { $0.classification.specificity + 1 == maxSpecificity }
        let categories = Set(best.map { aniRaw($0.aniCategory) })
        guard categories.count == 1 else {
            return unresolved("plusieurs catégories ANI contradictoires sont applicables à la même classification")
        }

        let extended = best.filter { $0.extensionApplicable(on: referenceDate) }
        guard !extended.isEmpty else {
            return unresolved("extension officielle de la règle conventionnelle non démontrée à cette date")
        }

        let approved = extended.filter { $0.approvalApplicable(on: referenceDate) }
        guard !approved.isEmpty else {
            return unresolved("agrément APEC exact (périmètre + classification + catégorie) non démontré à cette date")
        }

        let selected = approved.max {
            if $0.effectiveFrom != $1.effectiveFrom { return $0.effectiveFrom < $1.effectiveFrom }
            let leftExtension = $0.extensionEffectiveFrom ?? PayrollCivilDateV2(year: 1, month: 1, day: 1)!
            let rightExtension = $1.extensionEffectiveFrom ?? PayrollCivilDateV2(year: 1, month: 1, day: 1)!
            if leftExtension != rightExtension { return leftExtension < rightExtension }
            let leftApproval = $0.approvalEffectiveFrom ?? PayrollCivilDateV2(year: 1, month: 1, day: 1)!
            let rightApproval = $1.approvalEffectiveFrom ?? PayrollCivilDateV2(year: 1, month: 1, day: 1)!
            return leftApproval < rightApproval
        }!

        var warnings: [String] = []
        if selected.aniCategory == .extensionEligible {
            warnings.append(
                "Extension au régime cadres autorisée par la branche/APEC : l'affiliation effective au régime de l'entreprise reste à confirmer avant d'appliquer les contributions propres aux articles 2.1/2.2."
            )
        }
        return Resolution(
            category: ProtectionCategoryV2.Result(
                aniCategory: selected.aniCategory,
                confirmed: true,
                source: [selected.source, selected.approvalSource]
                    .compactMap { trimmed($0) }
                    .joined(separator: " + "),
                warnings: warnings
            ),
            selectedRule: selected,
            reliable: true,
            warnings: warnings
        )
    }

    private static func coverageConfirmsNoRule(
        _ coverage: ConventionMatterCoverageV2.Snapshot?,
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        classification: ConventionClassificationV2,
        professionalStatus: String?
    ) -> Bool {
        guard let coverage,
              coverage.reliable,
              coverage.state == .confirmedNoRule,
              let record = coverage.record,
              record.structurallyValid,
              record.state == .confirmedNoRule,
              record.matter == .providentCategory,
              record.authorities.contains(.kali),
              record.authorities.contains(.apec),
              normalizeIdcc(record.idcc) == idcc,
              record.active(on: referenceDate),
              classification.matches(record.classification),
              record.statusMatches(professionalStatus) else {
            return false
        }
        return true
    }

    private static func unresolved(_ reason: String) -> Resolution {
        let warning =
            "Catégorie de protection sociale complémentaire : \(reason) ; aucun classement ANI n'est inventé."
        return Resolution(
            category: ProtectionCategoryV2.Result(
                aniCategory: .toConfirm,
                confirmed: false,
                warnings: [warning]
            ),
            selectedRule: nil,
            reliable: false,
            warnings: [warning]
        )
    }
}

enum SalaryVerifiedProtectionCategoryProviderV2 {
    struct Snapshot: Equatable {
        let category: ProtectionCategoryV2.Result
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(
        profile: SalaryConventionLegalProfileV2,
        referenceDate: PayrollCivilDateV2,
        rules: [ConventionProtectionCategoryV2.Rule],
        coverage: ConventionMatterCoverageV2.Snapshot? = nil
    ) -> Snapshot {
        guard !normalizeIdcc(profile.idcc).isEmpty else {
            return unresolved("IDCC manquant")
        }
        guard !profile.classification.isEmpty else {
            return unresolved("classification conventionnelle exacte manquante")
        }
        guard normalizedStatus(profile.professionalStatus) != nil else {
            return unresolved("statut cadre/non-cadre exact manquant")
        }

        let resolution = ConventionProtectionCategoryV2.resolve(
            idcc: profile.idcc,
            referenceDate: referenceDate,
            classification: profile.classification,
            professionalStatus: profile.professionalStatus,
            rules: rules,
            coverage: coverage
        )
        return Snapshot(
            category: resolution.category,
            reliable: resolution.reliable,
            warnings: unique(resolution.warnings)
        )
    }

    private static func unresolved(_ reason: String) -> Snapshot {
        let warning = "Catégorie ANI vérifiée : \(reason) ; aucun classement n'est inventé."
        return Snapshot(
            category: ProtectionCategoryV2.Result(
                aniCategory: .toConfirm,
                confirmed: false,
                warnings: [warning]
            ),
            reliable: false,
            warnings: [warning]
        )
    }
}

private func normalizeIdcc(_ value: String?) -> String {
    let digits = (value ?? "").filter(\.isNumber)
    let trimmed = digits.drop { $0 == "0" }
    return String(trimmed)
}

private func normalizedStatus(_ value: String?) -> String? {
    guard let value else { return nil }
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    return normalized == "CADRE" || normalized == "NON_CADRE" ? normalized : nil
}

private func trimmed(_ value: String?) -> String? {
    guard let value else { return nil }
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return normalized.isEmpty ? nil : normalized
}

private func categoryMatchesStatus(
    _ category: ProtectionCategoryV2.AniCategory,
    status: String
) -> Bool {
    switch category {
    case .article2_1:
        return status == "CADRE"
    case .article2_2, .extensionEligible, .outside2_1_2_2:
        return status == "NON_CADRE"
    case .toConfirm, .noConventionOverride:
        return false
    }
}

private func aniRaw(_ value: ProtectionCategoryV2.AniCategory) -> String {
    switch value {
    case .article2_1: return "ARTICLE_2_1"
    case .article2_2: return "ARTICLE_2_2"
    case .extensionEligible: return "EXTENSION_ELIGIBLE"
    case .outside2_1_2_2: return "OUTSIDE_2_1_2_2"
    case .toConfirm: return "TO_CONFIRM"
    case .noConventionOverride: return "NO_CONVENTION_OVERRIDE"
    }
}

private func unique(_ values: [String]) -> [String] {
    var seen = Set<String>()
    return values.filter { seen.insert($0).inserted }
}
