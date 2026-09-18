import Foundation

enum CompanyEmployeeDeductionResolverV2 {
    enum Kind: String, Codable, CaseIterable, Hashable {
        case mutualEmployee = "MUTUAL_EMPLOYEE"
        case providentEmployee = "PROVIDENT_EMPLOYEE"
        case transportEmployee = "TRANSPORT_EMPLOYEE"
        case employerProtectionTaxable = "EMPLOYER_PROTECTION_TAXABLE"
        case employerProtectionCsgCrdsBase = "EMPLOYER_PROTECTION_CSG_CRDS_BASE"
        case employeeProvidentNonDeductible = "EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE"

        var label: String {
            switch self {
            case .mutualEmployee:
                return "Mutuelle salariale"
            case .providentEmployee:
                return "Prévoyance salariale entreprise"
            case .transportEmployee:
                return "Retenue transport"
            case .employerProtectionTaxable:
                return "Part employeur mutuelle/prévoyance réintégrable au net imposable"
            case .employerProtectionCsgCrdsBase:
                return "Part employeur protection sociale complémentaire soumise à CSG/CRDS"
            case .employeeProvidentNonDeductible:
                return "Part salariale de prévoyance non déductible"
            }
        }
    }

    struct YearMonth: Codable, Hashable, Comparable, CustomStringConvertible {
        let year: Int
        let month: Int

        init?(year: Int, month: Int) {
            guard year >= 1, (1...12).contains(month) else { return nil }
            self.year = year
            self.month = month
        }

        init?(iso8601: String) {
            let parts = iso8601.split(separator: "-", omittingEmptySubsequences: false)
            guard parts.count == 2,
                  parts[0].count == 4,
                  parts[1].count == 2,
                  let year = Int(parts[0]),
                  let month = Int(parts[1]),
                  year >= 1,
                  (1...12).contains(month) else {
                return nil
            }
            self.year = year
            self.month = month
        }

        var description: String {
            String(format: "%04d-%02d", year, month)
        }

        static func < (lhs: YearMonth, rhs: YearMonth) -> Bool {
            lhs.year == rhs.year ? lhs.month < rhs.month : lhs.year < rhs.year
        }
    }

    struct Record: Equatable {
        let id: String
        let kind: Kind
        let amount: Double
        let effectiveFrom: YearMonth?
        let effectiveTo: YearMonth?
        let source: String?

        init(
            id: String,
            kind: Kind,
            amount: Double,
            effectiveFrom: YearMonth?,
            effectiveTo: YearMonth? = nil,
            source: String? = nil
        ) {
            self.id = id
            self.kind = kind
            self.amount = amount
            self.effectiveFrom = effectiveFrom
            self.effectiveTo = effectiveTo
            self.source = source
        }
    }

    struct Value: Equatable {
        let amount: Double?
        let source: String?
        let hasDatedRecords: Bool
        let reliable: Bool
        let legacyUsed: Bool
        let warnings: [String]

        init(
            amount: Double?,
            source: String?,
            hasDatedRecords: Bool,
            reliable: Bool,
            legacyUsed: Bool = false,
            warnings: [String] = []
        ) {
            self.amount = amount
            self.source = source
            self.hasDatedRecords = hasDatedRecords
            self.reliable = reliable
            self.legacyUsed = legacyUsed
            self.warnings = warnings
        }
    }

    struct Snapshot: Equatable {
        let values: [Kind: Value]
        let warnings: [String]

        subscript(kind: Kind) -> Value {
            values[kind] ?? Value(
                amount: nil,
                source: nil,
                hasDatedRecords: true,
                reliable: false,
                warnings: ["\(kind.label) : état absent, calcul bloqué."]
            )
        }
    }

    static func resolve(records: [Record], period: YearMonth) -> Snapshot {
        var values: [Kind: Value] = [:]

        for kind in Kind.allCases {
            let own = records.filter { $0.kind == kind }
            guard !own.isEmpty else {
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: false,
                    reliable: true
                )
                continue
            }

            guard own.allSatisfy(valid) else {
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: true,
                    reliable: false,
                    warnings: ["\(kind.label) : période, montant ou source datée invalide, calcul bloqué."]
                )
                continue
            }

            let applicable = own.filter { record in
                guard let start = record.effectiveFrom else { return false }
                return period >= start && (record.effectiveTo == nil || period <= record.effectiveTo!)
            }

            switch applicable.count {
            case 0:
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: true,
                    reliable: false,
                    warnings: ["\(kind.label) : aucune période confirmée pour \(period)."]
                )
            case 1:
                let record = applicable[0]
                values[kind] = Value(
                    amount: record.amount,
                    source: record.source?.trimmingCharacters(in: .whitespacesAndNewlines),
                    hasDatedRecords: true,
                    reliable: true
                )
            default:
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: true,
                    reliable: false,
                    warnings: ["\(kind.label) : plusieurs périodes se chevauchent pour \(period), calcul bloqué."]
                )
            }
        }

        return Snapshot(
            values: values,
            warnings: unique(values.values.flatMap(\.warnings))
        )
    }

    static func withLegacyFallback(
        snapshot: Snapshot,
        legacyAmounts: [Kind: Double?]
    ) -> Snapshot {
        var values: [Kind: Value] = [:]

        for kind in Kind.allCases {
            let dated = snapshot[kind]
            if dated.hasDatedRecords {
                values[kind] = dated
                continue
            }

            guard let wrapped = legacyAmounts[kind], let legacy = wrapped else {
                values[kind] = dated
                continue
            }

            if !legacy.isFinite || legacy < 0 {
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: false,
                    reliable: false,
                    warnings: ["\(kind.label) : ancienne valeur non datée invalide ; confirmer une valeur datée et sourcée."]
                )
            } else {
                values[kind] = Value(
                    amount: nil,
                    source: nil,
                    hasDatedRecords: false,
                    reliable: false,
                    warnings: ["\(kind.label) : ancienne valeur non datée détectée mais non utilisée par Salaire V2 ; confirmer sa période et sa source."]
                )
            }
        }

        return Snapshot(
            values: values,
            warnings: unique(values.values.flatMap(\.warnings))
        )
    }

    static func valid(_ record: Record) -> Bool {
        guard !record.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              record.amount.isFinite,
              record.amount >= 0,
              let source = record.source?.trimmingCharacters(in: .whitespacesAndNewlines),
              !source.isEmpty,
              let start = record.effectiveFrom else {
            return false
        }
        return record.effectiveTo == nil || record.effectiveTo! >= start
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
