package com.amaury.pointage.v2.engine

import java.time.LocalDate
import kotlin.math.min

/**
 * Cotisations conventionnelles de prévoyance, génériques, datées et classifiées.
 *
 * Une règle n'est calculable que si son périmètre salarié, son applicabilité juridique,
 * ses tranches d'assiette et la répartition salarié/employeur sont explicitement connus.
 * L'absence de règle correspondante n'est jamais transformée ici en preuve d'absence de droit.
 */
object ConventionProvidentContributionV2 {
    data class Band(
        val label: String,
        /** Borne basse exprimée en multiples du plafond mensuel SS. 0 = dès le premier euro. */
        val lowerCeilingMultiple: Double = 0.0,
        /** Null = pas de borne haute ; sinon multiple du plafond mensuel SS. */
        val upperCeilingMultiple: Double? = null,
        /** Taux décimal exact, ex. 0,40 % = 0.004. */
        val employeeRate: Double,
        /** Taux décimal exact, ex. 0,40 % = 0.004. */
        val employerRate: Double
    ) {
        fun structurallyValid(): Boolean =
            label.isNotBlank() &&
                lowerCeilingMultiple.isFinite() && lowerCeilingMultiple >= 0.0 &&
                (upperCeilingMultiple == null || (
                    upperCeilingMultiple.isFinite() && upperCeilingMultiple > lowerCeilingMultiple
                    )) &&
                employeeRate.isFinite() && employeeRate in 0.0..1.0 &&
                employerRate.isFinite() && employerRate in 0.0..1.0 &&
                (employeeRate > 0.0 || employerRate > 0.0)

        val needsCeiling: Boolean
            get() = lowerCeilingMultiple > 0.0 || upperCeilingMultiple != null
    }

