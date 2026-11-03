import Foundation

/// Frontière monétaire de la projection : décimal, HALF_UP, puis centimes signés.
/// La borne commune aux plateformes est technique ; elle ne définit aucun plafond de paie.
enum PayrollMoneyV2 {
    static let maximumCents: Int64 = 9_007_199_254_740_991
    private static let locale = Locale(identifier: "en_US_POSIX")

    static func cents(_ value: Double) -> Int64? {
        guard value.isFinite else { return nil }
        // Inclut les sous-normaux Double hors exposants Decimal : l'arrondi est exactement zéro.
        if abs(value) < 0.005 { return 0 }
        guard let decimal = decimal(value), let result = rawCents(decimal),
              amount(result) != nil else { return nil }
        return result
    }

    static func contribution(base: Double, rate: Double) -> Int64? {
        guard base >= 0, rate >= 0,
              var decimalBase = decimal(base), var decimalRate = decimal(rate) else { return nil }
        var product = Decimal()
        guard NSDecimalMultiply(&product, &decimalBase, &decimalRate, .plain) == .noError,
              let result = rawCents(product), amount(result) != nil else { return nil }
        return result
    }

    static func sum(_ values: [Int64]) -> Int64? {
        var result: Int64 = 0
        for value in values {
            guard withinBounds(value) else { return nil }
            let addition = result.addingReportingOverflow(value)
            guard !addition.overflow, withinBounds(addition.partialValue) else { return nil }
            result = addition.partialValue
        }
        return amount(result) == nil ? nil : result
    }

    static func tax(baseCents: Int64, rate: Double) -> Int64? {
        guard baseCents >= 0, withinBounds(baseCents), rate.isFinite, rate >= 0, rate <= 1 else { return nil }
        // Borne stricte à un quart de centime, donc éloignée du demi-centime d'arrondi.
        // Elle couvre les taux sous-normaux que Decimal ne peut pas représenter.
        if rate < 0.25 / Double(maximumCents) { return 0 }
        guard var decimalRate = decimal(rate) else { return nil }
        var base = Decimal(baseCents)
        var product = Decimal()
        guard NSDecimalMultiply(&product, &base, &decimalRate, .plain) == .noError else { return nil }
        // Le produit est déjà exprimé en centimes : ne pas passer par un Double intermédiaire.
        var rounded = Decimal()
        NSDecimalRound(&rounded, &product, 0, .plain)
        guard let result = integerCents(rounded), amount(result) != nil else { return nil }
        return result
    }

    static func amount(_ cents: Int64) -> Double? {
        guard withinBounds(cents) else { return nil }
        var decimalCents = Decimal(cents)
        var hundred = Decimal(100)
        var decimalAmount = Decimal()
        guard NSDecimalDivide(&decimalAmount, &decimalCents, &hundred, .plain) == .noError else { return nil }
        let result = NSDecimalNumber(decimal: decimalAmount).doubleValue
        guard let returned = decimal(result), rawCents(returned) == cents else { return nil }
        return result
    }

    private static func decimal(_ value: Double) -> Decimal? {
        guard value.isFinite else { return nil }
        return Decimal(string: String(value), locale: locale)
    }

    private static func rawCents(_ value: Decimal) -> Int64? {
        var value = value
        var hundred = Decimal(100)
        var scaled = Decimal()
        guard NSDecimalMultiply(&scaled, &value, &hundred, .plain) == .noError else { return nil }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        return integerCents(rounded)
    }

    private static func integerCents(_ value: Decimal) -> Int64? {
        guard !value.isNaN,
              value >= Decimal(-maximumCents), value <= Decimal(maximumCents) else { return nil }
        let result = NSDecimalNumber(decimal: value).int64Value
        return Decimal(result) == value ? result : nil
    }

    private static func withinBounds(_ value: Int64) -> Bool {
        value >= -maximumCents && value <= maximumCents
    }
}
