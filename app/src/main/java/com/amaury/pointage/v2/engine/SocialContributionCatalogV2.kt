package com.amaury.pointage.v2.engine

import kotlin.math.min

/**
 * Référentiel V2 daté des principales retenues salariales du secteur privé.
 * Les taux 2026 ci-dessous proviennent du barème Urssaf au 01/01/2026.
 * Chaque règle conserve son assiette : on ne remplace jamais la paie par un
 * pourcentage global brut -> net.
 */
object SocialContributionCatalogV2 {
    enum class Base { GROSS, GROSS_ABATED_9825, GROSS_CAPPED_MONTHLY_PASS }

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

    // PASS 2026 = 48 060 EUR, soit PMSS 4 005 EUR.
    private const val MONTHLY_PASS_2026 = 4005.0

    private val rules2026 = listOf(
        Rule("old_age_uncapped", "Assurance vieillesse déplafonnée", 0.0040, Base.GROSS, 2026, 2026, "Urssaf - taux secteur privé 01/01/2026"),
        Rule("old_age_capped", "Assurance vieillesse plafonnée", 0.0690, Base.GROSS_CAPPED_MONTHLY_PASS, 2026, 2026, "Urssaf - taux secteur privé 01/01/2026"),
        Rule("csg_deductible", "CSG déductible", 0.0680, Base.GROSS_ABATED_9825, 2026, 2026, "Urssaf - taux secteur privé 01/01/2026"),
        Rule("csg_taxable", "CSG imposable", 0.0240, Base.GROSS_ABATED_9825, 2026, 2026, "Urssaf - taux secteur privé 01/01/2026"),
        Rule("crds", "CRDS", 0.0050, Base.GROSS_ABATED_9825, 2026, 2026, "Urssaf - taux secteur privé 01/01/2026")
    )

    fun employeeRules(year: Int): List<Rule> = when (year) {
        2026 -> rules2026
        else -> emptyList()
    }

    fun estimateEmployeeDeductions(gross: Double, year: Int): Estimate {
        val safeGross = gross.coerceAtLeast(0.0)
        val rules = employeeRules(year)
        if (rules.isEmpty()) {
            return Estimate(safeGross, 0.0, safeGross, emptyList(), listOf("Cotisations salariales : barème non intégré pour $year"))
        }
        val pass = if (year == 2026) MONTHLY_PASS_2026 else Double.POSITIVE_INFINITY
        val lines = rules.map { rule ->
            val base = when (rule.base) {
                Base.GROSS -> safeGross
                Base.GROSS_ABATED_9825 -> safeGross * 0.9825
                Base.GROSS_CAPPED_MONTHLY_PASS -> min(safeGross, pass)
            }
            Line(rule.id, rule.label, base, rule.employeeRate, base * rule.employeeRate, rule.source)
        }
        val total = lines.sumOf { it.employeeAmount }
        return Estimate(
            gross = safeGross,
            employeeDeductions = total,
            netBeforeIncomeTax = (safeGross - total).coerceAtLeast(0.0),
            lines = lines,
            warnings = listOf("Retraite complémentaire, mutuelle/prévoyance et autres retenues propres au salarié/entreprise restent à confirmer tant que leurs paramètres ne sont pas intégrés.")
        )
    }
}
