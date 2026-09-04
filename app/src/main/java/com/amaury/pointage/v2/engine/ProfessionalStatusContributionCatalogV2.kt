package com.amaury.pointage.v2.engine

import kotlin.math.min

/** Règles de cotisations/contributions dépendant explicitement du statut professionnel. */
object ProfessionalStatusContributionCatalogV2 {
    data class Line(
        val id: String,
        val label: String,
        val baseAmount: Double,
        val employerRate: Double,
        val employerAmount: Double,
        val source: String
    )

    data class Estimate(
        val lines: List<Line>,
        val employerContributions: Double,
        val warnings: List<String>
    )

    private const val SOURCE = "Légifrance — ANI du 17/11/2017 relatif à la prévoyance des cadres, article 1er"

    fun estimate(
        gross: Double,
        year: Int,
        professionalStatus: String?,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null,
        protectionCategory: PlasturgieProtectionCategoryV2.Result? = null
    ): Estimate {
        val g = gross.coerceAtLeast(0.0)
        val full = SocialSecurityCeilingV2.fullMonthly(year)
            ?: return Estimate(emptyList(), 0.0, listOf("Prévoyance cadre : barème non intégré pour $year"))

        val status = professionalStatus?.trim()?.uppercase()
        val category = protectionCategory?.category
        val categoryControlsAni = category != null && category != PlasturgieProtectionCategoryV2.Category.NOT_APPLICABLE
        val aniBeneficiary = when {
            !categoryControlsAni -> status == "CADRE"
            protectionCategory?.confirmed != true -> false
            category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1 -> true
            category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_2 -> true
            else -> false
        }
        val applicable = ceiling?.applicableMonthly ?: full
        val lines = buildList {
            if (aniBeneficiary && g > 0.0) {
                val base = min(g, applicable)
                add(
                    Line(
                        id = "cadre_provident_employer_minimum",
                        label = "Prévoyance ANI cadres/assimilés - minimum employeur",
                        baseAmount = base,
                        employerRate = 0.015,
                        employerAmount = base * 0.015,
                        source = SOURCE
                    )
                )
            }
        }

        val warnings = buildList {
            when {
                categoryControlsAni && protectionCategory?.confirmed != true ->
                    add("Catégorie ANI 2.1/2.2 à confirmer : minimum employeur de 1,50 % non calculé.")
                category == PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE ->
                    add("Coefficient éligible à une extension du régime cadres : le 1,50 % ANI n'est pas appliqué automatiquement sans confirmation du régime d'entreprise.")
                !categoryControlsAni && status != "CADRE" && status != "NON_CADRE" ->
                    add("Statut professionnel à préciser : prévoyance cadre minimale non calculée.")
            }
            ceiling?.warnings?.let(::addAll)
        }.distinct()

        return Estimate(
            lines = lines,
            employerContributions = lines.sumOf { it.employerAmount },
            warnings = warnings
        )
    }
}
