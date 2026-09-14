import Foundation

/// Contrat iOS de fiabilité SalaireV2.
///
/// Il reproduit le principe canonique Android : inconnu != zéro, un brut social
/// n'est utilisable comme référence que si le salaire en espèces et les avantages
/// en nature du mois sont confirmables.
struct YearMonthV2: Codable, Equatable, Hashable, Comparable, CustomStringConvertible {
    let year: Int
    let month: Int

    init?(year: Int, month: Int) {
        guard (1...12).contains(month) else { return nil }
        self.year = year
        self.month = month
    }

    init?(_ raw: String) {
        let parts = raw.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 2,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              (1...12).contains(month) else { return nil }
        self.year = year
        self.month = month
    }

    var description: String { String(format: "%04d-%02d", year, month) }

    static func < (lhs: YearMonthV2, rhs: YearMonthV2) -> Bool {
        lhs.year == rhs.year ? lhs.month < rhs.month : lhs.year < rhs.year
    }
}

enum CompanyBenefitInKindContractV2 {
    enum Kind: String, Codable {
        case monthly = "MONTHLY"
        case oneOff = "ONE_OFF"
    }

    struct Record: Equatable {
        let id: String
        let label: String
        let grossValue: Double
        let kind: Kind
        let effectiveFrom: YearMonthV2?
        let effectiveTo: YearMonthV2?
        let paymentMonth: YearMonthV2?
    }

    struct Applied: Equatable {
        let id: String
        let label: String
        let grossValue: Double
    }

    struct Snapshot {
        let applied: [Applied]
        let totalGross: Double
        let reliable: Bool
        let warnings: [String]
    }

    struct ReadResult {
        let records: [Record]
        let reliable: Bool
        let warnings: [String]
    }

    struct MonthConfirmation: Equatable {
        let period: YearMonthV2
        let source: String
    }

    struct ConfirmationReadResult {
        let confirmations: [MonthConfirmation]
        let reliable: Bool
        let warnings: [String]
    }

    private static let storageWarning =
        "Avantages en nature : stockage local incohérent ; calcul automatique bloqué."
    private static let coverageStorageWarning =
        "Avantages en nature : stockage des confirmations mensuelles incohérent ; calcul automatique bloqué."

    static func companyUnavailableReadResult() -> ReadResult {
        ReadResult(
            records: [],
            reliable: false,
            warnings: ["Avantages en nature : entreprise absente ou stockage des entreprises non fiable."]
        )
    }

    static func companyUnavailableConfirmationResult() -> ConfirmationReadResult {
        ConfirmationReadResult(
            confirmations: [],
            reliable: false,
            warnings: ["Avantages en nature : entreprise absente ou stockage des entreprises non fiable."]
        )
    }

    static func resolve(records: [Record], period: YearMonthV2) -> Snapshot {
        var applied: [Applied] = []
        var warnings: [String] = []
        var reliable = true

        for record in records {
            let label = record.label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !label.isEmpty, record.grossValue.isFinite, record.grossValue > 0 else {
                reliable = false
                warnings.append("Avantage en nature invalide : libellé ou valeur brute à vérifier.")
                continue
            }

            switch record.kind {
            case .monthly:
                guard let start = record.effectiveFrom else {
                    reliable = false
                    warnings.append("Avantage en nature mensuel « \(record.label) » : mois de début manquant.")
                    continue
                }
                if let end = record.effectiveTo, end < start {
                    reliable = false
                    warnings.append("Avantage en nature mensuel « \(record.label) » : période invalide.")
                    continue
                }
                if period >= start && (record.effectiveTo == nil || period <= record.effectiveTo!) {
                    applied.append(Applied(id: record.id, label: record.label, grossValue: record.grossValue))
                }

            case .oneOff:
                guard let paymentMonth = record.paymentMonth else {
                    reliable = false
                    warnings.append("Avantage en nature ponctuel « \(record.label) » : mois d'application manquant.")
                    continue
                }
                if paymentMonth == period {
                    applied.append(Applied(id: record.id, label: record.label, grossValue: record.grossValue))
                }
            }
        }

        return Snapshot(
            applied: applied,
            totalGross: applied.reduce(0) { $0 + $1.grossValue },
            reliable: reliable,
            warnings: unique(warnings)
        )
    }

