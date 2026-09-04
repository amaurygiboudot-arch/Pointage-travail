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

    private const val PMSS_2026 = 4005.0
    private const val SOURCE = "Urssaf — prévoyance obligatoire des cadres, règle 2026"

    fun estimate(
        gross: Double,
        year: Int,
        professionalStatus: String?,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null
    ): Estimate {
        val g = gross.coerceAtLeast(0.0)
        if (year != 2026) {
            return Estimate(emptyList(), 0.0, listOf("Prévoyance cadre : barème non intégré pour $year"))
        }

        val status = professionalStatus?.trim()?.uppercase()
        val applicable = ceiling?.applicableMonthly ?: PMSS_2026
        val lines = buildList {
            if (status == "CADRE" && g > 0.0) {
                val base = min(g, applicable)
                add(
                    Line(
                        id = "cadre_provident_employer_minimum",
                        label = "Prévoyance cadre - minimum employeur",
                        baseAmount = base,
                        employerRate = 0.015,
                        employerAmount = base * 0.015,
                        source = SOURCE
                    )
                )
            }
        }

        val warnings = buildList {
            if (status != "CADRE" && status != "NON_CADRE") {
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
