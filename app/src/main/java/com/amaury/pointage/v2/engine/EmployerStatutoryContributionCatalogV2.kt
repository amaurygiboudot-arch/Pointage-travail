package com.amaury.pointage.v2.engine

import kotlin.math.min

/**
 * Sous-ensemble sûr des cotisations patronales légales 2026 du secteur privé.
 * Seules les lignes dont le taux et l'assiette sont déterminables sans choisir
 * un taux réduit/plein ni un seuil d'effectif sont calculées ici.
 */
object EmployerStatutoryContributionCatalogV2 {
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
        val knownEmployerContributions: Double,
        val complete: Boolean,
        val warnings: List<String>
    )

    private const val SOURCE = "Urssaf — taux de cotisations secteur privé au 01/01/2026"

    fun estimate(
        gross: Double,
        year: Int,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null
    ): Estimate {
        val g = gross.coerceAtLeast(0.0)
        if (year != 2026) {
            return Estimate(
                emptyList(),
                0.0,
                false,
                listOf("Cotisations patronales légales : barème sûr non intégré pour $year.")
            )
        }
        val full = SocialSecurityCeilingV2.fullMonthly(year)
        val applicable = ceiling?.applicableMonthly ?: full
        if (applicable == null) {
            return Estimate(
                emptyList(),
                0.0,
                false,
                listOf("Cotisations patronales légales : plafond social 2026 indisponible.")
            )
        }
        val cappedBase = min(g, applicable)
        val lines = buildList {
            if (g > 0.0) {
                add(Line("employer_old_age_uncapped", "Vieillesse patronale déplafonnée", g, 0.0211, g * 0.0211, SOURCE))
                add(Line("employer_csa", "Contribution solidarité autonomie", g, 0.0030, g * 0.0030, SOURCE))
                add(Line("employer_social_dialogue", "Contribution au dialogue social", g, 0.00016, g * 0.00016, SOURCE))
            }
            if (cappedBase > 0.0) {
                add(Line("employer_old_age_capped", "Vieillesse patronale plafonnée", cappedBase, 0.0855, cappedBase * 0.0855, SOURCE))
            }
        }
        return Estimate(
            lines = lines,
            knownEmployerContributions = lines.sumOf { it.employerAmount },
            complete = false,
            warnings = listOf(
                "Coût employeur incomplet : maladie et allocations familiales dépendent d'un taux réduit/plein à déterminer.",
                "Coût employeur incomplet : chômage, AGS, FNAL, formation, taxe d'apprentissage et éventuelles réductions/exonérations ne sont pas encore intégrés à ce sous-total."
            )
        )
    }
}
