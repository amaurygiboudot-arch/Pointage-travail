package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/**
 * Résolution générique d'une catégorie objective ANI à partir de preuves KALI + APEC
 * portant sur la même classification salariée et le même périmètre conventionnel exact.
 *
 * L'IDCC seul ne suffit pas : une même IDCC peut contenir des accords nationaux/régionaux
 * ayant des classifications distinctes. Un agrément APEC n'est donc applicable que si son
 * périmètre, sa catégorie et sa classification ont été reliés explicitement à la règle KALI.
 */
object ConventionProtectionCategoryV2 {
    enum class ApprovalStatus {
        APEC_APPROVED,
        APEC_REQUIRED_UNVERIFIED
    }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        /** Classification exacte pour laquelle l'article KALI a été vérifié localement. */
        val classification: ConventionClassificationV2,
        val professionalStatus: String? = null,
        val aniCategory: ProtectionCategoryV2.AniCategory,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null,
        /** Identité exacte du périmètre KALI (accord national/régional, avenant, etc.) si prouvée. */
        val conventionScopeKey: String? = null,
        val approvalStatus: ApprovalStatus = ApprovalStatus.APEC_REQUIRED_UNVERIFIED,
        val approvalEffectiveFrom: LocalDate? = null,
        val approvalSource: String? = null,
        /** Périmètre exact déclaré par l'agrément APEC. Doit être identique au périmètre KALI. */
        val approvalScopeKey: String? = null,
        /** Classification exacte indépendamment prouvée par l'agrément APEC. */
        val approvalClassification: ConventionClassificationV2? = null,
        /** Catégorie indépendamment prouvée par l'agrément APEC. */
        val approvalAniCategory: ProtectionCategoryV2.AniCategory? = null
    ) {
        fun structurallyValid(): Boolean {
            val normalizedStatus = professionalStatus?.trim()?.uppercase(Locale.ROOT) ?: return false
            if (normalizedStatus != "CADRE" && normalizedStatus != "NON_CADRE") return false

            val approvalProofValid = when (approvalStatus) {
                ApprovalStatus.APEC_APPROVED -> {
                    val kaliScope = conventionScopeKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                    val apecScope = approvalScopeKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                    val apecClassification = approvalClassification?.takeIf { !it.isEmpty() } ?: return false
                    approvalEffectiveFrom != null &&
                        !approvalSource.isNullOrBlank() &&
                        kaliScope == apecScope &&
                        classification.matches(apecClassification) &&
                        apecClassification.matches(classification) &&
                        approvalAniCategory == aniCategory
                }
                ApprovalStatus.APEC_REQUIRED_UNVERIFIED ->
                    approvalEffectiveFrom == null &&
                        approvalSource.isNullOrBlank() &&
                        approvalScopeKey.isNullOrBlank() &&
                        approvalClassification == null &&
                        approvalAniCategory == null
            }

            return ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
                ruleId.isNotBlank() &&
                !classification.isEmpty() &&
                categoryMatchesStatus(aniCategory, normalizedStatus) &&
                aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
                aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE &&
                source.isNotBlank() &&
                (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) &&
                (extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED || extensionEffectiveFrom != null) &&
                approvalProofValid
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

        fun approvalApplicableOn(date: LocalDate): Boolean =
            approvalStatus == ApprovalStatus.APEC_APPROVED &&
                structurallyValid() &&
                approvalEffectiveFrom?.let { !date.isBefore(it) } == true
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

        val extended = best.filter { it.extensionApplicableOn(referenceDate) }
        if (extended.isEmpty()) {
            return unresolved("extension officielle de la règle conventionnelle non démontrée à cette date")
        }
        val approved = extended.filter { it.approvalApplicableOn(referenceDate) }
        if (approved.isEmpty()) {
            return unresolved("agrément APEC exact (périmètre + classification + catégorie) non démontré à cette date")
        }

        val selected = approved.maxWithOrNull(
            compareBy<Rule> { it.effectiveFrom }
                .thenBy { it.extensionEffectiveFrom ?: LocalDate.MIN }
                .thenBy { it.approvalEffectiveFrom ?: LocalDate.MIN }
        )!!
        val warnings = buildList {
            if (selected.aniCategory == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                add("Extension au régime cadres autorisée par la branche/APEC : l'affiliation effective au régime de l'entreprise reste à confirmer avant d'appliquer les contributions propres aux articles 2.1/2.2.")
            }
        }
        return Resolution(
            category = ProtectionCategoryV2.Result(
                aniCategory = selected.aniCategory,
                confirmed = true,
                source = listOfNotNull(selected.source, selected.approvalSource).joinToString(" + "),
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
        val requiredAuthorities = setOf(
            ConventionMatterCoverageV2.Authority.KALI,
            ConventionMatterCoverageV2.Authority.APEC
        )
        return record.structurallyValid() &&
            record.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE &&
            record.matter == ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY &&
            record.authorities.containsAll(requiredAuthorities) &&
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
