import CoreFoundation
import Foundation

/// Preuve explicite qu'une source juridique prioritaire a été contrôlée.
/// Une absence de donnée locale n'est jamais une preuve d'absence officielle.
enum SalaryPayrollSourceKnowledgeV2 {
    enum Matter: String, Codable, Equatable {
        case overtimeRate = "OVERTIME_RATE"
        case providentContribution = "PROVIDENT_CONTRIBUTION"
        case mealBasket = "MEAL_BASKET"
        case seniorityPremium = "SENIORITY_PREMIUM"
    }

    enum Outcome: String, Codable, Equatable {
        case noApplicableRule = "NO_APPLICABLE_RULE"
        case ruleFound = "RULE_FOUND"
        case inconclusive = "INCONCLUSIVE"
    }

    enum Knowledge: Equatable {
        case unknown
        case confirmedAbsence
    }

    struct Proof: Equatable {
        let source: ConventionMatterCoverageV2.Authority
        let matter: Matter
        let companyId: String?
        let idcc: String?
        let subjectKey: String?
        let referenceFrom: PayrollCivilDateV2
        let referenceTo: PayrollCivilDateV2
        let officialCoverageThrough: PayrollCivilDateV2
        let checkedAtMs: Int64
        let officialScopeId: String
        let exhaustive: Bool
        let scopeConfirmed: Bool
        let outcome: Outcome

        var structurallyValid: Bool {
            guard referenceTo >= referenceFrom,
                  checkedAtMs > 0,
                  !officialScopeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return false
            }
            if matter == .mealBasket {
                return !normalizedSubject(subjectKey).isEmpty
            }
            return true
        }
    }

    static func accoOfficialScopeId(_ siret: String?) -> String? {
        let digits = (siret ?? "").filter(\.isNumber)
        guard digits.count == 14 else { return nil }
        return "ACCO:SIRET:\(digits)"
    }

    static func knowledgeFor(
        proofs: [Proof],
        source: ConventionMatterCoverageV2.Authority,
        matter: Matter,
        companyId: String,
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        subjectKey: String? = nil
    ) -> Knowledge {
        let confirmed = proofs.contains { proof in
            proof.structurallyValid &&
            proof.source == source &&
            proof.matter == matter &&
            proof.exhaustive &&
            proof.scopeConfirmed &&
            proof.outcome == .noApplicableRule &&
            referenceDate >= proof.referenceFrom &&
            referenceDate <= proof.referenceTo &&
            proof.officialCoverageThrough >= referenceDate &&
            scopeMatches(proof, source: source, companyId: companyId, idcc: idcc) &&
            subjectMatches(proof, matter: matter, requested: subjectKey)
        }
        return confirmed ? .confirmedAbsence : .unknown
    }

    static func knowledgeForSeniorityPremium(
        proofs: [Proof],
        companyId: String,
        idcc: String,
        referenceDate: PayrollCivilDateV2
    ) -> [ConventionMatterCoverageV2.Authority: Knowledge] {
        var output: [ConventionMatterCoverageV2.Authority: Knowledge] = [:]
        for source in [ConventionMatterCoverageV2.Authority.acco, .kali] {
            let value = knowledgeFor(
                proofs: proofs,
                source: source,
                matter: .seniorityPremium,
                companyId: companyId,
                idcc: idcc,
                referenceDate: referenceDate
            )
            if value == .confirmedAbsence { output[source] = value }
        }
        return output
    }

    private static func scopeMatches(
        _ proof: Proof,
        source: ConventionMatterCoverageV2.Authority,
        companyId: String,
        idcc: String
    ) -> Bool {
        switch source {
        case .acco:
            return !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                proof.companyId?.trimmingCharacters(in: .whitespacesAndNewlines) ==
                companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        case .kali:
            let expected = normalizedIdcc(idcc)
            return !expected.isEmpty && normalizedIdcc(proof.idcc) == expected
        default:
            return false
        }
    }

    private static func subjectMatches(
        _ proof: Proof,
        matter: Matter,
        requested: String?
    ) -> Bool {
        guard matter == .mealBasket else { return true }
        let wanted = normalizedSubject(requested)
        return !wanted.isEmpty && normalizedSubject(proof.subjectKey) == wanted
    }

    static func normalizedIdcc(_ raw: String?) -> String {
        let value = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? "" : String(repeating: "0", count: max(0, 4 - value.count)) + value
    }

    static func normalizedSubject(_ raw: String?) -> String {
        var value = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        value = value.replacingOccurrences(of: "_[0-9]+$", with: "", options: .regularExpression)
        return value.hasPrefix("MEAL_") ? value : ""
    }
}

