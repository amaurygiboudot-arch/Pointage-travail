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
        val warnings: List<String>,
        /** Une clé bloquée ne doit jamais retomber sur un fallback moins précis. */
        val blockedKeys: Set<Key> = emptySet()
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
                warnings = listOf("Panier : identité entreprise/session manquante pour résoudre les faits locaux."),
                blockedKeys = Key.entries.toSet()
            )
        }

        val warnings = mutableListOf<String>()
        val blockedKeys = linkedSetOf<Key>()
        val companyEntries = entries.filter { it.companyId == companyId }
        val invalid = companyEntries.count { !it.structurallyValid() }
        if (invalid > 0) {
            warnings += "Panier : $invalid fait(s) local(aux) invalide(s) détecté(s) ; toute clé concernée reste inconnue."
        }

        // La portée est déterminée avant le statut. Un fait DAY/SESSION TO_CONFIRM doit donc masquer
        // un ancien fait COMPANY confirmé, sinon une incertitude plus précise serait contournée.
        val applicable = companyEntries.filter { entry ->
            when (entry.scope) {
                Scope.COMPANY -> true
                Scope.DAY -> entry.dayEpochDay == day.toEpochDay()
                Scope.SESSION -> entry.sessionId == sessionId
            }
        }

        fun valueFor(key: Key): Value? {
            val matching = applicable.filter { it.key == key }
            if (matching.isEmpty()) return null
            val highestRank = matching.maxOf { it.scope.rank }
            val strongest = matching.filter { it.scope.rank == highestRank }
            val scope = strongest.first().scope

            if (strongest.any { !it.structurallyValid() }) {
                blockedKeys += key
                warnings += "Panier : fait ${key.name} invalide au scope ${scope.name} ; fallback moins précis interdit."
                return null
            }
            if (strongest.any { it.status != DecisionStatusV2.CONFIRMED }) {
                blockedKeys += key
                warnings += "Panier : fait ${key.name} à confirmer au scope ${scope.name} ; fallback moins précis interdit."
                return null
            }

            val values = strongest.map { it.value }.distinct()
            if (values.size != 1) {
                blockedKeys += key
                warnings += "Panier : faits confirmés contradictoires pour ${key.name} au scope ${scope.name} ; valeur laissée inconnue."
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
            warnings = warnings.distinct(),
            blockedKeys = blockedKeys
        )
    }

    /**
     * Les faits spécifiques remplacent les fallbacks. Une clé explicitement bloquée par une
     * information plus précise reste null : l'incertitude ne peut pas être contournée.
     */
    fun overlay(
        fallback: VerifiedMealBasketPayrollV2.FactDefaults,
        resolution: Resolution
    ): VerifiedMealBasketPayrollV2.FactDefaults {
        fun flag(key: Key, specific: Boolean?, fallbackValue: Boolean?): Boolean? = when {
            key in resolution.blockedKeys -> null
            specific != null -> specific
            else -> fallbackValue
        }
        fun window(
            key: Key,
            specific: ConventionMealBasketV2.DailyWindow?,
            fallbackValue: ConventionMealBasketV2.DailyWindow?
        ): ConventionMealBasketV2.DailyWindow? = when {
            key in resolution.blockedKeys -> null
            specific != null -> specific
            else -> fallbackValue
        }

        val specific = resolution.facts
        return VerifiedMealBasketPayrollV2.FactDefaults(
            postedShiftWorker = flag(Key.POSTED_SHIFT_WORKER, specific.postedShiftWorker, fallback.postedShiftWorker),
            canReturnHomeForMeal = flag(Key.CAN_RETURN_HOME_FOR_MEAL, specific.canReturnHomeForMeal, fallback.canReturnHomeForMeal),
            worksAwayFromUsualWorkplace = flag(Key.WORKS_AWAY_FROM_USUAL_WORKPLACE, specific.worksAwayFromUsualWorkplace, fallback.worksAwayFromUsualWorkplace),
            mustEatAtWorkplace = flag(Key.MUST_EAT_AT_WORKPLACE, specific.mustEatAtWorkplace, fallback.mustEatAtWorkplace),
            companyCanteenAvailable = flag(Key.COMPANY_CANTEEN_AVAILABLE, specific.companyCanteenAvailable, fallback.companyCanteenAvailable),
            employerMealProvided = flag(Key.EMPLOYER_MEAL_PROVIDED, specific.employerMealProvided, fallback.employerMealProvided),
            mealVoucherProvided = flag(Key.MEAL_VOUCHER_PROVIDED, specific.mealVoucherProvided, fallback.mealVoucherProvided),
            otherSameNatureMealBenefit = flag(Key.OTHER_SAME_NATURE_MEAL_BENEFIT, specific.otherSameNatureMealBenefit, fallback.otherSameNatureMealBenefit),
            employerNightWindow = window(Key.EMPLOYER_NIGHT_WINDOW, specific.employerNightWindow, fallback.employerNightWindow)
        )
    }
}
