package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoMealBasketParserV2
import java.time.LocalDate

/**
 * Arbitrage L2253-3 des paniers repas : accord d'entreprise avant branche pour le même objet.
 *
 * Les familles jour/nuit/poste/hors domicile sont arbitrées séparément. Si une règle KALI existe
 * sans règle ACCO du même objet, le repli vers KALI exige une preuve explicite d'absence ACCO ;
 * un store ACCO vide ou une recherche vide ne suffisent jamais.
 */
object MealBasketLegalArbitrationBridgeV2 {
    data class Selected(
        val subject: String,
        val source: PayrollLegalArbitratorV2.Source,
        val branchRule: ConventionMealBasketV2.Rule? = null,
        val companyRule: OfficialAccoMealBasketParserV2.Rule? = null
    )

    data class Result(
        val selected: List<Selected>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        branchRules: List<ConventionMealBasketV2.Rule>,
        companyRules: List<OfficialAccoMealBasketParserV2.Rule>,
        territoryCode: String? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Result {
        val siret = profile.siret.filter(Char::isDigit)
        if (siret.length != 14 || profile.classification.isEmpty() || profile.professionalStatus == null) {
            return blocked("profil juridique exact ou SIRET manquant")
        }

        val branchScope = ConventionMealBasketV2.scopeRules(
            rules = branchRules,
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            territoryCode = territoryCode
        )
        if (!branchScope.reliable && branchRules.isNotEmpty()) {
            return blocked(branchScope.warnings.joinToString(" "))
        }
        val scopedCompany = companyRules.filter { rule ->
            rule.structurallyValid() &&
                rule.siret == siret &&
                !referenceDate.isBefore(rule.effectiveFrom) &&
                (rule.effectiveTo == null || !referenceDate.isAfter(rule.effectiveTo)) &&
                profile.classification.matches(rule.classification) &&
                rule.classification.matches(profile.classification) &&
                rule.professionalStatus == profile.professionalStatus
        }

        val branchBySubject = branchScope.rules.groupBy { subject(it.benefitId) }
        val companyBySubject = scopedCompany.groupBy { subject(it.benefitId) }
        val subjects = (branchBySubject.keys + companyBySubject.keys).toSortedSet()
        if (subjects.isEmpty()) {
            return Result(
                selected = emptyList(),
                reliable = false,
                warnings = listOf("Panier repas ACCO/KALI : aucune règle applicable structurée ; aucune absence de droit n'est déduite.")
            )
        }

        val selected = mutableListOf<Selected>()
        val warnings = mutableListOf<String>()
        subjects.forEach { subject ->
            val branch = branchBySubject[subject].orEmpty()
            val company = companyBySubject[subject].orEmpty()
            val candidates = buildList {
                branch.forEach { rule ->
                    add(
                        PayrollLegalArbitratorV2.Candidate(
                            id = "KALI:${rule.ruleId}",
                            source = PayrollLegalArbitratorV2.Source.KALI,
                            effectiveFrom = rule.effectiveFrom,
                            effectiveTo = rule.effectiveTo,
                            verified = true,
                            scopeConfirmed = true,
                            valueFingerprint = branchFingerprint(rule)
                        )
                    )
                }
                company.forEach { rule ->
                    add(
                        PayrollLegalArbitratorV2.Candidate(
                            id = "ACCO:${rule.agreementId}:${rule.benefitId}",
                            source = PayrollLegalArbitratorV2.Source.ACCO,
                            effectiveFrom = rule.effectiveFrom,
                            effectiveTo = rule.effectiveTo,
                            verified = true,
                            scopeConfirmed = true,
                            valueFingerprint = rule.fingerprint
                        )
                    )
                }
            }
            val arbitration = PayrollLegalArbitratorV2.resolve(
                candidates = candidates,
                referenceDate = referenceDate,
                policy = PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3,
                sourceKnowledge = sourceKnowledge
            )
            warnings += "Panier $subject : ${arbitration.explanation}"
            if (arbitration.state != PayrollLegalArbitratorV2.State.RESOLVED || arbitration.selected == null) {
                return Result(emptyList(), false, warnings.distinct())
            }
            when (arbitration.selected.source) {
                PayrollLegalArbitratorV2.Source.ACCO -> {
                    val id = arbitration.selected.id.removePrefix("ACCO:")
                    val chosen = company.singleOrNull { "${it.agreementId}:${it.benefitId}" == id }
                        ?: return blocked("règle ACCO arbitrée introuvable ou ambiguë pour $subject", warnings)
                    selected += Selected(subject, PayrollLegalArbitratorV2.Source.ACCO, companyRule = chosen)
                }
                PayrollLegalArbitratorV2.Source.KALI -> {
                    val id = arbitration.selected.id.removePrefix("KALI:")
                    val chosen = branch.singleOrNull { it.ruleId == id }
                        ?: return blocked("règle KALI arbitrée introuvable ou ambiguë pour $subject", warnings)
                    selected += Selected(subject, PayrollLegalArbitratorV2.Source.KALI, branchRule = chosen)
                }
                else -> return blocked("source arbitrée non autorisée pour $subject", warnings)
            }
        }

        return Result(
            selected = selected,
            reliable = true,
            warnings = warnings.distinct()
        )
    }

    internal fun subject(benefitId: String): String = benefitId.trim().uppercase()
        .replace(Regex("_[0-9]+$"), "")
        .takeIf { it.startsWith("MEAL_") }
        ?: "MEAL_OTHER"

    private fun branchFingerprint(rule: ConventionMealBasketV2.Rule): String = listOf(
        subject(rule.benefitId),
        rule.deliveryMode.name,
        amountFingerprint(rule.amountFormula),
        rule.eligibilityAnyOf.joinToString("||") { group -> group.allOf.joinToString("&") { it.toString() } },
        rule.blockers.sortedBy { it.name }.joinToString(",") { it.name },
        rule.countingUnit.name,
        rule.maxAwardsPerCalendarDay.toString()
    ).joinToString("|")

    private fun amountFingerprint(value: ConventionMealBasketV2.AmountFormula): String = when (value) {
        is ConventionMealBasketV2.AmountFormula.FixedEuro -> "FIXED:${value.amount}"
        is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> "MG:${value.multiplier}"
        ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> "EXTERNAL"
    }

    private fun blocked(reason: String, extra: List<String> = emptyList()) = Result(
        selected = emptyList(),
        reliable = false,
        warnings = (extra + "Panier repas ACCO/KALI : $reason ; calcul automatique bloqué.").distinct()
    )
}
