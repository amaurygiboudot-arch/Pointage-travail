import Foundation

/// Stockage local des taux PAS confirmés par mois pour l'écran Salaire V2 iOS.
/// Un taux n'est jamais appliqué hors de sa période explicite.
final class CompanyIncomeTaxRateStoreV2 {
    private struct StoredRecord: Codable {
        let id: String
        let ratePercent: Double
        let from: String
        let to: String?
        let source: String
    }

    private let defaults: UserDefaults
    private func key(companyId: String) -> String {
        "income_tax_rates_v2." + companyId.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func snapshot(companyId: String, for period: YearMonthV2) -> CompanyIncomeTaxRateResolverV2.Snapshot {
        let storageKey = key(companyId: companyId)
        guard !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .init(rate: nil, ratePercent: nil, source: nil, hasDatedRecords: false, reliable: false, warnings: ["PAS : entreprise non confirmée ; calcul après impôt bloqué."])
        }
        guard let data = defaults.data(forKey: storageKey) else {
            return CompanyIncomeTaxRateResolverV2.resolve(records: [], period: period)
        }
        do {
            let stored = try JSONDecoder().decode([StoredRecord].self, from: data)
            let records: [CompanyIncomeTaxRateResolverV2.Record] = try stored.map {
                guard let from = YearMonthV2($0.from) else { throw StoreError.invalidPeriod }
                let to: YearMonthV2?
                if let rawTo = $0.to {
                    guard let parsed = YearMonthV2(rawTo) else { throw StoreError.invalidPeriod }
                    to = parsed
                } else {
                    to = nil
                }
                return CompanyIncomeTaxRateResolverV2.Record(
                    id: $0.id,
                    ratePercent: $0.ratePercent,
                    effectiveFrom: from,
                    effectiveTo: to,
                    source: $0.source
                )
            }
            return CompanyIncomeTaxRateResolverV2.resolve(records: records, period: period)
        } catch {
            return .init(
                rate: nil,
                ratePercent: nil,
                source: nil,
                hasDatedRecords: true,
                reliable: false,
                warnings: ["PAS : stockage local incohérent ; calcul après impôt bloqué."]
            )
        }
    }

    private enum StoreError: Error { case invalidPeriod }

    @discardableResult
    func confirm(companyId: String, ratePercent: Double, period: YearMonthV2, source: String) -> Bool {
        let storageKey = key(companyId: companyId)
        guard !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        let trimmed = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ratePercent.isFinite, (0...100).contains(ratePercent), !trimmed.isEmpty else { return false }

        var records: [StoredRecord] = []
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([StoredRecord].self, from: data) {
            records = decoded.filter { $0.from != period.description }
        } else if defaults.object(forKey: storageKey) != nil {
            return false
        }

        records.append(.init(
            id: "pas-\(period.description)",
            ratePercent: ratePercent,
            from: period.description,
            to: period.description,
            source: trimmed
        ))
        guard let data = try? JSONEncoder().encode(records) else { return false }
        defaults.set(data, forKey: storageKey)
        return true
    }
}
