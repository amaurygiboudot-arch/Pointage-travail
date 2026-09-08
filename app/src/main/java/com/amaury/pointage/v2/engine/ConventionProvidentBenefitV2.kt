package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Garanties conventionnelles de prévoyance, génériques, datées et classifiées.
 *
 * Cette couche décrit uniquement des droits textuellement vérifiés. Elle ne transforme jamais
 * une garantie en montant payable si la formule complète n'est pas connue. Décès, incapacité,
 * invalidité et rentes restent des familles distinctes pour éviter les raccourcis juridiques.
 */
object ConventionProvidentBenefitV2 {
    enum class Family {
        DEATH_CAPITAL,
        INCAPACITY_INCOME_REPLACEMENT,
        INVALIDITY_PENSION,
        SPOUSE_PENSION,
        EDUCATION_PENSION
    }

    enum class Basis {
        ANNUAL_REFERENCE_SALARY,
        MONTHLY_REFERENCE_SALARY,
        PMSS,
        FIXED_EURO
    }

    enum class SocialSecurityTreatment {
        /** Le texte exploité ne permet pas de qualifier l'articulation avec la Sécurité sociale. */
        UNKNOWN,
        /** Cette famille n'est pas une prestation de remplacement liée aux prestations sociales. */
        NOT_APPLICABLE,
        /** La garantie s'ajoute explicitement aux prestations de Sécurité sociale. */
        ADDITIONAL_TO_SOCIAL_SECURITY,
        /** Le niveau annoncé inclut explicitement les prestations de Sécurité sociale. */
        INCLUDED_IN_TARGET_TOTAL,
        /** Les prestations de Sécurité sociale sont explicitement déduites du montant conventionnel. */
        DEDUCT_SOCIAL_SECURITY
    }

    /**
     * Formule exacte telle que prouvée par le texte : coefficient 1.0 = 100 % de l'assiette,
     * coefficient 2.0 = 200 %, etc. Pour FIXED_EURO, fixedAmount doit être utilisé à la place.
     */
    data class Formula(
        val basis: Basis,
        val coefficient: Double? = null,
        val fixedAmount: Double? = null
    ) {
        fun structurallyValid(): Boolean = when (basis) {
            Basis.FIXED_EURO -> fixedAmount?.let { it.isFinite() && it >= 0.0 } == true && coefficient == null
            else -> coefficient?.let { it.isFinite() && it > 0.0 && it <= 100.0 } == true && fixedAmount == null
        }
    }

