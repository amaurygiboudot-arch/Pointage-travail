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
    private let key = "income_tax_rates_v2"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func snapshot(for period: YearMonthV2) -> CompanyIncomeTaxRateResolverV2.Snapshot {
        guard let data = defaults.data(forKey: key) else {
            return CompanyIncomeTaxRateResolverV2.resolve(records: [], period: period)
        }
        do {
            let stored = try JSONDecoder().decode([StoredRecord].self, from: data)
            let records = stored.map {
                CompanyIncomeTaxRateResolverV2.Record(
                    id: $0.id,
                    ratePercent: $0.ratePercent,
                    effectiveFrom: YearMonthV2($0.from),
                    effectiveTo: $0.to.flatMap(YearMonthV2.init),
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

    @discardableResult
    func confirm(ratePercent: Double, period: YearMonthV2, source: String) -> Bool {
        let trimmed = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ratePercent.isFinite, (0...100).contains(ratePercent), !trimmed.isEmpty else { return false }

        var records: [StoredRecord] = []
        if let data = defaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode([StoredRecord].self, from: data) {
            records = decoded.filter { $0.from != period.description }
        } else if defaults.object(forKey: key) != nil {
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
        defaults.set(data, forKey: key)
        return true
    }
}
