package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/**
 * Résolution générique d'une catégorie objective ANI à partir de règles conventionnelles
 * prouvées pour une classification salariée exacte.
 */
object ConventionProtectionCategoryV2 {
    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        /** Classification exacte pour laquelle l'article officiel a été vérifié localement. */
        val classification: ConventionClassificationV2,
        val professionalStatus: String? = null,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            val normalizedStatus = professionalStatus?.trim()?.uppercase(Locale.ROOT)
            return ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
                ruleId.isNotBlank() &&
                !classification.isEmpty() &&
                normalizedStatus in setOf("CADRE", "NON_CADRE") &&
                categoryMatchesStatus(aniCategory, normalizedStatus!!) &&
                aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
                aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE &&
                source.isNotBlank() &&
                (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) &&
                (extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED || extensionEffectiveFrom != null)
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun statusMatches(value: String?): Boolean {
            val expected = professionalStatus?.trim()?.uppercase(Locale.ROOT) ?: return false
            val actual = value?.trim()?.uppercase(Locale.ROOT) ?: return false
            return expected == actual
        }

        fun extensionApplicableOn(date: LocalDate): Boolean =
            extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                extensionEffectiveFrom?.let { !date.isBefore(it) } == true
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
            if (coverageConfirmsNoRule(
                    coverage = coverage,
                    idcc = normalized,
                    referenceDate = referenceDate,
                    classification = classification,
                    professionalStatus = professionalStatus
                )
            ) {
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
        fun specificity(rule: Rule): Int = rule.classification.specificity() + 1 // statut exact obligatoire
        val maxSpecificity = latest.maxOf(::specificity)
        val best = latest.filter { specificity(it) == maxSpecificity }
        val categories = best.map { it.aniCategory }.distinct()
        if (categories.size != 1) {
            return unresolved("plusieurs catégories ANI contradictoires sont applicables à la même classification")
        }

        val applicable = best.filter { it.extensionApplicableOn(referenceDate) }
        if (applicable.isEmpty()) {
            return unresolved("applicabilité de la règle conventionnelle à l'entreprise non démontrée à cette date")
        }
        val selected = applicable.maxByOrNull { it.extensionEffectiveFrom ?: LocalDate.MIN }!!
        val warnings = buildList {
            if (selected.aniCategory == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                add("Extension au régime cadres possible : l'affiliation effective au régime de l'entreprise reste à confirmer avant d'appliquer les contributions propres aux articles 2.1/2.2.")
            }
        }
        return Resolution(
            category = ProtectionCategoryV2.Result(
                aniCategory = selected.aniCategory,
                confirmed = true,
                source = selected.source,
                warnings = warnings
            ),
            selectedRule = selected,
            reliable = true,
            warnings = warnings
        )
    }

    private fun coverageConfirmsNoRule(
        coverage: ConventionMatterCoverageV2.Snapshot?,
        idcc: String,
        referenceDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String?
    ): Boolean {
        if (coverage?.reliable != true || coverage.state != ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE) return false
        val record = coverage.record ?: return false
        return record.structurallyValid() &&
            record.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE &&
            record.matter == ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY &&
            ConventionMinimumSalaryV2.normalizeIdcc(record.idcc) == idcc &&
            record.activeOn(referenceDate) &&
            classification.matches(record.classification) &&
            record.statusMatches(professionalStatus)
    }

    private fun categoryMatchesStatus(category: ProtectionCategoryV2.AniCategory, status: String): Boolean = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> status == "CADRE"
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2 -> status == "NON_CADRE"
        ProtectionCategoryV2.AniCategory.TO_CONFIRM,
        ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE -> false
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
