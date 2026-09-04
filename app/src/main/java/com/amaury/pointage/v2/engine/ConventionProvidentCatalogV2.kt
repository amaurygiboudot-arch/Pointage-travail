package com.amaury.pointage.v2.engine

import kotlin.math.min

/** Couche 3/6 — prévoyance conventionnelle datée. Les inconnues restent non calculées. */
object ConventionProvidentCatalogV2 {
    data class Line(
        val id: String,
        val label: String,
        val baseAmount: Double,
        val employeeRate: Double,
        val employerRate: Double,
        val employeeAmount: Double,
        val employerAmount: Double,
        val source: String
    )

    data class Estimate(
        val lines: List<Line>,
        val employeeDeductions: Double,
        val employerContributions: Double,
        val warnings: List<String>
    )

    private const val SOURCE_PLASTURGIE = "Légifrance — IDCC 292, accord du 29/10/2014 modifié par avenant du 19/12/2024, étendu"

    /**
     * Régime conventionnel Plasturgie pour les salariés ne relevant pas des articles 2.1/2.2 de l'ANI cadres.
     * Bénéficiaires à partir de 3 mois d'ancienneté.
     * Cotisation minimale : 0,80 % du salaire de référence, dont 0,40 % salarié et 0,40 % employeur.
     */
    fun estimate(
        gross: Double,
        year: Int,
        idcc: String?,
        protectionCategory: PlasturgieProtectionCategoryV2.Result,
        seniorityMonths: Int?,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null
    ): Estimate {
        val g = gross.coerceAtLeast(0.0)
        val convention = idcc?.trim()

        if (convention != "292") return Estimate(emptyList(), 0.0, 0.0, emptyList())
        val full = SocialSecurityCeilingV2.fullMonthly(year)
            ?: return Estimate(emptyList(), 0.0, 0.0, listOf("Prévoyance Plasturgie : règle non validée dans HoraTrack pour $year."))
        if (!protectionCategory.confirmed || protectionCategory.category == PlasturgieProtectionCategoryV2.Category.TO_CONFIRM) {
            return Estimate(emptyList(), 0.0, 0.0, protectionCategory.warnings.ifEmpty {
                listOf("Prévoyance Plasturgie : catégorie ANI 2.1/2.2 à confirmer avant calcul.")
            })
        }
        if (protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1 ||
            protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_2) {
            return Estimate(emptyList(), 0.0, 0.0, emptyList())
        }
        if (seniorityMonths == null) {
            return Estimate(emptyList(), 0.0, 0.0, listOf("Prévoyance Plasturgie hors ANI 2.1/2.2 : ancienneté à confirmer avant calcul."))
        }
        if (seniorityMonths < 3 || g <= 0.0) return Estimate(emptyList(), 0.0, 0.0, protectionCategory.warnings)

        val max4 = ceiling?.fourTimesApplicable ?: full * 4.0
        val base = min(g, max4)
        val employeeRate = 0.004
        val employerRate = 0.004
        val line = Line(
            id = "plasturgie_292_non_cadre_provident",
            label = "Prévoyance Plasturgie hors ANI 2.1/2.2",
            baseAmount = base,
            employeeRate = employeeRate,
            employerRate = employerRate,
            employeeAmount = base * employeeRate,
            employerAmount = base * employerRate,
            source = SOURCE_PLASTURGIE
        )
        return Estimate(
            lines = listOf(line),
            employeeDeductions = line.employeeAmount,
            employerContributions = line.employerAmount,
            warnings = (ceiling?.warnings.orEmpty() + protectionCategory.warnings).distinct()
        )
    }
}
