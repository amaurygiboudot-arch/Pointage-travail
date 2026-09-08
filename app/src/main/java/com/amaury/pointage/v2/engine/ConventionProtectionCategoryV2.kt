package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Résolution générique d'une catégorie objective ANI à partir de règles conventionnelles
 * prouvées pour la classification salariée exacte.
 *
 * Une règle de branche et son extension ne suffisent pas à elles seules : lorsque le
 * classement résulte d'une classification conventionnelle, la preuve de validation par la
 * commission APEC (ou d'un agrément AGIRC antérieur encore pertinent) est conservée séparément
 * et doit couvrir exactement le même profil conventionnel.
 */
object ConventionProtectionCategoryV2 {
    enum class ApprovalAuthority {
        APEC,
        AGIRC
    }

    data class ApprovalEvidence(
        val authority: ApprovalAuthority,
        val approvedOn: LocalDate,
        val idcc: String,
        val classification: ConventionClassificationV2,
        val professionalStatus: String?,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val source: String
    ) {
        fun structurallyValid(): Boolean = ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
            !classification.isEmpty() &&
            aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
            aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE &&
            source.isNotBlank()

        fun applicableOn(date: LocalDate): Boolean = structurallyValid() && !date.isBefore(approvedOn)

        fun covers(
            ruleIdcc: String,
            ruleClassification: ConventionClassificationV2,
            ruleStatus: String?,
            ruleCategory: ProtectionCategoryV2.AniCategory
        ): Boolean {
            val expectedStatus = professionalStatus?.trim()?.uppercase()
            val actualStatus = ruleStatus?.trim()?.uppercase()
            return structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(idcc) == ConventionMinimumSalaryV2.normalizeIdcc(ruleIdcc) &&
                classification.matches(ruleClassification) &&
                ruleClassification.matches(classification) &&
                expectedStatus == actualStatus &&
                aniCategory == ruleCategory
        }
    }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        /** Classification exacte du salarié pour laquelle la source KALI a été vérifiée. */
        val classification: ConventionClassificationV2,
        val professionalStatus: String? = null,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null,
        /**
         * Les règles KALI de classement ANI reposant sur une classification conventionnelle
         * exigent une preuve d'agrément. La preuve peut être enrichie après extraction APEC/AGIRC.
         */
        val approvalRequired: Boolean = true,
        val approvalEvidence: ApprovalEvidence? = null
    ) {
        fun structurallyValid(): Boolean = ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
            ruleId.isNotBlank() &&
            !classification.isEmpty() &&
            aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE &&
            aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
            source.isNotBlank() &&
            (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) &&
            (extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED || extensionEffectiveFrom != null) &&
            (approvalEvidence == null || approvalEvidence.structurallyValid())

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun statusMatches(value: String?): Boolean {
            val expected = professionalStatus?.trim()?.uppercase()
            return expected == value?.trim()?.uppercase()
        }

        fun extensionApplicableOn(date: LocalDate): Boolean =
            extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                extensionEffectiveFrom?.let { !date.isBefore(it) } == true

        fun approvalScopeMatches(): Boolean = approvalEvidence?.covers(
            ruleIdcc = idcc,
            ruleClassification = classification,
            ruleStatus = professionalStatus,
            ruleCategory = aniCategory
        ) == true

        fun approvalApplicableOn(date: LocalDate): Boolean = when {
            !approvalRequired -> true
            !approvalScopeMatches() -> false
            else -> approvalEvidence?.applicableOn(date) == true
        }
    }

    data class Resolution(
        val category: ProtectionCategoryV2.Result,
        val selectedRule: Rule?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        idcc: String,
        referenceDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        rules: List<Rule>,
        coverage: ConventionMatterCoverageV2.Snapshot? = null
    ): Resolution {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val matching = rules.filter { rule ->
            rule.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == normalized &&
                rule.activeOn(referenceDate) &&
                classification.matches(rule.classification) &&
                rule.classification.matches(classification) &&
                rule.statusMatches(professionalStatus)
        }

        if (matching.isEmpty()) {
            if (coverage?.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE && coverage.reliable) {
                return Resolution(
                    category = ProtectionCategoryV2.noConventionOverride(),
                    selectedRule = null,
                    reliable = true,
                    warnings = emptyList()
                )
            }
            return unresolved("catégorie conventionnelle ANI non confirmée pour cette classification")
        }

        val latestDate = matching.maxOf { it.effectiveFrom }
        val latest = matching.filter { it.effectiveFrom == latestDate }
        fun specificity(rule: Rule): Int = rule.classification.specificity() + (if (rule.professionalStatus == null) 0 else 1)
        val maxSpecificity = latest.maxOf(::specificity)
        val best = latest.filter { specificity(it) == maxSpecificity }
        val categories = best.map { it.aniCategory }.distinct()
        if (categories.size != 1) {
            return unresolved("plusieurs catégories ANI contradictoires sont applicables à la même classification")
        }

        val extensionApplicable = best.filter { it.extensionApplicableOn(referenceDate) }
        if (extensionApplicable.isEmpty()) {
            return unresolved("applicabilité de la règle conventionnelle à l'entreprise non démontrée à cette date")
        }

        val approved = extensionApplicable.filter { it.approvalApplicableOn(referenceDate) }
        if (approved.isEmpty()) {
            val withEvidence = extensionApplicable.filter { it.approvalEvidence != null }
            val reason = when {
                withEvidence.isEmpty() -> "agrément APEC/AGIRC requis mais non prouvé pour cette classification"
                withEvidence.none { it.approvalScopeMatches() } -> "agrément APEC/AGIRC trouvé mais il ne couvre pas exactement cette catégorie et cette classification"
                else -> {
                    val authorities = withEvidence.mapNotNull { it.approvalEvidence?.authority }.distinct()
                    "agrément ${authorities.joinToString("/") { it.name }} non applicable à la période analysée"
                }
            }
            return unresolved(reason)
        }

        val selected = approved.maxWithOrNull(
            compareBy<Rule> { it.extensionEffectiveFrom ?: LocalDate.MIN }
                .thenBy { it.approvalEvidence?.approvedOn ?: LocalDate.MIN }
        )!!
        val warnings = buildList {
            if (selected.aniCategory == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                add("Catégorie pouvant être intégrée au régime cadres : l'agrément autorise le périmètre, mais l'affiliation effective dépend encore du choix formalisé par l'entreprise.")
            }
        }
        return Resolution(
            category = ProtectionCategoryV2.Result(
                aniCategory = selected.aniCategory,
                confirmed = true,
                source = listOfNotNull(selected.source, selected.approvalEvidence?.source).joinToString(" + "),
                warnings = warnings
            ),
            selectedRule = selected,
            reliable = true,
            warnings = warnings
        )
    }

    private fun unresolved(reason: String): Resolution {
        val warning = "Catégorie de protection sociale complémentaire : $reason ; aucun classement ANI n'est inventé."
        return Resolution(
            category = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed = false,
                warnings = listOf(warning)
            ),
            selectedRule = null,
            reliable = false,
            warnings = listOf(warning)
        )
    }
}