    data class Guarantee(
        val family: Family,
        val label: String,
        val formula: Formula,
        /** Franchise/carence explicitement prouvée. Pour l'incapacité, null signifie inconnue. */
        val waitingPeriodDays: Int? = null,
        /** Durée maximale explicitement prouvée ; null = aucune durée maximale structurée ici. */
        val maximumDurationDays: Int? = null,
        /** Catégorie d'invalidité 1/2/3 lorsqu'elle est explicitement visée. */
        val invalidityCategory: Int? = null,
        val socialSecurityTreatment: SocialSecurityTreatment = SocialSecurityTreatment.UNKNOWN,
        /** Article(s) KALI exact(s) ayant prouvé cette garantie. */
        val evidenceArticleIds: Set<String>
    ) {
        fun structurallyValid(): Boolean {
            if (label.isBlank() || !formula.structurallyValid() || evidenceArticleIds.isEmpty()) return false
            if (waitingPeriodDays != null && waitingPeriodDays !in 0..3660) return false
            if (maximumDurationDays != null && maximumDurationDays !in 1..36600) return false
            if (invalidityCategory != null && invalidityCategory !in 1..3) return false
            if (evidenceArticleIds.any { !it.trim().uppercase().matches(Regex("^KALIARTI\\d+$")) }) return false
            if (family != Family.INVALIDITY_PENSION && invalidityCategory != null) return false

            return when (family) {
                Family.DEATH_CAPITAL ->
                    waitingPeriodDays == null && socialSecurityTreatment == SocialSecurityTreatment.NOT_APPLICABLE
                Family.INCAPACITY_INCOME_REPLACEMENT ->
                    waitingPeriodDays != null && socialSecurityTreatment in incomeReplacementTreatments
                Family.INVALIDITY_PENSION ->
                    waitingPeriodDays == null && socialSecurityTreatment in incomeReplacementTreatments
                Family.SPOUSE_PENSION,
                Family.EDUCATION_PENSION ->
                    waitingPeriodDays == null && socialSecurityTreatment == SocialSecurityTreatment.NOT_APPLICABLE
            }
        }
    }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        val professionalStatus: String? = null,
        val aniCategories: Set<ProtectionCategoryV2.AniCategory> = emptySet(),
        val minimumSeniorityMonths: Int = 0,
        val guarantees: List<Guarantee>,
        val source: String,
        val conventionScopeKey: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank()) return false
            if (ruleId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true) return false
            if (minimumSeniorityMonths !in 0..600) return false
            if (guarantees.isEmpty() || guarantees.any { !it.structurallyValid() }) return false
            if (guarantees.distinctBy { it.family to it.invalidityCategory }.size != guarantees.size) return false
            if (!conventionScopeKey.trim().uppercase().matches(Regex("^KALITEXT\\d+$"))) return false
            val status = professionalStatus?.trim()?.uppercase()
            if (status != null && status !in setOf("CADRE", "NON_CADRE")) return false
            if (aniCategories.any { it !in allowedAniCategories }) return false
            if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED && extensionEffectiveFrom == null) return false
            if (extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED && extensionEffectiveFrom != null) return false
            return true
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun statusMatches(value: String?): Boolean {
            val wanted = professionalStatus?.trim()?.uppercase()
            return wanted == null || wanted == value?.trim()?.uppercase()
        }

        fun extensionApplicableOn(date: LocalDate): Boolean =
            extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                extensionEffectiveFrom?.let { !date.isBefore(it) } == true
    }

    data class Resolution(
        val guarantees: List<Guarantee>,
        val selectedRules: List<Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    private data class Candidate(val rule: Rule, val guarantee: Guarantee)

    fun resolve(
        rules: List<Rule>,
        idcc: String?,
        referenceDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        protectionCategory: ProtectionCategoryV2.Result?,
        seniorityMonths: Int?
    ): Resolution {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (normalized.isBlank()) return unresolved("IDCC manquant")
        if (classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        if (seniorityMonths != null && seniorityMonths < 0) return unresolved("ancienneté invalide")

        val scoped = rules.filter { rule ->
            rule.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == normalized &&
                rule.activeOn(referenceDate) &&
                classification.matches(rule.classification) &&
                rule.classification.matches(classification) &&
                rule.statusMatches(professionalStatus)
        }
        if (scoped.isEmpty()) return unresolved("aucune garantie vérifiée ne correspond exactement au profil et à la période")

        val extended = scoped.filter { it.extensionApplicableOn(referenceDate) }
        if (extended.isEmpty()) return unresolved("extension officielle des garanties non démontrée à cette date")

        val categoryDependent = extended.any { it.aniCategories.isNotEmpty() }
        if (categoryDependent && protectionCategory?.confirmed != true) {
            return unresolved("catégorie ANI vérifiée requise avant de sélectionner les garanties")
        }
        val category = protectionCategory?.aniCategory
        val categoryMatched = extended.filter { it.aniCategories.isEmpty() || category in it.aniCategories }
        if (categoryMatched.isEmpty()) return unresolved("aucune garantie vérifiée ne correspond à la catégorie ANI confirmée")

        val seniorityMatched = if (seniorityMonths == null) {
            if (categoryMatched.any { it.minimumSeniorityMonths > 0 }) {
                return unresolved("ancienneté requise pour confirmer l'ouverture des garanties")
            }
            categoryMatched
        } else {
            categoryMatched.filter { seniorityMonths >= it.minimumSeniorityMonths }
        }
        if (seniorityMatched.isEmpty()) {
            return Resolution(
                guarantees = emptyList(),
                selectedRules = emptyList(),
                reliable = true,
                warnings = listOf("Prévoyance conventionnelle IDCC $normalized : ancienneté insuffisante pour les garanties vérifiées à cette date.")
            )
        }

        val candidates = seniorityMatched.flatMap { rule -> rule.guarantees.map { Candidate(rule, it) } }
        val selectedGuarantees = mutableListOf<Guarantee>()
        val selectedRules = mutableListOf<Rule>()

        candidates.groupBy { it.guarantee.family to it.guarantee.invalidityCategory }
            .toSortedMap(compareBy({ it.first.name }, { it.second ?: 0 }))
            .forEach { (key, familyCandidates) ->
                val latestDate = familyCandidates.maxOf { it.rule.effectiveFrom }
                val latest = familyCandidates.filter { it.rule.effectiveFrom == latestDate }
                val maxSpecificity = latest.maxOf { specificity(it.rule) }
                val mostSpecific = latest.filter { specificity(it.rule) == maxSpecificity }
                val maxEligibleSeniority = mostSpecific.maxOf { it.rule.minimumSeniorityMonths }
                val best = mostSpecific.filter { it.rule.minimumSeniorityMonths == maxEligibleSeniority }
                val fingerprints = best.map { guaranteeFingerprint(it.guarantee) }.distinct()
                if (fingerprints.size != 1) {
                    return unresolved(
                        "plusieurs garanties ${key.first.name}${key.second?.let { " catégorie $it" }.orEmpty()} de même précision et ancienneté se contredisent"
                    )
                }
                val merged = best.first().guarantee.copy(
                    evidenceArticleIds = best.flatMap { it.guarantee.evidenceArticleIds }.toSet()
                )
                selectedGuarantees += merged
                selectedRules += best.map { it.rule }
            }

        return Resolution(
            guarantees = selectedGuarantees,
            selectedRules = selectedRules.distinctBy { it.ruleId },
            reliable = true,
            warnings = listOf(
                "Garanties de prévoyance issues uniquement de ${selectedRules.distinctBy { it.ruleId }.size} règle(s) conventionnelle(s) vérifiée(s)."
            )
        )
    }

    private fun specificity(rule: Rule) = rule.classification.specificity() +
        (if (rule.professionalStatus == null) 0 else 1) +
        (if (rule.aniCategories.isEmpty()) 0 else 1)

    private fun guaranteeFingerprint(value: Guarantee): String = listOf(
        value.family.name,
        value.formula.basis.name,
        value.formula.coefficient?.toString().orEmpty(),
        value.formula.fixedAmount?.toString().orEmpty(),
        value.waitingPeriodDays?.toString().orEmpty(),
        value.maximumDurationDays?.toString().orEmpty(),
        value.invalidityCategory?.toString().orEmpty(),
        value.socialSecurityTreatment.name
    ).joinToString("|")

    private fun unresolved(reason: String) = Resolution(
        guarantees = emptyList(),
        selectedRules = emptyList(),
        reliable = false,
        warnings = listOf("Garanties de prévoyance : $reason ; aucun droit n'est inventé.")
    )

    private val allowedAniCategories = setOf(
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
    )

    private val incomeReplacementTreatments = setOf(
        SocialSecurityTreatment.ADDITIONAL_TO_SOCIAL_SECURITY,
        SocialSecurityTreatment.INCLUDED_IN_TARGET_TOTAL,
        SocialSecurityTreatment.DEDUCT_SOCIAL_SECURITY
    )
}
