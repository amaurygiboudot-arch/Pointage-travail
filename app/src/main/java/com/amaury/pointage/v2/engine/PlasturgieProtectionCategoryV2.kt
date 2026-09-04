package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Catégorie objective de protection sociale complémentaire Plasturgie.
 * Accord du 27 juin 2024, étendu, applicable à compter du 01/01/2025.
 *
 * La catégorie est déduite du coefficient conventionnel : aucune saisie ANI
 * parallèle n'est créée dans HoraTrack.
 */
object PlasturgieProtectionCategoryV2 {
    const val IDCC = "292"
    val EFFECTIVE_FROM: LocalDate = LocalDate.of(2025, 1, 1)

    enum class Category {
        ARTICLE_2_1,
        ARTICLE_2_2,
        EXTENSION_ELIGIBLE,
        OUTSIDE_2_1_2_2,
        TO_CONFIRM,
        NOT_APPLICABLE
    }

    data class Result(
        val category: Category,
        val confirmed: Boolean,
        val coefficient: Int?,
        val warnings: List<String>
    )

    fun classify(
        idcc: String?,
        referenceDate: LocalDate,
        coefficient: Int?
    ): Result {
        val normalized = idcc.orEmpty().filter(Char::isDigit).trimStart('0')
        if (normalized != IDCC) {
            return Result(Category.NOT_APPLICABLE, true, coefficient, emptyList())
        }
        if (referenceDate.isBefore(EFFECTIVE_FROM)) {
            return Result(
                Category.TO_CONFIRM,
                false,
                coefficient,
                listOf("Catégorie prévoyance Plasturgie : le classement ANI automatique utilisé par HoraTrack est applicable à compter du 01/01/2025.")
            )
        }
        if (coefficient == null) {
            return Result(
                Category.TO_CONFIRM,
                false,
                null,
                listOf("Catégorie prévoyance Plasturgie : coefficient conventionnel manquant.")
            )
        }

        val category = when {
            coefficient in 900..940 -> Category.ARTICLE_2_1
            coefficient == 830 -> Category.ARTICLE_2_2
            coefficient in 800..820 -> Category.EXTENSION_ELIGIBLE
            else -> Category.OUTSIDE_2_1_2_2
        }
        val warning = when (category) {
            Category.EXTENSION_ELIGIBLE -> listOf(
                "Coefficient $coefficient : hors ANI 2.1/2.2, mais susceptible d'être intégré au régime de protection sociale complémentaire des cadres par extension ; le contrat d'entreprise reste à contrôler."
            )
            else -> emptyList()
        }
        return Result(category, true, coefficient, warning)
    }

    fun label(result: Result): String = label(result.category)

    fun label(category: Category): String = when (category) {
        Category.ARTICLE_2_1 -> "ANI 2.1 — cadre"
        Category.ARTICLE_2_2 -> "ANI 2.2 — assimilé cadre"
        Category.EXTENSION_ELIGIBLE -> "Hors ANI 2.1/2.2 — extension régime cadres possible"
        Category.OUTSIDE_2_1_2_2 -> "Hors ANI 2.1/2.2"
        Category.TO_CONFIRM -> "À confirmer"
        Category.NOT_APPLICABLE -> "Non applicable"
    }
}
