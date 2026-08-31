package com.amaury.pointage.v2.engine

import kotlin.math.min

/**
 * Couche 1/6 - référentiel daté des retenues salariales légales de base.
 * Source 2026 : Urssaf, règles applicables au secteur privé.
 *
 * Important : chaque ligne conserve son taux ET son assiette. HoraTrack ne
 * remplace jamais la paie par un pourcentage global brut -> net.
 */
object SocialContributionCatalogV2 {
    enum class Base { GROSS, CSG_CRDS_2026, GROSS_CAPPED_MONTHLY_PASS }

    data class Rule(
        val id: String,
        val label: String,
        val employeeRate: Double,
        val base: Base,
        val validFromYear: Int,
        val validToYear: Int? = null,
        val source: String
    )

    data class Line(
        val id: String,
        val label: String,
        val baseAmount: Double,
        val rate: Double,
        val employeeAmount: Double,
        val source: String
    )

    data class Estimate(
        val gross: Double,
        val employeeDeductions: Double,
        val netBeforeIncomeTax: Double,
        val lines: List<Line>,
        val warnings: List<String>
    )

    // PASS 2026 = 48 060 EUR ; PMSS = 4 005 EUR.
    private const val MONTHLY_PASS_2026 = 4005.0
    // L'abattement de 1,75 % CSG/CRDS est limité à 4 PASS.
    // Au-delà, la fraction excédentaire reste dans l'assiette à 100 %.
    private const val CSG_CRDS_MONTHLY_ABATEMENT_CAP_2026 = MONTHLY_PASS_2026 * 4.0

    private val rules2026 = listOf(
        Rule("old_age_uncapped", "Assurance vieillesse déplafonnée", 0.0040, Base.GROSS, 2026, 2026, "Urssaf - taux secteur privé 2026"),
        Rule("old_age_capped", "Assurance vieillesse plafonnée", 0.0690, Base.GROSS_CAPPED_MONTHLY_PASS, 2026, 2026, "Urssaf - taux secteur privé 2026"),
        Rule("csg_deductible", "CSG déductible", 0.0680, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026"),
        Rule("csg_taxable", "CSG imposable", 0.0240, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026"),
        Rule("crds", "CRDS", 0.0050, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026")
    )

    fun employeeRules(year: Int): List<Rule> = when (year) {
        2026 -> rules2026
        else -> emptyList()
    }

    /**
     * Assiette CSG/CRDS de base sur le salaire brut seul :
     * - 98,25 % jusqu'à 4 PMSS ;
     * - 100 % de la fraction au-delà.
     *
     * Les contributions patronales de prévoyance/santé qui doivent être
     * réintégrées dans l'assiette ne sont pas inventées ici : elles seront
     * ajoutées quand la couche entreprise dispose de ces montants.
     */
    private fun csgCrdsBase2026(gross: Double): Double {
        val abatedPart = min(gross, CSG_CRDS_MONTHLY_ABATEMENT_CAP_2026)
        val excess = (gross - CSG_CRDS_MONTHLY_ABATEMENT_CAP_2026).coerceAtLeast(0.0)
        return abatedPart * 0.9825 + excess
    }

    fun estimateEmployeeDeductions(gross: Double, year: Int): Estimate {
        val safeGross = gross.coerceAtLeast(0.0)
        val rules = employeeRules(year)
        if (rules.isEmpty()) {
            return Estimate(
                safeGross,
                0.0,
                safeGross,
                emptyList(),
                listOf("Cotisations salariales : barème non intégré pour $year")
            )
        }

        val monthlyPass = if (year == 2026) MONTHLY_PASS_2026 else Double.POSITIVE_INFINITY
        val lines = rules.map { rule ->
            val base = when (rule.base) {
                Base.GROSS -> safeGross
                Base.CSG_CRDS_2026 -> csgCrdsBase2026(safeGross)
                Base.GROSS_CAPPED_MONTHLY_PASS -> min(safeGross, monthlyPass)
            }
            Line(rule.id, rule.label, base, rule.employeeRate, base * rule.employeeRate, rule.source)
        }
        val total = lines.sumOf { it.employeeAmount }
        return Estimate(
            gross = safeGross,
            employeeDeductions = total,
            netBeforeIncomeTax = (safeGross - total).coerceAtLeast(0.0),
            lines = lines,
            warnings = listOf(
                "Couche 1/6 : ce net est volontairement partiel.",
                "L'assiette CSG/CRDS est calculée sur le brut connu ; les éventuelles contributions patronales à réintégrer restent à fournir par la couche entreprise.",
                "Retraite complémentaire, CEG/CET, mutuelle/prévoyance, convention et retenues propres à l'entreprise sont traitées dans les couches suivantes."
            )
        )
    }
}
