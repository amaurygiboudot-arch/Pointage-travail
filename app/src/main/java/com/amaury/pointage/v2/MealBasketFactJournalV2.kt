package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.LocalDate

/**
 * Journal factuel local des informations nécessaires aux règles de panier.
 *
 * Ce modèle ne déduit aucune règle juridique et ne transforme jamais un signal GPS/horaire en fait
 * métier. Il arbitre uniquement des faits déjà enregistrés avec leur provenance et leur statut.
 * Seuls les faits CONFIRMED peuvent alimenter le calcul salarial.
 */
object MealBasketFactJournalV2 {
    enum class Key {
        POSTED_SHIFT_WORKER,
        CAN_RETURN_HOME_FOR_MEAL,
        WORKS_AWAY_FROM_USUAL_WORKPLACE,
        MUST_EAT_AT_WORKPLACE,
        COMPANY_CANTEEN_AVAILABLE,
        EMPLOYER_MEAL_PROVIDED,
        MEAL_VOUCHER_PROVIDED,
        OTHER_SAME_NATURE_MEAL_BENEFIT,
        EMPLOYER_NIGHT_WINDOW
    }

    enum class Source {
        USER_CONFIRMED,
        COMPANY_CONFIGURATION,
        SESSION_EVENT,
        WORK_FACTS_ENGINE,
        IMPORT
    }

    enum class Scope(val rank: Int) {
        COMPANY(1),
        DAY(2),
        SESSION(3)
    }

    sealed interface Value {
        data class Flag(val value: Boolean) : Value
        data class NightWindow(val window: ConventionMealBasketV2.DailyWindow) : Value
    }

    data class Entry(
        val id: String,
        val companyId: String,
        val scope: Scope,
        val key: Key,
        val value: Value,
        val source: Source,
        val status: DecisionStatusV2,
        val recordedAtMs: Long,
        val dayEpochDay: Long? = null,
        val sessionId: String? = null
    ) {
        fun structurallyValid(): Boolean {
            if (id.isBlank() || companyId.isBlank() || recordedAtMs < 0L) return false
            val scopeValid = when (scope) {
                Scope.COMPANY -> dayEpochDay == null && sessionId == null
                Scope.DAY -> dayEpochDay != null && sessionId == null
                Scope.SESSION -> dayEpochDay == null && !sessionId.isNullOrBlank()
            }
            if (!scopeValid) return false
            return when (key) {
                Key.EMPLOYER_NIGHT_WINDOW ->
                    value is Value.NightWindow && value.window.structurallyValid()
                else -> value is Value.Flag
            }
        }
    }

    data class Resolution(
        val facts: VerifiedMealBasketPayrollV2.FactDefaults,
        val warnings: List<String>
    )

    fun resolve(
        entries: List<Entry>,
        companyId: String,
        day: LocalDate,
        sessionId: String
    ): Resolution {
        if (companyId.isBlank() || sessionId.isBlank()) {
            return Resolution(
                facts = VerifiedMealBasketPayrollV2.FactDefaults(),
                warnings = listOf("Panier : identité entreprise/session manquante pour résoudre les faits locaux.")
            )
        }

        val warnings = mutableListOf<String>()
        val companyEntries = entries.filter { it.companyId == companyId }
        val invalid = companyEntries.count { !it.structurallyValid() }
        if (invalid > 0) {
            warnings += "Panier : $invalid fait(s) local(aux) invalide(s) ignoré(s) ; aucune valeur n'est inventée."
        }

        val candidates = companyEntries.filter { entry ->
            entry.structurallyValid() &&
                entry.status == DecisionStatusV2.CONFIRMED &&
                when (entry.scope) {
                    Scope.COMPANY -> true
                    Scope.DAY -> entry.dayEpochDay == day.toEpochDay()
                    Scope.SESSION -> entry.sessionId == sessionId
                }
        }

        fun valueFor(key: Key): Value? {
            val matching = candidates.filter { it.key == key }
            if (matching.isEmpty()) return null
            val highestRank = matching.maxOf { it.scope.rank }
            val strongest = matching.filter { it.scope.rank == highestRank }
            val values = strongest.map { it.value }.distinct()
            if (values.size != 1) {
                warnings += "Panier : faits confirmés contradictoires pour ${key.name} au scope ${strongest.first().scope.name} ; valeur laissée inconnue."
                return null
            }
            return values.single()
        }

        fun flag(key: Key): Boolean? = (valueFor(key) as? Value.Flag)?.value
        fun window(key: Key): ConventionMealBasketV2.DailyWindow? =
            (valueFor(key) as? Value.NightWindow)?.window

        return Resolution(
            facts = VerifiedMealBasketPayrollV2.FactDefaults(
                postedShiftWorker = flag(Key.POSTED_SHIFT_WORKER),
                canReturnHomeForMeal = flag(Key.CAN_RETURN_HOME_FOR_MEAL),
                worksAwayFromUsualWorkplace = flag(Key.WORKS_AWAY_FROM_USUAL_WORKPLACE),
                mustEatAtWorkplace = flag(Key.MUST_EAT_AT_WORKPLACE),
                companyCanteenAvailable = flag(Key.COMPANY_CANTEEN_AVAILABLE),
                employerMealProvided = flag(Key.EMPLOYER_MEAL_PROVIDED),
                mealVoucherProvided = flag(Key.MEAL_VOUCHER_PROVIDED),
                otherSameNatureMealBenefit = flag(Key.OTHER_SAME_NATURE_MEAL_BENEFIT),
                employerNightWindow = window(Key.EMPLOYER_NIGHT_WINDOW)
            ),
            warnings = warnings.distinct()
        )
    }

    /** Les faits spécifiques remplacent uniquement les valeurs effectivement connues. */
    fun overlay(
        fallback: VerifiedMealBasketPayrollV2.FactDefaults,
        specific: VerifiedMealBasketPayrollV2.FactDefaults
    ) = VerifiedMealBasketPayrollV2.FactDefaults(
        postedShiftWorker = specific.postedShiftWorker ?: fallback.postedShiftWorker,
        canReturnHomeForMeal = specific.canReturnHomeForMeal ?: fallback.canReturnHomeForMeal,
        worksAwayFromUsualWorkplace = specific.worksAwayFromUsualWorkplace ?: fallback.worksAwayFromUsualWorkplace,
        mustEatAtWorkplace = specific.mustEatAtWorkplace ?: fallback.mustEatAtWorkplace,
        companyCanteenAvailable = specific.companyCanteenAvailable ?: fallback.companyCanteenAvailable,
        employerMealProvided = specific.employerMealProvided ?: fallback.employerMealProvided,
        mealVoucherProvided = specific.mealVoucherProvided ?: fallback.mealVoucherProvided,
        otherSameNatureMealBenefit = specific.otherSameNatureMealBenefit ?: fallback.otherSameNatureMealBenefit,
        employerNightWindow = specific.employerNightWindow ?: fallback.employerNightWindow
    )
}