    data class SeniorityTier(
        val minimumSeniorityMonths: Int,
        val bands: List<Band>
    ) {
        fun structurallyValid(): Boolean {
            if (minimumSeniorityMonths < 0 || bands.isEmpty() || bands.any { !it.structurallyValid() }) return false
            val sorted = bands.sortedBy { it.lowerCeilingMultiple }
            sorted.zipWithNext().forEach { (previous, next) ->
                val previousUpper = previous.upperCeilingMultiple ?: return false
                if (next.lowerCeilingMultiple < previousUpper) return false
            }
            return true
        }
    }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        /** CADRE / NON_CADRE lorsque le texte distingue explicitement les statuts. */
        val professionalStatus: String? = null,
        /**
         * Vide = la règle ne dépend pas d'un classement ANI.
         * Sinon une catégorie générique confirmée doit appartenir exactement à cet ensemble.
         */
        val aniCategories: Set<ProtectionCategoryV2.AniCategory> = emptySet(),
        val tiers: List<SeniorityTier>,
        val source: String,
        /** KALITEXT parent exact lorsqu'une preuve KALI a été structurée. */
        val conventionScopeKey: String? = null,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank() || ruleId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true) return false
            if (extensionEffectiveFrom != null && extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) return false
            if (tiers.isEmpty() || tiers.any { !it.structurallyValid() }) return false
            if (tiers.map { it.minimumSeniorityMonths }.distinct().size != tiers.size) return false
            if (aniCategories.any { it !in allowedRuleAniCategories }) return false
            val status = professionalStatus?.trim()?.uppercase()
            if (status != null && status !in setOf("CADRE", "NON_CADRE")) return false
            val scope = conventionScopeKey?.trim()?.uppercase()
            if (scope != null && !scope.matches(Regex("^KALITEXT\\d+$"))) return false
            return true
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun statusMatches(value: String?): Boolean {
            val wanted = professionalStatus?.trim()?.uppercase()
            return wanted == null || wanted == value?.trim()?.uppercase()
        }

        fun applicableToCompany(companyApplicabilityConfirmed: Boolean, date: LocalDate): Boolean = when (extensionStatus) {
            ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED -> companyApplicabilityConfirmed ||
                extensionEffectiveFrom?.let { !date.isBefore(it) } == true
            ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
            ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN -> false
        }
    }

    data class AppliedBand(
        val label: String,
        val baseAmount: Double,
        val employeeRate: Double,
        val employerRate: Double,
        val employeeAmount: Double,
        val employerAmount: Double
    )

    data class Result(
        val applicable: Boolean,
        val eligibilityConfirmed: Boolean,
        val reliable: Boolean,
        val selectedRule: Rule?,
        val selectedTier: SeniorityTier?,
        val lines: List<AppliedBand>,
        val employeeAmount: Double?,
        val employerAmount: Double?,
        val warnings: List<String>
    )

    fun calculate(
        rules: List<Rule>,
        idcc: String?,
        referenceDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        protectionCategory: ProtectionCategoryV2.Result?,
        seniorityMonths: Int?,
        gross: Double,
        applicableMonthlyCeiling: Double?,
        companyApplicabilityConfirmed: Boolean = false
    ): Result {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (normalized.isBlank()) return unavailable("Prévoyance conventionnelle : IDCC manquant ; aucune cotisation n'est inventée.")
        if (!gross.isFinite() || gross < 0.0) return unavailable("Prévoyance conventionnelle IDCC $normalized : salaire brut invalide.")

        val scoped = rules
            .filter { it.structurallyValid() }
            .filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
            .filter { it.activeOn(referenceDate) }
            .filter { classification.matches(it.classification) }
            .filter { it.statusMatches(professionalStatus) }

        if (scoped.isEmpty()) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : aucune règle de cotisation vérifiée ne correspond exactement à la période, au statut et à la classification."
            )
        }

        val legallyApplicable = scoped.filter { it.applicableToCompany(companyApplicabilityConfirmed, referenceDate) }
        if (legallyApplicable.isEmpty()) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : règle présente mais extension/applicabilité à l'entreprise non démontrée."
            ).copy(applicable = true)
        }

        val potentiallyCategoryDependent = legallyApplicable.any { it.aniCategories.isNotEmpty() }
        if (potentiallyCategoryDependent && protectionCategory?.confirmed != true) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : catégorie ANI vérifiée requise avant de choisir le barème de cotisation."
            ).copy(applicable = true)
        }

        val category = protectionCategory?.aniCategory
        val categoryMatched = legallyApplicable.filter { rule ->
            rule.aniCategories.isEmpty() || category in rule.aniCategories
        }
        if (categoryMatched.isEmpty()) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : aucune règle de cotisation vérifiée ne correspond à la catégorie ANI confirmée du salarié."
            ).copy(applicable = true)
        }

        val selected = select(categoryMatched)
            ?: return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : plusieurs règles de même précision se contredisent ; calcul bloqué."
            ).copy(applicable = true)

        val sortedTiers = selected.tiers.sortedBy { it.minimumSeniorityMonths }
        val needsSeniority = sortedTiers.size > 1 || sortedTiers.single().minimumSeniorityMonths > 0
        if (needsSeniority && seniorityMonths == null) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : ancienneté requise pour sélectionner le taux exact. Source : ${selected.source}."
            ).copy(applicable = true, selectedRule = selected)
        }
        if (seniorityMonths != null && seniorityMonths < 0) {
            return unavailable("Prévoyance conventionnelle IDCC $normalized : ancienneté invalide.")
                .copy(applicable = true, selectedRule = selected)
        }

        val tier = if (seniorityMonths == null) {
            sortedTiers.single()
        } else {
            sortedTiers.filter { seniorityMonths >= it.minimumSeniorityMonths }.maxByOrNull { it.minimumSeniorityMonths }
        }
        if (tier == null) {
            return Result(
                applicable = true,
                eligibilityConfirmed = true,
                reliable = true,
                selectedRule = selected,
                selectedTier = null,
                lines = emptyList(),
                employeeAmount = 0.0,
                employerAmount = 0.0,
                warnings = listOf(
                    "Prévoyance conventionnelle IDCC $normalized : ancienneté inférieure au minimum prévu par la règle vérifiée. Source : ${selected.source}."
                )
            )
        }

        val ceilingRequired = tier.bands.any { it.needsCeiling }
        val ceiling = applicableMonthlyCeiling?.takeIf { it.isFinite() && it > 0.0 }
        if (ceilingRequired && ceiling == null) {
            return unavailable(
                "Prévoyance conventionnelle IDCC $normalized : plafond de Sécurité sociale applicable manquant pour calculer les tranches. Source : ${selected.source}."
            ).copy(applicable = true, selectedRule = selected, selectedTier = tier)
        }

        val lines = tier.bands.sortedBy { it.lowerCeilingMultiple }.mapNotNull { band ->
            val base = when {
                !band.needsCeiling -> gross
                ceiling == null -> null
                else -> {
                    val lower = band.lowerCeilingMultiple * ceiling
                    val upper = band.upperCeilingMultiple?.times(ceiling)
                    (min(gross, upper ?: gross) - min(gross, lower)).coerceAtLeast(0.0)
                }
            } ?: return@mapNotNull null
            if (base <= 0.0) null else AppliedBand(
                label = band.label,
                baseAmount = base,
                employeeRate = band.employeeRate,
                employerRate = band.employerRate,
                employeeAmount = base * band.employeeRate,
                employerAmount = base * band.employerRate
            )
        }

        return Result(
            applicable = true,
            eligibilityConfirmed = true,
            reliable = true,
            selectedRule = selected,
            selectedTier = tier,
            lines = lines,
            employeeAmount = lines.sumOf { it.employeeAmount },
            employerAmount = lines.sumOf { it.employerAmount },
            warnings = listOf("Prévoyance conventionnelle : cotisation calculée uniquement à partir du barème vérifié. Source : ${selected.source}.")
        )
    }

    private fun select(rules: List<Rule>): Rule? {
        if (rules.isEmpty()) return null
        val latestDate = rules.maxOf { it.effectiveFrom }
        val latest = rules.filter { it.effectiveFrom == latestDate }
        fun specificity(rule: Rule) = rule.classification.specificity() +
            (if (rule.professionalStatus == null) 0 else 1) +
            (if (rule.aniCategories.isEmpty()) 0 else 1)
        val maxSpecificity = latest.maxOf(::specificity)
        return latest.filter { specificity(it) == maxSpecificity }.singleOrNull()
    }

    private fun unavailable(reason: String) = Result(
        applicable = false,
        eligibilityConfirmed = false,
        reliable = false,
        selectedRule = null,
        selectedTier = null,
        lines = emptyList(),
        employeeAmount = null,
        employerAmount = null,
        warnings = listOf(reason)
    )

    private val allowedRuleAniCategories = setOf(
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
    )
}