    static func resolve(
        records: ReadResult,
        confirmations: ConfirmationReadResult,
        period: YearMonthV2
    ) -> Snapshot {
        guard records.reliable else {
            return Snapshot(
                applied: [],
                totalGross: 0,
                reliable: false,
                warnings: records.warnings.isEmpty ? [storageWarning] : records.warnings
            )
        }

        let base = resolve(records: records.records, period: period)
        let matching = confirmations.reliable
            ? confirmations.confirmations.filter { $0.period == period }
            : []
        let coverageConfirmed = confirmations.reliable && matching.count == 1

        var coverageWarnings: [String] = []
        if !confirmations.reliable {
            coverageWarnings.append(contentsOf: confirmations.warnings.isEmpty
                ? [coverageStorageWarning]
                : confirmations.warnings)
        } else if matching.isEmpty {
            coverageWarnings.append(
                "Avantages en nature \(period) : liste mensuelle non confirmée exhaustive ; le total 0 € éventuel reste inconnu."
            )
        } else if matching.count > 1 {
            coverageWarnings.append(coverageStorageWarning)
        }

        return Snapshot(
            applied: base.applied,
            totalGross: base.totalGross,
            reliable: base.reliable && coverageConfirmed,
            warnings: unique(base.warnings + coverageWarnings)
        )
    }

