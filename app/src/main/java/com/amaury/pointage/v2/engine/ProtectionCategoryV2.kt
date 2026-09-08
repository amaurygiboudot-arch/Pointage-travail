package com.amaury.pointage.v2.engine

/**
 * Relation générique du salarié aux catégories objectives ANI cadres/assimilés.
 *
 * Ce type n'encode aucune convention collective particulière. Une convention peut fournir
 * un résolveur qui établit cette relation à partir de sa propre classification. Quand aucune
 * convention ne fournit d'override, les moteurs nationaux conservent leur repli prudent sur
 * le statut professionnel explicite du salarié.
 */
object ProtectionCategoryV2 {
    enum class AniCategory {
        ARTICLE_2_1,
        ARTICLE_2_2,
        EXTENSION_ELIGIBLE,
        OUTSIDE_2_1_2_2,
        TO_CONFIRM,
        NO_CONVENTION_OVERRIDE
    }

    data class Result(
        val aniCategory: AniCategory,
        val confirmed: Boolean,
        val source: String? = null,
        val warnings: List<String> = emptyList()
    ) {
        /** Vrai uniquement lorsqu'une convention apporte réellement une classification ANI. */
        val conventionControlsAni: Boolean
            get() = aniCategory != AniCategory.NO_CONVENTION_OVERRIDE

        val aniBeneficiaryConfirmed: Boolean
            get() = confirmed && (aniCategory == AniCategory.ARTICLE_2_1 || aniCategory == AniCategory.ARTICLE_2_2)
    }

    fun noConventionOverride(): Result = Result(
        aniCategory = AniCategory.NO_CONVENTION_OVERRIDE,
        confirmed = true
    )

    fun label(result: Result): String = when (result.aniCategory) {
        AniCategory.ARTICLE_2_1 -> "ANI 2.1 — cadre"
        AniCategory.ARTICLE_2_2 -> "ANI 2.2 — assimilé cadre"
        AniCategory.EXTENSION_ELIGIBLE -> "Hors ANI 2.1/2.2 — extension régime cadres possible"
        AniCategory.OUTSIDE_2_1_2_2 -> "Hors ANI 2.1/2.2"
        AniCategory.TO_CONFIRM -> "À confirmer"
        AniCategory.NO_CONVENTION_OVERRIDE -> "Aucun classement conventionnel ANI spécifique"
    }
}
