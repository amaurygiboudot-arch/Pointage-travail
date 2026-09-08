package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/** Garanties de prévoyance d'entreprise structurées depuis un ACCOTEXT officiel. */
object CompanyProvidentBenefitV2 {
    data class Guarantee(
        val family: ConventionProvidentBenefitV2.Family,
        val label: String,
        val formula: ConventionProvidentBenefitV2.Formula,
        val waitingPeriodDays: Int? = null,
        val maximumDurationDays: Int? = null,
        val invalidityCategory: Int? = null,
        val socialSecurityTreatment: ConventionProvidentBenefitV2.SocialSecurityTreatment =
            ConventionProvidentBenefitV2.SocialSecurityTreatment.UNKNOWN
    ) {
        fun structurallyValid(): Boolean {
            if (label.isBlank() || !formula.structurallyValid()) return false
            if (waitingPeriodDays != null && waitingPeriodDays !in 0..3660) return false
            if (maximumDurationDays != null && maximumDurationDays !in 1..36600) return false
            if (invalidityCategory != null && invalidityCategory !in 1..3) return false
            if (family != ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION && invalidityCategory != null) return false
            return when (family) {
                ConventionProvidentBenefitV2.Family.DEATH_CAPITAL ->
                    waitingPeriodDays == null &&
                        socialSecurityTreatment == ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE

                ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT ->
                    waitingPeriodDays != null && socialSecurityTreatment in incomeReplacementTreatments

                ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION ->
                    waitingPeriodDays == null && socialSecurityTreatment in incomeReplacementTreatments

                ConventionProvidentBenefitV2.Family.SPOUSE_PENSION,
                ConventionProvidentBenefitV2.Family.EDUCATION_PENSION ->
                    waitingPeriodDays == null &&
                        socialSecurityTreatment == ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
            }
        }
    }

    data class Rule(
        val agreementId: String,
        val siret: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val minimumSeniorityMonths: Int,
        val guarantee: Guarantee,
        /** Familles observées dans l'accord complet lors de la même analyse. */
        val observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        /** Vrai seulement si chaque occurrence de garantie observée a été structurée sans ambiguïté. */
        val packageComplete: Boolean,
        val evidenceExcerpt: String
    ) {
        fun structurallyValid(): Boolean {
            if (!agreementId.trim().uppercase(Locale.ROOT).matches(Regex("^ACCOTEXT\\d+$"))) return false
            if (siret.filter(Char::isDigit).length != 14) return false
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) return false
            if (classification.isEmpty()) return false
            if (professionalStatus.trim().uppercase(Locale.ROOT) !in setOf("CADRE", "NON_CADRE")) return false
            if (minimumSeniorityMonths !in 0..600) return false
            if (!guarantee.structurallyValid()) return false
            if (observedFamilies.isEmpty() || guarantee.family !in observedFamilies) return false
            if (evidenceExcerpt.isBlank()) return false
            return true
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))
    }

    fun fingerprint(guarantee: Guarantee): String = listOf(
        guarantee.family.name,
        guarantee.formula.basis.name,
        guarantee.formula.coefficient?.toString().orEmpty(),
        guarantee.formula.fixedAmount?.toString().orEmpty(),
        guarantee.waitingPeriodDays?.toString().orEmpty(),
        guarantee.maximumDurationDays?.toString().orEmpty(),
        guarantee.invalidityCategory?.toString().orEmpty(),
        guarantee.socialSecurityTreatment.name
    ).joinToString("|")

    fun fingerprint(guarantee: ConventionProvidentBenefitV2.Guarantee): String = listOf(
        guarantee.family.name,
        guarantee.formula.basis.name,
        guarantee.formula.coefficient?.toString().orEmpty(),
        guarantee.formula.fixedAmount?.toString().orEmpty(),
        guarantee.waitingPeriodDays?.toString().orEmpty(),
        guarantee.maximumDurationDays?.toString().orEmpty(),
        guarantee.invalidityCategory?.toString().orEmpty(),
        guarantee.socialSecurityTreatment.name
    ).joinToString("|")

    private val incomeReplacementTreatments = setOf(
        ConventionProvidentBenefitV2.SocialSecurityTreatment.ADDITIONAL_TO_SOCIAL_SECURITY,
        ConventionProvidentBenefitV2.SocialSecurityTreatment.INCLUDED_IN_TARGET_TOTAL,
        ConventionProvidentBenefitV2.SocialSecurityTreatment.DEDUCT_SOCIAL_SECURITY
    )
}
