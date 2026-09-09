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
        val source: String,
        val employerRate: Double = 0.0
    )

    data class Line(
        val id: String,
        val label: String,
        val baseAmount: Double,
        val rate: Double,
        val employeeAmount: Double,
        val source: String,
        val employerRate: Double = 0.0,
        val employerAmount: Double = 0.0
    )

    data class Estimate(
        val gross: Double,
        val employeeDeductions: Double,
        val netBeforeIncomeTax: Double,
        val lines: List<Line>,
        val warnings: List<String>,
        val employerContributions: Double = 0.0
    )

    private val rules2026 = listOf(
        Rule("old_age_uncapped", "Assurance vieillesse déplafonnée", 0.0040, Base.GROSS, 2026, 2026, "Urssaf - taux secteur privé 2026", employerRate = 0.0211),
        Rule("old_age_capped", "Assurance vieillesse plafonnée", 0.0690, Base.GROSS_CAPPED_MONTHLY_PASS, 2026, 2026, "Urssaf - taux secteur privé 2026", employerRate = 0.0855),
        Rule("csa_employer", "Contribution solidarité autonomie", 0.0, Base.GROSS, 2026, 2026, "Urssaf - taux secteur privé 2026", employerRate = 0.0030),
        Rule("social_dialogue_employer", "Contribution au dialogue social", 0.0, Base.GROSS, 2026, 2026, "Urssaf - taux secteur privé 2026", employerRate = 0.00016),
        Rule("csg_deductible", "CSG déductible", 0.0680, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026"),
        Rule("csg_taxable", "CSG imposable", 0.0240, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026"),
        Rule("crds", "CRDS", 0.0050, Base.CSG_CRDS_2026, 2026, 2026, "Urssaf - CSG/CRDS revenus d'activité 2026")
    )

    private val alsaceMoselle2026 = Rule(
        "alsace_moselle_local_health",
        "Régime local Alsace-Moselle — cotisation maladie supplémentaire",
        0.0130,
        Base.GROSS,
        2026,
        2026,
        "Urssaf / Régime Local Alsace-Moselle - taux 2026"
    )

    fun employeeRules(year: Int): List<Rule> = when (year) {
        2026 -> rules2026
        else -> emptyList()
    }

    /**
     * Assiette CSG/CRDS 2026 : le salaire bénéficie de l'abattement de 1,75 %
     * jusqu'à quatre fois le plafond social applicable, tandis que la part employeur
     * de protection sociale complémentaire soumise à CSG/CRDS est ajoutée ensuite,
     * sans appliquer cet abattement à cette part patronale.
     */
    private fun csgCrdsBase2026(
        gross: Double,
        applicableMonthlyPass: Double,
        employerProtectionCsgCrdsBaseAmount: Double
    ): Double {
        val cap = (applicableMonthlyPass * 4.0).coerceAtLeast(0.0)
        val abatedPart = min(gross, cap)
        val excess = (gross - cap).coerceAtLeast(0.0)
        return abatedPart * 0.9825 + excess + employerProtectionCsgCrdsBaseAmount
    }

    fun estimateEmployeeDeductions(
        gross: Double,
        year: Int,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null,
        alsaceMoselleLocalRegime: Boolean? = null,
        employerProtectionCsgCrdsBaseAmount: Double? = null
    ): Estimate {
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

        val validEmployerProtectionCsgCrdsBase = employerProtectionCsgCrdsBaseAmount
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val monthlyPass = ceiling?.applicableMonthly
            ?: SocialSecurityCeilingV2.fullMonthly(year)
            ?: Double.POSITIVE_INFINITY
        val baseLines = rules.map { rule ->
            val base = when (rule.base) {
                Base.GROSS -> safeGross
                Base.CSG_CRDS_2026 -> csgCrdsBase2026(
                    safeGross,
                    monthlyPass,
                    validEmployerProtectionCsgCrdsBase ?: 0.0
                )
                Base.GROSS_CAPPED_MONTHLY_PASS -> min(safeGross, monthlyPass)
            }
            Line(
                id = rule.id,
                label = rule.label,
                baseAmount = base,
                rate = rule.employeeRate,
                employeeAmount = base * rule.employeeRate,
                source = rule.source,
                employerRate = rule.employerRate,
                employerAmount = base * rule.employerRate
            )
        }
        val localLines = if (year == 2026 && alsaceMoselleLocalRegime == true && safeGross > 0.0) {
            listOf(
                Line(
                    alsaceMoselle2026.id,
                    alsaceMoselle2026.label,
                    safeGross,
                    alsaceMoselle2026.employeeRate,
                    safeGross * alsaceMoselle2026.employeeRate,
                    alsaceMoselle2026.source
                )
            )
        } else emptyList()
        val lines = baseLines + localLines
        val total = lines.sumOf { it.employeeAmount }
        return Estimate(
            gross = safeGross,
            employeeDeductions = total,
            netBeforeIncomeTax = (safeGross - total).coerceAtLeast(0.0),
            lines = lines,
            warnings = buildList {
                add("Couche 1/6 : ce net est volontairement partiel.")
                add("Les parts patronales vieillesse, CSA et dialogue social 2026 sont intégrées séparément ; les autres cotisations patronales légales de base restent à compléter.")
                when {
                    employerProtectionCsgCrdsBaseAmount == null -> add("Assiette CSG/CRDS : part employeur de protection sociale complémentaire à confirmer, même si elle est nulle ; le sous-total courant reste calculé sur le brut connu uniquement.")
                    validEmployerProtectionCsgCrdsBase == null -> add("Assiette CSG/CRDS : part employeur de protection sociale complémentaire invalide ; aucune valeur n'est inventée.")
                    else -> add("Assiette CSG/CRDS : part employeur de protection sociale complémentaire confirmée ajoutée après l'abattement applicable au salaire.")
                }
                add("Retraite complémentaire, CEG/CET, mutuelle/prévoyance, convention et retenues propres à l'entreprise sont traitées dans les couches suivantes.")
                if (year == 2026 && alsaceMoselleLocalRegime == null) {
                    add("Régime local Alsace-Moselle : affiliation à confirmer ; aucune cotisation locale n'est inventée.")
                }
                if (year == 2026 && alsaceMoselleLocalRegime == true) {
                    add("Régime local Alsace-Moselle confirmé : cotisation salariale maladie supplémentaire de 1,30 % appliquée au brut déplafonné.")
                }
                ceiling?.warnings?.let(::addAll)
            }.distinct(),
            employerContributions = lines.sumOf { it.employerAmount }
        )
    }
}