    static func decodeRecords(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8) else {
            return ReadResult(records: [], reliable: false, warnings: [storageWarning])
        }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return ReadResult(records: [], reliable: false, warnings: [storageWarning])
            }
            var records: [Record] = []
            var malformed = false

            for object in array {
                guard let record = record(from: object) else {
                    malformed = true
                    continue
                }
                records.append(record)
            }

            let duplicateIds = Dictionary(grouping: records, by: \ .id).values.contains { $0.count > 1 }
            if duplicateIds { malformed = true }
            return ReadResult(
                records: records,
                reliable: !malformed,
                warnings: malformed ? [storageWarning] : []
            )
        } catch {
            return ReadResult(records: [], reliable: false, warnings: [storageWarning])
        }
    }

    static func decodeConfirmations(_ raw: String) -> ConfirmationReadResult {
        guard let data = raw.data(using: .utf8) else {
            return ConfirmationReadResult(confirmations: [], reliable: false, warnings: [coverageStorageWarning])
        }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return ConfirmationReadResult(confirmations: [], reliable: false, warnings: [coverageStorageWarning])
            }
            var confirmations: [MonthConfirmation] = []
            var malformed = false

            for object in array {
                guard let periodRaw = object["period"] as? String,
                      let period = YearMonthV2(periodRaw),
                      let sourceRaw = object["source"] as? String else {
                    malformed = true
                    continue
                }
                let source = sourceRaw.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !source.isEmpty else {
                    malformed = true
                    continue
                }
                confirmations.append(MonthConfirmation(period: period, source: source))
            }

            let duplicatePeriods = Dictionary(grouping: confirmations, by: \ .period).values.contains { $0.count > 1 }
            if duplicatePeriods { malformed = true }
            return ConfirmationReadResult(
                confirmations: confirmations,
                reliable: !malformed,
                warnings: malformed ? [coverageStorageWarning] : []
            )
        } catch {
            return ConfirmationReadResult(confirmations: [], reliable: false, warnings: [coverageStorageWarning])
        }
    }

    private static func record(from object: [String: Any]) -> Record? {
        guard let idRaw = object["id"] as? String,
              let label = object["label"] as? String,
              let value = object["grossValue"] as? NSNumber,
              let kindRaw = object["kind"] as? String,
              let kind = Kind(rawValue: kindRaw) else { return nil }

        let id = idRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let grossValue = value.doubleValue
        guard grossValue.isFinite, grossValue > 0 else { return nil }

        guard let effectiveFrom = optionalMonth(object["effectiveFrom"]),
              let effectiveTo = optionalMonth(object["effectiveTo"]),
              let paymentMonth = optionalMonth(object["paymentMonth"]) else { return nil }

        let record = Record(
            id: id,
            label: label,
            grossValue: grossValue,
            kind: kind,
            effectiveFrom: effectiveFrom,
            effectiveTo: effectiveTo,
            paymentMonth: paymentMonth
        )

        switch kind {
        case .monthly:
            guard let start = effectiveFrom,
                  paymentMonth == nil,
                  effectiveTo == nil || effectiveTo! >= start else { return nil }
        case .oneOff:
            guard paymentMonth != nil, effectiveFrom == nil, effectiveTo == nil else { return nil }
        }
        return record
    }

    /// Double optionnel : nil = champ absent/null valide ; Optional.some(nil) est représenté par nil retourné
    /// via Result afin de distinguer un mois invalide d'un champ absent.
    private static func optionalMonth(_ raw: Any?) -> YearMonthV2?? {
        guard let raw else { return .some(nil) }
        if raw is NSNull { return .some(nil) }
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" { return .some(nil) }
        guard let month = YearMonthV2(trimmed) else { return nil }
        return .some(month)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

struct SalaryReferenceContractV2 {
    let gross: Double
    let grossReliable: Bool
    let netBeforeIncomeTax: Double
    let netTaxable: Double?
    let complete: Bool
    let warnings: [String]
    let benefitsInKindDeduction: Double

    static func build(
        cashGross: Double,
        benefits: CompanyBenefitInKindContractV2.Snapshot,
        netBeforeIncomeTax: Double,
        netTaxable: Double?,
        additionalWarnings: [String] = []
    ) -> SalaryReferenceContractV2 {
        let normalizedCashGross = cashGross.isFinite ? max(0, cashGross) : 0
        let normalizedBenefits = benefits.totalGross.isFinite && benefits.totalGross >= 0
            ? benefits.totalGross
            : 0
        let gross = normalizedCashGross + normalizedBenefits
        let grossReliable = benefits.reliable
            && cashGross.isFinite
            && cashGross >= 0
            && benefits.totalGross.isFinite
            && benefits.totalGross >= 0
            && gross.isFinite

        var warnings = benefits.warnings + additionalWarnings
        if !grossReliable {
            warnings.insert(
                "Brut social : salaire ou avantages en nature du mois non confirmables ; seuls les éléments connus sont calculés, aucun total fiable n'est disponible.",
                at: 0
            )
        }
        warnings = uniqueWarnings(warnings)

        return SalaryReferenceContractV2(
            gross: gross,
            grossReliable: grossReliable,
            netBeforeIncomeTax: max(0, netBeforeIncomeTax),
            netTaxable: netTaxable,
            complete: warnings.isEmpty,
            warnings: warnings,
            benefitsInKindDeduction: normalizedBenefits
        )
    }

    static func socialGross(_ result: SalaryReferenceContractV2) -> Double? {
        guard result.grossReliable, result.gross.isFinite, result.gross >= 0 else { return nil }
        return result.gross
    }

    static func beforeIncomeTax(_ result: SalaryReferenceContractV2) -> Double? {
        guard result.complete,
              socialGross(result) != nil,
              result.netBeforeIncomeTax.isFinite,
              result.netBeforeIncomeTax >= 0 else { return nil }
        return result.netBeforeIncomeTax
    }

    static func taxable(_ result: SalaryReferenceContractV2) -> Double? {
        guard beforeIncomeTax(result) != nil,
              let amount = result.netTaxable,
              amount.isFinite,
              amount >= 0 else { return nil }
        return amount
    }

    private static func uniqueWarnings(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
