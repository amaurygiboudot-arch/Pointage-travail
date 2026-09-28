import Foundation

/// Calendrier déterministe des jours fériés légaux français utilisé uniquement pour
/// affecter des minutes réellement travaillées. Il ne décide jamais d'un taux de paie.
///
/// Port iOS de la couche Android existante : le 1er mai reste séparé, les territoires
/// particuliers sont explicites et un périmètre non exhaustif reste fail-closed.
enum FrenchPublicHolidayCalendarV2 {
    enum Jurisdiction: Equatable {
        case commonFrance
        case alsaceMoselle
        case guadeloupe
        case martinique
        case guyane
        case mayotte
        case reunion
        case saintBarthelemy
        case saintMartin
        case specialTerritoryUnknown
        case addressUnknown
    }

    struct Scope: Equatable {
        let jurisdiction: Jurisdiction
        /// false = des jours locaux peuvent exister sans pouvoir être confirmés depuis l'adresse seule.
        let complete: Bool
        let postalCode: String?
        let warning: String?
    }

    static func scopeForAddress(_ address: String) -> Scope {
        guard let postal = postalCode(in: address) else {
            return Scope(
                jurisdiction: .addressUnknown,
                complete: false,
                postalCode: nil,
                warning: "Adresse de l'entreprise insuffisante pour confirmer le calendrier territorial des jours fériés."
            )
        }

        switch postal {
        case "97133":
            return Scope(jurisdiction: .saintBarthelemy, complete: true, postalCode: postal, warning: nil)
        case "97150":
            return Scope(jurisdiction: .saintMartin, complete: true, postalCode: postal, warning: nil)
        default:
            break
        }

        if postal.hasPrefix("971") {
            return Scope(jurisdiction: .guadeloupe, complete: true, postalCode: postal, warning: nil)
        }
        if postal.hasPrefix("972") {
            return Scope(jurisdiction: .martinique, complete: true, postalCode: postal, warning: nil)
        }
        if postal.hasPrefix("973") {
            return Scope(jurisdiction: .guyane, complete: true, postalCode: postal, warning: nil)
        }
        if postal.hasPrefix("974") {
            return Scope(jurisdiction: .reunion, complete: true, postalCode: postal, warning: nil)
        }
        if postal.hasPrefix("976") {
            return Scope(jurisdiction: .mayotte, complete: true, postalCode: postal, warning: nil)
        }
        if postal.hasPrefix("98") {
            return Scope(
                jurisdiction: .specialTerritoryUnknown,
                complete: false,
                postalCode: postal,
                warning: "Territoire à calendrier local : validation juridique des jours fériés requise avant calcul automatique."
            )
        }
        if postal.hasPrefix("57") || postal.hasPrefix("67") || postal.hasPrefix("68") {
            return Scope(
                jurisdiction: .alsaceMoselle,
                complete: false,
                postalCode: postal,
                warning: "Alsace-Moselle : le Vendredi saint dépend de la commune ; le calendrier ne peut pas être déclaré exhaustif depuis le seul code postal."
            )
        }

        return Scope(
            jurisdiction: .commonFrance,
            complete: true,
            postalCode: postal,
            warning: nil
        )
    }

    /// Jours pouvant recevoir une règle collective générique, hors 1er mai.
    static func genericHolidays(
        year: Int,
        scope: Scope
    ) -> Set<PayrollCivilDateV2>? {
        guard (1900...2200).contains(year),
              let easter = easterSunday(year) else {
            return nil
        }

        var values = Set<PayrollCivilDateV2>()
        [
            date(year, 1, 1),
            addingDays(easter, 1),
            date(year, 5, 8),
            addingDays(easter, 39),
            addingDays(easter, 50),
            date(year, 7, 14),
            date(year, 8, 15),
            date(year, 11, 1),
            date(year, 11, 11),
            date(year, 12, 25)
        ].compactMap { $0 }.forEach { values.insert($0) }

        let local: PayrollCivilDateV2?
        switch scope.jurisdiction {
        case .alsaceMoselle:
            local = date(year, 12, 26)
        case .guadeloupe, .saintMartin:
            local = date(year, 5, 27)
        case .martinique:
            local = date(year, 5, 22)
        case .guyane:
            local = date(year, 6, 10)
        case .mayotte:
            local = date(year, 4, 27)
        case .reunion:
            local = date(year, 12, 20)
        case .saintBarthelemy:
            local = date(year, 10, 9)
        case .commonFrance, .specialTerritoryUnknown, .addressUnknown:
            local = nil
        }
        if let local { values.insert(local) }
        return values
    }

    /// Dates potentiellement fériées que le périmètre disponible ne permet pas de trancher.
    static func unresolvedPossibleHolidays(
        year: Int,
        scope: Scope
    ) -> Set<PayrollCivilDateV2>? {
        guard (1900...2200).contains(year),
              let easter = easterSunday(year) else {
            return nil
        }

        switch scope.jurisdiction {
        case .alsaceMoselle:
            guard let goodFriday = addingDays(easter, -2) else { return nil }
            return [goodFriday]
        case .addressUnknown, .specialTerritoryUnknown:
            return Set(
                [
                    addingDays(easter, -2),
                    date(year, 4, 27),
                    date(year, 5, 22),
                    date(year, 5, 27),
                    date(year, 6, 10),
                    date(year, 10, 9),
                    date(year, 12, 20),
                    date(year, 12, 26)
                ].compactMap { $0 }
            )
        default:
            return []
        }
    }

    static func mayFirst(_ year: Int) -> PayrollCivilDateV2? {
        guard (1900...2200).contains(year) else { return nil }
        return date(year, 5, 1)
    }

    static func isGenericHoliday(
        _ value: PayrollCivilDateV2,
        scope: Scope
    ) -> Bool {
        genericHolidays(year: value.year, scope: scope)?.contains(value) == true
    }

    static func isMayFirst(_ value: PayrollCivilDateV2) -> Bool {
        value.month == 5 && value.day == 1
    }

    /// Algorithme grégorien de Meeus/Jones/Butcher, identique au moteur Android.
    static func easterSunday(_ year: Int) -> PayrollCivilDateV2? {
        guard (1900...2200).contains(year) else { return nil }
        let a = year % 19
        let b = year / 100
        let c = year % 100
        let d = b / 4
        let e = b % 4
        let f = (b + 8) / 25
        let g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4
        let k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = (h + l - 7 * m + 114) % 31 + 1
        return date(year, month, day)
    }

    private static func postalCode(in address: String) -> String? {
        let pattern = #"(?<!\d)(\d{5})(?!\d)"#
        guard let range = address.range(of: pattern, options: .regularExpression) else {
            return nil
        }
        return String(address[range])
    }

    private static func date(_ year: Int, _ month: Int, _ day: Int) -> PayrollCivilDateV2? {
        PayrollCivilDateV2(year: year, month: month, day: day)
    }

    private static func addingDays(
        _ value: PayrollCivilDateV2,
        _ offset: Int
    ) -> PayrollCivilDateV2? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let base = calendar.date(
            from: DateComponents(year: value.year, month: value.month, day: value.day)
        ),
        let result = calendar.date(byAdding: .day, value: offset, to: base) else {
            return nil
        }
        let components = calendar.dateComponents([.year, .month, .day], from: result)
        guard let year = components.year,
              let month = components.month,
              let day = components.day else {
            return nil
        }
        return PayrollCivilDateV2(year: year, month: month, day: day)
    }
}
