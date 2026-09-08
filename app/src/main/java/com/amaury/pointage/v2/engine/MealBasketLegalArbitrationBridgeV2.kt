package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoMealBasketParserV2
import java.time.LocalDate

/**
 * Arbitrage L2253-3 des paniers repas : accord d'entreprise avant branche pour le même objet.
 *
 * Les familles jour/nuit/poste/hors domicile sont arbitrées séparément. Si une règle KALI existe
 * sans règle ACCO du même objet, le repli vers KALI exige une preuve explicite d'absence ACCO
 * POUR CET OBJET. Une absence globale ou un store ACCO vide ne suffisent jamais.
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
        sourceKnowledgeBySubject: Map<
            String,
            Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge>
        > = emptyMap()
    ): Result {
        val siret = profile.siret.filter(Char::isDigit)
        if (siret.length != 14 || profile.classification.isEmpty() || profile.professionalStatus == null) {
            return blocked("profil juridique exact ou SIRET manquant")
        }
        val normalizedStatus = profile.professionalStatus.trim().uppercase()
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)

        val scopedCompany = companyRules.filter { rule ->
            rule.structurallyValid() &&
                rule.siret == siret &&
                !referenceDate.isBefore(rule.effectiveFrom) &&
                (rule.effectiveTo == null || !referenceDate.isAfter(rule.effectiveTo)) &&
                profile.classification.matches(rule.classification) &&
                rule.classification.matches(profile.classification) &&
                rule.professionalStatus == normalizedStatus
        }
        val companySubjects = scopedCompany.mapTo(linkedSetOf()) { subject(it.benefitId) }

        // Écarte d'abord les règles KALI manifestement étrangères au salarié/date. Ainsi un article
        // historique ou un coefficient voisin dans le cache partagé ne peut pas invalider un ACCO
        // exact. En revanche, une règle KALI potentiellement pertinente sur un objet non couvert par
        // ACCO reste bloquante si sa portée finale (extension/territoire/exclusion) est indémontrable.
        val potentiallyRelevantBranch = branchRules.filter { rule ->
            rule.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == normalizedIdcc &&
                rule.activeOn(referenceDate) &&
                profile.classification.matches(rule.classification) &&
                rule.classification.matches(profile.classification) &&
                (rule.professionalStatus == null || rule.professionalStatus.trim().uppercase() == normalizedStatus)
        }
        val branchScope = ConventionMealBasketV2.scopeRules(
            rules = potentiallyRelevantBranch,
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            territoryCode = territoryCode
        )
        if (!branchScope.reliable && potentiallyRelevantBranch.isNotEmpty()) {
            val unresolvedSubjects = potentiallyRelevantBranch
                .mapTo(linkedSetOf()) { subject(it.benefitId) } - companySubjects
            if (unresolvedSubjects.isNotEmpty()) {
                return blocked(
                    "portée KALI non résolue pour ${unresolvedSubjects.sorted().joinToString()} : ${branchScope.warnings.joinToString(" ")}"
                )
            }
        }
        val usableBranchRules = if (branchScope.reliable) branchScope.rules else emptyList()

        val branchBySubject = usableBranchRules.groupBy { subject(it.benefitId) }
        val companyBySubject = scopedCompany.groupBy { subject(it.benefitId) }
        val subjects = (branchBySubject.keys + companyBySubject.keys).toSortedSet()
        if (subjects.isEmpty()) {
            return Result(
                selected = emptyList(),
                reliable = false,
                warnings = listOf("Panier repas ACCO/KALI : aucune règle applicable structurée ; aucune absence de droit n'est déduite.")
            )
        }

        val normalizedKnowledge = sourceKnowledgeBySubject.mapKeys { subject(it.key) }
        val selected = mutableListOf<Selected>()
        val warnings = mutableListOf<String>()
        if (!branchScope.reliable && scopedCompany.isNotEmpty()) {
            warnings += "Panier repas : règles KALI étrangères ou de portée non nécessaire ignorées pour les objets couverts par un ACCO exact."
        }
        subjects.forEach { currentSubject ->
            val branch = branchBySubject[currentSubject].orEmpty()
            val company = companyBySubject[currentSubject].orEmpty()
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
                            valueFingerprint = companyFingerprint(rule)
                        )
                    )
                }
            }
            val arbitration = PayrollLegalArbitratorV2.resolve(
                candidates = candidates,
                referenceDate = referenceDate,
                policy = PayrollLegalArbitratorV2.Policy.ENTERPRISE_PREVAILS_L2253_3,
                sourceKnowledge = normalizedKnowledge[currentSubject].orEmpty()
            )
            warnings += "Panier $currentSubject : ${arbitration.explanation}"
            if (arbitration.state != PayrollLegalArbitratorV2.State.RESOLVED || arbitration.selected == null) {
                return Result(emptyList(), false, warnings.distinct())
            }
            when (arbitration.selected.source) {
                PayrollLegalArbitratorV2.Source.ACCO -> {
                    val id = arbitration.selected.id.removePrefix("ACCO:")
                    val chosen = company.singleOrNull { "${it.agreementId}:${it.benefitId}" == id }
                        ?: return blocked("règle ACCO arbitrée introuvable ou ambiguë pour $currentSubject", warnings)
                    selected += Selected(currentSubject, PayrollLegalArbitratorV2.Source.ACCO, companyRule = chosen)
                }
                PayrollLegalArbitratorV2.Source.KALI -> {
                    val id = arbitration.selected.id.removePrefix("KALI:")
                    val chosen = branch.singleOrNull { it.ruleId == id }
                        ?: return blocked("règle KALI arbitrée introuvable ou ambiguë pour $currentSubject", warnings)
                    selected += Selected(currentSubject, PayrollLegalArbitratorV2.Source.KALI, branchRule = chosen)
                }
                else -> return blocked("source arbitrée non autorisée pour $currentSubject", warnings)
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
        eligibilityFingerprint(rule.eligibilityAnyOf),
        blockersFingerprint(rule.blockers),
        rule.countingUnit.name,
        rule.maxAwardsPerCalendarDay.toString()
    ).joinToString("|")

    private fun companyFingerprint(rule: OfficialAccoMealBasketParserV2.Rule): String = listOf(
        subject(rule.benefitId),
        rule.deliveryMode.name,
        amountFingerprint(rule.amountFormula),
        eligibilityFingerprint(rule.eligibilityAnyOf),
        blockersFingerprint(rule.blockers),
        rule.countingUnit.name,
        rule.maxAwardsPerCalendarDay.toString()
    ).joinToString("|")

    private fun eligibilityFingerprint(groups: List<ConventionMealBasketV2.EligibilityGroup>): String =
        groups.joinToString("||") { group -> group.allOf.joinToString("&") { it.toString() } }

    private fun blockersFingerprint(blockers: Set<ConventionMealBasketV2.Blocker>): String =
        blockers.sortedBy { it.name }.joinToString(",") { it.name }

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