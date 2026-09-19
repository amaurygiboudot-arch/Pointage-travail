import Foundation

/// Relation générique du salarié aux catégories objectives ANI cadres/assimilés.
///
/// Ce type n'encode aucune convention collective particulière. Une convention peut fournir
/// un résolveur qui établit cette relation à partir de sa propre classification. Quand aucune
/// convention ne fournit d'override, les moteurs nationaux conservent un repli prudent sur
/// le statut professionnel explicite du salarié.
enum ProtectionCategoryV2 {
    enum AniCategory: Equatable {
        case article2_1
        case article2_2
        case extensionEligible
        case outside2_1_2_2
        case toConfirm
        case noConventionOverride
    }

    struct Result: Equatable {
        let aniCategory: AniCategory
        let confirmed: Bool
        let source: String?
        let warnings: [String]

        init(
            aniCategory: AniCategory,
            confirmed: Bool,
            source: String? = nil,
            warnings: [String] = []
        ) {
            self.aniCategory = aniCategory
            self.confirmed = confirmed
            self.source = source
            self.warnings = warnings
        }

        /// Vrai uniquement lorsqu'une convention apporte réellement une classification ANI.
        var conventionControlsAni: Bool {
            aniCategory != .noConventionOverride
        }

        var aniBeneficiaryConfirmed: Bool {
            confirmed && (aniCategory == .article2_1 || aniCategory == .article2_2)
        }
    }

    static func noConventionOverride() -> Result {
        Result(aniCategory: .noConventionOverride, confirmed: true)
    }

    static func label(_ result: Result) -> String {
        switch result.aniCategory {
        case .article2_1:
            return "ANI 2.1 — cadre"
        case .article2_2:
            return "ANI 2.2 — assimilé cadre"
        case .extensionEligible:
            return "Hors ANI 2.1/2.2 — extension régime cadres possible"
        case .outside2_1_2_2:
            return "Hors ANI 2.1/2.2"
        case .toConfirm:
            return "À confirmer"
        case .noConventionOverride:
            return "Aucun classement conventionnel ANI spécifique"
        }
    }
}
