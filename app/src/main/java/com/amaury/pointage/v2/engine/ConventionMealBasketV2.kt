package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/**
 * Modèle juridique générique des paniers / indemnités repas.
 *
 * Aucune règle de branche n'est codée ici. Le modèle décrit seulement des règles déjà prouvées
 * par une source officielle : périmètre salarié, période, montant, conditions, non-cumul et preuve.
 */
object ConventionMealBasketV2 {
    enum class DeliveryMode {
        CASH_ALLOWANCE,
        /** Repas fourni par l'employeur ou, à défaut, indemnité monétaire. */
        EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED
    }

    enum class CountingUnit { WORKED_DAY, SHIFT }

    enum class Blocker {
        COMPANY_CANTEEN,
        EMPLOYER_PROVIDED_MEAL,
        MEAL_VOUCHER,
        OTHER_SAME_NATURE_MEAL_BENEFIT
    }

    sealed interface AmountFormula {
        fun structurallyValid(): Boolean

        data class FixedEuro(val amount: Double) : AmountFormula {
            override fun structurallyValid() = amount.isFinite() && amount >= 0.0
        }

        data class MinimumGuaranteedMultiple(val multiplier: Double) : AmountFormula {
            override fun structurallyValid() = multiplier.isFinite() && multiplier > 0.0 && multiplier <= 100.0
        }

        /** Le texte renvoie le montant à un autre accord applicable. */
        data object ExternalAgreementAmount : AmountFormula {
            override fun structurallyValid() = true
        }
    }

    /** Plage quotidienne [début, fin), en minutes depuis minuit ; elle peut traverser minuit. */
    data class DailyWindow(val startMinute: Int, val endMinute: Int) {
        fun structurallyValid() = startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute
        val crossesMidnight get() = endMinute < startMinute
        fun durationMinutes(): Int = when {
            !structurallyValid() -> 0
            endMinute > startMinute -> endMinute - startMinute
            else -> 1440 - startMinute + endMinute
        }

        fun containsMinute(minute: Int): Boolean {
            if (!structurallyValid() || minute !in 0..1439) return false
            return if (crossesMidnight) minute >= startMinute || minute < endMinute
            else minute in startMinute until endMinute
        }

        fun containedIn(other: DailyWindow): Boolean {
            if (!structurallyValid() || !other.structurallyValid()) return false
            return (0..1439).all { minute -> !containsMinute(minute) || other.containsMinute(minute) }
        }
    }

    /** Conditions atomiques. Les conditions d'un groupe sont cumulatives ; les groupes sont alternatifs. */
    sealed interface Condition {
        fun structurallyValid(): Boolean

        data object WorkedDay : Condition { override fun structurallyValid() = true }
        data object PostedShiftWorker : Condition { override fun structurallyValid() = true }
        data object UnableToReturnHomeForMeal : Condition { override fun structurallyValid() = true }
        data object WorksAwayFromUsualWorkplace : Condition { override fun structurallyValid() = true }
        data object MustEatAtWorkplace : Condition { override fun structurallyValid() = true }
        data object ShiftEnclosesMidnight : Condition { override fun structurallyValid() = true }
        data object ShiftStartsAtMidnight : Condition { override fun structurallyValid() = true }

        data class MinimumEffectiveMinutesInFixedWindow(
            val window: DailyWindow,
            val minimumMinutes: Int
        ) : Condition {
            override fun structurallyValid() = window.structurallyValid() && minimumMinutes in 1..window.durationMinutes()
        }

        /** Ex. 4 h dans la plage de 8 h retenue par l'employeur entre 21 h et 6 h. */
        data class MinimumEffectiveMinutesInEmployerWindow(
            val allowedEnvelope: DailyWindow,
            val requiredWindowMinutes: Int,
            val minimumEffectiveMinutes: Int
        ) : Condition {
            override fun structurallyValid() = allowedEnvelope.structurallyValid() &&
                requiredWindowMinutes in 1..allowedEnvelope.durationMinutes() &&
                minimumEffectiveMinutes in 1..requiredWindowMinutes
        }

        data class ShiftStartsOrEndsInWindow(val window: DailyWindow) : Condition {
            override fun structurallyValid() = window.structurallyValid()
        }
    }

