import Foundation

/// Miroir iOS du contrat Android CompanyPremiumResolverV2.
/// Résout uniquement les primes contractuelles/personnelles explicitement enregistrées.
enum CompanyPremiumContractV2 {
    enum Kind: String, Codable {
        case monthly = "MONTHLY"
        case oneOff = "ONE_OFF"
    }

    struct Record: Equatable {
        let id: String
        let label: String
        let grossAmount: Double
        let kind: Kind
        let effectiveFrom: YearMonthV2?
        let effectiveTo: YearMonthV2?
        let paymentMonth: YearMonthV2?
    }

    struct Applied: Equatable {
        let id: String
        let label: String
        let grossAmount: Double
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

    static let storageWarning =
        "Primes contractuelles/personnelles : stockage local incohérent ; calcul du brut bloqué."
    static let coverageStorageWarning =
        "Primes contractuelles/personnelles : stockage des confirmations mensuelles incohérent ; calcul du brut bloqué."

    static func companyUnavailableReadResult() -> ReadResult {
        .init(
            records: [],
            reliable: false,
            warnings: ["Primes contractuelles/personnelles : entreprise absente ou stockage des entreprises non fiable."]
        )
    }

    static func companyUnavailableConfirmationResult() -> ConfirmationReadResult {
        .init(
            confirmations: [],
            reliable: false,
            warnings: ["Primes contractuelles/personnelles : entreprise absente ou stockage des entreprises non fiable."]
        )
    }

    static func resolve(records: [Record], period: YearMonthV2) -> Snapshot {
        var applied: [Applied] = []
        var warnings: [String] = []
        var reliable = true

        for record in records {
            let label = record.label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !record.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !label.isEmpty,
                  record.grossAmount.isFinite,
                  record.grossAmount > 0 else {
                reliable = false
                warnings.append("Prime entreprise invalide : libellé ou montant brut à vérifier.")
                continue
            }

            switch record.kind {
            case .monthly:
                guard let start = record.effectiveFrom else {
                    reliable = false
                    warnings.append("Prime mensuelle « \(record.label) » : mois de début manquant.")
                    continue
                }
                if let end = record.effectiveTo, end < start {
                    reliable = false
                    warnings.append("Prime mensuelle « \(record.label) » : période invalide.")
                    continue
                }
                guard record.paymentMonth == nil else {
                    reliable = false
                    warnings.append("Prime mensuelle « \(record.label) » : mois de versement ponctuel inattendu.")
                    continue
                }
                if period >= start && (record.effectiveTo == nil || period <= record.effectiveTo!) {
                    applied.append(.init(id: record.id, label: record.label, grossAmount: record.grossAmount))
                }

            case .oneOff:
                guard let payment = record.paymentMonth,
                      record.effectiveFrom == nil,
                      record.effectiveTo == nil else {
                    reliable = false
                    warnings.append("Prime ponctuelle « \(record.label) » : mois de versement manquant ou période invalide.")
                    continue
                }
                if payment == period {
                    applied.append(.init(id: record.id, label: record.label, grossAmount: record.grossAmount))
                }
            }
        }

        let total = applied.reduce(0) { $0 + $1.grossAmount }
        guard total.isFinite, total >= 0 else {
            return .init(applied: [], totalGross: 0, reliable: false,
                         warnings: unique(warnings + ["Primes contractuelles/personnelles : total brut non représentable."]))
        }
        return .init(applied: applied, totalGross: total, reliable: reliable, warnings: unique(warnings))
    }

    static func resolve(
        records: ReadResult,
        confirmations: ConfirmationReadResult,
        period: YearMonthV2
    ) -> Snapshot {
        guard records.reliable else {
            return .init(
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
                "Primes contractuelles/personnelles \(period) : liste mensuelle non confirmée exhaustive ; le total 0 € éventuel reste inconnu."
            )
        } else if matching.count > 1 {
            coverageWarnings.append(coverageStorageWarning)
        }

        return .init(
            applied: base.applied,
            totalGross: base.totalGross,
            reliable: base.reliable && coverageConfirmed,
            warnings: unique(base.warnings + coverageWarnings)
        )
    }

    static func decodeRecords(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return .init(records: [], reliable: false, warnings: [storageWarning])
        }
        var records: [Record] = []
        var malformed = false
        for item in array {
            guard let id = (item["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !id.isEmpty,
                  let label = item["label"] as? String,
                  let amount = item["grossAmount"] as? NSNumber,
                  let kindRaw = item["kind"] as? String,
                  let kind = Kind(rawValue: kindRaw),
                  let from = optionalMonth(item["effectiveFrom"]),
                  let to = optionalMonth(item["effectiveTo"]),
                  let payment = optionalMonth(item["paymentMonth"]) else {
                malformed = true
                continue
            }
            records.append(.init(
                id: id,
                label: label,
                grossAmount: amount.doubleValue,
                kind: kind,
                effectiveFrom: from,
                effectiveTo: to,
                paymentMonth: payment
            ))
        }
        if Set(records.map(\.id)).count != records.count { malformed = true }
        return .init(records: records, reliable: !malformed, warnings: malformed ? [storageWarning] : [])
    }

    static func decodeConfirmations(_ raw: String) -> ConfirmationReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return .init(confirmations: [], reliable: false, warnings: [coverageStorageWarning])
        }
        var confirmations: [MonthConfirmation] = []
        var malformed = false
        for item in array {
            guard let periodRaw = item["period"] as? String,
                  let period = YearMonthV2(periodRaw),
                  let source = (item["source"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !source.isEmpty else {
                malformed = true
                continue
            }
            confirmations.append(.init(period: period, source: source))
        }
        if Set(confirmations.map(\.period)).count != confirmations.count { malformed = true }
        return .init(
            confirmations: confirmations,
            reliable: !malformed,
            warnings: malformed ? [coverageStorageWarning] : []
        )
    }

    private static func optionalMonth(_ value: Any?) -> YearMonthV2?? {
        if value == nil || value is NSNull { return .some(nil) }
        guard let raw = value as? String else { return nil }
        if raw.isEmpty || raw == "null" { return .some(nil) }
        guard let month = YearMonthV2(raw) else { return nil }
        return .some(month)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