/// Journal local non destructif des preuves juridiques prioritaires.
enum SalaryPayrollSourceKnowledgeStoreV2 {
    struct ReadResult {
        let proofs: [SalaryPayrollSourceKnowledgeV2.Proof]
        let reliable: Bool
        let warnings: [String]
    }

    struct KnowledgeResult {
        let knowledge: [ConventionMatterCoverageV2.Authority: SalaryPayrollSourceKnowledgeV2.Knowledge]
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "Preuves de contrôle des sources juridiques : stockage local incohérent ; aucune absence de règle ne peut être déduite."
    static let maxProofs = 250

    private static let key = "salary_payroll_source_knowledge_v2.proofs"
    private static let lock = NSLock()

    static func read(defaults: UserDefaults = .standard) -> ReadResult {
        guard let object = defaults.object(forKey: key) else {
            return .init(proofs: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else { return unreliable() }
        return decodeProofs(raw)
    }

    @discardableResult
    static func record(
        _ proof: SalaryPayrollSourceKnowledgeV2.Proof,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard proof.structurallyValid else { return false }
        lock.lock()
        defer { lock.unlock() }

        let stored = read(defaults: defaults)
        guard stored.reliable else { return false }
        var proofs = stored.proofs.filter { !sameIdentity($0, proof) }
        proofs.append(proof)
        guard accepts(proofs), let raw = encode(proofs) else { return false }
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }
        return read(defaults: defaults).reliable
    }

    static func seniorityKnowledge(
        companyId: String,
        idcc: String,
        referenceDate: PayrollCivilDateV2,
        currentSiret: String?,
        defaults: UserDefaults = .standard
    ) -> KnowledgeResult {
        let stored = read(defaults: defaults)
        guard stored.reliable else {
            return .init(knowledge: [:], reliable: false, warnings: stored.warnings)
        }
        let scoped = scopeAccoProofs(stored.proofs, currentSiret: currentSiret)
        return .init(
            knowledge: SalaryPayrollSourceKnowledgeV2.knowledgeForSeniorityPremium(
                proofs: scoped,
                companyId: companyId,
                idcc: idcc,
                referenceDate: referenceDate
            ),
            reliable: true,
            warnings: []
        )
    }

    static func decodeProofs(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return unreliable()
        }
        var proofs: [SalaryPayrollSourceKnowledgeV2.Proof] = []
        var malformed = array.count > maxProofs
        for object in array {
            guard let proof = decode(object), proof.structurallyValid else {
                malformed = true
                continue
            }
            proofs.append(proof)
        }
        if hasDuplicateIdentity(proofs) { malformed = true }
        return .init(
            proofs: proofs,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    static func scopeAccoProofs(
        _ proofs: [SalaryPayrollSourceKnowledgeV2.Proof],
        currentSiret: String?
    ) -> [SalaryPayrollSourceKnowledgeV2.Proof] {
        let expected = SalaryPayrollSourceKnowledgeV2.accoOfficialScopeId(currentSiret)
        return proofs.filter {
            $0.source != .acco ||
            (expected != nil && $0.officialScopeId.trimmingCharacters(in: .whitespacesAndNewlines) == expected)
        }
    }

    private static func accepts(_ proofs: [SalaryPayrollSourceKnowledgeV2.Proof]) -> Bool {
        proofs.count <= maxProofs &&
        proofs.allSatisfy(\.structurallyValid) &&
        !hasDuplicateIdentity(proofs)
    }

    private static func sameIdentity(
        _ a: SalaryPayrollSourceKnowledgeV2.Proof,
        _ b: SalaryPayrollSourceKnowledgeV2.Proof
    ) -> Bool {
        a.source == b.source &&
        a.matter == b.matter &&
        (a.companyId ?? "").trimmingCharacters(in: .whitespacesAndNewlines) ==
            (b.companyId ?? "").trimmingCharacters(in: .whitespacesAndNewlines) &&
        SalaryPayrollSourceKnowledgeV2.normalizedIdcc(a.idcc) ==
            SalaryPayrollSourceKnowledgeV2.normalizedIdcc(b.idcc) &&
        SalaryPayrollSourceKnowledgeV2.normalizedSubject(a.subjectKey) ==
            SalaryPayrollSourceKnowledgeV2.normalizedSubject(b.subjectKey) &&
        a.referenceFrom == b.referenceFrom &&
        a.referenceTo == b.referenceTo &&
        a.officialScopeId.trimmingCharacters(in: .whitespacesAndNewlines) ==
            b.officialScopeId.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func hasDuplicateIdentity(
        _ proofs: [SalaryPayrollSourceKnowledgeV2.Proof]
    ) -> Bool {
        for i in proofs.indices {
            for j in proofs.indices where j > i {
                if sameIdentity(proofs[i], proofs[j]) { return true }
            }
        }
        return false
    }

    private static func encode(
        _ proofs: [SalaryPayrollSourceKnowledgeV2.Proof]
    ) -> String? {
        let array: [[String: Any]] = proofs.map { proof in
            [
                "source": proof.source.rawValue,
                "matter": proof.matter.rawValue,
                "companyId": json(proof.companyId),
                "idcc": json(proof.idcc),
                "subjectKey": json(proof.subjectKey),
                "referenceFrom": dateString(proof.referenceFrom),
                "referenceTo": dateString(proof.referenceTo),
                "officialCoverageThrough": dateString(proof.officialCoverageThrough),
                "checkedAtMs": proof.checkedAtMs,
                "officialScopeId": proof.officialScopeId,
                "exhaustive": proof.exhaustive,
                "scopeConfirmed": proof.scopeConfirmed,
                "outcome": proof.outcome.rawValue
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return raw
    }

    private static func decode(
        _ object: [String: Any]
    ) -> SalaryPayrollSourceKnowledgeV2.Proof? {
        guard let sourceRaw = object["source"] as? String,
              let source = ConventionMatterCoverageV2.Authority(rawValue: sourceRaw),
              let matterRaw = object["matter"] as? String,
              let matter = SalaryPayrollSourceKnowledgeV2.Matter(rawValue: matterRaw),
              let from = date(object["referenceFrom"]),
              let to = date(object["referenceTo"]),
              let through = date(object["officialCoverageThrough"]),
              let checkedAt = integer(object["checkedAtMs"]),
              let scope = string(object["officialScopeId"]),
              let exhaustive = boolean(object["exhaustive"]),
              let scopeConfirmed = boolean(object["scopeConfirmed"]),
              let outcomeRaw = object["outcome"] as? String,
              let outcome = SalaryPayrollSourceKnowledgeV2.Outcome(rawValue: outcomeRaw) else {
            return nil
        }
        return .init(
            source: source,
            matter: matter,
            companyId: optionalString(object["companyId"]),
            idcc: optionalString(object["idcc"]),
            subjectKey: optionalString(object["subjectKey"]),
            referenceFrom: from,
            referenceTo: to,
            officialCoverageThrough: through,
            checkedAtMs: checkedAt,
            officialScopeId: scope,
            exhaustive: exhaustive,
            scopeConfirmed: scopeConfirmed,
            outcome: outcome
        )
    }

    private static func date(_ raw: Any?) -> PayrollCivilDateV2? {
        guard let s = raw as? String else { return nil }
        let p = s.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]) else { return nil }
        return PayrollCivilDateV2(year: y, month: m, day: d)
    }

    private static func dateString(_ value: PayrollCivilDateV2) -> String {
        String(format: "%04d-%02d-%02d", value.year, value.month, value.day)
    }
    private static func integer(_ raw: Any?) -> Int64? {
        guard let n = raw as? NSNumber, !isBool(n), n.int64Value > 0 else { return nil }
        return n.int64Value
    }
    private static func boolean(_ raw: Any?) -> Bool? {
        guard let n = raw as? NSNumber, isBool(n) else { return nil }
        return n.boolValue
    }
    private static func string(_ raw: Any?) -> String? {
        guard let s = raw as? String else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
    private static func optionalString(_ raw: Any?) -> String? {
        if raw == nil || raw is NSNull { return nil }
        return string(raw)
    }
    private static func json(_ value: Any?) -> Any { value ?? NSNull() }
    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }
    private static func unreliable() -> ReadResult {
        .init(proofs: [], reliable: false, warnings: [storageWarning])
    }
}