    data class EligibilityGroup(val allOf: List<Condition>) {
        fun structurallyValid() = allOf.isNotEmpty() && allOf.all { it.structurallyValid() }
    }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        /** Identifie séparément panier de jour, panier de nuit, indemnité chantier, etc. */
        val benefitId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        val professionalStatus: String? = null,
        /** Vide = portée nationale de la convention ; sinon codes territoriaux explicitement prouvés. */
        val territoryCodes: Set<String> = emptySet(),
        /** Emplois explicitement exclus par le texte, ex. gardiens/veilleurs. */
        val excludedEmployments: Set<String> = emptySet(),
        val deliveryMode: DeliveryMode,
        val amountFormula: AmountFormula,
        val eligibilityAnyOf: List<EligibilityGroup>,
        val blockers: Set<Blocker> = emptySet(),
        val countingUnit: CountingUnit = CountingUnit.SHIFT,
        val maxAwardsPerCalendarDay: Int = 1,
        val source: String,
        val conventionScopeKey: String,
        val evidenceArticleIds: Set<String>,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank()) return false
            if (ruleId.isBlank() || benefitId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true) return false
            if (!amountFormula.structurallyValid()) return false
            if (eligibilityAnyOf.isEmpty() || eligibilityAnyOf.any { !it.structurallyValid() }) return false
            if (maxAwardsPerCalendarDay !in 1..24) return false
            if (!conventionScopeKey.trim().uppercase(Locale.ROOT).matches(kaliTextIdRegex)) return false
            if (evidenceArticleIds.isEmpty() || evidenceArticleIds.any {
                    !it.trim().uppercase(Locale.ROOT).matches(kaliArticleIdRegex)
                }) return false
            val status = professionalStatus?.trim()?.uppercase(Locale.ROOT)
            if (status != null && status !in setOf("CADRE", "NON_CADRE")) return false
            if (territoryCodes.any { it.isBlank() } || excludedEmployments.any { it.isBlank() }) return false
            if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED && extensionEffectiveFrom == null) return false
            if (extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED && extensionEffectiveFrom != null) return false
            return true
        }

        fun activeOn(date: LocalDate) = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun extensionApplicableOn(date: LocalDate, companyApplicabilityConfirmed: Boolean = false): Boolean =
            when (extensionStatus) {
                ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED -> companyApplicabilityConfirmed ||
                    extensionEffectiveFrom?.let { !date.isBefore(it) } == true
                ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
                ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN -> false
            }
    }

    data class ScopeResult(
        val rules: List<Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    /**
     * Filtre uniquement le périmètre juridique. Les conditions de journée sont évaluées séparément.
     * Une règle territoriale ou une exclusion d'emploi sans donnée locale correspondante bloque.
     */
    fun scopeRules(
        rules: List<Rule>,
        idcc: String?,
        referenceDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        territoryCode: String?,
        companyApplicabilityConfirmed: Boolean = false
    ): ScopeResult {
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (normalizedIdcc.isBlank()) return unresolved("IDCC manquant")
        if (classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")

        val base = rules.filter { rule ->
            rule.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == normalizedIdcc &&
                rule.activeOn(referenceDate) &&
                classification.matches(rule.classification) &&
                rule.classification.matches(classification) &&
                (rule.professionalStatus == null ||
                    rule.professionalStatus.trim().uppercase(Locale.ROOT) == professionalStatus?.trim()?.uppercase(Locale.ROOT))
        }
        if (base.isEmpty()) return unresolved("aucune règle repas vérifiée ne correspond au profil et à la période")

        val applicable = base.filter { it.extensionApplicableOn(referenceDate, companyApplicabilityConfirmed) }
        if (applicable.isEmpty()) return unresolved("extension/applicabilité des règles repas non démontrée")

        if (applicable.any { it.territoryCodes.isNotEmpty() } && territoryCode.isNullOrBlank()) {
            return unresolved("territoire employeur requis pour départager des règles repas locales")
        }
        val territory = territoryCode?.trim()?.uppercase(Locale.ROOT)
        val territorial = applicable.filter { rule ->
            rule.territoryCodes.isEmpty() || territory in rule.territoryCodes.map { it.trim().uppercase(Locale.ROOT) }
        }
        if (territorial.isEmpty()) return ScopeResult(emptyList(), true, listOf("Aucune règle repas vérifiée pour le territoire confirmé."))

        val employment = classification.employment?.trim()?.let(::normalizeText)
        if (territorial.any { it.excludedEmployments.isNotEmpty() } && employment == null) {
            return unresolved("emploi exact requis pour contrôler une exclusion professionnelle")
        }
        val notExcluded = territorial.filterNot { rule ->
            employment != null && employment in rule.excludedEmployments.map(::normalizeText).toSet()
        }
        return ScopeResult(
            rules = notExcluded,
            reliable = true,
            warnings = if (notExcluded.isEmpty()) listOf("Profil explicitement exclu des règles repas vérifiées.") else emptyList()
        )
    }

    private fun unresolved(reason: String) = ScopeResult(
        emptyList(),
        false,
        listOf("Panier / indemnité repas : $reason ; aucun droit n'est inventé.")
    )

    private fun normalizeText(value: String) = value.lowercase(Locale.FRANCE).replace(Regex("\\s+"), " ").trim()
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
}
